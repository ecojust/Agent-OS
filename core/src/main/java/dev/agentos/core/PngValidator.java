package dev.agentos.core;

import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Shared bounded single-frame PNG validation. Does not decode a full image or retain its input. */
final class PngValidator {
    static final int SCRATCH_BYTES = 8192;
    private static final int MAX_EDGE = ImageAttachment.MAX_EDGE;
    private static final int MAX_PIXELS = ImageAttachment.MAX_PIXELS;
    private static final int MAX_DECODED_BYTES = ImageAttachment.MAX_DECODED_BYTES;
    private PngValidator() { }

    static int[] validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > ImageAttachment.MAX_ENCODED_BYTES) throw invalid();
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < 57) throw invalid();
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) throw invalid();
        int offset = 8, width = 0, height = 0, channels = 0, metadataSeen = 0;
        boolean header = false, image = false, ended = false;
        Scanlines scanlines = null;
        try {
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) throw invalid();
            long lengthValue = unsignedInt(bytes, offset);
            if (lengthValue > bytes.length - offset - 12L) throw invalid();
            int length = (int) lengthValue;
            int type = intValue(bytes, offset + 4);
            CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
            if (crc.getValue() != unsignedInt(bytes, offset + 8 + length)) throw invalid();
            if (!header && type != 0x49484452) throw invalid();
            if (type == 0x49484452) {
                if (header || length != 13) throw invalid();
                width = intValue(bytes, offset + 8); height = intValue(bytes, offset + 12);
                int color = bytes[offset + 17] & 255;
                if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE
                        || (long) width * height > MAX_PIXELS || bytes[offset + 16] != 8
                        || (color != 2 && color != 6) || bytes[offset + 18] != 0
                        || bytes[offset + 19] != 0 || bytes[offset + 20] != 0) throw invalid();
                channels = color == 6 ? 4 : 3; header = true;
            } else if (type == 0x49444154) {
                if (scanlines == null) scanlines = new Scanlines(width, height, channels);
                scanlines.accept(bytes, offset + 8, length); image = true;
            } else if (type == 0x49454e44) {
                if (!image || length != 0 || offset + 12 != bytes.length) throw invalid();
                ended = true;
            } else {
                // Permit only fixed-size rendering metadata before IDAT. In particular,
                // APNG frames and compressed profiles/text cannot hide more decoded data.
                if (image) throw invalid();
                int metadataBit;
                if (type == 0x73524742 && length == 1 && (bytes[offset + 8] & 255) <= 3) metadataBit = 1;
                else if (type == 0x67414d41 && length == 4 && unsignedInt(bytes, offset + 8) != 0) metadataBit = 2;
                else if (type == 0x6348524d && length == 32) metadataBit = 4;
                else if (type == 0x70485973 && length == 9 && (bytes[offset + 16] & 255) <= 1) metadataBit = 8;
                else if (type == 0x73424954 && length == channels) {
                    for (int i = 0; i < channels; i++) if ((bytes[offset + 8 + i] & 255) < 1 || (bytes[offset + 8 + i] & 255) > 8) throw invalid();
                    metadataBit = 16;
                } else throw invalid();
                if ((metadataSeen & metadataBit) != 0) throw invalid();
                metadataSeen |= metadataBit;
            }
            offset += length + 12;
        }
        if (!ended || (long) width * height * channels > MAX_DECODED_BYTES) throw invalid();
        if (scanlines == null) throw invalid();
        scanlines.finish();
        return new int[]{width, height};
        } finally { if (scanlines != null) scanlines.close(); }
    }

    /**
     * Feeds validated IDAT slices directly from the caller's PNG. No concatenated
     * compressed copy or full decoded image is allocated, even across chunk boundaries.
     */
    private static final class Scanlines implements AutoCloseable {
        private final long rowBytes, expected;
        private final byte[] chunk = new byte[SCRATCH_BYTES];
        // Allocate Java scratch first: if allocation fails, no native inflater
        // has yet been created without a fully constructed owner to close it.
        private final Inflater inflater = new Inflater();
        private long total;

        Scanlines(int width, int height, int channels) {
            rowBytes = (long) width * channels + 1;
            expected = rowBytes * height;
        }

        void accept(byte[] source, int offset, int inputLength) {
            // Empty IDAT chunks are legal, including after the completed zlib stream.
            if (inputLength == 0) return;
            if (inflater.finished() || !inflater.needsInput()) throw invalid();
            inflater.setInput(source, offset, inputLength);
            try {
                while (!inflater.finished()) {
                    int length = inflater.inflate(chunk);
                    if (total + length > expected) throw invalid();
                    for (int i = 0; i < length; i++) {
                        if ((total + i) % rowBytes == 0 && (chunk[i] & 255) > 4) throw invalid();
                    }
                    total += length;
                    if (length == 0 && !inflater.finished()) {
                        if (inflater.needsDictionary() || !inflater.needsInput()) throw invalid();
                        return; // The next IDAT may contain the rest of a header or scanline.
                    }
                }
                // Reject trailing bytes in this slice; a later nonempty IDAT is also rejected.
                if (inflater.getRemaining() != 0) throw invalid();
            } catch (DataFormatException badStream) { throw invalid(); }
        }

        void finish() {
            if (!inflater.finished() || total != expected || inflater.getRemaining() != 0) throw invalid();
        }

        @Override public void close() {
            try { Arrays.fill(chunk, (byte) 0); }
            finally { inflater.end(); }
        }
    }

    private static int intValue(byte[] value, int offset) {
        return (value[offset] & 255) << 24 | (value[offset + 1] & 255) << 16 | (value[offset + 2] & 255) << 8 | value[offset + 3] & 255;
    }
    private static long unsignedInt(byte[] value, int offset) { return intValue(value, offset) & 0xffffffffL; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("图像类型、内容或大小无效。"); }
}
