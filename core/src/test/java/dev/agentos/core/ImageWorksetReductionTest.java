package dev.agentos.core;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.*;

/** Only verified logical capacity is adjusted; these tests make no native-memory claim. */
public final class ImageWorksetReductionTest {
    @Test public void partialReleaseAdmitsAnotherOwnerAndCloseReturnsOnlyTheRemainingCapacity() {
        ImageWorkset workset = new ImageWorkset(100);
        try (ImageWorkset.Reservation image = workset.reserve(80);
             ImageWorkset.Reservation unrelated = workset.reserve(20)) {
            assertThrows(IllegalStateException.class, () -> workset.reserve(1));
            image.reduceTo(30);
            assertEquals(30, image.bytes()); assertEquals(50, workset.reservedBytes());
            try (ImageWorkset.Reservation copy = workset.reserve(50)) {
                assertEquals(100, workset.reservedBytes()); assertEquals(50, copy.bytes());
                image.reduceTo(30);
                assertEquals("same-size reduction is a no-op", 100, workset.reservedBytes());
                image.close(); image.close();
                assertEquals(70, workset.reservedBytes());
                assertEquals(20, unrelated.bytes()); assertEquals(50, copy.bytes());
                assertThrows(IllegalStateException.class, () -> workset.reserve(31));
            }
            assertEquals(20, workset.reservedBytes());
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void invalidOrClosedReductionsCannotChangeAccountingOrReviveAnOldOwner() {
        ImageWorkset workset = new ImageWorkset(100);
        ImageWorkset.Reservation old = workset.reserve(60);
        try {
            for (long invalid : new long[]{0, -1, Long.MIN_VALUE, 61, Long.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> old.reduceTo(invalid));
                assertEquals(60, old.bytes()); assertEquals(60, workset.reservedBytes());
            }
            old.reduceTo(20);
            assertThrows(IllegalArgumentException.class, () -> old.reduceTo(21));
            assertEquals(20, old.bytes()); assertEquals(20, workset.reservedBytes());
            old.close();
            try (ImageWorkset.Reservation successor = workset.reserve(100)) {
                for (long invalid : new long[]{1, 20, 60, 0, Long.MAX_VALUE}) {
                    assertThrows(IllegalStateException.class, () -> old.reduceTo(invalid));
                    old.close();
                    assertEquals(20, old.bytes()); assertEquals(100, successor.bytes());
                    assertEquals(100, workset.reservedBytes());
                }
            }
            assertEquals(0, workset.reservedBytes());
        } finally { old.close(); }
    }

    @Test public void longMaxCapacityReducesWithoutOverflowAndCannotBeExpandedAgain() {
        ImageWorkset workset = new ImageWorkset(Long.MAX_VALUE);
        try (ImageWorkset.Reservation first = workset.reserve(Long.MAX_VALUE)) {
            first.reduceTo(Long.MAX_VALUE - 1);
            assertEquals(Long.MAX_VALUE - 1, first.bytes());
            try (ImageWorkset.Reservation lastByte = workset.reserve(1)) {
                assertEquals(Long.MAX_VALUE, workset.reservedBytes());
                first.reduceTo(1);
                assertEquals(2, workset.reservedBytes()); assertEquals(1, first.bytes());
                assertThrows(IllegalArgumentException.class, () -> first.reduceTo(Long.MAX_VALUE));
                try (ImageWorkset.Reservation remaining = workset.reserve(Long.MAX_VALUE - 2)) {
                    assertEquals(Long.MAX_VALUE, workset.reservedBytes());
                    first.close(); first.close();
                    assertEquals(Long.MAX_VALUE - 1, workset.reservedBytes());
                    assertEquals(Long.MAX_VALUE - 2, remaining.bytes());
                    assertEquals(1, lastByte.bytes());
                }
                assertEquals(1, workset.reservedBytes());
            }
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test(timeout = 12000) public void reductionRacingTwoClosersSettlesExactlyOnceAndKeepsOtherOwners() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            for (int iteration = 0; iteration < 32; iteration++) {
                ImageWorkset workset = new ImageWorkset(107);
                ImageWorkset.Reservation current = workset.reserve(100);
                try (ImageWorkset.Reservation other = workset.reserve(7)) {
                    CyclicBarrier start = new CyclicBarrier(4);
                    Future<Boolean> reducing = workers.submit(() -> {
                        start.await(1, TimeUnit.SECONDS);
                        try { current.reduceTo(25); return true; }
                        catch (IllegalStateException alreadyClosed) { return false; }
                    });
                    Future<?> firstClose = workers.submit(() -> { start.await(1, TimeUnit.SECONDS); current.close(); return null; });
                    Future<?> secondClose = workers.submit(() -> { start.await(1, TimeUnit.SECONDS); current.close(); return null; });
                    start.await(1, TimeUnit.SECONDS);
                    boolean reduced = reducing.get(1, TimeUnit.SECONDS);
                    firstClose.get(1, TimeUnit.SECONDS); secondClose.get(1, TimeUnit.SECONDS);
                    assertEquals(reduced ? 25 : 100, current.bytes());
                    assertEquals(7, workset.reservedBytes()); assertEquals(7, other.bytes());
                    try (ImageWorkset.Reservation replacement = workset.reserve(100)) {
                        current.close();
                        assertThrows(IllegalStateException.class, () -> current.reduceTo(1));
                        assertEquals(107, workset.reservedBytes()); assertEquals(100, replacement.bytes());
                    }
                } finally { current.close(); }
                assertEquals(0, workset.reservedBytes());
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test(timeout = 5000) public void concurrentReductionsCannotRestoreCapacityAlreadyReleasedByAnotherThread() throws Exception {
        ImageWorkset workset = new ImageWorkset(100);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (ImageWorkset.Reservation current = workset.reserve(100)) {
            CyclicBarrier start = new CyclicBarrier(3);
            Future<Boolean> larger = workers.submit(() -> {
                start.await(1, TimeUnit.SECONDS);
                try { current.reduceTo(80); return true; }
                catch (IllegalArgumentException nowAnExpansion) { return false; }
            });
            Future<?> smaller = workers.submit(() -> { start.await(1, TimeUnit.SECONDS); current.reduceTo(60); return null; });
            start.await(1, TimeUnit.SECONDS); larger.get(1, TimeUnit.SECONDS); smaller.get(1, TimeUnit.SECONDS);
            assertEquals(60, current.bytes()); assertEquals(60, workset.reservedBytes());
            Future<Long> independentReader = workers.submit(current::bytes);
            assertEquals(60, (long) independentReader.get(1, TimeUnit.SECONDS));
            try (ImageWorkset.Reservation next = workset.reserve(40)) {
                assertEquals(100, workset.reservedBytes()); assertEquals(40, next.bytes());
                assertThrows(IllegalArgumentException.class, () -> current.reduceTo(80));
                assertEquals(100, workset.reservedBytes());
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS)); }
        assertEquals(0, workset.reservedBytes());
    }
}
