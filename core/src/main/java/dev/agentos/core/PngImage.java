package dev.agentos.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Owned, validated final PNG bytes. Content cannot change before close; copies belong to their
 * recipients and must be released separately. This object is not a screenshot authorization.
 * Validation, hashing and copying belong on a worker, never an Android UI callback.
 */
public final class PngImage implements AutoCloseable {
    public final int width, height, encodedBytes;
    public final String sha256;
    private byte[] bytes;

    private PngImage(byte[] bytes, int[] dimensions) {
        this.bytes = bytes;
        width = dimensions[0]; height = dimensions[1]; encodedBytes = bytes.length;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(64);
            for (byte value : hash) {
                text.append(Character.forDigit((value >>> 4) & 15, 16));
                text.append(Character.forDigit(value & 15, 16));
            }
            sha256 = text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Copies before validation, so a later caller mutation cannot change the reviewed PNG. */
    public static PngImage fromBytes(byte[] source) {
        if (source == null || source.length == 0 || source.length > ImageAttachment.MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("PNG exceeds the supported encoded size.");
        }
        byte[] copy = source.clone();
        try { return new PngImage(copy, PngValidator.validate(copy)); }
        catch (RuntimeException failure) { Arrays.fill(copy, (byte) 0); throw failure; }
    }

    /** A bounded temporary copy for decoding or the final transport; never put it in logs. */
    public synchronized byte[] copyBytes() {
        if (bytes == null) throw new IllegalStateException("PNG has been released.");
        return bytes.clone();
    }

    public synchronized boolean isClosed() { return bytes == null; }

    /** Releases only this owned buffer, not copies held by renderers or a remote recipient. */
    @Override public synchronized void close() {
        if (bytes != null) { Arrays.fill(bytes, (byte) 0); bytes = null; }
    }
}
