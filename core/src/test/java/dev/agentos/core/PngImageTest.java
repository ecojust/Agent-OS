package dev.agentos.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PngImageTest {
    static byte[] png() throws IOException { return png(2, 1, new byte[]{0, 10, 20, 30, 40, 50, 60}); }
    static byte[] png(int width, int height, byte[] rows) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(header);
        fields.writeInt(width); fields.writeInt(height); fields.write(new byte[]{8, 2, 0, 0, 0});
        chunk(output, "IHDR", header.toByteArray());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(encoded)) { deflater.write(rows); }
        chunk(output, "IDAT", encoded.toByteArray()); chunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream output, String name, byte[] value) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        DataOutputStream fields = new DataOutputStream(output);
        fields.writeInt(value.length); fields.write(type); fields.write(value);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(value); fields.writeInt((int) crc.getValue());
    }

    @Test public void finalBytesAndReadResultsAreIsolatedAndCloseIsIdempotent() throws Exception {
        byte[] source = png(), expected = source.clone();
        try (PngImage image = PngImage.fromBytes(source)) {
            assertEquals(2, image.width); assertEquals(1, image.height);
            assertEquals(expected.length, image.encodedBytes); assertEquals(64, image.sha256.length());
            Arrays.fill(source, (byte) 0);
            byte[] copy = read(image); Arrays.fill(copy, (byte) 0);
            assertArrayEquals(expected, read(image));
            image.close(); image.close(); assertTrue(image.isClosed());
            assertThrows(IllegalStateException.class, image::openRead);
        }
    }

    static byte[] read(PngImage image) throws IOException {
        try (PngImage.ReadLease stream = image.openRead()) { return stream.readAllBytes(); }
    }

    @Test public void rejectsOversizedEncodingBeforeCopyAndDecode() {
        assertThrows(IllegalArgumentException.class,
                () -> PngImage.fromBytes(new byte[ImageAttachment.MAX_ENCODED_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(null));
    }

    @Test public void headerLimitsAndActualInflatedPixelsAreBothValidated() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(png(1281, 1, new byte[4])));
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(png(1280, 1280, new byte[4])));
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(png(2, 1, new byte[4])));
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(png(2, 1, new byte[10])));
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(png(2, 1, new byte[]{5, 1, 2, 3, 4, 5, 6})));
    }

    @Test public void malformedChunksDoNotProduceAPreviewObject() throws Exception {
        byte[] broken = png(); broken[broken.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(broken));
        byte[] trailing = Arrays.copyOf(png(), png().length + 1);
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(trailing));
        byte[] truncated = Arrays.copyOf(png(), png().length - 7);
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(truncated));
    }

    @Test public void bothEncodingAndScratchMustBeAdmittedBeforeTheClone() throws Exception {
        byte[] source = png(); AtomicInteger copies = new AtomicInteger();
        for (long capacity : new long[]{source.length - 1L, source.length,
                source.length + PngValidator.SCRATCH_BYTES - 1L}) {
            ImageWorkset workset = new ImageWorkset(capacity);
            assertThrows(IllegalStateException.class, () -> PngImage.fromBytes(source, workset, value -> {
                copies.incrementAndGet(); return value.clone();
            }));
            assertEquals(0, copies.get()); assertEquals(0, workset.reservedBytes());
        }
        ImageWorkset workset = new ImageWorkset(source.length + PngValidator.SCRATCH_BYTES);
        try (PngImage image = PngImage.fromBytes(source, workset, value -> {
            assertEquals(workset.limitBytes(), workset.reservedBytes());
            copies.incrementAndGet(); return value.clone();
        })) {
            assertEquals(1, copies.get());
            assertEquals("scratch is released after successful validation and hashing", source.length, workset.reservedBytes());
            assertArrayEquals(source, read(image));
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void errorsDuringAllocationReturnEncodingAndScratchReservations() throws Exception {
        byte[] source = png(); ImageWorkset workset = new ImageWorkset(source.length + PngValidator.SCRATCH_BYTES);
        for (Error injected : new Error[]{new AssertionError("synthetic failure"), new OutOfMemoryError("synthetic allocation")}) {
            Error caught = assertThrows(Error.class, () -> PngImage.fromBytes(source, workset, value -> {
                assertEquals(workset.limitBytes(), workset.reservedBytes()); throw injected;
            }));
            assertSame(injected, caught); assertEquals(0, workset.reservedBytes());
            try (PngImage subsequent = PngImage.fromBytes(source, workset)) { assertArrayEquals(source, read(subsequent)); }
            assertEquals(0, workset.reservedBytes());
        }
    }

    @Test public void validationFailureWipesOnlyTheOwnedCloneBeforeReturningCapacity() throws Exception {
        byte[] source = png(); source[source.length - 1] ^= 1;
        byte[] expectedSource = source.clone(); AtomicReference<byte[]> owned = new AtomicReference<>();
        ImageWorkset workset = new ImageWorkset(source.length + PngValidator.SCRATCH_BYTES);
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(source, workset, value -> {
            byte[] copy = value.clone(); owned.set(copy); return copy;
        }));
        assertArrayEquals(expectedSource, source);
        assertNotNull(owned.get()); assertArrayEquals(new byte[source.length], owned.get());
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void readersShareOneEncodingAndLastCloseReleasesItAfterOwnerClosure() throws Exception {
        byte[] source = png(); AtomicReference<byte[]> owned = new AtomicReference<>();
        ImageWorkset workset = new ImageWorkset(source.length + PngValidator.SCRATCH_BYTES);
        try (PngImage image = PngImage.fromBytes(source, workset, value -> {
            byte[] copy = value.clone(); owned.set(copy); return copy;
        }); PngImage.ReadLease first = image.openRead(); PngImage.ReadLease last = image.openRead()) {
            assertEquals(source.length, workset.reservedBytes());
            assertEquals(source[0] & 255, first.read());
            image.close(); assertTrue(image.isClosed());
            assertThrows(IllegalStateException.class, image::openRead);
            assertArrayEquals(source, owned.get());
            first.close(); first.close();
            assertEquals(source.length, workset.reservedBytes());
            assertArrayEquals(source, last.readAllBytes());
            assertEquals("reaching EOF alone does not close a lease", source.length, workset.reservedBytes());
            last.close(); assertEquals(0, workset.reservedBytes());
            assertArrayEquals(new byte[source.length], owned.get());
            try (PngImage replacement = PngImage.fromBytes(source, workset)) {
                first.close(); last.close(); image.close();
                assertEquals(source.length, workset.reservedBytes());
                assertArrayEquals(source, read(replacement));
            }
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test(timeout = 6000) public void ownerCancellationDoesNotEraseOrReleaseAWorkerHeldReadLease() throws Exception {
        byte[] source = png(); ImageWorkset workset = new ImageWorkset(source.length + PngValidator.SCRATCH_BYTES);
        PngImage image = PngImage.fromBytes(source, workset);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch reading = new CountDownLatch(1), continueRead = new CountDownLatch(1);
        try {
            Future<byte[]> decoded = executor.submit(() -> {
                try (PngImage.ReadLease lease = image.openRead()) {
                    reading.countDown(); assertTrue(continueRead.await(3, TimeUnit.SECONDS));
                    return lease.readAllBytes();
                }
            });
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            image.close(); assertEquals(source.length, workset.reservedBytes());
            assertThrows(IllegalStateException.class, () -> PngImage.fromBytes(source, workset));
            assertEquals(source.length, workset.reservedBytes());
            continueRead.countDown(); assertArrayEquals(source, decoded.get(2, TimeUnit.SECONDS));
            assertEquals(0, workset.reservedBytes());
        } finally {
            image.close(); continueRead.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void markResetAndIndependentPositionsUseTheExistingBytesAndRespectClosure() throws Exception {
        byte[] source = png(); ImageWorkset workset = new ImageWorkset(source.length + PngValidator.SCRATCH_BYTES);
        try (PngImage image = PngImage.fromBytes(source, workset);
             PngImage.ReadLease first = image.openRead(); PngImage.ReadLease second = image.openRead()) {
            assertTrue(first.markSupported()); assertEquals(source.length, first.available());
            assertEquals(source[0] & 255, first.read()); first.mark(1);
            assertEquals(5, first.skip(5)); assertEquals(source.length - 6, first.available());
            image.close(); first.reset(); assertEquals(source[1] & 255, first.read());
            assertEquals(source[0] & 255, second.read());
            assertEquals(0, first.skip(-1));
            assertEquals(source.length - 2, first.skip(Long.MAX_VALUE));
            assertEquals(-1, first.read()); assertEquals(0, first.read(new byte[0], 0, 0));
            assertEquals(source.length, workset.reservedBytes());
            first.close();
            assertThrows(IOException.class, first::read); assertThrows(IOException.class, first::reset);
            assertThrows(IOException.class, first::available); assertThrows(IOException.class, () -> first.skip(1));
            assertThrows(IOException.class, () -> first.read(new byte[1], 0, 1));
            first.mark(Integer.MAX_VALUE);
            assertEquals(source.length, workset.reservedBytes());
            second.close(); assertEquals(0, workset.reservedBytes());
        }
    }

    @Test public void bulkReadHonorsOffsetsBoundsAndDoesNotLetCallerModifyOwnedBytes() throws Exception {
        byte[] source = png();
        try (PngImage image = PngImage.fromBytes(source); PngImage.ReadLease lease = image.openRead()) {
            byte[] destination = new byte[8]; Arrays.fill(destination, (byte) 42);
            assertEquals(4, lease.read(destination, 2, 4));
            assertEquals(42, destination[0]); assertEquals(42, destination[7]);
            assertArrayEquals(Arrays.copyOf(source, 4), Arrays.copyOfRange(destination, 2, 6));
            Arrays.fill(destination, (byte) 0); assertArrayEquals(source, read(image));
            assertThrows(NullPointerException.class, () -> lease.read(null, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> lease.read(destination, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> lease.read(destination, 1, Integer.MAX_VALUE));
            assertThrows(IndexOutOfBoundsException.class, () -> lease.read(destination, 0, -1));
        }
    }

    @Test public void publicOwnersShareProcessReservationsAndReaderCloseAloneDoesNotCloseOwner() throws Exception {
        byte[] source = png(); ImageWorkset shared = ImageWorkset.process(); long baseline = shared.reservedBytes();
        try (PngImage first = PngImage.fromBytes(source); PngImage second = PngImage.fromBytes(source)) {
            assertEquals(baseline + 2L * source.length, shared.reservedBytes());
            try (PngImage.ReadLease lease = first.openRead()) { assertEquals(source.length, lease.available()); }
            assertFalse(first.isClosed()); assertEquals(baseline + 2L * source.length, shared.reservedBytes());
            first.close(); assertEquals(baseline + source.length, shared.reservedBytes());
            assertArrayEquals(source, read(second));
        }
        assertEquals(baseline, shared.reservedBytes());
    }
}
