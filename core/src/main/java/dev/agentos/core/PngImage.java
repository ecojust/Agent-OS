package dev.agentos.core;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Owned, validated final PNG bytes. Readers share its immutable encoding without a preview
 * copy. Closing the owner blocks new readers; existing readers retain the bytes and reservation
 * until they close. This object is not a screenshot authorization. Validation and hashing
 * belong on a worker, never an Android UI callback.
 */
public final class PngImage implements AutoCloseable {
    public final int width, height, encodedBytes;
    public final String sha256;
    private byte[] bytes;
    private ImageWorkset.Reservation reservation;
    private int readers;
    private boolean closed;

    private PngImage(byte[] bytes, int[] dimensions, ImageWorkset.Reservation reservation) {
        this.bytes = bytes;
        this.reservation = reservation;
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

    /**
     * Copies before validation, so later caller mutation cannot change the reviewed PNG.
     * The source array belongs to the caller and is not charged to this owner's reservation.
     */
    public static PngImage fromBytes(byte[] source) {
        return fromBytes(source, ImageWorkset.process());
    }

    static PngImage fromBytes(byte[] source, ImageWorkset workset) {
        return fromBytes(source, workset, value -> value.clone());
    }

    /** Narrow allocation seam for admission-before-copy and allocation-error tests only. */
    @FunctionalInterface interface ByteArrayCopier { byte[] copy(byte[] source); }

    static PngImage fromBytes(byte[] source, ImageWorkset workset, ByteArrayCopier copier) {
        if (source == null || source.length == 0 || source.length > ImageAttachment.MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("PNG exceeds the supported encoded size.");
        }
        Objects.requireNonNull(workset, "workset"); Objects.requireNonNull(copier, "copier");
        ImageWorkset.Reservation owned = workset.reserve(source.length);
        byte[] copy = null;
        boolean transferred = false;
        try (ImageWorkset.Reservation scratch = workset.reserve(PngValidator.SCRATCH_BYTES)) {
            copy = copier.copy(source);
            if (copy == source) {
                copy = null; // A bad test copier must not make cleanup erase the caller's array.
                throw new IllegalArgumentException("PNG requires an independent owned encoding.");
            }
            if (copy == null || copy.length != source.length) {
                throw new IllegalArgumentException("PNG copy has an invalid length.");
            }
            PngImage image = new PngImage(copy, PngValidator.validate(copy), owned);
            transferred = true;
            return image;
        } finally {
            // Also covers allocation errors; only a fully constructed owner can take the token.
            if (!transferred) {
                if (copy != null) Arrays.fill(copy, (byte) 0);
                owned.close();
            }
        }
    }

    /** The recipient must close this read lease, including when decoding fails or is cancelled. */
    public synchronized ReadLease openRead() {
        if (closed) throw new IllegalStateException("PNG has been released.");
        if (readers == Integer.MAX_VALUE) throw new IllegalStateException("Too many PNG readers.");
        ReadLease reader = new ReadLease(this);
        readers++;
        return reader;
    }

    public synchronized boolean isClosed() { return closed; }

    /** Prevents new readers; does not erase bytes still owned by a running decoder. */
    @Override public synchronized void close() {
        closed = true;
        releaseIfUnused();
    }

    private void releaseIfUnused() {
        if (!closed || readers != 0 || bytes == null) return;
        Arrays.fill(bytes, (byte) 0); bytes = null;
        reservation.close(); reservation = null;
    }

    /**
     * Independent position over one immutable encoding. It never exposes the backing array.
     * Arrays supplied to read(), and any buffers made by consumers, remain consumer-owned.
     */
    public static final class ReadLease extends InputStream {
        private final PngImage owner;
        private int position, mark;
        private boolean closed;

        private ReadLease(PngImage owner) { this.owner = owner; }

        @Override public int read() throws IOException {
            synchronized (owner) {
                requireOpen();
                return position == owner.encodedBytes ? -1 : owner.bytes[position++] & 255;
            }
        }

        @Override public int read(byte[] output, int offset, int length) throws IOException {
            Objects.requireNonNull(output, "output");
            if (offset < 0 || length < 0 || offset > output.length - length) throw new IndexOutOfBoundsException();
            synchronized (owner) {
                requireOpen();
                if (length == 0) return 0;
                if (position == owner.encodedBytes) return -1;
                int count = Math.min(length, owner.encodedBytes - position);
                System.arraycopy(owner.bytes, position, output, offset, count);
                position += count;
                return count;
            }
        }

        @Override public long skip(long count) throws IOException {
            synchronized (owner) {
                requireOpen();
                int skipped = (int) Math.min(Math.max(0, count), owner.encodedBytes - position);
                position += skipped;
                return skipped;
            }
        }

        @Override public int available() throws IOException {
            synchronized (owner) { requireOpen(); return owner.encodedBytes - position; }
        }

        @Override public boolean markSupported() { return true; }
        @Override public void mark(int readLimit) {
            synchronized (owner) { if (!closed) mark = position; }
        }
        @Override public void reset() throws IOException {
            synchronized (owner) { requireOpen(); position = mark; }
        }

        @Override public void close() {
            synchronized (owner) {
                if (closed) return;
                closed = true;
                owner.readers--;
                owner.releaseIfUnused();
            }
        }

        private void requireOpen() throws IOException {
            if (closed) throw new IOException("PNG read lease has been closed.");
        }
    }
}
