package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Protected, local recovery of synthetic settings; no reset, network probe or device action. */
@RunWith(AndroidJUnit4.class)
public final class ConfigurationRecoveryUiInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_configuration_recovery_capture_not_attempted");

    @Test public void failedReadsPreserveOriginalBytesAndExplicitRetryLoadsOnlyPersistedConfiguration() throws Exception {
        try (RecoveryUi ui = new RecoveryUi(false)) {
            ui.assertFailed("原副本已保留");
            Map<String, ?> corrupt = ui.preferences.getAll();
            ui.retry(); ui.awaitFailed();
            assertEquals(corrupt, ui.preferences.getAll());
            assertFalse(SettingsStore.isCurrent(ui.originalLease));
            screenshots.capture("protected-unreadable-configuration-preserved");

            ui.installFutureFormat();
            Map<String, ?> future = ui.preferences.getAll();
            for (int attempt = 0; attempt < 2; attempt++) {
                ui.retry(); ui.awaitFailed();
                ui.assertFailed("支持该格式的应用版本");
                assertEquals("Retry must preserve every unknown-format preference", future, ui.preferences.getAll());
            }
            screenshots.capture("protected-unknown-format-repeated-read-preserved");

            ui.restoreSaved();
            ui.retry(); ui.awaitHealthy();
            ui.onMain(() -> {
                assertEquals(ui.saved.endpoint, ui.text(R.id.settings_endpoint));
                assertEquals(ui.saved.apiKey, ui.text(R.id.settings_api_key));
                assertEquals(ui.saved.model, ui.text(R.id.settings_model));
                assertEquals("", ui.text(R.id.task_prompt));
                assertEquals(View.GONE, ui.reload().getVisibility());
                assertTrue(ui.message().contains("未启动任务或模型测试"));
                assertTrue(SettingsStore.isCurrent((SettingsStore.ConfigurationLease) field(ui.activity, "savedConfigurationLease")));
            });
            assertEquals(ui.persisted, ui.preferences.getAll());
            ui.assertSecureAndUnchanged();
            screenshots.capture("protected-persisted-configuration-reloaded-without-execution");
        }
    }

    @Test public void pendingRetryBlocksRepeatedClicksAndStaleResetConfirmation() throws Exception {
        try (RecoveryUi ui = new RecoveryUi(true); WorkerHold hold = new WorkerHold(ui)) {
            Map<String, ?> future = ui.preferences.getAll();
            AtomicReference<Button> resetPositive = new AtomicReference<>();
            ui.onMain(() -> {
                assertTrue(ui.activity.findViewById(R.id.reset_settings).performClick());
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    Button candidate = root.findViewById(android.R.id.button1);
                    if (candidate != null && "删除并重新配置".contentEquals(candidate.getText())) resetPositive.set(candidate);
                }
                assertNotNull("The real reset confirmation must be shown before the retry race", resetPositive.get());
            });
            ui.retry();
            long request = ui.onMainLong("configurationLoadGeneration");
            ui.onMain(() -> {
                assertFalse(ui.reload().isEnabled());
                assertFalse(ui.activity.findViewById(R.id.reset_settings).isEnabled());
                assertFalse(ui.activity.findViewById(R.id.save_settings).isEnabled());
                assertFalse(ui.activity.findViewById(R.id.start_task).isEnabled());
                assertFalse(ui.activity.findViewById(R.id.test_model).isEnabled());
                // Queued clicks must still be rejected by the listeners, not just their disabled views.
                assertTrue(ui.reload().performClick());
                assertTrue(ui.reload().performClick());
                assertEquals(request, ((Long) field(ui.activity, "configurationLoadGeneration")).longValue());
            });
            assertEquals(future, ui.preferences.getAll());
            hold.release(); ui.awaitFailed();
            ui.assertFailed("支持该格式的应用版本");
            assertEquals(future, ui.preferences.getAll());
            assertEquals(request, ui.onMainLong("configurationLoadGeneration"));
            ui.onMain(() -> {
                assertFalse((Boolean) field(ui.activity, "busy"));
                assertTrue("Keep the actual old reset dialog until the retry has finished", resetPositive.get().isAttachedToWindow());
                assertTrue(resetPositive.get().performClick());
                assertFalse("A stale dialog must be rejected even after busy becomes false", (Boolean) field(ui.activity, "busy"));
            });
            ui.instrumentation.waitForIdleSync();
            ((ExecutorService) field(ui.activity, "storageWorker")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            ui.instrumentation.waitForIdleSync();
            assertEquals(future, ui.preferences.getAll());
            // Restore the synthetic valid pair only to verify the original Keystore key still decrypts it.
            ui.restoreSaved();
            assertEquals(ui.saved.apiKey, ui.store.load().apiKey);
            ui.assertSecureAndUnchanged();
            screenshots.capture("protected-single-retry-and-stale-reset-rejection");
        }
    }

    @Test public void leavingDuringRetryDiscardsLateResultsUntilAnotherExplicitRead() throws Exception {
        try (RecoveryUi ui = new RecoveryUi(true); WorkerHold hold = new WorkerHold(ui)) {
            ui.restoreSaved();
            ui.retry();
            long request = ui.onMainLong("configurationLoadGeneration");
            ui.onMain(() -> assertTrue("The real task must move behind the launcher", ui.activity.moveTaskToBack(true)));
            ui.await(() -> !(Boolean) field(ui.activity, "resumed"), "Activity did not actually pause");
            assertTrue(ui.onMainLong("configurationLoadGeneration") > request);
            hold.release();
            ui.await(() -> !(Boolean) field(ui.activity, "busy"), "Late recovery read did not settle");
            ui.onMain(() -> {
                assertTrue((Boolean) field(ui.activity, "storageDamaged"));
                assertEquals("", ui.text(R.id.settings_model));
                assertNull(field(ui.activity, "savedConfigurationLease"));
                assertFalse(ui.activity.findViewById(R.id.save_settings).isEnabled());
                assertFalse(ui.reload().isEnabled());
            });
            long cancelled = ui.onMainLong("configurationLoadGeneration");
            ui.onMain(() -> ui.context.startActivity(new Intent(ui.context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            ui.await(() -> (Boolean) field(ui.activity, "resumed") && ui.reload().isEnabled(),
                    "Returning to the same Activity must offer another explicit retry");
            ui.onMain(() -> {
                assertEquals(cancelled, ((Long) field(ui.activity, "configurationLoadGeneration")).longValue());
                assertTrue((Boolean) field(ui.activity, "storageDamaged"));
                assertEquals("", ui.text(R.id.settings_model));
                assertTrue(ui.message().contains("重新读取已取消"));
            });
            assertEquals(ui.persisted, ui.preferences.getAll());
            screenshots.capture("protected-late-read-discarded-after-leaving");
            ui.retry(); ui.awaitHealthy();
            ui.onMain(() -> assertEquals(ui.saved.model, ui.text(R.id.settings_model)));
            ui.assertSecureAndUnchanged();
            screenshots.capture("protected-explicit-retry-after-return");
        }
    }

    private static final class RecoveryUi implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final AgentController controller = AgentController.get(context);
        final AgentController.State before = controller.getState();
        final SharedPreferences preferences;
        final Map<String, ?> original, persisted;
        final SettingsStore store;
        final SettingsStore.SavedSettings saved;
        final SettingsStore.ConfigurationLease originalLease;
        Activity activity;

        RecoveryUi(boolean future) throws Exception {
            assertFalse("Recovery tests require an idle controller", before.running);
            String profile = SettingsStore.currentProfileId(context);
            preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            original = preferences.getAll();
            try {
                assertTrue(preferences.edit().clear().commit());
                store = new SettingsStore(context);
                saved = store.load();
                saved.endpoint = "https://recovery.example.invalid/v1";
                saved.apiKey = "synthetic-recovery-key-do-not-display";
                saved.model = "synthetic-recovery-persisted-model";
                originalLease = store.save(saved);
                persisted = preferences.getAll();
                if (future) installFutureFormat();
                else assertTrue(preferences.edit().putString("encrypted_settings", "synthetic-unreadable-ciphertext-marker").commit());
                activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                awaitFailed();
            } catch (Throwable failure) {
                try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void installFutureFormat() throws Exception {
            JSONObject future = new JSONObject((String) persisted.get("encrypted_settings"));
            future.put("version", 999);
            future.put("syntheticUnknownField", "synthetic-untrusted-error-text-do-not-display");
            assertTrue(preferences.edit().putString("encrypted_settings", future.toString()).commit());
        }

        void restoreSaved() { replacePreferences(persisted); }
        void onMain(Runnable action) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { action.run(); } catch (Throwable error) { failure.set(error); }
            });
            Throwable error = failure.get();
            if (error instanceof Error) throw (Error) error;
            if (error instanceof RuntimeException) throw (RuntimeException) error;
            if (error != null) throw new AssertionError(error);
        }
        Button reload() { return activity.findViewById(R.id.reload_settings); }
        String text(int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
        String message() { return ((TextView) field(activity, "messageText")).getText().toString(); }

        void retry() {
            onMain(() -> {
                assertTrue(reload().isShown());
                assertTrue(reload().isEnabled());
                reload().requestRectangleOnScreen(new Rect(0, 0, Math.max(1, reload().getWidth()), Math.max(1, reload().getHeight())), true);
                assertTrue(reload().performClick());
                assertTrue((Boolean) field(activity, "busy"));
            });
        }

        long onMainLong(String name) {
            AtomicReference<Long> value = new AtomicReference<>();
            onMain(() -> value.set((Long) field(activity, name)));
            return value.get();
        }

        void awaitFailed() {
            await(() -> (Boolean) field(activity, "resumed") && !(Boolean) field(activity, "busy")
                    && (Boolean) field(activity, "storageDamaged") && reload().isEnabled(), "Recovery failure did not become ready");
        }

        void awaitHealthy() {
            await(() -> !(Boolean) field(activity, "busy") && !(Boolean) field(activity, "storageDamaged")
                    && activity.findViewById(R.id.save_settings).isEnabled(), "Explicit recovery read did not load valid settings");
        }

        void await(BooleanSupplier condition, String failure) {
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            do {
                onMain(() -> ready.set(condition.getAsBoolean()));
                if (ready.get()) return;
                SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtime() < deadline);
            fail(failure);
        }

        void assertFailed(String explanation) {
            onMain(() -> {
                assertTrue(reload().isShown());
                assertTrue(reload().isEnabled());
                assertTrue(message().contains(explanation));
                assertFalse(message().contains(saved.apiKey));
                assertFalse(message().contains("synthetic-untrusted"));
                assertFalse(message().contains("synthetic-unreadable"));
                assertFalse(message().contains("ciphertext"));
                assertFalse(activity.findViewById(R.id.save_settings).isEnabled());
                assertFalse(activity.findViewById(R.id.start_task).isEnabled());
                assertFalse(activity.findViewById(R.id.test_model).isEnabled());
                assertNull(field(activity, "savedConfigurationLease"));
            });
            assertSecureAndUnchanged();
        }

        void assertSecureAndUnchanged() {
            onMain(() -> {
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertNull(field(activity, "modelProbe"));
                assertNull(field(activity, "savedProbeOwner"));
                AgentController.State after = controller.getState();
                assertFalse(after.running);
                assertEquals(before.paused, after.paused);
                assertEquals(before.status, after.status);
                assertEquals(before.summary, after.summary);
                assertEquals(before.approvalId, after.approvalId);
                assertSame(before.usage, after.usage);
            });
        }

        void replacePreferences(Map<String, ?> values) {
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                Object value = entry.getValue(); String name = entry.getKey();
                if (value instanceof String) editor.putString(name, (String) value);
                else if (value instanceof Boolean) editor.putBoolean(name, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(name, (Integer) value);
                else if (value instanceof Long) editor.putLong(name, (Long) value);
                else if (value instanceof Float) editor.putFloat(name, (Float) value);
                else if (value instanceof Set<?>) {
                    @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                    editor.putStringSet(name, strings);
                } else throw new AssertionError("Unexpected preference type");
            }
            assertTrue("Synthetic recovery settings must restore exactly", editor.commit());
        }

        @Override public void close() throws Exception {
            try {
                if (activity != null) {
                    onMain(activity::finish);
                    await(activity::isDestroyed, "Recovery Activity did not close");
                    ExecutorService worker = (ExecutorService) field(activity, "storageWorker");
                    assertTrue("Recovery worker must settle before restoring test preferences", worker.awaitTermination(5, TimeUnit.SECONDS));
                }
            } finally {
                // Never delete the existing Keystore key: the original encrypted copy still needs it.
                replacePreferences(original);
            }
        }
    }

    /** Holds the real single storage worker without replacing its store, reads, results or UI state. */
    private static final class WorkerHold implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        final Future<?> held;
        WorkerHold(RecoveryUi ui) throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            held = ((ExecutorService) field(ui.activity, "storageWorker")).submit(() -> {
                entered.countDown();
                try { assertTrue("Synthetic storage gate exceeded its bound", release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            });
            assertTrue("Actual storage worker did not enter the gate", entered.await(5, TimeUnit.SECONDS));
        }
        void release() { release.countDown(); }
        @Override public void close() throws Exception { release(); held.get(5, TimeUnit.SECONDS); }
    }

    private static Object field(Object owner, String name) {
        try {
            Field value = owner.getClass().getDeclaredField(name);
            value.setAccessible(true);
            return value.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
