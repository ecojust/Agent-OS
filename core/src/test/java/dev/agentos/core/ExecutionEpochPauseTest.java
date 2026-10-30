package dev.agentos.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

import static org.junit.Assert.*;

/** An old refusal cannot pause a resumed task or detach its new cancellation hook. */
public final class ExecutionEpochPauseTest {
    @Test public void matchingEpochPausesOnceWithoutLosingTaskIdentityOrBudget() throws Exception {
        AtomicLong clock = new AtomicLong();
        RunControl control = new RunControl("personal-test", new TaskBudget(10, 20, 1000), clock::get);
        String taskId = control.getTaskId(); long epoch = control.getExecutionEpoch();
        control.consumeModelRound(); control.consumeToolCall();
        AtomicInteger hooks = new AtomicInteger(); control.setCancelHook(hooks::incrementAndGet);
        clock.set(TimeUnit.MILLISECONDS.toNanos(100)); control.beginApprovalWait();
        clock.set(TimeUnit.MILLISECONDS.toNanos(150));
        assertTrue(control.pauseIfExecutionEpoch(epoch));
        assertTrue(control.isPaused()); assertFalse(control.isCancelled());
        assertEquals(epoch + 1, control.getExecutionEpoch());
        assertFalse(control.pauseIfExecutionEpoch(epoch));
        assertFalse(control.pauseIfExecutionEpoch(control.getExecutionEpoch()));
        assertEquals(1, hooks.get());
        assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class,
                () -> control.checkExecutionEpoch(epoch)).getCode());
        control.endApprovalWait();
        clock.set(TimeUnit.MILLISECONDS.toNanos(250)); control.resume();
        clock.set(TimeUnit.MILLISECONDS.toNanos(300));
        RunControl.BudgetSnapshot snapshot = control.budgetSnapshot();
        assertEquals(taskId, control.getTaskId()); assertEquals("personal-test", control.getProfileId());
        assertEquals(epoch + 2, control.getExecutionEpoch());
        assertEquals(1, snapshot.modelRounds); assertEquals(1, snapshot.toolCalls);
        assertEquals(150, snapshot.activeMillis); assertEquals(50, snapshot.approvalMillis);
        assertEquals(100, snapshot.pausedMillis);
    }

    @Test public void staleEpochAfterResumeDoesNotAccountTimeOrRemoveTheNewHook() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger clockReads = new AtomicInteger();
        RunControl control = new RunControl("personal-test", new TaskBudget(10, 20, 1000), () -> {
            clockReads.incrementAndGet(); return clock.get();
        });
        long original = control.getExecutionEpoch();
        AtomicInteger oldHook = new AtomicInteger(), freshHook = new AtomicInteger();
        control.setCancelHook(oldHook::incrementAndGet);
        clock.set(TimeUnit.MILLISECONDS.toNanos(10)); control.pause();
        long paused = control.getExecutionEpoch();
        clock.set(TimeUnit.MILLISECONDS.toNanos(110)); control.resume();
        long resumed = control.getExecutionEpoch(); control.setCancelHook(freshHook::incrementAndGet);
        int reads = clockReads.get();
        assertFalse(control.pauseIfExecutionEpoch(original));
        assertFalse(control.pauseIfExecutionEpoch(paused));
        assertFalse(control.pauseIfExecutionEpoch(resumed + 1));
        assertEquals(reads, clockReads.get());
        assertFalse(control.isPaused()); assertFalse(control.isCancelled());
        assertEquals(resumed, control.getExecutionEpoch());
        assertEquals(1, oldHook.get()); assertEquals(0, freshHook.get());
        control.checkExecutionEpoch(resumed);
        assertTrue(control.pauseIfExecutionEpoch(resumed)); assertEquals(1, freshHook.get());
    }

    @Test(timeout = 5000) public void onlyOneConcurrentRefusalPausesAndItsHookRunsOutsideTheMonitor() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger hooks = new AtomicInteger(); CyclicBarrier start = new CyclicBarrier(3);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        control.setCancelHook(() -> {
            assertFalse(Thread.holdsLock(control));
            assertTrue(control.isPaused()); assertEquals(epoch + 1, control.getExecutionEpoch());
            hooks.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
        });
        try {
            Future<Boolean> first = workers.submit(() -> { start.await(1, TimeUnit.SECONDS); return control.pauseIfExecutionEpoch(epoch); });
            Future<Boolean> second = workers.submit(() -> { start.await(1, TimeUnit.SECONDS); return control.pauseIfExecutionEpoch(epoch); });
            start.await(1, TimeUnit.SECONDS); assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<Long> read = workers.submit(control::getExecutionEpoch);
            assertEquals(epoch + 1, (long) read.get(1, TimeUnit.SECONDS));
            release.countDown();
            assertNotEquals(first.get(1, TimeUnit.SECONDS), second.get(1, TimeUnit.SECONDS));
            assertEquals(1, hooks.get());
        } finally {
            release.countDown(); control.cancel(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void pausedAndCancelledControlsRejectWithoutChangingTheirState() {
        AtomicInteger reads = new AtomicInteger(); AtomicLong clock = new AtomicLong();
        RunControl control = new RunControl("personal-test", TaskBudget.DEFAULT, () -> {
            reads.incrementAndGet(); return clock.get();
        });
        control.pause(); long paused = control.getExecutionEpoch(); int before = reads.get();
        assertFalse(control.pauseIfExecutionEpoch(paused));
        assertEquals(before, reads.get()); assertTrue(control.isPaused());
        assertEquals(paused, control.getExecutionEpoch());
        control.cancel(); long cancelled = control.getExecutionEpoch(); before = reads.get();
        assertFalse(control.pauseIfExecutionEpoch(cancelled));
        assertFalse(control.pauseIfExecutionEpoch(paused));
        assertEquals(before, reads.get()); assertEquals(cancelled, control.getExecutionEpoch());
        assertTrue(control.isCancelled()); assertFalse(control.isPaused());
        control.resume(); assertTrue(control.isCancelled());
    }

    @Test public void admittedDispatchRemainsOwnedAcrossConditionalPauseAndResume() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        RunControl.DispatchTicket ticket = control.beginDispatch(epoch);
        try {
            assertTrue(control.pauseIfExecutionEpoch(epoch));
            assertTrue(control.hasInFlightDispatch()); assertFalse(ticket.isSettled());
            assertEquals(1, control.getDispatchAttempts());
            assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class, ticket::checkExecutionEpoch).getCode());
            control.resume(); long resumed = control.getExecutionEpoch();
            assertFalse(control.pauseIfExecutionEpoch(epoch));
            assertEquals("DISPATCH_IN_PROGRESS", assertThrows(TaskControlException.class,
                    () -> control.beginDispatch(resumed)).getCode());
            assertTrue(control.hasInFlightDispatch()); assertFalse(control.isPaused());
        } finally { ticket.close(); }
        assertFalse(control.hasInFlightDispatch()); assertTrue(ticket.isSettled());
        try (RunControl.DispatchTicket fresh = control.beginDispatch(control.getExecutionEpoch())) {
            fresh.checkExecutionEpoch(); assertEquals(2, control.getDispatchAttempts());
        }
    }

    @Test public void throwingHookDoesNotUndoOrRepeatThePause() {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger hooks = new AtomicInteger();
        control.setCancelHook(() -> { hooks.incrementAndGet(); throw new IllegalStateException("synthetic hook failure"); });
        assertTrue(control.pauseIfExecutionEpoch(epoch));
        assertFalse(control.pauseIfExecutionEpoch(epoch)); control.pause();
        assertTrue(control.isPaused()); assertFalse(control.isCancelled());
        assertEquals(epoch + 1, control.getExecutionEpoch()); assertEquals(1, hooks.get());
    }
}
