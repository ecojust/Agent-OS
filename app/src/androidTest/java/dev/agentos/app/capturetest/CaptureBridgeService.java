package dev.agentos.app.capturetest;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-APK process only. Depends on SDK/JDK classes, never the target APK or its core. */
public final class CaptureBridgeService extends AccessibilityService {
    static final int SUCCESS = 0, UNAVAILABLE = 1, FAILED = 2, CANCELLED = 3;
    static final String FIXTURE = "dev.agentos.fixture";
    private static final Object LOCK = new Object();
    private static volatile CaptureBridgeService connected;
    private static Session session;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "test-window-capture-bridge"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        synchronized (LOCK) { connected = this; }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { invalidate(); }
    @Override public boolean onUnbind(Intent intent) { invalidate(); return super.onUnbind(intent); }
    @Override public void onDestroy() { invalidate(); worker.shutdown(); super.onDestroy(); }

    private void invalidate() {
        synchronized (LOCK) {
            if (connected == this) connected = null;
            if (session != null && session.pending != null && session.pending.service == this)
                session.pending.cancelled = true;
        }
    }

    static Bundle open(String nonce) {
        synchronized (LOCK) {
            if (session != null && (session.pending != null || session.ownedHandles != 0)) throw new IllegalStateException("Previous bridge work has not drained");
            session = new Session(nonce);
            return statusLocked(session);
        }
    }

    static Bundle status(String nonce) {
        synchronized (LOCK) { return statusLocked(requireSession(nonce)); }
    }

    static Bundle end(String nonce) {
        synchronized (LOCK) {
            Session current = requireSession(nonce); current.ended = true;
            if (current.pending != null) current.pending.cancelled = true;
            return statusLocked(current);
        }
    }

    static Bundle capture(String nonce, String requestId, int windowId, Rect bounds, ResultReceiver receiver) {
        Session current;
        CaptureBridgeService service;
        Job job;
        synchronized (LOCK) {
            current = requireSession(nonce);
            if (current.ended || current.pending != null) throw new IllegalStateException("Bridge session is closed or busy");
            if (Build.VERSION.SDK_INT < 34) {
                receiver.send(UNAVAILABLE, result(nonce, requestId, "API_BELOW_34"));
                return statusLocked(current);
            }
            service = connected;
            if (service == null) throw new IllegalStateException("Capture test service is disconnected");
            job = new Job(current, service, requestId, windowId, bounds, receiver);
            current.pending = job;
        }
        try { service.worker.execute(() -> service.submit(job)); }
        catch (RuntimeException | Error rejected) {
            job.submissionReturned = true;
            fail(job, FAILED, "WORK_QUEUE_REJECTED");
            if (rejected instanceof Error) throw (Error) rejected;
        }
        return status(nonce);
    }

    private void submit(Job job) {
        boolean submitted = false;
        String stage = "before-window-read";
        Bundle diagnostics = null;
        try {
            if (Build.VERSION.SDK_INT < 34) { fail(job, UNAVAILABLE, "API_BELOW_34"); return; }
            if (!live(job)) { fail(job, CANCELLED, "STALE_BEFORE_CAPTURE"); return; }
            diagnostics = new Bundle();
            stage = "before-window-read";
            WindowSnapshot actual = snapshot();
            recordWindow(diagnostics, actual);
            stage = "before-window-validation";
            if (actual.id != job.windowId || !actual.bounds.equals(job.bounds)) throw new CaptureCheckFailure("Caller window metadata changed");
            job.before = actual;
            if (!live(job)) { fail(job, CANCELLED, "STALE_BEFORE_SUBMISSION"); return; }
            stage = "capability-check";
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null || (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) == 0)
                throw new CaptureCheckFailure("Test service lacks screenshot capability");
            synchronized (LOCK) { job.session.platformCalls++; }
            stage = "platform-submission";
            submitted = true;
            Api34.capture(this, actual.id, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) { received(job, result); }
                @Override public void onFailure(int errorCode) {
                    Bundle failure = result(job.session.nonce, job.id, "PLATFORM_FAILURE"); failure.putInt("platformError", errorCode);
                    failure.putString("failureStage", "platform-callback");
                    try { job.receiver.send(FAILED, failure); } finally { finished(job); }
                }
            });
        } catch (RuntimeException failed) {
            if (submitted) {
                // A transport exception does not prove the platform never accepted
                // the call. Refuse reuse until its actual callback has drained.
                job.cancelled = true;
                job.receiver.send(FAILED, diagnosticFailure(job, "PLATFORM_SUBMISSION_UNCERTAIN", stage, failed, diagnostics));
            } else {
                try { job.receiver.send(FAILED, diagnosticFailure(job, "SUBMISSION_OR_WINDOW_CHECK_FAILED", stage, failed, diagnostics)); }
                finally { finished(job); }
            }
        }
        finally {
            job.submissionReturned = true;
            if (!submitted) job.callbackFinished = true;
            releaseIfFinished(job);
        }
    }

    @TargetApi(34) private static final class Api34 {
        static void capture(CaptureBridgeService service, int id, TakeScreenshotCallback callback) {
            // Non-rejecting, lightweight callback handoff. No Binder window reads,
            // Bitmap conversion, or network activity execute on this callback.
            service.takeScreenshotOfWindow(id, Runnable::run, callback);
        }
    }

    private void received(Job job, ScreenshotResult result) {
        HardwareBuffer handle = result.getHardwareBuffer();
        if (handle == null) { fail(job, FAILED, "MISSING_PLATFORM_BUFFER"); return; }
        OwnedHandle owned;
        try { owned = new OwnedHandle(job.session, handle); }
        catch (RuntimeException | Error failed) {
            try { handle.close(); } finally { finished(job); }
            throw failed;
        }
        if (!live(job)) {
            try { owned.close(true); } finally { finished(job); }
            return;
        }
        if (Build.VERSION.SDK_INT < 34) {
            try { owned.close(false); } finally { fail(job, UNAVAILABLE, "API_BELOW_34"); }
            return;
        }
        try { worker.execute(() -> deliver(job, result, owned)); }
        catch (RuntimeException | Error rejected) {
            try { owned.close(false); } finally { fail(job, FAILED, "RESULT_QUEUE_REJECTED"); }
            if (rejected instanceof Error) throw (Error) rejected;
        }
    }

    @TargetApi(34) private void deliver(Job job, ScreenshotResult capture, OwnedHandle owned) {
        boolean late = false;
        String stage = "result-metadata-read";
        Bundle diagnostics = null;
        try {
            if (!live(job)) { late = true; return; }
            // This separate diagnostics bundle contains only numeric geometry and
            // format metadata. It never receives the transferable buffer or pixels.
            diagnostics = new Bundle();
            HardwareBuffer handle = owned.buffer;
            ColorSpace space = capture.getColorSpace();
            diagnostics.putBoolean("bufferClosed", handle.isClosed());
            diagnostics.putInt("width", handle.getWidth()); diagnostics.putInt("height", handle.getHeight());
            diagnostics.putInt("format", handle.getFormat()); diagnostics.putInt("layers", handle.getLayers());
            diagnostics.putLong("usage", handle.getUsage());
            diagnostics.putInt("colorSpaceId", space == null ? -1 : space.getId());
            stage = "after-window-read";
            WindowSnapshot after = snapshot();
            recordWindow(diagnostics, after);
            stage = "after-window-validation";
            if (job.before == null || after.id != job.before.id || !after.bounds.equals(job.before.bounds))
                throw new CaptureCheckFailure("Fixture window changed after capture");
            stage = "result-metadata-validation";
            if (handle.isClosed() || handle.getWidth() != after.bounds.width() || handle.getHeight() != after.bounds.height()
                    || handle.getLayers() != 1 || handle.getFormat() != HardwareBuffer.RGBA_8888
                    || space == null || !space.equals(ColorSpace.get(ColorSpace.Named.SRGB)))
                throw new CaptureCheckFailure("Unsupported real capture metadata");
            if (!live(job)) { late = true; return; }
            stage = "result-marshalling";
            Bundle data = result(job.session.nonce, job.id, "CAPTURED_FIXTURE_WINDOW");
            data.putInt("windowId", after.id); data.putInt("displayId", Display.DEFAULT_DISPLAY);
            data.putParcelable("bounds", new Rect(after.bounds));
            data.putInt("width", handle.getWidth()); data.putInt("height", handle.getHeight());
            data.putInt("format", handle.getFormat()); data.putInt("layers", handle.getLayers());
            data.putLong("usage", handle.getUsage()); data.putLong("bufferId", handle.getId());
            data.putInt("colorSpaceId", ColorSpace.get(ColorSpace.Named.SRGB).getId());
            data.putLong("timestamp", capture.getTimestamp()); data.putParcelable("buffer", handle);
            // send() returning is only the end of sender-side marshalling, never
            // proof that the receiver ran or closed its separate native handle.
            job.receiver.send(SUCCESS, data);
            synchronized (LOCK) { job.session.sent++; }
        } catch (RuntimeException failed) {
            synchronized (LOCK) { job.session.deliveryFailures++; }
            job.receiver.send(FAILED, diagnosticFailure(job, "RESULT_VALIDATION_OR_SEND_FAILED", stage, failed, diagnostics));
        } finally {
            try { owned.close(late); } finally { finished(job); }
        }
    }

    private WindowSnapshot snapshot() {
        if (connected != this) throw new CaptureCheckFailure("Test service disconnected");
        if (Build.VERSION.SDK_INT >= 33) {
            if (!clearCache()) throw new CaptureCheckFailure("Window cache unavailable");
        } else {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) throw new CaptureCheckFailure("Service configuration unavailable");
            setServiceInfo(info);
        }
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            if (windows == null || windows.isEmpty() || windows.size() > 40) throw new CaptureCheckFailure("Window list unavailable");
            AccessibilityWindowInfo focus = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) {
                if (focus != null || window.getDisplayId() != Display.DEFAULT_DISPLAY) throw new CaptureCheckFailure("Ambiguous focus");
                focus = window;
            }
            if (focus == null || focus.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) throw new CaptureCheckFailure("No focused application");
            Rect bounds = new Rect(); focus.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) throw new CaptureCheckFailure("Empty fixture window");
            AccessibilityNodeInfo root = focus.getRoot();
            if (root == null) throw new CaptureCheckFailure("Fixture root unavailable");
            try {
                if (root.getWindowId() != focus.getId() || !FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()))
                    throw new CaptureCheckFailure("Only the fixed synthetic fixture may be captured");
                return new WindowSnapshot(focus.getId(), bounds);
            } finally { root.recycle(); }
        } finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static boolean live(Job job) {
        synchronized (LOCK) {
            return session == job.session && !job.session.ended && job.session.pending == job && !job.cancelled
                    && connected == job.service && SystemClock.uptimeMillis() < job.deadline;
        }
    }

    private static void fail(Job job, int code, String reason) {
        try { job.receiver.send(code, result(job.session.nonce, job.id, reason)); }
        finally { finished(job); }
    }
    private static void recordWindow(Bundle metadata, WindowSnapshot window) {
        metadata.putInt("actualWindowId", window.id);
        metadata.putInt("actualDisplayId", Display.DEFAULT_DISPLAY);
        metadata.putParcelable("actualWindowBounds", new Rect(window.bounds));
    }
    private static Bundle diagnosticFailure(Job job, String reason, String stage,
                                            RuntimeException error, Bundle metadata) {
        Bundle failure = result(job.session.nonce, job.id, reason);
        if (metadata != null) failure.putAll(metadata);
        failure.putString("failureStage", stage);
        String type = error.getClass().getName();
        failure.putString("exceptionClass", type.substring(0, Math.min(type.length(), 180)));
        // Only our own fixed, technical messages are eligible. Framework exception
        // messages are deliberately omitted; they need not be free of screen data.
        String detail = error instanceof CaptureCheckFailure ? error.getMessage()
                : "Android platform operation failed; exception message and page content were not retained";
        failure.putString("errorDetail", detail == null ? "Unavailable" : detail.substring(0, Math.min(detail.length(), 180)));
        return failure;
    }
    private static void finished(Job job) { job.callbackFinished = true; releaseIfFinished(job); }
    private static void releaseIfFinished(Job job) {
        synchronized (LOCK) {
            if (job.submissionReturned && job.callbackFinished && job.session.pending == job) job.session.pending = null;
        }
    }
    private static Session requireSession(String nonce) {
        if (session == null || !session.nonce.equals(nonce)) throw new SecurityException("Unknown test session");
        return session;
    }
    private static Bundle result(String nonce, String id, String reason) {
        Bundle value = new Bundle(); value.putString("nonce", nonce); value.putString("requestId", id);
        value.putString("reason", reason); value.putInt("senderUid", Process.myUid()); value.putInt("senderPid", Process.myPid());
        return value;
    }
    private static Bundle statusLocked(Session value) {
        Bundle state = new Bundle(); state.putBoolean("connected", connected != null); state.putBoolean("ended", value.ended);
        state.putBoolean("pending", value.pending != null); state.putInt("platformCalls", value.platformCalls);
        state.putInt("received", value.received); state.putInt("ownedHandles", value.ownedHandles); state.putInt("closedHandles", value.closedHandles);
        state.putInt("lateClosed", value.lateClosed); state.putInt("sent", value.sent); state.putInt("deliveryFailures", value.deliveryFailures);
        state.putInt("closeFailures", value.closeFailures); state.putInt("bridgeUid", Process.myUid()); state.putInt("bridgePid", Process.myPid());
        return state;
    }

    private static final class Session {
        final String nonce;
        boolean ended;
        Job pending;
        int platformCalls, received, ownedHandles, closedHandles, lateClosed, sent, deliveryFailures, closeFailures;
        Session(String nonce) { this.nonce = nonce; }
    }
    private static final class Job {
        final Session session;
        final CaptureBridgeService service;
        final String id;
        final int windowId;
        final Rect bounds;
        final ResultReceiver receiver;
        final long deadline = SystemClock.uptimeMillis() + 8_000;
        volatile boolean cancelled, submissionReturned, callbackFinished;
        WindowSnapshot before;
        Job(Session session, CaptureBridgeService service, String id, int windowId, Rect bounds, ResultReceiver receiver) {
            this.session = session; this.service = service; this.id = id; this.windowId = windowId;
            this.bounds = new Rect(bounds); this.receiver = receiver;
        }
    }
    private static final class WindowSnapshot {
        final int id; final Rect bounds;
        WindowSnapshot(int id, Rect bounds) { this.id = id; this.bounds = bounds; }
    }
    private static final class CaptureCheckFailure extends IllegalStateException {
        CaptureCheckFailure(String technicalMessage) { super(technicalMessage); }
    }
    private static final class OwnedHandle {
        final Session session; final HardwareBuffer buffer;
        final AtomicBoolean closed = new AtomicBoolean();
        OwnedHandle(Session session, HardwareBuffer buffer) {
            this.session = session; this.buffer = buffer;
            synchronized (LOCK) { session.received++; session.ownedHandles++; }
        }
        void close(boolean late) {
            if (!closed.compareAndSet(false, true)) return;
            try { buffer.close(); }
            finally {
                synchronized (LOCK) {
                    if (buffer.isClosed()) { session.ownedHandles--; session.closedHandles++; if (late) session.lateClosed++; }
                    else session.closeFailures++;
                }
            }
        }
    }
}
