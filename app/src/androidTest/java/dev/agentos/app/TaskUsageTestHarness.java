package dev.agentos.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Uses the ordinary controller and synthetic HTTP/fixture; reflection only reads state/queues a hold. */
final class TaskUsageTestHarness implements AutoCloseable {
    final DeviceHarness device;
    final LocalControllerModelServer server;
    final ControllerTestSettings settings;
    final AgentController controller;
    final TaskBudget budget;
    final SettingsStore.ConfigurationLease lease;
    Activity activity;

    TaskUsageTestHarness(LocalControllerModelServer.Scenario scenario, TaskBudget budget) throws Exception {
        this.budget = budget;
        device = new DeviceHarness();
        LocalControllerModelServer opened = null;
        ControllerTestSettings saved = null;
        try {
            opened = new LocalControllerModelServer(scenario);
            saved = new ControllerTestSettings(device.context, opened);
            server = opened; settings = saved;
            controller = AgentController.get(device.context);
            assertFalse("Usage tests must not replace an existing task", controller.getState().running);
            settle();
            onMain(controller::clearCompletedTask);
            SettingsStore store = new SettingsStore(device.context);
            SettingsStore.SavedSettings configuration = store.load();
            configuration.taskBudget = budget;
            lease = store.save(configuration);
            onMain(() -> device.service.showOverlay("正在连接模型", false, ""));
            device.service.whenControlsReadyForAction().get(5, TimeUnit.SECONDS);
            device.awaitUiIdle();
            DeviceHarness.awaitObservation(device.tools, device.control,
                    value -> DeviceHarness.FIXTURE.equals(DeviceHarness.string(value, "packageName"))
                            && value.get("focused").getAsBoolean() && !value.get("hasOcclusion").getAsBoolean());
            device.instrumentation.waitForIdleSync();
            assertNull(controller.getTaskUsage());
        } catch (Exception | AssertionError failure) {
            if (saved != null) try { saved.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            if (opened != null) try { opened.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            try { device.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Report main-thread assertion failures to JUnit rather than terminating the app process. */
    void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> {
            try { action.run(); }
            catch (RuntimeException | AssertionError error) { failure.set(error); }
        });
        Throwable error = failure.get();
        if (error instanceof AssertionError) throw (AssertionError) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
    }

    void start() {
        onMain(() -> controller.start(settings.model,
                "Measure this synthetic task's actual local usage; do not approve any device write.",
                Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, budget, lease));
    }

    RunControl owner() throws Exception {
        Field field = AgentController.class.getDeclaredField("control");
        field.setAccessible(true);
        RunControl value = (RunControl) field.get(controller);
        assertNotNull("The real controller must still own this task", value);
        return value;
    }

    AgentController.TaskUsage awaitApproval() throws Exception {
        await("The actual HTTP click plan did not reach confirmation", () -> {
            server.assertHealthy();
            AgentController.State state = controller.getState();
            AgentController.TaskUsage usage = state.usage;
            return state.running && !state.approvalId.isEmpty() && usage != null && usage.consumed.approvalMillis >= 20;
        });
        device.service.whenControlsReadyForAction().get(5, TimeUnit.SECONDS);
        device.instrumentation.waitForIdleSync();
        assertTrue(server.requestCount() >= 1 && server.requestCount() <= 1 + LocalControllerModelServer.MAX_WRITE_REPLANS);
        assertEquals(server.requestCount(), server.writePlanCount());
        assertEquals(0, server.acceptedWriteCount());
        return controller.getTaskUsage();
    }

    void awaitPausedWorker() {
        await("The original task worker did not finish releasing its pending confirmation", () -> {
            if (!controller.getState().paused || !controller.getState().approvalId.isEmpty()) return false;
            // Read-only evidence that requestApproval's finally has completed and the
            // real worker has reached its ordinary RunControl pause wait. No hook or
            // task state is installed, and no timing value is fabricated.
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                if (!"agent-os-task".equals(entry.getKey().getName())) continue;
                for (StackTraceElement frame : entry.getValue())
                    if (RunControl.class.getName().equals(frame.getClassName())
                            && "awaitIfPaused".equals(frame.getMethodName())) return true;
            }
            return false;
        });
        device.instrumentation.waitForIdleSync();
    }

    AgentController.TaskUsage awaitStopped() throws Exception {
        await("The controller did not finish its actual task", () -> {
            server.assertHealthy();
            return !controller.getState().running;
        });
        settle();
        device.instrumentation.waitForIdleSync();
        AgentController.TaskUsage usage = controller.getTaskUsage();
        assertNotNull(usage);
        assertTrue(usage.finished);
        assertSame(usage, controller.getState().usage);
        return usage;
    }

    void stop() {
        onMain(controller::stop);
    }

    void settle() throws Exception { worker().submit(() -> { }).get(10, TimeUnit.SECONDS); }

    ThreadPoolExecutor worker() throws Exception {
        Field field = AgentController.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(controller);
    }

    Activity openMain() throws Exception {
        activity = device.instrumentation.startActivitySync(new Intent(device.context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("MainActivity did not finish loading its actual saved configuration", () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onMain(() -> ready.set(activity.findViewById(R.id.save_settings) != null
                    && !((MainActivity) activity).isFinishing()
                    && readBoolean(activity, "ready")
                    && readBoolean(activity, "resumed")));
            return ready.get();
        });
        onMain(() -> assertTrue("Protected task/configuration UI must stay secure",
                (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0));
        device.instrumentation.waitForIdleSync();
        return activity;
    }

    void finishActivity() {
        if (activity == null) return;
        Activity current = activity;
        onMain(current::finish);
        device.instrumentation.waitForIdleSync();
        await("The owned Activity did not destroy", current::isDestroyed);
        activity = null;
    }

    static boolean readBoolean(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    static Handler mainHandler(Activity activity) throws Exception { return (Handler) readField(activity, "main"); }
    static Runnable usageRefresh(Activity activity) throws Exception { return (Runnable) readField(activity, "taskUsageRefresh"); }
    static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    static void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }

    static void waitRealMillis(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
        }
    }

    static void assertSameCounters(RunControl.BudgetSnapshot expected, RunControl.BudgetSnapshot actual) {
        assertEquals(expected.modelRounds, actual.modelRounds);
        assertEquals(expected.toolCalls, actual.toolCalls);
        assertEquals(expected.activeMillis, actual.activeMillis);
        assertEquals(expected.pausedMillis, actual.pausedMillis);
        assertEquals(expected.approvalMillis, actual.approvalMillis);
    }

    static void assertNoDispatch(PerformanceTrace.Session trace) {
        assertEquals(0, trace.overwrittenCount());
        for (PerformanceTrace.Event event : trace.events())
            assertNotEquals("Usage inspection must not authorize the pending write", PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
    }

    void assertCounterZero() {
        List<AccessibilityWindowInfo> windows = device.automation.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/counter_value");
                    try {
                        if (nodes.isEmpty()) continue;
                        assertEquals(1, nodes.size());
                        assertTrue(nodes.get(0).refresh());
                        assertEquals("Count: 0", String.valueOf(nodes.get(0).getText()));
                        return;
                    } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        fail("The independent fixture counter is not currently visible");
    }

    @Override public void close() throws Exception {
        try {
            stop();
            try { settle(); }
            finally {
                try { finishActivity(); }
                finally {
                    onMain(controller::clearCompletedTask);
                    device.instrumentation.waitForIdleSync();
                }
            }
        } finally {
            try { settings.close(); }
            finally { try { server.close(); } finally { device.close(); } }
        }
    }

    /** A bounded harmless worker hold; no task state, permission, action guard or clock is replaced. */
    static final class WorkerHold implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> future;
        WorkerHold(TaskUsageTestHarness harness) throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            future = harness.worker().submit(() -> {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("Synthetic usage hold timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Synthetic usage hold interrupted", interrupted);
                }
            });
            assertTrue("The real task worker did not enter the harmless hold", entered.await(5, TimeUnit.SECONDS));
        }
        @Override public void close() throws Exception { release.countDown(); future.get(10, TimeUnit.SECONDS); }
    }
}
