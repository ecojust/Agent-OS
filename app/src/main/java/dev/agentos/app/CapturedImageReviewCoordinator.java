package dev.agentos.app;

import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;

import com.google.gson.JsonObject;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.LocalToolResult;
import dev.agentos.core.PngImage;
import dev.agentos.core.RunControl;
import dev.agentos.core.ToolRequest;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal, worker-only bridge from one bound capture receipt through actual local
 * image review to the ordinary final target guard. It has no capture, network,
 * Controller or advertised-tool entry point. General page eligibility remains a
 * separate prerequisite; local approval is not proof that arbitrary pixels are safe.
 */
final class CapturedImageReviewCoordinator {
    private static final long POLL_MILLIS = 40;
    private static final long FINAL_MILLIS = 5_000;

    enum Failure {
        WRONG_THREAD, CONTEXT_CHANGED, SHOW_FAILED, APPROVAL_REJECTED,
        REVIEW_EXPIRED, HOST_CLOSED, REMOVAL_TIMEOUT, FINAL_CHECK_EXPIRED
    }

    static final class ReviewException extends Exception {
        final Failure failure;
        ReviewException(Failure failure) {
            super("Local image review unavailable: " + failure.name());
            this.failure = failure;
        }
    }

    /** Test observation only: the host and session were created by the actual service. */
    interface HostObserver {
        void onShown(SecureImageReviewOverlay host, ImageReviewSession session);
    }

    private CapturedImageReviewCoordinator() { }

    /** Consumes receipt ownership at entry, including wrong-thread or failed construction. */
    static LocalToolResult reviewPlatform(ToolRequest original, RunControl control,
            AndroidDeviceTools tools, WindowPngConverter.CapturedPng captured) throws Exception {
        return review(original, control, tools, captured, Origin.PLATFORM, null,
                (input, options) -> BitmapFactory.decodeStream(input, null, options),
                WindowManager::removeViewImmediate);
    }

    /** Synthetic receipts only; the secure host, original binding and final guards remain real. */
    static LocalToolResult reviewSyntheticForTesting(ToolRequest original, RunControl control,
            AndroidDeviceTools tools, WindowPngConverter.CapturedPng captured) throws Exception {
        return reviewSyntheticForTesting(original, control, tools, captured, null,
                (input, options) -> BitmapFactory.decodeStream(input, null, options),
                WindowManager::removeViewImmediate);
    }

    /** No replaceable host factory or fabricated approval is accepted by this seam. */
    static LocalToolResult reviewSyntheticForTesting(ToolRequest original, RunControl control,
            AndroidDeviceTools tools, WindowPngConverter.CapturedPng captured, HostObserver observer,
            ImagePreviewLoader.Decoder decoder, SecureImageReviewOverlay.WindowRemover remover) throws Exception {
        return review(original, control, tools, captured, Origin.SYNTHETIC, observer, decoder, remover);
    }

    private enum Origin { PLATFORM, SYNTHETIC }

    private static LocalToolResult review(ToolRequest original, RunControl control,
            AndroidDeviceTools tools, WindowPngConverter.CapturedPng captured, Origin origin,
            HostObserver observer, ImagePreviewLoader.Decoder decoder,
            SecureImageReviewOverlay.WindowRemover remover) throws Exception {
        Objects.requireNonNull(captured, "captured");
        PngImage local = null;
        LocalToolResult output = null;
        AndroidDeviceTools.CapturedReviewContext context;
        long finalDeadline;
        JsonObject metadata;
        try {
            ImageReviewSession session = null;
            ShowTicket show = null;
            try {
                requireWorker();
                Objects.requireNonNull(decoder, "decoder");
                Objects.requireNonNull(remover, "remover");
                // The receipt checks the complete original ToolRequest and exact
                // adapter/control before reading the same saved configuration lease.
                context = captured.prepareReviewContext(original, tools, control);
                checkContext(original, control, context);
                local = origin == Origin.PLATFORM ? captured.claimForReview(original, tools, control)
                        : captured.claimForTesting(original, tools, control);
                // Retain the local reference until construction succeeds. If an
                // allocation fails before constructor entry, finally still owns it;
                // a constructor failure itself also closes the idempotent PNG owner.
                session = new ImageReviewSession(context.binding, local, control, context::currentBinding);
                local = null;
                checkReview(original, control, context, session);
                show = new ShowTicket(original, control, context, session, observer, decoder, remover);
                show.enqueue();
                SecureImageReviewOverlay host = awaitReview(show.created, original, control, context, session);
                ImageReviewSession.ApprovalToken approval = awaitReview(show.approved,
                        original, control, context, session);
                checkReview(original, control, context, session);
                local = session.consume(approval);
                // This deadline is created once from actual local consume, before
                // removal, and is never supplied by a model or renewed on failure.
                finalDeadline = SystemClock.elapsedRealtime() + FINAL_MILLIS;
                if (local == null) throw closedReview(session);
                checkFinal(original, control, context, finalDeadline);
                CompletableFuture<Void> removed = host.whenRemoved();
                try {
                    host.close();
                    awaitRemoval(removed, original, control, context, finalDeadline);
                } finally {
                    // Cancelling this dependent never cancels host cleanup or
                    // releases its admission while the actual View is attached.
                    removed.cancel(false);
                }
                checkFinal(original, control, context, finalDeadline);
                captured.validateAfterReview(original, tools, control, context, finalDeadline);
                checkFinal(original, control, context, finalDeadline);
                metadata = metadata(captured, origin);
            } finally {
                // No RunControl cancellation hook is replaced. Invalidating the
                // session is synchronous; queued/in-flight host cleanup never waits
                // for main, a decoder, Binder, or another host's device gate.
                try {
                    if (session != null) session.close();
                } finally {
                    try { if (show != null) show.abandon(); }
                    finally { captured.close(); }
                }
            }
            // This success path has acknowledged the exact UI removal and released
            // its admission; that is not a claim that an in-flight decoder or GPU
            // has drained. Their existing owners retain the shared resource slots.
            // withPng owns the sole PNG at invocation, including factory failure.
            checkFinal(original, control, context, finalDeadline);
            PngImage transferred = local;
            local = null;
            output = LocalToolResult.withPng(metadata, transferred);
            checkFinal(original, control, context, finalDeadline);
            LocalToolResult returned = output;
            output = null;
            return returned;
        } finally {
            try { if (local != null) local.close(); }
            finally { if (output != null) output.close(); }
        }
    }

    private static JsonObject metadata(WindowPngConverter.CapturedPng captured, Origin origin) {
        JsonObject data = new JsonObject();
        data.addProperty("revision", captured.revision);
        data.addProperty("width", captured.width);
        data.addProperty("height", captured.height);
        data.addProperty("encodedBytes", captured.encodedBytes);
        data.addProperty("sha256", captured.sha256);
        data.addProperty("captureId", captured.captureId);
        data.addProperty("windowId", captured.windowId);
        data.addProperty("displayId", captured.displayId);
        data.addProperty("provenance", origin == Origin.PLATFORM ? "platform_window" : "synthetic_test");
        JsonObject result = new JsonObject();
        result.addProperty("ok", true); result.add("data", data);
        return result;
    }

    private static void requireWorker() throws ReviewException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new ReviewException(Failure.WRONG_THREAD);
    }

    private static void checkContext(ToolRequest original, RunControl control,
            AndroidDeviceTools.CapturedReviewContext context) throws Exception {
        // The original epoch is never reread from control to revive an old request.
        control.checkExecutionEpoch(original.executionEpoch);
        if (context.currentBinding() != context.binding) throw new ReviewException(Failure.CONTEXT_CHANGED);
    }

    private static void checkReview(ToolRequest original, RunControl control,
            AndroidDeviceTools.CapturedReviewContext context, ImageReviewSession session) throws Exception {
        checkContext(original, control, context);
        switch (session.state()) {
            case WAITING_HOST: case PREPARING: case PRESENTED: case APPROVED:
                if (session.remainingMillis() > 0) return;
                break;
            default: break;
        }
        throw closedReview(session);
    }

    private static ReviewException closedReview(ImageReviewSession session) {
        ImageReviewSession.Reason reason = session.reason();
        if (reason == ImageReviewSession.Reason.REJECTED) return new ReviewException(Failure.APPROVAL_REJECTED);
        if (reason == ImageReviewSession.Reason.EXPIRED) return new ReviewException(Failure.REVIEW_EXPIRED);
        if (reason == ImageReviewSession.Reason.CONTEXT_CHANGED || reason == ImageReviewSession.Reason.CLOCK_CHANGED)
            return new ReviewException(Failure.CONTEXT_CHANGED);
        return new ReviewException(Failure.HOST_CLOSED);
    }

    private static void checkFinal(ToolRequest original, RunControl control,
            AndroidDeviceTools.CapturedReviewContext context, long deadline) throws Exception {
        checkContext(original, control, context);
        if (SystemClock.elapsedRealtime() >= deadline) throw new ReviewException(Failure.FINAL_CHECK_EXPIRED);
    }

    private static <T> T awaitReview(CompletableFuture<T> future, ToolRequest original, RunControl control,
            AndroidDeviceTools.CapturedReviewContext context, ImageReviewSession session) throws Exception {
        for (;;) {
            checkReview(original, control, context, session);
            try {
                T result = future.get(Math.min(POLL_MILLIS, Math.max(1, session.remainingMillis())), TimeUnit.MILLISECONDS);
                checkReview(original, control, context, session);
                return result;
            } catch (TimeoutException waiting) { /* Bounded original epoch/configuration checks. */ }
            catch (ExecutionException failed) {
                checkContext(original, control, context);
                throwCause(failed.getCause());
            }
        }
    }

    private static void awaitRemoval(CompletableFuture<Void> future, ToolRequest original, RunControl control,
            AndroidDeviceTools.CapturedReviewContext context, long deadline) throws Exception {
        for (;;) {
            checkRemoval(original, control, context, deadline);
            try {
                future.get(Math.min(POLL_MILLIS, Math.max(1, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS);
                checkRemoval(original, control, context, deadline);
                return;
            } catch (TimeoutException waiting) { /* No gate ownership and no main-thread wait. */ }
            catch (ExecutionException failed) {
                checkContext(original, control, context);
                throwCause(failed.getCause());
            }
        }
    }

    private static void checkRemoval(ToolRequest original, RunControl control,
            AndroidDeviceTools.CapturedReviewContext context, long deadline) throws Exception {
        checkContext(original, control, context);
        if (SystemClock.elapsedRealtime() >= deadline) throw new ReviewException(Failure.REMOVAL_TIMEOUT);
    }

    private static void throwCause(Throwable failed) throws Exception {
        if (failed instanceof Error) throw (Error) failed;
        if (failed instanceof Exception) throw (Exception) failed;
        throw new IllegalStateException("Local image review failed", failed);
    }

    /** One pending main-thread show. Cancellation never closes a replacement host. */
    private static final class ShowTicket implements Runnable, SecureImageReviewOverlay.Listener {
        private final ToolRequest original;
        private final RunControl control;
        private final AndroidDeviceTools.CapturedReviewContext context;
        private final ImageReviewSession session;
        private final HostObserver observer;
        private final ImagePreviewLoader.Decoder decoder;
        private final SecureImageReviewOverlay.WindowRemover remover;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private volatile SecureImageReviewOverlay host;
        final CompletableFuture<SecureImageReviewOverlay> created = new CompletableFuture<>();
        final CompletableFuture<ImageReviewSession.ApprovalToken> approved = new CompletableFuture<>();

        ShowTicket(ToolRequest original, RunControl control, AndroidDeviceTools.CapturedReviewContext context,
                   ImageReviewSession session, HostObserver observer, ImagePreviewLoader.Decoder decoder,
                   SecureImageReviewOverlay.WindowRemover remover) {
            this.original = original; this.control = control; this.context = context;
            this.session = session; this.observer = observer; this.decoder = decoder; this.remover = remover;
        }

        void enqueue() {
            if (!main.post(this)) created.completeExceptionally(new ReviewException(Failure.SHOW_FAILED));
        }

        @Override public void run() {
            if (abandoned.get()) return;
            SecureImageReviewOverlay actual = null;
            try {
                checkReview(original, control, context, session);
                if (abandoned.get()) return;
                actual = context.reviewService().showImageReview(session, control, original.executionEpoch,
                        context.configuration, this, decoder, remover);
                host = actual;
                // A stop may race the call above. The exact returned object is
                // closed even when cancellation happened before it was published.
                if (abandoned.get()) { actual.close(); return; }
                checkReview(original, control, context, session);
                if (observer != null) observer.onShown(actual, session);
                if (abandoned.get()) { actual.close(); return; }
                checkReview(original, control, context, session);
                created.complete(actual);
            } catch (Exception | Error failed) {
                try { if (actual != null) actual.close(); }
                finally { created.completeExceptionally(failed); }
                if (failed instanceof Error) throw (Error) failed;
            }
        }

        @Override public void onApproved(ImageReviewSession.ApprovalToken approval) {
            if (!abandoned.get() && approval != null) approved.complete(approval);
        }

        @Override public void onClosed(ImageReviewSession.State state, ImageReviewSession.Reason reason) {
            approved.completeExceptionally(closedReview(session));
        }

        void abandon() {
            abandoned.set(true);
            main.removeCallbacks(this);
            try {
                SecureImageReviewOverlay actual = host;
                if (actual != null) actual.close();
            } finally {
                created.completeExceptionally(new ReviewException(Failure.HOST_CLOSED));
                approved.completeExceptionally(new ReviewException(Failure.HOST_CLOSED));
            }
        }
    }
}
