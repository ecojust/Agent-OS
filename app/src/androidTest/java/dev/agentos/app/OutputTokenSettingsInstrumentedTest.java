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
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import static org.junit.Assert.*;

/** Real encrypted settings with a separate test namespace and actual Keystore key. */
@RunWith(AndroidJUnit4.class)
public final class OutputTokenSettingsInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("non_visual_output_token_settings_test");
    private static final String CIPHER = "encrypted_settings", REVISION = "configuration_revision";
    private static final String KEY = "synthetic-output-token-settings-key";
    private Context context; private String namespace, preferencesName, alias; private byte[] aad;
    private SharedPreferences preferences; private SettingsStore store;
    @Before public void isolateConfiguration() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        namespace = "output_tokens_" + UUID.randomUUID().toString().replace("-", "");
        String profile = SettingsStore.currentProfileId(context);
        preferencesName = "agent_settings_secure_" + profile.replace(':', '_') + "__" + namespace;
        alias = "dev.agentos.settings.v1." + profile + "." + namespace;
        aad = ("Agent OS settings v1 " + profile + "." + namespace).getBytes(StandardCharsets.UTF_8);
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE); store = reopened();
    }
    @After public void removeOnlyTestConfiguration() throws Exception {
        if (store == null) return; store.reset(); assertFalse(keys().containsAlias(alias)); assertTrue(context.deleteSharedPreferences(preferencesName));
    }
    @Test public void emptyDefaultAndExplicitBoundaryValuesRoundTripThroughEncryption() throws Exception {
        SettingsStore.LoadedSettings empty = store.loadWithLease(); assertEquals(2048, empty.settings.maxOutputTokens);
        assertNull(empty.lease); assertTrue(preferences.getAll().isEmpty());
        SettingsStore.SavedSettings settings = sample(); SettingsStore.ConfigurationLease prior = null;
        for (int selected : new int[]{128, 3072, 8192}) {
            settings.maxOutputTokens = selected; SettingsStore.ConfigurationLease saved = store.save(settings);
            if (prior != null) assertFalse(SettingsStore.isCurrent(prior));
            SettingsStore.LoadedSettings loaded = reopened().loadWithLease();
            assertSame(saved, loaded.lease); assertEquals(selected, loaded.settings.maxOutputTokens);
            assertEquals(selected, payload().getInt("maxOutputTokens"));
            String encrypted = preferences.getString(CIPHER, ""); assertFalse(encrypted.contains("maxOutputTokens")); assertFalse(encrypted.contains(KEY));
            prior = saved;
        }
    }
    @Test public void absenceAloneUsesTheLegacyDefaultWithoutSilentlyRewritingTheCiphertext() throws Exception {
        store.save(sample()); JSONObject original = payload();
        for (boolean withRevision : new boolean[]{true, false}) {
            JSONObject legacy = new JSONObject(original.toString()); legacy.remove("maxOutputTokens");
            if (!withRevision) legacy.remove("configurationRevision");
            SharedPreferences.Editor edit = preferences.edit().putString(CIPHER, encrypt(legacy.toString()));
            if (withRevision) edit.putString(REVISION, original.getString("configurationRevision")); else edit.remove(REVISION);
            assertTrue(edit.commit()); Map<String, ?> bytesBefore = preferences.getAll();
            SettingsStore.LoadedSettings loaded = reopened().loadWithLease();
            assertEquals(2048, loaded.settings.maxOutputTokens); assertTrue(SettingsStore.isCurrent(loaded.lease));
            assertEquals(bytesBefore, preferences.getAll()); assertFalse(payload().has("maxOutputTokens"));
            SettingsStore.ConfigurationLease explicit = store.save(loaded.settings);
            assertFalse(SettingsStore.isCurrent(loaded.lease)); assertTrue(SettingsStore.isCurrent(explicit)); assertEquals(2048, payload().getInt("maxOutputTokens"));
        }
    }
    @Test public void presentInvalidValuesCannotBeCoercedOrOverwriteTheOriginalEncryptedDocument() throws Exception {
        SettingsStore.SavedSettings good = sample(); store.save(good);
        String goodCipher = preferences.getString(CIPHER, null); JSONObject baseObject = payload(); baseObject.remove("maxOutputTokens"); String base = baseObject.toString();
        for (String rejected : new String[]{"null", "true", "[]", "{}", "\"2048\"", "\"" + KEY + "\"", "2048.0", "2.048e3", "-1", "0", "127", "8193", "2147483648", "9223372036854775808"}) {
            SettingsStore.ConfigurationLease old = reopened().loadWithLease().lease;
            // Preserve the exact number token; JSONObject would normalize 2048.0.
            String raw = base.substring(0, base.length() - 1) + ",\"maxOutputTokens\":" + rejected + "}";
            assertTrue(preferences.edit().putString(CIPHER, encrypt(raw)).commit()); Map<String, ?> before = preferences.getAll();
            assertSafe(assertThrows(IOException.class, () -> reopened().loadWithLease())); assertFalse(SettingsStore.isCurrent(old));
            assertSafe(assertThrows(IOException.class, () -> store.save(good))); assertEquals(before, preferences.getAll());
            // Explicit fixture restoration; production never recovers by overwriting.
            assertTrue(preferences.edit().putString(CIPHER, goodCipher).commit()); assertEquals(2048, reopened().load().maxOutputTokens);
        }
        for (int invalid : new int[]{Integer.MIN_VALUE, 0, 127, 8193, Integer.MAX_VALUE}) {
            SettingsStore.SavedSettings edited = reopened().load(); edited.maxOutputTokens = invalid; Map<String, ?> before = preferences.getAll();
            assertSafe(assertThrows(IOException.class, () -> store.save(edited))); assertEquals(before, preferences.getAll()); assertEquals(2048, reopened().load().maxOutputTokens);
        }
    }
    private static void assertSafe(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) assertFalse("Settings errors must not reveal a stored credential", String.valueOf(cause.getMessage()).contains(KEY));
    }
    private SettingsStore reopened() { return new SettingsStore(context, namespace, SharedPreferences.Editor::commit); }
    private SettingsStore.SavedSettings sample() throws IOException {
        SettingsStore.SavedSettings settings = store.load(); settings.endpoint = "https://output-tokens.example.invalid/v1";
        settings.apiKey = KEY; settings.model = "synthetic-output-tokens"; return settings;
    }
    private KeyStore keys() throws Exception { KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); return keys; }
    private JSONObject payload() throws Exception {
        JSONObject envelope = new JSONObject(preferences.getString(CIPHER, null)); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, (SecretKey) keys().getKey(alias, null), new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        cipher.updateAAD(aad); return new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }
    private String encrypt(String raw) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) keys().getKey(alias, null)); cipher.updateAAD(aad);
        JSONObject envelope = new JSONObject(); envelope.put("version", 1); envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        envelope.put("ciphertext", Base64.encodeToString(cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP)); return envelope.toString();
    }
}
