package dev.agentos.app;

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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Actual encrypted settings, native UI and decoded loopback PNG challenges; no device image capture. */
@RunWith(AndroidJUnit4.class)
public final class SavedVisionProbeUiInstrumentedTest {
    private static final String SAVED = "测试已保存配置的图像理解";
    private static final String DRAFT = "测试当前填写的图像理解";
    private static final String CURRENT = "已保存配置记录：当前有效";
    private static final String VERIFIED = "图像能力：本次合成图片已验证";
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_saved_model_configuration_capture_not_attempted");

    @Test public void loadedSavedConfigurationKeepsItsVerifiedOwnerAfterTheButtonBecomesIdleAndToolTesting() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            assertEquals(SAVED, ui.state().label); assertNull(SavedVisionProbe.process().current(ui.lease));
            ui.beginAndTrustFirstRequest();
            ui.awaitState(state -> state.result.contains(VERIFIED) && state.result.contains(CURRENT) && !state.probing);
            Owner owner = ui.owner(); assertNotNull(owner); ui.assertCurrent(owner);
            assertNull(ui.state().modelProbe); assertTrue(ui.state().visionEnabled); assertTrue(ui.state().toolEnabled);
            assertEquals(600_000L, owner.verification.expiresAtElapsedRealtimeMillis - owner.verification.verifiedAtElapsedRealtimeMillis);
            assertTrue(owner.verification.verifiedAtElapsedRealtimeMillis <= SystemClock.elapsedRealtime());
            assertTrue(SystemClock.elapsedRealtime() < owner.verification.expiresAtElapsedRealtimeMillis);
            assertEquals(1, server.imageRequestCount()); assertEquals(0, server.toolRequestCount());
            screenshots.capture("saved-probe-current-record-secure-ui");
            ui.click(R.id.test_model);
            ui.awaitState(state -> state.result.contains("工具调用：已验证") && !state.probing);
            assertSame(owner.identity, ui.owner().identity); ui.assertCurrent(owner);
            assertTrue(ui.state().result.contains(CURRENT)); assertEquals(1, server.imageRequestCount()); assertEquals(1, server.toolRequestCount());
            assertFalse(server.awaitRequests(3, 150)); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void editingThenRestoringAFieldStillUsesDraftTestingUntilTheUserActuallySaves() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            SettingsStore.ConfigurationLease original = ui.lease;
            ui.setText(R.id.settings_model, LocalVisionProbeServer.MODEL + "-edited");
            ui.setText(R.id.settings_model, LocalVisionProbeServer.MODEL);
            assertEquals(DRAFT, ui.state().label);
            ui.beginAndTrustFirstRequest();
            ui.awaitState(state -> state.result.contains(VERIFIED) && !state.probing);
            assertFalse(ui.state().result.contains(CURRENT)); assertNull(ui.owner());
            assertNull(SavedVisionProbe.process().current(original));
            SettingsStore.LoadedSettings unchanged = ui.store.loadWithLease();
            assertSame(original, unchanged.lease); assertEquals(LocalVisionProbeServer.MODEL, unchanged.settings.model);
            assertEquals(1, server.imageRequestCount());
            ui.saveInUi(); assertNotSame(original, ui.lease); assertEquals(SAVED, ui.state().label);
            assertNull(SavedVisionProbe.process().current(ui.lease)); assertFalse(server.awaitRequests(2, 150));
            ui.click(R.id.test_model_vision);
            ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            ui.assertCurrent(ui.owner()); assertEquals(2, server.imageRequestCount()); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void sameValueSaveAndLaterConnectionEditsInvalidateTheActualSuccessfulOwner() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            ui.beginAndTrustFirstRequest(); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            Owner beforeSave = ui.owner(); ui.assertCurrent(beforeSave); SettingsStore.ConfigurationLease oldLease = ui.lease;
            ui.click(R.id.save_settings);
            // The actual save button revokes the old owner at initiation, before a
            // later successful disk write could accidentally preserve the record.
            assertTrue(beforeSave.control.isCancelled()); assertFalse(beforeSave.verification.isCurrentFor(oldLease)); ui.assertExpiryRemoved(beforeSave);
            ui.awaitSave(oldLease); assertNull(SavedVisionProbe.process().current(ui.lease));
            assertFalse(ui.state().result.contains(CURRENT)); assertEquals(SAVED, ui.state().label);
            ui.click(R.id.test_model_vision); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            Owner beforeEdit = ui.owner(); ui.assertCurrent(beforeEdit);
            ui.setText(R.id.settings_model, LocalVisionProbeServer.MODEL + "-edited");
            assertTrue(beforeEdit.control.isCancelled()); assertFalse(beforeEdit.verification.isCurrentFor(ui.lease)); ui.assertExpiryRemoved(beforeEdit);
            assertNull(ui.owner()); assertFalse(ui.state().result.contains(CURRENT)); assertEquals(DRAFT, ui.state().label);
            ui.setText(R.id.settings_model, LocalVisionProbeServer.MODEL); assertEquals(DRAFT, ui.state().label);
            assertFalse(server.awaitRequests(3, 150)); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void anUnverifiedRealRetestCannotKeepThePreviousSavedSuccess() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED_THEN_UNVERIFIED);
             SavedUi ui = new SavedUi(server)) {
            ui.beginAndTrustFirstRequest(); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            Owner original = ui.owner(); ui.assertCurrent(original);
            ui.click(R.id.test_model_vision);
            assertTrue(original.control.isCancelled()); assertFalse(original.verification.isCurrentFor(ui.lease)); ui.assertExpiryRemoved(original);
            ui.awaitState(state -> state.result.contains("图像能力：本次合成图片未验证") && !state.probing);
            assertFalse(ui.state().result.contains(CURRENT)); assertNull(SavedVisionProbe.process().current(ui.lease));
            assertNull(ui.owner()); assertEquals(2, server.imageRequestCount()); assertFalse(server.awaitRequests(3, 150));
            ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void cancelAndLeavingTheActivityDiscardDelayedSavedProbeReplies() throws Exception {
        for (boolean leave : new boolean[]{false, true}) {
            try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HELD_VERIFIED);
                 SavedUi ui = new SavedUi(server)) {
                ui.beginAndTrustFirstRequest(); assertTrue(server.awaitRequests(1, 10_000));
                UiState running = ui.state(); assertTrue(running.probing); assertNotNull(running.modelProbe);
                RunControl original = running.modelProbe;
                if (leave) ui.finish(); else ui.click(R.id.cancel_model_test);
                assertTrue(original.isCancelled()); assertNull(SavedVisionProbe.process().current(ui.lease));
                if (!leave) ui.awaitState(state -> !state.probing && !state.result.contains(CURRENT));
                server.releaseResponse(); assertTrue(server.awaitResponseAttempt());
                ui.assertNoLateRecord(); assertEquals(1, server.imageRequestCount()); assertFalse(server.awaitRequests(2, 150));
                assertNull(ui.owner());
                if (!leave) ui.assertSecureAndIdle();
                server.assertHealthy();
            }
        }
    }

    @Test public void anExternalSavedConfigurationInvalidatesItsUiOwnerAndExplicitResaveCanEstablishANewOne() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            ui.beginAndTrustFirstRequest(); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            Owner original = ui.owner(); ui.assertCurrent(original); SettingsStore.ConfigurationLease previous = ui.lease;
            SettingsStore.ConfigurationLease externallySaved = ui.store.save(ui.store.load());
            assertNotSame(previous, externallySaved); assertFalse(SettingsStore.isCurrent(previous));
            assertFalse(original.verification.isCurrentFor(previous));
            ui.awaitState(state -> !state.result.contains(CURRENT) && DRAFT.equals(state.label));
            assertTrue(original.control.isCancelled()); ui.assertExpiryRemoved(original); assertNull(ui.owner()); assertNull(SavedVisionProbe.process().current(externallySaved));
            assertEquals(1, server.imageRequestCount());
            ui.saveInUi(); assertNotSame(externallySaved, ui.lease); assertEquals(SAVED, ui.state().label);
            ui.click(R.id.test_model_vision); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            Owner replacement = ui.owner(); assertNotSame(original.identity, replacement.identity); ui.assertCurrent(replacement);
            assertFalse(original.verification.isCurrentFor(previous)); assertSame(replacement.verification, SavedVisionProbe.process().current(ui.lease));
            assertFalse(replacement.verification.isCurrentFor(ui.lease, original.control, original.epoch));
            assertFalse(replacement.control.isCancelled()); assertEquals(2, server.imageRequestCount());
            ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void anIndependentRealProbeSupersedesTheIdleUiRecordWithoutALeaseChangeOrUiPoke() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             SavedUi ui = new SavedUi(server)) {
            ui.beginAndTrustFirstRequest(); ui.awaitState(state -> state.result.contains(CURRENT) && !state.probing);
            Owner original = ui.owner(); ui.assertCurrent(original);
            SettingsStore.ConfigurationLease unchanged = ui.lease;
            RunControl independent = new RunControl(unchanged.profileId);
            long independentEpoch = independent.getExecutionEpoch();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                // A second real attempt publishes through the production registry
                // and the actual PNG/HTTP algorithm, without editing/saving the
                // configuration or calling any Activity render/refresh method.
                Future<SavedVisionProbe.Outcome> result = caller.submit(() -> SavedVisionProbe.process()
                        .test(ui.context, unchanged, independent, independentEpoch));
                SavedVisionProbe.Outcome outcome = result.get(10, TimeUnit.SECONDS);
                assertEquals(SavedVisionProbe.Status.VERIFIED, outcome.status); assertTrue(outcome.connected);
                assertEquals("SYNTHETIC_IMAGE_VERIFIED", outcome.code);
                assertTrue(SettingsStore.isCurrent(unchanged));
                // awaitState only reads Views/modelProbe on main; it does not
                // trigger a render or manufacture a lifecycle/config event.
                ui.awaitState(state -> !state.probing && state.result.contains("已保存配置记录：已失效")
                        && !state.result.contains(CURRENT));
                assertNull(ui.owner()); assertTrue(original.control.isCancelled()); ui.assertExpiryRemoved(original);
                assertSame(unchanged, ui.store.loadWithLease().lease); assertEquals(SAVED, ui.state().label);
                SavedVisionProbe.Verification replacement = SavedVisionProbe.process().current(unchanged);
                assertNotNull(replacement); assertNotSame(original.verification, replacement);
                assertTrue(replacement.isCurrentFor(unchanged, independent, independentEpoch));
                assertFalse(replacement.isCurrentFor(unchanged, original.control, original.epoch));
                assertFalse(independent.isCancelled()); assertEquals(independentEpoch, independent.getExecutionEpoch());
                assertEquals(0, independent.getDispatchAttempts()); assertEquals(0, independent.budgetSnapshot().toolCalls);
                assertEquals(2, server.imageRequestCount()); assertEquals(0, server.toolRequestCount());
                assertFalse("UI freshness checks must not send another probe", server.awaitRequests(3, 150));
                ui.assertSecureAndIdle(); server.assertHealthy();
            } finally {
                // This independent probe is not owned by the Activity. The test
                // must stop its original control and await its actual worker.
                independent.cancel(); caller.shutdownNow();
                assertTrue("Independent real probe caller did not exit", caller.awaitTermination(5, TimeUnit.SECONDS));
                await(() -> ImageWorkset.process().reservedBytes() == 0, "Independent probe payload has not drained");
                assertNull(SavedVisionProbe.process().current(unchanged));
            }
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
