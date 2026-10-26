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
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Short diagnostic samples, not a physical-device or release performance-budget benchmark. */
@RunWith(AndroidJUnit4.class)
public final class PerformanceDiagnosticsInstrumentedTest {
    private static final long WINDOW_MILLIS = 6_000, SAMPLE_MILLIS = 1_000;
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);

    @Test public void collectServiceOffIdleActiveAndStopDiagnostics() throws Exception {
        JsonObject report = reportHeader();
        JsonArray modes = new JsonArray();
        report.add("modes", modes);
        boolean passed = false;
        AgentController controller = AgentController.get(context);
        assertFalse("Diagnostics require an idle dedicated synthetic test device", controller.getState().running);
        String originalServices = secureRead(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String originalEnabled = secureRead(Settings.Secure.ACCESSIBILITY_ENABLED);
        try {
            String withoutAgent = withoutAgent(originalServices);
            secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withoutAgent);
            await("Agent service did not disconnect", () -> AgentAccessibilityService.getInstance() == null, 8_000);
            launchFixture();
            try (SampleWindow window = new SampleWindow("A_SERVICE_OFF_RESIDENT_PROCESS", modes)) {
                SystemClock.sleep(WINDOW_MILLIS);
                assertNull(AgentAccessibilityService.getInstance());
                window.passed = true;
            }
            screenshots.capture("A-service-off-resident-process");

            secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    withoutAgent.isEmpty() ? COMPONENT : withoutAgent + ":" + COMPONENT);
            secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            await("Agent service did not connect", () -> AgentAccessibilityService.getInstance() != null
                    && AgentAccessibilityService.getInstance().isConnected(), 8_000);
            instrumentation.waitForIdleSync();
            // No DeviceHarness, task, adapter, tree read or overlay is created for B.
            instrumentation.runOnMainSync(() -> assertFalse(AgentAccessibilityService.getInstance().isControlAvailable()));
            try (SampleWindow window = new SampleWindow("B_SERVICE_ON_IDLE", modes)) {
                SystemClock.sleep(WINDOW_MILLIS);
                assertFalse(controller.getState().running);
                instrumentation.runOnMainSync(() -> {
                    AgentAccessibilityService service = AgentAccessibilityService.getInstance();
                    assertNotNull("Idle service disconnected during sampling", service);
                    assertTrue(service.isConnected());
                    assertFalse(service.isControlAvailable());
                });
                assertTrue("Idle Agent unexpectedly observed or dispatched", window.trace.events().isEmpty());
                window.passed = true;
            }
            screenshots.capture("B-service-on-idle");

            runActive(controller, modes);
            runStop(controller, modes);
            passed = true;
        } finally {
            boolean controllerCleared = false, servicesRestored = false, enabledRestored = false;
            try {
                instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
                controllerCleared = true;
            }
            finally {
                try {
                    secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices);
                    servicesRestored = true;
                }
                finally {
                    try {
                        secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled);
                        enabledRestored = true;
                    } finally {
                        report.addProperty("status", passed && controllerCleared && servicesRestored && enabledRestored ? "passed" : "failed");
                        writeReport(report);
                    }
                }
            }
        }
    }

    private void runActive(AgentController controller, JsonArray modes) throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            try (SampleWindow window = new SampleWindow("C_LOCAL_MODEL_FIXTURE", modes)) {
                instrumentation.runOnMainSync(() -> controller.start(settings.model,
                        "Save the synthetic sample and increment the fixture counter once.",
                        Collections.singleton(DeviceHarness.FIXTURE), true, settings.lease));
                Set<String> approved = new HashSet<>();
                long deadline = SystemClock.elapsedRealtime() + 30_000;
                while (controller.getState().running && SystemClock.elapsedRealtime() < deadline) {
                    server.assertHealthy();
                    AgentController.State state = controller.getState();
                    if (!state.approvalId.isEmpty() && approved.add(state.approvalId)) {
                        instrumentation.runOnMainSync(() -> controller.approve(state.approvalId, true));
                    }
                    SystemClock.sleep(25);
                }
                assertFalse("Synthetic task exceeded its diagnostic window", controller.getState().running);
                server.assertHealthy();
                assertEquals(3, server.acceptedWriteCount());
                assertTrue(server.finalStateObserved());
                assertFixtureText("counter_value", "Count: 1");
                assertFixtureText("saved_value", "Saved: " + LocalControllerModelServer.TEXT);
                assertTrue(window.trace.events().stream().anyMatch(event -> event.stage == PerformanceTrace.Stage.SNAPSHOT_OBSERVE && !event.mainThread));
                assertTrue(window.trace.events().stream().anyMatch(event -> event.stage == PerformanceTrace.Stage.SNAPSHOT_FINAL_MUTATION && !event.mainThread));
                assertFalse(window.trace.events().stream().anyMatch(event -> event.stage == PerformanceTrace.Stage.SNAPSHOT_FINAL_MUTATION && event.mainThread));
                window.passed = true;
            } finally {
                instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
            }
            screenshots.capture("C-local-model-verified-fixture-actions");
        }
    }

    private void runStop(AgentController controller, JsonArray modes) throws Exception {
        // A separate harness supplies a fresh fixture and control identity. The previous
        // task intentionally removed its overlay; its device tools must not be revived.
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            try (SampleWindow window = new SampleWindow("STOP_WAITING_APPROVAL", modes)) {
                instrumentation.runOnMainSync(() -> controller.start(settings.model,
                        "Prepare one synthetic increment and wait for confirmation.",
                        Collections.singleton(DeviceHarness.FIXTURE), true, settings.lease));
                await("Synthetic stop trial did not reach confirmation", () -> {
                    server.assertHealthy();
                    return !controller.getState().approvalId.isEmpty();
                }, 15_000);
                window.stopEnqueuedNanos = SystemClock.elapsedRealtimeNanos();
                new Handler(Looper.getMainLooper()).post(controller::stop);
                await("Stopped generation did not apply to the local UI", () -> {
                    for (PerformanceTrace.Event event : window.trace.events()) {
                        if (event.stage == PerformanceTrace.Stage.STOP_UI_APPLIED) {
                            window.stopStateAppliedNanos = event.startNanos + event.durationNanos;
                            return true;
                        }
                    }
                    return false;
                }, 5_000);
                assertFalse(controller.getState().running);
                // Observe a bounded quiet interval, not a promise about all future time.
                SystemClock.sleep(500);
                long invalidatedAt = -1;
                int stopRequests = 0, invalidations = 0;
                for (PerformanceTrace.Event event : window.trace.events()) {
                    if (event.stage == PerformanceTrace.Stage.STOP_RECEIVED) stopRequests++;
                    if (event.stage == PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED) {
                        invalidatedAt = event.startNanos;
                        invalidations++;
                        assertEquals(0, event.dispatchAttempts);
                    }
                    if (event.stage == PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT && invalidatedAt >= 0) {
                        fail("A device action was submitted after stop invalidation");
                    }
                }
                assertEquals(1, stopRequests);
                assertEquals(1, invalidations);
                assertFixtureText("counter_value", "Count: 0");
                server.assertHealthy();
                assertEquals(0, server.acceptedWriteCount());
                // Idle stop is a no-op and must not create another successful stop sample.
                instrumentation.runOnMainSync(controller::stop);
                assertEquals(1, window.trace.events().stream().filter(event -> event.stage == PerformanceTrace.Stage.STOP_RECEIVED).count());
                window.passed = true;
            } finally {
                instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
            }
            screenshots.capture("stop-applied-without-fixture-write");
        }
    }

    private final class SampleWindow implements AutoCloseable {
        final JsonObject result = new JsonObject();
        final JsonArray samples = new JsonArray();
        final PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
        final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-performance-sampler"); thread.setDaemon(true); return thread;
        });
        boolean passed;
        boolean samplesClosed;
        volatile boolean sampleLimitReached;
        volatile Throwable sampleFailure;
        long stopEnqueuedNanos = -1, stopStateAppliedNanos = -1;

        SampleWindow(String mode, JsonArray modes) {
            result.addProperty("mode", mode);
            result.addProperty("startedNanos", trace.startedNanos);
            modes.add(result);
            sample();
            sampler.scheduleWithFixedDelay(this::sample, SAMPLE_MILLIS, SAMPLE_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void sample() {
            synchronized (this) {
                if (samplesClosed) return;
                if (samples.size() >= 128) { sampleLimitReached = true; return; }
            }
            try {
                long start = SystemClock.elapsedRealtimeNanos();
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
                // Native memory inspection never holds the result-copy monitor.
                synchronized (this) { if (!samplesClosed) samples.add(point); }
            } catch (Throwable failure) { sampleFailure = failure; }
        }

        @Override public void close() throws Exception {
            sampler.shutdownNow();
            boolean collected = false;
            try {
                assertTrue("Diagnostic sampler did not terminate", sampler.awaitTermination(3, TimeUnit.SECONDS));
                sample();
                assertNull("Numeric resource sampling failed", sampleFailure);
                assertFalse("Resource sample cap reached", sampleLimitReached);
                assertEquals("Diagnostic phase events were overwritten", 0, trace.overwrittenCount());
                if (stopEnqueuedNanos >= 0) {
                    long received = stageStart(PerformanceTrace.Stage.STOP_RECEIVED);
                    long invalidated = stageStart(PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED);
                    assertTrue("Stop request/entry timing unavailable", difference(received, stopEnqueuedNanos) >= 0);
                    assertTrue("Stop entry/invalidation timing unavailable", difference(invalidated, received) >= 0);
                    assertTrue("Stop request/UI timing unavailable", difference(stopStateAppliedNanos, stopEnqueuedNanos) >= 0);
                }
                collected = true;
            } finally {
                // Even an unexpectedly slow native sampler cannot append after the
                // failure report's time boundary or mutate its captured samples.
                synchronized (this) { samplesClosed = true; }
                trace.close();
                result.addProperty("status", passed && collected ? "passed" : "failed");
                result.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                synchronized (this) { result.add("samples", samples.deepCopy()); }
                result.addProperty("sampleLimitReached", sampleLimitReached);
                result.add("trace", traceJson(trace));
                result.addProperty("traceOverwrittenEvents", trace.overwrittenCount());
                result.addProperty("traceCapacity", PerformanceTrace.CAPACITY);
                result.addProperty("stopEnqueuedNanos", stopEnqueuedNanos);
                result.addProperty("stopStateAppliedNanos", stopStateAppliedNanos);
                boolean stopTrial = "STOP_WAITING_APPROVAL".equals(result.get("mode").getAsString());
                long received = stopTrial ? stageStart(PerformanceTrace.Stage.STOP_RECEIVED) : -1;
                long invalidated = stopTrial ? stageStart(PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED) : -1;
                result.addProperty("stopRequestToReceivedNanos", difference(received, stopEnqueuedNanos));
                result.addProperty("stopReceivedToInvalidatedNanos", difference(invalidated, received));
                result.addProperty("stopRequestToUiAppliedNanos", difference(stopStateAppliedNanos, stopEnqueuedNanos));
            }
        }

        private long stageStart(PerformanceTrace.Stage stage) {
            for (PerformanceTrace.Event event : trace.events()) if (event.stage == stage) return event.startNanos;
            return -1;
        }
    }

    private static long difference(long end, long start) { return start >= 0 && end >= start ? end - start : -1; }

    private static JsonArray traceJson(PerformanceTrace.Session session) {
        JsonArray entries = new JsonArray();
        for (PerformanceTrace.Event event : session.events()) {
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
        return entries;
    }

    private JsonObject reportHeader() {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("kind", "agent-performance-diagnostics");
        report.addProperty("diagnosticOnly", true);
        report.addProperty("budgetAcceptance", false);
        report.addProperty("appProcessPid", Process.myPid());
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
        conditions.addProperty("localModelScope", "same_process_C_and_STOP_only");
        conditions.addProperty("fixtureProcessIncluded", false);
        conditions.addProperty("screenshotSamplingIncluded", false);
        conditions.addProperty("forcedGc", false);
        conditions.addProperty("systemAnimationOverrides", false);
        conditions.addProperty("windowMillis", WINDOW_MILLIS);
        conditions.addProperty("sampleIntervalMillis", SAMPLE_MILLIS);
        conditions.addProperty("order", "A_B_C_STOP");
        report.add("conditions", conditions);
        return report;
    }

    private void writeReport(JsonObject report) throws Exception {
        byte[] bytes = new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalStateException("Diagnostic report exceeded its byte cap");
        File directory = new File(context.getFilesDir(), "performance");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create diagnostic directory");
        try (FileOutputStream output = new FileOutputStream(new File(directory, "three-state-diagnostics.json"))) { output.write(bytes); }
    }

    private void launchFixture() {
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent()
                .setComponent(new ComponentName(DeviceHarness.FIXTURE, DeviceHarness.FIXTURE + ".FixtureActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)));
        instrumentation.waitForIdleSync();
        try { automation.waitForIdle(500, 8_000); }
        catch (Exception failure) { throw new AssertionError("Synthetic fixture did not become idle", failure); }
    }

    /** Test-only result read after the production task/control overlay has ended. */
    private void assertFixtureText(String viewId, String expected) {
        final int observedFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= observedFlags;
        automation.setServiceInfo(info);
        try {
            await("Expected synthetic fixture value is not visible: " + viewId, () -> {
                List<AccessibilityWindowInfo> windows = automation.getWindows();
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
        } finally {
            AccessibilityServiceInfo restored = automation.getServiceInfo();
            restored.flags = (restored.flags & ~observedFlags) | (originalFlags & observedFlags);
            automation.setServiceInfo(restored);
        }
    }

    private static String withoutAgent(String services) {
        StringBuilder kept = new StringBuilder();
        if (services != null) for (String component : services.split(":")) {
            if (component.isEmpty() || COMPONENT.equals(component)) continue;
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

    private static void await(String failure, BooleanSupplier ready, long millis) {
        long deadline = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(25);
        }
        fail(failure);
    }
}
