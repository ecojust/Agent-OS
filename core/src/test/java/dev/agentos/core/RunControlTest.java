package dev.agentos.core;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class RunControlTest {
    @Test public void pauseWaitsUntilResumeAndCancellationIsTerminal() throws Exception {
        RunControl control = new RunControl();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            control.pause();
            CountDownLatch entered = new CountDownLatch(1);
            Future<Boolean> waiting = executor.submit(() -> { entered.countDown(); control.awaitIfPaused(); return true; });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(80, TimeUnit.MILLISECONDS));
            control.resume();
            assertTrue(waiting.get(1, TimeUnit.SECONDS));
            control.cancel();
            control.resume();
            assertTrue(control.isCancelled());
            assertThrows(InterruptedException.class, control::checkCancelled);
        } finally { control.cancel(); executor.shutdownNow(); }
    }

    @Test public void cancellationUnblocksPausedWaitAndInvokesActiveHook() throws Exception {
        RunControl control = new RunControl();
        AtomicInteger calls = new AtomicInteger();
        control.setCancelHook(calls::incrementAndGet);
        control.pause();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting = executor.submit(() -> {
                try { control.awaitIfPaused(); fail("cancelled wait must not resume normally"); }
                catch (InterruptedException expected) { }
            });
            control.cancel();
            waiting.get(1, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        } finally { control.cancel(); executor.shutdownNow(); }
    }

    @Test public void hookInstalledAfterCancellationRunsImmediatelyAndClearedHookIsUnused() {
        RunControl cancelled = new RunControl();
        cancelled.cancel();
        AtomicInteger immediate = new AtomicInteger();
        cancelled.setCancelHook(immediate::incrementAndGet);
        assertEquals(1, immediate.get());

        RunControl completedRequest = new RunControl();
        AtomicInteger stale = new AtomicInteger();
        completedRequest.setCancelHook(stale::incrementAndGet);
        completedRequest.setCancelHook(null);
        completedRequest.cancel();
        assertEquals(0, stale.get());
    }

    @Test public void pauseInvalidatesPlanAndDisconnectsRequestButPreservesTaskAndBudgets() throws Exception {
        AtomicLong clock=new AtomicLong();
        RunControl control=new RunControl("personal-test",new TaskBudget(2,5,1000),clock::get);
        String taskId=control.getTaskId();long firstEpoch=control.getExecutionEpoch();
        AtomicInteger disconnected=new AtomicInteger();control.setCancelHook(disconnected::incrementAndGet);
        control.consumeModelRound();control.consumeToolCall();
        clock.set(TimeUnit.MILLISECONDS.toNanos(100));control.pause();
        assertEquals(1,disconnected.get());assertTrue(control.getExecutionEpoch()>firstEpoch);
        assertThrows(TaskControlException.class,()->control.checkExecutionEpoch(firstEpoch));
        long pausedEpoch=control.getExecutionEpoch();clock.set(TimeUnit.MILLISECONDS.toNanos(5100));control.resume();
        assertEquals(taskId,control.getTaskId());assertEquals("personal-test",control.getProfileId());
        assertTrue(control.getExecutionEpoch()>pausedEpoch);
        assertThrows(TaskControlException.class,()->control.checkExecutionEpoch(firstEpoch));
        control.checkExecutionEpoch(control.getExecutionEpoch());
        control.consumeModelRound();control.consumeToolCall();clock.set(TimeUnit.MILLISECONDS.toNanos(5200));
        assertEquals(2,control.budgetSnapshot().modelRounds);assertEquals(2,control.budgetSnapshot().toolCalls);
        assertEquals(200,control.budgetSnapshot().activeMillis);assertEquals(5000,control.budgetSnapshot().pausedMillis);
        TaskControlException exhausted=assertThrows(TaskControlException.class,control::consumeModelRound);
        assertEquals("BUDGET_EXCEEDED",exhausted.getCode());
    }

    @Test public void approvalTimeIsSeparateAndActiveBudgetStopsAfterContinuation() throws Exception {
        AtomicLong clock=new AtomicLong();
        RunControl control=new RunControl("personal-test",new TaskBudget(30,100,1000),clock::get);
        clock.set(TimeUnit.MILLISECONDS.toNanos(200));control.beginApprovalWait();
        clock.set(TimeUnit.MILLISECONDS.toNanos(7200));control.endApprovalWait();
        assertEquals(200,control.budgetSnapshot().activeMillis);assertEquals(7000,control.budgetSnapshot().approvalMillis);
        clock.set(TimeUnit.MILLISECONDS.toNanos(8101));
        TaskControlException exhausted=assertThrows(TaskControlException.class,control::checkCancelled);
        assertEquals("BUDGET_EXCEEDED",exhausted.getCode());
    }

    @Test public void toolBudgetStopsRepeatedCallsAndPauseCannotResetIt() throws Exception {
        RunControl control=new RunControl("personal-test",new TaskBudget(30,2,600000));
        control.consumeToolCall();control.pause();control.resume();control.consumeToolCall();
        assertEquals(2,control.budgetSnapshot().toolCalls);
        TaskControlException exhausted=assertThrows(TaskControlException.class,control::consumeToolCall);
        assertEquals("BUDGET_EXCEEDED",exhausted.getCode());
    }

    @Test(timeout=3000) public void pausedTaskExpiresUsingMonotonicRetentionClock() throws Exception {
        AtomicLong clock=new AtomicLong();
        RunControl control=new RunControl("personal-test",new TaskBudget(30,100,600000),clock::get);
        control.pause();clock.set(TimeUnit.MINUTES.toNanos(10)+TimeUnit.MILLISECONDS.toNanos(1));
        assertThrows(TaskControlException.class,control::awaitIfPaused);
    }
}
