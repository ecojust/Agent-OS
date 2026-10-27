package dev.agentos.app;

import android.os.Looper;

import dev.agentos.core.ImageReviewSession;

import java.util.Objects;

/** Process-wide admission shared by all internal image-review host implementations. */
final class ImageReviewPresentationCoordinator {
    /** Immutable publication also supports nonblocking reads from cancellation callbacks. */
    private static final class Claim {
        final Object owner;
        final ImageReviewSession session;
        final ImageReviewSession.HostToken token;

        Claim(Object owner, ImageReviewSession session, ImageReviewSession.HostToken token) {
            this.owner = owner;
            this.session = session;
            this.token = token;
        }
    }

    private static volatile Claim activeHost;

    private ImageReviewPresentationCoordinator() { }

    static boolean hasHost() { return activeHost != null; }

    static boolean isSessionOwnedByAnother(Object owner, ImageReviewSession session) {
        Claim current = activeHost;
        return current != null && current.owner != owner && current.session == session;
    }

    static boolean isCurrent(ImageReviewSession session, ImageReviewSession.HostToken token) {
        Claim current = activeHost;
        return current != null && current.session == session && current.token == token;
    }

    /**
     * Each host uses its own one-shot object identity. A rejected/unclaimed shell owns
     * nothing and must not invalidate a session already held by another implementation.
     */
    static ImageReviewSession.HostToken claim(Object owner, ImageReviewSession session) {
        requireMain();
        Objects.requireNonNull(owner);
        Objects.requireNonNull(session);
        if (activeHost != null) return null;
        ImageReviewSession.HostToken token = session.claimHost();
        if (token != null) {
            try { activeHost = new Claim(owner, session, token); }
            catch (RuntimeException | Error failedPublication) {
                // claimHost already moved this exact session into PREPARING.
                // Failed publication must not strand its PNG or approval wait,
                // nor revoke any other host or a subsequently replaced task.
                try { session.invalidateHost(token); }
                catch (RuntimeException | Error failedCleanup) {
                    if (failedCleanup != failedPublication) failedPublication.addSuppressed(failedCleanup);
                }
                throw failedPublication;
            }
        }
        return token;
    }

    /**
     * The owner first invalidates its session and detaches any displayed Drawable.
     * This releases only host admission, never a decoder/posted-result reservation.
     */
    static void release(Object owner) {
        requireMain();
        Claim current = activeHost;
        if (current != null && current.owner == owner) activeHost = null;
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("请在主线程管理图片审阅宿主。");
        }
    }
}
