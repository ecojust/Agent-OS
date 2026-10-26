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
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

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

/** Cancellation during a real remote onClick cannot retract the platform call or free its gate early. */
@RunWith(AndroidJUnit4.class)
public final class InFlightDeviceActionInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void stoppingAnEnteredPlatformActionKeepsItsTicketAndServiceGateUntilItReturns() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            device.launch("SlowActivity");
            JsonObject baseline = device.observe();
            JsonObject arguments = nodeArguments(baseline, "increment");
            long epoch = device.control.getExecutionEpoch();
            assertEquals(0, device.control.getDispatchAttempts());
            AtomicInteger approvals = new AtomicInteger();
            try (ActionProbe probe = new ActionProbe(device)) {
                AndroidDeviceTools tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true,
                        (description, control, requestEpoch) -> {
                            assertEquals(epoch, requestEpoch);
                            assertEquals(1, approvals.incrementAndGet());
                            Bundle armed = probe.call("arm_action");
                            assertTrue(armed.getBoolean("exists"));
                            assertEquals("ACTION", armed.getString("kind"));
                            assertFalse(armed.getBoolean("claimed"));
                            return true;
                        });
                probe.operation = new FutureTask<>(() -> tools.execute("click", arguments, device.control, epoch));
                probe.worker.execute(probe.operation);
                assertTrue("The main thread did not receive the entered-action event", probe.heartbeat.await(2, TimeUnit.SECONDS));
                assertTrue("Cancellation was not processed on the app main thread", probe.mainThread.get());
                assertTrue("The original platform action was no longer in flight", probe.inFlight.get());
                assertTrue("Cancellation lost the still-owned dispatch ticket", probe.ticketAfterCancel.get());
                Bundle blocked = probe.call("status");
                assertEquals("ACTION", blocked.getString("kind"));
                assertTrue("The real onClick handler was not reached", blocked.getBoolean("claimed"));
                assertFalse(blocked.getBoolean("returned"));
                assertFalse(blocked.getBoolean("released"));
                assertFalse("An emergency timeout is not an explicit test release", blocked.getBoolean("expired"));
                long latencyMillis = TimeUnit.NANOSECONDS.toMillis(probe.callbackNanos.get() - blocked.getLong("startedNanos"));
                assertTrue("Remote performAction blocked main-thread stop: " + latencyMillis + " ms", latencyMillis >= 0 && latencyMillis < 500);
                assertTrue(device.control.isCancelled());
                assertTrue(device.control.hasInFlightDispatch());
                assertEquals(1, device.control.getDispatchAttempts());
                assertFalse("The action worker settled before its remote callback returned", probe.operation.isDone());
                assertCompletedSnapshotsAndOneDispatch(trace, blocked.getLong("startedNanos"), epoch,
                        baseline.getAsJsonArray("nodes").size());

                // A new adapter/task must not bypass the service's cross-task gate.
                // Its construction watches the same package but never replaces controls.
                RunControl replacement = new RunControl(SettingsStore.currentProfileId(device.context));
                AndroidDeviceTools nextTools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true,
                        (description, control, requestEpoch) -> { fail("A busy read must not request approval"); return false; });
                JsonObject busy = nextTools.execute("observe", new JsonObject(), replacement, replacement.getExecutionEpoch());
                assertFalse(busy.toString(), busy.get("ok").getAsBoolean());
                assertEquals("CAPABILITY_UNAVAILABLE", string(busy, "code"));
                assertUnsubmitted(busy);
                assertEquals(0, replacement.getDispatchAttempts());
                assertTrue("A rejected new adapter released the original ticket", device.control.hasInFlightDispatch());
                assertFalse("A rejected new adapter completed the original action", probe.operation.isDone());
                Bundle stillBlocked = probe.call("status");
                assertFalse(stillBlocked.getBoolean("returned"));
                assertFalse(stillBlocked.getBoolean("expired"));

                Bundle finished = probe.call("finish");
                assertTrue(finished.getBoolean("returned"));
                assertFalse(finished.getBoolean("expired"));
                try {
                    JsonObject result = probe.operation.get(5, TimeUnit.SECONDS);
                    fail("The stopped original request must not report ordinary success: " + result);
                } catch (ExecutionException interrupted) {
                    assertTrue(interrupted.getCause().toString(), interrupted.getCause() instanceof TaskControlException);
                    assertEquals("TASK_STOPPED", ((TaskControlException) interrupted.getCause()).getCode());
                }
                assertFalse("The completed worker retained its dispatch ticket", device.control.hasInFlightDispatch());
                assertEquals(1, device.control.getDispatchAttempts());
                // The call had already entered the other process: stop cannot undo it.
                assertEquals("Count: 1", actualCounter(device));

                JsonObject restored = awaitObservation(nextTools, replacement,
                        data -> "Count: 1".equals(string(node(data, "counter_value"), "text")));
                assertEquals(FIXTURE, string(restored, "packageName"));
                assertEquals(0, replacement.getDispatchAttempts());
                try {
                    tools.execute("click", arguments, device.control, epoch);
                    fail("The stopped action's original epoch must not submit another click");
                } catch (TaskControlException stopped) { assertEquals("TASK_STOPPED", stopped.getCode()); }
                assertEquals(1, approvals.get());
                assertEquals(1, device.control.getDispatchAttempts());
                assertFalse(device.control.hasInFlightDispatch());
                assertEquals("Count: 1", actualCounter(device));
                screenshots.capture("entered-action-completed-once-after-stop-gate-released");
            }
        }
    }

    private static void assertUnsubmitted(JsonObject result) {
        for (String key : new String[]{"submitted", "dispatchSubmitted", "dispatchAccepted"}) {
            assertTrue(result.toString(), result.has(key) && result.get(key).isJsonPrimitive()
                    && result.getAsJsonPrimitive(key).isBoolean());
            assertFalse(result.toString(), result.get(key).getAsBoolean());
        }
    }

    private static void assertCompletedSnapshotsAndOneDispatch(PerformanceTrace.Session trace, long enteredAt,
                                                               long epoch, int nodes) {
        int preparations = 0, finals = 0, dispatches = 0;
        for (PerformanceTrace.Event event : trace.events()) {
            if (event.epoch != epoch) continue;
            if (event.stage == PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT) {
                dispatches++;
                assertEquals(1, event.dispatchAttempts);
                assertTrue("The remote callback preceded admission", event.startNanos <= enteredAt);
            }
            if (event.stage != PerformanceTrace.Stage.SNAPSHOT_PREPARE
                    && event.stage != PerformanceTrace.Stage.SNAPSHOT_FINAL_MUTATION) continue;
            assertFalse("The accepted plan used a truncated snapshot", event.truncated);
            assertEquals(nodes, event.nodeCount);
            assertTrue(event.nodeCount > 0 && event.snapshotJsonBytes > 0);
            assertTrue("The snapshot did not finish before the actual onClick", event.startNanos + event.durationNanos <= enteredAt);
            if (event.stage == PerformanceTrace.Stage.SNAPSHOT_PREPARE) preparations++; else finals++;
        }
        assertEquals(1, preparations);
        assertEquals(1, finals);
        assertEquals(1, dispatches);
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

    private static final class ActionProbe implements AutoCloseable {
        final DeviceHarness device;
        final String token = UUID.randomUUID().toString();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CountDownLatch heartbeat = new CountDownLatch(1);
        final AtomicBoolean mainThread = new AtomicBoolean(), inFlight = new AtomicBoolean(), ticketAfterCancel = new AtomicBoolean();
        final AtomicLong callbackNanos = new AtomicLong();
        volatile FutureTask<JsonObject> operation;
        final BroadcastReceiver receiver;

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        ActionProbe(DeviceHarness device) {
            this.device = device;
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!token.equals(intent.getStringExtra("token"))) return;
                    callbackNanos.set(SystemClock.elapsedRealtimeNanos());
                    mainThread.set(Looper.myLooper() == Looper.getMainLooper());
                    inFlight.set(operation != null && !operation.isDone());
                    device.control.cancel();
                    ticketAfterCancel.set(device.control.hasInFlightDispatch());
                    heartbeat.countDown();
                }
            };
            IntentFilter filter = new IntentFilter("dev.agentos.fixture.ACTION_REPLY_BLOCKED");
            // This is a cross-application synthetic event; older APIs have only
            // the legacy exported-by-default registration overload.
            if (Build.VERSION.SDK_INT >= 33) device.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else device.context.registerReceiver(receiver, filter);
        }

        Bundle call(String method) {
            Bundle result = device.context.getContentResolver().call(
                    Uri.parse("content://dev.agentos.fixture.slow-read-control"), method, token, null);
            assertNotNull("Missing fixture action-control response", result);
            return result;
        }

        @Override public void close() throws Exception {
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
