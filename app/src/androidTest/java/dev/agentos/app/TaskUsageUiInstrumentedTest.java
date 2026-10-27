package dev.agentos.app;

import android.app.Activity;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.agentos.app.TaskUsageTestHarness.*;
import static org.junit.Assert.*;

/** Real protected MainActivity and real task snapshots; screenshots remain truthfully protected. */
@RunWith(AndroidJUnit4.class)
public final class TaskUsageUiInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_current_task_usage_capture_not_attempted");

    @Test public void foregroundUsageTicksOnlyItsViewAndBackgroundRemovesTheSingleCallback() throws Exception {
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.REJECT_CLICK, TaskBudget.DEFAULT)) {
            task.start(); task.awaitApproval();
            task.onMain(task.controller::pauseResume);
            task.awaitPausedWorker();
            Activity activity = task.openMain();
            Handler handler = mainHandler(activity);
            Runnable refresh = usageRefresh(activity);
            task.device.service.whenControlsReadyForAction().get(5, TimeUnit.SECONDS);
            task.device.instrumentation.waitForIdleSync();
            int requests = task.server.requestCount();
            List<String> logBefore = task.controller.getState().log;
            Object controlsBefore = task.device.service.getOwnedControlWindowIdentity();
            assertNotNull(controlsBefore);
            List<Long> changes = new ArrayList<>();
            CountDownLatch twoChanges = new CountDownLatch(2);
            AtomicReference<Throwable> watcherFailure = new AtomicReference<>();
            AtomicInteger listenerCalls = new AtomicInteger();
            AgentController.Listener listener = listenerCalls::incrementAndGet;
            TextWatcher watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    try {
                        assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
                        changes.add(System.nanoTime()); twoChanges.countDown();
                    } catch (RuntimeException | AssertionError error) {
                        watcherFailure.compareAndSet(null, error);
                        while (twoChanges.getCount() > 0) twoChanges.countDown();
                    }
                }
                @Override public void afterTextChanged(Editable value) { }
            };
            try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                task.onMain(() -> {
                    assertTrue(handler.hasCallbacks(refresh));
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.task_usage).getVisibility());
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                            activity.findViewById(R.id.task_usage).getAccessibilityLiveRegion());
                    ((TextView) activity.findViewById(R.id.task_usage_times)).addTextChangedListener(watcher);
                    task.controller.addListener(listener);
                    // These production reads must neither publish nor schedule separate timers.
                    for (int sample = 0; sample < 100; sample++) assertNotNull(task.controller.getTaskUsage());
                });
                assertTrue("Paused real-task time did not update in the foreground", twoChanges.await(6, TimeUnit.SECONDS));
                if (watcherFailure.get() != null) throw new AssertionError("Usage TextWatcher failed", watcherFailure.get());
                task.onMain(() -> {
                    ((TextView) activity.findViewById(R.id.task_usage_times)).removeTextChangedListener(watcher);
                    assertTrue(handler.hasCallbacks(refresh));
                    assertTrue(changes.size() >= 2);
                    for (int index = 1; index < changes.size(); index++) {
                        assertTrue("Periodic numeric writes must not run faster than the one-second UI timer",
                                changes.get(index) - changes.get(index - 1) >= TimeUnit.MILLISECONDS.toNanos(950));
                    }
                    assertEquals(expectedCounts(task.controller.getTaskUsage()), text(activity, R.id.task_usage_counts));
                    assertTrue(text(activity, R.id.task_usage_times).contains("暂停 "));
                    assertTrue(text(activity, R.id.task_usage_times).contains("等待确认 "));
                    assertFalse(text(activity, R.id.task_usage_times).contains(task.controller.getTaskUsage().taskId));
                });
                assertEquals("Usage polling must not call the Controller publication listener", 0, listenerCalls.get());
                assertEquals(logBefore, task.controller.getState().log);
                assertSame("The timer must not republish or replace the trusted control window",
                        controlsBefore, task.device.service.getOwnedControlWindowIdentity());
                assertEquals(requests, task.server.requestCount());
                assertEquals(0, trace.overwrittenCount());
                for (PerformanceTrace.Event event : trace.events()) {
                    assertNotEquals(PerformanceTrace.Stage.UI_PUBLICATION_QUEUE, event.stage);
                    assertNotEquals(PerformanceTrace.Stage.UI_PUBLICATION_APPLIED, event.stage);
                    assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
                }
                screenshots.capture("protected-current-task-numeric-view-refresh");
            } finally {
                task.controller.removeListener(listener);
                task.onMain(() ->
                        ((TextView) activity.findViewById(R.id.task_usage_times)).removeTextChangedListener(watcher));
            }
            task.onMain(() -> assertTrue(activity.moveTaskToBack(true)));
            await("Background Activity must remove its usage callback", () -> {
                AtomicReference<Boolean> removed = new AtomicReference<>(false);
                task.onMain(() -> removed.set(!readBoolean(activity, "resumed")
                        && !handler.hasCallbacks(refresh)));
                return removed.get();
            });
            AtomicReference<String> textAtPause = new AtomicReference<>();
            task.onMain(() -> textAtPause.set(text(activity, R.id.task_usage_times)));
            waitRealMillis(1_200);
            task.onMain(() -> {
                assertFalse(handler.hasCallbacks(refresh));
                assertEquals(textAtPause.get(), text(activity, R.id.task_usage_times));
            });
            assertTrue(task.controller.getState().paused);
            assertEquals(requests, task.server.requestCount());
        }
    }

    @Test public void endedTaskShowsFrozenCountsWithoutATimerAndClearOrNewTaskReplacesThem() throws Exception {
        TaskBudget budget = new TaskBudget(1, 100, 600_000);
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.EXHAUST_ROUND_BUDGET, budget)) {
            task.start();
            AgentController.TaskUsage ended = task.awaitStopped();
            assertEquals(1, task.server.requestCount());
            assertEquals("达到任务预算，已停止", task.controller.getState().status);
            Activity activity = task.openMain();
            Handler handler = mainHandler(activity);
            Runnable refresh = usageRefresh(activity);
            AtomicReference<String> endedTimes = new AtomicReference<>();
            task.onMain(() -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.task_usage).getVisibility());
                assertEquals("本次任务用量 · 已结束", text(activity, R.id.task_usage_heading));
                assertEquals(expectedCounts(ended), text(activity, R.id.task_usage_counts));
                assertFalse(handler.hasCallbacks(refresh));
                for (int id : new int[]{R.id.task_usage, R.id.task_usage_counts, R.id.task_usage_times})
                    assertFalse(activity.findViewById(id).isSaveEnabled());
                assertTrue(text(activity, R.id.task_usage_explanation).contains("失败调用"));
                endedTimes.set(text(activity, R.id.task_usage_times));
            });
            waitRealMillis(1_100);
            task.onMain(() -> {
                assertSame(ended, task.controller.getTaskUsage());
                assertEquals(endedTimes.get(), text(activity, R.id.task_usage_times));
                assertFalse(handler.hasCallbacks(refresh));
                assertTrue(activity.findViewById(R.id.clear_task).performClick());
                assertEquals(View.GONE, activity.findViewById(R.id.task_usage).getVisibility());
                assertEquals("", text(activity, R.id.task_usage_counts));
                assertEquals("", text(activity, R.id.task_usage_times));
                assertFalse(handler.hasCallbacks(refresh));
                assertNull(task.controller.getTaskUsage());
            });
            try (WorkerHold hold = new WorkerHold(task)) {
                try {
                    task.start();
                    AgentController.TaskUsage next = task.controller.getTaskUsage();
                    assertNotEquals(ended.taskId, next.taskId);
                    assertFalse(next.finished);
                    assertEquals(0, next.consumed.modelRounds);
                    assertEquals(0, next.consumed.toolCalls);
                    task.device.instrumentation.waitForIdleSync();
                    task.onMain(() -> {
                        assertEquals("本次任务用量", text(activity, R.id.task_usage_heading));
                        assertEquals(expectedCounts(next), text(activity, R.id.task_usage_counts));
                        assertEquals(View.VISIBLE, activity.findViewById(R.id.task_usage).getVisibility());
                        assertTrue(handler.hasCallbacks(refresh));
                    });
                    task.stop(); task.device.instrumentation.waitForIdleSync();
                    task.onMain(() -> {
                        assertEquals("本次任务用量 · 已结束", text(activity, R.id.task_usage_heading));
                        assertFalse(handler.hasCallbacks(refresh));
                    });
                } finally { task.stop(); }
            }
            task.settle();
            assertEquals("The second task was stopped in the queue, before any new model request", 1, task.server.requestCount());
            screenshots.capture("protected-task-usage-terminal-clear-and-new-identity");
            task.finishActivity();
            task.onMain(() -> assertFalse(handler.hasCallbacks(refresh)));
        }
    }

    private static String text(Activity activity, int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
    private static String expectedCounts(AgentController.TaskUsage usage) {
        return "模型轮次 " + usage.consumed.modelRounds + " / " + usage.budget.maxModelRounds
                + " · 工具调用 " + usage.consumed.toolCalls + " / " + usage.budget.maxToolCalls;
    }
}
