package dev.agentos.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.agentos.core.TaskResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Fixed synthetic task/stop/recovery stress, with committed bounded segments rather than a growing trace. */
@RunWith(AndroidJUnit4.class)
public final class SegmentedStressInstrumentedTest {
    private static final int SEGMENTS = 30, MAX_SAMPLES = 128;
    private static final long SEGMENT_MILLIS = 60_000, RECOVERY_MILLIS = 10_000, SAMPLE_MILLIS = 5_000;
    private static final long OVERALL_MILLIS = 35 * 60_000L;
    private static final int MAX_REPORT_BYTES = 65_536, MAX_SEGMENT_BYTES = 4 * 1024 * 1024;
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    private static final String REPORT = "stress-diagnostics.json";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int AUTOMATION_FLAGS = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private long overallDeadlineNanos = Long.MAX_VALUE;

    @Test public void collectThirtyMinuteTaskStopAndServiceCycles() throws Exception {
        JsonObject report = reportHeader();
        JsonArray references = new JsonArray();
        report.add("segments", references);
        writeReport(report); // A hard interruption can retain a committed, explicitly incomplete prefix.
        AgentController controller = null;
        String originalServices = null, originalEnabled = null;
        boolean servicesRead = false, enabledRead = false, flagsRead = false, passed = false;
        int originalFlags = 0;
        try {
            controller = AgentController.get(context);
            assertFalse("Stress requires an idle dedicated synthetic device", controller.getState().running);
            originalServices = secureRead(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            servicesRead = true;
            originalEnabled = secureRead(Settings.Secure.ACCESSIBILITY_ENABLED);
            enabledRead = true;
            AccessibilityServiceInfo info = automation.getServiceInfo();
            originalFlags = info.flags;
            flagsRead = true;
            info.flags |= AUTOMATION_FLAGS;
            automation.setServiceInfo(info);
            String withoutAgent = withoutAgent(originalServices);
            ensureServiceOff(withoutAgent);
            launchFixture();
            screenshots.capture("segmented-stress-before");

            long started = SystemClock.elapsedRealtimeNanos();
            overallDeadlineNanos = started + OVERALL_MILLIS * 1_000_000;
            report.addProperty("startedNanos", started);
            writeReport(report);
            for (int index = 0; index < SEGMENTS; index++) {
                checkOverall();
                Segment segment = new Segment(index);
                try (Segment ignored = segment) {
                    runActive(controller, segment);
                    ensureServiceOff(withoutAgent);
                    runStop(controller, segment);
                    ensureServiceOff(withoutAgent);
                    runRecovery(controller, segment);
                    checkOverall();
                    segment.passed = true;
                } finally {
                    // Only the current segment's samples/events remain live. The root
                    // contains small immutable references, not earlier segment objects.
                    commitSegment(report, references, segment);
                }
            }
            assertEquals(SEGMENTS, references.size());
            long measured = 0;
            for (int index = 0; index < references.size(); index++) {
                JsonObject reference = references.get(index).getAsJsonObject();
                assertEquals("passed", reference.get("status").getAsString());
                measured += reference.get("endedNanos").getAsLong() - reference.get("startedNanos").getAsLong();
            }
            assertTrue("Thirty measured minutes were not completed", measured >= SEGMENTS * SEGMENT_MILLIS * 1_000_000);
            screenshots.capture("segmented-stress-after");
            passed = true;
        } finally {
            boolean controllerCleared = false, servicesRestored = false, enabledRestored = false, flagsRestored = !flagsRead;
            try {
                if (controller != null) {
                    clearController(controller);
                    controllerCleared = true;
                }
            } finally {
                try {
                    if (servicesRead) {
                        secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices);
                        servicesRestored = true;
                    }
                } finally {
                    try {
                        if (enabledRead) {
                            secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled);
                            enabledRestored = true;
                        }
                    } finally {
                        try {
                            if (flagsRead) {
                                AccessibilityServiceInfo restored = automation.getServiceInfo();
                                restored.flags = (restored.flags & ~AUTOMATION_FLAGS) | (originalFlags & AUTOMATION_FLAGS);
                                automation.setServiceInfo(restored);
                                flagsRestored = true;
                            }
                        } finally {
                            JsonObject cleanup = report.getAsJsonObject("cleanup");
                            cleanup.addProperty("controllerCleared", controllerCleared);
                            cleanup.addProperty("servicesRestored", servicesRestored);
                            cleanup.addProperty("enabledRestored", enabledRestored);
                            report.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                            report.addProperty("status", passed && controllerCleared && servicesRestored && enabledRestored && flagsRestored
                                    ? "passed" : "failed");
                            writeReport(report);
                        }
                    }
                }
            }
        }
    }

    private void runActive(AgentController controller, Segment segment) throws Exception {
        segment.phase = "setup";
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            checkOverall();
            segment.phase = "active";
            try (TaskPhase phase = new TaskPhase(false, segment)) {
                try {
                    instrumentation.runOnMainSync(() -> controller.start(settings.model,
                            "Save the synthetic sample and increment the fixture counter once.",
                            Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, settings.lease));
                    Set<String> approved = new HashSet<>();
                    long deadline = deadlineAfter(30_000);
                    while (controller.getState().running && SystemClock.elapsedRealtimeNanos() < deadline) {
                        server.assertHealthy();
                        AgentController.State state = controller.getState();
                        if (!state.approvalId.isEmpty() && approved.add(state.approvalId)) {
                            instrumentation.runOnMainSync(() -> controller.approve(state.approvalId, true));
                        }
                        SystemClock.sleep(25);
                    }
                    assertFalse("Synthetic completion task exceeded its diagnostic window", controller.getState().running);
                    server.assertHealthy();
                    phase.captureState(controller, server);
                    assertEquals(TaskResult.Kind.MODEL_REPORT, controller.getState().resultKind);
                    assertEquals("MODEL_REPORT", controller.getState().resultCode);
                    assertEquals(3, server.acceptedWriteCount());
                    assertTrue(server.finalStateObserved());
                    assertFixtureText("counter_value", "Count: 1");
                    phase.result.addProperty("counterVerified", true);
                    assertFixtureText("saved_value", "Saved: " + LocalControllerModelServer.TEXT);
                    phase.result.addProperty("savedTextVerified", true);
                    phase.passed = true;
                } finally {
                    phase.captureState(controller, server);
                }
            } finally {
                segment.phase = "setup";
                clearController(controller);
            }
        }
    }

    private void runStop(AgentController controller, Segment segment) throws Exception {
        segment.phase = "setup";
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            checkOverall();
            segment.phase = "stop";
            try (TaskPhase phase = new TaskPhase(true, segment)) {
                try {
                    instrumentation.runOnMainSync(() -> controller.start(settings.model,
                            "Prepare one synthetic increment and wait for confirmation.",
                            Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, settings.lease));
                    await("Synthetic stop task did not reach confirmation", () -> {
                        server.assertHealthy();
                        return !controller.getState().approvalId.isEmpty();
                    }, 15_000);
                    phase.result.addProperty("approvalReached", true);
                    phase.result.addProperty("stopEnqueuedNanos", SystemClock.elapsedRealtimeNanos());
                    assertTrue("Main thread rejected the stop request", new Handler(Looper.getMainLooper()).post(controller::stop));
                    await("Stopped generation did not apply to the local UI", () -> {
                        for (PerformanceTrace.Event event : phase.trace.events()) {
                            if (event.stage == PerformanceTrace.Stage.STOP_UI_APPLIED) {
                                phase.result.addProperty("stopStateAppliedNanos", event.startNanos + event.durationNanos);
                                return true;
                            }
                        }
                        return false;
                    }, 5_000);
                    assertFalse(controller.getState().running);
                    boundedSleep(500); // A finite post-stop observation, not an all-future claim.
                    server.assertHealthy();
                    phase.captureState(controller, server);
                    assertEquals(TaskResult.Kind.STOPPED, controller.getState().resultKind);
                    assertEquals("TASK_STOPPED", controller.getState().resultCode);
                    assertEquals(0, server.acceptedWriteCount());
                    assertFixtureText("counter_value", "Count: 0");
                    phase.result.addProperty("counterVerified", true);
                    phase.passed = true;
                } finally {
                    phase.captureState(controller, server);
                }
            } finally {
                segment.phase = "setup";
                clearController(controller);
            }
        }
    }

    private void runRecovery(AgentController controller, Segment segment) throws Exception {
        JsonObject before = readState(controller);
        PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
        JsonObject result = new JsonObject();
        result.addProperty("status", "failed");
        result.add("stateBefore", before);
        result.add("stateAfter", JsonNull.INSTANCE);
        result.addProperty("startedNanos", trace.startedNanos);
        segment.result.add("recovery", result);
        segment.phase = "recovery";
        boolean completed = false;
        try {
            assertRecoveryState(before);
            long until = Math.max(trace.startedNanos + RECOVERY_MILLIS * 1_000_000,
                    segment.startedNanos + SEGMENT_MILLIS * 1_000_000);
            while (SystemClock.elapsedRealtimeNanos() < until) {
                checkOverall();
                SystemClock.sleep(Math.min(100, Math.max(1, (until - SystemClock.elapsedRealtimeNanos()) / 1_000_000)));
            }
            completed = true;
        } finally {
            freezeTrace(result, trace);
        }
        JsonObject after = readState(controller);
        result.add("stateAfter", after);
        assertRecoveryState(after);
        assertTrue(completed);
        assertEquals("Recovery trace was overwritten", 0, trace.overwrittenCount());
        assertTrue("Service-off recovery unexpectedly performed traced execution work", trace.events().isEmpty());
        assertTrue("Recovery was shorter than its fixed minimum", result.get("endedNanos").getAsLong()
                - result.get("startedNanos").getAsLong() >= RECOVERY_MILLIS * 1_000_000);
        result.addProperty("status", "passed");
    }

    private final class TaskPhase implements AutoCloseable {
        final JsonObject result = new JsonObject();
        final PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
        final boolean stop;
        boolean passed;

        TaskPhase(boolean stop, Segment segment) {
            this.stop = stop;
            result.addProperty("status", "failed");
            result.addProperty("startedNanos", trace.startedNanos);
            result.addProperty("resultKind", "");
            result.addProperty("resultCode", "");
            result.addProperty("acceptedWrites", -1);
            result.addProperty("counterVerified", false);
            if (stop) {
                result.addProperty("approvalReached", false);
                result.addProperty("stopEnqueuedNanos", -1);
                result.addProperty("stopStateAppliedNanos", -1);
            } else {
                result.addProperty("finalStateObserved", false);
                result.addProperty("savedTextVerified", false);
            }
            segment.result.add(stop ? "stop" : "active", result);
        }

        void captureState(AgentController controller, LocalControllerModelServer server) {
            AgentController.State state = controller.getState();
            result.addProperty("resultKind", state.resultKind == null ? "" : state.resultKind.name());
            result.addProperty("resultCode", state.resultCode == null ? "" : state.resultCode);
            result.addProperty("acceptedWrites", server.acceptedWriteCount());
            if (!stop) result.addProperty("finalStateObserved", server.finalStateObserved());
        }

        @Override public void close() {
            freezeTrace(result, trace);
            if (!passed) return;
            assertEquals("Task trace events were overwritten", 0, trace.overwrittenCount());
            if (stop) verifyStoppedTrace(result, trace);
            result.addProperty("status", "passed");
        }
    }

    private static void verifyStoppedTrace(JsonObject result, PerformanceTrace.Session trace) {
        PerformanceTrace.Event received = null, invalidated = null, applied = null;
        for (PerformanceTrace.Event event : trace.events()) {
            assertNotEquals("A pending-approval stop submitted a device action", PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
            if (event.stage == PerformanceTrace.Stage.STOP_RECEIVED) {
                assertNull("Duplicate stop receipt", received);
                received = event;
            }
            if (event.stage == PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED) {
                assertNull("Duplicate stop invalidation", invalidated);
                invalidated = event;
            }
            if (event.stage == PerformanceTrace.Stage.STOP_UI_APPLIED) {
                assertNull("Duplicate stop UI result", applied);
                applied = event;
            }
        }
        assertNotNull("Stop receipt is missing", received);
        assertNotNull("Stop invalidation is missing", invalidated);
        assertNotNull("Stop UI result is missing", applied);
        assertEquals(0, invalidated.dispatchAttempts);
        assertTrue(received.startNanos >= result.get("stopEnqueuedNanos").getAsLong());
        assertTrue(invalidated.startNanos >= received.startNanos + received.durationNanos);
        assertTrue(applied.startNanos >= invalidated.startNanos + invalidated.durationNanos);
        assertEquals(applied.startNanos + applied.durationNanos, result.get("stopStateAppliedNanos").getAsLong());
    }

    private final class Segment implements AutoCloseable {
        final JsonObject result = new JsonObject();
        final JsonArray samples = new JsonArray();
        final long startedNanos = SystemClock.elapsedRealtimeNanos();
        final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-segmented-stress-sampler");
            thread.setDaemon(true);
            return thread;
        });
        volatile String phase = "setup";
        volatile boolean sampleLimitReached;
        volatile Throwable sampleFailure;
        boolean samplesClosed, passed;

        Segment(int index) {
            result.addProperty("schemaVersion", 1);
            result.addProperty("kind", "agent-stress-segment");
            result.addProperty("index", index);
            result.addProperty("status", "failed");
            result.addProperty("startedNanos", startedNanos);
            result.add("active", JsonNull.INSTANCE);
            result.add("stop", JsonNull.INSTANCE);
            result.add("recovery", JsonNull.INSTANCE);
            sample();
            sampler.scheduleWithFixedDelay(this::sample, SAMPLE_MILLIS, SAMPLE_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void sample() {
            synchronized (this) {
                if (samplesClosed) return;
                if (samples.size() >= MAX_SAMPLES) { sampleLimitReached = true; return; }
            }
            try {
                long start = SystemClock.elapsedRealtimeNanos();
                String observedPhase = phase;
                long cpu = Process.getElapsedCpuTime();
                Debug.MemoryInfo memory = new Debug.MemoryInfo();
                Debug.getMemoryInfo(memory);
                JsonObject point = new JsonObject();
                point.addProperty("elapsedRealtimeNanos", start);
                point.addProperty("processCpuMillis", cpu);
                point.addProperty("totalPssKiB", memory.getTotalPss());
                point.addProperty("dalvikPssKiB", memory.dalvikPss);
                point.addProperty("nativePssKiB", memory.nativePss);
                point.addProperty("otherPssKiB", memory.otherPss);
                point.addProperty("collectionNanos", SystemClock.elapsedRealtimeNanos() - start);
                point.addProperty("phase", observedPhase);
                synchronized (this) { if (!samplesClosed) samples.add(point); }
            } catch (Throwable failure) { sampleFailure = failure; }
        }

        @Override public void close() throws Exception {
            sampler.shutdownNow();
            boolean collected = false;
            try {
                assertTrue("Stress resource sampler did not terminate", sampler.awaitTermination(3, TimeUnit.SECONDS));
                sample();
                assertNull("Stress resource sampling failed", sampleFailure);
                assertFalse("Stress sample cap reached", sampleLimitReached);
                if (passed) {
                    assertTrue("Stress segment was shorter than one minute", SystemClock.elapsedRealtimeNanos()
                            - startedNanos >= SEGMENT_MILLIS * 1_000_000);
                    assertTrue("Stress segment has too few resource samples", samples.size() >= 10);
                    assertTrue("Stress samples cover less than 55 seconds", samples.get(samples.size() - 1).getAsJsonObject()
                            .get("elapsedRealtimeNanos").getAsLong() - samples.get(0).getAsJsonObject()
                            .get("elapsedRealtimeNanos").getAsLong() >= 55_000_000_000L);
                }
                collected = true;
            } finally {
                synchronized (this) { samplesClosed = true; }
                result.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                synchronized (this) { result.add("samples", samples.deepCopy()); }
                result.addProperty("sampleLimitReached", sampleLimitReached);
                result.addProperty("status", passed && collected ? "passed" : "failed");
            }
        }
    }

    private void commitSegment(JsonObject report, JsonArray references, Segment segment) throws Exception {
        int index = segment.result.get("index").getAsInt();
        assertEquals("Segment commits must be an exact prefix", references.size(), index);
        String name = String.format(Locale.ROOT, "segment-%03d.json", index);
        byte[] bytes = encode(segment.result, MAX_SEGMENT_BYTES);
        writeAtomic(name, bytes);
        JsonObject reference = new JsonObject();
        reference.addProperty("index", index);
        reference.addProperty("path", name);
        reference.addProperty("bytes", bytes.length);
        reference.addProperty("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        reference.addProperty("status", segment.result.get("status").getAsString());
        reference.addProperty("startedNanos", segment.result.get("startedNanos").getAsLong());
        reference.addProperty("endedNanos", segment.result.get("endedNanos").getAsLong());
        references.add(reference);
        writeReport(report);
    }

    private void writeReport(JsonObject report) throws Exception { writeAtomic(REPORT, encode(report, MAX_REPORT_BYTES)); }

    private static byte[] encode(JsonObject value, int maximum) {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IllegalStateException("Segmented diagnostic file exceeded its fixed byte cap");
        return bytes;
    }

    private void writeAtomic(String name, byte[] bytes) throws Exception {
        File directory = new File(context.getFilesDir(), "performance/stress");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create stress diagnostic directory");
        AtomicFile file = new AtomicFile(new File(directory, name));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) file.failWrite(stream);
            throw failure;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }

    private static void freezeTrace(JsonObject result, PerformanceTrace.Session trace) {
        trace.close();
        result.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
        JsonArray entries = new JsonArray();
        for (PerformanceTrace.Event event : trace.events()) {
            JsonObject value = new JsonObject();
            value.addProperty("stage", event.stage.name());
            value.addProperty("startNanos", event.startNanos);
            value.addProperty("durationNanos", event.durationNanos);
            value.addProperty("mainThread", event.mainThread);
            value.addProperty("nodeCount", event.nodeCount);
            value.addProperty("snapshotJsonBytes", event.snapshotJsonBytes);
            value.addProperty("truncated", event.truncated);
            value.addProperty("epoch", event.epoch);
            value.addProperty("dispatchAttempts", event.dispatchAttempts);
            entries.add(value);
        }
        result.add("trace", entries);
        result.addProperty("traceCapacity", PerformanceTrace.CAPACITY);
        result.addProperty("traceOverwrittenEvents", trace.overwrittenCount());
    }

    private JsonObject reportHeader() {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("kind", "agent-segmented-stress-diagnostics");
        report.addProperty("diagnosticOnly", true);
        report.addProperty("budgetAcceptance", false);
        report.addProperty("status", "incomplete");
        report.addProperty("appProcessPid", Process.myPid());
        report.addProperty("startedNanos", SystemClock.elapsedRealtimeNanos());
        report.addProperty("endedNanos", -1);
        JsonObject cleanup = new JsonObject();
        cleanup.addProperty("controllerCleared", false);
        cleanup.addProperty("servicesRestored", false);
        cleanup.addProperty("enabledRestored", false);
        report.add("cleanup", cleanup);
        JsonObject device = new JsonObject();
        device.addProperty("sdkInt", Build.VERSION.SDK_INT);
        device.addProperty("manufacturer", Build.MANUFACTURER);
        device.addProperty("model", Build.MODEL);
        device.addProperty("hardware", Build.HARDWARE);
        device.addProperty("fingerprint", Build.FINGERPRINT);
        device.addProperty("emulatorHeuristic", Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")
                || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"));
        report.add("device", device);
        JsonObject conditions = new JsonObject();
        conditions.addProperty("buildType", "debug");
        conditions.addProperty("processScope", "instrumented_app_with_sampler");
        conditions.addProperty("fixtureProcessIncluded", false);
        conditions.addProperty("localModelScope", "same_process");
        conditions.addProperty("minimumDurationMillis", SEGMENTS * SEGMENT_MILLIS);
        conditions.addProperty("segmentCount", SEGMENTS);
        conditions.addProperty("minimumSegmentMillis", SEGMENT_MILLIS);
        conditions.addProperty("recoveryMinimumMillis", RECOVERY_MILLIS);
        conditions.addProperty("sampleIntervalMillis", SAMPLE_MILLIS);
        conditions.addProperty("maxSamplesPerSegment", MAX_SAMPLES);
        conditions.addProperty("maxTraceEventsPerTask", PerformanceTrace.CAPACITY);
        conditions.addProperty("taskOrder", "SAVE_AND_COUNT_STOP_WAITING_APPROVAL_SERVICE_OFF");
        conditions.addProperty("fixtureReset", "per_task");
        conditions.addProperty("forcedGc", false);
        conditions.addProperty("systemAnimationOverrides", false);
        conditions.addProperty("screenshotSamplingIncluded", false);
        report.add("conditions", conditions);
        return report;
    }

    private JsonObject readState(AgentController controller) {
        boolean fixtureFocused = fixtureFocused();
        JsonObject state = new JsonObject();
        instrumentation.runOnMainSync(() -> {
            AgentAccessibilityService service = AgentAccessibilityService.getInstance();
            state.addProperty("servicePresent", service != null);
            state.addProperty("serviceConnected", service != null && service.isConnected());
            state.addProperty("controllerRunning", controller.getState().running);
            state.addProperty("controlAvailable", service != null && service.isControlAvailable());
        });
        state.addProperty("fixtureFocused", fixtureFocused);
        state.addProperty("checkedNanos", SystemClock.elapsedRealtimeNanos());
        return state;
    }

    private static void assertRecoveryState(JsonObject state) {
        assertFalse("Recovery service still present", state.get("servicePresent").getAsBoolean());
        assertFalse("Recovery service still connected", state.get("serviceConnected").getAsBoolean());
        assertFalse("Recovery task still running", state.get("controllerRunning").getAsBoolean());
        assertFalse("Recovery control overlay still present", state.get("controlAvailable").getAsBoolean());
        assertTrue("Recovery fixture no longer focused", state.get("fixtureFocused").getAsBoolean());
    }

    private boolean fixtureFocused() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null) return false;
        int focused = 0;
        boolean fixture = false;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                focused++;
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try { fixture = DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()); }
                finally { root.recycle(); }
            }
            return focused == 1 && fixture;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private void assertFixtureText(String viewId, String expected) {
        await("Expected synthetic fixture value is not visible: " + viewId, () -> {
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            if (windows == null) return false;
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (!window.isFocused() || window.getDisplayId() != Display.DEFAULT_DISPLAY
                            || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    try {
                        if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/" + viewId);
                        try {
                            return nodes.size() == 1 && nodes.get(0).isVisibleToUser()
                                    && expected.contentEquals(nodes.get(0).getText() == null ? "" : nodes.get(0).getText());
                        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                    } finally { root.recycle(); }
                }
                return false;
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        }, 5_000);
    }

    private void clearController(AgentController controller) {
        instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
    }

    private void ensureServiceOff(String withoutAgent) {
        secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withoutAgent);
        await("Stress Agent service did not disconnect", () -> AgentAccessibilityService.getInstance() == null, 8_000);
        instrumentation.waitForIdleSync();
    }

    private void launchFixture() {
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent()
                .setComponent(new ComponentName(DeviceHarness.FIXTURE, DeviceHarness.FIXTURE + ".FixtureActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)));
        instrumentation.waitForIdleSync();
        try { automation.waitForIdle(500, 8_000); }
        catch (Exception failure) { throw new AssertionError("Synthetic fixture did not become idle", failure); }
    }

    private static String withoutAgent(String services) {
        StringBuilder kept = new StringBuilder();
        ComponentName agent = ComponentName.unflattenFromString(COMPONENT);
        if (services != null) for (String component : services.split(":")) {
            if (component.isEmpty() || agent.equals(ComponentName.unflattenFromString(component))) continue;
            if (kept.length() > 0) kept.append(':');
            kept.append(component);
        }
        return kept.toString();
    }

    private String secureRead(String key) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try { return Settings.Secure.getString(context.getContentResolver(), key); }
        finally { automation.dropShellPermissionIdentity(); }
    }

    private void secureWrite(String key, String value) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try { assertTrue(Settings.Secure.putString(context.getContentResolver(), key, value)); }
        finally { automation.dropShellPermissionIdentity(); }
    }

    private long deadlineAfter(long millis) {
        return Math.min(overallDeadlineNanos, SystemClock.elapsedRealtimeNanos() + millis * 1_000_000);
    }

    private void checkOverall() { assertTrue("Segmented stress exceeded its 35-minute instrumentation budget",
            SystemClock.elapsedRealtimeNanos() < overallDeadlineNanos); }

    private void boundedSleep(long millis) {
        long until = deadlineAfter(millis);
        while (SystemClock.elapsedRealtimeNanos() < until) SystemClock.sleep(25);
        checkOverall();
    }

    private void await(String failure, BooleanSupplier ready, long millis) {
        long deadline = deadlineAfter(millis);
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(25);
        }
        fail(failure);
    }
}
