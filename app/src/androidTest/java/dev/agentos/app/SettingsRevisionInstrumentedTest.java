package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static org.junit.Assert.*;

/** Uses isolated real Keystore aliases; it never deletes or replaces the application's default key. */
@RunWith(AndroidJUnit4.class)
public final class SettingsRevisionInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("non_visual_configuration_revision_test");
    private static final String CIPHER = "encrypted_settings", REVISION = "configuration_revision";
    private Context context;
    private String namespace, preferencesName, alias;
    private byte[] aad;
    private SharedPreferences preferences;
    private SettingsStore store;
    private final AtomicBoolean failAfterCommit = new AtomicBoolean();
    private final AtomicReference<Runnable> beforeCommit = new AtomicReference<>();

    @Before public void createIsolatedStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        namespace = "revision_" + UUID.randomUUID().toString().replace("-", "");
        String profile = SettingsStore.currentProfileId(context);
        preferencesName = "agent_settings_secure_" + profile.replace(':', '_') + "__" + namespace;
        alias = "dev.agentos.settings.v1." + profile + "." + namespace;
        aad = ("Agent OS settings v1 " + profile + "." + namespace).getBytes(StandardCharsets.UTF_8);
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        store = new SettingsStore(context, namespace, editor -> {
            Runnable hook = beforeCommit.get();
            if (hook != null) hook.run();
            boolean persisted = editor.commit();
            // SharedPreferences may already update process memory when persistence
            // reports failure. Model that boundary without damaging unrelated keys.
            return !failAfterCommit.getAndSet(false) && persisted;
        });
    }

    @After public void removeOnlyThisTestsKeyAndPreferences() throws Exception {
        if (store == null) return;
        beforeCommit.set(null);
        failAfterCommit.set(false);
        store.reset();
        assertFalse(keys().containsAlias(alias));
        assertTrue(context.deleteSharedPreferences(preferencesName));
    }

    private SettingsStore reopened() { return new SettingsStore(context, namespace, SharedPreferences.Editor::commit); }

    private SettingsStore.SavedSettings sample() throws IOException {
        SettingsStore.SavedSettings value = store.load();
        value.endpoint = "https://revision.example.invalid/v1";
        value.apiKey = "synthetic-revision-key";
        value.model = "revision-fixture";
        return value;
    }

    @Test public void storeInstancesShareTheLeaseAndEvenIdenticalSavesRotateIt() throws Exception {
        assertNull(store.loadWithLease().lease);
        SettingsStore.SavedSettings value = sample();
        SettingsStore.ConfigurationLease first = store.save(value);
        assertSame(first, reopened().loadWithLease().lease);
        assertTrue(SettingsStore.isCurrent(first));
        assertEquals(SettingsStore.currentProfileId(context), first.profileId);
        AtomicInteger called = new AtomicInteger();
        SettingsStore.ConfigurationLease second;
        try (SettingsStore.Registration watcher = reopened().watch(first, called::incrementAndGet)) {
            second = store.save(value);
            assertEquals(1, called.get());
            assertFalse(SettingsStore.isCurrent(first));
            assertTrue(SettingsStore.isCurrent(second));
            assertNotEquals(first.configurationToken, second.configurationToken);
            assertSame(second, reopened().loadWithLease().lease);
            AtomicInteger immediate = new AtomicInteger();
            try (SettingsStore.Registration stale = store.watch(first, immediate::incrementAndGet)) {
                assertEquals("An expired lease must notify during registration", 1, immediate.get());
            }
        }
        AtomicInteger closed = new AtomicInteger();
        SettingsStore.Registration removed = store.watch(second, closed::incrementAndGet);
        removed.close();
        store.save(value);
        assertEquals("A closed watcher must not receive future invalidations", 0, closed.get());
    }

    @Test public void watchersRunBeforeCommitAndCanReadFromAnotherThreadWithoutHoldingStorageLocks() throws Exception {
        SettingsStore.SavedSettings value = sample();
        SettingsStore.ConfigurationLease lease = store.save(value);
        Map<String, ?> original = preferences.getAll();
        AtomicBoolean notified = new AtomicBoolean();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (SettingsStore.Registration registration = store.watch(lease, () -> {
            try {
                assertFalse(SettingsStore.isCurrent(lease));
                assertEquals("Invalidation must precede persistence", original, preferences.getAll());
                Future<Boolean> read = reader.submit(() -> {
                    try { reopened().loadWithLease(); return false; }
                    catch (IOException busy) { return true; }
                });
                assertTrue("A concurrent callback read must fail closed without waiting for the callback's lock",
                        read.get(2, TimeUnit.SECONDS));
                notified.set(true);
            } catch (Throwable failure) { callbackFailure.set(failure); }
        })) {
            beforeCommit.set(() -> assertTrue("Commit preceded invalidation", notified.get()));
            SettingsStore.ConfigurationLease replacement = store.save(value);
            if (callbackFailure.get() != null) throw new AssertionError("Invalidation callback failed", callbackFailure.get());
            assertTrue(notified.get());
            assertTrue(SettingsStore.isCurrent(replacement));
        } finally {
            beforeCommit.set(null);
            reader.shutdownNow();
        }
    }

    @Test public void resetInvalidatesLeasesAndDoesNotAuthorizeRestoringOldCiphertext() throws Exception {
        SettingsStore.ConfigurationLease lease = store.save(sample());
        String oldCipher = preferences.getString(CIPHER, null);
        String oldRevision = preferences.getString(REVISION, null);
        AtomicInteger notifications = new AtomicInteger();
        try (SettingsStore.Registration registration = store.watch(lease, notifications::incrementAndGet)) {
            store.reset();
            assertEquals(1, notifications.get());
            assertFalse(SettingsStore.isCurrent(lease));
            assertFalse(preferences.contains(CIPHER));
            String tombstone = preferences.getString(REVISION, null);
            assertNotNull(tombstone);
            assertNotEquals(oldRevision, tombstone);
            assertNull(reopened().loadWithLease().lease);
            assertEquals("", reopened().load().endpoint);
            assertTrue(preferences.edit().putString(CIPHER, oldCipher).commit());
            assertThrows(IOException.class, () -> reopened().loadWithLease());
            assertEquals(oldCipher, preferences.getString(CIPHER, null));
            assertEquals(tombstone, preferences.getString(REVISION, null));
        }
    }

    @Test public void legacyIsAcceptedOnlyWithBothRevisionsMissingAndIsNotSilentlyRewritten() throws Exception {
        SettingsStore.SavedSettings value = sample();
        store.save(value);
        JSONObject legacyPayload = payload();
        legacyPayload.remove("configurationRevision");
        String legacy = encrypt(legacyPayload);
        assertTrue(preferences.edit().putString(CIPHER, legacy).remove(REVISION).commit());
        SettingsStore.LoadedSettings loaded = reopened().loadWithLease();
        assertNotNull(loaded.lease);
        assertTrue(SettingsStore.isCurrent(loaded.lease));
        assertSame(loaded.lease, reopened().loadWithLease().lease);
        assertEquals(value.model, loaded.settings.model);
        assertEquals(legacy, preferences.getString(CIPHER, null));
        assertFalse(preferences.contains(REVISION));
        SettingsStore.ConfigurationLease saved = store.save(loaded.settings);
        assertFalse(SettingsStore.isCurrent(loaded.lease));
        assertTrue(SettingsStore.isCurrent(saved));
        assertEquals(preferences.getString(REVISION, null), payload().getString("configurationRevision"));
    }

    @Test public void partialMismatchedAndMalformedRevisionsPreserveTheRejectedBytes() throws Exception {
        SettingsStore.SavedSettings value = sample();
        store.save(value);
        String goodCipher = preferences.getString(CIPHER, null), goodRevision = preferences.getString(REVISION, null);
        JSONObject missing = payload(); missing.remove("configurationRevision");
        String noPayloadRevision = encrypt(missing);
        JSONObject invalid = payload(); invalid.put("configurationRevision", "not-a-valid-revision");
        String invalidPayloadRevision = encrypt(invalid);
        String[][] rejected = {{goodCipher, null}, {noPayloadRevision, goodRevision},
                {goodCipher, UUID.randomUUID().toString()}, {goodCipher, "INVALID"}, {invalidPayloadRevision, goodRevision}};
        for (String[] scenario : rejected) {
            SharedPreferences.Editor editor = preferences.edit().putString(CIPHER, scenario[0]);
            if (scenario[1] == null) editor.remove(REVISION); else editor.putString(REVISION, scenario[1]);
            assertTrue(editor.commit());
            Map<String, ?> original = preferences.getAll();
            assertThrows(IOException.class, () -> store.loadWithLease());
            assertThrows(IOException.class, () -> store.save(value));
            assertEquals(original, preferences.getAll());
            assertTrue(preferences.edit().putString(CIPHER, goodCipher).putString(REVISION, goodRevision).commit());
            assertNotNull(store.loadWithLease().lease);
        }
    }

    @Test public void unknownEnvelopeVersionsAndNonStringMetadataAreNeverOverwritten() throws Exception {
        SettingsStore.SavedSettings value = sample();
        store.save(value);
        String goodCipher = preferences.getString(CIPHER, null), goodRevision = preferences.getString(REVISION, null);
        JSONObject future = new JSONObject(goodCipher); future.put("version", 999);
        for (boolean wrongType : new boolean[]{false, true}) {
            SharedPreferences.Editor editor = preferences.edit();
            if (wrongType) editor.putString(CIPHER, goodCipher).putInt(REVISION, 42);
            else editor.putString(CIPHER, future.toString()).putString(REVISION, goodRevision);
            assertTrue(editor.commit());
            Map<String, ?> original = preferences.getAll();
            assertThrows(IOException.class, () -> store.loadWithLease());
            assertThrows(IOException.class, () -> store.save(value));
            assertEquals(original, preferences.getAll());
        }
    }

    @Test public void failedSaveAndResetRestoreThePreferencePairButKeepTheOldLeaseInvalid() throws Exception {
        SettingsStore.SavedSettings value = sample();
        SettingsStore.ConfigurationLease first = store.save(value);
        Map<String, ?> beforeSave = preferences.getAll();
        failAfterCommit.set(true);
        assertThrows(IOException.class, () -> store.save(value));
        assertEquals("Known process memory must not retain the failed replacement", beforeSave, preferences.getAll());
        assertFalse(SettingsStore.isCurrent(first));
        assertThrows(IOException.class, () -> reopened().loadWithLease());
        assertThrows(IOException.class, () -> store.save(value));
        assertTrue(keys().containsAlias(alias));
        store.reset();
        assertNull(store.loadWithLease().lease);

        SettingsStore.ConfigurationLease second = store.save(value);
        Map<String, ?> beforeReset = preferences.getAll();
        failAfterCommit.set(true);
        assertThrows(IOException.class, store::reset);
        assertEquals(beforeReset, preferences.getAll());
        assertTrue("A failed reset must not delete the still-needed isolated key", keys().containsAlias(alias));
        assertFalse(SettingsStore.isCurrent(second));
        assertThrows(IOException.class, () -> reopened().loadWithLease());
        assertThrows(IOException.class, () -> store.save(value));
        store.reset();
        assertNull(reopened().loadWithLease().lease);
        assertFalse(keys().containsAlias(alias));
    }

    private KeyStore keys() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); return keys;
    }

    private JSONObject payload() throws Exception {
        JSONObject envelope = new JSONObject(preferences.getString(CIPHER, null));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, (SecretKey) keys().getKey(alias, null),
                new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        cipher.updateAAD(aad);
        return new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }

    private String encrypt(JSONObject payload) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) keys().getKey(alias, null));
        cipher.updateAAD(aad);
        JSONObject envelope = new JSONObject();
        envelope.put("version", 1);
        envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        envelope.put("ciphertext", Base64.encodeToString(cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        return envelope.toString();
    }
}
