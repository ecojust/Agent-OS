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

/** Conditional cancellation cannot let a delayed callback acquire the resumed task's epoch. */
public final class ExecutionEpochCancellationTest {
    @Test public void matchingEpochCancelsOnceAndPreservesAccountedTaskBudget() throws Exception {
        AtomicLong clock = new AtomicLong();
        RunControl control = new RunControl("personal-test", new TaskBudget(10, 20, 1000), clock::get);
        String taskId = control.getTaskId(); long epoch = control.getExecutionEpoch();
        control.consumeModelRound(); control.consumeToolCall();
        clock.set(TimeUnit.MILLISECONDS.toNanos(100)); control.beginApprovalWait();
        clock.set(TimeUnit.MILLISECONDS.toNanos(150));
        AtomicInteger hooks = new AtomicInteger(); control.setCancelHook(hooks::incrementAndGet);
        assertTrue(control.cancelIfExecutionEpoch(epoch));
        assertTrue(control.isCancelled()); assertFalse(control.isPaused());
        assertEquals(epoch + 1, control.getExecutionEpoch());
        assertFalse(control.cancelIfExecutionEpoch(epoch));
        assertFalse(control.cancelIfExecutionEpoch(control.getExecutionEpoch()));
        control.cancel(); control.resume(); control.endApprovalWait();
        clock.set(TimeUnit.SECONDS.toNanos(5));
        RunControl.BudgetSnapshot snapshot = control.budgetSnapshot();
        assertEquals(1, hooks.get()); assertEquals(1, snapshot.modelRounds); assertEquals(1, snapshot.toolCalls);
        assertEquals(100, snapshot.activeMillis); assertEquals(50, snapshot.approvalMillis);
        assertEquals(0, snapshot.pausedMillis); assertEquals(taskId, control.getTaskId());
        assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                () -> control.checkExecutionEpoch(epoch)).getCode());
    }

    @Test public void staleEpochAfterPauseResumeDoesNotAccountOrRemoveTheNewHook() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger clockReads = new AtomicInteger();
        RunControl control = new RunControl("personal-test", new TaskBudget(10, 20, 1000), () -> {
            clockReads.incrementAndGet(); return clock.get();
        });
        long original = control.getExecutionEpoch(); AtomicInteger oldHook = new AtomicInteger(), freshHook = new AtomicInteger();
        control.setCancelHook(oldHook::incrementAndGet);
        clock.set(TimeUnit.MILLISECONDS.toNanos(10)); control.pause();
        long paused = control.getExecutionEpoch();
        clock.set(TimeUnit.MILLISECONDS.toNanos(110)); control.resume();
        long resumed = control.getExecutionEpoch(); control.setCancelHook(freshHook::incrementAndGet);
        int reads = clockReads.get();
        assertFalse(control.cancelIfExecutionEpoch(original));
        assertFalse(control.cancelIfExecutionEpoch(paused));
        assertFalse(control.cancelIfExecutionEpoch(resumed + 1));
        assertEquals("a failed comparison must not even account time", reads, clockReads.get());
        assertEquals(resumed, control.getExecutionEpoch()); assertFalse(control.isCancelled()); assertFalse(control.isPaused());
        assertEquals(1, oldHook.get()); assertEquals(0, freshHook.get());
        control.checkExecutionEpoch(resumed);
        assertTrue(control.cancelIfExecutionEpoch(resumed)); assertEquals(1, freshHook.get());
    }

    @Test(timeout = 5000) public void detachedHookRunsOutsideMonitorAndOnlyOneConcurrentCallerWins() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger hooks = new AtomicInteger(); CyclicBarrier start = new CyclicBarrier(3);
        CountDownLatch hookEntered = new CountDownLatch(1), releaseHook = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        control.setCancelHook(() -> {
            assertFalse("hook must not inherit RunControl's monitor", Thread.holdsLock(control));
            assertTrue(control.isCancelled()); assertEquals(epoch + 1, control.getExecutionEpoch());
            hooks.incrementAndGet(); hookEntered.countDown();
            try { assertTrue(releaseHook.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
        });
        try {
            Future<Boolean> first = workers.submit(() -> { start.await(1, TimeUnit.SECONDS); return control.cancelIfExecutionEpoch(epoch); });
            Future<Boolean> second = workers.submit(() -> { start.await(1, TimeUnit.SECONDS); return control.cancelIfExecutionEpoch(epoch); });
            start.await(1, TimeUnit.SECONDS); assertTrue(hookEntered.await(1, TimeUnit.SECONDS));
            Future<Long> readWhileHookRuns = workers.submit(control::getExecutionEpoch);
            assertEquals(epoch + 1, (long) readWhileHookRuns.get(1, TimeUnit.SECONDS));
            releaseHook.countDown();
            assertNotEquals(first.get(1, TimeUnit.SECONDS), second.get(1, TimeUnit.SECONDS));
            assertEquals(1, hooks.get());
        } finally {
            releaseHook.countDown(); control.cancel(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 3000) public void cancellationOfTheCurrentPausedEpochWakesItsWaiter() throws Exception {
        RunControl control = new RunControl(); control.pause(); long pausedEpoch = control.getExecutionEpoch();
        ExecutorService worker = Executors.newSingleThreadExecutor(); CountDownLatch waiting = new CountDownLatch(1);
        try {
            Future<String> waiter = worker.submit(() -> {
                synchronized (control) {
                    // Cancellation cannot acquire the monitor between this signal
                    // and awaitIfPaused releasing it in Object.wait().
                    waiting.countDown();
                    try { control.awaitIfPaused(); return "unexpected-resume"; }
                    catch (TaskControlException stopped) { return stopped.getCode(); }
                }
            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            assertTrue(control.cancelIfExecutionEpoch(pausedEpoch));
            assertEquals("TASK_STOPPED", waiter.get(1, TimeUnit.SECONDS));
            assertFalse(control.isPaused()); assertTrue(control.isCancelled());
        } finally { control.cancel(); worker.shutdownNow(); assertTrue(worker.awaitTermination(1, TimeUnit.SECONDS)); }
    }

    @Test public void cancelKeepsAdmittedDispatchOwnedUntilItsWorkerClosesIt() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        RunControl.DispatchTicket ticket = control.beginDispatch(epoch);
        try {
            assertTrue(control.cancelIfExecutionEpoch(epoch));
            assertTrue(control.hasInFlightDispatch()); assertFalse(ticket.isSettled());
            assertEquals(1, control.getDispatchAttempts());
            assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class, ticket::checkExecutionEpoch).getCode());
            assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                    () -> control.beginDispatch(control.getExecutionEpoch())).getCode());
            assertFalse(control.cancelIfExecutionEpoch(epoch)); assertTrue(control.hasInFlightDispatch());
        } finally { ticket.close(); }
        ticket.close();
        assertTrue(ticket.isSettled()); assertFalse(control.hasInFlightDispatch());
        assertEquals(1, control.getDispatchAttempts()); assertEquals(epoch + 1, control.getExecutionEpoch());
    }

    @Test public void throwingHookDoesNotRepeatOrUndoTheTerminalState() {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch(); AtomicInteger hooks = new AtomicInteger();
        control.setCancelHook(() -> { hooks.incrementAndGet(); throw new IllegalStateException("synthetic hook failure"); });
        assertTrue(control.cancelIfExecutionEpoch(epoch));
        assertFalse(control.cancelIfExecutionEpoch(epoch)); control.cancel();
        assertTrue(control.isCancelled()); assertEquals(epoch + 1, control.getExecutionEpoch()); assertEquals(1, hooks.get());
    }
}
