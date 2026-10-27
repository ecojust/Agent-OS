package dev.agentos.app;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Real remote root reads after approval, with deterministic release rather than a timed sleep. */
@RunWith(AndroidJUnit4.class)
public final class SlowFinalReadInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void pauseDuringTheApprovedFinalReadRemainsResponsiveAndInvalidatesTheOldEpoch() throws Exception {
        verifyInterruptedFinalRead(false);
    }

    @Test public void stopDuringTheApprovedFinalReadRemainsResponsiveAndNeverDispatches() throws Exception {
        verifyInterruptedFinalRead(true);
    }

    @Test public void publishedConfigurationInvalidationRejectsAnApprovedFinalReadBeforeItsCancelListenerRuns() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            device.launch("SlowActivity");
            SettingsStore store = new SettingsStore(device.context);
            SettingsStore.SavedSettings same = store.load();
            JsonObject arguments = nodeArguments(device.observe(), "increment");
            long epoch = device.control.getExecutionEpoch();
            AtomicInteger approvals = new AtomicInteger(), cancellationNotifications = new AtomicInteger();
            try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, settings.lease);
                 SettingsStore.Registration cancellation = settings.lease.watch(() -> {
                     cancellationNotifications.incrementAndGet(); device.control.cancel();
                 });
                 FinalReadProbe probe = new FinalReadProbe(device, () -> held.start(same))) {
                AndroidDeviceTools tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), settings.executionMode, settings.lease,
                        (description, control, requestEpoch) -> {
                            assertEquals(epoch, requestEpoch);
                            assertEquals(1, approvals.incrementAndGet());
                            assertFalse(probe.call("arm").getBoolean("claimed"));
                            return true;
                        });
                probe.operation = new FutureTask<>(() -> tools.execute("click", arguments, device.control, epoch));
                probe.worker.execute(probe.operation);
                assertTrue("The actual final root read never reached the barrier", probe.heartbeat.await(2, TimeUnit.SECONDS));
                held.awaitPublished();
                assertTrue(probe.mainThread.get());
                assertTrue(probe.inFlight.get());
                assertEquals(0, cancellationNotifications.get());
                assertFalse("Cancellation must still be waiting behind the first observer", device.control.isCancelled());
                Bundle blocked = probe.call("status");
                assertTrue(blocked.getBoolean("claimed"));
                assertFalse(blocked.getBoolean("returned"));
                assertFalse(blocked.getBoolean("released"));
                assertFalse(blocked.getBoolean("expired"));
                Bundle completed = probe.call("finish");
                assertTrue(completed.getBoolean("returned"));
                assertFalse(completed.getBoolean("expired"));
                assertInterrupted(probe.operation, "TASK_STOPPED");
                held.assertHeld();
                assertEquals("The final read must reject before cancellation notification", 0, cancellationNotifications.get());
                assertTrue(device.control.isCancelled());
                assertEquals(1, approvals.get());
                assertEquals(0, device.control.getDispatchAttempts());
                assertFinalSnapshotWasExercised(trace, completed.getLong("startedNanos"), epoch);
                assertEquals("Count: 0", actualCounter(device));
                assertEquals(0, trace.overwrittenCount());
                for (PerformanceTrace.Event event : trace.events()) {
                    assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
                }
                held.finish();
                assertEquals(1, cancellationNotifications.get());
                assertEquals(0, server.requestCount());
                screenshots.capture("configuration-invalid-before-cancel-notification-final-read-rejected");
            }
        }
    }

    @Test public void invalidatingTheControlOwnerDuringFinalReadRejectsAnOtherwiseUnchangedPlan() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            device.launch("SlowActivity");
            AgentAccessibilityService.OverlayCallbacks callbacks = new AgentAccessibilityService.OverlayCallbacks() {
                @Override public void onPauseResume() {
                    if (device.control.isPaused()) device.control.resume(); else device.control.pause();
                }
                @Override public void onStop() { device.control.cancel(); }
                @Override public void onApprove(boolean approved) { }
            };
            device.instrumentation.runOnMainSync(() -> device.service.setOverlayCallbacks(callbacks));
            device.awaitUiIdle();
            JsonObject baseline = device.observe();
            JsonObject arguments = nodeArguments(baseline, "increment");
            long epoch = device.control.getExecutionEpoch();
            long dispatches = device.control.getDispatchAttempts();
            AtomicInteger approvals = new AtomicInteger();
            try (FinalReadProbe probe = new FinalReadProbe(device, () -> device.service.setOverlayCallbacks(null))) {
                AndroidDeviceTools tools = AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true, () -> true,
                        (description, control, requestEpoch) -> {
                            assertEquals(epoch, requestEpoch);
                            assertEquals(1, approvals.incrementAndGet());
                            Bundle armed = probe.call("arm");
                            assertTrue(armed.getBoolean("exists"));
                            assertFalse(armed.getBoolean("claimed"));
                            return true;
                        });
                probe.operation = new FutureTask<>(() -> tools.execute("click", arguments, device.control, epoch));
                probe.worker.execute(probe.operation);
                assertTrue("The main thread could not revoke the control lease", probe.heartbeat.await(2, TimeUnit.SECONDS));
                assertTrue(probe.mainThread.get());
                assertTrue(probe.inFlight.get());
                Bundle blocked = probe.call("status");
                assertTrue(blocked.getBoolean("claimed"));
                assertFalse(blocked.getBoolean("returned"));
                assertFalse(blocked.getBoolean("released"));
                assertFalse(blocked.getBoolean("expired"));
                long latencyMillis = TimeUnit.NANOSECONDS.toMillis(probe.callbackNanos.get() - blocked.getLong("startedNanos"));
                assertTrue("Final read blocked lease revocation: " + latencyMillis + " ms", latencyMillis >= 0 && latencyMillis < 500);
                assertEquals("Lease revocation must not cancel or pause the task", epoch, device.control.getExecutionEpoch());
                assertFalse(device.control.isPaused());
                assertFalse(device.control.isCancelled());

                Bundle completed = probe.call("finish");
                assertTrue(completed.getBoolean("returned"));
                assertFalse(completed.getBoolean("expired"));
                JsonObject result = probe.operation.get(5, TimeUnit.SECONDS);
                assertFalse(result.toString(), result.get("ok").getAsBoolean());
                assertEquals("STALE_OBSERVATION", string(result, "code"));
                for (String key : new String[]{"submitted", "dispatchSubmitted", "dispatchAccepted"}) {
                    assertTrue("Missing explicit non-dispatch evidence: " + result,
                            result.has(key) && result.get(key).isJsonPrimitive() && result.getAsJsonPrimitive(key).isBoolean());
                    assertFalse(result.toString(), result.get(key).getAsBoolean());
                }
                assertEquals(1, approvals.get());
                assertEquals("A stale control lease acquired a dispatch ticket", dispatches, device.control.getDispatchAttempts());
                assertEquals(epoch, device.control.getExecutionEpoch());
                assertFalse(device.control.isPaused());
                assertFalse(device.control.isCancelled());
                PerformanceTrace.Event finalRead = assertFinalSnapshotWasExercised(trace, completed.getLong("startedNanos"), epoch);
                // Revoking callbacks now invalidates both the action lease and its
                // owned-window identity. The resumed final read must not publish a
                // completed snapshot; these sentinels do not identify a finer stage.
                assertEquals("A revoked control owner must not yield a completed final snapshot", -1, finalRead.nodeCount);
                assertEquals("No completed final snapshot may be serialized", -1, finalRead.snapshotJsonBytes);
                int baselineBytes = baseline.toString().getBytes(StandardCharsets.UTF_8).length;
                int preparations = 0;
                for (PerformanceTrace.Event event : trace.events()) {
                    if (event.stage != PerformanceTrace.Stage.SNAPSHOT_PREPARE || event.epoch != epoch) continue;
                    preparations++;
                    assertFalse(event.truncated);
                    assertTrue("The preparatory snapshot must be complete", event.nodeCount > 0 && event.snapshotJsonBytes > 0);
                    assertEquals(baseline.getAsJsonArray("nodes").size(), event.nodeCount);
                    assertEquals(baselineBytes, event.snapshotJsonBytes);
                }
                assertEquals(1, preparations);
                assertEquals("No diagnostic events may be missing", 0, trace.overwrittenCount());
                assertEquals("Count: 0", actualCounter(device));

                // Restore only callbacks, with no tree/layout edits. Identical revision
                // proves the rejection was not caused by a changed fixture or window.
                device.instrumentation.runOnMainSync(() -> device.service.setOverlayCallbacks(callbacks));
                assertEquals(string(baseline, "revision"), string(device.observe(), "revision"));
                screenshots.capture("revoked-lease-unchanged-tree-no-dispatch");
            } finally {
                device.instrumentation.runOnMainSync(() -> device.service.setOverlayCallbacks(callbacks));
            }
        }
    }

    private void verifyInterruptedFinalRead(boolean stop) throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            device.launch("SlowActivity");
            JsonObject arguments = nodeArguments(device.observe(), "increment");
            long epoch = device.control.getExecutionEpoch();
            long dispatches = device.control.getDispatchAttempts();
            AtomicInteger approvals = new AtomicInteger();
            try (FinalReadProbe probe = new FinalReadProbe(device, stop)) {
                AndroidDeviceTools tools = AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true, () -> true,
                        (description, control, requestEpoch) -> {
                            assertEquals(epoch, requestEpoch);
                            assertEquals(1, approvals.incrementAndGet());
                            // Preparation is finished. The next authoritative root read
                            // belongs to the final snapshot after this approval returns.
                            Bundle armed = probe.call("arm");
                            assertTrue(armed.getBoolean("exists"));
                            assertFalse(armed.getBoolean("claimed"));
                            return true;
                        });
                probe.operation = new FutureTask<>(() -> tools.execute("click", arguments, device.control, epoch));
                probe.worker.execute(probe.operation);
                assertTrue("The main thread did not handle the blocked-read event", probe.heartbeat.await(2, TimeUnit.SECONDS));
                assertTrue("The heartbeat was not delivered on the actual main thread", probe.mainThread.get());
                assertTrue("The final read was no longer in flight when its control event arrived", probe.inFlight.get());
                Bundle blocked = probe.call("status");
                assertTrue("The remote root was not actually held", blocked.getBoolean("claimed"));
                assertFalse("The remote read returned before the control callback", blocked.getBoolean("returned"));
                assertFalse("The fixture's emergency timeout released the read", blocked.getBoolean("expired"));
                assertFalse("The test released the read before processing pause/stop", blocked.getBoolean("released"));
                long latencyMillis = TimeUnit.NANOSECONDS.toMillis(probe.callbackNanos.get() - blocked.getLong("startedNanos"));
                assertTrue("Final read blocked the main control callback: " + latencyMillis + " ms", latencyMillis >= 0 && latencyMillis < 500);
                assertEquals(1, approvals.get());
                assertEquals(dispatches, device.control.getDispatchAttempts());
                if (stop) assertTrue(device.control.isCancelled()); else assertTrue(device.control.isPaused());

                Bundle completed = probe.call("finish");
                assertTrue(completed.getBoolean("returned"));
                assertFalse("Fixture timeout is not an explicit test release", completed.getBoolean("expired"));
                assertInterrupted(probe.operation, stop ? "TASK_STOPPED" : "TASK_PAUSED");
                assertFinalSnapshotWasExercised(trace, completed.getLong("startedNanos"), epoch);
                assertEquals("An interrupted final read reached Android dispatch", dispatches, device.control.getDispatchAttempts());
                assertEquals("Count: 0", actualCounter(device));

                if (!stop) device.control.resume();
                try {
                    tools.execute("click", arguments, device.control, epoch);
                    fail("The interrupted final-read request must not adopt another execution epoch");
                } catch (TaskControlException invalidated) {
                    assertEquals(stop ? "TASK_STOPPED" : "TASK_PAUSED", invalidated.getCode());
                }
                assertEquals("Old work requested confirmation again", 1, approvals.get());
                assertEquals(dispatches, device.control.getDispatchAttempts());
                assertEquals("Count: 0", actualCounter(device));
                screenshots.capture(stop ? "final-read-stopped-without-dispatch" : "final-read-paused-old-epoch-rejected");
            }
        }
    }

    private static void assertInterrupted(FutureTask<JsonObject> operation, String code) throws Exception {
        try {
            JsonObject result = operation.get(5, TimeUnit.SECONDS);
            fail("An interrupted final snapshot returned as an ordinary result: " + result);
        } catch (ExecutionException interrupted) {
            assertTrue(interrupted.getCause().toString(), interrupted.getCause() instanceof TaskControlException);
            assertEquals(code, ((TaskControlException) interrupted.getCause()).getCode());
        }
    }

    private static PerformanceTrace.Event assertFinalSnapshotWasExercised(PerformanceTrace.Session trace, long blockedNanos, long epoch) {
        int matching = 0;
        PerformanceTrace.Event found = null;
        for (PerformanceTrace.Event event : trace.events()) {
            if (event.stage != PerformanceTrace.Stage.SNAPSHOT_FINAL_MUTATION) continue;
            if (event.startNanos <= blockedNanos && blockedNanos <= event.startNanos + event.durationNanos) {
                matching++;
                assertEquals(epoch, event.epoch);
                assertFalse("Final snapshot must stay on its device worker", event.mainThread);
                found = event;
            }
        }
        assertEquals("The held root was not read by this operation's final snapshot", 1, matching);
        return found;
    }

    private static String actualCounter(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (!FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/counter_value");
                    try {
                        if (nodes.size() == 1 && nodes.get(0).refresh() && nodes.get(0).getText() != null) return nodes.get(0).getText().toString();
                    } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("Independent fixture counter is unavailable");
    }

    private static final class FinalReadProbe implements AutoCloseable {
        final DeviceHarness device;
        final String token = UUID.randomUUID().toString();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CountDownLatch heartbeat = new CountDownLatch(1);
        final AtomicBoolean mainThread = new AtomicBoolean(), inFlight = new AtomicBoolean();
        final AtomicLong callbackNanos = new AtomicLong();
        volatile FutureTask<JsonObject> operation;
        final BroadcastReceiver receiver;

        FinalReadProbe(DeviceHarness device, boolean stop) {
            this(device, () -> { if (stop) device.control.cancel(); else device.control.pause(); });
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        FinalReadProbe(DeviceHarness device, Runnable mainAction) {
            this.device = device;
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!token.equals(intent.getStringExtra("token"))) return;
                    callbackNanos.set(SystemClock.elapsedRealtimeNanos());
                    mainThread.set(Looper.myLooper() == Looper.getMainLooper());
                    inFlight.set(operation != null && !operation.isDone());
                    mainAction.run();
                    heartbeat.countDown();
                }
            };
            IntentFilter filter = new IntentFilter("dev.agentos.fixture.FINAL_ROOT_BLOCKED");
            // This receiver intentionally accepts the separate offline fixture's event.
            // API 30-32 expose only the legacy overload, exported by default.
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
            // A failing assertion must never release an approved write into dispatch.
            device.control.cancel();
            Throwable failure = null;
            try { call("finish"); }
            catch (Exception | AssertionError problem) { failure = problem; }
            try {
                if (operation != null) {
                    try { operation.get(5, TimeUnit.SECONDS); }
                    catch (ExecutionException ended) {
                        if (!(ended.getCause() instanceof InterruptedException)) throw ended;
                    }
                }
            } catch (Exception | AssertionError problem) { failure = preserve(failure, problem); }
            try { call("clear"); }
            catch (Exception | AssertionError problem) { failure = preserve(failure, problem); }
            worker.shutdownNow();
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
