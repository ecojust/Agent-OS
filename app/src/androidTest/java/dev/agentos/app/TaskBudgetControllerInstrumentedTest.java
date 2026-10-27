package dev.agentos.app;

import android.content.Context;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Saved budgets constrain the real controller, loopback HTTP and Android device adapter together. */
@RunWith(AndroidJUnit4.class)
public final class TaskBudgetControllerInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void aSavedSingleModelRoundStopsAfterExactlyOneRealReadOnlyRequest() throws Exception {
        verifyLimit(new TaskBudget(1, 100, 600_000),
                LocalControllerModelServer.Scenario.EXHAUST_ROUND_BUDGET,
                "模型轮次已达到上限", "saved-single-model-round-ended-without-write");
    }

    @Test public void aSavedSingleToolCallRejectsTheRealClickPlanBeforeApprovalOrDispatch() throws Exception {
        verifyLimit(new TaskBudget(30, 1, 600_000), LocalControllerModelServer.Scenario.REJECT_CLICK,
                "工具调用次数已达到上限", "saved-single-tool-call-rejected-click-before-approval");
    }

    @Test public void anExplicitBudgetDifferentFromTheSavedOneCannotObserveOrContactTheModel() throws Exception {
        verifyMismatch(false);
    }

    @Test public void theLegacyDefaultOverloadCannotOverrideASavedSmallerBudget() throws Exception {
        verifyMismatch(true);
    }

    @Test public void anActiveTimeBudgetExpiringInTheWorkerQueueStopsBeforeObservationOrHttp() throws Exception {
        TaskBudget budget = new TaskBudget(30, 100, 1_000);
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             BudgetConfiguration settings = new BudgetConfiguration(device.context, server, budget)) {
            AgentController controller = AgentController.get(device.context);
            prepareStableFixture(device, controller);
            try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
                 ConfirmationMonitor approvals = new ConfirmationMonitor(controller)) {
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                Future<?> blocker = null;
                try {
                    ThreadPoolExecutor worker = controllerWorker(controller);
                    blocker = worker.submit(() -> {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic worker hold was not released");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Synthetic worker hold was unexpectedly interrupted", interrupted);
                        }
                    });
                    assertTrue("The harmless queue blocker did not enter the real worker", entered.await(5, TimeUnit.SECONDS));
                    device.instrumentation.runOnMainSync(() -> controller.start(settings.model,
                            "A queued synthetic task must respect its original activity-time budget.",
                            Collections.singleton(DeviceHarness.FIXTURE), settings.mode, budget, settings.lease));
                    assertTrue(controller.getState().running);
                    assertEquals("The actual controller task must be queued behind the harmless hold", 1, worker.getQueue().size());
                    assertEquals(0, server.requestCount());
                    assertEquals(0, events(trace, PerformanceTrace.Stage.SNAPSHOT_OBSERVE));

                    // Count from after start() returns, so the task's monotonic active
                    // timer has certainly started. Only queue residence is delayed:
                    // neither a slow network nor timing an entire UI flow is involved.
                    long beganWaiting = System.nanoTime();
                    long holdNanos = TimeUnit.MILLISECONDS.toNanos(1_100);
                    while (System.nanoTime() - beganWaiting < holdNanos) {
                        long remaining = holdNanos - (System.nanoTime() - beganWaiting);
                        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
                    }
                    assertTrue(System.nanoTime() - beganWaiting >= TimeUnit.MILLISECONDS.toNanos(budget.maxActiveMillis));
                    assertFalse("The queued task must not run before its hold is released", blocker.isDone());
                    assertEquals(1, worker.getQueue().size());
                    release.countDown();
                    blocker.get(10, TimeUnit.SECONDS);

                    AgentController.State finished = awaitStoppedAndSettled(device, controller, server);
                    assertEquals("达到任务预算，已停止", finished.status);
                    assertTrue(finished.summary, finished.summary.contains("任务活动时间已达到上限"));
                    assertEquals(0, approvals.count());
                    assertTrue(finished.approvalId.isEmpty());
                    assertEquals(0, server.requestCount());
                    assertEquals(0, server.writePlanCount());
                    assertEquals(0, events(trace, PerformanceTrace.Stage.SNAPSHOT_OBSERVE));
                    assertNoWrites(trace);
                    assertFixtureUnchanged(device);
                    assertFalse("Queue-budget expiry started a detached retry", server.receivedAnotherRequest(0, 250));
                    server.assertHealthy();
                    screenshots.capture("queued-active-time-budget-ended-before-observation");
                } catch (Exception | AssertionError failure) {
                    recordFailure(failure, controller, server, trace);
                    throw failure;
                } finally {
                    // A failed assertion must never release an uncancelled queued task.
                    device.instrumentation.runOnMainSync(controller::stop);
                    release.countDown();
                    try { if (blocker != null) blocker.get(10, TimeUnit.SECONDS); }
                    finally { clearAndSettle(device, controller); }
                }
            }
        }
    }

    private void verifyLimit(TaskBudget budget, LocalControllerModelServer.Scenario scenario,
                             String expectedSummary, String capture) throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(scenario);
             BudgetConfiguration settings = new BudgetConfiguration(device.context, server, budget)) {
            AgentController controller = AgentController.get(device.context);
            prepareStableFixture(device, controller);
            try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
                 ConfirmationMonitor approvals = new ConfirmationMonitor(controller)) {
                try {
                    device.instrumentation.runOnMainSync(() -> controller.start(settings.model,
                            "Use the synthetic fixture only; respect the locally saved task limits.",
                            Collections.singleton(DeviceHarness.FIXTURE), settings.mode, budget, settings.lease));
                    AgentController.State finished = awaitStoppedAndSettled(device, controller, server);
                    assertEquals("达到任务预算，已停止", finished.status);
                    assertTrue(finished.summary, finished.summary.contains(expectedSummary));
                    assertEquals("A failed initial observation is not a successful budget test", 1, server.requestCount());
                    assertEquals(0, approvals.count());
                    assertTrue(finished.approvalId.isEmpty());
                    assertNoWrites(trace);
                    assertTrue("The effective saved limits must be visible in the actual controller log",
                            finished.log.stream().anyMatch(line -> line.contains("本次预算：" + budget.maxModelRounds
                                    + " 轮模型请求、" + budget.maxToolCalls + " 次工具调用、"
                                    + budget.maxActiveMillis / 1000 + " 秒活动时间")));
                    if (scenario == LocalControllerModelServer.Scenario.REJECT_CLICK) {
                        assertEquals("The local HTTP response must contain one real click plan", 1, server.writePlanCount());
                        assertEquals("The successful initial observe consumes the only tool allowance", 1,
                                events(trace, PerformanceTrace.Stage.SNAPSHOT_OBSERVE));
                        assertEquals(1, successfulObservations(trace));
                    } else {
                        assertEquals(0, server.writePlanCount());
                        assertTrue("The model's sole read-only observe plan must actually execute",
                                successfulObservations(trace) >= 2);
                    }
                    assertEquals(0, server.acceptedWriteCount());
                    assertFixtureUnchanged(device);
                    // The worker barrier already proved the task has settled; this short
                    // network check additionally catches any incorrectly detached retry.
                    assertFalse("A finished budget-limited task sent another model request",
                            server.receivedAnotherRequest(1, 250));
                    server.assertHealthy();
                    screenshots.capture(capture);
                } catch (Exception | AssertionError failure) {
                    recordFailure(failure, controller, server, trace);
                    throw failure;
                } finally { clearAndSettle(device, controller); }
            }
        }
    }

    private void verifyMismatch(boolean useLegacyOverload) throws Exception {
        TaskBudget saved = new TaskBudget(1, 100, 600_000);
        TaskBudget supplied = new TaskBudget(2, 100, 600_000);
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             BudgetConfiguration settings = new BudgetConfiguration(device.context, server, saved)) {
            AgentController controller = AgentController.get(device.context);
            prepareStableFixture(device, controller);
            try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
                 ConfirmationMonitor approvals = new ConfirmationMonitor(controller)) {
                try {
                    device.instrumentation.runOnMainSync(() -> {
                        if (useLegacyOverload) {
                            controller.start(settings.model, "Reject an unsaved default task budget.",
                                    Collections.singleton(DeviceHarness.FIXTURE), settings.mode, settings.lease);
                        } else {
                            controller.start(settings.model, "Reject the caller's unsaved task budget.",
                                    Collections.singleton(DeviceHarness.FIXTURE), settings.mode, supplied, settings.lease);
                        }
                    });
                    AgentController.State finished = awaitStoppedAndSettled(device, controller, server);
                    assertEquals("执行失败", finished.status);
                    assertTrue("Expected saved-configuration verification, not an unrelated task failure: " + finished.summary,
                            finished.summary.contains("任务预算已改变"));
                    assertEquals(0, server.requestCount());
                    assertEquals(0, server.writePlanCount());
                    assertEquals(0, server.acceptedWriteCount());
                    assertEquals(0, approvals.count());
                    assertTrue(finished.approvalId.isEmpty());
                    assertNoWrites(trace);
                    assertEquals("Budget mismatch must fail before any Android observation", 0,
                            events(trace, PerformanceTrace.Stage.SNAPSHOT_OBSERVE));
                    assertEquals(saved, new SettingsStore(device.context).load().taskBudget);
                    assertTrue("A rejected caller must not replace the saved configuration", SettingsStore.isCurrent(settings.lease));
                    assertFixtureUnchanged(device);
                    assertFalse("A rejected budget started a detached HTTP request", server.receivedAnotherRequest(0, 250));
                    server.assertHealthy();
                    screenshots.capture(useLegacyOverload ? "legacy-default-cannot-override-saved-budget"
                            : "caller-budget-mismatch-rejected-before-observation");
                } catch (Exception | AssertionError failure) {
                    recordFailure(failure, controller, server, trace);
                    throw failure;
                } finally { clearAndSettle(device, controller); }
            }
        }
    }

    private static void prepareStableFixture(DeviceHarness device, AgentController controller) throws Exception {
        assertFalse("A budget test must not interrupt an existing task", controller.getState().running);
        awaitWorkerSettled(controller);
        device.instrumentation.waitForIdleSync();
        // Pre-layout this test-owned control bar exactly as start() will initially
        // present it. No sleep, foreign keyboard dismissal or guard bypass is used.
        device.instrumentation.runOnMainSync(() -> device.service.showOverlay("正在连接模型", false, ""));
        device.service.whenControlsReadyForAction().get(5, TimeUnit.SECONDS);
        device.awaitUiIdle();
        com.google.gson.JsonObject observed = DeviceHarness.awaitObservation(device.tools, device.control,
                data -> DeviceHarness.FIXTURE.equals(DeviceHarness.string(data, "packageName"))
                        && data.get("focused").getAsBoolean() && !data.get("hasOcclusion").getAsBoolean());
        assertEquals("Count: 0", DeviceHarness.string(DeviceHarness.node(observed, "counter_value"), "text"));
    }

    private static AgentController.State awaitStoppedAndSettled(DeviceHarness device, AgentController controller,
                                                               LocalControllerModelServer server) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AgentController.State state;
        do {
            server.assertHealthy();
            state = controller.getState();
            assertTrue("A budget rejection reached confirmation: " + state.approval, state.approvalId.isEmpty());
            if (!state.running) break;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertFalse("Task did not finish: " + state.status + " / " + state.summary, state.running);
        awaitWorkerSettled(controller);
        device.instrumentation.waitForIdleSync();
        server.assertHealthy();
        state = controller.getState();
        assertFalse("A settled task resumed itself", state.running);
        return state;
    }

    /** Queue a no-op after the real worker; never alter its control or execution state. */
    private static void awaitWorkerSettled(AgentController controller) throws Exception {
        controllerWorker(controller).submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    private static ThreadPoolExecutor controllerWorker(AgentController controller) throws Exception {
        Field field = AgentController.class.getDeclaredField("worker");
        field.setAccessible(true);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(controller);
        assertNotNull(worker);
        return worker;
    }

    private static void clearAndSettle(DeviceHarness device, AgentController controller) throws Exception {
        device.instrumentation.runOnMainSync(controller::stop);
        awaitWorkerSettled(controller);
        device.instrumentation.runOnMainSync(controller::clearCompletedTask);
        device.instrumentation.waitForIdleSync();
    }

    private static int events(PerformanceTrace.Session trace, PerformanceTrace.Stage stage) {
        assertEquals("Lost trace events cannot prove the absence of work", 0, trace.overwrittenCount());
        int count = 0;
        for (PerformanceTrace.Event event : trace.events()) if (event.stage == stage) count++;
        return count;
    }

    private static int successfulObservations(PerformanceTrace.Session trace) {
        int count = 0;
        for (PerformanceTrace.Event event : trace.events()) {
            if (event.stage == PerformanceTrace.Stage.SNAPSHOT_OBSERVE && event.nodeCount > 0) {
                assertFalse("Controller observations belong on its worker", event.mainThread);
                count++;
            }
        }
        return count;
    }

    private static void assertNoWrites(PerformanceTrace.Session trace) {
        for (PerformanceTrace.Stage stage : new PerformanceTrace.Stage[]{PerformanceTrace.Stage.SNAPSHOT_PREPARE,
                PerformanceTrace.Stage.SNAPSHOT_FINAL_LAUNCH, PerformanceTrace.Stage.SNAPSHOT_FINAL_MUTATION,
                PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT}) {
            assertEquals("The limited/rejected task reached " + stage, 0, events(trace, stage));
        }
    }

    private static void assertFixtureUnchanged(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    assertText(root, "counter_value", "Count: 0");
                    assertText(root, "saved_value", "Saved: empty");
                    return;
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        fail("The independent fixture result is not visible\n" + DeviceHarness.windowDiagnostics());
    }

    private static void assertText(AccessibilityNodeInfo root, String id, String expected) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/" + id);
        try {
            assertEquals("Expected one actual fixture value: " + id, 1, nodes.size());
            assertTrue(nodes.get(0).refresh());
            assertEquals(expected, String.valueOf(nodes.get(0).getText()));
        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
    }

    private void recordFailure(Throwable failure, AgentController controller, LocalControllerModelServer server,
                               PerformanceTrace.Session trace) {
        AgentController.State state = controller.getState();
        failure.addSuppressed(new AssertionError("Synthetic budget state: status=" + state.status + "; summary="
                + state.summary + "; requests=" + server.requestCount() + "; writePlans=" + server.writePlanCount()
                + "; observations=" + events(trace, PerformanceTrace.Stage.SNAPSHOT_OBSERVE)
                + "; dispatches=" + events(trace, PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT)));
        screenshots.capture("task-budget-failure-before-cleanup");
    }

    private static final class ConfirmationMonitor implements AutoCloseable {
        private final AgentController controller;
        private final Set<String> approvals = Collections.synchronizedSet(new HashSet<>());
        private final AgentController.Listener listener;
        ConfirmationMonitor(AgentController controller) {
            this.controller = controller;
            listener = () -> {
                String id = controller.getState().approvalId;
                if (!id.isEmpty()) approvals.add(id);
            };
            controller.addListener(listener);
        }
        int count() { return approvals.size(); }
        @Override public void close() { controller.removeListener(listener); }
    }

    private static final class BudgetConfiguration implements AutoCloseable {
        private final ControllerTestSettings original;
        final ModelSettings model;
        final ActionConfirmationPolicy.Mode mode;
        final SettingsStore.ConfigurationLease lease;
        BudgetConfiguration(Context context, LocalControllerModelServer server, TaskBudget budget) throws IOException {
            original = new ControllerTestSettings(context, server);
            model = original.model;
            mode = original.executionMode;
            try {
                SettingsStore store = new SettingsStore(context);
                SettingsStore.SavedSettings saved = store.load();
                saved.taskBudget = budget;
                lease = store.save(saved);
                assertFalse(SettingsStore.isCurrent(original.lease));
                assertTrue(SettingsStore.isCurrent(lease));
            } catch (IOException | RuntimeException | AssertionError failure) {
                try { original.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public void close() throws IOException { original.close(); }
    }
}
