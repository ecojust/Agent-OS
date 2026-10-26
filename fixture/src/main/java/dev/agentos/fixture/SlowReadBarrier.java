package dev.agentos.fixture;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A controllable remote-root reply, present only in the separate offline fixture APK. */
final class SlowReadBarrier {
    private static volatile Object attachedOwner;
    private static volatile Session active;

    private SlowReadBarrier() { }

    static synchronized void attach(Object owner) { attachedOwner = owner; }

    static synchronized void detach(Object owner) {
        if (attachedOwner != owner) return;
        attachedOwner = null;
        Session previous = active;
        if (previous != null) previous.release.countDown();
    }

    static synchronized Bundle arm(String token) {
        if (attachedOwner == null) throw new IllegalStateException("The slow fixture must be foreground before arming");
        if (active != null && active.release.getCount() != 0) throw new IllegalStateException("A barrier is already armed");
        active = new Session(token);
        return status(token);
    }

    static Bundle release(String token) {
        Session session = active;
        if (session != null && session.token.equals(token)) session.release.countDown();
        return status(token);
    }

    static synchronized Bundle clear(String token) {
        Bundle result = release(token);
        if (active != null && active.token.equals(token)) active = null;
        return result;
    }

    static Bundle finish(String token) {
        release(token);
        Session session = active;
        if (session != null && session.token.equals(token) && session.claimed.get()) {
            try {
                if (!session.finished.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture root did not return after release");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fixture completion wait was interrupted", interrupted);
            }
        }
        return status(token);
    }

    static Bundle status(String token) {
        Bundle result = new Bundle();
        Session session = active;
        boolean exists = session != null && session.token.equals(token);
        result.putBoolean("exists", exists);
        if (!exists) return result;
        result.putBoolean("claimed", session.claimed.get());
        result.putBoolean("released", session.release.getCount() == 0);
        result.putBoolean("returned", session.returned);
        result.putBoolean("expired", session.expired);
        result.putLong("startedNanos", session.startedNanos);
        result.putLong("finishedNanos", session.finishedNanos);
        return result;
    }

    static void beforeRootReply(Context context, Object owner) {
        if (attachedOwner != owner) return;
        Session session = active;
        if (session == null || session.release.getCount() == 0 || !session.claimed.compareAndSet(false, true)) return;
        session.startedNanos = SystemClock.elapsedRealtimeNanos();
        context.sendBroadcast(new Intent("dev.agentos.fixture.FINAL_ROOT_BLOCKED")
                .setPackage("dev.agentos.app").putExtra("token", session.token)
                .putExtra("startedNanos", session.startedNanos));
        try {
            // The test releases this from a different Binder thread. This limit
            // prevents a failed test from indefinitely holding the fixture UI.
            session.expired = !session.release.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            session.expired = true;
        } finally {
            session.finishedNanos = SystemClock.elapsedRealtimeNanos();
            session.returned = true;
            session.finished.countDown();
        }
    }

    private static final class Session {
        final String token;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean claimed = new AtomicBoolean();
        volatile boolean returned, expired;
        volatile long startedNanos, finishedNanos;
        Session(String token) { this.token = token; }
    }
}
