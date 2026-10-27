package dev.agentos.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static dev.agentos.app.TaskUsageTestHarness.*;
import static org.junit.Assert.*;

/** User-visible accounting is checked against actual Controller/HTTP/control lifecycles, not fake values. */
@RunWith(AndroidJUnit4.class)
public final class TaskUsageControllerInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void actualReadOnlyBudgetCompletionFreezesTheOriginalControlCounters() throws Exception {
        TaskBudget budget = new TaskBudget(1, 100, 600_000);
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.EXHAUST_ROUND_BUDGET, budget);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            RunControl owner;
            // Capture the real owner while queued, before the one-round task can finish.
            try (WorkerHold hold = new WorkerHold(task)) {
                boolean captured = false;
                try {
                    task.start(); owner = task.owner();
                    assertEquals(owner.getTaskId(), task.controller.getTaskUsage().taskId);
                    captured = true;
                } finally {
                    // A failed setup must not release an uncancelled queued task.
                    if (!captured) task.stop();
                }
            }
            AgentController.TaskUsage ended = task.awaitStopped();
            assertEquals("达到任务预算，已停止", task.controller.getState().status);
            assertEquals(budget, ended.budget);
            assertEquals(owner.getTaskId(), ended.taskId);
            assertTrue(owner.isCancelled());
            assertSameCounters(owner.budgetSnapshot(), ended.consumed);
            assertEquals(1, task.server.requestCount());
            assertEquals(task.server.requestCount(), ended.consumed.modelRounds);
            assertTrue("The actual initial observation plus the model's observe call consume tools", ended.consumed.toolCalls >= 2);
            assertEquals(0, ended.consumed.pausedMillis);
            assertEquals(0, ended.consumed.approvalMillis);
            assertTrue(ended.consumed.activeMillis > 0);
            waitRealMillis(250);
            assertSame("Ended accounting must not keep sampling time", ended, task.controller.getTaskUsage());
            assertSameCounters(owner.budgetSnapshot(), ended.consumed);
            assertNoDispatch(trace);
            task.assertCounterZero();
            task.server.assertHealthy();
            screenshots.capture("real-read-only-budget-finished-accounting-frozen");
        }
    }

    @Test public void confirmationAndPauseAccumulateSeparatelyAndStopFreezesBeforeTeardown() throws Exception {
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.REJECT_CLICK, TaskBudget.DEFAULT);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            task.start();
            RunControl owner = task.owner();
            AgentController.TaskUsage waiting = task.awaitApproval();
            String approval = task.controller.getState().approvalId;
            int requestsAtApproval = task.server.requestCount();
            waitRealMillis(250);
            AgentController.TaskUsage waited = task.controller.getTaskUsage();
            assertEquals(waiting.taskId, waited.taskId);
            assertEquals(requestsAtApproval, waited.consumed.modelRounds);
            assertTrue(waited.consumed.toolCalls >= 2);
            assertTrue(waited.consumed.approvalMillis >= waiting.consumed.approvalMillis + 200);
            assertEquals(waiting.consumed.activeMillis, waited.consumed.activeMillis);
            assertEquals(0, waited.consumed.pausedMillis);

            task.onMain(task.controller::pauseResume);
            assertTrue(task.controller.getState().paused);
            assertTrue(task.controller.getState().approvalId.isEmpty());
            AgentController.TaskUsage paused = task.controller.getTaskUsage();
            waitRealMillis(250);
            AgentController.TaskUsage stillPaused = task.controller.getTaskUsage();
            assertTrue(stillPaused.consumed.pausedMillis >= paused.consumed.pausedMillis + 200);
            assertEquals(paused.consumed.activeMillis, stillPaused.consumed.activeMillis);
            assertEquals(paused.consumed.approvalMillis, stillPaused.consumed.approvalMillis);
            assertEquals(paused.consumed.modelRounds, stillPaused.consumed.modelRounds);
            assertEquals(paused.consumed.toolCalls, stillPaused.consumed.toolCalls);
            task.onMain(() -> task.controller.approve(approval, true));
            task.stop();
            AgentController.TaskUsage ended = task.controller.getTaskUsage();
            assertNotNull(ended);
            assertTrue(ended.finished);
            assertSameCounters(owner.budgetSnapshot(), ended.consumed);
            task.settle(); task.device.instrumentation.waitForIdleSync();
            waitRealMillis(150);
            assertSame("The draining worker must not overwrite stop's frozen accounting", ended, task.controller.getTaskUsage());
            assertSame(ended, task.controller.getState().usage);
            assertEquals(requestsAtApproval, task.server.requestCount());
            assertNoDispatch(trace);
            task.assertCounterZero();
            screenshots.capture("confirmation-pause-and-stopped-usage-real-controller");
        }
    }

    @Test public void queuedResumePreservesUsageAndLateOldWorkerCannotReplaceANewRejectedTask() throws Exception {
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.REJECT_CLICK, TaskBudget.DEFAULT);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AgentController.TaskUsage rejected;
            RunControl oldOwner;
            try (WorkerHold hold = new WorkerHold(task)) {
                try {
                    task.start(); oldOwner = task.owner();
                    AgentController.TaskUsage initial = task.controller.getTaskUsage();
                    assertEquals(1, task.worker().getQueue().size());
                    assertEquals(0, initial.consumed.modelRounds);
                    assertEquals(0, initial.consumed.toolCalls);
                    waitRealMillis(150);
                    task.onMain(task.controller::pauseResume);
                    AgentController.TaskUsage paused = task.controller.getTaskUsage();
                    assertTrue(paused.consumed.activeMillis >= initial.consumed.activeMillis + 100);
                    waitRealMillis(200);
                    AgentController.TaskUsage beforeResume = task.controller.getTaskUsage();
                    AtomicReference<AgentController.TaskUsage> resumed = new AtomicReference<>();
                    task.onMain(() -> {
                        task.controller.pauseResume();
                        resumed.set(task.controller.getTaskUsage());
                    });
                    assertEquals(initial.taskId, resumed.get().taskId);
                    assertFalse(task.controller.getState().paused);
                    assertTrue(resumed.get().consumed.pausedMillis >= beforeResume.consumed.pausedMillis);
                    assertEquals(beforeResume.consumed.modelRounds, resumed.get().consumed.modelRounds);
                    assertEquals(beforeResume.consumed.toolCalls, resumed.get().consumed.toolCalls);
                    task.stop();
                    AgentController.TaskUsage oldFinal = task.controller.getTaskUsage();
                    assertTrue(oldFinal.finished);
                    assertSameCounters(oldOwner.budgetSnapshot(), oldFinal.consumed);
                    // The first cancelled Runnable still occupies the one-slot real queue.
                    // Start the replacement normally: its rejection must own a new terminal snapshot.
                    task.start();
                    rejected = task.controller.getTaskUsage();
                    assertTrue(rejected.finished);
                    assertNotEquals(oldFinal.taskId, rejected.taskId);
                    assertEquals("执行失败", task.controller.getState().status);
                    assertTrue(task.controller.getState().summary.contains("上一任务仍在释放资源"));
                    assertEquals(0, rejected.consumed.modelRounds);
                    assertEquals(0, rejected.consumed.toolCalls);
                    assertEquals(0, rejected.consumed.pausedMillis);
                    assertEquals(0, rejected.consumed.approvalMillis);
                    assertEquals(0, task.server.requestCount());
                } finally { task.stop(); }
            }
            task.settle(); task.device.instrumentation.waitForIdleSync();
            assertSame("Old queued task finally/finish must not replace the newer task", rejected, task.controller.getTaskUsage());
            assertSame(rejected, task.controller.getState().usage);
            assertEquals(0, task.server.requestCount());
            assertNoDispatch(trace);
            task.assertCounterZero();
            task.onMain(task.controller::clearCompletedTask);
            assertNull(task.controller.getTaskUsage());
            assertNull(task.controller.getState().usage);
            screenshots.capture("queued-task-resume-and-replacement-accounting-isolated");
        }
    }

    @Test public void configurationInvalidationRetainsOriginalLimitsUntilTheUserClearsTheTask() throws Exception {
        TaskBudget original = new TaskBudget(7, 23, 120_000);
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.REJECT_CLICK, original);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            task.start(); RunControl owner = task.owner();
            AgentController.TaskUsage waiting = task.awaitApproval();
            String oldApproval = task.controller.getState().approvalId;
            int requestsAtApproval = task.server.requestCount();
            SettingsStore store = new SettingsStore(task.device.context);
            SettingsStore.SavedSettings replacement = store.load();
            replacement.taskBudget = new TaskBudget(9, 27, 180_000);
            SettingsStore.ConfigurationLease newLease = store.save(replacement);
            assertFalse(SettingsStore.isCurrent(task.lease));
            assertTrue(SettingsStore.isCurrent(newLease));
            AgentController.TaskUsage ended = task.awaitStopped();
            assertEquals("配置已改变，任务已停止", task.controller.getState().status);
            assertEquals(waiting.taskId, ended.taskId);
            assertEquals(original, ended.budget);
            assertNotEquals(replacement.taskBudget, ended.budget);
            assertSameCounters(owner.budgetSnapshot(), ended.consumed);
            task.onMain(() -> task.controller.approve(oldApproval, true));
            waitRealMillis(150);
            assertSame(ended, task.controller.getTaskUsage());
            assertEquals(requestsAtApproval, ended.consumed.modelRounds);
            assertEquals(requestsAtApproval, task.server.requestCount());
            assertNoDispatch(trace);
            task.assertCounterZero();
            screenshots.capture("configuration-save-keeps-original-task-accounting");
            task.onMain(task.controller::clearCompletedTask);
            assertNull(task.controller.getTaskUsage());
            assertNull(task.controller.getState().usage);
        }
    }
}
