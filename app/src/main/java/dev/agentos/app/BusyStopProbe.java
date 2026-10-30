package dev.agentos.app;

import dev.agentos.core.RunControl;

/** Opt-in, bounded numeric observation of one original task's platform call. */
final class BusyStopProbe {
    static final int CAPACITY = 16;
    static final int ENTER = 1, RETURN = 2, THROW = 3, SETTLED = 4;
    static final int STOP_OWNED_ENTRY = 5, CANCEL_RETURN = 6, STOP_RETURN = 7;
    private static volatile Session active;
    private static final Call DISABLED = new Call(null, 0, 0);

    private BusyStopProbe() { }

    static synchronized Session start(RunControl owner) {
        if (owner == null || active != null) throw new IllegalStateException("Busy-stop probe already active or owner missing");
        Session result = new Session(owner);
        active = result;
        return result;
    }

    static Call entering(RunControl owner, long epoch) {
        Session current = active;
        if (current == null || current.owner != owner) return DISABLED;
        Call call = new Call(current, epoch, owner.getDispatchAttempts());
        current.record(ENTER, epoch, call.attempts, 0);
        return call;
    }

    static final class Call {
        final Session session;
        final long epoch, attempts;
        Call(Session session, long epoch, long attempts) { this.session = session; this.epoch = epoch; this.attempts = attempts; }
        void returned(boolean accepted) { if (session != null) session.record(RETURN, epoch, attempts, accepted ? 1 : 0); }
        void threw() { if (session != null) session.record(THROW, epoch, attempts, 0); }
        void settled(RunControl owner) {
            if (session != null) session.record(SETTLED, epoch, attempts, owner.hasInFlightDispatch() ? 0 : 1);
        }
    }

    static Call disabled() { return DISABLED; }

    static void stopPoint(RunControl owner, int kind) {
        Session current = active;
        if (current == null || current.owner != owner) return;
        current.record(kind, owner.getExecutionEpoch(), owner.getDispatchAttempts(), owner.isCancelled() ? 1 : 0);
    }

    static final class Session implements AutoCloseable {
        private RunControl owner;
        private final long[][] values = new long[CAPACITY][5];
        private int count;
        private long overflow;
        Session(RunControl owner) { this.owner = owner; }
        private synchronized void record(int kind, long epoch, long attempts, long detail) {
            if (active != this || owner == null) return;
            if (count == CAPACITY) { overflow++; return; }
            long[] row = values[count++];
            row[0] = kind; row[1] = System.nanoTime(); row[2] = epoch; row[3] = attempts; row[4] = detail;
        }
        synchronized long[][] snapshot() {
            long[][] result = new long[count][5];
            for (int i = 0; i < count; i++) System.arraycopy(values[i], 0, result[i], 0, 5);
            return result;
        }
        synchronized long overflow() { return overflow; }
        synchronized boolean owns(RunControl expected) { return owner == expected && active == this; }
        @Override public void close() {
            synchronized (BusyStopProbe.class) {
                if (active == this) active = null;
                synchronized (this) { owner = null; }
            }
        }
    }
}
