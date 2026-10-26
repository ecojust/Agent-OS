package dev.agentos.app;

import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional bounded numeric diagnostics. No UI content, identifiers or network data are accepted. */
final class PerformanceTrace {
    static final int CAPACITY = 4096;
    enum Stage {
        MAIN_QUEUE, MAIN_CALLBACK,
        SNAPSHOT_OBSERVE, SNAPSHOT_PREPARE, SNAPSHOT_FINAL_LAUNCH, SNAPSHOT_FINAL_MUTATION,
        STOP_RECEIVED, STOP_EPOCH_INVALIDATED, STOP_UI_APPLIED, DEVICE_DISPATCH_ATTEMPT,
        UI_PUBLICATION_QUEUE, UI_PUBLICATION_APPLIED
    }

    private static volatile Session active;
    private static final Span DISABLED = new Span(null, null, 0, false);

    private PerformanceTrace() { }

    /** Package-private: only explicit diagnostics can enable collection; normal startup never does. */
    static synchronized Session startDiagnostics() {
        if (active != null) throw new IllegalStateException("Performance diagnostics already active");
        Session session = new Session(SystemClock.elapsedRealtimeNanos());
        active = session;
        return session;
    }

    static Span begin(Stage stage) {
        Session session = active;
        return session == null ? DISABLED : new Span(session, stage, SystemClock.elapsedRealtimeNanos(),
                Looper.myLooper() == Looper.getMainLooper());
    }

    static void mark(Stage stage, long epoch, long dispatchAttempts) {
        begin(stage).finish(-1, -1, false, epoch, dispatchAttempts);
    }

    static final class Event {
        final Stage stage;
        final long startNanos, durationNanos, epoch, dispatchAttempts;
        final boolean mainThread, truncated;
        final int nodeCount, snapshotJsonBytes;

        Event(Span span, long end, int nodes, int bytes, boolean truncated, long epoch, long dispatchAttempts) {
            stage = span.stage;
            startNanos = span.started;
            durationNanos = Math.max(0, end - span.started);
            mainThread = span.mainThread;
            nodeCount = nodes;
            snapshotJsonBytes = bytes;
            this.truncated = truncated;
            this.epoch = epoch;
            this.dispatchAttempts = dispatchAttempts;
        }
    }

    static final class Span {
        private final Session session;
        private final Stage stage;
        private final long started;
        private final boolean mainThread;

        private Span(Session session, Stage stage, long started, boolean mainThread) {
            this.session = session; this.stage = stage; this.started = started; this.mainThread = mainThread;
        }

        void finish() { finish(-1, -1, false, -1, -1); }
        void finishSnapshot(int nodes, int bytes, boolean truncated, long epoch) {
            finish(nodes, bytes, truncated, epoch, -1);
        }
        private void finish(int nodes, int bytes, boolean truncated, long epoch, long dispatchAttempts) {
            // A late completion must not contaminate another diagnostic session.
            if (session != null && active == session) {
                session.add(new Event(this, SystemClock.elapsedRealtimeNanos(), nodes, bytes, truncated, epoch, dispatchAttempts));
            }
        }
    }

    static final class Session implements AutoCloseable {
        final long startedNanos;
        private final Event[] ring = new Event[CAPACITY];
        private int next, size;
        private long overwritten;

        private Session(long started) { startedNanos = started; }

        private synchronized void add(Event event) {
            if (active != this) return;
            if (size == CAPACITY) overwritten++; else size++;
            ring[next] = event;
            next = (next + 1) % CAPACITY;
        }

        synchronized List<Event> events() {
            List<Event> result = new ArrayList<>(size);
            int first = (next - size + CAPACITY) % CAPACITY;
            for (int index = 0; index < size; index++) result.add(ring[(first + index) % CAPACITY]);
            return Collections.unmodifiableList(result);
        }

        synchronized long overwrittenCount() { return overwritten; }

        @Override public void close() {
            synchronized (PerformanceTrace.class) { if (active == this) active = null; }
        }
    }
}
