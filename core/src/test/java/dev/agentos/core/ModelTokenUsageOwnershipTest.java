package dev.agentos.core;

import org.junit.Test;

import static org.junit.Assert.*;

/** Request-ticket ownership, independently of HTTP and the parser's field boundary cases. */
public class ModelTokenUsageOwnershipTest {
    private static ModelTokenUsage.Report report() {
        return ModelTokenUsage.parseReport("{\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}");
    }

    @Test public void ticketsSettleOnceAndSnapshotsCannotFollowLaterReports() {
        RunControl owner = new RunControl();
        try {
            RunControl.ModelUsageTicket first = owner.beginModelUsage(owner.getExecutionEpoch());
            assertNotNull(first);
            ModelTokenUsage pending = owner.modelTokenUsageSnapshot();
            assertEquals(1, pending.calls);
            assertNull(pending.total.subtotal);
            first.accept(report()); first.accept(report()); first.close(); first.accept(report());
            ModelTokenUsage complete = owner.modelTokenUsageSnapshot();
            assertEquals(ModelTokenUsage.Completeness.COMPLETE, complete.completeness);
            assertEquals(Long.valueOf(10), complete.total.subtotal);
            assertEquals(1, complete.total.reportedCalls);
            RunControl.ModelUsageTicket second = owner.beginModelUsage(owner.getExecutionEpoch());
            second.close(); second.accept(report());
            ModelTokenUsage partial = owner.modelTokenUsageSnapshot();
            assertEquals(2, partial.calls);
            assertEquals(1, partial.total.reportedCalls);
            assertEquals(ModelTokenUsage.Completeness.PARTIAL, partial.completeness);
            assertEquals(1, complete.calls);
            assertEquals(ModelTokenUsage.Completeness.COMPLETE, complete.completeness);
            assertNull(pending.total.subtotal);
        } finally { owner.cancel(); }
    }

    @Test public void pauseResumeAndStopCannotAdoptOldRepliesOrContaminateAnotherTask() {
        RunControl owner = new RunControl(), replacement = new RunControl();
        try {
            long originalEpoch = owner.getExecutionEpoch();
            RunControl.ModelUsageTicket known = owner.beginModelUsage(originalEpoch);
            known.accept(report());
            RunControl.ModelUsageTicket old = owner.beginModelUsage(originalEpoch);
            owner.pause();
            assertNull(owner.beginModelUsage(owner.getExecutionEpoch()));
            owner.resume();
            old.accept(report()); old.close();
            assertNull(owner.beginModelUsage(originalEpoch));
            assertEquals(2, owner.modelTokenUsageSnapshot().calls);
            assertEquals(1, owner.modelTokenUsageSnapshot().total.reportedCalls);
            RunControl.ModelUsageTicket resumed = owner.beginModelUsage(owner.getExecutionEpoch());
            resumed.accept(report());
            assertEquals(Long.valueOf(20), owner.modelTokenUsageSnapshot().total.subtotal);
            RunControl.ModelUsageTicket pendingAtStop = owner.beginModelUsage(owner.getExecutionEpoch());
            owner.cancel();
            ModelTokenUsage stopped = owner.modelTokenUsageSnapshot();
            pendingAtStop.accept(report());
            assertNull(owner.beginModelUsage(owner.getExecutionEpoch()));
            RunControl.ModelUsageTicket newTask = replacement.beginModelUsage(replacement.getExecutionEpoch());
            newTask.accept(report());
            assertEquals(4, stopped.calls);
            assertEquals(2, stopped.total.reportedCalls);
            assertEquals(Long.valueOf(20), owner.modelTokenUsageSnapshot().total.subtotal);
            assertEquals(1, replacement.modelTokenUsageSnapshot().calls);
            assertEquals(Long.valueOf(10), replacement.modelTokenUsageSnapshot().total.subtotal);
        } finally { owner.cancel(); replacement.cancel(); }
    }

    @Test public void gatewayCallsAndOptionalStatisticsDoNotConsumeOrResetTaskBudgets() throws Exception {
        RunControl owner = new RunControl();
        try {
            owner.consumeModelRound(); owner.consumeModelRound();
            assertEquals(0, owner.modelTokenUsageSnapshot().calls);
            RunControl.ModelUsageTicket ticket = owner.beginModelUsage(owner.getExecutionEpoch());
            ticket.accept(report());
            ticket.close();
            assertEquals(2, owner.budgetSnapshot().modelRounds);
            assertEquals(0, owner.budgetSnapshot().toolCalls);
            assertEquals(0, owner.getDispatchAttempts());
            assertEquals(1, owner.modelTokenUsageSnapshot().calls);
        } finally { owner.cancel(); }
    }
}
