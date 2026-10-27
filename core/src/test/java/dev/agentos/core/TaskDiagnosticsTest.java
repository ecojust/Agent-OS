package dev.agentos.core;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class TaskDiagnosticsTest {
    @Test public void snapshotUsesOriginalTaskAndFreezesValuesWithoutRetainingMutableOwner() throws Exception {
        AtomicLong clock = new AtomicLong();
        RunControl owner = new RunControl("private-profile-must-not-be-reported", TaskBudget.DEFAULT, clock::get);
        owner.consumeModelRound(); owner.consumeToolCall();
        clock.set(15_000_000L);
        long epoch = owner.getExecutionEpoch();
        TaskDiagnostics report = new TaskDiagnostics(owner.getTaskId(), TaskDiagnostics.Phase.RUNNING,
                null, null, TaskBudget.DEFAULT, owner.budgetSnapshot());
        assertEquals(owner.getTaskId(), report.taskId);
        assertEquals(15, report.consumed.activeMillis);
        assertEquals(1, report.consumed.modelRounds);
        assertEquals(1, report.consumed.toolCalls);
        assertEquals(epoch, owner.getExecutionEpoch());
        assertFalse(owner.isCancelled());
        assertFalse(owner.isPaused());
        String shown = report.text;
        owner.consumeModelRound(); owner.pause(); clock.set(95_000_000L);
        assertEquals(80, owner.budgetSnapshot().pausedMillis);
        assertEquals(shown, report.text);
        assertEquals(15, report.consumed.activeMillis);
        assertEquals(0, report.consumed.pausedMillis);
        assertEquals(1, report.consumed.modelRounds);
        assertFalse(shown.contains(owner.getTaskId()));
        assertFalse(shown.contains(owner.getProfileId()));
    }

    @Test public void terminalKindAndKnownErrorCodeRemainWithoutSummaryOrArbitraryProviderText() {
        RunControl owner = new RunControl();
        TaskResult failure = new TaskResult(TaskResult.Kind.FAILED,
                "private task, model reply and https://secret.invalid must not enter report", "MODEL_AUTH");
        TaskDiagnostics report = new TaskDiagnostics(owner.getTaskId(), TaskDiagnostics.Phase.FINISHED,
                failure.kind, failure.code, TaskBudget.DEFAULT, owner.budgetSnapshot());
        assertEquals(TaskResult.Kind.FAILED, report.resultKind);
        assertEquals("MODEL_AUTH", report.resultCode);
        assertTrue(report.text.contains("结束种类: FAILED"));
        assertTrue(report.text.contains("结束代码: MODEL_AUTH"));
        assertFalse(report.text.contains(failure.summary));
        for (String unsafe : new String[]{"PRIVATE_API_KEY_ABC", "https://private.invalid/path", "MODEL_AUTH\nsecret", "secret".repeat(10_000)}) {
            TaskDiagnostics redacted = new TaskDiagnostics(owner.getTaskId(), TaskDiagnostics.Phase.FINISHED,
                    TaskResult.Kind.FAILED, unsafe, TaskBudget.DEFAULT, owner.budgetSnapshot());
            assertEquals("OTHER", redacted.resultCode);
            assertFalse(redacted.text.contains(unsafe));
            assertTrue(redacted.text.length() <= TaskDiagnostics.MAX_REPORT_CHARS);
        }
        assertEquals("NOT_RECORDED", TaskDiagnostics.safeTerminalCode(null));
        assertEquals("NOT_RECORDED", TaskDiagnostics.safeTerminalCode(""));
    }

    @Test public void activeSnapshotCannotCarryPriorTasksTerminalMetadata() {
        RunControl owner = new RunControl();
        for (TaskDiagnostics.Phase phase : TaskDiagnostics.Phase.values()) {
            if (phase == TaskDiagnostics.Phase.FINISHED) continue;
            TaskDiagnostics report = new TaskDiagnostics(owner.getTaskId(), phase,
                    TaskResult.Kind.BUDGET_EXCEEDED, "BUDGET_EXCEEDED", TaskBudget.DEFAULT, owner.budgetSnapshot());
            assertNull(report.resultKind);
            assertEquals("NONE", report.resultCode);
            assertFalse(report.text.contains("BUDGET_EXCEEDED"));
        }
    }

    @Test public void boundedReportPreservesUsageAboveBudgetInsteadOfHidingIt() {
        RunControl owner = new RunControl();
        RunControl.BudgetSnapshot usage = new RunControl.BudgetSnapshot(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        TaskDiagnostics report = new TaskDiagnostics(owner.getTaskId(), TaskDiagnostics.Phase.FINISHED,
                TaskResult.Kind.BUDGET_EXCEEDED, "BUDGET_EXCEEDED", TaskBudget.DEFAULT, usage);
        assertSame(usage, report.consumed);
        assertTrue(report.text.contains(Long.toString(Long.MAX_VALUE)));
        assertTrue(report.text.contains(Integer.toString(Integer.MAX_VALUE)));
        assertTrue(report.text.length() <= TaskDiagnostics.MAX_REPORT_CHARS);
    }

    @Test public void malformedTaskIdentityFailsWithoutEchoingTheInput() {
        RunControl owner = new RunControl();
        for (String unsafe : new String[]{"private-task-text", "https://secret.invalid", "1-1-1-1-1", owner.getTaskId().toUpperCase()}) {
            if (unsafe.equals(owner.getTaskId())) continue;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new TaskDiagnostics(unsafe, TaskDiagnostics.Phase.RUNNING,
                            null, null, TaskBudget.DEFAULT, owner.budgetSnapshot()));
            assertEquals("Diagnostics require an original random task identity", failure.getMessage());
            assertNull(failure.getCause());
        }
    }
}
