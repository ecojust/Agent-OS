package dev.agentos.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    @Test public void finalBytesAndCopiesAreIsolatedAndCloseIsIdempotent() throws Exception {
        byte[] source = png(), expected = source.clone();
        PngImage image = PngImage.fromBytes(source);
        assertEquals(2, image.width); assertEquals(1, image.height);
        assertEquals(expected.length, image.encodedBytes); assertEquals(64, image.sha256.length());
        Arrays.fill(source, (byte) 0);
        byte[] copy = image.copyBytes(); Arrays.fill(copy, (byte) 0);
        assertArrayEquals(expected, image.copyBytes());
        image.close(); image.close(); assertTrue(image.isClosed());
        assertThrows(IllegalStateException.class, image::copyBytes);
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
}
