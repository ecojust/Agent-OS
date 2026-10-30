package dev.agentos.fixture;

import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.FrameMetrics;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded, process-local passive measurements used only by the synthetic fixture APK. */
final class PerformanceProbe {
    private static final Object LOCK = new Object();
    private static final int MAX_FRAMES = 4096, PAGE_SIZE = 64;
    private static final long DRAIN_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final String PROCESS_INSTANCE = UUID.randomUUID().toString();
    private static final String[] EVENTS = {"create", "start", "resume", "pause", "stop", "destroy", "newIntent"};
    private static final String[] EVENT_TIMES = {"created", "started", "resumed", "paused", "stopped", "destroyed", "newIntent"};
    private static final long[] COUNTS = new long[EVENTS.length], TIMES = new long[EVENTS.length];
    private static final long[] DRAW_TIMES_MILLIS = new long[128], DRAW_VERSIONS = new long[128];
    private static final long[] DRAW_NANOS = new long[128], DRAW_MATCHED_VSYNCS = new long[128];
    private static final Frame[] RECENT_FRAMES = new Frame[128];
    private static int drawPosition;
    private static int recentFramePosition;
    private static long drawSequence, unresolvedDrawCount, sequenceMismatchCount, zeroDrawingTimeCount;
    private static String activityInstance = "";
    private static boolean present, resumed, stopped, focused, hardwareAccelerated;
    private static long focusGainCount, focusLossCount, firstFocusGainNanoTime;
    private static long lastFocusGainNanoTime, lastFocusLossNanoTime;
    private static long mutationVersion, drawVersion, drawVsyncNs, drawNanoTime;
    private static long drawTimeMillis, finalDrawTimeMillis;
    private static long finalDrawVersion, finalDrawVsyncNs;
    private static boolean drawFrameObserved;
    private static boolean drawMatchAmbiguous;
    private static long activityFrameCount, activityDroppedReports, lastFrameVsyncNs, lastFrameCallbackNanoTime;
    private static long displayChangeCount, displayObservedNanoTime, edits, saves, increments;
    private static int displayId = -1, clicks;
    private static double refreshHz = -1, modeHz = -1;
    private static String editorText = "", savedText = "Saved: empty";
    private static Handler frameHandler;
    private static Session session;

    static String created() {
        synchronized (LOCK) {
            activityInstance = UUID.randomUUID().toString();
            present = true; resumed = false; stopped = false; focused = false; hardwareAccelerated = false;
            focusGainCount = focusLossCount = firstFocusGainNanoTime = 0;
            lastFocusGainNanoTime = lastFocusLossNanoTime = 0;
            mutationVersion = drawVersion = drawVsyncNs = drawNanoTime = 0;
            finalDrawVersion = finalDrawVsyncNs = 0;
            drawTimeMillis = finalDrawTimeMillis = 0;
            drawMatchAmbiguous = false;
            java.util.Arrays.fill(DRAW_TIMES_MILLIS, 0);
            java.util.Arrays.fill(DRAW_MATCHED_VSYNCS, 0);
            java.util.Arrays.fill(RECENT_FRAMES, null);
            drawPosition = 0;
            recentFramePosition = 0;
            drawSequence = unresolvedDrawCount = sequenceMismatchCount = zeroDrawingTimeCount = 0;
            drawFrameObserved = false;
            activityFrameCount = activityDroppedReports = lastFrameVsyncNs = lastFrameCallbackNanoTime = 0;
            displayChangeCount = edits = saves = increments = 0;
            clicks = 0; editorText = ""; savedText = "Saved: empty";
            session = null;
            event(0);
            return activityInstance;
        }
    }
    static void setFrameHandler(String instance, Handler handler) {
        synchronized (LOCK) { if (activityInstance.equals(instance)) frameHandler = handler; }
    }
    static void lifecycle(String instance, String name) {
        synchronized (LOCK) {
            if (!activityInstance.equals(instance)) return;
            for (int i = 0; i < EVENTS.length; i++) if (EVENTS[i].equals(name)) event(i);
            if ("resume".equals(name)) { resumed = true; stopped = false; }
            if ("pause".equals(name)) resumed = false;
            if ("stop".equals(name)) stopped = true;
            if ("destroy".equals(name)) { present = false; resumed = false; }
            LOCK.notifyAll();
        }
    }
    private static void event(int index) { COUNTS[index]++; TIMES[index] = System.nanoTime(); }
    static void focused(String instance, boolean value) {
        synchronized (LOCK) {
            if (!activityInstance.equals(instance) || focused == value) return;
            focused = value;
            long now = System.nanoTime();
            if (value) {
                focusGainCount++;
                if (firstFocusGainNanoTime == 0) firstFocusGainNanoTime = now;
                lastFocusGainNanoTime = now;
            } else {
                focusLossCount++;
                lastFocusLossNanoTime = now;
            }
            LOCK.notifyAll();
        }
    }
    static void display(String instance, int id, double hz, double activeModeHz, boolean changed) {
        synchronized (LOCK) {
            if (!activityInstance.equals(instance)) return;
            if (changed && (displayId != id || refreshHz != hz || modeHz != activeModeHz)) displayChangeCount++;
            displayId = id; refreshHz = hz; modeHz = activeModeHz;
            displayObservedNanoTime = System.nanoTime();
        }
    }
    static void changed(String instance, String text, String saved, int count, long editCount, long saveCount, long incrementCount) {
        synchronized (LOCK) {
            if (!activityInstance.equals(instance)) return;
            mutationVersion++;
            editorText = text; savedText = saved; clicks = count;
            edits = editCount; saves = saveCount; increments = incrementCount;
            finalDrawVersion = finalDrawVsyncNs = 0;
            finalDrawTimeMillis = 0;
            LOCK.notifyAll();
        }
    }
    static DrawObservation observeDraw(String instance, boolean accelerated) {
        synchronized (LOCK) {
            if (!activityInstance.equals(instance)) return null;
            drawVersion = mutationVersion;
            drawFrameObserved = false;
            drawVsyncNs = 0;
            drawTimeMillis = 0;
            drawNanoTime = System.nanoTime();
            hardwareAccelerated = accelerated;
            return new DrawObservation(instance, ++drawSequence, drawVersion, drawNanoTime, finalState());
        }
    }
    static void completeDraw(DrawObservation observation, long drawingTimeMillis) {
        synchronized (LOCK) {
            if (observation == null || !activityInstance.equals(observation.instance)) return;
            if (observation.sequence != drawSequence || drawingTimeMillis <= 0) {
                // Classify each rejected observation once, with sequence mismatch
                // taking precedence if its later-frame time is also unavailable.
                if (observation.sequence != drawSequence) sequenceMismatchCount++;
                else zeroDrawingTimeCount++;
                unresolvedDrawCount++;
                LOCK.notifyAll();
                return;
            }
            drawTimeMillis = drawingTimeMillis;
            DRAW_TIMES_MILLIS[drawPosition] = drawingTimeMillis;
            DRAW_VERSIONS[drawPosition] = observation.version;
            DRAW_NANOS[drawPosition] = observation.nanoTime;
            DRAW_MATCHED_VSYNCS[drawPosition] = 0;
            drawPosition = (drawPosition + 1) % DRAW_TIMES_MILLIS.length;
            if (observation.finalState && finalDrawTimeMillis == 0) {
                finalDrawVersion = observation.version;
                finalDrawTimeMillis = drawingTimeMillis;
            }
            // A RenderThread report can reach its Handler before the one-shot UI post.
            for (Frame frame : RECENT_FRAMES) if (frame != null && frame.vsyncNs / 1_000_000 == drawingTimeMillis) {
                associateFrame(frame);
                recordFrame(frame);
            }
            LOCK.notifyAll();
        }
    }
    static void frame(String instance, FrameMetrics metrics, int dropped) {
        // FrameMetrics is reused by Android. Copy scalars before this callback returns.
        Frame frame = new Frame(metrics, dropped);
        synchronized (LOCK) {
            if (!activityInstance.equals(instance)) return;
            activityFrameCount++;
            activityDroppedReports += dropped;
            lastFrameVsyncNs = frame.vsyncNs;
            lastFrameCallbackNanoTime = frame.callbackNanoTime;
            frame.refreshHz = refreshHz; frame.modeHz = modeHz;
            frame.displayId = displayId;
            frame.refreshObservedNanoTime = displayObservedNanoTime;
            RECENT_FRAMES[recentFramePosition] = frame;
            recentFramePosition = (recentFramePosition + 1) % RECENT_FRAMES.length;
            associateFrame(frame);
            recordFrame(frame);
            LOCK.notifyAll();
        }
    }
    private static void associateFrame(Frame frame) {
        long frameTimeMillis = frame.vsyncNs / 1_000_000;
        for (int i = 0; i < DRAW_TIMES_MILLIS.length; i++) {
            if (DRAW_TIMES_MILLIS[i] != frameTimeMillis) continue;
            if (DRAW_MATCHED_VSYNCS[i] != 0 && DRAW_MATCHED_VSYNCS[i] != frame.vsyncNs) drawMatchAmbiguous = true;
            DRAW_MATCHED_VSYNCS[i] = frame.vsyncNs;
            frame.drawVersion = DRAW_VERSIONS[i];
            frame.drawNanoTime = DRAW_NANOS[i];
        }
        if (frameTimeMillis == drawTimeMillis && !drawMatchAmbiguous) {
            drawVsyncNs = frame.vsyncNs;
            drawFrameObserved = true;
        }
        if (finalDrawTimeMillis > 0 && frameTimeMillis == finalDrawTimeMillis && !drawMatchAmbiguous)
            finalDrawVsyncNs = frame.vsyncNs;
    }
    private static void recordFrame(Frame frame) {
        Session current = session;
        if (current == null || (frame.drawNanoTime < current.beginNanoTime && frame.intendedVsyncNs < current.beginNanoTime)) return;
        if (finalDrawVsyncNs != 0 && frame.vsyncNs == finalDrawVsyncNs) current.finalFrameObserved = true;
        if (current.id.equals(frame.recordedSession)) return;
        frame.recordedSession = current.id;
        frame.crossesBegin = frame.intendedVsyncNs < current.beginNanoTime;
        if (current.ended) {
            if (frame.vsyncNs <= current.frameCutoffVsyncNs) {
                current.lateFrameReports++;
                current.lateDroppedReports += frame.droppedReports;
                current.drainComplete = false;
            }
        } else {
            current.droppedReports += frame.droppedReports;
            if (current.frames.size() < MAX_FRAMES) current.frames.add(frame);
            else current.frameOverflow++;
        }
    }
    static Bundle status() { synchronized (LOCK) { return snapshot(); } }

    static Bundle begin(String id) {
        requireWorker();
        synchronized (LOCK) { requireInitial(); }
        MemorySample memory = new MemorySample();
        synchronized (LOCK) {
            requireInitial();
            Session next = new Session(id);
            next.beforeMemory = memory;
            next.beforePssKb = memory.totalPssKb;
            next.beginMutationVersion = mutationVersion;
            next.beginUnresolvedDrawCount = unresolvedDrawCount;
            next.beginRefreshHz = refreshHz; next.beginModeHz = modeHz;
            next.beginDisplayChangeCount = displayChangeCount;
            next.beginDisplayObservedNanoTime = displayObservedNanoTime;
            next.beginCpuMillis = Process.getElapsedCpuTime();
            next.beginNanoTime = System.nanoTime();
            session = next;
            return snapshot();
        }
    }
    private static void requireInitial() {
        if (!present || !resumed || frameHandler == null) throw new IllegalStateException("Resumed performance activity required");
        if (session != null && !session.ended) throw new IllegalStateException("Previous measurement still open");
        if (mutationVersion != 0 || !editorText.isEmpty() || !"Saved: empty".equals(savedText) || clicks != 0)
            throw new IllegalStateException("Fresh empty performance UI required");
    }

    static Bundle end(String id) {
        requireWorker();
        Session current;
        Handler handler;
        long deadline = System.nanoTime() + DRAIN_NANOS;
        synchronized (LOCK) {
            current = requireSession(id);
            if (current.ended || current.closing) throw new IllegalStateException("Session already ending or ended");
            current.closing = true;
            handler = frameHandler;
            while ((!finalState() || finalDrawVsyncNs == 0 || !current.finalFrameObserved) && present) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(LOCK, remaining); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            current.finalMutationVersion = mutationVersion;
            current.finalDrawVersion = finalDrawVersion;
            current.frameCutoffVsyncNs = finalDrawVsyncNs;
            current.endCpuMillis = Process.getElapsedCpuTime();
            current.endNanoTime = System.nanoTime();
            current.endRefreshHz = refreshHz; current.endModeHz = modeHz;
            current.ended = true;
        }
        // PSS and the consumer barrier are outside the CPU interval. No forced frame is emitted.
        MemorySample afterMemory = new MemorySample();
        CountDownLatch drained = new CountDownLatch(1);
        boolean posted = handler != null && handler.post(drained::countDown);
        boolean barrier = false;
        if (posted) {
            try { barrier = drained.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        synchronized (LOCK) {
            current.afterMemory = afterMemory;
            current.afterPssKb = afterMemory.totalPssKb;
            current.endDisplayChangeCount = displayChangeCount;
            current.endDisplayObservedNanoTime = displayObservedNanoTime;
            current.unresolvedTaskDraws = unresolvedDrawCount - current.beginUnresolvedDrawCount;
            current.drainComplete = barrier && current.finalFrameObserved && current.frameCutoffVsyncNs > 0
                    && !drawMatchAmbiguous && current.lateFrameReports == 0 && current.lateDroppedReports == 0
                    && current.unresolvedTaskDraws == 0;
            current.drainTimedOut = !current.drainComplete;
            // Select complete reported task frames through the final natural draw watermark.
            // Later cursor/IME frames do not extend the completed three-write task frame sample.
            if (current.frameCutoffVsyncNs > 0) {
                for (int i = current.frames.size() - 1; i >= 0; i--)
                    if (current.frames.get(i).vsyncNs > current.frameCutoffVsyncNs) current.frames.remove(i);
            }
            return snapshot();
        }
    }

    static Bundle page(String id, int offset) {
        synchronized (LOCK) {
            Session current = requireSession(id);
            if (!current.ended) throw new IllegalStateException("End the session before reading frames");
            if (offset < 0 || offset > current.frames.size()) throw new IllegalArgumentException("Invalid frame offset");
            Bundle result = identity();
            result.putString("sessionId", id);
            result.putInt("offset", offset);
            int end = Math.min(current.frames.size(), offset + PAGE_SIZE);
            result.putInt("nextOffset", end);
            result.putInt("totalFrames", current.frames.size());
            result.putLong("lateFrameReports", current.lateFrameReports);
            result.putLong("lateDroppedReports", current.lateDroppedReports);
            result.putBoolean("drainComplete", current.drainComplete);
            ArrayList<Bundle> frames = new ArrayList<>(end - offset);
            for (int i = offset; i < end; i++) frames.add(current.frames.get(i).bundle(i));
            result.putParcelableArrayList("frames", frames);
            return result;
        }
    }
    private static Session requireSession(String id) {
        if (session == null || !session.id.equals(id)) throw new IllegalStateException("Unknown measurement session");
        return session;
    }
    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Measurement call cannot block the UI thread");
    }
    private static boolean finalState() {
        return PerformanceActivity.SAMPLE.equals(editorText) && ("Saved: " + PerformanceActivity.SAMPLE).equals(savedText)
                && clicks == 1 && edits == 1 && saves == 1 && increments == 1;
    }
    private static Bundle identity() {
        Bundle result = new Bundle();
        result.putInt("schemaVersion", 3);
        result.putString("clockDomain", "CLOCK_MONOTONIC_System.nanoTime");
        result.putInt("pid", Process.myPid()); result.putInt("uid", Process.myUid());
        result.putString("processInstance", PROCESS_INSTANCE); result.putString("activityInstance", activityInstance);
        return result;
    }
    private static Bundle snapshot() {
        Bundle result = identity();
        result.putBoolean("activityPresent", present);
        for (int i = 0; i < EVENTS.length; i++) {
            result.putLong(EVENTS[i] + "Count", COUNTS[i]);
            result.putLong(EVENT_TIMES[i] + "NanoTime", TIMES[i]);
        }
        result.putBoolean("resumed", resumed); result.putBoolean("stopped", stopped);
        result.putBoolean("windowFocused", focused); result.putBoolean("hardwareAccelerated", hardwareAccelerated);
        result.putLong("focusGainCount", focusGainCount); result.putLong("focusLossCount", focusLossCount);
        result.putLong("firstFocusGainNanoTime", firstFocusGainNanoTime);
        result.putLong("lastFocusGainNanoTime", lastFocusGainNanoTime);
        result.putLong("lastFocusLossNanoTime", lastFocusLossNanoTime);
        result.putLong("mutationVersion", mutationVersion); result.putLong("drawVersion", drawVersion);
        result.putLong("drawVsyncNs", drawVsyncNs); result.putLong("drawNanoTime", drawNanoTime);
        result.putLong("drawTimeMillis", drawTimeMillis);
        result.putString("drawMatchMethod", "front_post_OnDraw_View.getDrawingTime_equals_floor_FrameMetrics.VSYNC_TIMESTAMP_ms");
        result.putBoolean("drawFrameObserved", drawFrameObserved);
        result.putBoolean("drawMatchAmbiguous", drawMatchAmbiguous);
        result.putLong("unresolvedDrawCount", unresolvedDrawCount);
        result.putLong("sequenceMismatchCount", sequenceMismatchCount);
        result.putLong("zeroDrawingTimeCount", zeroDrawingTimeCount);
        result.putBoolean("finalState", finalState()); result.putBoolean("finalDrawObserved", finalDrawVsyncNs > 0);
        result.putLong("activityFrameCount", activityFrameCount); result.putLong("activityDroppedReports", activityDroppedReports);
        result.putLong("lastFrameVsyncNs", lastFrameVsyncNs); result.putLong("lastFrameCallbackNanoTime", lastFrameCallbackNanoTime);
        result.putDouble("refreshHz", refreshHz); result.putDouble("modeHz", modeHz);
        result.putInt("displayId", displayId); result.putLong("displayChangeCount", displayChangeCount);
        result.putLong("displayObservedNanoTime", displayObservedNanoTime);
        result.putString("editorText", editorText); result.putString("savedText", savedText); result.putInt("clicks", clicks);
        result.putLong("editCount", edits); result.putLong("saveCount", saves); result.putLong("incrementCount", increments);
        if (session != null) session.append(result);
        return result;
    }
    private static final class Session {
        final String id;
        final ArrayList<Frame> frames = new ArrayList<>();
        long beforePssKb, afterPssKb = -1, beginCpuMillis, endCpuMillis, beginNanoTime, endNanoTime;
        long beginMutationVersion, finalMutationVersion, finalDrawVersion, frameCutoffVsyncNs;
        long frameOverflow, droppedReports, lateFrameReports, lateDroppedReports;
        long beginUnresolvedDrawCount, unresolvedTaskDraws;
        long beginDisplayChangeCount, endDisplayChangeCount, beginDisplayObservedNanoTime, endDisplayObservedNanoTime;
        MemorySample beforeMemory, afterMemory;
        double beginRefreshHz, beginModeHz, endRefreshHz, endModeHz;
        boolean closing, ended, finalFrameObserved, drainComplete, drainTimedOut;
        Session(String id) { this.id = id; }
        void append(Bundle result) {
            result.putString("sessionId", id); result.putBoolean("ended", ended);
            result.putLong("beforePssKb", beforePssKb); result.putLong("afterPssKb", afterPssKb);
            result.putLong("beginCpuMillis", beginCpuMillis); result.putLong("endCpuMillis", endCpuMillis);
            result.putLong("beginNanoTime", beginNanoTime); result.putLong("endNanoTime", endNanoTime);
            result.putLong("beginMutationVersion", beginMutationVersion); result.putLong("finalMutationVersion", finalMutationVersion);
            result.putLong("finalDrawVersion", finalDrawVersion); result.putLong("finalDrawVsyncNs", frameCutoffVsyncNs);
            result.putLong("frameCutoffVsyncNs", frameCutoffVsyncNs);
            result.putInt("frameCount", frames.size()); result.putLong("frameOverflow", frameOverflow);
            result.putLong("droppedReports", droppedReports); result.putLong("lateFrameReports", lateFrameReports);
            result.putLong("lateDroppedReports", lateDroppedReports);
            result.putLong("beginUnresolvedDrawCount", beginUnresolvedDrawCount);
            result.putLong("unresolvedTaskDraws", unresolvedTaskDraws);
            result.putBoolean("finalFrameObserved", finalFrameObserved); result.putBoolean("drainComplete", drainComplete);
            result.putBoolean("drainTimedOut", drainTimedOut);
            result.putDouble("beginRefreshHz", beginRefreshHz); result.putDouble("beginModeHz", beginModeHz);
            result.putDouble("endRefreshHz", endRefreshHz); result.putDouble("endModeHz", endModeHz);
            result.putLong("beginDisplayChangeCount", beginDisplayChangeCount);
            result.putLong("endDisplayChangeCount", endDisplayChangeCount);
            result.putLong("beginDisplayObservedNanoTime", beginDisplayObservedNanoTime);
            result.putLong("endDisplayObservedNanoTime", endDisplayObservedNanoTime);
            result.putBoolean("displayStable", ended && beginDisplayChangeCount == endDisplayChangeCount);
            if (beforeMemory != null) beforeMemory.append(result, "before");
            if (afterMemory != null) afterMemory.append(result, "after");
        }
    }
    private static final class Frame {
        final long intendedVsyncNs, vsyncNs, totalDurationNs, firstDraw, gpuDurationNs, deadlineNs;
        final long callbackNanoTime = System.nanoTime(), droppedReports;
        long drawVersion = -1, drawNanoTime = -1, refreshObservedNanoTime;
        boolean crossesBegin;
        String recordedSession;
        double refreshHz, modeHz;
        int displayId;
        Frame(FrameMetrics metrics, int dropped) {
            intendedVsyncNs = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
            vsyncNs = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
            totalDurationNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME);
            gpuDurationNs = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.GPU_DURATION) : -1;
            deadlineNs = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.DEADLINE) : -1;
            droppedReports = dropped;
        }
        Bundle bundle(int index) {
            Bundle result = new Bundle(); result.putInt("index", index);
            result.putLong("intendedVsyncNs", intendedVsyncNs); result.putLong("vsyncNs", vsyncNs);
            result.putLong("totalDurationNs", totalDurationNs); result.putLong("firstDraw", firstDraw);
            result.putLong("gpuDurationNs", gpuDurationNs); result.putLong("deadlineNs", deadlineNs);
            result.putLong("callbackNanoTime", callbackNanoTime); result.putLong("droppedReports", droppedReports);
            result.putDouble("refreshHz", refreshHz); result.putDouble("modeHz", modeHz);
            result.putInt("displayId", displayId); result.putLong("drawVersion", drawVersion);
            result.putLong("drawNanoTime", drawNanoTime); result.putBoolean("crossesBegin", crossesBegin);
            result.putLong("refreshObservedNanoTime", refreshObservedNanoTime);
            return result;
        }
    }
    static final class DrawObservation {
        final String instance;
        final long sequence, version, nanoTime;
        final boolean finalState;
        DrawObservation(String instance, long sequence, long version, long nanoTime, boolean finalState) {
            this.instance = instance; this.sequence = sequence; this.version = version;
            this.nanoTime = nanoTime; this.finalState = finalState;
        }
    }
    private static final class MemorySample {
        final long startedNanoTime, endedNanoTime, totalPssKb, dalvikPssKb, nativePssKb, otherPssKb, graphicsSummaryKb;
        MemorySample() {
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            startedNanoTime = System.nanoTime();
            Debug.getMemoryInfo(memory);
            endedNanoTime = System.nanoTime();
            totalPssKb = memory.getTotalPss();
            dalvikPssKb = memory.dalvikPss; nativePssKb = memory.nativePss; otherPssKb = memory.otherPss;
            String graphics = memory.getMemoryStat("summary.graphics");
            graphicsSummaryKb = graphics == null ? -1 : Long.parseLong(graphics);
        }
        void append(Bundle result, String prefix) {
            result.putLong(prefix + "PssStartedNanoTime", startedNanoTime);
            result.putLong(prefix + "PssEndedNanoTime", endedNanoTime);
            result.putLong(prefix + "DalvikPssKb", dalvikPssKb);
            result.putLong(prefix + "NativePssKb", nativePssKb);
            result.putLong(prefix + "OtherPssKb", otherPssKb);
            // A separately reported classification, not an extra addend to total PSS.
            result.putLong(prefix + "GraphicsSummaryKb", graphicsSummaryKb);
        }
    }
    private PerformanceProbe() { }
}
