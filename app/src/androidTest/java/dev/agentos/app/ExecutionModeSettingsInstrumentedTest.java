package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.agentos.core.ActionConfirmationPolicy;
import org.json.JSONArray;
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
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static org.junit.Assert.*;

/** Real encrypted configuration tests; each case owns a separate Keystore alias. */
@RunWith(AndroidJUnit4.class)
public final class ExecutionModeSettingsInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("non_visual_execution_mode_storage_test");
    private static final String CIPHER = "encrypted_settings", REVISION = "configuration_revision";
    private Context context;
    private String namespace, preferencesName, alias;
    private byte[] aad;
    private SharedPreferences preferences;
    private SettingsStore store;

    @Before public void createIsolatedStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        namespace = "execution_" + UUID.randomUUID().toString().replace("-", "");
        String profile = SettingsStore.currentProfileId(context);
        preferencesName = "agent_settings_secure_" + profile.replace(':', '_') + "__" + namespace;
        alias = "dev.agentos.settings.v1." + profile + "." + namespace;
        aad = ("Agent OS settings v1 " + profile + "." + namespace).getBytes(StandardCharsets.UTF_8);
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        store = reopened();
    }

    @After public void removeOnlyIsolatedConfiguration() throws Exception {
        if (store == null) return;
        store.reset();
        assertFalse(keys().containsAlias(alias));
        assertTrue(context.deleteSharedPreferences(preferencesName));
    }

    @Test public void emptyConfigurationAlwaysStartsWithConfirmationAndWithoutALease() throws Exception {
        SettingsStore.LoadedSettings empty = store.loadWithLease();
        assertNull(empty.lease);
        assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, empty.settings.executionMode);
        assertTrue(empty.settings.confirmEachAction);
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void eachExplicitModeSurvivesReopenAndTheLegacyFlagStaysRestrictive() throws Exception {
        SettingsStore.SavedSettings settings = sample();
        // The compatibility field must never create a permissive old-reader document.
        settings.confirmEachAction = false;
        for (ActionConfirmationPolicy.Mode mode : ActionConfirmationPolicy.Mode.values()) {
            settings.executionMode = mode;
            SettingsStore.ConfigurationLease lease = store.save(settings);
            SettingsStore.LoadedSettings loaded = reopened().loadWithLease();
            assertEquals(mode, loaded.settings.executionMode);
            assertTrue(loaded.settings.confirmEachAction);
            assertSame(lease, loaded.lease);
            assertEquals(mode.name(), payload().getString("executionMode"));
            assertTrue(payload().getBoolean("confirmEachAction"));
            String encrypted = preferences.getString(CIPHER, "");
            assertFalse(encrypted.contains(mode.name()));
            assertFalse(encrypted.contains(settings.apiKey));
        }
    }

    @Test public void legacyFalseNeverAuthorizesNavigationOrRewritesTheOldConfiguration() throws Exception {
        SettingsStore.SavedSettings settings = sample();
        store.save(settings);
        JSONObject originalPayload = payload();
        for (boolean hasRevision : new boolean[]{false, true}) {
            for (boolean legacyConfirm : new boolean[]{false, true}) {
                JSONObject legacy = new JSONObject(originalPayload.toString());
                legacy.remove("executionMode");
                legacy.put("confirmEachAction", legacyConfirm);
                if (!hasRevision) legacy.remove("configurationRevision");
                SharedPreferences.Editor editor = preferences.edit().putString(CIPHER, encrypt(legacy));
                if (hasRevision) editor.putString(REVISION, originalPayload.getString("configurationRevision"));
                else editor.remove(REVISION);
                assertTrue(editor.commit());
                Map<String, ?> before = preferences.getAll();

                SettingsStore.LoadedSettings loaded = reopened().loadWithLease();
                assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, loaded.settings.executionMode);
                assertEquals(legacyConfirm, loaded.settings.confirmEachAction);
                assertTrue(SettingsStore.isCurrent(loaded.lease));
                assertEquals("Reading legacy settings must not silently migrate or overwrite them", before, preferences.getAll());

                store.save(loaded.settings);
                assertEquals("CONFIRM_ALL", payload().getString("executionMode"));
                assertTrue(payload().getBoolean("confirmEachAction"));
                assertFalse(SettingsStore.isCurrent(loaded.lease));
            }
        }
    }

    @Test public void unknownAndConfusedModeNamesRejectReadAndSaveWithoutChangingBytes() throws Exception {
        assertRejectedModes(new Object[]{"AUTONOMOUS", "", "continuous_navigation", " CONTINUOUS_NAVIGATION",
                "CONTINUOUS_NAVIGATION ", "confirm_all"});
    }

    @Test public void nonStringModesRejectReadAndSaveWithoutChangingBytes() throws Exception {
        assertRejectedModes(new Object[]{false, 1, JSONObject.NULL, new JSONArray().put("CONFIRM_ALL"),
                new JSONObject().put("mode", "CONTINUOUS_NAVIGATION")});
    }

    @Test public void changingModeRotatesTheLeaseAndLeavesThePreviousSnapshotUnchanged() throws Exception {
        SettingsStore.ConfigurationLease first = store.save(sample());
        SettingsStore.LoadedSettings frozen = reopened().loadWithLease();
        SettingsStore.SavedSettings replacement = reopened().load();
        replacement.executionMode = ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION;
        AtomicInteger invalidations = new AtomicInteger();
        try (SettingsStore.Registration watcher = store.watch(first, invalidations::incrementAndGet)) {
            SettingsStore.ConfigurationLease next = reopened().save(replacement);
            assertEquals(1, invalidations.get());
            assertFalse(SettingsStore.isCurrent(first));
            assertTrue(SettingsStore.isCurrent(next));
            assertNotEquals(first.configurationToken, next.configurationToken);
            assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, frozen.settings.executionMode);
            assertEquals(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION, store.load().executionMode);
        }
    }

    @Test public void missingInMemoryModeCannotOverwriteAValidEncryptedConfiguration() throws Exception {
        SettingsStore.SavedSettings settings = sample();
        SettingsStore.ConfigurationLease first = store.save(settings);
        Map<String, ?> before = preferences.getAll();
        settings.executionMode = null;
        assertThrows(IOException.class, () -> store.save(settings));
        assertEquals(before, preferences.getAll());
        assertFalse(SettingsStore.isCurrent(first));
        assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, reopened().load().executionMode);
    }

    private void assertRejectedModes(Object[] modes) throws Exception {
        SettingsStore.SavedSettings settings = sample();
        store.save(settings);
        JSONObject good = payload();
        String goodCipher = preferences.getString(CIPHER, null);
        for (Object mode : modes) {
            JSONObject rejected = new JSONObject(good.toString()).put("executionMode", mode);
            assertTrue(preferences.edit().putString(CIPHER, encrypt(rejected)).commit());
            Map<String, ?> before = preferences.getAll();
            assertThrows(IOException.class, () -> reopened().loadWithLease());
            assertThrows(IOException.class, () -> store.save(settings));
            assertEquals(before, preferences.getAll());
            assertTrue(preferences.edit().putString(CIPHER, goodCipher).commit());
            assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, reopened().load().executionMode);
        }
    }

    private SettingsStore reopened() { return new SettingsStore(context, namespace, SharedPreferences.Editor::commit); }

    private SettingsStore.SavedSettings sample() throws IOException {
        SettingsStore.SavedSettings settings = store.load();
        settings.endpoint = "https://execution-mode.example.invalid/v1";
        settings.apiKey = "synthetic-execution-mode-key";
        settings.model = "execution-mode-fixture";
        return settings;
    }

    private KeyStore keys() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        return keys;
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
