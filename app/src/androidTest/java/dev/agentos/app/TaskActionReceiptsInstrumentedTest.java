package dev.agentos.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskActionReceipts;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.agentos.app.TaskUsageTestHarness.*;
import static org.junit.Assert.*;

/** Real HTTP/fixture rejection/interruption and protected receipt previews; no fabricated results. */
@RunWith(AndroidJUnit4.class)
public final class TaskActionReceiptsInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_task_action_receipts_capture_not_attempted");

    @Test public void rejectedHttpActionRemainsUnsubmittedAndItsSecurePreviewIsReadOnly() throws Exception {
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.REJECT_CLICK, TaskBudget.DEFAULT)) {
            assertNull(task.controller.getTaskActionReceipts());
            task.start(); task.awaitApproval();
            RunControl owner = task.owner();
            String approvalId = task.controller.getState().approvalId;
            TaskActionReceipts waiting = task.controller.getTaskActionReceipts();
            assertNotNull(waiting); assertFalse(waiting.frozen);
            assertEquals(owner.getTaskId(), waiting.taskId);
            TaskActionReceipts.Entry pending = last(waiting);
            assertEquals(TaskActionReceipts.Action.CLICK, pending.action);
            assertEquals(TaskActionReceipts.Stage.WAITING_RESULT, pending.stage);
            assertEquals("Waiting for a result is not proof of non-submission",
                    TaskActionReceipts.Fact.UNKNOWN, pending.submitted);
            assertEquals(TaskActionReceipts.Fact.UNKNOWN, pending.accepted);
            int requests = task.server.requestCount();
            task.assertCounterZero();
            task.onMain(() -> task.controller.approve(approvalId, false));
            task.awaitStopped();

            TaskActionReceipts ended = task.controller.getTaskActionReceipts();
            assertRejected(ended);
            assertEquals(waiting.taskId, ended.taskId);
            assertEquals(pending.ordinal, last(ended).ordinal);
            assertEquals("An earlier immutable snapshot must retain its original pending fact",
                    TaskActionReceipts.Stage.WAITING_RESULT, last(waiting).stage);
            assertEquals(0, owner.getDispatchAttempts());
            assertEquals(0, task.server.acceptedWriteCount());
            task.assertCounterZero();
            task.onMain(() -> task.controller.approve(approvalId, true));
            assertFalse("A rejected task cannot issue a fallback request",
                    task.server.receivedAnotherRequest(requests, 350));
            assertEquals(requests, task.server.requestCount());
            assertEquals(ended.version, task.controller.getTaskActionReceipts().version);
            assertEquals(ended.entries.size(), task.controller.getTaskActionReceipts().entries.size());
            task.assertCounterZero();

            Activity activity = task.openMain();
            AtomicInteger publications = new AtomicInteger();
            AgentController.Listener listener = publications::incrementAndGet;
            AtomicReference<AlertDialog> shownDialog = new AtomicReference<>();
            AtomicReference<TaskActionReceipts> shown = new AtomicReference<>();
            AtomicReference<String> text = new AtomicReference<>();
            task.controller.addListener(listener);
            try {
                task.onMain(() -> {
                    assertTrue(activity.findViewById(R.id.task_action_receipts).performClick());
                    shownDialog.set(dialog(activity)); shown.set(snapshot(activity));
                    assertNotNull(shown.get());
                    assertEquals(ended.taskId, shown.get().taskId);
                    assertEquals(ended.version, shown.get().version);
                    text.set(assertSecureReport(shownDialog.get(), shown.get(), task));
                });
                task.device.instrumentation.waitForIdleSync();
                task.onMain(() -> {
                    assertSame(shownDialog.get(), dialog(activity));
                    assertSame(shown.get(), snapshot(activity));
                    assertEquals(text.get(), report(shownDialog.get()).getText().toString());
                    assertEquals(ended.version, task.controller.getTaskActionReceipts().version);
                    assertTrue(closeButton(shownDialog.get()).performClick());
                });
                awaitClosed(task, activity);
                assertEquals("Opening/reading/closing receipts cannot publish a task action", 0, publications.get());
                assertEquals(0, owner.getDispatchAttempts());
                assertEquals(requests, task.server.requestCount());
                assertEquals(ended.version, task.controller.getTaskActionReceipts().version);
                screenshots.capture("protected-receipts-retained-after-real-rejection");
            } finally { task.controller.removeListener(listener); }
        }
    }

    @Test public void replacementLeavingAndClearInvalidateOldReceiptDialogsAndTaskHistory() throws Exception {
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.REJECT_CLICK, TaskBudget.DEFAULT)) {
            task.start(); task.awaitApproval();
            RunControl stoppedOwner = task.owner();
            TaskActionReceipts waiting = task.controller.getTaskActionReceipts();
            assertEquals(TaskActionReceipts.Stage.WAITING_RESULT, last(waiting).stage);
            task.stop();
            task.awaitStopped();
            TaskActionReceipts original = task.controller.getTaskActionReceipts();
            assertUnresolvedStopped(original);
            assertEquals(waiting.taskId, original.taskId);
            assertEquals(last(waiting).ordinal, last(original).ordinal);
            assertEquals("Freezing the task must not mutate the earlier pending snapshot",
                    TaskActionReceipts.Stage.WAITING_RESULT, last(waiting).stage);
            assertEquals(0, stoppedOwner.getDispatchAttempts());
            task.assertCounterZero();
            int requests = task.server.requestCount();
            Activity activity = task.openMain();
            AtomicReference<AlertDialog> oldDialog = new AtomicReference<>();
            AtomicReference<Button> oldClose = new AtomicReference<>();
            task.onMain(() -> {
                assertTrue(activity.findViewById(R.id.task_action_receipts).performClick());
                oldDialog.set(dialog(activity)); oldClose.set(closeButton(oldDialog.get()));
                assertSecureReport(oldDialog.get(), snapshot(activity), task);
            });

            // Hold only the actual worker with a bounded no-op. No task result,
            // permission, receipt or timing state is replaced by the test.
            try (WorkerHold hold = new WorkerHold(task)) {
                try {
                    task.start();
                    RunControl replacement = task.owner();
                    TaskActionReceipts fresh = task.controller.getTaskActionReceipts();
                    assertNotNull(fresh); assertFalse(fresh.frozen); assertTrue(fresh.entries.isEmpty());
                    assertEquals(replacement.getTaskId(), fresh.taskId);
                    assertNotEquals(original.taskId, fresh.taskId);
                    task.device.instrumentation.waitForIdleSync();
                    AtomicReference<AlertDialog> replacementDialog = new AtomicReference<>();
                    task.onMain(() -> {
                        assertNull(dialog(activity)); assertNull(snapshot(activity));
                        assertFalse(oldDialog.get().isShowing());
                        assertFalse(replacement.isCancelled());
                        assertTrue(activity.findViewById(R.id.task_action_receipts).performClick());
                        replacementDialog.set(dialog(activity));
                        assertEquals(fresh.taskId, snapshot(activity).taskId);
                        assertTrue(snapshot(activity).entries.isEmpty());
                        assertSecureReport(dialog(activity), snapshot(activity), task);
                        assertFalse("Opening a fixed preview cannot freeze the running task", task.controller.getTaskActionReceipts().frozen);
                        assertTrue(oldClose.get().performClick());
                    });
                    task.device.instrumentation.waitForIdleSync();
                    task.onMain(() -> {
                        assertSame("The old dialog's delayed dismissal cannot close the replacement preview",
                                replacementDialog.get(), dialog(activity));
                        assertEquals(fresh.taskId, snapshot(activity).taskId);
                        assertTrue(activity.moveTaskToBack(true));
                    });
                    await("Leaving MainActivity must release the receipt preview", () -> {
                        AtomicReference<Boolean> closed = new AtomicReference<>(false);
                        task.onMain(() -> closed.set(!readBoolean(activity, "resumed")
                                && dialog(activity) == null && snapshot(activity) == null));
                        return closed.get();
                    });
                    task.finishActivity();
                    Activity returned = task.openMain();
                    task.onMain(() -> {
                        assertNull(dialog(returned)); assertNull(snapshot(returned));
                        assertEquals(fresh.taskId, task.controller.getTaskActionReceipts().taskId);
                        assertTrue(task.controller.getTaskActionReceipts().entries.isEmpty());
                        assertFalse(replacement.isCancelled());
                    });
                    assertEquals("The held replacement has not sent a model request", requests, task.server.requestCount());
                    assertUnresolvedStopped(original);
                } finally { task.stop(); }
            }
            task.awaitStopped();
            assertTrue(task.controller.getTaskActionReceipts().frozen);
            assertTrue(task.controller.getTaskActionReceipts().entries.isEmpty());
            Activity returned = task.activity;
            task.onMain(() -> {
                assertTrue(returned.findViewById(R.id.task_action_receipts).performClick());
                AlertDialog clearing = dialog(returned);
                TaskActionReceipts shown = snapshot(returned);
                Button staleClose = closeButton(clearing);
                assertSecureReport(clearing, shown, task);
                // Invoke the actual clear listener for lifecycle testing; this is
                // not a claim that a user can touch through a modal dialog.
                assertTrue(returned.findViewById(R.id.clear_task).performClick());
                assertNull(task.controller.getTaskActionReceipts());
                assertNull(dialog(returned)); assertNull(snapshot(returned));
                assertFalse(clearing.isShowing());
                assertTrue(staleClose.performClick());
                assertNull(task.controller.getTaskActionReceipts());
                assertTrue(shown.entries.isEmpty());
                assertUnresolvedStopped(original);
            });
            assertEquals(requests, task.server.requestCount());
            screenshots.capture("protected-receipts-cleared-and-old-preview-invalidated");
        }
    }

    private static TaskActionReceipts.Entry last(TaskActionReceipts receipts) {
        assertFalse(receipts.entries.isEmpty());
        return receipts.entries.get(receipts.entries.size() - 1);
    }

    private static void assertRejected(TaskActionReceipts receipts) {
        assertNotNull(receipts); assertTrue(receipts.frozen);
        assertEquals(0, receipts.omittedCount);
        TaskActionReceipts.Entry denied = last(receipts);
        assertEquals(TaskActionReceipts.Action.CLICK, denied.action);
        assertEquals(TaskActionReceipts.Stage.RETURNED, denied.stage);
        assertEquals("USER_DENIED", denied.code);
        assertEquals(TaskActionReceipts.Fact.NO, denied.submitted);
        assertEquals(TaskActionReceipts.Fact.NO, denied.accepted);
        for (TaskActionReceipts.Entry entry : receipts.entries) {
            assertEquals(TaskActionReceipts.Stage.RETURNED, entry.stage);
            assertNotEquals(TaskActionReceipts.Fact.YES, entry.accepted);
        }
    }

    private static void assertUnresolvedStopped(TaskActionReceipts receipts) {
        assertNotNull(receipts); assertTrue(receipts.frozen);
        assertEquals(0, receipts.omittedCount);
        TaskActionReceipts.Entry unfinished = last(receipts);
        assertEquals(TaskActionReceipts.Action.CLICK, unfinished.action);
        assertEquals(TaskActionReceipts.Stage.NO_FINAL_RESULT, unfinished.stage);
        // Independent fixture/dispatch checks cannot manufacture a final tool
        // result that was not returned before the Controller froze its history.
        assertEquals(TaskActionReceipts.Fact.UNKNOWN, unfinished.submitted);
        assertEquals(TaskActionReceipts.Fact.UNKNOWN, unfinished.accepted);
        assertEquals(TaskActionReceipts.Verification.UNKNOWN, unfinished.verification);
        for (TaskActionReceipts.Entry entry : receipts.entries)
            assertNotEquals(TaskActionReceipts.Fact.YES, entry.accepted);
    }

    private static AlertDialog dialog(Activity activity) {
        try { return (AlertDialog) readField(activity, "taskActionReceiptsDialog"); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static TaskActionReceipts snapshot(Activity activity) {
        try { return (TaskActionReceipts) readField(activity, "displayedTaskActionReceipts"); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static TextView report(AlertDialog dialog) {
        assertNotNull(dialog);
        TextView report = dialog.findViewById(R.id.task_action_receipts_report);
        assertNotNull(report); return report;
    }

    private static void awaitClosed(TaskUsageTestHarness task, Activity activity) {
        await("The actual dialog dismissal did not release its fixed receipt snapshot", () -> {
            AtomicReference<Boolean> closed = new AtomicReference<>(false);
            task.onMain(() -> closed.set(dialog(activity) == null && snapshot(activity) == null));
            return closed.get();
        });
    }

    private static Button closeButton(AlertDialog dialog) {
        assertNotNull(dialog);
        Button only = null;
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null || button.getVisibility() != View.VISIBLE) continue;
            assertNull("The read-only preview must expose only its close action", only);
            only = button;
        }
        assertNotNull(only); assertTrue(only.isEnabled());
        assertTrue(only.getMinHeight() >= Math.round(48 * only.getResources().getDisplayMetrics().density));
        return only;
    }

    private static String assertSecureReport(AlertDialog dialog, TaskActionReceipts shown,
                                             TaskUsageTestHarness task) {
        assertNotNull(dialog); assertNotNull(shown); assertTrue(dialog.isShowing());
        assertNotNull(dialog.getWindow());
        assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        TextView report = report(dialog); assertFalse(report.isSaveEnabled());
        String text = report.getText().toString();
        assertTrue(text.contains("系统接受不等于目标完成"));
        assertTrue(text.contains("这是打开时的快照，不自动更新"));
        assertFalse("Internal failure codes are not product instructions", text.contains("USER_DENIED"));
        if (shown.entries.isEmpty()) assertTrue(text.contains("本次任务尚无操作回执"));
        for (TaskActionReceipts.Entry entry : shown.entries) {
            if (entry.verification == TaskActionReceipts.Verification.UNVERIFIED)
                assertTrue(text.contains("未验证"));
            if (entry.stage == TaskActionReceipts.Stage.NO_FINAL_RESULT)
                assertTrue(text.contains("最终回执未返回，提交情况需核对"));
            if (entry.stage == TaskActionReceipts.Stage.RETURNED && "USER_DENIED".equals(entry.code)
                    && entry.submitted == TaskActionReceipts.Fact.NO && entry.accepted == TaskActionReceipts.Fact.NO)
                assertTrue(text.contains("未提交：你已拒绝本次操作。"));
        }
        for (String privateValue : new String[]{shown.taskId, DeviceHarness.FIXTURE,
                LocalControllerModelServer.TEXT, task.settings.model.apiKey,
                task.settings.model.endpoint, task.settings.model.model,
                "Measure this synthetic task's actual local usage", "script-round-", "nodeId", "expectedRevision"}) {
            assertFalse("Receipt preview contains private task or request data", text.contains(privateValue));
        }
        closeButton(dialog);
        return text;
    }
}
