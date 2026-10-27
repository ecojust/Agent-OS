package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.agentos.app.DeviceHarness.*;
import static dev.agentos.app.AndroidDeviceTools.WindowCaptureFailure.*;
import static org.junit.Assert.*;

/** Actual product guards and saved leases; the submission callback never captures any pixels. */
@RunWith(AndroidJUnit4.class)
public final class WindowCapturePermitInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void actualWindowPermitIsImmutableAndSubmitsOnlyOnceWhileTheDeviceGateIsHeld() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            AndroidDeviceTools.WindowCapturePermit permit = prepare(env);
            Rect actual = focusedBounds(env.device);
            assertEquals(actual, permit.copyWindowBounds());
            assertTrue(permit.copyRootBounds().contains(actual));
            assertFalse(permit.copySafeContent().isEmpty());
            assertEquals(FIXTURE, permit.packageName);
            assertEquals(env.device.control.getTaskId(), permit.taskId);
            assertEquals(env.settings.lease.configurationToken, permit.configurationToken);
            assertEquals(env.device.control.getExecutionEpoch(), permit.executionEpoch);
            assertTrue(permit.deadlineElapsedRealtimeMs > SystemClock.elapsedRealtime());
            Rect copy = permit.copyWindowBounds(); copy.setEmpty();
            assertEquals(actual, permit.copyWindowBounds());
            assertFalse(permit.isAttempted()); assertFalse(permit.isSubmissionClaimed());
            AtomicInteger submissions = new AtomicInteger();
            env.tools.submitWindowCapture(permit, env.device.control, permit.executionEpoch, id -> {
                assertEquals(permit.windowId, id); assertGateBusy(env.device);
                assertTrue(permit.isAttempted()); assertTrue(permit.isSubmissionClaimed());
                submissions.incrementAndGet();
            });
            assertFailure(ALREADY_USED, () -> env.tools.submitWindowCapture(permit, env.device.control,
                    permit.executionEpoch, id -> submissions.incrementAndGet()));
            assertEquals(1, submissions.get()); assertGateFree(env.device);
            assertNoVisualCapabilityOrMutation(env);
            screenshots.capture("actual-window-permit-once-no-native-capture");
        }
    }

    @Test public void booleanConfigurationGuardsCannotIssueProductionCapturePermits() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            JsonObject observed = env.device.observe();
            assertFailure(REAL_CONFIGURATION_REQUIRED, () -> env.device.tools.prepareWindowCapture(
                    env.device.control, env.device.control.getExecutionEpoch(), string(observed, "revision")));
            assertGateFree(env.device); assertNoVisualCapabilityOrMutation(env);
            screenshots.capture("test-boolean-guard-cannot-create-capture-permit");
        }
    }

    @Test public void foreignOwnersCannotConsumeAnotherAdaptersPermit() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            AndroidDeviceTools.WindowCapturePermit permit = prepare(env);
            AndroidDeviceTools another = configuredTools(env.device, env.settings.lease, true);
            RunControl otherControl = new RunControl(SettingsStore.currentProfileId(env.device.context));
            AtomicInteger submissions = new AtomicInteger();
            try {
                assertFailure(WRONG_OWNER, () -> another.submitWindowCapture(permit, env.device.control,
                        permit.executionEpoch, id -> submissions.incrementAndGet()));
                assertFailure(WRONG_OWNER, () -> env.tools.submitWindowCapture(permit, otherControl,
                        permit.executionEpoch, id -> submissions.incrementAndGet()));
                assertFalse(permit.isAttempted()); assertFalse(permit.isSubmissionClaimed());
                assertFalse(otherControl.isCancelled()); assertFalse(env.device.control.isCancelled());
                env.tools.submitWindowCapture(permit, env.device.control, permit.executionEpoch,
                        id -> submissions.incrementAndGet());
                assertEquals(1, submissions.get()); assertNoVisualCapabilityOrMutation(env);
                screenshots.capture("foreign-owner-cannot-consume-current-capture-permit");
            } finally { otherControl.cancel(); }
        }
    }

    @Test public void oldObservationAndExecutionEpochCannotAcquireOrSubmitFreshWork() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            JsonObject observed = observe(env);
            assertFailure(STALE_OBSERVATION, () -> env.tools.prepareWindowCapture(env.device.control,
                    env.device.control.getExecutionEpoch(), "obsolete-observation"));
            AndroidDeviceTools.WindowCapturePermit permit = env.tools.prepareWindowCapture(env.device.control,
                    env.device.control.getExecutionEpoch(), string(observed, "revision"));
            env.device.control.pause(); env.device.control.resume();
            long resumed = env.device.control.getExecutionEpoch(); AtomicInteger submissions = new AtomicInteger();
            try {
                env.tools.submitWindowCapture(permit, env.device.control, permit.executionEpoch,
                        id -> submissions.incrementAndGet());
                fail("A resumed task must not submit the old permit");
            } catch (TaskControlException stopped) { assertEquals("TASK_PAUSED", stopped.getCode()); }
            assertFalse(env.device.control.isCancelled());
            env.device.control.checkExecutionEpoch(resumed);
            assertEquals(0, submissions.get()); assertNoVisualCapabilityOrMutation(env);
            assertGateFree(env.device);
            screenshots.capture("resumed-task-rejects-old-capture-epoch-without-cancelling-new-work");
        }
    }

    @Test public void publishedConfigurationInvalidationStopsCaptureBeforeItsListenerRuns() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            AndroidDeviceTools.WindowCapturePermit permit = prepare(env);
            SettingsStore store = new SettingsStore(env.device.context);
            SettingsStore.SavedSettings saved = store.load(); AtomicInteger notifications = new AtomicInteger();
            AtomicInteger submissions = new AtomicInteger();
            try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, env.settings.lease);
                 SettingsStore.Registration watcher = env.settings.lease.watch(notifications::incrementAndGet)) {
                held.start(saved); held.awaitPublished();
                assertEquals(0, notifications.get()); assertFalse(env.device.control.isCancelled());
                try {
                    env.tools.submitWindowCapture(permit, env.device.control, permit.executionEpoch,
                            id -> submissions.incrementAndGet());
                    fail("Published invalidation must stop capture without waiting for a listener");
                } catch (TaskControlException stopped) { assertEquals("TASK_STOPPED", stopped.getCode()); }
                assertTrue(env.device.control.isCancelled()); assertEquals(0, submissions.get());
                assertEquals(0, notifications.get()); held.assertHeld(); assertGateFree(env.device);
                held.finish(); assertEquals(1, notifications.get());
            }
            assertNoVisualCapabilityOrMutation(env);
            screenshots.capture("published-configuration-invalidates-capture-before-listener");
        }
    }

    @Test public void replacedControlsAndChangedFocusedWindowsInvalidateTheOriginalPermit() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            AndroidDeviceTools.WindowCapturePermit controls = prepare(env); AtomicInteger submissions = new AtomicInteger();
            env.device.instrumentation.runOnMainSync(() -> {
                env.device.service.hideOverlay();
                env.device.service.showOverlay("Integration test", false, "");
            });
            env.device.awaitUiIdle();
            assertFailure(CONTROL_CHANGED, () -> env.tools.submitWindowCapture(controls, env.device.control,
                    controls.executionEpoch, id -> submissions.incrementAndGet()));
            AndroidDeviceTools.WindowCapturePermit focus = prepare(env);
            env.device.launch("SecondActivity");
            assertFailure(STALE_OBSERVATION, () -> env.tools.submitWindowCapture(focus, env.device.control,
                    focus.executionEpoch, id -> submissions.incrementAndGet()));
            assertEquals(0, submissions.get()); assertNoVisualCapabilityOrMutation(env); assertGateFree(env.device);
            screenshots.capture("capture-permits-rejected-after-real-control-and-focus-changes");
        }
    }

    @Test public void sensitiveHighImpactIncompleteAndUnauthorizedObservationsCannotCreatePermits() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            String[] scenarios = {"sensitive", "high-impact", "truncated"};
            AndroidDeviceTools.WindowCaptureFailure[] reasons = {SENSITIVE_CONTENT, HIGH_IMPACT_CONTENT, INCOMPLETE_OBSERVATION};
            for (int i = 0; i < scenarios.length; i++) {
                Intent intent = new Intent().setComponent(new ComponentName(FIXTURE, FIXTURE + ".NavigationGuardActivity"))
                        .putExtra("scenario", scenarios[i]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                env.device.context.startActivity(intent); env.device.instrumentation.waitForIdleSync(); env.device.awaitUiIdle();
                JsonObject observed = observe(env); AndroidDeviceTools.WindowCaptureFailure reason = reasons[i];
                assertFailure(reason, () -> env.tools.prepareWindowCapture(env.device.control,
                        env.device.control.getExecutionEpoch(), string(observed, "revision")));
            }
            env.device.launch("CapturePatternActivity");
            AndroidDeviceTools unscoped = configuredTools(env.device, env.settings.lease, false);
            JsonObject unapproved = awaitObservation(unscoped, env.device.control, value -> FIXTURE.equals(string(value, "packageName")));
            assertFalse(unapproved.get("scoped").getAsBoolean());
            assertFailure(UNAUTHORIZED_APP, () -> unscoped.prepareWindowCapture(env.device.control,
                    env.device.control.getExecutionEpoch(), string(unapproved, "revision")));
            assertNoVisualCapabilityOrMutation(env); assertGateFree(env.device);
            screenshots.capture("known-page-and-authorization-capture-rejections-no-native-call");
        }
    }

    @Test public void aRealUnregisteredOverlayCannotBecomeCaptureAuthorization() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            try (UnknownOverlay overlay = new UnknownOverlay(env.device)) {
                env.device.awaitUiIdle();
                JsonObject observed = awaitObservation(env.tools, env.device.control,
                        value -> FIXTURE.equals(string(value, "packageName")) && value.get("hasOcclusion").getAsBoolean());
                assertFailure(OCCLUDED, () -> env.tools.prepareWindowCapture(env.device.control,
                        env.device.control.getExecutionEpoch(), string(observed, "revision")));
                assertNoVisualCapabilityOrMutation(env);
                screenshots.capture("unknown-secure-overlay-refuses-capture-permit");
            }
            assertGateFree(env.device);
        }
    }

    @Test public void expiryAndSubmissionFailureCannotReuseThePermitOrLeakTheDeviceGate() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            AndroidDeviceTools.WindowCapturePermit expired = prepare(env); AtomicInteger submissions = new AtomicInteger();
            SystemClock.sleep(Math.max(0, expired.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime() + 20));
            assertFailure(EXPIRED, () -> env.tools.submitWindowCapture(expired, env.device.control,
                    expired.executionEpoch, id -> submissions.incrementAndGet()));
            assertTrue(expired.isAttempted()); assertFalse(expired.isSubmissionClaimed());
            assertFailure(ALREADY_USED, () -> env.tools.submitWindowCapture(expired, env.device.control,
                    expired.executionEpoch, id -> submissions.incrementAndGet()));
            assertEquals(0, submissions.get()); assertGateFree(env.device);
            AndroidDeviceTools.WindowCapturePermit failed = prepare(env);
            IOException expected = new IOException("Synthetic backend stopped after submission admission");
            try {
                env.tools.submitWindowCapture(failed, env.device.control, failed.executionEpoch, id -> {
                    submissions.incrementAndGet(); assertGateBusy(env.device); throw expected;
                });
                fail("Backend failure must propagate with submission ownership intact");
            } catch (IOException actual) { assertSame(expected, actual); }
            assertTrue(failed.isAttempted()); assertTrue(failed.isSubmissionClaimed());
            assertFailure(ALREADY_USED, () -> env.tools.submitWindowCapture(failed, env.device.control,
                    failed.executionEpoch, id -> submissions.incrementAndGet()));
            assertEquals(1, submissions.get()); assertGateFree(env.device); assertNoVisualCapabilityOrMutation(env);
            screenshots.capture("expired-and-failed-permits-not-reused-device-gate-released");
        }
    }

    private boolean unsupported(Environment env) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) return false;
        JsonObject observed = observe(env);
        assertFailure(UNSUPPORTED_API, () -> env.tools.prepareWindowCapture(env.device.control,
                env.device.control.getExecutionEpoch(), string(observed, "revision")));
        assertNoVisualCapabilityOrMutation(env); assertGateFree(env.device);
        screenshots.capture("capture-permit-unavailable-before-api34-no-native-call");
        return true;
    }

    private static JsonObject observe(Environment env) throws Exception {
        env.device.awaitUiIdle();
        return awaitObservation(env.tools, env.device.control, data -> FIXTURE.equals(string(data, "packageName")));
    }

    private static AndroidDeviceTools.WindowCapturePermit prepare(Environment env) throws Exception {
        JsonObject observed = observe(env);
        return env.tools.prepareWindowCapture(env.device.control, env.device.control.getExecutionEpoch(), string(observed, "revision"));
    }

    private static void assertNoVisualCapabilityOrMutation(Environment env) {
        assertEquals(0, env.device.control.getDispatchAttempts()); assertEquals(0, env.server.requestCount());
        assertEquals(0, env.device.service.getServiceInfo().getCapabilities()
                & (AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT | AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES));
        assertFalse(env.tools.availableTools().contains("take_screenshot"));
        assertFalse(env.tools.availableTools().contains("tap")); assertFalse(env.tools.availableTools().contains("swipe"));
    }

    private static void assertGateBusy(DeviceHarness device) {
        boolean acquired = device.service.tryBeginDeviceCall();
        if (acquired) device.service.endDeviceCall();
        assertFalse("Synchronous submission must retain the actual device gate", acquired);
    }

    private static void assertGateFree(DeviceHarness device) {
        boolean acquired = device.service.tryBeginDeviceCall();
        try { assertTrue("The actual synchronous device call has ended", acquired); }
        finally { if (acquired) device.service.endDeviceCall(); }
    }

    private static void assertFailure(AndroidDeviceTools.WindowCaptureFailure expected, CheckedRunnable action) throws Exception {
        try { action.run(); fail("Expected capture rejection " + expected); }
        catch (AndroidDeviceTools.WindowCaptureException actual) { assertEquals(actual.toString(), expected, actual.code); }
    }

    private static Rect focusedBounds(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            Rect result = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) {
                assertNull(result); result = new Rect(); window.getBoundsInScreen(result);
            }
            assertNotNull(result); return result;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static AndroidDeviceTools configuredTools(DeviceHarness device, SettingsStore.ConfigurationLease lease,
                                                       boolean allowFixture) throws IOException {
        return new AndroidDeviceTools(device.service, allowFixture ? Collections.singleton(FIXTURE) : Collections.emptySet(),
                allowFixture ? Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)) : Collections.emptyMap(),
                ActionConfirmationPolicy.Mode.CONFIRM_ALL, lease,
                (description, control, epoch) -> { throw new AssertionError("A capture precondition is not image disclosure approval"); });
    }

    private interface CheckedRunnable { void run() throws Exception; }

    private static final class Environment implements AutoCloseable {
        DeviceHarness device;
        LocalControllerModelServer server;
        ControllerTestSettings settings;
        AndroidDeviceTools tools;
        Environment() throws Exception {
            try {
                device = new DeviceHarness();
                server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(device.context, server);
                tools = configuredTools(device, settings.lease, true);
                device.launch("CapturePatternActivity");
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public void close() throws Exception {
            try { if (device != null) device.control.cancel(); }
            finally {
                try { if (settings != null) settings.close(); }
                finally {
                    try { if (server != null) server.close(); }
                    finally { if (device != null) device.close(); }
                }
            }
        }
    }

    private static final class UnknownOverlay implements AutoCloseable {
        final DeviceHarness device; final WindowManager manager; final TextView view;
        UnknownOverlay(DeviceHarness device) {
            this.device = device;
            manager = (WindowManager) device.service.getSystemService(Context.WINDOW_SERVICE);
            view = new TextView(device.service);
            try {
                device.instrumentation.runOnMainSync(() -> {
                    view.setText("Unregistered synthetic overlay"); view.setTextColor(Color.WHITE); view.setClickable(true);
                    view.setBackgroundColor(Color.DKGRAY); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(120, 80,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                            PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.CENTER; params.setTitle("Agent OS task controls");
                    manager.addView(view, params);
                });
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public void close() {
            device.instrumentation.runOnMainSync(() -> { if (view.getParent() != null) manager.removeViewImmediate(view); });
        }
    }
}
