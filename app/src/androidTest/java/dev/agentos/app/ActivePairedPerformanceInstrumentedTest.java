package dev.agentos.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
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
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Real synthetic UI goals in alternating scripted/Agent arms, not human or release-budget measurements. */
@RunWith(AndroidJUnit4.class)
public final class ActivePairedPerformanceInstrumentedTest {
    private static final int PAIRS = 6, REPETITIONS = 5, TRIALS = PAIRS * 2 * REPETITIONS;
    private static final long SETTLE_MILLIS = 2_000, TASK_MILLIS = 30_000;
    private static final int MAX_REPORT_BYTES = 65_536, MAX_TRIAL_BYTES = 4 * 1024 * 1024;
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    private static final String MODE_A = "A_SERVICE_OFF_SCRIPTED_UI", MODE_C = "C_SERVICE_ON_AGENT";
    private static final String REPORT = "active-paired-diagnostics.json";
    private static final int AUTOMATION_FLAGS = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private long taskDeadlineNanos = Long.MAX_VALUE;

    @Test public void collectAlternatingEquivalentManualAndAgentTasks() throws Exception {
        JsonObject report = reportHeader();
        JsonArray references = new JsonArray();
        report.add("trials", references);
        writeReport(report);
        AgentController controller = null;
        String originalServices = null, originalEnabled = null;
        boolean servicesRead = false, enabledRead = false, flagsRead = false, passed = false;
        int originalFlags = 0;
        try {
            controller = AgentController.get(context);
            assertFalse("Active pairs require an idle dedicated synthetic device", controller.getState().running);
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
            screenshots.capture("active-pair-sequence-before");
            for (int index = 0; index < TRIALS; index++) {
                boolean agent = agentMode(index);
                if (index % REPETITIONS == 0) setService(agent, withoutAgent);
                Trial trial = new Trial(index, agent);
                try {
                    runTrial(controller, trial);
                } finally {
                    trial.result.addProperty("endedNanos", SystemClock.elapsedRealtimeNanos());
                    commitTrial(report, references, trial);
                }
            }
            assertEquals(TRIALS, references.size());
            for (int index = 0; index < references.size(); index++) {
                assertEquals("passed", references.get(index).getAsJsonObject().get("status").getAsString());
            }
            screenshots.capture("active-pair-sequence-after");
            passed = true;
        } finally {
            boolean controllerCleared = false, servicesRestored = false, enabledRestored = false;
            boolean flagsRestored = !flagsRead;
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

    private static boolean agentMode(int index) {
        int pair = index / (2 * REPETITIONS), position = (index / REPETITIONS) % 2;
        return (pair % 2 == 0) == (position == 1);
    }

    private void runTrial(AgentController controller, Trial trial) throws Exception {
        LocalControllerModelServer server = null;
        ControllerTestSettings settings = null;
        Throwable primaryFailure = null;
        try {
            // Both arms hold the same kind of saved configuration and a fresh local
            // server. A sends no requests; C's in-process HTTP work remains included.
            assertService(trial.agent);
            server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
            trial.captureModel(server);
            settings = new ControllerTestSettings(context, server);
            launchFixture();
            SystemClock.sleep(SETTLE_MILLIS);
            assertFixtureValue("edit_value", "", true);
            assertFixtureValue("saved_value", "Saved: empty", false);
            assertFixtureValue("counter_value", "Count: 0", false);
            trial.result.addProperty("initialStateVerified", true);
            trial.result.addProperty("preparedNanos", SystemClock.elapsedRealtimeNanos());
            JsonObject before = readState(controller);
            trial.result.add("stateBefore", before);
            assertState(before, trial.agent);
            trial.result.add("beforeSample", resourceSample());
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
                assertState(after, trial.agent);
            } catch (Exception | Error cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
        trial.result.addProperty("status", "passed");
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

    private void measureTask(AgentController controller, ControllerTestSettings settings,
                             LocalControllerModelServer server, Trial trial) {
        PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
        trial.result.addProperty("traceStartedNanos", trace.startedNanos);
        long startedNanos = SystemClock.elapsedRealtimeNanos();
        long startedCpu = Process.getElapsedCpuTime();
        taskDeadlineNanos = startedNanos + TASK_MILLIS * 1_000_000;
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
            trial.captureModel(server);
            assertEquals(trial.agent ? 3 : 0, server.acceptedWriteCount());
            if (trial.agent) {
                assertTrue(server.finalStateObserved());
                assertTrue(server.requestCount() > 0);
                assertTrue(trial.approvalCount >= 3);
            } else {
                assertEquals(0, server.requestCount());
                assertEquals(0, trial.approvalCount);
                assertEquals(3, trial.manualAttempted);
                assertEquals(3, trial.manualAccepted);
            }
            assertTrue("Equivalent task exceeded its diagnostic window", SystemClock.elapsedRealtimeNanos() < taskDeadlineNanos);
        } finally {
            long endedCpu = Process.getElapsedCpuTime();
            long endedNanos = SystemClock.elapsedRealtimeNanos();
            trace.close();
            long traceEndedNanos = SystemClock.elapsedRealtimeNanos();
            taskDeadlineNanos = Long.MAX_VALUE;
            // Neither PSS read falls inside task CPU/time markers. Preserve the
            // bounded trace and marker JSON only after the endpoint probe. The
            // later trial cleanup and stateAfter check are outside both markers
            // and endpoint probes; C keeps its service connected until arm end.
            try { trial.result.add("afterSample", resourceSample()); }
            finally {
                trial.result.addProperty("taskStartedNanos", startedNanos);
                trial.result.addProperty("taskEndedNanos", endedNanos);
                trial.result.addProperty("taskStartedCpuMillis", startedCpu);
                trial.result.addProperty("taskEndedCpuMillis", endedCpu);
                trial.result.addProperty("traceEndedNanos", traceEndedNanos);
                trial.captureModel(server);
                trial.captureCounters();
                if (trial.agent) {
                    AgentController.State state = controller.getState();
                    trial.result.addProperty("resultKind", state.resultKind == null ? "" : state.resultKind.name());
                    trial.result.addProperty("resultCode", state.resultCode == null ? "" : state.resultCode);
                }
                freezeTrace(trial.result, trace);
            }
        }
        assertEquals("Active-pair trace events were overwritten", 0, trace.overwrittenCount());
        if (trial.agent) {
            int dispatches = 0;
            for (PerformanceTrace.Event event : trace.events()) {
                if (event.stage == PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT) dispatches++;
            }
            assertTrue("Completed Agent task lacks its dispatch evidence", dispatches >= 3);
        } else {
            assertTrue("Service-off scripted arm unexpectedly executed Agent work", trace.events().isEmpty());
        }
    }

    private void runAgentTask(AgentController controller, ControllerTestSettings settings,
                              LocalControllerModelServer server, Trial trial) {
        instrumentation.runOnMainSync(() -> controller.start(settings.model,
                "Save the synthetic sample and increment the fixture counter once.",
                Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, settings.lease));
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
                if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return null;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/" + viewId);
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

    private final class Trial {
        final JsonObject result = new JsonObject();
        final boolean agent;
        int manualAttempted, manualAccepted, approvalCount;

        Trial(int index, boolean agent) {
            this.agent = agent;
            result.addProperty("schemaVersion", 1);
            result.addProperty("kind", "agent-active-paired-trial");
            result.addProperty("index", index);
            result.addProperty("pairIndex", index / (2 * REPETITIONS));
            result.addProperty("position", (index / REPETITIONS) % 2);
            result.addProperty("repetition", index % REPETITIONS);
            result.addProperty("mode", agent ? MODE_C : MODE_A);
            result.addProperty("status", "failed");
            result.addProperty("startedNanos", SystemClock.elapsedRealtimeNanos());
            result.addProperty("endedNanos", -1);
            for (String key : new String[]{"preparedNanos", "taskStartedNanos", "taskEndedNanos", "taskStartedCpuMillis",
                    "taskEndedCpuMillis", "traceStartedNanos", "traceEndedNanos", "modelRequests", "modelAcceptedWrites"}) {
                result.addProperty(key, -1);
            }
            for (String key : new String[]{"stateBefore", "stateAfter", "beforeSample", "afterSample"}) result.add(key, JsonNull.INSTANCE);
            for (String key : new String[]{"initialStateVerified", "editVerified", "savedTextVerified", "counterVerified", "cleanupCompleted"}) {
                result.addProperty(key, false);
            }
            result.addProperty("resultKind", "");
            result.addProperty("resultCode", "");
            result.add("trace", new JsonArray());
            result.addProperty("traceCapacity", PerformanceTrace.CAPACITY);
            result.addProperty("traceOverwrittenEvents", 0);
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

    private void commitTrial(JsonObject report, JsonArray references, Trial trial) throws Exception {
        int index = trial.result.get("index").getAsInt();
        assertEquals("Trial commits must be an exact prefix", references.size(), index);
        String name = String.format(Locale.ROOT, "trial-%03d.json", index);
        byte[] bytes = encode(trial.result, MAX_TRIAL_BYTES);
        writeAtomic(name, bytes);
        JsonObject reference = new JsonObject();
        reference.addProperty("index", index);
        reference.addProperty("path", name);
        reference.addProperty("bytes", bytes.length);
        reference.addProperty("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        reference.addProperty("status", trial.result.get("status").getAsString());
        reference.addProperty("startedNanos", trial.result.get("startedNanos").getAsLong());
        reference.addProperty("endedNanos", trial.result.get("endedNanos").getAsLong());
        references.add(reference);
        writeReport(report);
    }

    private static void freezeTrace(JsonObject result, PerformanceTrace.Session trace) {
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
        result.addProperty("traceOverwrittenEvents", trace.overwrittenCount());
    }

    private JsonObject reportHeader() {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("kind", "agent-active-paired-diagnostics");
        report.addProperty("diagnosticOnly", true);
        report.addProperty("budgetAcceptance", false);
        report.addProperty("status", "incomplete");
        report.addProperty("appProcessPid", Process.myPid());
        report.addProperty("startedNanos", SystemClock.elapsedRealtimeNanos());
        report.addProperty("endedNanos", -1);
        JsonObject cleanup = new JsonObject();
        for (String key : new String[]{"controllerCleared", "servicesRestored", "enabledRestored", "automationFlagsRestored"}) {
            cleanup.addProperty(key, false);
        }
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
        conditions.addProperty("processScope", "instrumented_app_with_endpoint_probes");
        conditions.addProperty("fixtureProcessIncluded", false);
        conditions.addProperty("localModelScope", "same_process_both_arms_idle_in_A");
        conditions.addProperty("pairCount", PAIRS);
        conditions.addProperty("repetitionsPerArm", REPETITIONS);
        conditions.addProperty("trialCount", TRIALS);
        conditions.addProperty("order", "AC_CA_AC_CA_AC_CA");
        conditions.addProperty("fixtureReset", "per_trial");
        conditions.addProperty("settleMillis", SETTLE_MILLIS);
        conditions.addProperty("sampling", "pre_post_outside_task");
        conditions.addProperty("taskTimeoutMillis", TASK_MILLIS);
        conditions.addProperty("maxTraceEventsPerTask", PerformanceTrace.CAPACITY);
        conditions.addProperty("task", "SAVE_AND_COUNT");
        conditions.addProperty("baselineDriver", "UiAutomation_accessibility_actions");
        conditions.addProperty("setupIncludedInTask", false);
        conditions.addProperty("screenshotSamplingIncluded", false);
        conditions.addProperty("forcedGc", false);
        conditions.addProperty("systemAnimationOverrides", false);
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

    private static void assertState(JsonObject state, boolean agent) {
        assertEquals(agent, state.get("servicePresent").getAsBoolean());
        assertEquals(agent, state.get("serviceConnected").getAsBoolean());
        assertFalse(state.get("controllerRunning").getAsBoolean());
        assertFalse(state.get("controlAvailable").getAsBoolean());
        assertTrue(state.get("fixtureFocused").getAsBoolean());
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
        await(enabled ? "Active-pair Agent service did not connect" : "Active-pair Agent service did not disconnect", () -> {
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

    private void await(String message, BooleanSupplier ready, long millis) {
        long deadline = Math.min(taskDeadlineNanos, SystemClock.elapsedRealtimeNanos() + millis * 1_000_000);
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(25);
        }
        fail(message);
    }

    private void writeReport(JsonObject report) throws Exception { writeAtomic(REPORT, encode(report, MAX_REPORT_BYTES)); }

    private static byte[] encode(JsonObject value, int maximum) {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IllegalStateException("Active-pair diagnostic exceeded its fixed byte cap");
        return bytes;
    }

    private void writeAtomic(String name, byte[] bytes) throws Exception {
        File directory = new File(context.getFilesDir(), "performance/active-pairs");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create active-pair diagnostic directory");
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
