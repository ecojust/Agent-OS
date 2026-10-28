package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.security.KeyStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real Keystore and private test namespace; no UI, model request or device action. */
@RunWith(AndroidJUnit4.class)
public final class SettingsRecoveryInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("non_visual_settings_recovery_test");
    private static final String CIPHER = "encrypted_settings", REVISION = "configuration_revision";
    private static final String KEY = "synthetic-recovery-credential", MODEL = "synthetic-recovery-model";
    private Context context;
    private String namespace, preferencesName, alias;
    private SharedPreferences preferences;
    private SettingsStore store;
    private final AtomicBoolean failAfterCommit = new AtomicBoolean();
    private final AtomicInteger commits = new AtomicInteger();

    @Before public void createIsolatedStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        namespace = "recovery_" + UUID.randomUUID().toString().replace("-", "");
        String profile = SettingsStore.currentProfileId(context);
        preferencesName = "agent_settings_secure_" + profile.replace(':', '_') + "__" + namespace;
        alias = "dev.agentos.settings.v1." + profile + "." + namespace;
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        store = new SettingsStore(context, namespace, editor -> {
            commits.incrementAndGet();
            boolean persisted = editor.commit();
            return !failAfterCommit.getAndSet(false) && persisted;
        });
    }

    @After public void removeOnlyIsolatedResources() throws Exception {
        failAfterCommit.set(false);
        Throwable failure = null;
        try { if (store != null) store.reset(); }
        catch (Exception | AssertionError cleanup) { failure = cleanup; }
        try {
            if (alias != null) {
                KeyStore keys = keys();
                if (keys.containsAlias(alias)) keys.deleteEntry(alias);
                assertFalse(keys.containsAlias(alias));
            }
        } catch (Exception | AssertionError cleanup) {
            if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
        }
        try {
            if (preferencesName != null) assertTrue(context.deleteSharedPreferences(preferencesName));
        } catch (Exception | AssertionError cleanup) {
            if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
        }
        if (failure instanceof AssertionError) throw (AssertionError) failure;
        if (failure != null) throw (Exception) failure;
    }

    @Test public void futureFormatPreservesOriginalBytesAndRestoringDataIssuesOnlyANewLease() throws Exception {
        SettingsStore.SavedSettings settings = sample();
        SettingsStore.ConfigurationLease originalLease = store.save(settings);
        String originalCipher = preferences.getString(CIPHER, null);
        String originalRevision = preferences.getString(REVISION, null);
        String future = envelopeWithVersion(originalCipher, "4294967297");
        assertTrue(preferences.edit().putString(CIPHER, future).commit());
        Map<String, ?> rejected = preferences.getAll();
        int priorCommits = commits.get();
        AtomicInteger invalidations = new AtomicInteger();
        try (SettingsStore.Registration ignored = store.watch(originalLease, invalidations::incrementAndGet)) {
            SettingsStore.LoadException failure = assertThrows(SettingsStore.LoadException.class, store::loadWithLease);
            assertEquals(SettingsStore.LoadFailureReason.UNSUPPORTED_FORMAT, failure.reason);
            assertSafeFailure(failure);
            assertEquals(1, invalidations.get());
            assertFalse(SettingsStore.isCurrent(originalLease));
            assertEquals(rejected, preferences.getAll());
            assertEquals(priorCommits, commits.get());
            assertTrue(keys().containsAlias(alias));
            assertThrows(IOException.class, () -> store.save(settings));
            assertEquals(rejected, preferences.getAll());
            assertEquals(priorCommits, commits.get());

            // A controlled fixture restores the original encrypted bytes; the
            // product reload neither repairs nor rewrites an unknown format.
            assertTrue(preferences.edit().putString(CIPHER, originalCipher).putString(REVISION, originalRevision).commit());
            SettingsStore.LoadedSettings restored = reopened().loadWithLease();
            assertEquals(KEY, restored.settings.apiKey);
            assertEquals(MODEL, restored.settings.model);
            assertNotNull(restored.lease);
            assertTrue(SettingsStore.isCurrent(restored.lease));
            assertFalse(SettingsStore.isCurrent(originalLease));
            assertNotEquals(originalLease.configurationToken, restored.lease.configurationToken);
            assertEquals(1, invalidations.get());
            assertEquals(originalCipher, preferences.getString(CIPHER, null));
            assertEquals(originalRevision, preferences.getString(REVISION, null));
            assertEquals(priorCommits, commits.get());
        }
    }

    @Test public void malformedEnvelopeVersionsCannotCoerceIntoSupportedOrFutureFormats() throws Exception {
        SettingsStore.ConfigurationLease lease = store.save(sample());
        String valid = preferences.getString(CIPHER, null);
        int priorCommits = commits.get();
        // Keep the JSON token spelling: JSONObject.put(double) may serialize a
        // whole double as an integer and would not exercise the parsing boundary.
        for (String token : new String[]{null, "\"1\"", "\"2\"", "1.0", "1e0", "1.5", "2.0",
                "true", "null", "0", "-1", "[]", "{}", "9223372036854775808"}) {
            String malformed = envelopeWithVersion(valid, token);
            assertTrue(preferences.edit().putString(CIPHER, malformed).commit());
            Map<String, ?> before = preferences.getAll();
            SettingsStore.LoadException failure = assertThrows(SettingsStore.LoadException.class, store::loadWithLease);
            assertEquals(SettingsStore.LoadFailureReason.UNREADABLE, failure.reason);
            assertSafeFailure(failure);
            assertFalse(SettingsStore.isCurrent(lease));
            assertEquals(before, preferences.getAll());
            assertEquals(priorCommits, commits.get());
        }
        for (String token : new String[]{"2", "2147483648", "9223372036854775807"}) {
            String future = envelopeWithVersion(valid, token);
            assertTrue(preferences.edit().putString(CIPHER, future).commit());
            SettingsStore.LoadException failure = assertThrows(SettingsStore.LoadException.class, store::loadWithLease);
            assertEquals(SettingsStore.LoadFailureReason.UNSUPPORTED_FORMAT, failure.reason);
            assertEquals(future, preferences.getString(CIPHER, null));
        }
        assertTrue(preferences.edit().putString(CIPHER, valid).commit());
        assertEquals(MODEL, store.load().model);
        assertEquals(valid, preferences.getString(CIPHER, null));
        assertEquals(priorCommits, commits.get());
    }

    @Test public void uncertainWriteCannotBeClearedByReloadOrReopeningTheStore() throws Exception {
        SettingsStore.SavedSettings settings = sample();
        SettingsStore.ConfigurationLease original = store.save(settings);
        Map<String, ?> before = preferences.getAll();
        failAfterCommit.set(true);
        assertThrows(IOException.class, () -> store.save(settings));
        assertEquals(before, preferences.getAll());
        assertFalse(SettingsStore.isCurrent(original));
        int priorCommits = commits.get();
        for (SettingsStore reader : new SettingsStore[]{store, reopened()}) {
            SettingsStore.LoadException failure = assertThrows(SettingsStore.LoadException.class, reader::loadWithLease);
            assertEquals(SettingsStore.LoadFailureReason.STORAGE_UNCERTAIN, failure.reason);
            assertSafeFailure(failure);
            assertEquals(before, preferences.getAll());
            assertFalse(SettingsStore.isCurrent(original));
        }
        assertThrows(IOException.class, () -> store.save(settings));
        assertEquals(priorCommits, commits.get());
        assertTrue(keys().containsAlias(alias));
        // Only the existing explicit reset operation may leave the fault state.
        store.reset();
        assertNull(reopened().loadWithLease().lease);
        assertFalse(keys().containsAlias(alias));
        assertFalse(SettingsStore.isCurrent(original));
    }

    private SettingsStore reopened() { return new SettingsStore(context, namespace, SharedPreferences.Editor::commit); }

    private SettingsStore.SavedSettings sample() throws IOException {
        SettingsStore.SavedSettings settings = store.load();
        settings.endpoint = "https://recovery.example.invalid/v1";
        settings.apiKey = KEY;
        settings.model = MODEL;
        return settings;
    }

    private static String envelopeWithVersion(String original, String rawVersion) throws Exception {
        JSONObject envelope = new JSONObject(original);
        return "{" + (rawVersion == null ? "" : "\"version\":" + rawVersion + ",")
                + "\"iv\":" + JSONObject.quote(envelope.getString("iv"))
                + ",\"ciphertext\":" + JSONObject.quote(envelope.getString("ciphertext")) + "}";
    }

    private static void assertSafeFailure(SettingsStore.LoadException failure) {
        assertNull("Read errors must not retain provider exceptions", failure.getCause());
        assertTrue(failure.getMessage().length() <= 160);
        assertFalse(failure.getMessage().contains(KEY));
        assertFalse(failure.getMessage().contains(MODEL));
        assertFalse(failure.getMessage().contains("recovery.example.invalid"));
        assertFalse(failure.getMessage().contains("4294967297"));
    }

    private static KeyStore keys() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        return keys;
    }
}
