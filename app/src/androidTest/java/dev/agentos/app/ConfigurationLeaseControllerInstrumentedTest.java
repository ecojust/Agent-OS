package dev.agentos.app;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Actual controller tasks, local HTTP and remote fixture; no model quality or reset E2E claim. */
@RunWith(AndroidJUnit4.class)
public final class ConfigurationLeaseControllerInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void savingConfigurationInvalidatesPendingApprovalAndLateApprovalCannotWrite() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AgentController controller = AgentController.get(device.context);
            try {
                start(device, controller, settings);
                AgentController.State pending = awaitApproval(controller, server);
                int requests = server.requestCount();
                assertTrue(requests >= 1 && requests <= 1 + LocalControllerModelServer.MAX_WRITE_REPLANS);
                screenshots.capture("configuration-before-save-pending-approval");
                SettingsStore store = new SettingsStore(device.context);
                SettingsStore.ConfigurationLease replacement = store.save(store.load());
                assertFalse(SettingsStore.isCurrent(settings.lease));
                assertTrue(SettingsStore.isCurrent(replacement));
                // Invalidation is synchronous, even though cancelled task cleanup may
                // still be returning on its worker. An old confirmation is never reused.
                assertFalse(controller.getState().running);
                assertTrue(controller.getState().approvalId.isEmpty());
                assertEquals("配置已改变，任务已停止", controller.getState().status);
                device.instrumentation.runOnMainSync(() -> {
                    controller.approve(pending.approvalId, true);
                    controller.pauseResume();
                });
                assertFalse(controller.getState().running);
                assertTrue(controller.getState().approvalId.isEmpty());
                assertFalse("Saving settings allowed another model plan", server.receivedAnotherRequest(requests, 500));
                server.assertHealthy();
                assertEquals(0, server.acceptedWriteCount());
                assertNoDeviceDispatch(trace);
                assertEquals("Count: 0", actualCounter(device));
                screenshots.capture("configuration-save-rejected-old-confirmation");
            } catch (Exception | AssertionError failure) {
                captureFailure(failure);
                throw failure;
            } finally { clear(device, controller); }
        }
    }

    @Test public void savingConfigurationCancelsAQueuedTaskBeforeTheOldRemoteReadReturns() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            try {
                device.launch("SlowActivity");
                SettingsStore store = new SettingsStore(device.context);
                // Prepare the same configuration before holding the remote root so
                // the bounded barrier only covers the race we intend to exercise.
                SettingsStore.SavedSettings sameConfiguration = store.load();
                try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
                     BlockedInitialRead blocked = new BlockedInitialRead(device, controller)) {
                    assertFalse(blocked.call("arm").getBoolean("claimed"));
                    start(device, controller, settings);
                    assertTrue("Controller's initial remote read did not begin", blocked.entered.await(2, TimeUnit.SECONDS));
                    Bundle held = blocked.call("status");
                    assertHeld(held);
                    assertEquals(0, server.requestCount());
                    assertTrue(controller.getState().approvalId.isEmpty());
                    device.instrumentation.runOnMainSync(() -> {
                        controller.stop();
                        controller.start(settings.model, "Queued synthetic task must never outlive its configuration.",
                                Collections.singleton(DeviceHarness.FIXTURE), true, settings.lease);
                    });
                    assertTrue("The replacement task must be queued, not rejected", controller.getState().running);
                    assertHeld(blocked.call("status"));
                    assertEquals(0, server.requestCount());

                    SettingsStore.ConfigurationLease replacement = store.save(sameConfiguration);
                    assertFalse(SettingsStore.isCurrent(settings.lease));
                    assertTrue(SettingsStore.isCurrent(replacement));
                    assertFalse("A queued task retained the old configuration", controller.getState().running);
                    assertTrue(controller.getState().approvalId.isEmpty());
                    assertEquals("配置已改变，任务已停止", controller.getState().status);
                    assertHeld(blocked.call("status"));
                    Bundle completed = blocked.call("finish");
                    assertTrue(completed.getBoolean("returned"));
                    assertFalse("Emergency timeout is not an explicit release", completed.getBoolean("expired"));
                    assertControllerObserveCoveredTheBlockedRead(trace, completed.getLong("startedNanos"));
                    assertFalse("The released worker started an invalidated queued task", server.receivedAnotherRequest(0, 500));
                    server.assertHealthy();
                    assertFalse(controller.getState().running);
                    assertTrue(controller.getState().approvalId.isEmpty());
                    assertEquals("配置已改变，任务已停止", controller.getState().status);
                    assertNoDeviceDispatch(trace);
                    assertEquals("Count: 0", actualCounter(device));
                    screenshots.capture("configuration-save-cancelled-real-worker-queue");
                }
            } catch (Exception | AssertionError failure) {
                captureFailure(failure);
                throw failure;
            } finally { clear(device, controller); }
        }
    }

    private static void assertHeld(Bundle status) {
        assertEquals("ROOT", status.getString("kind"));
        assertTrue(status.getBoolean("exists"));
        assertTrue(status.getBoolean("claimed"));
        assertFalse(status.getBoolean("released"));
        assertFalse(status.getBoolean("returned"));
        assertFalse(status.getBoolean("expired"));
    }

    private static void assertControllerObserveCoveredTheBlockedRead(PerformanceTrace.Session trace, long blockedNanos) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            for (PerformanceTrace.Event event : trace.events()) {
                if (event.stage == PerformanceTrace.Stage.SNAPSHOT_OBSERVE
                        && event.startNanos <= blockedNanos && blockedNanos <= event.startNanos + event.durationNanos) {
                    assertFalse("The actual controller observation must run off main", event.mainThread);
                    return;
                }
            }
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The held root was not inside the controller's actual observation");
    }

    private static void assertNoDeviceDispatch(PerformanceTrace.Session trace) {
        assertEquals("Lost trace events cannot prove absence of dispatch", 0, trace.overwrittenCount());
        for (PerformanceTrace.Event event : trace.events()) {
            assertNotEquals("Invalidated configuration reached platform dispatch", PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
        }
    }

    private static void start(DeviceHarness device, AgentController controller, ControllerTestSettings settings) {
        device.instrumentation.runOnMainSync(() -> controller.start(settings.model,
                "Prepare one synthetic counter click and wait for confirmation.",
                Collections.singleton(DeviceHarness.FIXTURE), true, settings.lease));
        assertTrue("Controller did not start", controller.getState().running);
    }

    private static AgentController.State awaitApproval(AgentController controller, LocalControllerModelServer server) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            server.assertHealthy();
            AgentController.State state = controller.getState();
            if (!state.approvalId.isEmpty()) return state;
            assertTrue("Task ended before confirmation: " + state.status, state.running);
            SystemClock.sleep(25);
        }
        throw new AssertionError("No actual controller confirmation appeared");
    }

    private static String actualCounter(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/counter_value");
                    try {
                        if (nodes.size() == 1 && nodes.get(0).refresh() && nodes.get(0).getText() != null) return nodes.get(0).getText().toString();
                    } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("Independent fixture counter is unavailable\n" + DeviceHarness.windowDiagnostics());
    }

    private void captureFailure(Throwable failure) {
        try { screenshots.capture("configuration-task-failure-before-cleanup"); }
        catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
    }

    private static void clear(DeviceHarness device, AgentController controller) {
        device.instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
        device.instrumentation.waitForIdleSync();
    }

    private static final class BlockedInitialRead implements AutoCloseable {
        final DeviceHarness device;
        final AgentController controller;
        final String token = UUID.randomUUID().toString();
        final CountDownLatch entered = new CountDownLatch(1);
        final BroadcastReceiver receiver;

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        BlockedInitialRead(DeviceHarness device, AgentController controller) {
            this.device = device; this.controller = controller;
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (token.equals(intent.getStringExtra("token"))) entered.countDown();
                }
            };
            IntentFilter filter = new IntentFilter("dev.agentos.fixture.FINAL_ROOT_BLOCKED");
            // A separate synthetic fixture sends this broadcast; API 30-32's only
            // overload exports receivers implicitly, newer APIs require the flag.
            if (Build.VERSION.SDK_INT >= 33) device.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else device.context.registerReceiver(receiver, filter);
        }

        Bundle call(String method) {
            Bundle result = device.context.getContentResolver().call(
                    Uri.parse("content://dev.agentos.fixture.slow-read-control"), method, token, null);
            assertNotNull("Fixture control returned no response for " + method, result);
            return result;
        }

        @Override public void close() throws Exception {
            // Stop before releasing: cleanup after any failed assertion must not
            // make a queued task runnable with permission to write.
            controller.stop();
            Throwable failure = null;
            try { call("finish"); }
            catch (Exception | AssertionError problem) { failure = problem; }
            try { call("clear"); }
            catch (Exception | AssertionError problem) { failure = preserve(failure, problem); }
            try { device.context.unregisterReceiver(receiver); }
            catch (Exception | AssertionError problem) { failure = preserve(failure, problem); }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure != null) throw (AssertionError) failure;
        }

        private static Throwable preserve(Throwable original, Throwable additional) {
            if (original == null) return additional;
            if (original != additional) original.addSuppressed(additional);
            return original;
        }
    }
}
