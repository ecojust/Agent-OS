package dev.agentos.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.*;

/** Chunk boundaries must not change the accepted single PNG stream or its output limits. */
public final class PngValidatorStreamingTest {
    private static final byte[] EMPTY = new byte[0];

    @Test public void everyTwoWaySplitIncludingZlibHeaderAndTrailerIsAccepted() throws Exception {
        byte[] rows = rows(17, 4, 3), compressed = compress(rows);
        for (int split = 0; split <= compressed.length; split++) {
            byte[] image = png(17, 4, 2, EMPTY,
                    Arrays.copyOfRange(compressed, 0, split), EMPTY,
                    Arrays.copyOfRange(compressed, split, compressed.length), EMPTY);
            byte[] before = image.clone();
            assertArrayEquals("IDAT split " + split, new int[]{17, 4}, PngValidator.validate(image));
            assertArrayEquals("validation does not modify its caller's PNG", before, image);
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png(17, 4, 2, compressed)));
        assertNotNull(decoded); assertEquals(17, decoded.getWidth()); assertEquals(4, decoded.getHeight());
    }

    @Test public void singleByteChunksAndEmptyChunksPreserveRgbaRowsBeyondScratchBuffer() throws Exception {
        byte[] pixels = rows(37, 73, 4), compressed = compress(pixels);
        assertTrue("exercise more than one 8 KiB output buffer", pixels.length > 8192);
        List<byte[]> slices = new ArrayList<>();
        slices.add(EMPTY);
        for (int index = 0; index < compressed.length; index++) {
            slices.add(new byte[]{compressed[index]});
            if (index % 17 == 0) slices.add(EMPTY);
        }
        slices.add(EMPTY);
        byte[] image = png(37, 73, 6, slices.toArray(new byte[0][]));
        assertArrayEquals(new int[]{37, 73}, PngValidator.validate(image));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
        assertNotNull(decoded); assertEquals(37, decoded.getWidth()); assertEquals(73, decoded.getHeight());
    }

    @Test public void zeroLengthChunksCannotSubstituteForACompleteZlibStream() throws Exception {
        assertInvalid(png(2, 1, 2, EMPTY, EMPTY, EMPTY));
        byte[] compressed = compress(rows(2, 1, 3));
        assertArrayEquals(new int[]{2, 1}, PngValidator.validate(png(2, 1, 2, compressed, EMPTY, EMPTY)));
    }

    @Test public void trailingBytesAreRejectedWithinOrAfterTheFinalIdat() throws Exception {
        byte[] compressed = compress(rows(2, 1, 3));
        for (byte[] suffix : new byte[][]{{0}, {1}, {0, 0, 0, 0}, {(byte) 0x78, (byte) 0x9c}}) {
            assertInvalid(png(2, 1, 2, concatenate(compressed, suffix)));
            assertInvalid(png(2, 1, 2, compressed, EMPTY, suffix));
            int split = compressed.length - 1;
            assertInvalid(png(2, 1, 2, Arrays.copyOf(compressed, split), EMPTY,
                    concatenate(Arrays.copyOfRange(compressed, split, compressed.length), suffix)));
        }
    }

    @Test public void concatenatedZlibStreamsAreNeverAcceptedAsOneImage() throws Exception {
        byte[] compressed = compress(rows(2, 1, 3));
        assertInvalid(png(2, 1, 2, compressed, compressed));
        assertInvalid(png(2, 1, 2, concatenate(compressed, compressed)));
        assertInvalid(png(2, 1, 2, compressed, EMPTY, new byte[]{compressed[0]},
                Arrays.copyOfRange(compressed, 1, compressed.length)));
    }

    @Test public void everyIncompleteStreamPrefixIncludingMissingAdlerChecksumIsRejected() throws Exception {
        byte[] compressed = compress(rows(2, 1, 3));
        for (int length = 0; length < compressed.length; length++) {
            byte[] prefix = Arrays.copyOf(compressed, length);
            int split = Math.min(2, length);
            assertInvalid(png(2, 1, 2, Arrays.copyOf(prefix, split), EMPTY,
                    Arrays.copyOfRange(prefix, split, prefix.length), EMPTY));
        }
    }

    @Test public void decodedByteBudgetAppliesAcrossAllChunksEvenWithValidCrc() throws Exception {
        int width = 20, height = 5, expected = (width * 3 + 1) * height;
        for (int excess : new int[]{1, 8192, 65536}) {
            byte[] compressed = compress(new byte[expected + excess]);
            assertInvalid(png(width, height, 2, oneByteSlices(compressed)));
        }
        byte[] shortRows = compress(new byte[expected - 1]);
        assertInvalid(png(width, height, 2, oneByteSlices(shortRows)));
    }

    @Test public void scanlineFiltersAreCheckedAtTheirGlobalOffsetsAcrossOutputAndInputChunks() throws Exception {
        int width = 1280, height = 4, stride = width * 3 + 1;
        byte[] pixels = rows(width, height, 3);
        assertArrayEquals(new int[]{width, height}, PngValidator.validate(png(width, height, 2, compress(pixels))));
        for (int row : new int[]{0, 2, 3}) {
            byte[] invalidRows = pixels.clone(); invalidRows[row * stride] = (byte) 255;
            byte[] compressed = compress(invalidRows);
            assertInvalid(png(width, height, 2, oneByteSlices(compressed)));
        }
    }

    @Test public void laterIdatCrcFailureAndInvalidDeflateCannotEscapePartialValidation() throws Exception {
        byte[] compressed = compress(rows(8, 2, 3));
        byte[] first = Arrays.copyOf(compressed, 2), later = Arrays.copyOfRange(compressed, 2, compressed.length);
        byte[] badCrc = png(8, 2, 2, first, EMPTY, later);
        int lastIdat = 33 + 12 + first.length + 12;
        badCrc[lastIdat + 8 + later.length] ^= 1;
        assertInvalid(badCrc);
        byte[] brokenStream = compressed.clone(); brokenStream[compressed.length - 1] ^= 1;
        assertInvalid(png(8, 2, 2, oneByteSlices(brokenStream)));
        assertInvalid(png(8, 2, 2, new byte[]{0, 0}, later));
    }

    @Test public void presetDictionaryStreamsRemainUnsupported() throws Exception {
        Deflater deflater = new Deflater();
        byte[] dictionary = "synthetic PNG test dictionary".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try {
            deflater.setDictionary(dictionary);
            try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed, deflater)) {
                stream.write(rows(2, 1, 3));
            }
        } finally { deflater.end(); }
        assertInvalid(png(2, 1, 2, oneByteSlices(compressed.toByteArray())));
    }

    @Test public void structuralAndMetadataRestrictionsStillApplyAfterStreamingBegins() throws Exception {
        byte[] compressed = compress(rows(2, 1, 3));
        ByteArrayOutputStream separated = prefix(2, 1, 2);
        chunk(separated, "IDAT", Arrays.copyOf(compressed, 2));
        chunk(separated, "sRGB", new byte[]{0});
        chunk(separated, "IDAT", Arrays.copyOfRange(compressed, 2, compressed.length));
        chunk(separated, "IEND", EMPTY);
        assertInvalid(separated.toByteArray());
        for (String type : new String[]{"acTL", "fcTL", "fdAT", "iCCP", "zTXt", "tEXt"}) {
            ByteArrayOutputStream unsupported = prefix(2, 1, 2);
            chunk(unsupported, type, EMPTY); chunk(unsupported, "IDAT", compressed);
            chunk(unsupported, "IEND", EMPTY); assertInvalid(unsupported.toByteArray());
        }
        byte[] image = png(2, 1, 2, compressed);
        assertInvalid(Arrays.copyOf(image, image.length - 12));
        assertInvalid(concatenate(image, new byte[]{0}));
    }

    @Test public void failedPartialValidationDoesNotMutateCallerOrPoisonTheNextImage() throws Exception {
        byte[] compressed = compress(rows(2, 1, 3));
        byte[] invalid = png(2, 1, 2, Arrays.copyOf(compressed, 2), new byte[]{0, 0, 0});
        byte[] before = invalid.clone();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertInvalid(invalid); assertArrayEquals(before, invalid);
            assertArrayEquals(new int[]{2, 1}, PngValidator.validate(png(2, 1, 2, compressed)));
        }
    }

    private static void assertInvalid(byte[] image) {
        assertThrows(IllegalArgumentException.class, () -> PngValidator.validate(image));
    }

    private static byte[] rows(int width, int height, int channels) {
        int stride = width * channels + 1;
        byte[] rows = new byte[stride * height]; new Random(49117L).nextBytes(rows);
        for (int row = 0; row < height; row++) rows[row * stride] = (byte) (row % 5);
        return rows;
    }

    private static byte[] compress(byte[] rows) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output)) { stream.write(rows); }
        return output.toByteArray();
    }

    private static byte[][] oneByteSlices(byte[] input) {
        byte[][] slices = new byte[input.length][];
        for (int index = 0; index < input.length; index++) slices[index] = new byte[]{input[index]};
        return slices;
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length); return combined;
    }

    private static byte[] png(int width, int height, int color, byte[]... slices) throws IOException {
        ByteArrayOutputStream output = prefix(width, height, color);
        for (byte[] slice : slices) chunk(output, "IDAT", slice);
        chunk(output, "IEND", EMPTY); return output.toByteArray();
    }

    private static ByteArrayOutputStream prefix(int width, int height, int color) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(header);
        fields.writeInt(width); fields.writeInt(height); fields.write(new byte[]{8, (byte) color, 0, 0, 0});
        chunk(output, "IHDR", header.toByteArray()); return output;
    }

    private static void chunk(ByteArrayOutputStream output, String type, byte[] bytes) throws IOException {
        DataOutputStream fields = new DataOutputStream(output);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        fields.writeInt(bytes.length); fields.write(name); fields.write(bytes);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(bytes); fields.writeInt((int) crc.getValue());
    }
}
