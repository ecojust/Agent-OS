package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.platform.app.InstrumentationRegistry;

import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
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
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        controller = AgentController.get(context);
        assertFalse("Usage tests must not replace an existing task", controller.getState().running);
        LocalControllerModelServer opened = null;
        ControllerTestSettings saved = null;
        DeviceHarness attached = null;
        try {
            // Drain the old worker (including its configuration-watch finally), then
            // apply idle cleanup before DeviceHarness installs its ordinary controls.
            // An idle Controller publication intentionally removes service callbacks.
            settle();
            onMain(instrumentation, controller::clearCompletedTask);
            instrumentation.waitForIdleSync();
            opened = new LocalControllerModelServer(scenario);
            saved = new ControllerTestSettings(context, opened);
            server = opened; settings = saved;
            SettingsStore store = new SettingsStore(context);
            SettingsStore.SavedSettings configuration = store.load();
            configuration.taskBudget = budget;
            lease = store.save(configuration);
            // Both real saves can invalidate old configuration observers. Drain their
            // main-thread notifications too; never repair callbacks after observation.
            settle();
            instrumentation.waitForIdleSync();
            assertFalse(controller.getState().running);
            assertNull(controller.getTaskUsage());
            attached = new DeviceHarness();
            device = attached;
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
            if (attached != null) try { attached.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Report main-thread assertion failures to JUnit rather than terminating the app process. */
    void onMain(Runnable action) { onMain(device.instrumentation, action); }

    private static void onMain(Instrumentation instrumentation, Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
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
        AgentController.State expected = controller.getState();
        // An approval deliberately keeps the controls expanded. The compact-write
        // readiness future cannot complete here and must not be used as a read guard.
        await("This task's approval was not actually published in the control window",
                () -> approvalWindowReady(expected));
        java.util.concurrent.CompletableFuture<AgentAccessibilityService.OwnedControlWindowIdentity> ready =
                device.service.acquireOwnedControlWindowIdentity();
        try {
            AgentAccessibilityService.OwnedControlWindowIdentity identity = ready.get(5, TimeUnit.SECONDS);
            onMain(() -> {
                assertTrue(approvalWindowReady(expected));
                assertSame("The expanded approval must still own the acquired actual window identity",
                        identity, device.service.getOwnedControlWindowIdentity());
            });
        } finally { ready.cancel(false); }
        device.instrumentation.waitForIdleSync();
        assertTrue(server.requestCount() >= 1 && server.requestCount() <= 1 + LocalControllerModelServer.MAX_WRITE_REPLANS);
        assertEquals(server.requestCount(), server.writePlanCount());
        assertEquals(0, server.acceptedWriteCount());
        return controller.getTaskUsage();
    }

    /** Reads actual presentation and callback ownership only; never acquires an action lease. */
    private boolean approvalWindowReady(AgentController.State expected) {
        AtomicBoolean ready = new AtomicBoolean();
        Runnable inspect = () -> {
            AgentController.State current = controller.getState();
            assertTrue("The pending task ended before its approval was presented", current.running);
            assertNotNull(current.usage);
            assertEquals(expected.usage.taskId, current.usage.taskId);
            assertEquals(expected.approvalId, current.approvalId);
            try {
                android.view.View panel = (android.view.View) readField(device.service, "overlay");
                android.view.View scroll = (android.view.View) readField(device.service, "approvalScroll");
                android.view.View buttons = (android.view.View) readField(device.service, "approvalButtons");
                Object callbacks = readField(device.service, "callbacks");
                RunControl callbackOwner = (RunControl) readField(controller, "overlayCallbackOwner");
                ready.set(panel != null && panel.isAttachedToWindow() && panel.isShown()
                        && panel.isLaidOut() && !panel.isLayoutRequested()
                        && scroll != null && scroll.isShown() && buttons != null && buttons.isShown()
                        && expected.approval.equals(readField(device.service, "currentApproval"))
                        && callbacks != null && callbacks == readField(controller, "overlayCallbacks")
                        && callbackOwner != null && expected.usage.taskId.equals(callbackOwner.getTaskId())
                        && expected.approvalId.equals(readField(controller, "overlayCallbackApprovalId"))
                        && readField(device.service, "displayedCallbackGeneration")
                                .equals(readField(device.service, "callbackGeneration")));
            } catch (Exception failure) { throw new AssertionError("Cannot read actual approval presentation", failure); }
        };
        // The final identity comparison runs in the same main-thread turn as this
        // inspection. Other callers use onMain so assertions still return to JUnit.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) inspect.run();
        else onMain(inspect);
        return ready.get();
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

    void settle() throws Exception { drainWorker(worker()); }

    /** Test-only drain: retry only a definitely rejected no-op, never a task or device action. */
    static void drainWorker(ThreadPoolExecutor executor) throws Exception {
        long started = System.nanoTime();
        long allowance = TimeUnit.SECONDS.toNanos(10);
        Future<?> barrier = null;
        try {
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException("Test worker drain interrupted");
                if (executor.isShutdown()) throw new RejectedExecutionException("Test worker is shut down");
                long remaining = allowance - (System.nanoTime() - started);
                if (remaining <= 0) throw new TimeoutException("Real task worker did not drain within ten seconds");
                if (barrier == null) {
                    try { barrier = executor.submit(() -> { }); }
                    catch (RejectedExecutionException full) {
                        // The Controller uses AbortPolicy. Only this explicit rejection
                        // proves the no-op was not accepted; do not inspect/remove tasks.
                        if (executor.isShutdown()) throw full;
                        remaining = allowance - (System.nanoTime() - started);
                        if (remaining <= 0) throw new TimeoutException("Real task worker queue remained full");
                        TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)));
                        continue;
                    }
                }
                // Once accepted, wait only for this exact barrier. A short get also
                // notices shutdown without renewing the original overall deadline.
                if (executor.isShutdown()) throw new RejectedExecutionException("Test worker shut down during drain");
                remaining = allowance - (System.nanoTime() - started);
                if (remaining <= 0) throw new TimeoutException("Accepted test barrier exceeded the drain deadline");
                try {
                    barrier.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException stillRunning) {
                    // Keep the same accepted future; no resubmission and no idle claim.
                    continue;
                }
                if (executor.isShutdown()) throw new RejectedExecutionException("Test worker shut down before drain completed");
                if (allowance - (System.nanoTime() - started) <= 0)
                    throw new TimeoutException("Test barrier completed after the drain deadline");
                return;
            }
        } finally {
            if (barrier != null && !barrier.isDone()) barrier.cancel(false);
        }
    }

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
