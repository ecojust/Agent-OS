package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.agentos.core.ActionConfirmationPolicy;
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

/** Exercises native choices and encrypted saves without enabling or executing device actions. */
@RunWith(AndroidJUnit4.class)
public final class ExecutionModeUiInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_execution_mode_configuration_capture_not_attempted");

    @Test public void firstOpenDefaultsToConfirmationAndExplainsTheLimitedNavigationScope() throws Exception {
        try (ModeUi ui = new ModeUi(false)) {
            ui.assertSelected(R.id.execution_mode_confirm_all);
            ui.instrumentation.runOnMainSync(() -> {
                assertEquals("逐步确认（默认）", ui.option(R.id.execution_mode_confirm_all).getText().toString());
                assertEquals("连续导航", ui.option(R.id.execution_mode_continuous_navigation).getText().toString());
                String explanation = ((TextView) ui.activity.findViewById(R.id.execution_mode_explanation)).getText().toString();
                assertTrue(explanation.contains("打开已授权应用、滚动和回到桌面"));
                assertTrue(explanation.contains("返回、点击和输入仍须确认"));
                assertTrue(explanation.contains("模型不能更改"));
                assertFalse(ui.option(R.id.execution_mode_continuous_navigation).isSaveEnabled());
            });
            assertTrue("Opening settings must not create or rewrite configuration", ui.preferences.getAll().isEmpty());
            ui.assertSecureAndIdle();
        }
    }

    @Test public void explicitSelectionSavesAndBothChoicesSurviveClosingAndReopening() throws Exception {
        try (ModeUi ui = new ModeUi(true)) {
            SettingsStore.ConfigurationLease original = ui.store.loadWithLease().lease;
            ui.choose(R.id.execution_mode_continuous_navigation);
            assertEquals("A draft choice must not silently change saved authorization",
                    ActionConfirmationPolicy.Mode.CONFIRM_ALL, ui.store.load().executionMode);
            ui.saveAndAwait();
            assertFalse(SettingsStore.isCurrent(original));
            SettingsStore.SavedSettings continuous = ui.store.load();
            assertEquals(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION, continuous.executionMode);
            assertTrue(continuous.confirmEachAction);
            ui.reopen();
            ui.assertSelected(R.id.execution_mode_continuous_navigation);

            ui.choose(R.id.execution_mode_confirm_all);
            ui.saveAndAwait();
            assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, ui.store.load().executionMode);
            ui.reopen();
            ui.assertSelected(R.id.execution_mode_confirm_all);
            ui.assertSecureAndIdle();
        }
    }

    @Test public void closingAnUnsavedChoiceDoesNotAuthorizeContinuousNavigation() throws Exception {
        try (ModeUi ui = new ModeUi(true)) {
            Map<String, ?> saved = ui.preferences.getAll();
            SettingsStore.ConfigurationLease lease = ui.store.loadWithLease().lease;
            ui.choose(R.id.execution_mode_continuous_navigation);
            ui.reopen();
            ui.assertSelected(R.id.execution_mode_confirm_all);
            assertEquals(saved, ui.preferences.getAll());
            assertTrue(SettingsStore.isCurrent(lease));
            assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, ui.store.load().executionMode);
            ui.assertSecureAndIdle();
        }
    }

    @Test public void savingFreezesTheSelectedModeAndRejectsALateChoiceUntilItFinishes() throws Exception {
        try (ModeUi ui = new ModeUi(true)) {
            CountDownLatch saving = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            SettingsStore.ConfigurationLease original = ui.store.loadWithLease().lease;
            try (SettingsStore.Registration watcher = ui.store.watch(original, () -> {
                saving.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic save gate timed out");
                } catch (Throwable failure) { callbackFailure.set(failure); }
            })) {
                ui.choose(R.id.execution_mode_continuous_navigation);
                ui.clickSave();
                assertTrue("The real encrypted save must invalidate its old lease first", saving.await(5, TimeUnit.SECONDS));
                ui.instrumentation.runOnMainSync(() -> {
                    assertFalse(ui.option(R.id.execution_mode_confirm_all).isEnabled());
                    assertFalse(ui.option(R.id.execution_mode_continuous_navigation).isEnabled());
                    // A queued/programmatic click must not mutate the frozen request either.
                    ui.option(R.id.execution_mode_confirm_all).performClick();
                });
                ui.assertSelected(R.id.execution_mode_continuous_navigation);
                release.countDown();
                ui.awaitReady();
                assertNull(callbackFailure.get());
                assertEquals(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION, ui.store.load().executionMode);
                ui.assertSecureAndIdle();
            } finally { release.countDown(); }
        }
    }

    private static final class ModeUi implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final SharedPreferences preferences;
        final Map<String, ?> original;
        final SettingsStore store;
        Activity activity;

        ModeUi(boolean seedSavedConfiguration) throws Exception {
            assertFalse("Mode settings tests must not interrupt a running task", AgentController.get(context).getState().running);
            String profile = SettingsStore.currentProfileId(context);
            preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            original = preferences.getAll();
            assertTrue(preferences.edit().clear().commit());
            store = new SettingsStore(context);
            try {
                if (seedSavedConfiguration) {
                    SettingsStore.SavedSettings settings = store.load();
                    settings.endpoint = "https://execution-mode.example.invalid/v1";
                    settings.model = "synthetic-mode-ui-model";
                    settings.apiKey = "synthetic-mode-ui-key";
                    store.save(settings);
                }
                open();
            } catch (Throwable failure) {
                finish(); restore(); throw failure;
            }
        }

        void open() {
            Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = instrumentation.startActivitySync(intent);
            awaitReady();
        }

        void reopen() {
            finish();
            open();
        }

        void awaitReady() {
            AtomicBoolean ready = new AtomicBoolean();
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            do {
                instrumentation.runOnMainSync(() -> ready.set(option(R.id.execution_mode_confirm_all).isEnabled()
                        && option(R.id.execution_mode_continuous_navigation).isEnabled()
                        && activity.findViewById(R.id.save_settings).isEnabled()));
                if (!ready.get()) SystemClock.sleep(25);
            } while (!ready.get() && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Execution-mode controls did not become ready", ready.get());
        }

        RadioButton option(int id) { return activity.findViewById(id); }

        void choose(int id) {
            instrumentation.runOnMainSync(() -> {
                RadioButton option = option(id);
                assertTrue(option.isEnabled());
                option.requestRectangleOnScreen(new Rect(0, 0, Math.max(1, option.getWidth()), Math.max(1, option.getHeight())), true);
                // RadioButton toggles through its checked listener; without an
                // OnClickListener, performClick may return false after a valid toggle.
                option.performClick();
            });
            assertSelected(id);
        }

        void assertSelected(int id) {
            instrumentation.runOnMainSync(() -> assertEquals(id,
                    ((RadioGroup) activity.findViewById(R.id.confirm_actions)).getCheckedRadioButtonId()));
        }

        void clickSave() {
            instrumentation.runOnMainSync(() -> {
                Button save = activity.findViewById(R.id.save_settings);
                if (!save.isShown()) ((Button) activity.findViewById(R.id.configuration_toggle)).performClick();
                assertTrue(save.isEnabled());
                save.requestRectangleOnScreen(new Rect(0, 0, Math.max(1, save.getWidth()), Math.max(1, save.getHeight())), true);
                assertTrue(save.performClick());
                assertFalse("Save must freeze the controls before handing work to its worker",
                        option(R.id.execution_mode_continuous_navigation).isEnabled());
            });
        }

        void saveAndAwait() { clickSave(); awaitReady(); }

        void assertSecureAndIdle() {
            instrumentation.runOnMainSync(() -> {
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertFalse(AgentController.get(context).getState().running);
            });
        }

        void finish() {
            if (activity == null) return;
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (!activity.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            assertTrue("Previous native configuration screen did not close", activity.isDestroyed());
            activity = null;
        }

        @Override public void close() throws Exception {
            try { finish(); }
            finally { restore(); }
        }

        private void restore() throws IOException {
            // Preserve the production Keystore key so the original encrypted value remains readable.
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
            if (!editor.commit()) throw new IOException("Could not restore configuration after execution-mode UI test");
        }
    }
}
