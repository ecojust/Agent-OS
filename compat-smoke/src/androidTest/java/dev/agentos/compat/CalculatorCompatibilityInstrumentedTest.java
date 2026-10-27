package dev.agentos.compat;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** External-application smoke checks, separate from the product instrumentation process. */
@RunWith(AndroidJUnit4.class)
public final class CalculatorCompatibilityInstrumentedTest {
    private static final Object REPORT_LOCK = new Object();
    private static JSONObject report;
    private static JSONArray states;
    private static File directory;
    private static SmokeHarness harness;

    @BeforeClass public static void prepareDedicatedEmulator() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        directory = new File(context.getFilesDir(), "third-party-smoke");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create evidence directory");
        states = new JSONArray().put(emptyState("off")).put(emptyState("idle"));
        report = new JSONObject().put("schemaVersion", 1)
                .put("kind", "third-party-calculator-smoke")
                .put("actualInstalledIdentityVerified", false)
                .put("installedIdentityFailureSeen", false)
                .put("states", states);
        persist();
        try {
            // Check explicit opt-in before connecting UiAutomation. Device properties are
            // then checked independently; argument strings do not prove an emulator.
            SmokeHarness.validateArguments(arguments);
            report.put("source", SmokeHarness.source(arguments));
            persist();
            harness = new SmokeHarness(instrumentation, arguments);
            harness.requireDedicatedEmulator();
            report.put("device", harness.device()).put("dedicatedEmulatorVerified", true);
            persist();
        } catch (Throwable failure) {
            if (harness != null) report.put("device", harness.device());
            report.put("dedicatedEmulatorVerified", false)
                    .put("preparationFailure", safeError(failure));
            for (int i = 0; i < states.length(); i++) {
                states.getJSONObject(i).put("cause", "dedicated_emulator_preparation_failed");
            }
            persist();
            rethrow(failure);
        }
    }

    @Test public void calculatorCoreArithmeticWithAgentDisabled() throws Exception { exercise("off"); }

    @Test public void calculatorCoreArithmeticWithAgentBoundAndIdle() throws Exception { exercise("idle"); }

    private void exercise(String state) throws Exception {
        JSONObject entry = stateEntry(state);
        JSONArray steps = entry.getJSONArray("steps");
        JSONArray observations = entry.getJSONArray("serviceObservations");
        JSONArray actionObservations = entry.getJSONArray("serviceActionObservations");
        JSONArray captures = entry.getJSONArray("captures");
        String phase = "installed_identity";
        String originalServices = null;
        String originalEnabled = null;
        boolean settingsRead = false;
        Throwable failure = null;
        entry.put("startedAt", System.currentTimeMillis()).put("status", "unverified");
        try {
            harness.requireDedicatedEmulator();
            JSONObject identity = harness.verifyCalculatorIdentity();
            entry.put("installedIdentity", identity).put("installedIdentityVerified", true);
            report.put("actualInstalledIdentityVerified", true);
            passed(steps, phase, "PackageManager identity and installed APK digest match the frozen official release");

            phase = "service_preparation";
            originalServices = harness.readSecure("enabled_accessibility_services");
            originalEnabled = harness.readSecure("accessibility_enabled");
            settingsRead = true;
            harness.requireCleanServiceBaseline(originalServices);
            harness.setServiceState(state);
            observations.put(harness.awaitServiceState(state, "before"));
            entry.put("serviceState", expectedService(state));
            passed(steps, "service_state_before", "Settings, accessibility binding and activity service records agree");

            phase = "launch_calculator";
            actionObservations.put(harness.verifyServiceState(state, "before_launch"));
            harness.launchCalculator();
            actionObservations.put(harness.verifyServiceState(state, "after_launch"));
            passed(steps, phase, "Resolved the installed package launcher and observed its focused page");

            phase = "clear_calculator";
            perform(state, "btn_clear", AccessibilityNodeInfo.ACTION_LONG_CLICK, actionObservations);
            harness.awaitResult("0");
            if (!harness.readText("formula").isEmpty()) throw new AssertionError("Calculator reset did not clear formula");
            passed(steps, phase, "Long-click C invokes the pinned release's reset action; result is 0 and formula is empty");
            SmokeCapture.capture(harness, directory, state, "before", captures);

            for (String id : new String[] {"btn_2", "btn_plus", "btn_3", "btn_equals"}) {
                phase = "click_" + id;
                perform(state, id, AccessibilityNodeInfo.ACTION_CLICK, actionObservations);
                passed(steps, phase, "Unique visible resource target accepted the accessibility action");
            }

            phase = "calculator_result";
            String actual = harness.awaitResult("5");
            entry.getJSONObject("result").put("actual", actual);
            passed(steps, phase, "Independent read of org.fossify.math:id/result equals 5");
            SmokeCapture.capture(harness, directory, state, "result", captures);

            phase = "installed_identity_after";
            harness.verifyCalculatorIdentity();
            passed(steps, phase, "Installed calculator identity is unchanged after the UI case");

            phase = "service_state_after";
            observations.put(harness.verifyServiceState(state, "after"));
            passed(steps, phase, "The requested service state remains true after arithmetic and capture");
            entry.put("status", "passed").put("stage", "complete");
        } catch (Throwable error) {
            failure = error;
            if (phase.startsWith("installed_identity")) {
                entry.put("installedIdentityVerified", false);
                report.put("installedIdentityFailureSeen", true);
            }
            if (error instanceof SmokeHarness.ServiceStateFailure) {
                JSONObject observed = ((SmokeHarness.ServiceStateFailure) error).observation;
                String observedStage = observed.optString("stage");
                ("before".equals(observedStage) || "after".equals(observedStage)
                        ? observations : actionObservations).put(observed);
            }
            if ("calculator_result".equals(phase) && error instanceof SmokeHarness.ResultMismatch) {
                entry.getJSONObject("result").put("actual", ((SmokeHarness.ResultMismatch) error).actual);
            }
            entry.put("status", "failed").put("stage", phase).put("error", safeError(error));
            steps.put(new JSONObject().put("name", phase).put("status", "failed").put("details", safeError(error)));
            if (entry.optBoolean("installedIdentityVerified")) {
                SmokeCapture.capture(harness, directory, state, "failure", captures);
            } else {
                captures.put(new JSONObject().put("stage", "failure").put("status", "blocked")
                        .put("reason", "installed_identity_not_verified"));
            }
        } finally {
            if (settingsRead) {
                try {
                    harness.restoreServiceSettings(originalServices, originalEnabled);
                    passed(steps, "restore_service_settings", "Original accessibility settings restored");
                } catch (Throwable cleanup) {
                    entry.put("status", "failed").put("cleanupError", safeError(cleanup));
                    steps.put(new JSONObject().put("name", "restore_service_settings").put("status", "failed")
                            .put("details", safeError(cleanup)));
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            entry.put("finishedAt", System.currentTimeMillis());
            boolean anyIdentityVerified = false;
            for (int i = 0; i < states.length(); i++) {
                anyIdentityVerified |= states.getJSONObject(i).optBoolean("installedIdentityVerified");
            }
            report.put("actualInstalledIdentityVerified", anyIdentityVerified);
            persist();
        }
        if (failure != null) rethrow(failure);
    }

    private void perform(String state, String resource, int action, JSONArray observations) throws Exception {
        observations.put(harness.verifyServiceState(state, "before_" + resource));
        harness.perform(resource, action);
        observations.put(harness.verifyServiceState(state, "after_" + resource));
    }

    private static JSONObject emptyState(String state) throws Exception {
        return new JSONObject().put("state", state).put("status", "unverified").put("stage", "not_run")
                .put("cause", "test_has_not_executed").put("installedIdentityVerified", false)
                .put("serviceState", "unknown").put("serviceObservations", new JSONArray())
                .put("serviceActionObservations", new JSONArray())
                .put("steps", new JSONArray()).put("captures", new JSONArray())
                .put("result", new JSONObject().put("expected", "5").put("actual", JSONObject.NULL)
                        .put("resourceId", SmokeHarness.CALCULATOR + ":id/result"));
    }

    private static JSONObject stateEntry(String state) throws Exception {
        for (int i = 0; i < states.length(); i++) {
            JSONObject entry = states.getJSONObject(i);
            if (state.equals(entry.getString("state"))) { entry.remove("cause"); return entry; }
        }
        throw new IllegalArgumentException("Unknown frozen test state");
    }

    private static String expectedService(String state) { return "off".equals(state) ? "disabled" : "bound_idle"; }

    private static void passed(JSONArray steps, String name, String details) throws Exception {
        steps.put(new JSONObject().put("name", name).put("status", "passed").put("details", details));
    }

    private static String safeError(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return failure.getClass().getSimpleName() + ": " + message.substring(0, Math.min(message.length(), 400));
    }

    private static void persist() throws Exception {
        synchronized (REPORT_LOCK) {
            report.put("updatedAt", System.currentTimeMillis());
            byte[] data = report.toString(2).getBytes(StandardCharsets.UTF_8);
            if (data.length > 512 * 1024) throw new IllegalStateException("Smoke report exceeded its fixed limit");
            File pending = new File(directory, "ui-results.json.pending");
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(data);
                output.getFD().sync();
            }
            File destination = new File(directory, "ui-results.json");
            if (!pending.renameTo(destination)) throw new IllegalStateException("Could not publish smoke report");
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        throw new IllegalStateException(failure);
    }
}
