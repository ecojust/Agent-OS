package dev.agentos.core;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.Test;

import static org.junit.Assert.*;

/** A fixed encoding buffer contributes only its valid range to the immutable PNG owner. */
public final class PngImageRangeTest {
    @Test public void copiesOnlyTheSelectedRangeAndIgnoresLargePrefixAndUnusedSuffix() throws Exception {
        byte[] png = PngImageTest.png(); int offset = 19;
        byte[] buffer = new byte[ImageAttachment.MAX_ENCODED_BYTES + 1024]; Arrays.fill(buffer, (byte) 83);
        System.arraycopy(png, 0, buffer, offset, png.length);
        byte[] original = buffer.clone();
        ImageWorkset workset = new ImageWorkset(png.length + PngValidator.SCRATCH_BYTES);
        try (PngImage image = PngImage.fromBytes(buffer, offset, png.length, workset)) {
            assertEquals(png.length, image.encodedBytes); assertEquals(png.length, workset.reservedBytes());
            assertEquals(2, image.width); assertEquals(1, image.height);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)), image.sha256);
            assertArrayEquals("factory must not alter prefix, selected range or suffix", original, buffer);
            Arrays.fill(buffer, (byte) 0);
            assertArrayEquals("caller mutation cannot change the owned range", png, PngImageTest.read(image));
        }
        assertEquals(0, workset.reservedBytes());
        assertArrayEquals(new byte[buffer.length], buffer);
    }

    @Test public void publicRangeFactoryRetainsReaderOwnershipWithoutChangingFullArrayValidation() throws Exception {
        byte[] png = PngImageTest.png(); byte[] buffer = Arrays.copyOf(png, png.length + 16);
        Arrays.fill(buffer, png.length, buffer.length, (byte) 127);
        byte[] original = buffer.clone();
        assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(buffer));
        try (PngImage image = PngImage.fromBytes(buffer, 0, png.length);
             PngImage.ReadLease reader = image.openRead()) {
            image.close();
            assertTrue(image.isClosed()); assertThrows(IllegalStateException.class, image::openRead);
            assertArrayEquals(png, reader.readAllBytes());
            reader.close(); assertThrows(java.io.IOException.class, reader::read);
        }
        assertArrayEquals(original, buffer);
    }

    @Test public void invalidRangesAndIntegerOverflowLeaveExistingReservationsUntouched() throws Exception {
        byte[] png = PngImageTest.png(); byte[] original = png.clone();
        ImageWorkset workset = new ImageWorkset(50_000);
        try (ImageWorkset.Reservation other = workset.reserve(17)) {
            int[][] invalid = {{-1, png.length}, {0, 0}, {0, -1}, {png.length, 1}, {1, png.length},
                    {0, png.length + 1}, {Integer.MAX_VALUE, 2}, {2, Integer.MAX_VALUE},
                    {Integer.MIN_VALUE, png.length}, {0, ImageAttachment.MAX_ENCODED_BYTES + 1}};
            for (int[] range : invalid) {
                assertThrows(IllegalArgumentException.class,
                        () -> PngImage.fromBytes(png, range[0], range[1], workset));
                assertEquals(17, workset.reservedBytes()); assertArrayEquals(original, png);
            }
            assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(null, 0, 1, workset));
            assertEquals(17, workset.reservedBytes());
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void rangeCopyNeedsBothValidLengthAndValidationScratchBeforeAllocation() throws Exception {
        byte[] png = PngImageTest.png(); byte[] buffer = new byte[ImageAttachment.MAX_ENCODED_BYTES];
        int offset = buffer.length - png.length; System.arraycopy(png, 0, buffer, offset, png.length);
        byte[] original = buffer.clone();
        for (long available : new long[]{png.length - 1L, png.length, png.length + PngValidator.SCRATCH_BYTES - 1L}) {
            ImageWorkset workset = new ImageWorkset(available + 9);
            try (ImageWorkset.Reservation other = workset.reserve(9)) {
                assertThrows(IllegalStateException.class, () -> PngImage.fromBytes(buffer, offset, png.length, workset));
                assertEquals(9, workset.reservedBytes()); assertArrayEquals(original, buffer);
            }
            assertEquals(0, workset.reservedBytes());
        }
        ImageWorkset exact = new ImageWorkset(png.length + PngValidator.SCRATCH_BYTES);
        try (PngImage image = PngImage.fromBytes(buffer, offset, png.length, exact)) {
            assertArrayEquals(png, PngImageTest.read(image)); assertEquals(png.length, exact.reservedBytes());
        }
        assertEquals(0, exact.reservedBytes()); assertArrayEquals(original, buffer);
    }

    @Test public void invalidSelectedPngReleasesItsCopyAndScratchWithoutChangingCallerStorage() throws Exception {
        byte[] valid = PngImageTest.png(); byte[] corrupted = valid.clone(); corrupted[corrupted.length - 1] ^= 1;
        byte[][] invalid = {corrupted, Arrays.copyOf(valid, valid.length - 1), new byte[32]};
        ImageWorkset workset = new ImageWorkset(50_000);
        try (ImageWorkset.Reservation other = workset.reserve(13)) {
            for (byte[] png : invalid) {
                byte[] buffer = new byte[png.length + 20]; Arrays.fill(buffer, (byte) 91);
                System.arraycopy(png, 0, buffer, 7, png.length); byte[] original = buffer.clone();
                assertThrows(IllegalArgumentException.class, () -> PngImage.fromBytes(buffer, 7, png.length, workset));
                assertEquals(13, workset.reservedBytes()); assertArrayEquals(original, buffer);
            }
            try (PngImage next = PngImage.fromBytes(valid, 0, valid.length, workset)) {
                assertArrayEquals(valid, PngImageTest.read(next)); assertEquals(13 + valid.length, workset.reservedBytes());
            }
        }
        assertEquals(0, workset.reservedBytes());
    }
}
