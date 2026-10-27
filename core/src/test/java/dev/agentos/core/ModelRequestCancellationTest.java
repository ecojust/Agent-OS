package dev.agentos.core;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ModelRequestCancellationTest {
    @Test public void invalidDisclosureRunsCancellationHookOutsideControlMonitor() throws Exception {
        RunControl control = new RunControl();
        long originalEpoch = control.getExecutionEpoch();
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        AtomicInteger hookCalls = new AtomicInteger();
        AtomicBoolean monitorHeld = new AtomicBoolean();
        AtomicReference<Throwable> observationFailure = new AtomicReference<>();
        AtomicReference<Long> observedEpoch = new AtomicReference<>();
        control.setCancelHook(() -> {
            hookCalls.incrementAndGet();
            monitorHeld.set(Thread.holdsLock(control));
            // A second thread must be able to acquire the monitor while the hook
            // is still executing, not merely after ModelRequestContext returns.
            Future<Long> observation = otherThread.submit(control::getExecutionEpoch);
            try { observedEpoch.set(observation.get(2, TimeUnit.SECONDS)); }
            catch (Exception failure) { observationFailure.set(failure); }
        });
        try {
            ModelRequestContext context = new ModelRequestContext(control, originalEpoch,
                    (owner, epoch) -> false);
            TaskControlException stopped = assertThrows(TaskControlException.class, context::check);
            assertEquals("TASK_STOPPED", stopped.getCode());
            assertTrue(control.isCancelled());
            assertFalse("Cancellation hook ran under the control monitor", monitorHeld.get());
            assertNull("Another thread could not read control while the hook ran", observationFailure.get());
            assertEquals(Long.valueOf(originalEpoch + 1), observedEpoch.get());
            assertEquals(1, hookCalls.get());
            assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class, context::check).getCode());
            assertEquals(1, hookCalls.get());
        } finally {
            control.cancel();
            otherThread.shutdownNow();
            assertTrue(otherThread.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test public void delayedInvalidOrThrowingOldGuardCannotCancelResumedEpoch() throws Exception {
        for (boolean throwing : new boolean[]{false, true}) {
            RunControl control = new RunControl();
            long originalEpoch = control.getExecutionEpoch();
            CountDownLatch guardEntered = new CountDownLatch(1);
            CountDownLatch releaseGuard = new CountDownLatch(1);
            AtomicInteger originalHook = new AtomicInteger();
            AtomicInteger replacementHook = new AtomicInteger();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            control.setCancelHook(originalHook::incrementAndGet);
            try {
                ModelRequestContext context = new ModelRequestContext(control, originalEpoch,
                        (owner, epoch) -> {
                            assertSame(control, owner);
                            assertEquals(originalEpoch, epoch);
                            guardEntered.countDown();
                            await(releaseGuard);
                            if (throwing) throw new IllegalStateException("synthetic guard failure");
                            return false;
                        });
                Future<TaskControlException> result = caller.submit(() ->
                        assertThrows(TaskControlException.class, context::check));
                assertTrue(guardEntered.await(2, TimeUnit.SECONDS));
                control.pause();
                control.resume();
                long resumedEpoch = control.getExecutionEpoch();
                control.setCancelHook(replacementHook::incrementAndGet);
                releaseGuard.countDown();
                assertEquals("TASK_PAUSED", result.get(2, TimeUnit.SECONDS).getCode());
                assertEquals(resumedEpoch, control.getExecutionEpoch());
                assertFalse(control.isPaused());
                assertFalse(control.isCancelled());
                control.checkExecutionEpoch(resumedEpoch);
                assertEquals(1, originalHook.get());
                assertEquals(0, replacementHook.get());
                control.cancel();
                assertEquals(1, replacementHook.get());
            } finally {
                releaseGuard.countDown();
                control.cancel();
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }


    @Test public void guardFailureDoesNotReplaceBudgetExpiryOrThreadInterruptionWithCancellation() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            AtomicLong clock = new AtomicLong();
            RunControl control = new RunControl("probe-test", new TaskBudget(1, 1, 1000), clock::get);
            long originalEpoch = control.getExecutionEpoch();
            AtomicInteger hookCalls = new AtomicInteger();
            control.setCancelHook(hookCalls::incrementAndGet);
            try {
                ModelRequestContext context = new ModelRequestContext(control, originalEpoch,
                        (owner, epoch) -> {
                            if (interrupt) Thread.currentThread().interrupt();
                            else clock.set(TimeUnit.MILLISECONDS.toNanos(1000));
                            return false;
                        });
                TaskControlException error = assertThrows(TaskControlException.class, context::check);
                assertEquals(interrupt ? "TASK_STOPPED" : "BUDGET_EXCEEDED", error.getCode());
                assertFalse("A failed pre-cancel task check must retain original control state", control.isCancelled());
                assertEquals(originalEpoch, control.getExecutionEpoch());
                assertEquals(0, hookCalls.get());
                assertEquals(interrupt, Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                control.cancel();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("Guard barrier timed out", latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Guard barrier interrupted", interrupted);
        }
    }
}
