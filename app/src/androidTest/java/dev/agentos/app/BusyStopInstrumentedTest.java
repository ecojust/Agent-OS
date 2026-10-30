package dev.agentos.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.TaskControlException;
import dev.agentos.core.TaskResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual Controller stop via injected touches while an external call is held. */
@RunWith(AndroidJUnit4.class)
public final class BusyStopInstrumentedTest {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String ROOT_TEMPLATE = "{\"schemaVersion\":1,\"kind\":\"agent-os-busy-stop-diagnostics\",\"status\":\"incomplete\",\"profile\":\"pilot\",\"diagnosticOnly\":true,\"budgetAcceptance\":false,\"fullSuiteAcceptance\":false,\"api\":null,\"device\":{\"manufacturer\":null,\"model\":null,\"fingerprint\":null},\"conditions\":{\"clock\":\"System.nanoTime\",\"traceClock\":\"SystemClock.elapsedRealtimeNanos\",\"rounds\":2,\"trialsPerRound\":2,\"trialCount\":4,\"orders\":[\"DEVICE_MODEL\",\"MODEL_DEVICE\"],\"quietMillis\":500,\"frameWaitMillis\":3000,\"fixtureBarrierMillis\":3000,\"modelBarrierMillis\":20000,\"frameCandidateCapacity\":16,\"probeCapacity\":16,\"traceCapacity\":128,\"input\":\"UiAutomation.injectInputEvent\",\"frameMetric\":\"INTENDED_VSYNC_TIMESTAMP+TOTAL_DURATION\",\"forcedInvalidate\":false,\"secureFlagsCleared\":false},\"startedNanoTime\":-1,\"endedNanoTime\":-1,\"trials\":[],\"cleanup\":{\"controllerCleared\":false,\"servicesRestored\":false,\"enabledRestored\":false,\"automationFlagsRestored\":false},\"appProcessPid\":1234}";
    private static final String TRIAL_TEMPLATE = "{\"schemaVersion\":1,\"kind\":\"agent-os-busy-stop-trial\",\"index\":0,\"round\":0,\"position\":0,\"order\":\"DEVICE_MODEL\",\"scenario\":\"DEVICE\",\"status\":\"incomplete\",\"phase\":\"setup\",\"failure\":null,\"startedNanoTime\":-1,\"endedNanoTime\":-1,\"taskId\":null,\"epochBefore\":-1,\"input\":{\"bounds\":null,\"point\":null,\"downIssuedNanoTime\":-1,\"downReturnedNanoTime\":-1,\"upIssuedNanoTime\":-1,\"upIssuedElapsedNanos\":-1,\"upReturnedNanoTime\":-1,\"downAccepted\":null,\"upAccepted\":null,\"touchUpNanoTime\":-1,\"touchUpElapsedNanos\":-1,\"touchUpCount\":0},\"beforeStop\":null,\"afterStop\":null,\"beforeRelease\":null,\"afterSettle\":null,\"finalQuiet\":null,\"probe\":{\"events\":[],\"overflow\":0,\"ownerMatched\":false},\"fixture\":null,\"model\":null,\"frames\":null,\"trace\":{\"events\":[],\"overwritten\":0},\"checks\":{\"initialCounter\":-1,\"finalCounter\":-1,\"approvalCount\":0,\"frozenUsageUnchanged\":false,\"frozenResultUnchanged\":false,\"workerDrained\":false,\"quietMillis\":500,\"noFurtherRequests\":false,\"workerDrainedNanoTime\":-1,\"networkDrained\":false,\"networkDrainedNanoTime\":-1,\"closerDrained\":false,\"closerDrainedNanoTime\":-1,\"requestCountBeforeStop\":-1},\"cleanup\":{\"controllerCleared\":false,\"settingsRestored\":false,\"serverClosed\":false,\"fixtureCleared\":false,\"frameProbeClosed\":false,\"touchListenerRemoved\":false,\"activityClosed\":false},\"feedback\":{\"controlGoneObservedNanoTime\":-1,\"quietStartedNanoTime\":-1,\"quietEndedNanoTime\":-1,\"overlayBefore\":null,\"overlayAfter\":null},\"releaseRequestedNanoTime\":-1,\"gateProbe\":null,\"oldEpochProbe\":null}";
    private static final String FIXTURE = "dev.agentos.fixture";
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    private static final int AUTOMATION_FLAGS = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private long deadlineNanoTime = Long.MAX_VALUE;

    @Test public void collectBusyStopDiagnostics() throws Exception {
        String profile = InstrumentationRegistry.getArguments().getString("busyStopProfile", "");
        assertTrue("Expected frozen pilot or formal profile", "pilot".equals(profile) || "formal".equals(profile));
        int rounds = "formal".equals(profile) ? 100 : 2;
        JsonObject report = JsonParser.parseString(ROOT_TEMPLATE).getAsJsonObject();
        report.addProperty("profile", profile);
        report.addProperty("api", Build.VERSION.SDK_INT);
        report.addProperty("appProcessPid", Process.myPid());
        JsonObject device = report.getAsJsonObject("device");
        device.addProperty("manufacturer", Build.MANUFACTURER);
        device.addProperty("model", Build.MODEL);
        device.addProperty("fingerprint", Build.FINGERPRINT);
        report.getAsJsonObject("conditions").addProperty("rounds", rounds);
        report.getAsJsonObject("conditions").addProperty("trialCount", rounds * 2);
        report.addProperty("startedNanoTime", System.nanoTime());
        deadlineNanoTime = System.nanoTime() + ("formal".equals(profile) ? 2300L : 1100L) * 1_000_000_000L;
        File directory = new File(context.getFilesDir(), "performance/busy-stop");
        assertFresh(directory);
        write(directory, "busy-stop-diagnostics.json", report, 65_536);
        Environment environment = null;
        boolean passed = false;
        try {
            environment = new Environment();
            launchFixture("SlowActivity");
            screenshots.capture("busy-stop-sequence-before");
            for (int index = 0; index < rounds * 2; index++) {
                checkDeadline();
                Trial trial = new Trial(index);
                try { runTrial(environment, trial, false); }
                finally { commitTrial(directory, report, trial); }
            }
            screenshots.capture("busy-stop-sequence-after");
            passed = true;
        } finally {
            try { if (environment != null) environment.close(); }
            finally {
                if (environment != null) report.add("cleanup", environment.cleanup);
                report.addProperty("endedNanoTime", System.nanoTime());
                report.addProperty("status", passed && allTrue(report.getAsJsonObject("cleanup")) ? "passed" : "failed");
                write(directory, "busy-stop-diagnostics.json", report, 65_536);
            }
        }
    }

    @Test public void recordBusyStopWalkthrough() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assertEquals("agent-os-busy-stop-video-v1", args.getString("busyStopDemoMarker"));
        assertEquals("35", args.getString("expectedApi"));
        assertEquals(35, Build.VERSION.SDK_INT);
        String sourceSha = args.getString("sourceSha", ""), run = args.getString("runId", ""), attempt = args.getString("runAttempt", "");
        assertTrue(sourceSha.matches("[0-9a-f]{40}"));
        assertTrue(run.matches("[1-9][0-9]{0,17}") && attempt.matches("[1-9][0-9]{0,3}"));
        JsonObject flow = new JsonObject(), source = new JsonObject();
        flow.addProperty("schemaVersion", 1); flow.addProperty("kind", "agent-os-busy-stop-walkthrough");
        source.addProperty("repository", "ecojust/Agent-OS"); source.addProperty("commit", sourceSha);
        source.addProperty("runId", Long.parseLong(run)); source.addProperty("attempt", Integer.parseInt(attempt));
        flow.add("source", source); flow.addProperty("apiLevel", Build.VERSION.SDK_INT);
        flow.addProperty("profile", "walkthrough"); flow.addProperty("scenario", "DEVICE");
        flow.addProperty("status", "incomplete"); flow.addProperty("performanceSample", false);
        flow.addProperty("secureFlagsPreserved", true); flow.addProperty("startedNanoTime", System.nanoTime());
        flow.add("endedNanoTime", JsonNull.INSTANCE); flow.add("trial", JsonNull.INSTANCE); flow.add("failure", JsonNull.INSTANCE);
        File directory = new File(context.getFilesDir(), "busy-stop-video");
        assertFresh(directory); write(directory, "flow-result.json", flow, 65_536);
        deadlineNanoTime = System.nanoTime() + 120_000_000_000L;
        Trial trial = new Trial(0);
        Environment environment = null;
        boolean passed = false;
        boolean trialStarted = false;
        Throwable primary = null;
        try {
            environment = new Environment();
            trialStarted = true;
            runTrial(environment, trial, true);
            passed = true;
        } catch (Exception | Error failure) {
            primary = failure;
            JsonObject detail = new JsonObject(); detail.addProperty("stage", trial.phase());
            detail.addProperty("errorClass", failure.getClass().getSimpleName()); flow.add("failure", detail);
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            try { if (environment != null) environment.close(); }
            catch (Exception | Error failure) {
                cleanupFailure = failure;
                if (primary != null) primary.addSuppressed(failure);
                else {
                    JsonObject detail = new JsonObject(); detail.addProperty("stage", "cleanup");
                    detail.addProperty("errorClass", failure.getClass().getSimpleName()); flow.add("failure", detail);
                }
            }
            finally {
                if (trialStarted) flow.add("trial", trial.value);
                flow.addProperty("endedNanoTime", System.nanoTime());
                flow.addProperty("status", passed && cleanupFailure == null && environment != null && allTrue(environment.cleanup) ? "passed" : "failed");
                write(directory, "flow-result.json", flow, 65_536);
            }
            if (primary == null && cleanupFailure != null) rethrow(cleanupFailure);
        }
    }

    private void runTrial(Environment environment, Trial trial, boolean walkthrough) throws Exception {
        BusyStopModelServer server = null;
        ControllerTestSettings settings = null;
        BusyStopProbe.Session probe = null;
        PerformanceTrace.Session trace = null;
        BusyStopFrameProbe frames = null;
        MainActivity activity = null;
        BoundButton stop = null;
        Throwable primary = null;
        boolean passed = false;
        String token = trial.device ? UUID.randomUUID().toString() : null;
        JsonObject fixtureSnapshots = trial.device ? new JsonObject() : null;
        if (fixtureSnapshots != null) {
            fixtureSnapshots.add("beforeStop", JsonNull.INSTANCE); fixtureSnapshots.add("beforeRelease", JsonNull.INSTANCE);
            fixtureSnapshots.add("afterSettle", JsonNull.INSTANCE); trial.value.add("fixture", fixtureSnapshots);
        }
        trial.value.addProperty("startedNanoTime", System.nanoTime());
        try {
            clearController(environment.controller);
            drainInfrastructure(null);
            server = new BusyStopModelServer(!trial.device);
            settings = new ControllerTestSettings(context, new ModelSettings(server.endpoint(),
                    LocalControllerModelServer.SYNTHETIC_KEY, LocalControllerModelServer.MODEL), ActionConfirmationPolicy.Mode.CONFIRM_ALL);
            launchFixture(trial.device ? "SlowActivity" : "FixtureActivity");
            assertEquals(0, actualCounter());
            trial.checks().addProperty("initialCounter", 0);
            if (walkthrough) { screenshots.capture("busy-stop-ready-count-zero"); SystemClock.sleep(1200); }
            trace = PerformanceTrace.startDiagnostics();
            ControllerTestSettings currentSettings = settings;
            onMain(() -> {
                environment.controller.start(currentSettings.model, "Increment this synthetic fixture counter once.",
                        Collections.singleton(FIXTURE), currentSettings.executionMode, TaskBudget.DEFAULT, currentSettings.lease);
                trial.owner = (RunControl) field(environment.controller, "control");
                assertNotNull("Controller did not create its real owner", trial.owner);
            });
            trial.value.addProperty("taskId", trial.owner.getTaskId());
            trial.value.addProperty("epochBefore", trial.owner.getExecutionEpoch());
            probe = BusyStopProbe.start(trial.owner);
            BusyStopModelServer model = server;
            if (trial.device) {
                trial.phase("await_platform");
                Set<String> approvals = new HashSet<>();
                boolean armed = false;
                long until = System.nanoTime() + 15_000_000_000L;
                while (System.nanoTime() < until) {
                    checkDeadline(); model.assertHealthy();
                    AgentController.State state = environment.controller.getState();
                    assertTrue("Task ended before the held platform callback", state.running);
                    if (!state.approvalId.isEmpty() && approvals.add(state.approvalId)) {
                        assertTrue("Unexpected approval replans", approvals.size() <= 4);
                        if (stop == null) stop = bindOverlayStop(environment.service);
                        if (!armed) { fixtureCall("arm_action", token); armed = true; }
                        String approval = state.approvalId;
                        onMain(() -> environment.controller.approve(approval, true));
                        trial.checks().addProperty("approvalCount", approvals.size());
                    }
                    if (armed && fixtureCall("status", token).getBoolean("claimed")) break;
                    SystemClock.sleep(5);
                }
                JsonObject blocked = fixtureSnapshot(token);
                assertBlocked(blocked); fixtureSnapshots.add("beforeStop", blocked);
                assertTrue(trial.owner.hasInFlightDispatch());
                assertEquals(1, trial.owner.getDispatchAttempts());
                assertNotNull("Actual stop button was not bound", stop);
            } else {
                trial.phase("await_model");
                assertTrue("Model did not receive and hold a real request", model.entered.await(10, TimeUnit.SECONDS));
                model.assertHealthy(); assertTrue(model.isHeld());
                activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                MainActivity current = activity;
                await("MainActivity did not become ready", () -> onMainValue(() ->
                        (Boolean) field(current, "ready") && !(Boolean) field(current, "busy")
                                && (Boolean) field(current, "resumed") && current.hasWindowFocus()), 8_000);
                onMain(current::dismissOwnTaskKeyboard);
                stop = bindMainStop(current, environment.service);
                frames = new BusyStopFrameProbe(instrumentation, current, trial.owner);
                assertTrue("No natural running-state baseline frame", frames.awaitBaseline(3_000));
                assertTrue(model.isHeld());
            }
            assertFalse(trial.owner.isCancelled());
            trial.checks().addProperty("requestCountBeforeStop", model.requests.get());
            trial.value.add("beforeStop", snapshot(environment, trial));
            AtomicReference<View> originalOverlayReference = new AtomicReference<>();
            BoundButton originalStop = stop;
            onMain(() -> {
                View original = (View) field(environment.service, "overlay");
                assertNotNull("Original task overlay is missing before stop", original);
                if (trial.device) assertSame("Stop button belongs to a different overlay", original, originalStop.ownerRoot);
                JsonObject observed = new JsonObject();
                observed.addProperty("originalAttached", original.isAttachedToWindow());
                observed.addProperty("currentMatchesOriginal", field(environment.service, "overlay") == original);
                observed.addProperty("controlAvailable", environment.service.isControlAvailable());
                trial.feedback().add("overlayBefore", observed);
                assertTrue("Original task overlay is not available before stop", allTrue(observed));
                originalOverlayReference.set(original);
            });
            View originalOverlay = originalOverlayReference.get();
            trial.phase("stop_input");
            stop.installTouchRecorder(trial.value.getAsJsonObject("input"));
            injectStop(stop, trial.value.getAsJsonObject("input"), frames);
            await("Actual button did not cancel the original task", () -> trial.owner.isCancelled()
                    && !environment.controller.getState().running, 1_500);
            trial.value.add("afterStop", snapshot(environment, trial));
            await("Original stopped overlay did not detach", () -> onMainValue(() -> {
                JsonObject observed = new JsonObject();
                observed.addProperty("originalAttached", originalOverlay.isAttachedToWindow());
                observed.addProperty("currentOverlayPresent", field(environment.service, "overlay") != null);
                observed.addProperty("controlAvailable", environment.service.isControlAvailable());
                trial.feedback().add("overlayAfter", observed);
                if (observed.get("originalAttached").getAsBoolean()
                        || observed.get("currentOverlayPresent").getAsBoolean()
                        || observed.get("controlAvailable").getAsBoolean()) return false;
                trial.feedback().addProperty("controlGoneObservedNanoTime", System.nanoTime());
                return true;
            }), 1_500);
            assertEquals(trial.value.get("epochBefore").getAsLong() + 1, trial.owner.getExecutionEpoch());
            AgentController.TaskUsage frozen = environment.controller.getTaskUsage();
            AgentController.State frozenState = environment.controller.getState();
            assertNotNull(frozen); assertTrue(frozen.finished); assertEquals(trial.owner.getTaskId(), frozen.taskId);
            assertEquals(TaskResult.Kind.STOPPED, frozenState.resultKind); assertEquals("TASK_STOPPED", frozenState.resultCode);
            if (frames != null) {
                trial.phase("stop_frame");
                boolean complete = frames.awaitAndDrain(3_000);
                trial.value.add("frames", frames.snapshot());
                assertTrue("First natural stopped frame was not complete", complete);
            }
            AndroidDeviceTools rejectedTools = testAdapter(environment.service);
            if (trial.device) {
                trial.phase("blocked_gate");
                RunControl other = new RunControl(SettingsStore.currentProfileId(context));
                JsonObject busy = rejectedTools.execute("observe", new JsonObject(), other, other.getExecutionEpoch());
                JsonObject gate = new JsonObject(); gate.addProperty("sampleNanoTime", System.nanoTime());
                gate.addProperty("replacementTaskId", other.getTaskId()); gate.addProperty("replacementEpoch", other.getExecutionEpoch());
                gate.addProperty("code", busy.get("code").getAsString()); gate.addProperty("attempts", other.getDispatchAttempts());
                for (String key : new String[]{"submitted", "dispatchSubmitted", "dispatchAccepted"}) gate.add(key, busy.get(key));
                trial.value.add("gateProbe", gate);
                assertEquals("CAPABILITY_UNAVAILABLE", gate.get("code").getAsString());
                assertEquals(0, other.getDispatchAttempts());
                for (String key : new String[]{"submitted", "dispatchSubmitted", "dispatchAccepted"}) assertFalse(gate.get(key).getAsBoolean());
                JsonObject stillBlocked = fixtureSnapshot(token); assertBlocked(stillBlocked);
                fixtureSnapshots.add("beforeRelease", stillBlocked);
                assertTrue(trial.owner.hasInFlightDispatch());
            } else assertTrue(model.isHeld());
            trial.value.add("beforeRelease", snapshot(environment, trial));
            if (walkthrough) SystemClock.sleep(500);
            trial.phase("release");
            trial.value.addProperty("releaseRequestedNanoTime", System.nanoTime());
            if (trial.device) fixtureCall("release", token);
            else { model.releaseAfterStop(); model.awaitWritten(); }
            trial.phase("settle");
            drainController(environment.controller);
            trial.checks().addProperty("workerDrained", true);
            trial.checks().addProperty("workerDrainedNanoTime", System.nanoTime());
            drainInfrastructure(trial);
            assertFalse(trial.owner.hasInFlightDispatch());
            if (trial.device) {
                JsonObject finished = fixtureSnapshot(token); fixtureSnapshots.add("afterSettle", finished);
                assertTrue(finished.get("returned").getAsBoolean()); assertFalse(finished.get("expired").getAsBoolean());
                assertEquals(1, finished.get("effectCount").getAsInt());
                assertEquals(1, actualCounter()); trial.checks().addProperty("finalCounter", 1);
            } else {
                // Foreground MainActivity remains for its real feedback frame; return to the fixture only after measurement.
                closeActivity(activity); activity = null;
                await("Original fixture did not resume after closing MainActivity", this::fixtureVisible, 5_000);
                assertEquals(0, actualCounter()); trial.checks().addProperty("finalCounter", 0);
            }
            trial.value.add("afterSettle", snapshot(environment, trial));
            JsonObject stale = new JsonObject();
            try {
                rejectedTools.execute("observe", new JsonObject(), trial.owner, trial.value.get("epochBefore").getAsLong());
                fail("An old stopped epoch was accepted");
            } catch (TaskControlException stopped) { stale.addProperty("code", stopped.getCode()); }
            stale.addProperty("sampleNanoTime", System.nanoTime()); stale.addProperty("attempts", trial.owner.getDispatchAttempts());
            trial.value.add("oldEpochProbe", stale); assertEquals("TASK_STOPPED", stale.get("code").getAsString());
            trial.phase("quiet");
            trial.feedback().addProperty("quietStartedNanoTime", System.nanoTime());
            SystemClock.sleep(500);
            trial.feedback().addProperty("quietEndedNanoTime", System.nanoTime());
            trial.value.add("finalQuiet", snapshot(environment, trial));
            trial.checks().addProperty("frozenUsageUnchanged", frozen == environment.controller.getTaskUsage());
            AgentController.State ending = environment.controller.getState();
            trial.checks().addProperty("frozenResultUnchanged", ending.resultKind == frozenState.resultKind
                    && ending.resultCode.equals(frozenState.resultCode) && ending.summary.equals(frozenState.summary));
            trial.checks().addProperty("noFurtherRequests", model.requests.get() == trial.checks().get("requestCountBeforeStop").getAsInt());
            assertTrue(trial.checks().get("frozenUsageUnchanged").getAsBoolean());
            assertTrue(trial.checks().get("frozenResultUnchanged").getAsBoolean());
            assertTrue(trial.checks().get("noFurtherRequests").getAsBoolean());
            assertEquals(trial.device ? 1 : 0, trial.owner.getDispatchAttempts());
            assertEquals("Busy-stop probe lost raw events", 0, probe.overflow());
            assertEquals("Performance trace lost raw events", 0, trace.overwrittenCount());
            assertTrue("Performance trace exceeded the frozen raw prefix cap", trace.events().size() <= 128);
            model.assertHealthy();
            if (walkthrough) { screenshots.capture("busy-stop-original-action-completed-once-after-stop"); SystemClock.sleep(1200); }
            passed = true;
        } catch (Exception | Error failure) {
            primary = failure;
            trial.value.addProperty("failure", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            if (probe != null) { trial.captureProbe(probe); probe.close(); }
            if (trace != null) { trial.captureTrace(trace); trace.close(); }
            if (server != null) trial.value.add("model", server.snapshot());
            try { if (stop != null) stop.removeTouchRecorder(); trial.cleaned("touchListenerRemoved"); }
            catch (Exception | Error e) { cleanupFailure = e; }
            try { if (frames != null) { frames.close(); trial.value.add("frames", frames.snapshot()); } trial.cleaned("frameProbeClosed"); }
            catch (Exception | Error e) { cleanupFailure = preserve(cleanupFailure, e); }
            try { if (trial.device && token != null) fixtureCall("release", token); if (server != null && !trial.device) server.releaseAfterStop(); }
            catch (Exception | Error e) { cleanupFailure = preserve(cleanupFailure, e); }
            try { clearController(environment.controller); drainInfrastructure(null); trial.cleaned("controllerCleared"); }
            catch (Exception | Error e) { cleanupFailure = preserve(cleanupFailure, e); }
            try { if (trial.device && token != null) fixtureCall("clear", token); trial.cleaned("fixtureCleared"); }
            catch (Exception | Error e) { cleanupFailure = preserve(cleanupFailure, e); }
            try { if (activity != null) closeActivity(activity); trial.cleaned("activityClosed"); }
            catch (Exception | Error e) { cleanupFailure = preserve(cleanupFailure, e); }
            try { if (settings != null) settings.close(); trial.cleaned("settingsRestored"); }
            catch (Exception | Error e) { cleanupFailure = preserve(cleanupFailure, e); }
            try { if (server != null) server.close(); trial.cleaned("serverClosed"); }
            catch (Exception | Error e) { cleanupFailure = preserve(cleanupFailure, e); }
            trial.value.addProperty("endedNanoTime", System.nanoTime());
            trial.value.addProperty("status", passed && cleanupFailure == null ? "passed" : "failed");
            if (passed && cleanupFailure == null) trial.phase("complete");
            if (cleanupFailure != null) {
                if (primary != null) primary.addSuppressed(cleanupFailure);
                else {
                    trial.phase("cleanup");
                    trial.value.addProperty("failure", cleanupFailure.getClass().getSimpleName());
                    rethrow(cleanupFailure);
                }
            }
        }
    }

    private final class Environment implements AutoCloseable {
        final AgentController controller = AgentController.get(context);
        final JsonObject cleanup = new JsonObject();
        final String services, enabled;
        final int flags;
        AgentAccessibilityService service;
        Environment() throws Exception {
            assertFalse("Dedicated synthetic device must have no existing task", controller.getState().running);
            services = secureRead(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled = secureRead(Settings.Secure.ACCESSIBILITY_ENABLED);
            AccessibilityServiceInfo info = automation.getServiceInfo(); flags = info.flags;
            for (String key : new String[]{"controllerCleared", "servicesRestored", "enabledRestored", "automationFlagsRestored"}) cleanup.addProperty(key, false);
            try {
                info.flags |= AUTOMATION_FLAGS; automation.setServiceInfo(info);
                String value = services == null || services.isEmpty() ? COMPONENT
                        : services.contains(COMPONENT) ? services : services + ":" + COMPONENT;
                secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
                secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
                await("Agent service did not connect", () -> {
                    service = AgentAccessibilityService.getInstance(); return service != null && service.isConnected();
                }, 8_000);
                clearController(controller); drainInfrastructure(null);
            } catch (Exception | Error failure) { try { close(); } catch (Throwable extra) { failure.addSuppressed(extra); } throw failure; }
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try { clearController(controller); drainInfrastructure(null); cleanup.addProperty("controllerCleared", true); }
            catch (Exception | Error e) { failure = e; }
            try { secureWrite(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services); cleanup.addProperty("servicesRestored", true); }
            catch (Exception | Error e) { failure = preserve(failure, e); }
            try { secureWrite(Settings.Secure.ACCESSIBILITY_ENABLED, enabled); cleanup.addProperty("enabledRestored", true); }
            catch (Exception | Error e) { failure = preserve(failure, e); }
            try {
                AccessibilityServiceInfo info = automation.getServiceInfo(); info.flags = (info.flags & ~AUTOMATION_FLAGS) | (flags & AUTOMATION_FLAGS);
                automation.setServiceInfo(info); cleanup.addProperty("automationFlagsRestored", true);
            } catch (Exception | Error e) { failure = preserve(failure, e); }
            if (failure != null) rethrow(failure);
        }
    }

    private final class Trial {
        final JsonObject value = JsonParser.parseString(TRIAL_TEMPLATE).getAsJsonObject();
        final boolean device;
        RunControl owner;
        Trial(int index) {
            int round = index / 2, position = index % 2;
            device = (round % 2 == 0) == (position == 0);
            value.addProperty("index", index); value.addProperty("round", round); value.addProperty("position", position);
            value.addProperty("order", round % 2 == 0 ? "DEVICE_MODEL" : "MODEL_DEVICE");
            value.addProperty("scenario", device ? "DEVICE" : "MODEL");
        }
        JsonObject checks() { return value.getAsJsonObject("checks"); }
        JsonObject feedback() { return value.getAsJsonObject("feedback"); }
        void phase(String phase) { value.addProperty("phase", phase); }
        String phase() { return value.get("phase").getAsString(); }
        void cleaned(String key) { value.getAsJsonObject("cleanup").addProperty(key, true); }
        void captureProbe(BusyStopProbe.Session probe) {
            JsonObject data = new JsonObject(); JsonArray events = new JsonArray();
            for (long[] row : probe.snapshot()) { JsonArray entry = new JsonArray(); for (long x : row) entry.add(x); events.add(entry); }
            data.add("events", events); data.addProperty("overflow", probe.overflow()); data.addProperty("ownerMatched", probe.owns(owner));
            value.add("probe", data);
        }
        void captureTrace(PerformanceTrace.Session trace) {
            JsonObject data = new JsonObject(); JsonArray events = new JsonArray();
            List<PerformanceTrace.Event> all = trace.events();
            // Preserve a bounded raw prefix on failure; overflow never passes validation.
            for (int i = 0; i < Math.min(128, all.size()); i++) {
                PerformanceTrace.Event event = all.get(i); JsonArray row = new JsonArray();
                row.add(event.stage.name()); row.add(event.startNanos); row.add(event.durationNanos);
                row.add(event.mainThread); row.add(event.epoch); row.add(event.dispatchAttempts); events.add(row);
            }
            data.add("events", events); data.addProperty("overwritten", trace.overwrittenCount() + Math.max(0, all.size() - 128));
            value.add("trace", data);
        }
    }

    private final class BoundButton {
        final Button button;
        final View ownerRoot;
        final Rect bounds;
        BoundButton(Button button, View root, Rect bounds) { this.button = button; ownerRoot = root; this.bounds = bounds; }
        void installTouchRecorder(JsonObject value) {
            onMain(() -> {
                Rect current = new Rect(); assertTrue(button.getGlobalVisibleRect(current));
                // Approval collapse may legitimately move controls. Bind the current local
                // Agent view before input; never query the blocked remote fixture or retry input.
                assertTrue(ownerRoot.isAttachedToWindow() && button.getRootView() == ownerRoot.getRootView());
                bounds.set(current);
                assertTrue(button.isAttachedToWindow() && button.isShown() && button.isEnabled());
                JsonArray rectangle = new JsonArray(); rectangle.add(bounds.left); rectangle.add(bounds.top); rectangle.add(bounds.right); rectangle.add(bounds.bottom);
                JsonArray point = new JsonArray(); point.add(bounds.centerX()); point.add(bounds.centerY());
                value.add("bounds", rectangle); value.add("point", point);
                button.setOnTouchListener((view, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        value.addProperty("touchUpNanoTime", System.nanoTime());
                        value.addProperty("touchUpElapsedNanos", SystemClock.elapsedRealtimeNanos());
                        value.addProperty("touchUpCount", value.get("touchUpCount").getAsInt() + 1);
                    }
                    return false; // Preserve the actual production Button onClick path.
                });
            });
        }
        void removeTouchRecorder() { onMain(() -> button.setOnTouchListener(null)); }
    }

    private BoundButton bindOverlayStop(AgentAccessibilityService service) {
        AtomicReference<BoundButton> result = new AtomicReference<>();
        await("Actual overlay stop control is not ready", () -> {
            onMain(() -> {
                View root = (View) field(service, "overlay"); if (root == null || !root.isAttachedToWindow()) return;
                Button stop = findStop(root); if (stop == null) return;
                Rect bounds = new Rect(); if (!stop.isEnabled() || !stop.getGlobalVisibleRect(bounds) || bounds.isEmpty()) return;
                result.set(new BoundButton(stop, root, bounds));
            });
            return result.get() != null;
        }, 5_000);
        return result.get();
    }
    private BoundButton bindMainStop(MainActivity activity, AgentAccessibilityService service) {
        await("Main stopped-state text is not laid out", () -> onMainValue(() -> {
            View status = activity.findViewById(R.id.status_text);
            return status != null && status.getWidth() > 0 && status.getHeight() > 0
                    && activity.getWindow().getDecorView().getWindowToken() != null;
        }), 5_000);
        onMain(() -> { View status = activity.findViewById(R.id.status_text); assertNotNull(status);
            status.requestRectangleOnScreen(new Rect(0, 0, status.getWidth(), status.getHeight()), true); });
        AtomicReference<BoundButton> result = new AtomicReference<>();
        await("Actual main stop control is not visible outside overlay", () -> {
            onMain(() -> {
                Button button = activity.findViewById(R.id.stop_task); Rect bounds = new Rect();
                View status = activity.findViewById(R.id.status_text); Rect statusBounds = new Rect();
                if (!status.getGlobalVisibleRect(statusBounds) || statusBounds.width() != status.getWidth()
                        || statusBounds.height() != status.getHeight()) return;
                if (!activity.hasWindowFocus() || !button.isShown() || !button.isEnabled() || !button.getGlobalVisibleRect(bounds)
                        || bounds.isEmpty() || service.controlBounds().contains(bounds.centerX(), bounds.centerY())) return;
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                result.set(new BoundButton(button, activity.getWindow().getDecorView(), bounds));
            });
            return result.get() != null;
        }, 5_000);
        return result.get();
    }
    private static Button findStop(View view) {
        if (view instanceof Button && "停止".contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findStop(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private void injectStop(BoundButton stop, JsonObject value, BusyStopFrameProbe frames) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, stop.bounds.centerX(), stop.bounds.centerY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { value.addProperty("downIssuedNanoTime", System.nanoTime()); value.addProperty("downAccepted", automation.injectInputEvent(down, true)); }
        finally { down.recycle(); value.addProperty("downReturnedNanoTime", System.nanoTime()); }
        assertTrue(value.get("downAccepted").getAsBoolean()); SystemClock.sleep(30);
        if (frames != null) frames.arm();
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                stop.bounds.centerX(), stop.bounds.centerY(), 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            value.addProperty("upIssuedElapsedNanos", SystemClock.elapsedRealtimeNanos());
            value.addProperty("upIssuedNanoTime", System.nanoTime());
            value.addProperty("upAccepted", automation.injectInputEvent(up, true));
        } finally { up.recycle(); value.addProperty("upReturnedNanoTime", System.nanoTime()); }
        assertTrue(value.get("upAccepted").getAsBoolean());
        onMain(() -> assertEquals("Touch did not reach the exact bound production button", 1, value.get("touchUpCount").getAsInt()));
    }

    private JsonObject snapshot(Environment environment, Trial trial) {
        AgentController.State state = environment.controller.getState();
        AgentController.TaskUsage usage = state.usage;
        JsonObject result = new JsonObject();
        result.addProperty("sampleNanoTime", System.nanoTime());
        result.addProperty("cancelled", trial.owner.isCancelled()); result.addProperty("epoch", trial.owner.getExecutionEpoch());
        result.addProperty("dispatchAttempts", trial.owner.getDispatchAttempts()); result.addProperty("inFlight", trial.owner.hasInFlightDispatch());
        result.addProperty("controllerRunning", state.running);
        result.addProperty("resultStopped", state.resultKind == TaskResult.Kind.STOPPED && "TASK_STOPPED".equals(state.resultCode));
        result.addProperty("usageTaskMatches", usage != null && trial.owner.getTaskId().equals(usage.taskId));
        result.addProperty("controlAvailable", onMainValue(environment.service::isControlAvailable));
        result.addProperty("ownerTaskId", trial.owner.getTaskId()); result.addProperty("usageTaskId", usage == null ? null : usage.taskId);
        result.addProperty("resultKind", state.resultKind == null ? null : state.resultKind.name()); result.addProperty("resultCode", state.resultCode);
        return result;
    }
    private AndroidDeviceTools testAdapter(AgentAccessibilityService service) throws Exception {
        return AndroidDeviceTools.forTesting(service, Collections.singleton(FIXTURE),
                Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(context, FIXTURE)), true, () -> true,
                (description, owner, epoch) -> { throw new AssertionError("Read-only rejection probe requested approval"); });
    }
    private Bundle fixtureCall(String method, String token) {
        Bundle result = context.getContentResolver().call(Uri.parse("content://dev.agentos.fixture.slow-read-control"), method, token, null);
        assertNotNull("Fixture provider returned no state", result); return result;
    }
    private JsonObject fixtureSnapshot(String token) {
        Bundle raw = fixtureCall("status", token); assertTrue(raw.getBoolean("exists")); assertEquals("ACTION", raw.getString("kind"));
        JsonObject result = new JsonObject(); result.addProperty("token", token); result.addProperty("pid", raw.getInt("pid"));
        for (String key : new String[]{"claimed", "released", "returned", "expired"}) result.addProperty(key, raw.getBoolean(key));
        for (String key : new String[]{"barrierEnteredNanoTime", "barrierExitedNanoTime", "effectWrittenNanoTime"}) result.addProperty(key, raw.getLong(key));
        result.addProperty("effectCount", raw.getInt("effectCount"));
        result.addProperty("startedElapsedNanos", raw.getLong("startedNanos")); result.addProperty("finishedElapsedNanos", raw.getLong("finishedNanos"));
        return result;
    }
    private static void assertBlocked(JsonObject value) {
        assertTrue(value.get("claimed").getAsBoolean()); assertFalse(value.get("released").getAsBoolean());
        assertFalse(value.get("returned").getAsBoolean()); assertFalse(value.get("expired").getAsBoolean());
        assertEquals(-1, value.get("effectCount").getAsInt());
    }
    private void launchFixture(String name) throws Exception {
        context.startActivity(new Intent().setComponent(new ComponentName(FIXTURE, FIXTURE + "." + name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await("Fixture did not become foreground", () -> fixtureVisible(), 8_000);
        automation.waitForIdle(300, 5_000);
        assertTrue(fixtureVisible());
    }
    private boolean fixtureVisible() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try { return FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()); }
        finally { root.recycle(); }
    }
    private int actualCounter() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow(); assertNotNull("Missing fixture root", root);
        try {
            assertEquals(FIXTURE, String.valueOf(root.getPackageName()));
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/counter_value");
            try {
                assertEquals(1, nodes.size()); assertTrue(nodes.get(0).refresh());
                String value = String.valueOf(nodes.get(0).getText());
                assertTrue("Unexpected synthetic counter", value.matches("Count: [0-9]{1,3}"));
                return Integer.parseInt(value.substring(7));
            } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
        } finally { root.recycle(); }
    }
    private void closeActivity(MainActivity activity) { onMain(activity::finish); instrumentation.waitForIdleSync(); }
    private void clearController(AgentController controller) throws Exception {
        onMain(controller::stop); drainController(controller); onMain(controller::clearCompletedTask); instrumentation.waitForIdleSync();
    }
    private static void drainController(AgentController controller) throws Exception {
        ((ThreadPoolExecutor) field(controller, "worker")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    private static void drainInfrastructure(Trial trial) throws Exception {
        Class<?> type = Class.forName("dev.agentos.core.HttpModelClient");
        Field network = type.getDeclaredField("NETWORK"); network.setAccessible(true);
        ((ThreadPoolExecutor) network.get(null)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        if (trial != null) { trial.checks().addProperty("networkDrained", true); trial.checks().addProperty("networkDrainedNanoTime", System.nanoTime()); }
        Field closer = type.getDeclaredField("CLOSER"); closer.setAccessible(true);
        ((ThreadPoolExecutor) closer.get(null)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        if (trial != null) { trial.checks().addProperty("closerDrained", true); trial.checks().addProperty("closerDrainedNanoTime", System.nanoTime()); }
    }
    private void commitTrial(File directory, JsonObject report, Trial trial) throws Exception {
        int index = trial.value.get("index").getAsInt(); String path = String.format(java.util.Locale.ROOT, "trial-%03d.json", index);
        byte[] bytes = write(directory, path, trial.value, 32_768);
        JsonObject ref = new JsonObject();
        for (String key : new String[]{"index", "round", "position", "order", "scenario", "status"}) ref.add(key, trial.value.get(key));
        ref.addProperty("path", path); ref.addProperty("bytes", bytes.length); ref.addProperty("sha256", sha256(bytes));
        report.getAsJsonArray("trials").add(ref); write(directory, "busy-stop-diagnostics.json", report, 65_536);
    }
    private static byte[] write(File directory, String name, JsonObject value, int cap) throws Exception {
        assertTrue(directory.isDirectory() || directory.mkdirs());
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        assertTrue("Diagnostic file exceeded its frozen capacity: " + name, bytes.length <= cap);
        File temporary = new File(directory, name + ".tmp"), destination = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
        assertTrue("Could not atomically commit diagnostic file", temporary.renameTo(destination)); return bytes;
    }
    private static void assertFresh(File directory) {
        String[] files = directory.list(); assertTrue("Diagnostics require a fresh run-specific app data directory", files == null || files.length == 0);
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
    private String secureRead(String name) { return Settings.Secure.getString(context.getContentResolver(), name); }
    private void secureWrite(String name, String value) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try { assertTrue(Settings.Secure.putString(context.getContentResolver(), name, value)); }
        finally { automation.dropShellPermissionIdentity(); }
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private interface Check { boolean get() throws Exception; }
    private void await(String message, Check check, long millis) {
        long until = Math.min(deadlineNanoTime, System.nanoTime() + millis * 1_000_000L);
        try {
            while (System.nanoTime() < until) { if (check.get()) return; SystemClock.sleep(5); }
            assertTrue(message, check.get());
        } catch (Exception e) { throw new AssertionError(message, e); }
    }
    private void checkDeadline() { assertTrue("Busy-stop sequence watchdog exceeded", System.nanoTime() < deadlineNanoTime); }
    private void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) { if (failure.get() instanceof Error) throw (Error) failure.get(); throw new AssertionError(failure.get()); }
    }
    private boolean onMainValue(java.util.function.BooleanSupplier predicate) {
        boolean[] result = {false}; onMain(() -> result[0] = predicate.getAsBoolean()); return result[0];
    }
    private static boolean allTrue(JsonObject value) { for (java.util.Map.Entry<String, JsonElement> entry : value.entrySet()) if (!entry.getValue().getAsBoolean()) return false; return true; }
    private static Throwable preserve(Throwable original, Throwable extra) { if (original == null) return extra; if (original != extra) original.addSuppressed(extra); return original; }
    private static void rethrow(Throwable failure) throws Exception { if (failure instanceof Exception) throw (Exception) failure; throw (Error) failure; }
}
