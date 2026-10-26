package dev.agentos.core;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Device side effects are simulated separately from the control's accounting. */
public class DispatchTicketTest {
    private static void invokeApi(RunControl.DispatchTicket ticket, AtomicInteger sideEffects) throws TaskControlException {
        ticket.checkExecutionEpoch();
        sideEffects.incrementAndGet();
    }

    @Test public void legacyAttemptsAndCompletedTicketsDoNotRemainInFlight() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger sideEffects = new AtomicInteger();
        control.markDispatchAttempt(epoch);
        assertEquals(1, control.getDispatchAttempts()); assertFalse(control.hasInFlightDispatch());
        RunControl.DispatchTicket ticket = control.beginDispatch(epoch);
        assertEquals(epoch, ticket.executionEpoch); assertTrue(control.hasInFlightDispatch());
        assertFalse(ticket.isSettled());
        invokeApi(ticket, sideEffects);
        ticket.settle(); ticket.close(); ticket.settle();
        assertTrue(ticket.isSettled()); assertFalse(control.hasInFlightDispatch());
        TaskControlException closed = assertThrows(TaskControlException.class, () -> invokeApi(ticket, sideEffects));
        assertEquals("DISPATCH_SETTLED", closed.getCode());
        assertEquals(1, sideEffects.get()); assertEquals(2, control.getDispatchAttempts());
    }

    @Test public void onePendingTicketBlocksAdmissionAndOldCloseCannotReleaseItsSuccessor() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger sideEffects = new AtomicInteger();
        RunControl.DispatchTicket first = control.beginDispatch(epoch);
        try {
            TaskControlException rejected = assertThrows(TaskControlException.class, () -> control.beginDispatch(epoch));
            assertEquals("DISPATCH_IN_PROGRESS", rejected.getCode());
            assertEquals(1, control.getDispatchAttempts());
            invokeApi(first, sideEffects);
        } finally { first.close(); }
        try (RunControl.DispatchTicket second = control.beginDispatch(epoch)) {
            first.settle(); first.close();
            assertTrue("late cleanup must not release a newer owner's slot", control.hasInFlightDispatch());
            assertFalse(second.isSettled());
            TaskControlException rejected = assertThrows(TaskControlException.class, () -> control.beginDispatch(epoch));
            assertEquals("DISPATCH_IN_PROGRESS", rejected.getCode());
            assertEquals("DISPATCH_SETTLED", assertThrows(TaskControlException.class, first::checkExecutionEpoch).getCode());
            invokeApi(second, sideEffects);
            assertEquals(2, control.getDispatchAttempts());
        }
        assertEquals(2, sideEffects.get()); assertFalse(control.hasInFlightDispatch());
    }

    @Test public void pauseResumeInvalidatesOldTicketButKeepsItsSlotUntilTheOwnerSettles() throws Exception {
        RunControl control = new RunControl(); long oldEpoch = control.getExecutionEpoch();
        AtomicInteger sideEffects = new AtomicInteger();
        RunControl.DispatchTicket old = control.beginDispatch(oldEpoch);
        try {
            control.pause();
            assertTrue(control.hasInFlightDispatch()); assertFalse(old.isSettled());
            assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class, () -> invokeApi(old, sideEffects)).getCode());
            assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class,
                    () -> control.beginDispatch(control.getExecutionEpoch())).getCode());
            control.resume();
            assertTrue(control.getExecutionEpoch() > oldEpoch);
            assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class, () -> invokeApi(old, sideEffects)).getCode());
            assertEquals("DISPATCH_IN_PROGRESS", assertThrows(TaskControlException.class,
                    () -> control.beginDispatch(control.getExecutionEpoch())).getCode());
            assertEquals(0, sideEffects.get()); assertEquals(1, control.getDispatchAttempts());
        } finally { old.settle(); }
        assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class,
                () -> control.beginDispatch(oldEpoch)).getCode());
        assertEquals(1, control.getDispatchAttempts());
        try (RunControl.DispatchTicket fresh = control.beginDispatch(control.getExecutionEpoch())) {
            invokeApi(fresh, sideEffects);
            assertEquals(control.getExecutionEpoch(), fresh.executionEpoch);
        }
        assertEquals(1, sideEffects.get()); assertEquals(2, control.getDispatchAttempts());
        assertFalse(control.hasInFlightDispatch());
    }

    @Test public void cancellationBeforeAdmissionPreventsAnyDeviceSideEffectOrAttempt() {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger sideEffects = new AtomicInteger(); control.cancel();
        TaskControlException cancelled = assertThrows(TaskControlException.class, () -> {
            try (RunControl.DispatchTicket ticket = control.beginDispatch(epoch)) { invokeApi(ticket, sideEffects); }
        });
        assertEquals("TASK_STOPPED", cancelled.getCode());
        control.resume();
        assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                () -> control.beginDispatch(control.getExecutionEpoch())).getCode());
        assertEquals(0, sideEffects.get()); assertEquals(0, control.getDispatchAttempts());
        assertFalse(control.hasInFlightDispatch());
    }

    @Test public void cancellationAfterAdmissionRejectsTheNotYetSubmittedApiAndPreservesOwnership() throws Exception {
        RunControl control = new RunControl(); AtomicInteger sideEffects = new AtomicInteger();
        RunControl.DispatchTicket ticket = control.beginDispatch(control.getExecutionEpoch());
        try {
            control.cancel();
            assertTrue(control.hasInFlightDispatch()); assertFalse(ticket.isSettled());
            assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                    () -> invokeApi(ticket, sideEffects)).getCode());
            assertEquals(0, sideEffects.get()); assertEquals(1, control.getDispatchAttempts());
        } finally { ticket.close(); }
        ticket.settle();
        assertTrue(ticket.isSettled()); assertFalse(control.hasInFlightDispatch());
    }

    @Test public void throwingDeviceApiStillReleasesTheTicketInFinally() throws Exception {
        RunControl control = new RunControl(); AtomicInteger sideEffects = new AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            try (RunControl.DispatchTicket ticket = control.beginDispatch(control.getExecutionEpoch())) {
                invokeApi(ticket, sideEffects);
                throw new IllegalStateException("synthetic API failed after entry");
            }
        });
        assertEquals("synthetic API failed after entry", failure.getMessage());
        assertEquals(1, sideEffects.get()); assertFalse(control.hasInFlightDispatch());
        try (RunControl.DispatchTicket next = control.beginDispatch(control.getExecutionEpoch())) { invokeApi(next, sideEffects); }
        assertEquals(2, sideEffects.get()); assertEquals(2, control.getDispatchAttempts());
        assertFalse(control.hasInFlightDispatch());
    }

    @Test(timeout = 6000) public void cancellationDoesNotWaitForBlockedApiAndInFlightEndsOnlyWhenItsOwnerCloses() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger apiEntered = new AtomicInteger(), apiCompleted = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), releaseApi = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> device = workers.submit(() -> {
                try (RunControl.DispatchTicket ticket = control.beginDispatch(epoch)) {
                    invokeApi(ticket, apiEntered); entered.countDown();
                    assertTrue("test must explicitly release the simulated API", releaseApi.await(4, TimeUnit.SECONDS));
                    // A system call submitted before cancellation can still complete.
                    apiCompleted.incrementAndGet();
                }
                return null;
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<?> stop = workers.submit(control::cancel);
            stop.get(1, TimeUnit.SECONDS);
            assertTrue(control.isCancelled()); assertTrue(control.hasInFlightDispatch());
            assertFalse("cancel must not wait for the independent API latch", device.isDone());
            assertEquals(1, apiEntered.get()); assertEquals(0, apiCompleted.get());
            assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                    () -> control.beginDispatch(control.getExecutionEpoch())).getCode());
            assertEquals(1, control.getDispatchAttempts());
            releaseApi.countDown(); device.get(1, TimeUnit.SECONDS);
            assertEquals(1, apiCompleted.get()); assertFalse(control.hasInFlightDispatch());
        } finally { releaseApi.countDown(); control.cancel(); workers.shutdownNow(); }
    }

    @Test(timeout = 5000) public void concurrentAdmissionAllowsOnlyOneApiWhileTheWinnerHoldsItsTicket() throws Exception {
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
        AtomicInteger sideEffects = new AtomicInteger(), accepted = new AtomicInteger(), rejected = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(3);
        CountDownLatch attempted = new CountDownLatch(2), releaseOwner = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Void> contender = () -> {
            start.await(1, TimeUnit.SECONDS);
            RunControl.DispatchTicket ticket;
            try { ticket = control.beginDispatch(epoch); }
            catch (TaskControlException busy) {
                assertEquals("DISPATCH_IN_PROGRESS", busy.getCode()); rejected.incrementAndGet(); attempted.countDown(); return null;
            }
            try (RunControl.DispatchTicket owner = ticket) {
                accepted.incrementAndGet(); invokeApi(owner, sideEffects); attempted.countDown();
                assertTrue(releaseOwner.await(3, TimeUnit.SECONDS));
            }
            return null;
        };
        try {
            Future<Void> first = workers.submit(contender), second = workers.submit(contender);
            start.await(1, TimeUnit.SECONDS); assertTrue(attempted.await(1, TimeUnit.SECONDS));
            assertEquals(1, accepted.get()); assertEquals(1, rejected.get());
            assertEquals(1, sideEffects.get()); assertEquals(1, control.getDispatchAttempts());
            assertTrue(control.hasInFlightDispatch());
            releaseOwner.countDown(); first.get(1, TimeUnit.SECONDS); second.get(1, TimeUnit.SECONDS);
            assertFalse(control.hasInFlightDispatch());
        } finally { releaseOwner.countDown(); control.cancel(); workers.shutdownNow(); }
    }

    @Test(timeout = 8000) public void concurrentCancellationAndAdmissionCannotAuthorizeAnApiAfterCancellation() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 32; iteration++) {
                RunControl control = new RunControl(); long epoch = control.getExecutionEpoch();
                CyclicBarrier start = new CyclicBarrier(3); CountDownLatch cancelled = new CountDownLatch(1);
                AtomicBoolean admitted = new AtomicBoolean(); AtomicInteger sideEffects = new AtomicInteger();
                Future<String> admission = workers.submit(() -> {
                    start.await(1, TimeUnit.SECONDS);
                    try (RunControl.DispatchTicket ticket = control.beginDispatch(epoch)) {
                        admitted.set(true);
                        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
                        invokeApi(ticket, sideEffects);
                        return "unexpected-api";
                    } catch (TaskControlException stopped) { return stopped.getCode(); }
                });
                Future<?> stop = workers.submit(() -> {
                    start.await(1, TimeUnit.SECONDS);
                    control.cancel(); cancelled.countDown(); return null;
                });
                start.await(1, TimeUnit.SECONDS);
                stop.get(1, TimeUnit.SECONDS);
                assertEquals("TASK_STOPPED", admission.get(1, TimeUnit.SECONDS));
                assertEquals(0, sideEffects.get());
                assertEquals(admitted.get() ? 1 : 0, control.getDispatchAttempts());
                assertFalse(control.hasInFlightDispatch());
            }
        } finally { workers.shutdownNow(); }
    }

    @Test public void ticketsFromDifferentControlsKeepIndependentOwnershipAndCancellation() throws Exception {
        RunControl first = new RunControl("profile-one"), second = new RunControl("profile-two");
        AtomicInteger sideEffects = new AtomicInteger();
        try (RunControl.DispatchTicket firstTicket = first.beginDispatch(first.getExecutionEpoch());
             RunControl.DispatchTicket secondTicket = second.beginDispatch(second.getExecutionEpoch())) {
            first.cancel(); firstTicket.close();
            assertFalse(first.hasInFlightDispatch()); assertTrue(second.hasInFlightDispatch());
            assertFalse(secondTicket.isSettled()); invokeApi(secondTicket, sideEffects);
            firstTicket.settle(); assertTrue(second.hasInFlightDispatch());
            assertEquals(1, first.getDispatchAttempts()); assertEquals(1, second.getDispatchAttempts());
        }
        assertEquals(1, sideEffects.get()); assertFalse(second.hasInFlightDispatch());
    }

    @Test public void activityBudgetPersistsAcrossPauseAndTicketSettlementAndStillBoundsAdmission() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger sideEffects = new AtomicInteger();
        RunControl control = new RunControl("personal-test", new TaskBudget(5, 10, 1000), clock::get);
        control.consumeModelRound(); control.consumeToolCall();
        RunControl.DispatchTicket old = control.beginDispatch(control.getExecutionEpoch());
        clock.set(TimeUnit.MILLISECONDS.toNanos(100)); control.pause();
        clock.set(TimeUnit.MILLISECONDS.toNanos(6100)); control.resume(); old.close();
        assertEquals(100, control.budgetSnapshot().activeMillis);
        assertEquals(6000, control.budgetSnapshot().pausedMillis);
        try (RunControl.DispatchTicket fresh = control.beginDispatch(control.getExecutionEpoch())) {
            invokeApi(fresh, sideEffects);
            clock.set(TimeUnit.MILLISECONDS.toNanos(7000));
            assertEquals("BUDGET_EXCEEDED", assertThrows(TaskControlException.class,
                    () -> invokeApi(fresh, sideEffects)).getCode());
            assertTrue(control.hasInFlightDispatch());
        }
        assertFalse(control.hasInFlightDispatch());
        assertEquals("BUDGET_EXCEEDED", assertThrows(TaskControlException.class,
                () -> control.beginDispatch(control.getExecutionEpoch())).getCode());
        assertEquals(1, sideEffects.get()); assertEquals(2, control.getDispatchAttempts());
        assertEquals(1000, control.budgetSnapshot().activeMillis);
        assertEquals(1, control.budgetSnapshot().modelRounds); assertEquals(1, control.budgetSnapshot().toolCalls);
    }
}
