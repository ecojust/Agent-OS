package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.agentos.core.TaskBudget;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static org.junit.Assert.*;

/** Production encrypted storage with isolated preferences, profile state and Keystore key. */
@RunWith(AndroidJUnit4.class)
public final class TaskBudgetSettingsInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("non_visual_task_budget_storage_test");
    private static final String CIPHER = "encrypted_settings", REVISION = "configuration_revision";
    private Context context;
    private String namespace, preferencesName, alias;
    private byte[] aad;
    private SharedPreferences preferences;
    private SettingsStore store;

    @Before public void createIsolatedStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        namespace = "budget_" + UUID.randomUUID().toString().replace("-", "");
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

    @Test public void emptyAndLegacySettingsUseDefaultsWithoutRewritingStoredBytes() throws Exception {
        SettingsStore.LoadedSettings empty = store.loadWithLease();
        assertSame(TaskBudget.DEFAULT, empty.settings.taskBudget);
        assertNull(empty.lease);
        assertTrue(preferences.getAll().isEmpty());
        store.save(sample());
        JSONObject original = payload();
        for (boolean withRevision : new boolean[]{false, true}) {
            JSONObject legacy = new JSONObject(original.toString());
            legacy.remove("taskBudget");
            if (!withRevision) legacy.remove("configurationRevision");
            SharedPreferences.Editor edit = preferences.edit().putString(CIPHER, encrypt(legacy.toString()));
            if (withRevision) edit.putString(REVISION, original.getString("configurationRevision"));
            else edit.remove(REVISION);
            assertTrue(edit.commit());
            Map<String, ?> before = preferences.getAll();
            SettingsStore.LoadedSettings loaded = reopened().loadWithLease();
            assertEquals(TaskBudget.DEFAULT, loaded.settings.taskBudget);
            assertTrue(SettingsStore.isCurrent(loaded.lease));
            assertEquals("Reading a legacy budget must not perform a silent migration", before, preferences.getAll());
            assertFalse(payload().has("taskBudget"));
            store.save(loaded.settings);
            assertFalse(SettingsStore.isCurrent(loaded.lease));
            assertBudgetPayload(TaskBudget.DEFAULT);
        }
    }

    @Test public void nonDefaultBudgetsAndBothLocalBoundsRoundTripEncrypted() throws Exception {
        SettingsStore.SavedSettings settings = sample();
        for (TaskBudget budget : new TaskBudget[]{new TaskBudget(1, 1, 1_000), new TaskBudget(17, 93, 93_000),
                new TaskBudget(300, 1000, 3_600_000)}) {
            settings.taskBudget = budget;
            SettingsStore.ConfigurationLease saved = store.save(settings);
            SettingsStore.LoadedSettings reopened = reopened().loadWithLease();
            assertEquals(budget, reopened.settings.taskBudget);
            assertNotSame(budget, reopened.settings.taskBudget);
            assertSame(saved, reopened.lease);
            assertBudgetPayload(budget);
            assertFalse(preferences.getString(CIPHER, "").contains("taskBudget"));
            assertFalse(preferences.getString(CIPHER, "").contains(settings.apiKey));
        }
    }

    @Test public void presentMalformedBudgetObjectsCannotBeReadOrOverwritten() throws Exception {
        assertRejectedBudgets(List.of("null", "[]", "true", "123", "\"defaults\"", "{}",
                "{\"maxToolCalls\":100,\"maxActiveMillis\":600000}",
                "{\"maxModelRounds\":30,\"maxActiveMillis\":600000}",
                "{\"maxModelRounds\":30,\"maxToolCalls\":100}",
                "{\"maxModelRounds\":30,\"maxToolCalls\":100,\"maxActiveMillis\":600000,\"futureLimit\":1}"));
    }

    @Test public void nonIntegerOverflowAndOutOfRangeFieldsCannotBeCoercedIntoValidBudgets() throws Exception {
        List<String> rejected = new ArrayList<>();
        for (String rounds : new String[]{"0", "301", "2147483648", "9223372036854775808", "\"1\"", "true", "null", "1.5", "1.0", "1e0"}) {
            rejected.add(budgetJson(rounds, "100", "600000"));
        }
        for (String tools : new String[]{"0", "1001", "4294967297", "false", "\"2\"", "null", "2.5"}) {
            rejected.add(budgetJson("30", tools, "600000"));
        }
        for (String millis : new String[]{"999", "1001", "3600001", "3601000", "-1", "0", "1000.0", "1e3", "\"1000\"", "null", "9223372036854775808"}) {
            rejected.add(budgetJson("30", "100", millis));
        }
        assertRejectedBudgets(rejected);
    }

    @Test public void savingANewBudgetInvalidatesTheOldLeaseAndPreservesItsFrozenValue() throws Exception {
        SettingsStore.ConfigurationLease original = store.save(sample());
        SettingsStore.LoadedSettings frozen = reopened().loadWithLease();
        SettingsStore.SavedSettings updated = reopened().load();
        updated.taskBudget = new TaskBudget(12, 65, 120_000);
        AtomicInteger invalidations = new AtomicInteger();
        try (SettingsStore.Registration registration = original.watch(invalidations::incrementAndGet)) {
            SettingsStore.ConfigurationLease replacement = store.save(updated);
            assertEquals(1, invalidations.get());
            assertFalse(SettingsStore.isCurrent(original));
            assertTrue(SettingsStore.isCurrent(replacement));
            assertNotEquals(original.configurationToken, replacement.configurationToken);
            assertEquals(TaskBudget.DEFAULT, frozen.settings.taskBudget);
            assertEquals(updated.taskBudget, reopened().load().taskBudget);
        }
    }

    @Test public void invalidInMemoryLocalBudgetsCannotOverwriteExistingConfiguration() throws Exception {
        for (TaskBudget invalid : new TaskBudget[]{null, new TaskBudget(1, 1, 1), new TaskBudget(1, 1, 999),
                new TaskBudget(1, 1, 1001), new TaskBudget(1, 1, 3_599_999)}) {
            SettingsStore.SavedSettings valid = sample();
            valid.taskBudget = TaskBudget.DEFAULT;
            SettingsStore.ConfigurationLease original = store.save(valid);
            Map<String, ?> before = preferences.getAll();
            SettingsStore.SavedSettings edited = reopened().load();
            edited.taskBudget = invalid;
            assertThrows(IOException.class, () -> store.save(edited));
            assertEquals(before, preferences.getAll());
            assertFalse("A failed save must not revive the previous execution lease", SettingsStore.isCurrent(original));
            assertEquals(TaskBudget.DEFAULT, reopened().load().taskBudget);
        }
    }

    private void assertRejectedBudgets(List<String> budgets) throws Exception {
        SettingsStore.SavedSettings good = sample();
        store.save(good);
        String goodCipher = preferences.getString(CIPHER, null);
        JSONObject withoutBudget = payload();
        withoutBudget.remove("taskBudget");
        String base = withoutBudget.toString();
        for (String rejected : budgets) {
            SettingsStore.ConfigurationLease beforeLease = reopened().loadWithLease().lease;
            // Encrypt exact JSON text: JSONObject.toString would normalize 1.0 to
            // 1 and silently erase the non-integer representation under test.
            String raw = base.substring(0, base.length() - 1) + ",\"taskBudget\":" + rejected + "}";
            assertTrue(preferences.edit().putString(CIPHER, encrypt(raw)).commit());
            Map<String, ?> before = preferences.getAll();
            assertThrows("Malformed budget was accepted: " + rejected, IOException.class, () -> reopened().loadWithLease());
            assertFalse(SettingsStore.isCurrent(beforeLease));
            assertThrows("A malformed existing budget was silently replaced: " + rejected, IOException.class, () -> store.save(good));
            assertEquals("Invalid budget read/save changed stored bytes", before, preferences.getAll());
            // Explicit test-fixture restoration, never production recovery behavior.
            assertTrue(preferences.edit().putString(CIPHER, goodCipher).commit());
            assertEquals(TaskBudget.DEFAULT, reopened().load().taskBudget);
        }
    }

    private static String budgetJson(String rounds, String tools, String millis) {
        return "{\"maxModelRounds\":" + rounds + ",\"maxToolCalls\":" + tools + ",\"maxActiveMillis\":" + millis + "}";
    }

    private void assertBudgetPayload(TaskBudget expected) throws Exception {
        JSONObject budget = payload().getJSONObject("taskBudget");
        assertEquals(3, budget.length());
        assertEquals(expected.maxModelRounds, budget.getInt("maxModelRounds"));
        assertEquals(expected.maxToolCalls, budget.getInt("maxToolCalls"));
        assertEquals(expected.maxActiveMillis, budget.getLong("maxActiveMillis"));
    }

    private SettingsStore reopened() { return new SettingsStore(context, namespace, SharedPreferences.Editor::commit); }

    private SettingsStore.SavedSettings sample() throws IOException {
        SettingsStore.SavedSettings settings = store.load();
        settings.endpoint = "https://task-budget.example.invalid/v1";
        settings.apiKey = "synthetic-task-budget-key";
        settings.model = "task-budget-fixture";
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

    private String encrypt(String payload) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) keys().getKey(alias, null));
        cipher.updateAAD(aad);
        JSONObject envelope = new JSONObject();
        envelope.put("version", 1);
        envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        envelope.put("ciphertext", Base64.encodeToString(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        return envelope.toString();
    }
}
