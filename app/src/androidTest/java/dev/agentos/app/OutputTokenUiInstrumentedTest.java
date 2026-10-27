package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.RunControl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Selected output limits pass through the actual native UI, saved lease and real PNG HTTP probes. */
@RunWith(AndroidJUnit4.class)
public final class OutputTokenUiInstrumentedTest {
    private static final String SAVED = "测试已保存配置的图像理解";
    private static final String DRAFT = "测试当前填写的图像理解";
    private static final String CURRENT = "已保存配置记录：当前有效";
    private static final String VERIFIED = "图像能力：本次合成图片已验证";
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("secure_output_token_configuration_capture_not_attempted");

    @Test public void invalidOutputLimitsRemainVisibleAndCannotSaveOrIssueAnyKindOfProbe() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            Map<String, ?> original = ui.preferences.getAll(); SettingsStore.ConfigurationLease saved = ui.lease;
            assertEquals("2048", ui.onMain(() -> ((EditText) ui.activity.findViewById(R.id.settings_max_output_tokens)).getText().toString()));
            for (String invalid : new String[]{"", "0", "127", "8193", "-1", "1.5"}) {
                ui.setText(R.id.settings_max_output_tokens, invalid);
                ui.click(R.id.test_model); ui.click(R.id.test_model_vision); ui.click(R.id.save_settings);
                ui.onMain(() -> { EditText field = ui.activity.findViewById(R.id.settings_max_output_tokens);
                    assertEquals(invalid, field.getText().toString()); assertNotNull(field.getError());
                    assertFalse(String.valueOf(field.getError()).contains(LocalVisionProbeServer.KEY)); return null; });
                assertFalse(ui.state().probing); assertNull(ui.state().modelProbe); assertEquals(original, ui.preferences.getAll());
                assertTrue(SettingsStore.isCurrent(saved)); assertNull(SavedVisionProbe.process().current(saved));
            }
            ui.setText(R.id.settings_max_output_tokens, "8193"); ui.recreateWithTheActualRetainedDraft();
            assertEquals("8193", ui.onMain(() -> ((EditText) ui.activity.findViewById(R.id.settings_max_output_tokens)).getText().toString()));
            assertEquals(DRAFT, ui.state().label); ui.click(R.id.test_model_vision); ui.click(R.id.save_settings);
            assertEquals(original, ui.preferences.getAll()); assertSame(saved, ui.store.loadWithLease().lease);
            assertFalse(server.awaitRequests(1, 150)); assertTrue(server.receivedMaxTokens().isEmpty());
            assertEquals(2048, ui.store.load().maxOutputTokens); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void selectedValueReachesDraftToolDraftImageAndActuallySavedImageHttpRequests() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            ui.setText(R.id.settings_max_output_tokens, "4096"); assertEquals(DRAFT, ui.state().label);
            ui.beginAndTrustFirstRequest(); ui.awaitState(state -> state.result.contains(VERIFIED) && !state.probing);
            assertEquals(java.util.Arrays.asList(4096), server.receivedMaxTokens()); assertNull(ui.owner());
            ui.click(R.id.test_model); ui.awaitState(state -> state.result.contains("工具调用：已验证") && !state.probing);
            assertEquals(java.util.Arrays.asList(4096, 4096), server.receivedMaxTokens());
            assertEquals("Draft testing must not silently persist the new limit", 2048, ui.store.load().maxOutputTokens);
            ui.saveInUi(); assertEquals(4096, ui.store.load().maxOutputTokens); assertEquals(SAVED, ui.state().label);
            ui.click(R.id.test_model_vision); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            ui.assertCurrent(ui.owner()); assertEquals(java.util.Arrays.asList(4096, 4096, 4096), server.receivedMaxTokens());
            assertEquals(2, server.imageRequestCount()); assertEquals(1, server.toolRequestCount());
            assertFalse(server.awaitRequests(4, 150)); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void changingTheLimitRevokesAnOldHttpPromptAndTheActualSavedVisionRecord() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            ui.click(R.id.test_model_vision); AccessibilityNodeInfo oldTrust = ui.findTrustButton();
            try {
                assertFalse(server.awaitRequests(1, 150)); ui.setText(R.id.settings_max_output_tokens, "3072");
                oldTrust.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                assertFalse("Old consent must not authorize a different limit", server.awaitRequests(1, 150));
            } finally { oldTrust.recycle(); }
            ui.beginAndTrustFirstRequest(); ui.awaitState(state -> state.result.contains(VERIFIED) && !state.probing);
            assertEquals(java.util.Arrays.asList(3072), server.receivedMaxTokens()); assertNull(ui.owner());
            ui.saveInUi(); ui.click(R.id.test_model_vision); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            Owner saved = ui.owner(); ui.assertCurrent(saved);
            ui.setText(R.id.settings_max_output_tokens, "128");
            assertTrue(saved.control.isCancelled()); assertFalse(saved.verification.isCurrentFor(ui.lease)); ui.assertExpiryRemoved(saved);
            assertNull(ui.owner()); assertFalse(ui.state().result.contains(CURRENT)); assertEquals(DRAFT, ui.state().label);
            ui.setText(R.id.settings_max_output_tokens, "3072"); assertEquals(DRAFT, ui.state().label);
            assertEquals(java.util.Arrays.asList(3072, 3072), server.receivedMaxTokens());
            assertFalse(server.awaitRequests(3, 150)); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    /** Reflection is read-only and limited to the real Activity's ownership, not manufactured success. */
    private static final class Owner {
        final Object identity; final RunControl control; final SettingsStore.ConfigurationLease lease;
        final long epoch; final SavedVisionProbe.Verification verification; final Runnable expiry;
        Owner(Object actual) throws Exception {
            identity = actual; control = (RunControl) field(actual, "control"); lease = (SettingsStore.ConfigurationLease) field(actual, "lease");
            epoch = (Long) field(actual, "epoch"); verification = (SavedVisionProbe.Verification) field(actual, "verification"); expiry = (Runnable) field(actual, "expiry");
        }
    }
    private static final class UiState {
        String label, result; boolean probing, visionEnabled, toolEnabled, saveEnabled; RunControl modelProbe;
    }
    private static final class SavedUi implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final LocalVisionProbeServer server; final SettingsStore store;
        final SharedPreferences preferences; final Map<String, ?> originalPreferences;
        final List<SettingsStore.ConfigurationLease> leases = new ArrayList<>(); final List<RunControl> observedOwners = new ArrayList<>();
        SettingsStore.ConfigurationLease lease; MainActivity activity; boolean closed;
        SavedUi(LocalVisionProbeServer server) throws Exception {
            this.server = server; store = new SettingsStore(context);
            assertFalse("Synthetic UI must not interrupt a running task", AgentController.get(context).getState().running);
            await(() -> ImageWorkset.process().reservedBytes() == 0, "Previous payload owners have not drained");
            String profile = SettingsStore.currentProfileId(context);
            preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            originalPreferences = preferences.getAll(); assertTrue(preferences.edit().clear().commit());
            try {
                SettingsStore.SavedSettings saved = store.load(); saved.endpoint = server.endpoint(); saved.model = LocalVisionProbeServer.MODEL;
                saved.apiKey = LocalVisionProbeServer.KEY; lease = store.save(saved); leases.add(lease);
                assertSame(lease, store.loadWithLease().lease); assertNull(SavedVisionProbe.process().current(lease));
                activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                await(() -> onMain(() -> button(R.id.test_model_vision).isEnabled()
                        && server.endpoint().equals(((EditText) activity.findViewById(R.id.settings_endpoint)).getText().toString())), "Saved configuration did not load");
                onMain(() -> {
                    if (!button(R.id.test_model_vision).isShown()) assertTrue(button(R.id.configuration_toggle).performClick());
                    ((EditText) activity.findViewById(R.id.task_prompt)).setText(LocalVisionProbeServer.PRIVATE_TASK); return null;
                });
                assertEquals(SAVED, state().label); assertSecureAndIdle();
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        Button button(int id) { return activity.findViewById(id); }
        void click(int id) throws Exception {
            onMain(() -> { Button target = button(id); assertTrue("Actual button must be enabled", target.isEnabled());
                target.requestRectangleOnScreen(new Rect(0, 0, Math.max(1, target.getWidth()), Math.max(1, target.getHeight())), true);
                assertTrue(target.performClick()); return null; });
        }
        void setText(int id, String value) throws Exception { onMain(() -> { ((EditText) activity.findViewById(id)).setText(value); return null; }); }
        UiState state() throws Exception {
            return onMain(() -> { UiState result = new UiState(); result.label = button(R.id.test_model_vision).getText().toString();
                result.result = ((TextView) activity.findViewById(R.id.model_test_result)).getText().toString();
                result.probing = button(R.id.cancel_model_test).getVisibility() == View.VISIBLE;
                result.visionEnabled = button(R.id.test_model_vision).isEnabled(); result.toolEnabled = button(R.id.test_model).isEnabled();
                result.saveEnabled = button(R.id.save_settings).isEnabled(); result.modelProbe = (RunControl) field(activity, "modelProbe"); return result; });
        }
        Owner owner() throws Exception {
            Owner actual = onMain(() -> { Object value = field(activity, "savedProbeOwner"); return value == null ? null : new Owner(value); });
            if (actual != null && !observedOwners.contains(actual.control)) observedOwners.add(actual.control); return actual;
        }
        void assertCurrent(Owner owner) throws Exception {
            assertNotNull(owner); assertSame(lease, owner.lease); assertNotNull(owner.verification);
            assertFalse(owner.control.isCancelled()); assertEquals(owner.epoch, owner.control.getExecutionEpoch());
            assertTrue(owner.verification.isCurrentFor(lease)); assertTrue(owner.verification.isCurrentFor(lease, owner.control, owner.epoch));
            assertSame(owner.verification, SavedVisionProbe.process().current(lease));
            assertEquals(0, owner.control.getDispatchAttempts()); assertEquals(0, owner.control.budgetSnapshot().toolCalls);
            // This checks the actual pending expiry callback, not ten minutes of
            // elapsed UI behavior; TTL boundaries have separate registry tests.
            onMain(() -> { assertNotNull(owner.expiry); assertTrue(((Handler) field(activity, "main")).hasCallbacks(owner.expiry)); return null; });
        }
        void assertExpiryRemoved(Owner owner) throws Exception {
            assertNotNull(owner.expiry);
            onMain(() -> { assertFalse(((Handler) field(activity, "main")).hasCallbacks(owner.expiry)); return null; });
        }
        UiState awaitState(Predicate<UiState> expected) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 15_000; UiState current;
            do { current = state(); if (expected.test(current)) return current; SystemClock.sleep(20); }
            while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("Saved probe UI did not reach the expected state: " + current.label + " | " + current.result);
        }
        void beginAndTrustFirstRequest() throws Exception {
            int before = server.requestCount(); click(R.id.test_model_vision);
            assertFalse("A loaded loopback address still needs actual user trust", server.awaitRequests(before + 1, 150));
            AccessibilityNodeInfo trust = findTrustButton();
            try { assertTrue(trust.performAction(AccessibilityNodeInfo.ACTION_CLICK)); } finally { trust.recycle(); }
        }
        private AccessibilityNodeInfo findTrustButton() throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            do {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root != null) try {
                    if (context.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText("信任此连接"); AccessibilityNodeInfo selected = null;
                        try { for (AccessibilityNodeInfo item : found)
                            if (selected == null && "信任此连接".contentEquals(item.getText() == null ? "" : item.getText()) && item.isClickable() && item.isEnabled()) selected = AccessibilityNodeInfo.obtain(item);
                        } finally { for (AccessibilityNodeInfo item : found) item.recycle(); }
                        if (selected != null) return selected;
                    }
                } finally { root.recycle(); }
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("Actual HTTP trust dialog was not shown");
        }
        void saveInUi() throws Exception { SettingsStore.ConfigurationLease previous = store.loadWithLease().lease; click(R.id.save_settings); awaitSave(previous); }
        void awaitSave(SettingsStore.ConfigurationLease previous) throws Exception {
            awaitState(state -> state.saveEnabled && !state.probing && SAVED.equals(state.label));
            lease = store.loadWithLease().lease; assertNotNull(lease); assertNotSame(previous, lease); assertTrue(SettingsStore.isCurrent(lease)); leases.add(lease);
        }
        void assertNoLateRecord() throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 500;
            do { assertNull(SavedVisionProbe.process().current(lease)); assertFalse(state().result.contains(CURRENT)); SystemClock.sleep(20); }
            while (SystemClock.elapsedRealtime() < deadline);
        }
        void assertSecureAndIdle() throws Exception {
            onMain(() -> { assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertFalse(AgentController.get(context).getState().running); assertTrue(button(R.id.test_model).isEnabled());
                assertTrue(button(R.id.test_model_vision).isEnabled()); return null; });
            assertFalse(state().probing); assertNull(state().modelProbe);
        }
        void recreateWithTheActualRetainedDraft() throws Exception {
            MainActivity previous = activity;
            ExecutorService previousWorker = (ExecutorService) field(previous, "storageWorker");
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
            try {
                onMain(() -> { previous.recreate(); return null; });
                Activity replacement = monitor.waitForActivityWithTimeout(10_000);
                assertNotNull("Actual Activity recreation did not return", replacement); assertNotSame(previous, replacement);
                assertTrue(replacement instanceof MainActivity); activity = (MainActivity) replacement;
                await(() -> onMain(() -> button(R.id.test_model_vision).isEnabled()
                        && server.endpoint().equals(((EditText) activity.findViewById(R.id.settings_endpoint)).getText().toString())), "Recreated configuration did not load");
                assertTrue(previous.isDestroyed()); assertTrue("Replaced Activity worker did not exit", previousWorker.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                // Preserve ownership of a replacement even if a following check
                // fails, so ordinary test cleanup still finishes the real host.
                Activity latest = monitor.getLastActivity();
                if (latest instanceof MainActivity && latest != previous) activity = (MainActivity) latest;
                instrumentation.removeMonitor(monitor);
            }
        }
        void finish() throws Exception {
            if (activity != null && !activity.isDestroyed()) { onMain(() -> { activity.finish(); return null; }); await(() -> activity.isDestroyed(), "Activity did not actually finish"); }
        }
        <T> T onMain(CheckedSupplier<T> action) throws Exception {
            AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> { try { result.set(action.get()); } catch (Exception | Error failed) { failure.set(failed); } });
            Throwable caught = failure.get(); if (caught instanceof Error) throw (Error) caught; if (caught instanceof Exception) throw (Exception) caught;
            return result.get();
        }
        @Override public void close() throws Exception {
            if (closed) return; closed = true; Throwable failure = null;
            try {
                try { finish(); } finally { server.releaseResponse(); }
                if (activity != null) {
                    ExecutorService worker = (ExecutorService) field(activity, "storageWorker");
                    assertTrue("Actual Activity settings worker still runs after destroy", worker.awaitTermination(5, TimeUnit.SECONDS));
                }
                await(() -> ImageWorkset.process().reservedBytes() == 0, "Actual HTTP payload owner has not drained");
                for (RunControl owner : observedOwners) assertTrue("Leaving the Activity retained a probe owner", owner.isCancelled());
                for (SettingsStore.ConfigurationLease original : leases) assertNull(SavedVisionProbe.process().current(original));
                server.assertHealthy();
            } catch (Exception | Error failed) { failure = failed; }
            finally { try { restorePreferences(); } catch (Exception | Error cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); } }
            if (failure instanceof Error) throw (Error) failure; if (failure instanceof Exception) throw (Exception) failure;
        }
        private void restorePreferences() throws IOException {
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Set<?>) { @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value; editor.putStringSet(entry.getKey(), values); }
            }
            if (!editor.commit()) throw new IOException("Could not restore saved probe UI configuration");
        }
    }
    private interface CheckedSupplier<T> { T get() throws Exception; }
    private interface CheckedCondition { boolean get() throws Exception; }
    private static Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void await(CheckedCondition condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        while (!condition.get() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.get());
    }
}
