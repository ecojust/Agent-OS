package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Uses the real Android Keystore; all credentials below are synthetic test values. */
@RunWith(AndroidJUnit4.class)
public final class SettingsStoreInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("non_visual_encrypted_storage_test");
    private Context context;
    private SettingsStore store;
    private SharedPreferences preferences;
    private Map<String, ?> original;

    @Before public void isolateTestPreferences() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String profile = SettingsStore.currentProfileId(context);
        preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
        original = preferences.getAll();
        assertTrue(preferences.edit().clear().commit());
        // Preserve any existing Keystore key so restoring the original encrypted value remains valid.
        store = new SettingsStore(context);
    }

    @After public void restoreOriginalPreferences() {
        if (preferences == null || original == null) return;
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set<?>) {
                @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                editor.putStringSet(entry.getKey(), values);
            }
        }
        assertTrue(editor.commit());
    }

    private SettingsStore.SavedSettings sample() throws IOException {
        SettingsStore.SavedSettings settings = store.load();
        settings.endpoint = "https://model.example.invalid/v1";
        settings.apiKey = "synthetic-instrumentation-key";
        settings.model = "fixture-model";
        settings.confirmEachAction = true;
        return settings;
    }

    @Test public void encryptedConfigurationSurvivesAStoreReopen() throws Exception {
        SettingsStore.SavedSettings settings = sample();
        store.save(settings);
        String encrypted = preferences.getString("encrypted_settings", "");
        assertFalse(encrypted.isEmpty());
        assertFalse(encrypted.contains(settings.apiKey));
        assertFalse(encrypted.contains(settings.endpoint));
        SettingsStore.SavedSettings loaded = new SettingsStore(context).load();
        assertEquals(settings.endpoint, loaded.endpoint);
        assertEquals(settings.apiKey, loaded.apiKey);
        assertEquals(settings.model, loaded.model);
        assertTrue(loaded.confirmEachAction);
        assertTrue(loaded.allowedPackages.isEmpty());
    }

    @Test public void corruptedConfigurationIsNotSilentlyOverwritten() throws Exception {
        SettingsStore.SavedSettings replacement = sample();
        store.save(replacement);
        String valid = preferences.getString("encrypted_settings", "");
        String corrupt = "{\"version\":1,\"iv\":\"invalid\",\"ciphertext\":\"invalid\"}";
        assertTrue(preferences.edit().putString("encrypted_settings", corrupt).commit());
        try {
            store.load();
            fail("Corrupted configuration must be reported");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains(replacement.apiKey));
        }
        try {
            store.save(replacement);
            fail("Saving must not replace an unreadable configuration without explicit reset");
        } catch (IOException expected) {
            assertEquals(corrupt, preferences.getString("encrypted_settings", null));
        }
        assertTrue(preferences.edit().putString("encrypted_settings", valid).commit());
        assertEquals(replacement.model, store.load().model);
    }

    @Test public void newerConfigurationVersionIsPreserved() throws Exception {
        SettingsStore.SavedSettings replacement = sample();
        String future = "{\"version\":999,\"iv\":\"\",\"ciphertext\":\"\"}";
        assertTrue(preferences.edit().putString("encrypted_settings", future).commit());
        try {
            store.save(replacement);
            fail("Unknown newer configuration must not be overwritten");
        } catch (IOException expected) {
            assertEquals(future, preferences.getString("encrypted_settings", null));
        }
    }

    @Test public void applicationGrantMatchesTheInstalledIdentity() throws Exception {
        SettingsStore.AppGrant grant = SettingsStore.captureGrant(context, DeviceHarness.FIXTURE);
        assertNotNull(grant);
        assertTrue(SettingsStore.isGrantValid(context, grant));
        assertEquals(SettingsStore.currentProfileId(context), grant.profileId);
        assertEquals(DeviceHarness.FIXTURE, grant.packageName);
        SettingsStore.SavedSettings settings = sample();
        settings.allowedPackages.add(grant.packageName);
        settings.grants.put(grant.packageName, grant);
        store.save(settings);
        SettingsStore.SavedSettings reopened = new SettingsStore(context).load();
        assertTrue(store.validateAllowedPackages(reopened).contains(DeviceHarness.FIXTURE));
        SettingsStore.AppGrant wrongInstallation = new SettingsStore.AppGrant(grant.packageName,
                grant.profileId, grant.firstInstallTime + 1, grant.uid, grant.signers);
        assertFalse(SettingsStore.isGrantValid(context, wrongInstallation));
        SettingsStore.AppGrant wrongProfile = new SettingsStore.AppGrant(grant.packageName,
                "user:99999", grant.firstInstallTime, grant.uid, grant.signers);
        assertFalse(SettingsStore.isGrantValid(context, wrongProfile));
    }
}
