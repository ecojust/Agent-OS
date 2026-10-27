package dev.agentos.app;

import android.hardware.HardwareBuffer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.ImageWorkset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Real synthetic native handles only: no screenshots, device capture, or renderer ownership. */
@RunWith(AndroidJUnit4.class)
public final class OwnedWindowBufferInstrumentedTest {
    private static final int WIDTH = 8, HEIGHT = 6;
    private static final long LOGICAL_CAPACITY = (long) WIDTH * HEIGHT * 8;
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence(
            "synthetic_hardware_buffer_ownership_nonvisual_no_device_capture");

    private static HardwareBuffer createBuffer() {
        return HardwareBuffer.create(WIDTH, HEIGHT, HardwareBuffer.RGBA_8888, 1,
                HardwareBuffer.USAGE_CPU_READ_RARELY | HardwareBuffer.USAGE_CPU_WRITE_RARELY);
    }

    /** Test caller owns failed constructions, just as the capturer must. */
    private static final class Source implements AutoCloseable {
        final HardwareBuffer handle;
        final ImageWorkset.Reservation capacity;
        private OwnedWindowBuffer transferred;

        Source() {
            ImageWorkset.Reservation reserved = ImageWorkset.process().reserve(LOGICAL_CAPACITY);
            HardwareBuffer allocated = null;
            boolean success = false;
            try {
                allocated = createBuffer();
                handle = allocated; capacity = reserved; success = true;
            } finally {
                if (!success) {
                    if (allocated != null) allocated.close();
                    reserved.close();
                }
            }
        }

        OwnedWindowBuffer transfer(Runnable completion) {
            if (transferred != null) throw new IllegalStateException("test source already transferred");
            transferred = new OwnedWindowBuffer(handle, capacity, completion);
            return transferred;
        }

        @Override public void close() {
            if (transferred != null) transferred.close();
            else {
                handle.close();
                assertTrue(handle.isClosed());
                capacity.close();
            }
        }
    }

    @Test public void lastReaderClosesTheActualHandleBeforeCapacityAndSlotNotification() {
        ImageWorkset workset = ImageWorkset.process(); long baseline = workset.reservedBytes();
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<OwnedWindowBuffer> reference = new AtomicReference<>();
        try (Source source = new Source()) {
            HardwareBuffer handle = source.handle;
            OwnedWindowBuffer owner = source.transfer(() -> {
                assertFalse(Thread.holdsLock(reference.get()));
                assertTrue(handle.isClosed()); assertTrue(reference.get().isActuallyClosed());
                assertEquals(baseline, workset.reservedBytes()); notifications.incrementAndGet();
            });
            reference.set(owner);
            try (OwnedWindowBuffer.ReadLease first = owner.openRead(); OwnedWindowBuffer.ReadLease second = owner.openRead()) {
                assertSame(handle, first.buffer()); assertSame(handle, second.buffer());
                owner.close(); owner.close();
                assertTrue(owner.isClosed()); assertFalse(owner.isActuallyClosed());
                assertFalse(handle.isClosed()); assertEquals(WIDTH, first.buffer().getWidth());
                assertEquals(baseline + LOGICAL_CAPACITY, workset.reservedBytes());
                assertThrows(IllegalStateException.class, owner::openRead);
                first.close(); first.close();
                assertThrows(IllegalStateException.class, first::buffer);
                assertFalse(handle.isClosed()); assertEquals(HEIGHT, second.buffer().getHeight());
                assertEquals(0, notifications.get());
                second.close();
                assertThrows(IllegalStateException.class, second::buffer);
                assertTrue(handle.isClosed()); assertTrue(owner.isActuallyClosed());
                assertEquals(1, notifications.get());
            }
        }
        assertEquals(baseline, workset.reservedBytes()); assertEquals(1, notifications.get());
    }

    @Test public void closedReadersDoNotCloseAnOpenOwnerAndOldCloseCannotReleaseANewOwner() {
        ImageWorkset workset = ImageWorkset.process(); long baseline = workset.reservedBytes();
        AtomicInteger oldNotifications = new AtomicInteger(), freshNotifications = new AtomicInteger();
        try (Source oldSource = new Source(); Source freshSource = new Source()) {
            HardwareBuffer oldHandle = oldSource.handle, freshHandle = freshSource.handle;
            OwnedWindowBuffer old = oldSource.transfer(oldNotifications::incrementAndGet);
            OwnedWindowBuffer fresh = freshSource.transfer(freshNotifications::incrementAndGet);
            try (OwnedWindowBuffer.ReadLease reader = old.openRead()) { assertSame(oldHandle, reader.buffer()); }
            assertFalse(oldHandle.isClosed()); assertFalse(old.isClosed()); assertFalse(old.isActuallyClosed());
            try (OwnedWindowBuffer.ReadLease reader = old.openRead()) { assertSame(oldHandle, reader.buffer()); }
            old.close();
            assertTrue(oldHandle.isClosed()); assertEquals(1, oldNotifications.get());
            old.close(); old.close();
            assertFalse(freshHandle.isClosed()); assertFalse(fresh.isClosed()); assertFalse(fresh.isActuallyClosed());
            assertEquals(baseline + LOGICAL_CAPACITY, workset.reservedBytes()); assertEquals(0, freshNotifications.get());
            try (OwnedWindowBuffer.ReadLease reader = fresh.openRead()) { assertEquals(WIDTH, reader.buffer().getWidth()); }
        }
        assertEquals(1, oldNotifications.get()); assertEquals(1, freshNotifications.get());
        assertEquals(baseline, workset.reservedBytes());
    }

    @Test public void constructorFailureLeavesCallerResourcesAndAThrowingCallbackIsNotRepeated() {
        ImageWorkset workset = ImageWorkset.process(); long baseline = workset.reservedBytes();
        try (Source source = new Source()) {
            assertThrows(NullPointerException.class, () -> source.transfer(null));
            assertFalse(source.handle.isClosed()); assertEquals(baseline + LOGICAL_CAPACITY, workset.reservedBytes());
            source.handle.close();
            assertThrows(IllegalArgumentException.class, () -> source.transfer(() -> fail("constructor must not notify")));
            assertEquals(baseline + LOGICAL_CAPACITY, workset.reservedBytes());
        }
        assertEquals(baseline, workset.reservedBytes());

        AtomicInteger notifications = new AtomicInteger();
        RuntimeException callbackFailure = new IllegalStateException("synthetic callback failure");
        try (Source source = new Source()) {
            OwnedWindowBuffer owner = source.transfer(() -> {
                assertTrue(source.handle.isClosed()); assertEquals(baseline, workset.reservedBytes());
                notifications.incrementAndGet(); throw callbackFailure;
            });
            assertSame(callbackFailure, assertThrows(IllegalStateException.class, owner::close));
            assertTrue(owner.isActuallyClosed()); assertTrue(source.handle.isClosed());
            owner.close(); assertEquals(1, notifications.get());
        }
        assertEquals(baseline, workset.reservedBytes());
    }

    @Test public void ownerCloseCannotInvalidateAConcurrentWorkerBorrow() throws Exception {
        ImageWorkset workset = ImageWorkset.process(); long baseline = workset.reservedBytes();
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch borrowed = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Source source = new Source()) {
            HardwareBuffer handle = source.handle;
            OwnedWindowBuffer owner = source.transfer(notifications::incrementAndGet);
            try {
                Future<?> reading = worker.submit(() -> {
                    try (OwnedWindowBuffer.ReadLease reader = owner.openRead()) {
                        borrowed.countDown();
                        assertTrue(release.await(3, TimeUnit.SECONDS));
                        assertFalse(reader.buffer().isClosed()); assertEquals(WIDTH, reader.buffer().getWidth());
                    }
                    return null;
                });
                assertTrue(borrowed.await(2, TimeUnit.SECONDS));
                owner.close();
                assertFalse(handle.isClosed()); assertFalse(owner.isActuallyClosed());
                assertEquals(0, notifications.get()); assertEquals(baseline + LOGICAL_CAPACITY, workset.reservedBytes());
                release.countDown(); reading.get(2, TimeUnit.SECONDS);
                assertTrue(handle.isClosed()); assertTrue(owner.isActuallyClosed()); assertEquals(1, notifications.get());
            } finally {
                release.countDown(); worker.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            }
        } finally { release.countDown(); worker.shutdownNow(); }
        assertEquals(baseline, workset.reservedBytes());
    }
}
