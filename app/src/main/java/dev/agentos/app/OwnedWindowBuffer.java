package dev.agentos.app;

import android.hardware.HardwareBuffer;
import dev.agentos.core.ImageWorkset;
import java.util.Objects;

/**
 * Internal ownership of one native capture handle, not a capture or disclosure permission.
 * The caller validates geometry/format and retains all inputs if construction fails.
 * Successful construction transfers the handle and reservation to this owner.
 */
final class OwnedWindowBuffer implements AutoCloseable {
    private HardwareBuffer buffer;
    private ImageWorkset.Reservation reservation;
    private Runnable onActuallyClosed;
    private int readers;
    private boolean closed, closing, settled;

    OwnedWindowBuffer(HardwareBuffer buffer, ImageWorkset.Reservation reservation, Runnable onActuallyClosed) {
        HardwareBuffer source = Objects.requireNonNull(buffer, "buffer");
        ImageWorkset.Reservation capacity = Objects.requireNonNull(reservation, "reservation");
        Runnable completion = Objects.requireNonNull(onActuallyClosed, "onActuallyClosed");
        if (source.isClosed()) throw new IllegalArgumentException("Native window buffer is already closed.");
        this.buffer = source; this.reservation = capacity; this.onActuallyClosed = completion;
    }

    /** Each worker borrower must close its lease, including decode failure or cancellation. */
    synchronized ReadLease openRead() {
        if (closed) throw new IllegalStateException("Window buffer owner is closed.");
        if (readers == Integer.MAX_VALUE) throw new IllegalStateException("Too many window buffer readers.");
        ReadLease lease = new ReadLease(this);
        // Allocate first: a failed lease construction must not invent a reader.
        readers++;
        return lease;
    }

    /** Whether new reads have been disabled; an existing reader may still own the handle. */
    synchronized boolean isClosed() { return closed; }

    /** Native handle closed and known reservation settled, not proof of GPU memory reclamation. */
    synchronized boolean isActuallyClosed() { return settled; }

    @Override public void close() {
        synchronized (this) { closed = true; }
        releaseIfReady();
    }

    private void releaseIfReady() {
        HardwareBuffer target;
        ImageWorkset.Reservation capacity;
        synchronized (this) {
            if (!closed || readers != 0 || closing || settled) return;
            closing = true;
            target = buffer; capacity = reservation;
        }

        // Native work and arbitrary callback code never run under this monitor.
        Throwable failure = null;
        try { target.close(); }
        catch (RuntimeException | Error rejected) { failure = rejected; }
        boolean confirmedClosed = false;
        try { confirmedClosed = target.isClosed(); }
        catch (RuntimeException | Error uncertain) {
            if (failure == null) failure = uncertain;
        }
        if (!confirmedClosed) {
            synchronized (this) { closing = false; }
            // Keep the handle, reservation and callback owned. The capturer must
            // retain this owner/slot; a later explicit close can retry, never a
            // timeout-driven release or an unbounded automatic retry loop.
            throw new IllegalStateException("Native window buffer close was not confirmed; ownership is retained.", failure);
        }

        try { capacity.close(); }
        catch (RuntimeException | Error failedSettlement) {
            synchronized (this) { closing = false; }
            throw failedSettlement;
        }
        Runnable completion;
        synchronized (this) {
            settled = true; closing = false;
            buffer = null; reservation = null;
            completion = onActuallyClosed; onActuallyClosed = null;
        }
        // Marked consumed before invoking: a throwing or reentrant callback is
        // never repeated and cannot reopen the old owner's resource state.
        completion.run();
    }

    /**
     * Borrow only on the capture/encoding worker. The returned original handle is
     * for read-only use during this lease: do not close it, retain it after lease
     * close, transfer it to UI/renderer code, or create another owner around it.
     */
    static final class ReadLease implements AutoCloseable {
        private final OwnedWindowBuffer owner;
        private boolean closed;

        private ReadLease(OwnedWindowBuffer owner) { this.owner = owner; }

        HardwareBuffer buffer() {
            synchronized (owner) {
                if (closed) throw new IllegalStateException("Window buffer read lease is closed.");
                return owner.buffer;
            }
        }

        @Override public void close() {
            synchronized (owner) {
                if (closed) return;
                closed = true; owner.readers--;
            }
            owner.releaseIfReady();
        }
    }
}
