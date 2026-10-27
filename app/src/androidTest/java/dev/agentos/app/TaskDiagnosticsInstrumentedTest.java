package dev.agentos.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.TaskDiagnostics;
import dev.agentos.core.TaskResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.agentos.app.TaskUsageTestHarness.*;
import static org.junit.Assert.*;

/** Actual protected application dialogs and explicit clipboard buttons; no secure-window exception. */
@RunWith(AndroidJUnit4.class)
public final class TaskDiagnosticsInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_task_diagnostic_preview_capture_not_attempted");

    @Test public void activePreviewIsFrozenRedactedExplicitlyCopiedAndCanStopItsOriginalTask() throws Exception {
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.REJECT_CLICK, TaskBudget.DEFAULT)) {
            task.start(); task.awaitApproval();
            task.onMain(task.controller::pauseResume);
            task.awaitPausedWorker();
            Activity activity = task.openMain();
            awaitFocus(task, activity.getWindow());
            RunControl owner = task.owner();
            long epoch = owner.getExecutionEpoch();
            long dispatches = owner.getDispatchAttempts();
            int requests = task.server.requestCount();
            AtomicInteger publications = new AtomicInteger();
            AgentController.Listener listener = publications::incrementAndGet;
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            assertNotNull(clipboard);
            AtomicReference<ClipData> previous = new AtomicReference<>();
            AtomicReference<AlertDialog> dialog = new AtomicReference<>();
            AtomicReference<TaskDiagnostics> shown = new AtomicReference<>();
            AtomicReference<Button> copy = new AtomicReference<>();
            task.onMain(() -> {
                previous.set(clipboard.getPrimaryClip());
                clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "explicit-copy-required"));
                task.controller.addListener(listener);
            });
            try {
                task.onMain(() -> {
                    assertTrue(activity.findViewById(R.id.task_diagnostics).performClick());
                    dialog.set(dialog(activity));
                    shown.set(snapshot(activity));
                    assertNotNull(dialog.get());
                    assertTrue(dialog.get().isShowing());
                    assertSecure(dialog.get());
                    TextView report = dialog.get().findViewById(R.id.task_diagnostic_report);
                    assertFalse(report.isSaveEnabled());
                    assertEquals(shown.get().text, report.getText().toString());
                    assertEquals(TaskDiagnostics.Phase.PAUSED, shown.get().phase);
                    assertEquals(owner.getTaskId(), shown.get().taskId);
                    copy.set(dialog.get().findViewById(R.id.copy_task_diagnostic_report));
                    assertTrue(copy.get().getMinHeight() >= dp(activity, 48));
                    assertTrue(dialog.get().getButton(AlertDialog.BUTTON_NEUTRAL).getMinHeight() >= dp(activity, 48));
                    assertRedacted(shown.get(), task);
                });
                awaitFocus(task, dialog.get().getWindow());
                task.onMain(() -> assertEquals("explicit-copy-required", clipText(clipboard)));
                waitRealMillis(1_200);
                task.onMain(() -> {
                    assertSame(dialog.get(), dialog(activity));
                    assertSame(shown.get(), snapshot(activity));
                    assertEquals(shown.get().text,
                            ((TextView) dialog.get().findViewById(R.id.task_diagnostic_report)).getText().toString());
                    assertTrue(task.controller.getTaskUsage().consumed.pausedMillis > shown.get().consumed.pausedMillis);
                    assertTrue(copy.get().performClick());
                    assertEquals("The copied report must be exactly the displayed fixed snapshot", shown.get().text, clipText(clipboard));
                });
                assertEquals(0, publications.get());
                assertEquals(epoch, owner.getExecutionEpoch());
                assertEquals(dispatches, owner.getDispatchAttempts());
                assertEquals(requests, task.server.requestCount());
                assertFalse(owner.isCancelled());
                screenshots.capture("protected-task-diagnostic-preview-and-explicit-copy");
                task.onMain(() -> {
                    assertTrue(dialog.get().findViewById(R.id.stop_task_from_diagnostics).performClick());
                    assertNull(dialog(activity));
                    assertNull(snapshot(activity));
                    assertTrue(owner.isCancelled());
                    assertFalse(task.controller.getState().running);
                    clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "stale-copy-must-not-run"));
                    assertTrue(copy.get().performClick());
                });
                awaitFocus(task, activity.getWindow());
                task.onMain(() -> assertEquals("stale-copy-must-not-run", clipText(clipboard)));
                task.awaitStopped();
                assertEquals(TaskResult.Kind.STOPPED, task.controller.getState().resultKind);
                assertEquals("TASK_STOPPED", task.controller.getTaskDiagnostics().resultCode);
            } finally {
                task.controller.removeListener(listener);
                task.onMain(() -> restoreClipboard(clipboard, previous.get()));
            }
        }
    }

    @Test public void completedCodeSurvivesButClearReplacementAndLeavingInvalidateOldDialogActions() throws Exception {
        try (TaskUsageTestHarness task = new TaskUsageTestHarness(
                LocalControllerModelServer.Scenario.EXHAUST_ROUND_BUDGET, new TaskBudget(1, 100, 600_000))) {
            task.start(); task.awaitStopped();
            Activity activity = task.openMain();
            awaitFocus(task, activity.getWindow());
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            assertNotNull(clipboard);
            AtomicReference<ClipData> previous = new AtomicReference<>();
            AtomicReference<Button> staleCopy = new AtomicReference<>();
            AtomicReference<TaskDiagnostics> ended = new AtomicReference<>();
            task.onMain(() -> {
                previous.set(clipboard.getPrimaryClip());
                clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "unchanged-unless-explicit-current-copy"));
            });
            try {
                task.onMain(() -> {
                    assertEquals(TaskResult.Kind.BUDGET_EXCEEDED, task.controller.getState().resultKind);
                    assertEquals("BUDGET_EXCEEDED", task.controller.getState().resultCode);
                    assertTrue(activity.findViewById(R.id.task_diagnostics).performClick());
                    ended.set(snapshot(activity));
                    assertEquals(TaskDiagnostics.Phase.FINISHED, ended.get().phase);
                    assertEquals("BUDGET_EXCEEDED", ended.get().resultCode);
                    staleCopy.set(dialog(activity).findViewById(R.id.copy_task_diagnostic_report));
                });
                awaitFocus(task, dialog(activity).getWindow());
                waitRealMillis(1_100);
                task.onMain(() -> {
                    assertEquals(ended.get().text, task.controller.getTaskDiagnostics().text);
                    assertTrue(staleCopy.get().performClick());
                    assertEquals(ended.get().text, clipText(clipboard));
                    clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "old-actions-must-not-change-this"));
                });
                try (WorkerHold hold = new WorkerHold(task)) {
                    try {
                        task.start();
                        awaitFocus(task, activity.getWindow());
                        RunControl replacement = task.owner();
                        long epoch = replacement.getExecutionEpoch();
                        task.onMain(() -> {
                            assertFalse(task.controller.stopIfCurrentTask(ended.get().taskId));
                            assertFalse(task.controller.stopIfCurrentTask(null));
                            assertFalse(replacement.isCancelled());
                            assertEquals(epoch, replacement.getExecutionEpoch());
                            assertNotEquals(ended.get().taskId, replacement.getTaskId());
                            assertNull(task.controller.getState().resultKind);
                            assertEquals("", task.controller.getState().resultCode);
                            assertNull(dialog(activity)); assertNull(snapshot(activity));
                            assertTrue(staleCopy.get().performClick());
                            assertEquals("old-actions-must-not-change-this", clipText(clipboard));
                            assertTrue(activity.findViewById(R.id.task_diagnostics).performClick());
                            assertEquals(replacement.getTaskId(), snapshot(activity).taskId);
                            staleCopy.set(dialog(activity).findViewById(R.id.copy_task_diagnostic_report));
                            assertSecure(dialog(activity));
                            assertTrue(activity.moveTaskToBack(true));
                        });
                        await("Leaving MainActivity did not invalidate its task report", () -> {
                            AtomicReference<Boolean> closed = new AtomicReference<>(false);
                            task.onMain(() -> closed.set(!readBoolean(activity, "resumed")
                                    && dialog(activity) == null && snapshot(activity) == null));
                            return closed.get();
                        });
                        task.onMain(() -> assertTrue(staleCopy.get().performClick()));
                        task.finishActivity();
                        Activity returned = task.openMain();
                        awaitFocus(task, returned.getWindow());
                        task.onMain(() -> {
                            assertNull(dialog(returned)); assertNull(snapshot(returned));
                            assertEquals("old-actions-must-not-change-this", clipText(clipboard));
                            assertFalse(replacement.isCancelled());
                        });
                        screenshots.capture("protected-report-invalidated-on-task-replacement-and-leave");
                    } finally { task.stop(); }
                }
                task.awaitStopped();
                Activity returned = task.activity;
                task.onMain(() -> {
                    assertTrue(returned.findViewById(R.id.task_diagnostics).performClick());
                    ended.set(snapshot(returned));
                    assertEquals(TaskDiagnostics.Phase.FINISHED, ended.get().phase);
                    assertEquals("TASK_STOPPED", ended.get().resultCode);
                    staleCopy.set(dialog(returned).findViewById(R.id.copy_task_diagnostic_report));
                    // Invoke the actual attached clear button; it uses the ordinary Controller path.
                    assertTrue(returned.findViewById(R.id.clear_task).performClick());
                    assertNull(dialog(returned)); assertNull(snapshot(returned));
                    assertNull(task.controller.getTaskDiagnostics());
                    assertNull(task.controller.getState().resultKind);
                    assertEquals("", task.controller.getState().resultCode);
                    assertFalse(task.controller.isTaskDiagnosticsCurrent(ended.get().taskId));
                    assertTrue(staleCopy.get().performClick());
                });
                awaitFocus(task, returned.getWindow());
                task.onMain(() -> assertEquals("old-actions-must-not-change-this", clipText(clipboard)));
            } finally { task.onMain(() -> restoreClipboard(clipboard, previous.get())); }
        }
    }

    private static void awaitFocus(TaskUsageTestHarness task, Window window) {
        assertNotNull(window);
        await("The actual task-report window did not gain clipboard access focus", () -> {
            AtomicReference<Boolean> focused = new AtomicReference<>(false);
            task.onMain(() -> focused.set(window.getDecorView().hasWindowFocus()));
            return focused.get();
        });
    }
    private static AlertDialog dialog(Activity activity) {
        try { return (AlertDialog) readField(activity, "taskDiagnosticsDialog"); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static TaskDiagnostics snapshot(Activity activity) {
        try { return (TaskDiagnostics) readField(activity, "displayedTaskDiagnostics"); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void assertSecure(AlertDialog dialog) {
        assertNotNull(dialog.getWindow());
        assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
    private static void assertRedacted(TaskDiagnostics report, TaskUsageTestHarness task) {
        assertFalse(report.text.contains(task.settings.model.endpoint));
        assertFalse(report.text.contains(task.settings.model.apiKey));
        assertFalse(report.text.contains(task.settings.model.model));
        assertFalse(report.text.contains(DeviceHarness.FIXTURE));
        assertFalse(report.text.contains("Measure this synthetic task"));
        assertFalse(report.text.contains(report.taskId));
        assertTrue(report.text.length() <= TaskDiagnostics.MAX_REPORT_CHARS);
    }
    private static String clipText(ClipboardManager clipboard) {
        ClipData clip = clipboard.getPrimaryClip();
        assertNotNull(clip); assertEquals(1, clip.getItemCount());
        assertNotNull(clip.getItemAt(0).getText());
        return clip.getItemAt(0).getText().toString();
    }
    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    private static void restoreClipboard(ClipboardManager clipboard, ClipData previous) {
        if (previous == null) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(previous);
    }
}
