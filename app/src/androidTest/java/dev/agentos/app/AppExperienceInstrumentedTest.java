package dev.agentos.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.Trace;
import android.net.Uri;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
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
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Synthetic target-process, launch and HWUI diagnostics; never a physical-device budget result. */
@RunWith(AndroidJUnit4.class)
public final class AppExperienceInstrumentedTest {
    private static final String FIXTURE = "dev.agentos.fixture";
    private static final String ACTIVITY = FIXTURE + ".PerformanceActivity";
    private static final Uri PROVIDER = Uri.parse("content://dev.agentos.fixture.performance");
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    private static final String[] ARMS = {"A_SERVICE_OFF", "B_SERVICE_ON_IDLE", "C_SERVICE_ON_AGENT"};
    private static final String[] ORDERS = {"ABC", "BCA", "CAB", "ACB", "CBA", "BAC"};
    private static final long TASK_MILLIS = 30_000, SEQUENCE_MILLIS = 2_300_000, HOME_SETTLE_MILLIS = 1_000;
    private static final String REPORT = "experience-diagnostics.json";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int AUTOMATION_FLAGS = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private long taskDeadlineNanos = Long.MAX_VALUE, sequenceDeadlineNanos = Long.MAX_VALUE;
    private int pairs, repetitions;
    private String profile;

    @Test public void collectTargetResourcesStartupAndFrames() throws Exception {
        profile = InstrumentationRegistry.getArguments().getString("experienceProfile", "");
        assertTrue("experienceProfile must explicitly be pilot or formal", "pilot".equals(profile) || "formal".equals(profile));
        pairs = "pilot".equals(profile) ? 2 : 100;
        repetitions = "pilot".equals(profile) ? 1 : 6;
        sequenceDeadlineNanos = SystemClock.elapsedRealtimeNanos() + SEQUENCE_MILLIS * 1_000_000;
        JsonObject report = reportHeader();
        writeReport(report);
        AgentController controller = null;
        String originalServices = null, originalEnabled = null;
        boolean servicesRead = false, enabledRead = false, flagsRead = false, passed = false;
        int originalFlags = 0;
        try {
            controller = AgentController.get(context);
            assertFalse("Experience diagnostics require an idle dedicated synthetic device", controller.getState().running);
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
            setService(false, withoutAgent);
            launchFixture();
            screenshots.capture("app-experience-sequence-before");
            boolean allLaunchesAvailable = true;
            for (int index = 0; index < pairs * 4; index++) {
                checkSequence();
                JsonObject launch = newLaunch(index);
                try {
                    runLaunch(controller, withoutAgent, launch);
                    if (!"passed".equals(launch.get("status").getAsString())) allLaunchesAvailable = false;
                } catch (Exception | Error failure) {
                    launch.addProperty("status", "failed");
                    throw failure;
                } finally {
                    launch.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                    commit(report, "launches", "launch", launch, 32 * 1024);
                }
            }
            for (int index = 0; index < repetitions * 3; index++) {
                checkSequence();
                Trial trial = new Trial(index);
                try { runTrial(controller, withoutAgent, trial); }
                finally {
                    trial.result.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                    commit(report, "trials", "trial", trial.result, 2 * 1024 * 1024);
                }
            }
            screenshots.capture("app-experience-sequence-after");
            assertTrue("A launch had unavailable natural-frame or draw-association evidence; raw reasons are retained", allLaunchesAvailable);
            passed = true;
        } finally {
            boolean controllerCleared = false, servicesRestored = false, enabledRestored = false;
            boolean flagsRestored = !flagsRead;
            try {
                if (controller != null) { clearController(controller); controllerCleared = true; }
            } finally {
                try {
                    if (servicesRead) { secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices); servicesRestored = true; }
                } finally {
                    try {
                        if (enabledRead) { secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled); enabledRestored = true; }
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
                            cleanup.addProperty("automationFlagsRestored", flagsRestored);
                            report.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                            report.addProperty("status", passed && controllerCleared && servicesRestored
                                    && enabledRestored && flagsRestored ? "passed" : "failed");
                            writeReport(report);
                        }
                    }
                }
            }
        }
    }

    private JsonObject newLaunch(int index) {
        int pair = (index % (pairs * 2)) / 2, position = index % 2;
        String startupType = index < pairs * 2 ? "cold" : "hot";
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", 2);
        value.addProperty("kind", "agent-app-experience-launch");
        value.addProperty("index", index);
        value.addProperty("pairIndex", pair);
        value.addProperty("position", position);
        value.addProperty("arm", ARMS[(pair % 2 == 0) == (position == 0) ? 0 : 1]);
        value.addProperty("startupType", startupType);
        value.addProperty("status", "failed");
        value.addProperty("startedNanos", SystemClock.elapsedRealtimeNanos());
        value.addProperty("endedNanos", -1);
        value.addProperty("marker", "aos.experience.launch." + index);
        value.addProperty("driverPid", Process.myPid());
        value.addProperty("expectedPid", -1);
        value.addProperty("expectedStartupType", startupType);
        value.addProperty("homePackage", "");
        value.addProperty("homeFocusedVerified", false);
        for (String key : new String[]{"preLaunchPidAbsent", "homeStoppedVerified", "lifecycleVerified", "newDrawVerified"}) value.addProperty(key, false);
        for (String key : new String[]{"stateBefore", "stateAfter", "beforeLaunch", "afterLaunch"}) value.add(key, JsonNull.INSTANCE);
        for (String key : new String[]{"launchStartedElapsedNanos", "launchEndedElapsedNanos", "launchStartedNanoTime", "launchEndedNanoTime"}) value.addProperty(key, -1);
        value.addProperty("amStartOutput", "");
        value.addProperty("unavailableReason", "");
        return value;
    }

    private void runLaunch(AgentController controller, String withoutAgent, JsonObject value) throws Exception {
        boolean enabled = ARMS[1].equals(value.get("arm").getAsString());
        boolean cold = "cold".equals(value.get("startupType").getAsString());
        setService(enabled, withoutAgent);
        SystemClock.sleep(250);
        assertService(enabled);
        Bundle before = null;
        if (cold) {
            shell("am force-stop " + FIXTURE, 8_192, 10_000);
            // No provider or ActivityManager lookup that starts the target process here.
            assertEquals("Cold launch target is still running", "", shell("pidof " + FIXTURE, 1_024, 5_000).trim());
            value.addProperty("preLaunchPidAbsent", true);
        } else {
            Bundle foreground = provider("status", null, null);
            assertTrue("Hot setup target was not resumed", foreground.getBoolean("resumed"));
            String homePackage = resolvedHomePackage();
            value.addProperty("homePackage", homePackage);
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
            Bundle[] stopped = {null};
            await("HOME did not focus the resolved launcher and stop the target Activity", () -> {
                stopped[0] = provider("status", null, null);
                return stopped[0].getBoolean("stopped") && !stopped[0].getBoolean("resumed")
                        && homePackage.equals(focusedApplicationPackage());
            }, 8_000);
            assertIdentity(foreground, stopped[0]);
            // Identical preparation in A and B, outside the launch marker. A
            // callback entering onStop alone does not certify a settled HOME
            // transition; verify real launcher focus and the stopped target again.
            SystemClock.sleep(HOME_SETTLE_MILLIS);
            before = provider("status", null, null);
            value.add("beforeLaunch", bundleJson(before));
            assertIdentity(foreground, before);
            assertTrue("Target left its stopped HOME preparation state", before.getBoolean("stopped") && !before.getBoolean("resumed"));
            assertEquals("Resolved launcher lost focus during HOME settling", homePackage, focusedApplicationPackage());
            assertEquals(stopped[0].getLong("resumeCount"), before.getLong("resumeCount"));
            assertEquals(stopped[0].getLong("stopCount"), before.getLong("stopCount"));
            value.addProperty("homeStoppedVerified", true);
            value.addProperty("homeFocusedVerified", true);
        }
        JsonObject stateBefore = readState(controller);
        value.add("stateBefore", stateBefore);
        assertIdleState(stateBefore, enabled, false);
        Bundle after = null;
        long startElapsed = SystemClock.elapsedRealtimeNanos(), startNano = System.nanoTime();
        value.addProperty("launchStartedElapsedNanos", startElapsed);
        value.addProperty("launchStartedNanoTime", startNano);
        Trace.beginSection(value.get("marker").getAsString());
        try {
            String output = shell("am start -W -n " + FIXTURE + "/.PerformanceActivity -f 0x10000000", 8_192, 20_000);
            value.addProperty("amStartOutput", output);
            assertTrue("am start did not report a successful launch", output.contains("Status: ok") && !output.contains("Error:"));
            long deadline = Math.min(sequenceDeadlineNanos, SystemClock.elapsedRealtimeNanos() + 3_000_000_000L);
            boolean newDraw = false, observationsResolved = false;
            do {
                after = provider("status", null, null);
                value.addProperty("expectedPid", after.getInt("pid"));
                boolean resumed = after.getBoolean("resumed") && after.getBoolean("windowFocused");
                newDraw = resumed && after.getBoolean("drawFrameObserved") && !after.getBoolean("drawMatchAmbiguous")
                        && after.getLong("drawVsyncNs") > 0
                        && (cold || after.getLong("drawVsyncNs") > before.getLong("drawVsyncNs"));
                observationsResolved = after.getLong("unresolvedDrawCount") == (cold ? 0 : before.getLong("unresolvedDrawCount"));
                if (newDraw && observationsResolved) break;
                SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtimeNanos() < deadline);
            assertNotNull(after);
            assertTrue("Target Activity did not resume", after.getBoolean("resumed"));
            assertTrue("Target window did not receive focus", after.getBoolean("windowFocused"));
            assertTrue("Target Activity must use hardware rendering", after.getBoolean("hardwareAccelerated"));
            if (cold) {
                assertEquals(1, after.getLong("createCount"));
                assertEquals(1, after.getLong("resumeCount"));
                assertTrue(after.getLong("createdNanoTime") >= startNano);
            } else {
                assertIdentity(before, after);
                assertEquals(before.getLong("createCount"), after.getLong("createCount"));
                assertTrue(after.getLong("resumeCount") > before.getLong("resumeCount"));
            }
            value.addProperty("lifecycleVerified", true);
            value.addProperty("newDrawVerified", newDraw);
            value.addProperty("status", newDraw && observationsResolved ? "passed" : "unavailable");
            if (!newDraw) value.addProperty("unavailableReason", "no_new_draw_frame_report");
            else if (!observationsResolved) value.addProperty("unavailableReason", "unresolved_draw_observation");
        } finally {
            Trace.endSection();
            value.addProperty("launchEndedNanoTime", System.nanoTime());
            value.addProperty("launchEndedElapsedNanos", SystemClock.elapsedRealtimeNanos());
            if (after != null) value.add("afterLaunch", bundleJson(after));
        }
        JsonObject stateAfter = readState(controller);
        value.add("stateAfter", stateAfter);
        assertIdleState(stateAfter, enabled, true);
        checkSequence();
    }

    private String resolvedHomePackage() {
        ResolveInfo resolved = context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull("A resolved default HOME activity is required", resolved);
        assertNotNull("Resolved HOME lacks an Activity identity", resolved.activityInfo);
        String name = resolved.activityInfo.packageName;
        assertTrue("HOME resolved to no launcher or to a resolver dialog", name != null && !name.isEmpty()
                && !"android".equals(name) && !FIXTURE.equals(name) && !context.getPackageName().equals(name));
        return name;
    }

    private String focusedApplicationPackage() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null) return "";
        try {
            AccessibilityWindowInfo focused = null;
            int count = 0;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getDisplayId() == Display.DEFAULT_DISPLAY && window.isFocused()) {
                    focused = window;
                    count++;
                }
            }
            if (count != 1 || focused == null || focused.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) return "";
            AccessibilityNodeInfo root = focused.getRoot();
            if (root == null) return "";
            try { return root.getPackageName() == null ? "" : root.getPackageName().toString(); }
            finally { root.recycle(); }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static void assertIdentity(Bundle before, Bundle after) {
        assertEquals(before.getInt("pid"), after.getInt("pid"));
        assertEquals(before.getString("processInstance"), after.getString("processInstance"));
        assertEquals(before.getString("activityInstance"), after.getString("activityInstance"));
    }

    private final class Trial {
        final JsonObject result = new JsonObject();
        final int arm;
        final boolean agent;
        int manualAttempted, manualAccepted, approvalCount;
        Trial(int index) {
            int repetition = index / 3, position = index % 3;
            arm = ORDERS[repetition].charAt(position) - 'A';
            agent = arm == 2;
            result.addProperty("schemaVersion", 2);
            result.addProperty("kind", "agent-app-experience-trial");
            result.addProperty("index", index);
            result.addProperty("repetition", repetition);
            result.addProperty("position", position);
            result.addProperty("arm", ARMS[arm]);
            result.addProperty("status", "failed");
            result.addProperty("startedNanos", SystemClock.elapsedRealtimeNanos());
            result.addProperty("endedNanos", -1);
            result.addProperty("sessionId", UUID.randomUUID().toString());
            for (String key : new String[]{"initialStateVerified", "editVerified", "savedTextVerified", "counterVerified", "drawingVerified", "cleanupCompleted"}) result.addProperty(key, false);
            for (String key : new String[]{"stateBefore", "stateAfter", "agentBeforeSample", "agentAfterSample", "targetBefore", "targetAfter"}) result.add(key, JsonNull.INSTANCE);
            for (String key : new String[]{"agentTaskStartedNanos", "agentTaskEndedNanos", "agentTaskStartedCpuMillis", "agentTaskEndedCpuMillis", "modelRequests", "modelAcceptedWrites"}) result.addProperty(key, -1);
            result.addProperty("resultKind", "");
            result.addProperty("resultCode", "");
            result.add("frames", new JsonArray());
            result.add("framePageChecks", new JsonArray());
            captureCounters();
        }
        void captureCounters() {
            result.addProperty("manualActionsAttempted", manualAttempted);
            result.addProperty("manualActionsAccepted", manualAccepted);
            result.addProperty("approvalCount", approvalCount);
        }
        void captureModel(LocalControllerModelServer server) {
            result.addProperty("modelRequests", server.requestCount());
            result.addProperty("modelAcceptedWrites", server.acceptedWriteCount());
        }
    }

    private void runTrial(AgentController controller, String withoutAgent, Trial trial) throws Exception {
        LocalControllerModelServer server = null;
        ControllerTestSettings settings = null;
        Throwable primaryFailure = null;
        try {
            setService(trial.arm != 0, withoutAgent);
            server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
            trial.captureModel(server);
            settings = new ControllerTestSettings(context, server);
            launchFixture();
            SystemClock.sleep(2_000);
            assertFixtureValue("edit_value", "", true);
            assertFixtureValue("saved_value", "Saved: empty", false);
            assertFixtureValue("counter_value", "Count: 0", false);
            trial.result.addProperty("initialStateVerified", true);
            JsonObject before = readState(controller);
            trial.result.add("stateBefore", before);
            assertIdleState(before, trial.arm != 0, true);
            trial.result.add("agentBeforeSample", resourceSample());
            measureTask(controller, settings, server, trial);
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            taskDeadlineNanos = Long.MAX_VALUE;
            try {
                cleanupTrial(controller, settings, server, trial);
                JsonObject after = readState(controller);
                trial.result.add("stateAfter", after);
                assertIdleState(after, trial.arm != 0, true);
            } catch (Exception | Error cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
        checkSequence();
        trial.result.addProperty("status", "passed");
    }

    private void measureTask(AgentController controller, ControllerTestSettings settings,
                             LocalControllerModelServer server, Trial trial) {
        String sessionId = trial.result.get("sessionId").getAsString();
        Bundle targetBefore = provider("begin", sessionId, null), targetAfter = null;
        long start = SystemClock.elapsedRealtimeNanos(), startCpu = Process.getElapsedCpuTime();
        taskDeadlineNanos = Math.min(sequenceDeadlineNanos, start + TASK_MILLIS * 1_000_000);
        Throwable primaryFailure = null;
        try {
            if (trial.agent) runAgentTask(controller, settings, server, trial);
            else runScriptedTask(trial);
            assertFixtureValue("edit_value", LocalControllerModelServer.TEXT, false);
            trial.result.addProperty("editVerified", true);
            assertFixtureValue("saved_value", "Saved: " + LocalControllerModelServer.TEXT, false);
            trial.result.addProperty("savedTextVerified", true);
            assertFixtureValue("counter_value", "Count: 1", false);
            trial.result.addProperty("counterVerified", true);
            server.assertHealthy();
            assertEquals(trial.agent ? 3 : 0, server.acceptedWriteCount());
            if (trial.agent) { assertTrue(server.requestCount() > 0); assertTrue(server.finalStateObserved()); assertTrue(trial.approvalCount >= 3); }
            else { assertEquals(0, server.requestCount()); assertEquals(3, trial.manualAccepted); assertEquals(3, trial.manualAttempted); }
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                // End is bounded and freezes real final-draw evidence even when the UI task failed.
                targetAfter = provider("end", sessionId, null);
            } catch (Exception | Error endFailure) {
                if (primaryFailure == null) throw endFailure;
                primaryFailure.addSuppressed(endFailure);
            } finally {
                long endCpu = Process.getElapsedCpuTime(), end = SystemClock.elapsedRealtimeNanos();
                taskDeadlineNanos = Long.MAX_VALUE;
                try { trial.result.add("agentAfterSample", resourceSample()); }
                finally {
                    trial.result.addProperty("agentTaskStartedNanos", start);
                    trial.result.addProperty("agentTaskEndedNanos", end);
                    trial.result.addProperty("agentTaskStartedCpuMillis", startCpu);
                    trial.result.addProperty("agentTaskEndedCpuMillis", endCpu);
                    trial.captureModel(server);
                    trial.captureCounters();
                    trial.result.add("targetBefore", bundleJson(targetBefore));
                    if (trial.agent) {
                        AgentController.State state = controller.getState();
                        trial.result.addProperty("resultKind", state.resultKind == null ? "" : state.resultKind.name());
                        trial.result.addProperty("resultCode", state.resultCode == null ? "" : state.resultCode);
                    }
                    if (targetAfter != null) {
                        trial.result.add("targetAfter", bundleJson(targetAfter));
                        readFrames(sessionId, targetAfter, trial.result.getAsJsonArray("frames"),
                                trial.result.getAsJsonArray("framePageChecks"));
                    }
                }
            }
        }
        assertNotNull(targetAfter);
        assertTrue(targetAfter.getBoolean("finalState"));
        assertTrue("Final UI had no matching natural draw/frame callback", targetAfter.getBoolean("finalFrameObserved"));
        assertTrue(targetAfter.getBoolean("drainComplete"));
        assertFalse(targetAfter.getBoolean("drainTimedOut"));
        assertEquals(0, targetAfter.getLong("frameOverflow"));
        assertEquals(0, targetAfter.getLong("droppedReports"));
        assertEquals(0, targetAfter.getLong("lateFrameReports"));
        assertEquals(0, targetAfter.getLong("lateDroppedReports"));
        assertEquals("A natural draw could not be associated with its frame", 0, targetAfter.getLong("unresolvedTaskDraws"));
        assertTrue("Display mode changed during the resource task", targetAfter.getBoolean("displayStable"));
        assertFalse("Natural draw/frame millisecond association is ambiguous", targetAfter.getBoolean("drawMatchAmbiguous"));
        assertEquals(targetAfter.getLong("finalMutationVersion"), targetAfter.getLong("finalDrawVersion"));
        assertTrue("Resource task exceeded its diagnostic wait", trial.result.get("agentTaskEndedNanos").getAsLong() - start < TASK_MILLIS * 1_000_000);
        trial.result.addProperty("drawingVerified", true);
    }

    private void readFrames(String sessionId, Bundle summary, JsonArray frames, JsonArray pageChecks) {
        int total = summary.getInt("frameCount");
        assertTrue(total >= 0 && total <= 4_096);
        int offset = 0;
        while (offset < total) {
            assertTrue("Frame paging exceeded its fixed 64-page cap", pageChecks.size() < 64);
            Bundle extras = new Bundle();
            extras.putInt("offset", offset);
            Bundle page = provider("page", sessionId, extras);
            JsonObject check = new JsonObject();
            check.addProperty("offset", page.getInt("offset"));
            check.addProperty("nextOffset", page.getInt("nextOffset"));
            check.addProperty("totalFrames", page.getInt("totalFrames"));
            check.addProperty("drainComplete", page.getBoolean("drainComplete"));
            check.addProperty("lateFrameReports", page.getLong("lateFrameReports"));
            check.addProperty("lateDroppedReports", page.getLong("lateDroppedReports"));
            pageChecks.add(check); // Preserve even the first failed page without rewriting the end snapshot.
            assertEquals(sessionId, page.getString("sessionId"));
            assertEquals(offset, page.getInt("offset"));
            assertEquals(total, page.getInt("totalFrames"));
            ArrayList<Bundle> rows = page.getParcelableArrayList("frames");
            assertNotNull(rows);
            assertTrue(!rows.isEmpty() && rows.size() <= 64);
            for (Bundle row : rows) {
                assertEquals(offset++, row.getInt("index"));
                frames.add(bundleJson(row));
            }
            assertEquals(offset, page.getInt("nextOffset"));
            assertTrue(offset <= total);
            assertTrue("Frame drain changed while reading immutable frame pages", page.getBoolean("drainComplete"));
            assertEquals("Late frame report arrived after end", 0, page.getLong("lateFrameReports"));
            assertEquals("Late callback reported missing frame reports", 0, page.getLong("lateDroppedReports"));
        }
        assertEquals(total, frames.size());
    }

    private Bundle provider(String method, String arg, Bundle extras) {
        Bundle value = context.getContentResolver().call(PROVIDER, method, arg, extras);
        assertNotNull("Fixture diagnostic provider returned no bundle", value);
        assertEquals(2, value.getInt("schemaVersion"));
        assertEquals("CLOCK_MONOTONIC_System.nanoTime", value.getString("clockDomain"));
        assertTrue(value.getInt("pid") > 0);
        return value;
    }

    private static JsonObject bundleJson(Bundle value) {
        JsonObject json = new JsonObject();
        for (String key : value.keySet()) {
            Object field = value.get(key);
            if (field == null) json.add(key, JsonNull.INSTANCE);
            else if (field instanceof String) json.addProperty(key, (String) field);
            else if (field instanceof Boolean) json.addProperty(key, (Boolean) field);
            else if (field instanceof Number) json.addProperty(key, (Number) field);
            else throw new IllegalArgumentException("Non-scalar fixture diagnostic field: " + key);
        }
        return json;
    }

    private String shell(String command, int maxBytes, long timeoutMillis) throws Exception {
        // Bounded pipe polling avoids an uninterruptible read and creates no accumulating worker threads.
        long deadline = Math.min(sequenceDeadlineNanos, SystemClock.elapsedRealtimeNanos() + timeoutMillis * 1_000_000);
        try (ParcelFileDescriptor pipe = automation.executeShellCommand(command);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            StructPollfd poll = new StructPollfd();
            poll.fd = pipe.getFileDescriptor();
            poll.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
            byte[] buffer = new byte[1_024];
            while (true) {
                long remaining = deadline - SystemClock.elapsedRealtimeNanos();
                assertTrue("Shell diagnostic command exceeded its deadline: " + command, remaining > 0);
                int ready = Os.poll(new StructPollfd[]{poll}, (int) Math.max(1, Math.min(1_000, remaining / 1_000_000)));
                if (ready == 0) continue;
                assertEquals("Shell diagnostic pipe failed", 0, poll.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL));
                int count = Os.read(pipe.getFileDescriptor(), buffer, 0, buffer.length);
                if (count == 0) break;
                assertTrue("Shell diagnostic output exceeded its fixed cap", bytes.size() + count <= maxBytes);
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void checkSequence() {
        assertTrue("App-experience sequence exceeded its fixed 2300-second limit", SystemClock.elapsedRealtimeNanos() < sequenceDeadlineNanos);
    }

    private void commit(JsonObject report, String array, String prefix, JsonObject value, int maximum) throws Exception {
        int index = value.get("index").getAsInt();
        JsonArray references = report.getAsJsonArray(array);
        assertEquals("Diagnostic commits must be an exact prefix", references.size(), index);
        String name = String.format(Locale.ROOT, prefix + "-%03d.json", index);
        byte[] bytes = encode(value, maximum);
        writeAtomic(name, bytes);
        JsonObject ref = new JsonObject();
        ref.addProperty("index", index);
        ref.addProperty("path", name);
        ref.addProperty("bytes", bytes.length);
        ref.addProperty("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        for (String key : new String[]{"status", "startedNanos", "endedNanos"}) ref.add(key, value.get(key));
        if ("launch".equals(prefix)) for (String key : new String[]{"marker", "expectedPid", "expectedStartupType", "driverPid"}) ref.add(key, value.get(key));
        references.add(ref);
        writeReport(report);
    }

    private JsonObject reportHeader() {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 2);
        report.addProperty("kind", "agent-app-experience-diagnostics");
        report.addProperty("diagnosticOnly", true);
        report.addProperty("budgetAcceptance", false);
        report.addProperty("profile", profile);
        report.addProperty("status", "incomplete");
        report.addProperty("appProcessPid", Process.myPid());
        report.addProperty("targetPackage", FIXTURE);
        report.addProperty("startedNanos", SystemClock.elapsedRealtimeNanos());
        report.addProperty("endedNanos", -1);
        JsonObject cleanup = new JsonObject();
        for (String key : new String[]{"controllerCleared", "servicesRestored", "enabledRestored", "automationFlagsRestored"}) cleanup.addProperty(key, false);
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
        conditions.addProperty("fixturePackage", FIXTURE);
        conditions.addProperty("fixtureActivity", ACTIVITY);
        conditions.addProperty("startupPairsPerMode", pairs);
        conditions.addProperty("launchCount", pairs * 4);
        conditions.addProperty("resourceRepetitions", repetitions);
        conditions.addProperty("trialCount", repetitions * 3);
        conditions.addProperty("startupOrder", "AB_BA_alternating");
        conditions.addProperty("resourceOrder", "pilot".equals(profile) ? "ABC" : "ABC_BCA_CAB_ACB_CBA_BAC");
        conditions.addProperty("startupSettleMillis", 250);
        conditions.addProperty("homeSettleMillis", HOME_SETTLE_MILLIS);
        conditions.addProperty("homePreparation", "resolved_HOME_focused_stopped_settle_recheck");
        conditions.addProperty("resourceSettleMillis", 2_000);
        conditions.addProperty("taskTimeoutMillis", TASK_MILLIS);
        conditions.addProperty("sequenceTimeoutMillis", SEQUENCE_MILLIS);
        conditions.addProperty("baselineDriver", "UiAutomation_accessibility_actions");
        conditions.addProperty("localModelScope", "same_process_all_arms_idle_in_A_B");
        conditions.addProperty("sampling", "target_provider_and_agent_endpoints");
        conditions.addProperty("startupMetric", "external_perfetto_android_startups_time_to_display");
        conditions.addProperty("forcedGc", false);
        conditions.addProperty("systemAnimationOverrides", false);
        conditions.addProperty("screenshotSamplingIncluded", false);
        report.add("conditions", conditions);
        report.add("launches", new JsonArray());
        report.add("trials", new JsonArray());
        return report;
    }

    private void cleanupTrial(AgentController controller, ControllerTestSettings settings,
                              LocalControllerModelServer server, Trial trial) throws Exception {
        boolean controllerCleared = false, settingsRestored = settings == null, serverClosed = server == null;
        try {
            clearController(controller);
            controllerCleared = true;
        } finally {
            try {
                if (settings != null) settings.close();
                settingsRestored = true;
            } finally {
                try {
                    if (server != null) server.close();
                    serverClosed = true;
                } finally {
                    trial.result.addProperty("cleanupCompleted", controllerCleared && settingsRestored && serverClosed);
                }
            }
        }
    }

    private void runAgentTask(AgentController controller, ControllerTestSettings settings,
                              LocalControllerModelServer server, Trial trial) {
        instrumentation.runOnMainSync(() -> controller.start(settings.model,
                "Save the synthetic sample and increment the fixture counter once.",
                Collections.singleton(FIXTURE), settings.executionMode, settings.lease));
        Set<String> approved = new HashSet<>();
        while (controller.getState().running && SystemClock.elapsedRealtimeNanos() < taskDeadlineNanos) {
            server.assertHealthy();
            AgentController.State state = controller.getState();
            if (!state.approvalId.isEmpty() && approved.add(state.approvalId)) {
                trial.approvalCount++;
                instrumentation.runOnMainSync(() -> controller.approve(state.approvalId, true));
            }
            SystemClock.sleep(25);
        }
        assertFalse("Agent equivalent task exceeded its diagnostic window", controller.getState().running);
        assertEquals(TaskResult.Kind.MODEL_REPORT, controller.getState().resultKind);
        assertEquals("MODEL_REPORT", controller.getState().resultCode);
    }

    private void runScriptedTask(Trial trial) {
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, LocalControllerModelServer.TEXT);
        performOnce(trial, "edit_value", AccessibilityNodeInfo.ACTION_SET_TEXT, text);
        assertFixtureValue("edit_value", LocalControllerModelServer.TEXT, false);
        performOnce(trial, "save_value", AccessibilityNodeInfo.ACTION_CLICK, null);
        assertFixtureValue("saved_value", "Saved: " + LocalControllerModelServer.TEXT, false);
        performOnce(trial, "increment", AccessibilityNodeInfo.ACTION_CLICK, null);
    }

    private void performOnce(Trial trial, String viewId, int action, Bundle arguments) {
        AccessibilityNodeInfo[] selected = {null};
        await("The scripted fixture target is not available: " + viewId, () -> {
            AccessibilityNodeInfo node = fixtureNode(viewId);
            if (node == null) return false;
            boolean ready = node.isEnabled() && node.isVisibleToUser()
                    && (action == AccessibilityNodeInfo.ACTION_SET_TEXT ? node.isEditable() : node.isClickable());
            if (ready) selected[0] = node; else node.recycle();
            return ready;
        }, 15_000);
        AccessibilityNodeInfo node = selected[0];
        try {
            // Never retry a write, including a false or unknown submission result.
            assertTrue("The scripted action reached its diagnostic deadline", SystemClock.elapsedRealtimeNanos() < taskDeadlineNanos);
            trial.manualAttempted++;
            boolean accepted = arguments == null ? node.performAction(action) : node.performAction(action, arguments);
            if (accepted) trial.manualAccepted++;
            assertTrue("The single scripted platform action was not accepted: " + viewId, accepted);
        } finally { node.recycle(); }
    }

    private void assertFixtureValue(String viewId, String expected, boolean allowEmptyHint) {
        await("The expected synthetic fixture value is unavailable: " + viewId, () -> {
            AccessibilityNodeInfo node = fixtureNode(viewId);
            if (node == null) return false;
            try {
                CharSequence text = node.getText();
                if (allowEmptyHint) return node.isEditable()
                        && (text == null || text.length() == 0 || (node.isShowingHintText()
                        && "Test text".contentEquals(node.getHintText() == null ? "" : node.getHintText())));
                return !node.isShowingHintText() && expected.contentEquals(text == null ? "" : text);
            } finally { node.recycle(); }
        }, 15_000);
    }

    private AccessibilityNodeInfo fixtureNode(String viewId) {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null) return null;
        try {
            AccessibilityWindowInfo focused = null;
            int count = 0;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getDisplayId() == Display.DEFAULT_DISPLAY && window.isFocused()) {
                    focused = window;
                    count++;
                }
            }
            if (count != 1 || focused == null || focused.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) return null;
            AccessibilityNodeInfo root = focused.getRoot();
            if (root == null) return null;
            try {
                if (!FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return null;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/" + viewId);
                try {
                    return nodes.size() == 1 && nodes.get(0).isVisibleToUser()
                            ? AccessibilityNodeInfo.obtain(nodes.get(0)) : null;
                } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
            } finally { root.recycle(); }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static JsonObject resourceSample() {
        long started = SystemClock.elapsedRealtimeNanos();
        long cpu = Process.getElapsedCpuTime();
        Debug.MemoryInfo memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        JsonObject sample = new JsonObject();
        sample.addProperty("elapsedRealtimeNanos", started);
        sample.addProperty("processCpuMillis", cpu);
        sample.addProperty("totalPssKiB", memory.getTotalPss());
        sample.addProperty("dalvikPssKiB", memory.dalvikPss);
        sample.addProperty("nativePssKiB", memory.nativePss);
        sample.addProperty("otherPssKiB", memory.otherPss);
        sample.addProperty("collectionNanos", SystemClock.elapsedRealtimeNanos() - started);
        return sample;
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

    private static void assertIdleState(JsonObject state, boolean enabled, boolean requireFocus) {
        assertEquals(enabled, state.get("servicePresent").getAsBoolean());
        assertEquals(enabled, state.get("serviceConnected").getAsBoolean());
        assertFalse(state.get("controllerRunning").getAsBoolean());
        assertFalse(state.get("controlAvailable").getAsBoolean());
        if (requireFocus) assertTrue(state.get("fixtureFocused").getAsBoolean());
    }

    private boolean fixtureFocused() {
        AccessibilityNodeInfo editor = fixtureNode("edit_value");
        if (editor == null) return false;
        editor.recycle();
        return true;
    }

    private void assertService(boolean enabled) {
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        assertEquals("Unexpected Agent service presence at trial setup", enabled, service != null);
        assertEquals("Unexpected Agent service connection at trial setup", enabled, service != null && service.isConnected());
    }

    private void setService(boolean enabled, String withoutAgent) {
        secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabled ? (withoutAgent.isEmpty() ? COMPONENT : withoutAgent + ":" + COMPONENT) : withoutAgent);
        if (enabled) secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
        await(enabled ? "App-experience Agent service did not connect" : "App-experience Agent service did not disconnect", () -> {
            AgentAccessibilityService service = AgentAccessibilityService.getInstance();
            return enabled ? service != null && service.isConnected() : service == null;
        }, 8_000);
        instrumentation.waitForIdleSync();
    }

    private void clearController(AgentController controller) {
        instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
        instrumentation.waitForIdleSync();
    }

    private void launchFixture() {
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent()
                .setComponent(new ComponentName(FIXTURE, ACTIVITY))
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

    private void await(String message, BooleanSupplier ready, long millis) {
        long deadline = Math.min(sequenceDeadlineNanos, Math.min(taskDeadlineNanos, SystemClock.elapsedRealtimeNanos() + millis * 1_000_000));
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(25);
        }
        fail(message);
    }

    private void writeReport(JsonObject report) throws Exception { writeAtomic(REPORT, encode(report, 262_144)); }

    private static byte[] encode(JsonObject value, int maximum) {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IllegalStateException("App-experience diagnostic exceeded its fixed byte cap");
        return bytes;
    }

    private void writeAtomic(String name, byte[] bytes) throws Exception {
        File directory = new File(context.getFilesDir(), "performance/app-experience");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create app-experience diagnostic directory");
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
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
