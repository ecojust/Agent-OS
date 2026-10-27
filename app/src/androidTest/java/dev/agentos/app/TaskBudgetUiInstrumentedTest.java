package dev.agentos.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.ActionConfirmationPolicy.Mode;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real local settings widgets and encrypted saves; no model or device-action authorization. */
@RunWith(AndroidJUnit4.class)
public final class TaskBudgetUiInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_task_budget_configuration_capture_not_attempted");

    @Test public void defaultsAndSelectedBudgetSurviveAnExplicitSaveAndReopen() throws Exception {
        try (BudgetUi ui = new BudgetUi(false)) {
            ui.assertValues("30", "100", "600");
            ui.device.instrumentation.runOnMainSync(() -> {
                assertEquals(View.GONE, ui.activity.findViewById(R.id.task_budget_fields).getVisibility());
                assertEquals("30 轮模型 · 100 次工具 · 600 秒活动", ui.text(R.id.task_budget_summary));
                assertTrue(ui.text(R.id.task_budget_explanation).contains("暂停和确认等待不计活动时间"));
                for (int id : ids()) assertFalse(ui.field(id).isSaveEnabled());
            });
            assertTrue("Opening the budget UI must not save defaults", ui.preferences.getAll().isEmpty());
            ui.seedModelFields();
            ui.edit("18", "65", "240");
            assertEquals(TaskBudget.DEFAULT, ui.store.load().taskBudget);
            ui.save();
            assertEquals(new TaskBudget(18, 65, 240_000), ui.store.load().taskBudget);
            assertEquals(Mode.CONFIRM_ALL, ui.store.load().executionMode);
            ui.reopen();
            ui.assertValues("18", "65", "240");
            // An unsaved visual draft must not be restored by the Android view-state bundle.
            ui.edit("19", "66", "241");
            ui.reopen();
            ui.assertValues("18", "65", "240");
            ui.assertIdleSecure();
        }
    }

    @Test public void emptyOutOfRangeAndOverflowBudgetsCannotSaveOrStartATask() throws Exception {
        try (BudgetUi ui = new BudgetUi(true)) {
            SettingsStore.ConfigurationLease original = ui.store.loadWithLease().lease;
            Map<String, ?> persisted = ui.preferences.getAll();
            // Different invalid classes are checked together; no field may be silently clamped.
            ui.edit("", "1001", "9999999999999999999999999");
            ui.device.instrumentation.runOnMainSync(() -> {
                ui.showModelSave();
                assertTrue(ui.button(R.id.save_settings).performClick());
                for (int id : ids()) assertNotNull(ui.field(id).getError());
                assertEquals(View.VISIBLE, ui.activity.findViewById(R.id.task_budget_fields).getVisibility());
                assertTrue(ui.button(R.id.save_settings).isEnabled());
                ui.field(R.id.task_prompt).setText("Synthetic invalid budget must not start");
                assertTrue(ui.button(R.id.start_task).isEnabled());
                assertTrue(ui.button(R.id.start_task).performClick());
                for (int id : ids()) assertNotNull(ui.field(id).getError());
            });
            ui.assertValues("", "1001", "9999999999999999999999999");
            assertTrue(SettingsStore.isCurrent(original));
            assertEquals(persisted, ui.preferences.getAll());
            assertEquals(TaskBudget.DEFAULT, ui.store.load().taskBudget);
            ui.edit("0", "-1", "1.5");
            ui.device.instrumentation.runOnMainSync(() -> {
                assertTrue(ui.button(R.id.save_settings).performClick());
                for (int id : ids()) assertNotNull(ui.field(id).getError());
            });
            assertTrue(SettingsStore.isCurrent(original));
            assertEquals(persisted, ui.preferences.getAll());
            ui.assertIdleSecure();
        }
    }

    @Test public void encryptedSaveFreezesBudgetFieldsAndIgnoresLateProgrammaticEdits() throws Exception {
        try (BudgetUi ui = new BudgetUi(true)) {
            SettingsStore.ConfigurationLease original = ui.store.loadWithLease().lease;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try (SettingsStore.Registration watch = original.watch(() -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Budget save barrier timed out"); }
                catch (Throwable problem) { failure.set(problem); }
            })) {
                ui.edit("12", "45", "180");
                ui.device.instrumentation.runOnMainSync(() -> {
                    ui.showModelSave();
                    assertTrue(ui.button(R.id.save_settings).performClick());
                });
                assertTrue("The actual encrypted save did not start", entered.await(5, TimeUnit.SECONDS));
                ui.device.instrumentation.runOnMainSync(() -> {
                    for (int id : ids()) {
                        assertFalse(ui.field(id).isEnabled());
                        ui.field(id).setText("999");
                    }
                    assertFalse(ui.button(R.id.task_budget_toggle).isEnabled());
                });
                ui.assertValues("12", "45", "180");
                release.countDown();
                ui.awaitReady();
                assertNull(failure.get());
                assertEquals(new TaskBudget(12, 45, 180_000), ui.store.load().taskBudget);
                assertEquals(Mode.CONFIRM_ALL, ui.store.load().executionMode);
                ui.assertIdleSecure();
            } finally { release.countDown(); }
        }
    }

    private static int[] ids() { return new int[]{R.id.task_budget_rounds, R.id.task_budget_tools, R.id.task_budget_seconds}; }

    private static final class BudgetUi implements AutoCloseable {
        final DeviceHarness device;
        final SharedPreferences preferences;
        final Map<String, ?> original;
        final SettingsStore store;
        Activity activity;

        BudgetUi(boolean saved) throws Exception {
            device = new DeviceHarness();
            String profile = SettingsStore.currentProfileId(device.context);
            preferences = device.context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            original = preferences.getAll();
            store = new SettingsStore(device.context);
            try {
                assertFalse(AgentController.get(device.context).getState().running);
                assertTrue(preferences.edit().clear().commit());
                if (saved) {
                    SettingsStore.SavedSettings settings = store.load();
                    settings.endpoint = "https://task-budget.example.invalid/v1";
                    settings.model = "synthetic-budget-ui-model";
                    settings.apiKey = "synthetic-budget-ui-key";
                    settings.allowedPackages.add(DeviceHarness.FIXTURE);
                    settings.grants.put(DeviceHarness.FIXTURE, SettingsStore.captureGrant(device.context, DeviceHarness.FIXTURE));
                    store.save(settings);
                }
                open();
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void open() throws Exception {
            activity = device.instrumentation.startActivitySync(new Intent(device.context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitReady();
            device.hideOwnKeyboardAndObserve(activity, device.tools, device.control);
        }
        void reopen() throws Exception { finish(); open(); }
        EditText field(int id) { return activity.findViewById(id); }
        Button button(int id) { return activity.findViewById(id); }
        String text(int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
        void awaitReady() {
            AtomicBoolean ready = new AtomicBoolean();
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            do {
                device.instrumentation.runOnMainSync(() -> ready.set(button(R.id.save_settings).isEnabled()
                        && field(R.id.task_budget_rounds).isEnabled() && field(R.id.task_budget_seconds).isEnabled()));
                if (!ready.get()) SystemClock.sleep(25);
            } while (!ready.get() && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Budget controls did not become ready", ready.get());
        }
        void seedModelFields() {
            device.instrumentation.runOnMainSync(() -> {
                field(R.id.settings_endpoint).setText("https://task-budget.example.invalid/v1");
                field(R.id.settings_model).setText("synthetic-budget-ui-model");
                field(R.id.settings_api_key).setText("synthetic-budget-ui-key");
            });
        }
        void edit(String rounds, String tools, String seconds) {
            device.instrumentation.runOnMainSync(() -> {
                if (activity.findViewById(R.id.task_budget_fields).getVisibility() != View.VISIBLE) button(R.id.task_budget_toggle).performClick();
                field(R.id.task_budget_rounds).setText(rounds);
                field(R.id.task_budget_tools).setText(tools);
                field(R.id.task_budget_seconds).setText(seconds);
            });
        }
        void assertValues(String rounds, String tools, String seconds) {
            device.instrumentation.runOnMainSync(() -> {
                assertEquals(rounds, text(R.id.task_budget_rounds));
                assertEquals(tools, text(R.id.task_budget_tools));
                assertEquals(seconds, text(R.id.task_budget_seconds));
            });
        }
        void showModelSave() {
            if (!button(R.id.save_settings).isShown()) button(R.id.configuration_toggle).performClick();
        }
        void save() {
            device.instrumentation.runOnMainSync(() -> { showModelSave(); assertTrue(button(R.id.save_settings).performClick()); });
            awaitReady();
        }
        void assertIdleSecure() {
            device.instrumentation.runOnMainSync(() -> {
                assertFalse(AgentController.get(device.context).getState().running);
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
        }
        void finish() {
            if (activity == null) return;
            device.instrumentation.runOnMainSync(activity::finish);
            device.instrumentation.waitForIdleSync();
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (!activity.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            assertTrue(activity.isDestroyed());
            activity = null;
        }
        @Override public void close() throws Exception {
            try { finish(); }
            finally {
                try { restore(); }
                finally { device.close(); }
            }
        }
        private void restore() throws IOException {
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
            if (!editor.commit()) throw new IOException("Could not restore original budget-test settings");
        }
    }
}
