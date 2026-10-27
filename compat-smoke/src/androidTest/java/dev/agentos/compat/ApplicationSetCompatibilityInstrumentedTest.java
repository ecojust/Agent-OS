package dev.agentos.compat;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
public final class ApplicationSetCompatibilityInstrumentedTest {
    private static final Object REPORT_LOCK = new Object();
    private static JSONObject report;
    private static JSONArray apps;
    private static File directory;
    private SmokeHarness harness;

    @BeforeClass public static void prepareDedicatedEmulator() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        directory = new File(context.getFilesDir(), "third-party-smoke-v2");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create evidence directory");
        apps = new JSONArray().put(emptyApp(SmokeHarness.CALCULATOR_TARGET)).put(emptyApp(SmokeHarness.NOTES_TARGET));
        report = new JSONObject().put("schemaVersion", 2)
                .put("kind", "third-party-app-set-smoke")
                .put("sampleId", "calculator-1.4.0-notes-1.7.0-v2")
                .put("apps", apps);
        persist();
        SmokeHarness guard = null;
        try {
            // Check explicit opt-in before connecting UiAutomation. Device properties are
            // then checked independently; argument strings do not prove an emulator.
            SmokeHarness.validateArguments(arguments);
            SmokeHarness.require("agent-os-third-party-smoke-v2".equals(arguments.getString("syntheticTestMarker")),
                    "The application-set probe requires its version-two sample");
            report.put("source", SmokeHarness.source(arguments));
            persist();
            verifyPlatformParsers();
            guard = new SmokeHarness(instrumentation, arguments);
            guard.requireDedicatedEmulator();
            report.put("device", guard.device()).put("dedicatedEmulatorVerified", true);
            persist();
        } catch (Throwable failure) {
            if (guard != null) report.put("device", guard.device());
            report.put("dedicatedEmulatorVerified", false)
                    .put("preparationFailure", safeError(failure));
            for (int app = 0; app < apps.length(); app++) {
                JSONArray pending = apps.getJSONObject(app).getJSONArray("states");
                for (int i = 0; i < pending.length(); i++) pending.getJSONObject(i).put("cause", "dedicated_emulator_preparation_failed");
            }
            persist();
            rethrow(failure);
        }
    }

    @Test public void calculatorWithAgentDisabled() throws Exception { exercise(SmokeHarness.CALCULATOR_TARGET, "off"); }
    @Test public void calculatorWithAgentBoundAndIdle() throws Exception { exercise(SmokeHarness.CALCULATOR_TARGET, "idle"); }
    @Test public void notesWithAgentDisabled() throws Exception { exercise(SmokeHarness.NOTES_TARGET, "off"); }
    @Test public void notesWithAgentBoundAndIdle() throws Exception { exercise(SmokeHarness.NOTES_TARGET, "idle"); }

    private static void verifyPlatformParsers() throws Exception {
        // Run on each actual Android API's regex implementation, inside the versioned
        // four-case probe; JVM compilation alone does not exercise Android ICU syntax.
        for (String dump : new String[] {"attributes:{id=0}", "User state[attributes:{id=0, currentUser=true}]}"}) {
            if (!SmokeHarness.hasOwnerUserAttributes(dump)) throw new AssertionError("Owner-user dump parser rejected a supported boundary");
        }
        for (String dump : new String[] {"", "attributes:{id=01}", "attributes:{id=10}", "attributes:{id=0", "attributes:{id=0unexpected}"}) {
            if (SmokeHarness.hasOwnerUserAttributes(dump)) throw new AssertionError("Owner-user dump parser accepted an incomplete or foreign user");
        }
        for (String dump : new String[] {
                "ServiceRecord{abc u0 dev.agentos.app/.AgentAccessibilityService}",
                "ServiceRecord{abc u0 dev.agentos.app/dev.agentos.app.AgentAccessibilityService c:android}",
                "ServiceRecord{abc u0 dev.agentos.app/.AgentAccessibilityService c:com.android.shell}"}) {
            if (!SmokeHarness.hasAgentServiceRecord(dump)) throw new AssertionError("Service record parser rejected a supported platform format");
        }
        for (String dump : new String[] {
                "ServiceRecord{abc u0 other.app/.AgentAccessibilityService}",
                "ServiceRecord{abc u0 other.dev.agentos.app/.AgentAccessibilityService}",
                "ServiceRecord{abc u10 dev.agentos.app/.AgentAccessibilityService}",
                "ServiceRecord{abc u0 dev.agentos.app/.OtherService}",
                "ServiceRecord{abc u0 dev.agentos.app/.AgentAccessibilityServiceExtra}",
                "ServiceRecord{abc u0 dev.agentos.app/.AgentAccessibilityService c:}",
                "ServiceRecord{abc u0 dev.agentos.app/.AgentAccessibilityService"}) {
            if (SmokeHarness.hasAgentServiceRecord(dump)) throw new AssertionError("Service record parser accepted an unrelated or incomplete service");
        }
        Rect display = new Rect(0, 0, 320, 640);
        Insets bars = Insets.of(0, 24, 0, 48);
        int system = AccessibilityWindowInfo.TYPE_SYSTEM;
        for (Rect region : new Rect[] {new Rect(0, 0, 320, 24), new Rect(0, 592, 320, 640)}) {
            if (!SmokeHarness.isContainedInVisibleSystemBar(system, region, display, bars)) throw new AssertionError("Platform bar region was rejected");
        }
        for (Rect region : new Rect[] {new Rect(), new Rect(0, 0, 320, 25), new Rect(0, 591, 320, 640),
                new Rect(0, 24, 320, 592), new Rect(0, 0, 320, 640), new Rect(-1, 0, 320, 24)}) {
            if (SmokeHarness.isContainedInVisibleSystemBar(system, region, display, bars)) throw new AssertionError("Unknown or oversized system region was accepted");
        }
        Rect top = new Rect(0, 0, 320, 24);
        for (int type : new int[] {AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}) {
            if (SmokeHarness.isContainedInVisibleSystemBar(type, top, display, bars)) throw new AssertionError("Non-system overlay was accepted as a bar");
        }
        if (SmokeHarness.isContainedInVisibleSystemBar(system, top, display, Insets.NONE)) throw new AssertionError("Hidden system bars were accepted");
        if (!SmokeHarness.isContainedInVisibleSystemBar(system, new Rect(0, 0, 8, 640), display, Insets.of(8, 0, 0, 0))) {
            throw new AssertionError("Platform side bar was rejected");
        }
        SmokeHarness.StableWindow stability = new SmokeHarness.StableWindow(4);
        if (stability.observe(4, "old", 0) || stability.observe(4, "old", 1000)) throw new AssertionError("Old launch window was reused");
        if (stability.observe(5, "new", 1100) || stability.observe(5, "new", 1699)
                || !stability.observe(5, "new", 1700)) throw new AssertionError("New window stability interval was not enforced");
        if (stability.observe(5, "resizing", 1800) || stability.observe(5, "resizing", 2300)) {
            throw new AssertionError("Changing window geometry was accepted too early");
        }
        stability.reset();
        if (stability.observe(5, "resizing", 2400) || stability.observe(5, "resizing", 2999)
                || !stability.observe(5, "resizing", 3000)) throw new AssertionError("Interrupted window observation kept stale readiness");
        SmokeCapture.verifyWindowParser();
    }

    private void exercise(SmokeHarness.Target target, String state) throws Exception {
        harness = new SmokeHarness(InstrumentationRegistry.getInstrumentation(), InstrumentationRegistry.getArguments(), target);
        JSONObject app = appEntry(target);
        JSONArray states = app.getJSONArray("states");
        JSONObject entry = stateEntry(app, state);
        String capturePrefix = target.appId + "-" + state;
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
            JSONObject identity = harness.verifyTargetIdentity();
            entry.put("installedIdentity", identity).put("installedIdentityVerified", true);
            app.put("actualInstalledIdentityVerified", true);
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

            if (target == SmokeHarness.CALCULATOR_TARGET) {
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
                SmokeCapture.capture(harness, directory, capturePrefix, "before", captures);

                for (String id : new String[] {"btn_2", "btn_plus", "btn_3", "btn_equals"}) {
                    phase = "click_" + id;
                    perform(state, id, AccessibilityNodeInfo.ACTION_CLICK, actionObservations);
                    passed(steps, phase, "Unique visible resource target accepted the accessibility action");
                }

                phase = "calculator_result";
                String actual = harness.awaitResult("5");
                entry.getJSONObject("result").put("actual", actual);
                passed(steps, phase, "Independent read of org.fossify.math:id/result equals 5");
                SmokeCapture.capture(harness, directory, capturePrefix, "result", captures);

            } else {
                phase = "launch_notes";
                actionObservations.put(harness.verifyServiceState(state, "before_launch"));
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.LAUNCH);
                harness.launchTarget();
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.INITIAL_EDITOR);
                harness.awaitNotesResource("text_note_view");
                actionObservations.put(harness.verifyServiceState(state, "after_launch"));
                passed(steps, phase, "PackageManager resolved the installed launcher alias and a real notes editor became visible");
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.INITIAL_CAPTURE);
                SmokeCapture.capture(harness, directory, capturePrefix, "before", captures);

                phase = "create_note";
                actionObservations.put(harness.verifyServiceState(state, "before_create_note"));
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.OPEN_CREATE_DIALOG);
                harness.notesCreateDialog();
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.SET_TITLE);
                harness.notesSetText("locked_note_title", noteTitle(state));
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.SELECT_TEXT_TYPE);
                harness.notesClick("type_text_note");
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.CONFIRM_CREATE);
                harness.notesClick("android:id/button1");
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.CREATED_EDITOR);
                harness.awaitNotesResource("text_note_view");
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.CREATED_TITLE);
                harness.notesAssertTitle(noteTitle(state));
                actionObservations.put(harness.verifyServiceState(state, "after_create_note"));
                passed(steps, phase, "Created the state's uniquely titled plain-text note through the real native menu and dialog");

                phase = "enter_note_body";
                actionObservations.put(harness.verifyServiceState(state, "before_enter_note_body"));
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.SET_BODY);
                harness.notesSetText("text_note_view", noteBody(state));
                actionObservations.put(harness.verifyServiceState(state, "after_enter_note_body"));
                passed(steps, phase, "The visible unprotected editor independently reflects the exact synthetic input");

                phase = "save_note_by_background";
                actionObservations.put(harness.verifyServiceState(state, "before_save_note"));
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.SAVE_BACKGROUND);
                harness.notesBackgroundForSave();
                actionObservations.put(harness.verifyServiceState(state, "after_save_note"));
                passed(steps, phase, "Normal HOME navigation invokes the pinned app's default onPause autosave; no app data was written by the probe");

                phase = "reopen_notes";
                actionObservations.put(harness.verifyServiceState(state, "before_reopen"));
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.REOPEN);
                harness.notesRestartInFreshProcess();
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.REOPENED_EDITOR);
                harness.awaitNotesResource("text_note_view");
                entry.getJSONObject("result").put("reopenedInFreshProcess", true);
                actionObservations.put(harness.verifyServiceState(state, "after_reopen"));
                passed(steps, phase, "The old notes process stopped and the installed launcher opened a different real process");

                phase = "notes_persisted_result";
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.VERIFY_PERSISTED);
                String actual = harness.notesAwaitText("text_note_view", noteBody(state));
                String title = harness.notesAssertTitle(noteTitle(state));
                entry.getJSONObject("result").put("actual", actual).put("titleActual", title).put("persistenceVerified", true);
                passed(steps, phase, "After a fresh process start the visible editor and note title exactly match this state's synthetic note");
                harness.notesDiagnosticStage(SmokeHarness.NotesStage.RESULT_CAPTURE);
                SmokeCapture.capture(harness, directory, capturePrefix, "result", captures);
            }

            phase = "installed_identity_after";
            harness.verifyTargetIdentity();
            passed(steps, phase, "Installed application identity is unchanged after the UI case");

            phase = "service_state_after";
            observations.put(harness.verifyServiceState(state, "after"));
            passed(steps, phase, "The requested service state remains true after the app-specific core workflow and capture");
            entry.put("status", "passed").put("stage", "complete");
        } catch (Throwable error) {
            failure = error;
            if (phase.startsWith("installed_identity")) {
                entry.put("installedIdentityVerified", false);
                app.put("installedIdentityFailureSeen", true);
            }
            if (error instanceof SmokeHarness.ServiceStateFailure) {
                JSONObject observed = ((SmokeHarness.ServiceStateFailure) error).observation;
                String observedStage = observed.optString("stage");
                ("before".equals(observedStage) || "after".equals(observedStage)
                        ? observations : actionObservations).put(observed);
            }
            if (("calculator_result".equals(phase) || "notes_persisted_result".equals(phase)) && error instanceof SmokeHarness.ResultMismatch) {
                entry.getJSONObject("result").put("actual", ((SmokeHarness.ResultMismatch) error).actual);
            }
            entry.put("status", "failed").put("stage", phase).put("error", safeError(error));
            steps.put(new JSONObject().put("name", phase).put("status", "failed").put("details", safeError(error)));
            if (entry.optBoolean("installedIdentityVerified")) {
                SmokeCapture.capture(harness, directory, capturePrefix, "failure", captures);
            } else {
                captures.put(new JSONObject().put("stage", "failure").put("status", "blocked")
                        .put("reason", "installed_identity_not_verified"));
            }
        } finally {
            if (settingsRead) {
                try {
                    harness.restoreServiceSettings(originalServices, originalEnabled);
                    passed(steps, "restore_service_settings", "Original accessibility component list restored and Agent service unbound; system owns the global accessibility flag");
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
            app.put("actualInstalledIdentityVerified", anyIdentityVerified);
            persist();
        }
        if (failure != null) rethrow(failure);
    }

    private void perform(String state, String resource, int action, JSONArray observations) throws Exception {
        observations.put(harness.verifyServiceState(state, "before_" + resource));
        harness.perform(resource, action);
        observations.put(harness.verifyServiceState(state, "after_" + resource));
    }

    private static String noteTitle(String state) { return "Agent OS " + state + " sample"; }
    private static String noteBody(String state) {
        return "Agent OS offline note (" + state + ")\nSynthetic sample: 2 + 3 = 5.\nNo personal data.";
    }

    private static JSONObject emptyApp(SmokeHarness.Target target) throws Exception {
        return new JSONObject().put("appId", target.appId).put("package", target.packageName)
                .put("actualInstalledIdentityVerified", false).put("installedIdentityFailureSeen", false)
                .put("states", new JSONArray().put(emptyState(target, "off")).put(emptyState(target, "idle")));
    }

    private static JSONObject emptyState(SmokeHarness.Target target, String state) throws Exception {
        JSONObject result = target == SmokeHarness.CALCULATOR_TARGET
                ? new JSONObject().put("expected", "5").put("actual", JSONObject.NULL).put("resourceId", target.packageName + ":id/result")
                : new JSONObject().put("expected", noteBody(state)).put("actual", JSONObject.NULL)
                        .put("resourceId", target.packageName + ":id/text_note_view")
                        .put("titleExpected", noteTitle(state)).put("titleActual", JSONObject.NULL)
                        .put("persistenceVerified", false).put("reopenedInFreshProcess", false);
        return new JSONObject().put("state", state).put("status", "unverified").put("stage", "not_run")
                .put("cause", "test_has_not_executed").put("installedIdentityVerified", false)
                .put("serviceState", "unknown").put("serviceObservations", new JSONArray())
                .put("serviceActionObservations", new JSONArray()).put("steps", new JSONArray())
                .put("captures", new JSONArray()).put("result", result);
    }

    private static JSONObject appEntry(SmokeHarness.Target target) throws Exception {
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.getJSONObject(i);
            if (target.appId.equals(app.getString("appId"))) return app;
        }
        throw new IllegalArgumentException("Unknown frozen application");
    }

    private static JSONObject stateEntry(JSONObject app, String state) throws Exception {
        JSONArray states = app.getJSONArray("states");
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
