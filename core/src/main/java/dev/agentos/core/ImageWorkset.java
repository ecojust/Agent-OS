package dev.agentos.core;

/**
 * Admission for known, explicitly owned image payload capacities in this process.
 * Reservations precede allocations and remain held until that owner finishes using
 * its buffer. This is not a bound on physical memory, GC, platform decoders, native
 * allocations, renderer copies or caller-owned input/output arrays.
 */
public final class ImageWorkset {
    private static final long DEFAULT_LIMIT_BYTES = 24L * 1024 * 1024;
    private static final ImageWorkset PROCESS = new ImageWorkset(DEFAULT_LIMIT_BYTES);
    private final long limit;
    private long reserved;

    /** Production callers share this budget, including old work still completing cancellation. */
    public static ImageWorkset process() { return PROCESS; }

    /** Small isolated budgets are available only to same-package core tests. */
    ImageWorkset(long limitBytes) {
        if (limitBytes <= 0) throw new IllegalArgumentException("Image payload budget must be positive.");
        limit = limitBytes;
    }

    public synchronized Reservation reserve(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("Image payload reservation must be positive.");
        if (bytes > limit - reserved) throw new IllegalStateException("Image payload budget is in use.");
        // Construct before changing accounting: an allocation failure owns no reservation.
        Reservation reservation = new Reservation(this, bytes);
        reserved += bytes;
        return reservation;
    }

    public synchronized long reservedBytes() { return reserved; }
    public long limitBytes() { return limit; }

    /** Only the owner that obtained this token may release its capacity. */
    public static final class Reservation implements AutoCloseable {
        private final ImageWorkset owner;
        private final long bytes;
        private boolean closed;

        private Reservation(ImageWorkset owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        public long bytes() { return bytes; }

        @Override public void close() {
            synchronized (owner) {
                if (closed) return;
                closed = true;
                owner.reserved -= bytes;
            }
        }
    }
}
