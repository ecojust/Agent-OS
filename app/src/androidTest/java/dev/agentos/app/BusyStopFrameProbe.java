package dev.agentos.app;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.FrameMetrics;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.google.gson.JsonNull;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Passive, test-only association of the first visible stopped UI with its original HWUI report. */
final class BusyStopFrameProbe implements AutoCloseable {
    private static final int CANDIDATE_CAPACITY = 16;
    private static final long MAX_WAIT_MILLIS = 3_000, CLOSE_JOIN_MILLIS = 1_000;
    private final Object lock = new Object();
    private final Instrumentation instrumentation;
    private final MainActivity activity;
    private final RunControl owner;
    private final AgentController controller;
    private final HandlerThread frameThread = new HandlerThread("busy-stop-frame-metrics");
    private final Handler drawReadHandler = Handler.createAsync(Looper.getMainLooper());
    private final Frame[] candidates = new Frame[CANDIDATE_CAPACITY];
    private final Rect visibleBounds = new Rect();
    private final Window.OnFrameMetricsAvailableListener frameListener = (reportedWindow, metrics, dropped) -> {
        try { onFrame(reportedWindow, metrics, dropped); }
        catch (RuntimeException | AssertionError failure) { callbackFailure("frame"); }
    };
    private final ViewTreeObserver.OnDrawListener drawListener = () -> {
        try { onDraw(); }
        catch (RuntimeException | AssertionError failure) { callbackFailure("draw"); }
    };
    private Handler frameHandler;
    private Window window;
    private IBinder windowToken;
    private View decor, startButton, stopButton, runningButtons;
    private TextView statusText;
    private int candidatePosition;
    private long sequence, armedNanoTime = -1;
    private Draw baseline, firstStoppedDraw;
    private long baselineDroppedReports;
    private boolean baselineComplete, baselineTimedOut;
    private boolean frameListenerAdded, drawListenerAdded, listenersRemoved, secureAtAttach, secureAtClose;
    private boolean drainStarted, drainBarrierObserved, frameThreadStopped, drainComplete, drainTimedOut, closed;
    private long drainStartedNanoTime = -1, drainEndedNanoTime = -1;
    private long receivedReports, observedDrawCount, discardedNonTargetCandidates, candidateOverflow;
    private long droppedReports, sequenceMismatchCount, zeroDrawingTimeCount, ambiguousMatchCount;
    private long lateFrameReports, lateDroppedReports, invalidMetricCount;
    private long callbackFailureCount;
    private String callbackFailureCode = "";

    BusyStopFrameProbe(Instrumentation instrumentation, MainActivity activity, RunControl owner) {
        requireWorker();
        this.instrumentation = java.util.Objects.requireNonNull(instrumentation);
        this.activity = java.util.Objects.requireNonNull(activity);
        this.owner = java.util.Objects.requireNonNull(owner);
        this.controller = AgentController.get(activity);
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        try {
            onMain(() -> {
                window = activity.getWindow();
                decor = window.getDecorView();
                windowToken = decor.getWindowToken();
                statusText = activity.findViewById(R.id.status_text);
                startButton = activity.findViewById(R.id.start_task);
                stopButton = activity.findViewById(R.id.stop_task);
                if (statusText == null || startButton == null || stopButton == null
                        || !(stopButton.getParent() instanceof View)) {
                    throw new IllegalStateException("Actual MainActivity stop/status views are required");
                }
                runningButtons = (View) stopButton.getParent();
                secureAtAttach = secureFlag();
                window.addOnFrameMetricsAvailableListener(frameListener, frameHandler);
                frameListenerAdded = true;
                decor.getViewTreeObserver().addOnDrawListener(drawListener);
                drawListenerAdded = true;
            });
        } catch (RuntimeException | Error failure) {
            try { close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** A natural running-state frame is completed before input sampling can be armed. */
    boolean awaitBaseline(long timeoutMillis) throws InterruptedException {
        requireWorker();
        long deadline = deadline(timeoutMillis);
        synchronized (lock) {
            if (armedNanoTime >= 0 || closed) throw new IllegalStateException("Baseline phase already ended");
            while (!drawReady(baseline, false) && !fatalQuality() && !invalidDraw(baseline)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { baselineTimedOut = true; break; }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            if (System.nanoTime() >= deadline) baselineTimedOut = true;
            baselineComplete = !baselineTimedOut && !fatalQuality() && baselineDroppedReports == 0
                    && drawReady(baseline, false);
            return baselineComplete;
        }
    }

    /** The caller separately records the immediately following real UP injection boundary. */
    long arm() {
        requireWorker();
        synchronized (lock) {
            if (closed || armedNanoTime >= 0 || !baselineComplete || !drawReady(baseline, false)
                    || baselineDroppedReports != 0 || fatalQuality()) {
                throw new IllegalStateException("A complete original running baseline is required");
            }
            // No measurement counters have been collected yet. Afterwards they are never reset.
            Arrays.fill(candidates, null);
            candidatePosition = 0;
            armedNanoTime = System.nanoTime();
            return armedNanoTime;
        }
    }

    /** Removes both listeners, then bounds the drain of callbacks already queued for this Window. */
    boolean awaitAndDrain(long timeoutMillis) throws InterruptedException {
        requireWorker();
        long deadline = deadline(timeoutMillis);
        synchronized (lock) {
            if (armedNanoTime < 0 || closed || drainStarted) throw new IllegalStateException("No open armed frame sample");
            while (!drawReady(firstStoppedDraw, true) && !fatalQuality() && !invalidDraw(firstStoppedDraw)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { drainTimedOut = true; break; }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            // Never let a callback arriving at/after the fixed wait deadline turn a timeout into a pass.
            if (System.nanoTime() >= deadline) drainTimedOut = true;
            drainStarted = true;
            drainStartedNanoTime = System.nanoTime();
        }
        removeListeners();
        CountDownLatch barrier = new CountDownLatch(1);
        boolean posted = frameHandler.post(barrier::countDown);
        boolean observed = posted && barrier.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        synchronized (lock) {
            drainBarrierObserved = observed;
            if (!observed) drainTimedOut = true;
        }
        finishThread();
        synchronized (lock) {
            drainEndedNanoTime = System.nanoTime();
            drainComplete = validComplete();
            closed = true;
            return drainComplete;
        }
    }

    /** Snapshot remains available after close; only original copied numeric/boolean evidence is retained. */
    JsonObject snapshot() {
        synchronized (lock) {
            JsonObject result = new JsonObject();
            result.addProperty("clockDomain", "CLOCK_MONOTONIC_System.nanoTime");
            result.addProperty("status", armedNanoTime < 0 ? "not_started" : validComplete() ? "passed" : "incomplete");
            result.addProperty("pid", Process.myPid());
            result.addProperty("activityIdentity", System.identityHashCode(activity));
            result.addProperty("windowIdentity", window == null ? -1 : System.identityHashCode(window));
            result.addProperty("decorIdentity", decor == null ? -1 : System.identityHashCode(decor));
            result.addProperty("armedNanoTime", armedNanoTime);
            JsonObject baselineValue = new JsonObject();
            baselineValue.add("draw", baseline == null ? JsonNull.INSTANCE : baseline.json());
            baselineValue.add("frame", baseline == null || baseline.frame == null ? JsonNull.INSTANCE : baseline.frame.json());
            baselineValue.addProperty("matchCount", baseline == null ? 0 : baseline.matchCount);
            baselineValue.addProperty("droppedReports", baselineDroppedReports);
            baselineValue.addProperty("complete", baselineComplete);
            baselineValue.addProperty("timedOut", baselineTimedOut);
            result.add("baseline", baselineValue);
            result.add("firstStoppedDraw", firstStoppedDraw == null ? JsonNull.INSTANCE : firstStoppedDraw.json());
            Frame target = firstStoppedDraw == null ? null : firstStoppedDraw.frame;
            result.add("targetFrame", target == null ? JsonNull.INSTANCE : target.json());
            result.addProperty("completedNanoTime", target == null ? -1 : target.completedNanoTime());
            JsonObject quality = new JsonObject();
            quality.addProperty("receivedReports", receivedReports);
            quality.addProperty("observedDrawCount", observedDrawCount);
            quality.addProperty("discardedNonTargetCandidates", discardedNonTargetCandidates);
            quality.addProperty("candidateOverflow", candidateOverflow);
            quality.addProperty("droppedReports", droppedReports);
            quality.addProperty("sequenceMismatchCount", sequenceMismatchCount);
            quality.addProperty("zeroDrawingTimeCount", zeroDrawingTimeCount);
            quality.addProperty("ambiguousMatchCount", ambiguousMatchCount);
            quality.addProperty("lateFrameReports", lateFrameReports);
            quality.addProperty("lateDroppedReports", lateDroppedReports);
            quality.addProperty("invalidMetricCount", invalidMetricCount);
            quality.addProperty("callbackFailureCount", callbackFailureCount);
            quality.addProperty("callbackFailureCode", callbackFailureCode);
            quality.addProperty("targetMatchCount", firstStoppedDraw == null ? 0 : firstStoppedDraw.matchCount);
            quality.addProperty("finalFrameObserved", target != null);
            quality.addProperty("listenersRemoved", listenersRemoved);
            quality.addProperty("drainComplete", drainComplete && validComplete());
            quality.addProperty("drainTimedOut", drainTimedOut);
            quality.addProperty("secureFlagsPreserved", secureAtAttach && secureAtClose);
            quality.addProperty("drainStartedNanoTime", drainStartedNanoTime);
            quality.addProperty("drainEndedNanoTime", drainEndedNanoTime);
            quality.addProperty("drainBarrierObserved", drainBarrierObserved);
            quality.addProperty("frameThreadStopped", frameThreadStopped);
            result.add("quality", quality);
            return result;
        }
    }

    private void onDraw() {
        Draw candidate;
        synchronized (lock) {
            if (closed || listenersRemoved || drainStarted) return;
            sequence++;
            if (armedNanoTime >= 0) observedDrawCount++;
            if (armedNanoTime < 0 ? baseline != null : firstStoppedDraw != null) return;
        }
        boolean cancelled = owner.isCancelled();
        boolean hasVisibleBounds = statusText.getGlobalVisibleRect(visibleBounds);
        if (!hasVisibleBounds) visibleBounds.setEmpty();
        int statusWidth = statusText.getWidth(), statusHeight = statusText.getHeight();
        boolean statusVisible = statusText.isShown() && hasVisibleBounds && statusWidth > 0 && statusHeight > 0
                && visibleBounds.width() == statusWidth && visibleBounds.height() == statusHeight;
        boolean stoppedText = "已停止".contentEquals(statusText.getText());
        boolean stoppedUi = cancelled && statusVisible && stoppedText
                && runningButtons.getVisibility() == View.GONE && startButton.isShown();
        boolean runningUi = !cancelled && statusVisible && !stoppedText
                && runningButtons.isShown() && stopButton.isShown() && startButton.getVisibility() == View.GONE;
        synchronized (lock) {
            if (closed || listenersRemoved || drainStarted) return;
            boolean armed = armedNanoTime >= 0;
            if (armed ? firstStoppedDraw != null || !stoppedUi : baseline != null || !runningUi) return;
            candidate = new Draw(sequence, System.nanoTime(), armed ? stoppedUi : runningUi,
                    ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED,
                    activity.hasWindowFocus(), decor.isAttachedToWindow() && !activity.isDestroyed() && !activity.isFinishing(),
                    decor.isHardwareAccelerated(), secureFlag(), windowToken != null
                            && activity.getWindow() == window && window.getDecorView() == decor
                            && decor.getWindowToken() == windowToken,
                    controller.isTaskDiagnosticsCurrent(owner.getTaskId()),
                    visibleBounds, statusWidth, statusHeight);
            // Freeze the first eligible UI draw even if a guard is wrong. Never wait for a better replacement.
            if (armed) firstStoppedDraw = candidate; else baseline = candidate;
        }
        boolean posted = drawReadHandler.postAtFrontOfQueue(() -> {
            try { completeDraw(candidate); }
            catch (RuntimeException | AssertionError failure) { callbackFailure("draw_read"); }
        });
        if (!posted) callbackFailure("draw_read_post");
    }

    private void completeDraw(Draw draw) {
        long readNanoTime = System.nanoTime();
        long drawingTimeMillis = decor.getDrawingTime();
        synchronized (lock) {
            if (closed || draw.readNanoTime >= 0) return;
            draw.readNanoTime = readNanoTime;
            draw.drawingTimeMillis = drawingTimeMillis;
            if (draw.sequence != sequence) sequenceMismatchCount++;
            else if (drawingTimeMillis <= 0) zeroDrawingTimeCount++;
            else {
                for (int index = 0; index < candidates.length; index++) {
                    Frame frame = candidates[index];
                    if (frame == null) continue;
                    if (!associate(draw, frame) && armedNanoTime >= 0) discardedNonTargetCandidates++;
                    candidates[index] = null;
                }
            }
            lock.notifyAll();
        }
    }

    private void onFrame(Window reportedWindow, FrameMetrics metrics, int dropped) {
        Frame frame = new Frame(metrics, dropped);
        synchronized (lock) {
            if (closed) return;
            if (reportedWindow != window) { invalidMetricCount++; lock.notifyAll(); return; }
            boolean armed = armedNanoTime >= 0;
            if (armed) { receivedReports++; droppedReports += dropped; }
            else baselineDroppedReports += dropped;
            if (dropped < 0) invalidMetricCount++;
            Draw target = armed ? firstStoppedDraw : baseline;
            if (drainStarted && firstStoppedDraw != null && firstStoppedDraw.frame != null
                    && frame.vsyncNanoTime <= firstStoppedDraw.frame.vsyncNanoTime) {
                lateFrameReports++;
                lateDroppedReports += dropped;
            }
            if (target != null && target.drawingTimeMillis > 0) {
                if (!associate(target, frame) && armed) discardedNonTargetCandidates++;
            } else {
                Frame replaced = candidates[candidatePosition];
                if (replaced != null) {
                    // A callback processed before the first qualifying OnDraw cannot belong to that draw.
                    // Once the draw exists, an unresolved potentially matching report is never silently lost.
                    if (target != null && replaced.callbackNanoTime >= target.drawNanoTime) candidateOverflow++;
                    else if (armed) discardedNonTargetCandidates++;
                }
                candidates[candidatePosition] = frame;
                candidatePosition = (candidatePosition + 1) % candidates.length;
            }
            lock.notifyAll();
        }
    }

    private boolean associate(Draw draw, Frame frame) {
        // A report already delivered before this frozen draw cannot describe its completion,
        // even when separate traversals share the same VSYNC millisecond.
        if (frame.callbackNanoTime < draw.drawNanoTime) return false;
        if (draw.drawingTimeMillis <= 0 || frame.vsyncNanoTime / 1_000_000 != draw.drawingTimeMillis) return false;
        if (draw.frame == frame) return true;
        draw.matchCount++;
        if (draw.frame == null) {
            draw.frame = frame;
            if (!frame.valid() || frame.completedNanoTime() < draw.drawNanoTime) invalidMetricCount++;
        } else ambiguousMatchCount++;
        return true;
    }

    private boolean drawReady(Draw draw, boolean stopped) {
        return draw != null && draw.guardsValid() && draw.readNanoTime >= draw.drawNanoTime
                && draw.drawingTimeMillis > 0 && draw.matchCount == 1 && draw.frame != null && draw.frame.valid()
                && draw.frame.completedNanoTime() >= draw.drawNanoTime
                && (!stopped || draw.frame.firstDraw == 0 && draw.drawNanoTime >= armedNanoTime);
    }

    private static boolean invalidDraw(Draw draw) { return draw != null && !draw.guardsValid(); }

    private boolean fatalQuality() {
        return candidateOverflow != 0 || droppedReports != 0 || sequenceMismatchCount != 0
                || zeroDrawingTimeCount != 0 || ambiguousMatchCount != 0 || lateFrameReports != 0
                || lateDroppedReports != 0 || invalidMetricCount != 0 || callbackFailureCount != 0;
    }

    private void callbackFailure(String code) {
        synchronized (lock) {
            callbackFailureCount++;
            if (callbackFailureCode.isEmpty()) callbackFailureCode = code;
            lock.notifyAll();
        }
    }

    private boolean validComplete() {
        return armedNanoTime >= 0 && baselineComplete && !baselineTimedOut && baselineDroppedReports == 0
                && drawReady(baseline, false) && drawReady(firstStoppedDraw, true) && !fatalQuality()
                && drainStarted && listenersRemoved && drainBarrierObserved && frameThreadStopped
                && !drainTimedOut && secureAtAttach && secureAtClose;
    }

    private boolean secureFlag() {
        return window != null && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    private void removeListeners() {
        onMain(() -> {
            if (window != null && frameListenerAdded) {
                window.removeOnFrameMetricsAvailableListener(frameListener);
                frameListenerAdded = false;
            }
            if (decor != null && drawListenerAdded) {
                ViewTreeObserver observer = decor.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnDrawListener(drawListener);
                drawListenerAdded = false;
            }
            synchronized (lock) {
                listenersRemoved = !frameListenerAdded && !drawListenerAdded;
                secureAtClose = secureFlag();
                lock.notifyAll();
            }
        });
    }

    private void finishThread() throws InterruptedException {
        frameThread.quitSafely();
        frameThread.join(CLOSE_JOIN_MILLIS);
        synchronized (lock) {
            frameThreadStopped = !frameThread.isAlive();
            if (!frameThreadStopped) drainTimedOut = true;
        }
    }

    @Override public void close() {
        requireWorker();
        synchronized (lock) { if (closed) return; }
        try {
            removeListeners();
        } finally {
            frameThread.quitSafely();
            try { frameThread.join(CLOSE_JOIN_MILLIS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            synchronized (lock) {
                frameThreadStopped = !frameThread.isAlive();
                if (!frameThreadStopped) drainTimedOut = true;
                if (drainStarted && drainEndedNanoTime < 0) drainEndedNanoTime = System.nanoTime();
                drainComplete = validComplete();
                closed = true;
            }
        }
    }

    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Frame probe waits cannot run on UI");
    }

    /** Setup/cleanup assertions return to the test thread instead of killing the application's UI loop. */
    private void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); }
            catch (RuntimeException | Error error) { failure.set(error); }
        });
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
    }

    private static long deadline(long timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > MAX_WAIT_MILLIS) throw new IllegalArgumentException("Frame wait exceeds fixed bound");
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private static final class Draw {
        final long sequence, drawNanoTime;
        final boolean uiVerified, resumed, focused, attached, hardwareAccelerated, secureFlagPresent;
        final boolean windowIdentityMatched, taskIdentityMatched;
        final int statusLeft, statusTop, statusRight, statusBottom, statusWidth, statusHeight;
        long drawingTimeMillis = -1, readNanoTime = -1, matchCount;
        Frame frame;
        Draw(long sequence, long drawNanoTime, boolean uiVerified, boolean resumed, boolean focused,
             boolean attached, boolean hardwareAccelerated, boolean secureFlagPresent, boolean windowIdentityMatched,
             boolean taskIdentityMatched,
             Rect statusBounds, int statusWidth, int statusHeight) {
            this.sequence = sequence; this.drawNanoTime = drawNanoTime; this.uiVerified = uiVerified;
            this.resumed = resumed; this.focused = focused; this.attached = attached;
            this.hardwareAccelerated = hardwareAccelerated; this.secureFlagPresent = secureFlagPresent;
            this.windowIdentityMatched = windowIdentityMatched;
            this.taskIdentityMatched = taskIdentityMatched;
            statusLeft = statusBounds.left; statusTop = statusBounds.top;
            statusRight = statusBounds.right; statusBottom = statusBounds.bottom;
            this.statusWidth = statusWidth; this.statusHeight = statusHeight;
        }
        boolean guardsValid() {
            return uiVerified && resumed && focused && attached && hardwareAccelerated && secureFlagPresent
                    && windowIdentityMatched && taskIdentityMatched && statusWidth > 0 && statusHeight > 0
                    && statusRight - statusLeft == statusWidth && statusBottom - statusTop == statusHeight;
        }
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("sequence", sequence); result.addProperty("drawNanoTime", drawNanoTime);
            result.addProperty("drawingTimeMillis", drawingTimeMillis); result.addProperty("readNanoTime", readNanoTime);
            result.addProperty("uiVerified", uiVerified); result.addProperty("resumed", resumed);
            result.addProperty("focused", focused); result.addProperty("attached", attached);
            result.addProperty("hardwareAccelerated", hardwareAccelerated); result.addProperty("secureFlagPresent", secureFlagPresent);
            result.addProperty("windowIdentityMatched", windowIdentityMatched);
            result.addProperty("taskIdentityMatched", taskIdentityMatched);
            JsonArray bounds = new JsonArray();
            bounds.add(statusLeft); bounds.add(statusTop); bounds.add(statusRight); bounds.add(statusBottom);
            result.add("statusBounds", bounds);
            result.addProperty("statusWidth", statusWidth); result.addProperty("statusHeight", statusHeight);
            return result;
        }
    }

    private static final class Frame {
        final long intendedVsyncNanoTime, vsyncNanoTime, totalDurationNanos, firstDraw;
        final long gpuDurationNanos, deadlineNanos, callbackNanoTime, droppedReports;
        Frame(FrameMetrics metrics, int dropped) {
            callbackNanoTime = System.nanoTime();
            intendedVsyncNanoTime = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
            vsyncNanoTime = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
            totalDurationNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME);
            gpuDurationNanos = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.GPU_DURATION) : -1;
            deadlineNanos = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.DEADLINE) : -1;
            droppedReports = dropped;
        }
        long completedNanoTime() {
            return intendedVsyncNanoTime > 0 && totalDurationNanos > 0
                    && intendedVsyncNanoTime <= Long.MAX_VALUE - totalDurationNanos
                    ? intendedVsyncNanoTime + totalDurationNanos : -1;
        }
        boolean valid() {
            long completed = completedNanoTime();
            return vsyncNanoTime >= intendedVsyncNanoTime && intendedVsyncNanoTime > 0
                    && completed >= vsyncNanoTime && completed <= callbackNanoTime
                    && droppedReports >= 0 && (firstDraw == 0 || firstDraw == 1);
        }
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("intendedVsyncNanoTime", intendedVsyncNanoTime);
            result.addProperty("vsyncNanoTime", vsyncNanoTime); result.addProperty("totalDurationNanos", totalDurationNanos);
            result.addProperty("firstDraw", firstDraw); result.addProperty("gpuDurationNanos", gpuDurationNanos);
            result.addProperty("deadlineNanos", deadlineNanos); result.addProperty("callbackNanoTime", callbackNanoTime);
            result.addProperty("droppedReports", droppedReports);
            return result;
        }
    }
}
