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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Dedicated same-device idle pairs. These bounded debug samples do not accept release budgets. */
@RunWith(AndroidJUnit4.class)
public final class IdlePairedPerformanceInstrumentedTest {
    private static final long WINDOW_MILLIS = 20_000, SETTLE_MILLIS = 2_000, SAMPLE_MILLIS = 1_000;
    private static final int PAIR_COUNT = 6;
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    private static final String MODE_A = "A_SERVICE_OFF_RESIDENT_PROCESS", MODE_B = "B_SERVICE_ON_IDLE";
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);

    @Test public void collectAlternatingServiceOffAndIdlePairs() throws Exception {
        JsonObject report = reportHeader();
        JsonArray windows = new JsonArray();
        report.add("windows", windows);
        AgentController controller = AgentController.get(context);
        String originalServices = null, originalEnabled = null;
        boolean servicesRead = false, enabledRead = false, flagsRead = false, passed = false;
        int originalFlags = 0;
        try {
            assertFalse("Idle pairs require an idle dedicated synthetic device", controller.getState().running);
            originalServices = secureRead(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            servicesRead = true;
            originalEnabled = secureRead(Settings.Secure.ACCESSIBILITY_ENABLED);
            enabledRead = true;
            AccessibilityServiceInfo info = automation.getServiceInfo();
            originalFlags = info.flags;
            flagsRead = true;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);

            String withoutAgent = withoutAgent(originalServices);
            setService(false, withoutAgent);
            launchFixture();
            screenshots.capture("paired-idle-sequence-before");
            for (int pair = 0; pair < PAIR_COUNT; pair++) {
                for (int position = 0; position < 2; position++) {
                    boolean serviceOn = (pair % 2 == 0) == (position == 1);
                    setService(serviceOn, withoutAgent);
                    // Both states receive the same fixed settling interval. No GC,
                    // screenshot, model, task or target-app reset occurs between pairs.
                    SystemClock.sleep(SETTLE_MILLIS);
                    JsonObject before = readState(controller);
                    assertState(before, serviceOn);
                    SampleWindow window = new SampleWindow(pair, position, serviceOn, before, windows);
                    try (SampleWindow ignored = window) {
                        SystemClock.sleep(WINDOW_MILLIS);
                        window.completedWindow = true;
                    }
                    // Service/foreground checks happen after the resource/trace window.
                    // They are endpoint observations, not proof of continuous state.
                    JsonObject after = readState(controller);
                    window.result.add("stateAfter", after);
                    assertState(after, serviceOn);
                    assertTrue("Numeric sampling did not finish", window.collected);
                    window.result.addProperty("status", "passed");
                }
            }
            screenshots.capture("paired-idle-sequence-after");
            passed = true;
        } finally {
            boolean servicesRestored = !servicesRead, enabledRestored = !enabledRead, flagsRestored = !flagsRead;
            try {
                if (servicesRead) secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices);
                servicesRestored = true;
            } finally {
                try {
                    if (enabledRead) secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled);
                    enabledRestored = true;
                } finally {
                    try {
                        if (flagsRead) {
                            AccessibilityServiceInfo restored = automation.getServiceInfo();
                            int mask = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                            restored.flags = (restored.flags & ~mask) | (originalFlags & mask);
                            automation.setServiceInfo(restored);
                        }
                        flagsRestored = true;
                    } finally {
                        report.addProperty("status", passed && servicesRestored && enabledRestored && flagsRestored
                                ? "passed" : "failed");
                        writeReport(report);
                    }
                }
            }
        }
    }

    private void setService(boolean enabled, String withoutAgent) {
        secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabled ? (withoutAgent.isEmpty() ? COMPONENT : withoutAgent + ":" + COMPONENT) : withoutAgent);
        if (enabled) secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
        await(enabled ? "Idle Agent service did not connect" : "Idle Agent service did not disconnect", () -> {
            AgentAccessibilityService service = AgentAccessibilityService.getInstance();
            return enabled ? service != null && service.isConnected() : service == null;
        }, 8_000);
        instrumentation.waitForIdleSync();
    }

    private JsonObject readState(AgentController controller) {
        // UI automation only checks the synthetic foreground outside each measured window.
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

    private static void assertState(JsonObject state, boolean serviceOn) {
        assertEquals("Unexpected service instance at a window endpoint", serviceOn, state.get("servicePresent").getAsBoolean());
        assertEquals("Unexpected service connection at a window endpoint", serviceOn, state.get("serviceConnected").getAsBoolean());
        assertFalse("A task is running in an idle pair", state.get("controllerRunning").getAsBoolean());
        assertFalse("An idle pair unexpectedly has an Agent control overlay", state.get("controlAvailable").getAsBoolean());
        assertTrue("The synthetic fixture is not the unique focused application", state.get("fixtureFocused").getAsBoolean());
    }

    private boolean fixtureFocused() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null) return false;
        int focusedCount = 0;
        boolean fixture = false;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                focusedCount++;
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    fixture = DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
                } finally { root.recycle(); }
            }
            return focusedCount == 1 && fixture;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private final class SampleWindow implements AutoCloseable {
        final JsonObject result = new JsonObject();
        final JsonArray samples = new JsonArray();
        final PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
        final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-idle-pair-sampler");
            thread.setDaemon(true);
            return thread;
        });
        boolean samplesClosed, completedWindow, collected;
        volatile boolean sampleLimitReached;
        volatile Throwable sampleFailure;

        SampleWindow(int pair, int position, boolean serviceOn, JsonObject before, JsonArray windows) {
            result.addProperty("pairIndex", pair);
            result.addProperty("position", position);
            result.addProperty("mode", serviceOn ? MODE_B : MODE_A);
            result.addProperty("status", "failed");
            result.addProperty("startedNanos", trace.startedNanos);
            result.add("stateBefore", before);
            result.add("stateAfter", JsonNull.INSTANCE);
            windows.add(result);
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
                synchronized (this) { if (!samplesClosed) samples.add(point); }
            } catch (Throwable failure) { sampleFailure = failure; }
        }

        @Override public void close() throws Exception {
            sampler.shutdownNow();
            try {
                assertTrue("Idle-pair sampler did not terminate", sampler.awaitTermination(3, TimeUnit.SECONDS));
                sample();
                assertNull("Idle-pair resource sampling failed", sampleFailure);
                assertFalse("Idle-pair resource sample cap reached", sampleLimitReached);
                assertTrue("Idle-pair window did not complete", completedWindow);
                assertTrue("Idle-pair window was shorter than planned",
                        SystemClock.elapsedRealtimeNanos() - trace.startedNanos >= WINDOW_MILLIS * 1_000_000);
            } finally {
                synchronized (this) { samplesClosed = true; }
                trace.close();
                result.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                synchronized (this) { result.add("samples", samples.deepCopy()); }
                result.addProperty("sampleLimitReached", sampleLimitReached);
                result.add("trace", traceJson(trace));
                result.addProperty("traceOverwrittenEvents", trace.overwrittenCount());
                result.addProperty("traceCapacity", PerformanceTrace.CAPACITY);
            }
            // Check the frozen session so an event arriving at the end cannot be
            // omitted from the acceptance assertion but present in the report.
            assertEquals("Idle-pair trace events were overwritten", 0, trace.overwrittenCount());
            assertTrue("Idle Agent unexpectedly performed traced execution work", trace.events().isEmpty());
            assertTrue("Idle-pair resource sample span is unavailable", samples.size() >= 2
                    && samples.get(samples.size() - 1).getAsJsonObject().get("elapsedRealtimeNanos").getAsLong()
                    > samples.get(0).getAsJsonObject().get("elapsedRealtimeNanos").getAsLong());
            collected = true;
        }
    }

    private static JsonArray traceJson(PerformanceTrace.Session trace) {
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
        return entries;
    }

    private JsonObject reportHeader() {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("kind", "agent-idle-paired-diagnostics");
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
        conditions.addProperty("fixtureProcessIncluded", false);
        conditions.addProperty("screenshotSamplingIncluded", false);
        conditions.addProperty("forcedGc", false);
        conditions.addProperty("systemAnimationOverrides", false);
        conditions.addProperty("windowMillis", WINDOW_MILLIS);
        conditions.addProperty("settleMillis", SETTLE_MILLIS);
        conditions.addProperty("sampleIntervalMillis", SAMPLE_MILLIS);
        conditions.addProperty("pairCount", PAIR_COUNT);
        conditions.addProperty("order", "AB_BA_AB_BA_AB_BA");
        conditions.addProperty("fixtureReset", "once_before_all_windows");
        conditions.addProperty("modelUsed", false);
        conditions.addProperty("stateChecks", "before_and_after_window");
        report.add("conditions", conditions);
        return report;
    }

    private void writeReport(JsonObject report) throws Exception {
        byte[] bytes = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalStateException("Idle-pair report exceeded its byte cap");
        File directory = new File(context.getFilesDir(), "performance");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create idle-pair report directory");
        try (FileOutputStream output = new FileOutputStream(new File(directory, "idle-paired-diagnostics.json"))) {
            output.write(bytes);
        }
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

    private static void await(String failure, BooleanSupplier ready, long millis) {
        long deadline = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(25);
        }
        fail(failure);
    }
}
