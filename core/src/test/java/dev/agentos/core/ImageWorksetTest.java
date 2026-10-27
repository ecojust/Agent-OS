package dev.agentos.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Tests application-owned byte reservations, without claiming a JVM or process memory cap. */
public class ImageWorksetTest {
    @Test public void processBudgetIsOneSingletonWithTheDocumentedLimit() {
        ImageWorkset process = ImageWorkset.process();
        assertSame(process, ImageWorkset.process());
        assertEquals(24L * 1024 * 1024, process.limitBytes());
        assertNotSame(process, new ImageWorkset(process.limitBytes()));
        // Do not reserve or assert an idle counter on the shared process instance.
    }

    @Test public void invalidLimitsAndRequestsCannotChangeExistingReservations() {
        for (long invalid : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ImageWorkset(invalid));
        }
        ImageWorkset workset = new ImageWorkset(10);
        assertEquals(10, workset.limitBytes());
        assertEquals(0, workset.reservedBytes());
        try (ImageWorkset.Reservation held = workset.reserve(4)) {
            assertEquals(4, held.bytes());
            for (long invalid : new long[] {0, -1, Long.MIN_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> workset.reserve(invalid));
                assertEquals(4, workset.reservedBytes());
            }
            for (long tooLarge : new long[] {7, 10, Long.MAX_VALUE}) {
                assertThrows(IllegalStateException.class, () -> workset.reserve(tooLarge));
                assertEquals("failed admission must leave the owner's reservation intact", 4, workset.reservedBytes());
            }
            try (ImageWorkset.Reservation remainder = workset.reserve(6)) {
                assertEquals(6, remainder.bytes());
                assertEquals(10, workset.reservedBytes());
                assertThrows(IllegalStateException.class, () -> workset.reserve(1));
                assertEquals(10, workset.reservedBytes());
            }
            assertEquals(4, workset.reservedBytes());
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void longMaxCapacityUsesRemainingSpaceWithoutArithmeticOverflow() {
        ImageWorkset workset = new ImageWorkset(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, workset.limitBytes());
        ImageWorkset.Reservation almostFull = workset.reserve(Long.MAX_VALUE - 1);
        ImageWorkset.Reservation lastByte = null;
        try {
            assertEquals(Long.MAX_VALUE - 1, almostFull.bytes());
            assertEquals(Long.MAX_VALUE - 1, workset.reservedBytes());
            for (long rejected : new long[] {2, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
                assertThrows(IllegalStateException.class, () -> workset.reserve(rejected));
                assertEquals(Long.MAX_VALUE - 1, workset.reservedBytes());
            }
            lastByte = workset.reserve(1);
            assertEquals(Long.MAX_VALUE, workset.reservedBytes());
            assertThrows(IllegalStateException.class, () -> workset.reserve(1));
            assertEquals(Long.MAX_VALUE, workset.reservedBytes());
            almostFull.close();
            assertEquals(1, workset.reservedBytes());
            assertThrows(IllegalStateException.class, () -> workset.reserve(Long.MAX_VALUE));
            assertEquals(1, workset.reservedBytes());
            lastByte.close();
            assertEquals(0, workset.reservedBytes());
            try (ImageWorkset.Reservation full = workset.reserve(Long.MAX_VALUE)) {
                assertEquals(Long.MAX_VALUE, full.bytes());
                almostFull.close(); lastByte.close();
                assertEquals("old tokens cannot underflow a later full reservation", Long.MAX_VALUE, workset.reservedBytes());
                assertThrows(IllegalStateException.class, () -> workset.reserve(1));
            }
            assertEquals(0, workset.reservedBytes());
        } finally {
            almostFull.close();
            if (lastByte != null) lastByte.close();
        }
    }

    @Test public void closingOneTokenReleasesExactlyItsBytesAndNeverItsSuccessor() {
        ImageWorkset workset = new ImageWorkset(100);
        ImageWorkset.Reservation first = workset.reserve(25);
        ImageWorkset.Reservation second = workset.reserve(35);
        ImageWorkset.Reservation third = workset.reserve(40);
        try {
            assertEquals(100, workset.reservedBytes());
            second.close(); second.close();
            assertEquals(65, workset.reservedBytes());
            assertThrows(IllegalStateException.class, () -> workset.reserve(36));
            try (ImageWorkset.Reservation replacement = workset.reserve(35)) {
                assertEquals(100, workset.reservedBytes());
                second.close();
                assertEquals(100, workset.reservedBytes());
                first.close();
                assertEquals(75, workset.reservedBytes());
                second.close(); first.close();
                assertEquals(75, workset.reservedBytes());
                third.close();
                assertEquals(35, workset.reservedBytes());
                assertEquals(35, replacement.bytes());
            }
            assertEquals(0, workset.reservedBytes());
            try (ImageWorkset.Reservation full = workset.reserve(100)) {
                first.close(); second.close(); third.close();
                assertEquals(100, full.bytes());
                assertEquals(100, workset.reservedBytes());
            }
        } finally { first.close(); second.close(); third.close(); }
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void reservationsFromDifferentWorksetsHaveIndependentOwnership() {
        ImageWorkset first = new ImageWorkset(8), second = new ImageWorkset(8);
        ImageWorkset.Reservation firstToken = first.reserve(8);
        try (ImageWorkset.Reservation secondToken = second.reserve(8)) {
            firstToken.close(); firstToken.close();
            assertEquals(0, first.reservedBytes());
            assertEquals(8, second.reservedBytes());
            assertEquals(8, secondToken.bytes());
            assertThrows(IllegalStateException.class, () -> second.reserve(1));
            try (ImageWorkset.Reservation renewed = first.reserve(8)) {
                firstToken.close();
                assertEquals(8, renewed.bytes());
                assertEquals(8, first.reservedBytes());
                assertEquals(8, second.reservedBytes());
            }
            assertEquals(0, first.reservedBytes());
            assertEquals(8, second.reservedBytes());
        } finally { firstToken.close(); }
        assertEquals(0, first.reservedBytes()); assertEquals(0, second.reservedBytes());
    }

    @Test public void exceptionalOwnedWorkStillReturnsItsReservationInFinally() {
        ImageWorkset workset = new ImageWorkset(64);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            try (ImageWorkset.Reservation owner = workset.reserve(64)) {
                assertEquals(64, owner.bytes()); assertEquals(64, workset.reservedBytes());
                throw new IllegalStateException("synthetic owner failure");
            }
        });
        assertEquals("synthetic owner failure", failure.getMessage());
        assertEquals(0, workset.reservedBytes());
        try (ImageWorkset.Reservation next = workset.reserve(64)) {
            assertEquals(64, next.bytes()); assertEquals(64, workset.reservedBytes());
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test(timeout = 10000) public void concurrentContendersCannotBothClaimTheLastAvailableBytes() throws Exception {
        ImageWorkset workset = new ImageWorkset(100);
        int contenders = 6;
        ExecutorService workers = Executors.newFixedThreadPool(contenders);
        CyclicBarrier start = new CyclicBarrier(contenders + 1);
        CountDownLatch attempted = new CountDownLatch(contenders), releaseWinner = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        try (ImageWorkset.Reservation held = workset.reserve(93)) {
            for (int i = 0; i < contenders; i++) {
                tasks.add(workers.submit(() -> {
                    start.await(3, TimeUnit.SECONDS);
                    ImageWorkset.Reservation candidate;
                    try { candidate = workset.reserve(7); }
                    catch (IllegalStateException full) {
                        rejected.incrementAndGet(); attempted.countDown(); return null;
                    }
                    try (ImageWorkset.Reservation winner = candidate) {
                        assertEquals(7, winner.bytes()); accepted.incrementAndGet(); attempted.countDown();
                        assertTrue("winner must retain its bytes until all contenders have tried",
                                releaseWinner.await(5, TimeUnit.SECONDS));
                    }
                    return null;
                }));
            }
            start.await(3, TimeUnit.SECONDS);
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertEquals(1, accepted.get()); assertEquals(contenders - 1, rejected.get());
            assertEquals(100, workset.reservedBytes());
            assertEquals(93, held.bytes());
            assertThrows(IllegalStateException.class, () -> workset.reserve(1));
            releaseWinner.countDown();
            for (Future<?> task : tasks) task.get(3, TimeUnit.SECONDS);
            assertEquals(93, workset.reservedBytes());
            try (ImageWorkset.Reservation availableAgain = workset.reserve(7)) {
                assertEquals(7, availableAgain.bytes()); assertEquals(100, workset.reservedBytes());
            }
        } finally { releaseWinner.countDown(); workers.shutdownNow(); }
        assertEquals(0, workset.reservedBytes());
    }

    @Test(timeout = 10000) public void concurrentCrossThreadCloseReturnsBytesOnlyOnce() throws Exception {
        ImageWorkset workset = new ImageWorkset(100);
        ImageWorkset.Reservation shared = workset.reserve(30);
        int closers = 6;
        ExecutorService workers = Executors.newFixedThreadPool(closers);
        CyclicBarrier start = new CyclicBarrier(closers + 1);
        List<Future<?>> tasks = new ArrayList<>();
        try (ImageWorkset.Reservation survivor = workset.reserve(70)) {
            for (int i = 0; i < closers; i++) {
                tasks.add(workers.submit(() -> {
                    start.await(3, TimeUnit.SECONDS);
                    for (int repeat = 0; repeat < 64; repeat++) shared.close();
                    return null;
                }));
            }
            start.await(3, TimeUnit.SECONDS);
            for (Future<?> task : tasks) task.get(3, TimeUnit.SECONDS);
            assertEquals(70, survivor.bytes()); assertEquals(70, workset.reservedBytes());
            assertThrows(IllegalStateException.class, () -> workset.reserve(31));
            try (ImageWorkset.Reservation replacement = workset.reserve(30)) {
                shared.close();
                assertEquals(30, replacement.bytes()); assertEquals(100, workset.reservedBytes());
                assertThrows(IllegalStateException.class, () -> workset.reserve(1));
            }
            assertEquals(70, workset.reservedBytes());
        } finally { shared.close(); workers.shutdownNow(); }
        assertEquals(0, workset.reservedBytes());
    }
}
