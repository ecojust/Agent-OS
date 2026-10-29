package dev.agentos.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy.Mode;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Actual remote UI conditions must still reject navigation after its confirmation is exempted. */
@RunWith(AndroidJUnit4.class)
public final class NavigationObservationGuardInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void aSensitiveScreenCannotScrollLeaveOrRelaunchWithoutConfirmation() throws Exception {
        verifyRejectedPage("sensitive", "PERMISSION_DENIED");
    }

    @Test public void anIncompleteObservationCannotScrollLeaveOrRelaunchWithoutConfirmation() throws Exception {
        verifyRejectedPage("truncated", "CAPABILITY_UNAVAILABLE");
    }

    @Test public void aKnownHighImpactScreenCannotScrollLeaveOrRelaunchWithoutConfirmation() throws Exception {
        verifyRejectedPage("high-impact", "CAPABILITY_UNAVAILABLE");
    }

    private void verifyRejectedPage(String scenario, String expectedCode) throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server, Mode.CONTINUOUS_NAVIGATION);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            device.context.startActivity(new Intent().setComponent(new ComponentName(FIXTURE, FIXTURE + ".NavigationGuardActivity"))
                    .putExtra("scenario", scenario).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            device.awaitUiIdle();
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = guardedTools(device, settings, approvals);
            String marker = "Navigation guard: " + scenario;
            for (String tool : new String[]{"scroll", "home", "launch_app"}) {
                verifyRejectedTool(device, tools, approvals, scenario, marker, tool, expectedCode);
            }
            assertNoDispatch(trace);
            assertEquals(0, server.requestCount());
            screenshots.capture("continuous-navigation-rejects-" + scenario + "-observation");
        }
    }

    private static void verifyRejectedTool(DeviceHarness device, AndroidDeviceTools tools, AtomicInteger approvals,
                                           String scenario, String marker, String tool, String expectedCode) throws Exception {
        long epoch = device.control.getExecutionEpoch();
        // At most three actual plans. Only an explicitly unsubmitted stale plan
        // permits another real observation; stale alone never passes this test.
        for (int attempt = 0; attempt < 3; attempt++) {
            device.awaitUiIdle();
            device.control.checkExecutionEpoch(epoch);
            JsonObject before = awaitObservation(tools, device.control,
                    data -> matchesRiskObservation(data, scenario, marker));
            assertTrue(before.get("scoped").getAsBoolean());
            assertFalse("The scenario must exercise observation content, not an unrelated overlay", before.get("hasOcclusion").getAsBoolean());
            assertEquals("Only the deliberately oversized field may truncate this fixture", "truncated".equals(scenario),
                    before.get("truncated").getAsBoolean());
            JsonObject scroll = node(before, "fixture_scroll");
            assertTrue("The rejected tool must have a real scrollable target", scroll.get("scrollable").getAsBoolean());
            assertTrue(scroll.get("enabled").getAsBoolean());
            if ("sensitive".equals(scenario)) {
                assertTrue(node(before, "password_field").get("password").getAsBoolean());
                assertFalse(before.toString().contains("synthetic-navigation-secret"));
                assertFalse(before.toString().contains("synthetic-private-navigation-description"));
            } else if ("high-impact".equals(scenario)) {
                assertTrue(containsText(before, "Payment fixture — offline, no account or payment service"));
            }
            assertEquals(marker, actualFixtureText(device, "navigation_case_marker"));
            assertEquals("Scroll changes: 0", actualFixtureText(device, "navigation_scroll_count"));
            JsonObject args = new JsonObject();
            if ("launch_app".equals(tool)) args.addProperty("packageName", FIXTURE);
            else {
                args.addProperty("expectedRevision", string(before, "revision"));
                if ("scroll".equals(tool)) {
                    args.addProperty("nodeId", string(scroll, "id"));
                    args.addProperty("direction", "forward");
                }
            }
            JsonObject result = tools.execute(tool, args, device.control, epoch);
            assertFalseFlag(result, "ok");
            assertFalseFlag(result, "submitted");
            assertFalseFlag(result, "dispatchSubmitted");
            assertFalseFlag(result, "dispatchAccepted");
            assertEquals(0, approvals.get());
            assertEquals(0, device.control.getDispatchAttempts());
            device.control.checkExecutionEpoch(epoch);
            // Read independent platform nodes, not the tool result or its cached observation.
            assertEquals(marker, actualFixtureText(device, "navigation_case_marker"));
            assertEquals("Scroll changes: 0", actualFixtureText(device, "navigation_scroll_count"));
            if ("STALE_OBSERVATION".equals(string(result, "code"))) {
                assertTrue("The expected risk guard was not reached within three fresh plans: " + result, attempt < 2);
                continue;
            }
            assertEquals(tool + " unexpectedly bypassed the observation guard: " + result, expectedCode, string(result, "code"));
            String expectedError = "sensitive".equals(scenario) ? "当前页面包含敏感字段，请由用户操作"
                    : "truncated".equals(scenario) ? "当前观察不完整，无法连续导航，请由用户检查界面"
                    : "当前页面涉及已知高影响操作，请由用户操作";
            assertEquals("An unrelated failure cannot stand in for the intended risk guard", expectedError, string(result, "error"));
            return;
        }
        throw new AssertionError("The expected risk guard was never reached");
    }

    private static boolean matchesRiskObservation(JsonObject data, String scenario, String marker) {
        if (!FIXTURE.equals(string(data, "packageName")) || !containsText(data, marker)
                || !data.get("scoped").getAsBoolean() || data.get("hasOcclusion").getAsBoolean()
                || data.get("truncated").getAsBoolean() != "truncated".equals(scenario)) return false;
        if ("high-impact".equals(scenario)) return containsText(data, "Payment fixture — offline, no account or payment service");
        if ("sensitive".equals(scenario)) {
            for (JsonElement item : data.getAsJsonArray("nodes")) {
                JsonObject entry = item.getAsJsonObject();
                if ((FIXTURE + ":id/password_field").equals(string(entry, "viewId"))
                        && entry.get("password").getAsBoolean()) return true;
            }
            return false;
        }
        // The long field itself must be observed, not merely an unrelated time-budget truncation.
        for (JsonElement item : data.getAsJsonArray("nodes")) {
            JsonObject entry = item.getAsJsonObject();
            if (string(entry, "text").startsWith("Long fixture field:")
                    && entry.get("contentTruncated").getAsBoolean()) return true;
        }
        return false;
    }

    @Test public void anUnscopedSettingsWindowCannotBeReplacedByAnAutomaticLaunch() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server, Mode.CONTINUOUS_NAVIGATION);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AtomicInteger approvals = new AtomicInteger();
            NavigationDiagnostic diagnostic = new NavigationDiagnostic();
            try {
                AndroidDeviceTools tools = guardedTools(device, settings, approvals);
                resolveSettingsOrigin(device, diagnostic);
                diagnostic.phase("settings_launch");
                // Keep the actual implicit launch. Resolution alone is not proof
                // that this package subsequently owns the focused window.
                device.context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                device.awaitUiIdle();
                diagnostic.phase("settings_observation");
                JsonObject before = awaitSettingsObservation(tools, device, diagnostic);
                assertFalse(before.get("scoped").getAsBoolean());
                assertEquals(0, before.getAsJsonArray("nodes").size());
                assertFalse("The focused Settings page itself must be rejected, without another overlay", before.get("hasOcclusion").getAsBoolean());
                FocusedOrigin focused = focusedOrigin(device, diagnostic);
                diagnostic.values.add("beforeFocus", focused.metadata());
                assertEquals("The independent focused application must be the resolved system Settings", "SETTINGS", focused.classification);
                assertEquals(before.get("windowId").getAsInt(), focused.windowId);
                assertEquals(focused.windowId, focused.rootWindowId);
                JsonObject args = new JsonObject(); args.addProperty("packageName", FIXTURE);
                diagnostic.phase("launch_app");
                diagnostic.values.addProperty("epochBefore", device.control.getExecutionEpoch());
                diagnostic.traceStart = trace.events().size();
                diagnostic.values.addProperty("outcome", "ENTERED");
                try {
                    JsonObject returned = tools.execute("launch_app", args, device.control);
                    diagnostic.values.addProperty("outcome", "RETURNED");
                    diagnostic.recordResult("return", returned);
                    // A normal stale/unsupported/unsubmitted return still fails:
                    // only the original TASK_PAUSED exception satisfies this case.
                    fail("Automatic launch returned without the required source interruption");
                } catch (TaskControlException paused) {
                    diagnostic.values.addProperty("outcome", "CONTROL_EXCEPTION");
                    diagnostic.values.addProperty("exceptionCode", fixedCode(paused.getCode()));
                    assertEquals("TASK_PAUSED", paused.getCode());
                }
                diagnostic.phase("post_launch_assertions");
                assertTrue(device.control.isPaused());
                assertEquals(0, approvals.get());
                assertEquals(0, device.control.getDispatchAttempts());
                FocusedOrigin after = focusedOrigin(device, diagnostic);
                diagnostic.values.add("afterFocus", after.metadata());
                assertEquals("The original Settings package must retain focus", focused.classification, after.classification);
                assertNoDispatch(trace);
                assertEquals(0, server.requestCount());
                screenshots.capture("continuous-navigation-rejects-unscoped-focused-settings");
            } catch (Exception | AssertionError failure) {
                // This catch runs inside the resources' lifetime, before DeviceHarness
                // cancels the control or removes its windows. Keep the original failure.
                String snapshot = diagnostic.attach(failure, device, server, approvals, trace);
                if (failure instanceof AssertionError) {
                    // AndroidJUnitRunner trims suppressed diagnostics from this assertion's
                    // report. Reuse the same bounded sample in its top-level message.
                    throw new AssertionError(failure.getMessage() + "\nnavigationDiagnostic=" + snapshot, failure);
                }
                throw failure;
            }
        }
    }

    @Test public void aKnownUnscopedSettingsOriginStillPausesAfterControlWindowRecreation() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server, Mode.CONTINUOUS_NAVIGATION);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AtomicInteger approvals = new AtomicInteger();
            NavigationDiagnostic diagnostic = new NavigationDiagnostic();
            diagnostic.values.addProperty("recreationVerified", false);
            try {
                AndroidDeviceTools tools = guardedTools(device, settings, approvals);
                resolveSettingsOrigin(device, diagnostic);
                diagnostic.phase("settings_launch");
                device.context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                device.awaitUiIdle();
                diagnostic.phase("settings_observation");
                JsonObject before = awaitSettingsObservation(tools, device, diagnostic);
                assertFalse(before.get("scoped").getAsBoolean());
                assertEquals(0, before.getAsJsonArray("nodes").size());
                assertFalse("Settings itself must be unscoped without another overlay", before.get("hasOcclusion").getAsBoolean());
                FocusedOrigin focused = focusedOrigin(device, diagnostic);
                diagnostic.values.add("beforeFocus", focused.metadata());
                assertEquals("SETTINGS", focused.classification);
                assertEquals(before.get("windowId").getAsInt(), focused.windowId);
                assertEquals(focused.windowId, focused.rootWindowId);

                RunControl owner = device.control;
                long epoch = owner.getExecutionEpoch();
                String profile = owner.getProfileId();
                SettingsStore.ConfigurationLease configuration = settings.lease;
                assertEquals(profile, string(before, "profileId"));
                assertTrue(SettingsStore.isCurrent(configuration));
                AgentAccessibilityService service = device.service;
                diagnostic.phase("original_control_readiness");
                AgentAccessibilityService.OwnedControlWindowIdentity originalIdentity =
                        awaitRealControlWindow(service.acquireOwnedControlWindowIdentity());
                AgentAccessibilityService.ControlLease originalLease = awaitRealControlWindow(service.acquireControlLease());
                assertTrue(service.isOwnedControlWindowCurrent(originalIdentity));
                assertTrue(service.isControlLeaseCurrent(originalLease));
                assertTrue(originalLease.matchesOriginalAttachment(originalIdentity));

                diagnostic.phase("control_recreation");
                // Real removal and addView produce the replacement registration. No
                // private identity, epoch, callback or platform metadata is fabricated.
                device.instrumentation.runOnMainSync(() -> {
                    service.hideOverlay();
                    assertNull(service.getOwnedControlWindowIdentity());
                    assertFalse(service.isOwnedControlWindowCurrent(originalIdentity));
                    assertFalse(service.isControlLeaseCurrent(originalLease));
                    service.showOverlay("Integration test", false, "");
                    // hideOverlay clears package monitoring; restore this adapter's
                    // original authorized set without replacing its observed baseline.
                    service.watchPackages(Collections.singleton(FIXTURE));
                });
                diagnostic.phase("recreated_control_readiness");
                AgentAccessibilityService.OwnedControlWindowIdentity recreatedIdentity =
                        awaitRealControlWindow(service.acquireOwnedControlWindowIdentity());
                AgentAccessibilityService.ControlLease recreatedLease = awaitRealControlWindow(service.acquireControlLease());
                assertNotSame(originalIdentity, recreatedIdentity);
                assertFalse(service.isOwnedControlWindowCurrent(originalIdentity));
                assertFalse(service.isControlLeaseCurrent(originalLease));
                assertTrue(service.isOwnedControlWindowCurrent(recreatedIdentity));
                assertTrue(service.isControlLeaseCurrent(recreatedLease));
                assertTrue(recreatedLease.matchesOriginalAttachment(recreatedIdentity));
                assertFalse("A real new attachment must not match the old observation's controls",
                        recreatedLease.matchesOriginalAttachment(originalIdentity));
                assertSame(owner, device.control);
                assertEquals(profile, owner.getProfileId());
                assertEquals(profile, SettingsStore.currentProfileId(device.context));
                assertSame(configuration, settings.lease);
                assertTrue(SettingsStore.isCurrent(configuration));
                assertEquals(epoch, owner.getExecutionEpoch());
                assertFalse(owner.isPaused());
                assertFalse(owner.isCancelled());
                assertEquals(0, approvals.get());
                assertEquals(0, owner.getDispatchAttempts());
                assertEquals(0, server.requestCount());
                FocusedOrigin ready = focusedOrigin(device, diagnostic);
                diagnostic.values.add("recreatedFocus", ready.metadata());
                assertEquals("Settings must retain the actual input focus after control recreation", "SETTINGS", ready.classification);
                assertEquals(focused.windowId, ready.windowId);
                assertEquals(ready.windowId, ready.rootWindowId);
                diagnostic.values.addProperty("recreationVerified", true);

                // Do not observe again: this single call must retain the successful
                // Settings observation and its now-revoked control attachment.
                JsonObject args = new JsonObject(); args.addProperty("packageName", FIXTURE);
                diagnostic.phase("launch_app_after_recreation");
                diagnostic.values.addProperty("epochBefore", epoch);
                diagnostic.traceStart = trace.events().size();
                diagnostic.values.addProperty("outcome", "ENTERED");
                try {
                    JsonObject returned = tools.execute("launch_app", args, owner, epoch);
                    diagnostic.values.addProperty("outcome", "RETURNED");
                    diagnostic.recordResult("return", returned);
                    fail("Known unscoped Settings origin returned without the required source interruption after control recreation");
                } catch (TaskControlException paused) {
                    diagnostic.values.addProperty("outcome", "CONTROL_EXCEPTION");
                    diagnostic.values.addProperty("exceptionCode", fixedCode(paused.getCode()));
                    assertEquals("TASK_PAUSED", paused.getCode());
                }
                diagnostic.phase("post_launch_assertions");
                assertTrue(owner.isPaused());
                assertFalse(owner.isCancelled());
                assertEquals(0, approvals.get());
                assertEquals(0, owner.getDispatchAttempts());
                assertNoDispatch(trace);
                assertEquals(0, server.requestCount());
                FocusedOrigin after = focusedOrigin(device, diagnostic);
                diagnostic.values.add("afterFocus", after.metadata());
                assertEquals("The original Settings package must retain focus", "SETTINGS", after.classification);
                screenshots.capture("known-unscoped-origin-pauses-after-real-control-recreation");
            } catch (Exception | AssertionError failure) {
                String snapshot = diagnostic.attach(failure, device, server, approvals, trace);
                if (failure instanceof AssertionError) {
                    throw new AssertionError(failure.getMessage() + "\nnavigationDiagnostic=" + snapshot, failure);
                }
                throw failure;
            }
        }
    }

    private static <T> T awaitRealControlWindow(CompletableFuture<T> future) throws Exception {
        try { return future.get(5, TimeUnit.SECONDS); }
        finally { future.cancel(false); }
    }

    private static void resolveSettingsOrigin(DeviceHarness device, NavigationDiagnostic diagnostic) {
        PackageManager manager = device.context.getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo home = manager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        diagnostic.homePackage = resolvedPackage(manager, homeIntent, home,
                "Default HOME must resolve to one enabled exported activity");
        assertFalse("Default HOME must not be a resolver or a test application",
                "android".equals(diagnostic.homePackage) || FIXTURE.equals(diagnostic.homePackage)
                        || device.context.getPackageName().equals(diagnostic.homePackage));
        Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
        ResolveInfo settings = manager.resolveActivity(settingsIntent, PackageManager.MATCH_DEFAULT_ONLY);
        // Ordinary target-context visibility only. A missing resolution is a setup
        // failure, never grounds to add privileges or guess com.android.settings.
        diagnostic.settingsPackage = resolvedPackage(manager, settingsIntent, settings,
                "System Settings must be visible and resolve to an enabled exported activity");
        assertTrue("The resolved Settings application must have system installation identity",
                (settings.activityInfo.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
        assertFalse("Settings must not resolve to HOME, a resolver or either test application",
                diagnostic.settingsPackage.equals(diagnostic.homePackage) || "android".equals(diagnostic.settingsPackage)
                        || FIXTURE.equals(diagnostic.settingsPackage) || device.context.getPackageName().equals(diagnostic.settingsPackage));
        diagnostic.values.addProperty("resolvedSystemSettings", true);
    }

    private static String resolvedPackage(PackageManager manager, Intent intent, ResolveInfo resolved, String failure) {
        assertTrue(failure, resolved != null && resolved.activityInfo != null && resolved.activityInfo.applicationInfo != null
                && resolved.activityInfo.enabled && resolved.activityInfo.exported && resolved.activityInfo.applicationInfo.enabled
                && resolved.activityInfo.name != null && !resolved.activityInfo.name.isEmpty()
                && resolved.activityInfo.packageName != null && !resolved.activityInfo.packageName.isEmpty());
        List<ResolveInfo> candidates = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        assertTrue("Intent resolution requires a bounded visible candidate list", candidates != null && candidates.size() <= 32);
        int matches = 0;
        for (ResolveInfo candidate : candidates) {
            if (candidate.activityInfo != null
                    && resolved.activityInfo.packageName.equals(candidate.activityInfo.packageName)
                    && resolved.activityInfo.name.equals(candidate.activityInfo.name)) matches++;
        }
        // A synthetic resolver (including an OEM-configured one) is not a real
        // candidate for this intent. Multiple candidates with a real default are fine.
        assertEquals("The resolved component must match exactly one actual intent candidate", 1, matches);
        return resolved.activityInfo.packageName;
    }

    /** Same eight-second, two-equal-revision read preparation, without content dumps on timeout. */
    private static JsonObject awaitSettingsObservation(AndroidDeviceTools tools, DeviceHarness device,
                                                       NavigationDiagnostic diagnostic) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        String previousRevision = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            JsonObject result = tools.execute("observe", new JsonObject(), device.control);
            diagnostic.recordResult("observe", result);
            if (result != null && "TRUE".equals(booleanField(result, "ok"))
                    && result.has("data") && result.get("data").isJsonObject()) {
                JsonObject data = result.getAsJsonObject("data");
                String revision = string(data, "revision");
                diagnostic.values.addProperty("observedOrigin", diagnostic.classify(string(data, "packageName"), device));
                if (!revision.isEmpty() && revision.equals(previousRevision)
                        && diagnostic.settingsPackage.equals(string(data, "packageName"))
                        && "TRUE".equals(booleanField(data, "focused")) && SystemClock.elapsedRealtime() < deadline) return data;
                previousRevision = revision;
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0) SystemClock.sleep(Math.min(100, remaining));
        }
        throw new AssertionError("No stable observation of the precisely resolved Settings application within eight seconds");
    }

    private static final class FocusedOrigin {
        String classification = "NO_FOCUS";
        int count, windowId = -1, rootWindowId = -1, type = -1;
        JsonObject metadata() {
            JsonObject value = new JsonObject(); value.addProperty("class", classification);
            value.addProperty("focusedCount", count); value.addProperty("windowId", windowId);
            value.addProperty("rootWindowId", rootWindowId); value.addProperty("type", type); return value;
        }
    }

    /** One bounded window-list read and at most one root; no titles, text, children or cache writes. */
    private static FocusedOrigin focusedOrigin(DeviceHarness device, NavigationDiagnostic diagnostic) {
        FocusedOrigin result = new FocusedOrigin();
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        if (windows == null) { result.classification = "UNAVAILABLE"; return result; }
        try {
            if (windows.size() > 32) { result.classification = "WINDOW_LIMIT"; return result; }
            AccessibilityWindowInfo focused = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) { result.count++; focused = window; }
            if (result.count == 0) return result;
            if (result.count != 1) { result.classification = "AMBIGUOUS"; return result; }
            result.windowId = focused.getId(); result.type = focused.getType();
            if (result.type != AccessibilityWindowInfo.TYPE_APPLICATION) { result.classification = "NON_APPLICATION"; return result; }
            AccessibilityNodeInfo root = focused.getRoot();
            if (root == null) { result.classification = "NO_ROOT"; return result; }
            try {
                result.rootWindowId = root.getWindowId();
                result.classification = root.getPackageName() == null ? "NO_PACKAGE"
                        : diagnostic.classify(root.getPackageName().toString(), device);
                return result;
            } finally { root.recycle(); }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static String booleanField(JsonObject object, String field) {
        if (object == null || !object.has(field)) return "MISSING";
        JsonElement value = object.get(field);
        if (value.isJsonNull()) return "NULL";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return "INVALID";
        return value.getAsBoolean() ? "TRUE" : "FALSE";
    }

    private static String fixedCode(String code) {
        if (code == null) return "NULL";
        switch (code) {
            case "TASK_PAUSED": case "TASK_STOPPED": case "STALE_OBSERVATION": case "CAPABILITY_UNAVAILABLE":
            case "PERMISSION_DENIED": case "USER_DENIED": case "INVALID_ARGUMENT": case "OUTCOME_UNKNOWN": case "TIMEOUT":
            case "CONFIGURATION_CHANGED": case "BUDGET_EXCEEDED": return code;
            default: return "OTHER";
        }
    }

    private static final class NavigationDiagnostic {
        final JsonObject values = new JsonObject();
        String settingsPackage, homePackage;
        int traceStart = -1;
        NavigationDiagnostic() {
            phase("resolve_origin"); values.addProperty("outcome", "NOT_CALLED");
            values.addProperty("resolvedSystemSettings", false);
        }
        void phase(String phase) { values.addProperty("phase", phase); }
        String classify(String name, DeviceHarness device) {
            if (name == null || name.isEmpty()) return "NO_PACKAGE";
            if (name.equals(settingsPackage)) return "SETTINGS";
            if (name.equals(homePackage)) return "HOME";
            if (FIXTURE.equals(name)) return "FIXTURE";
            if (device.context.getPackageName().equals(name)) return "AGENT";
            return "OTHER";
        }
        void recordResult(String key, JsonObject result) {
            try {
                JsonObject metadata = new JsonObject();
                String code = "MISSING";
                if (result != null && result.has("code")) {
                    JsonElement value = result.get("code");
                    code = value.isJsonNull() ? "NULL" : value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                            ? fixedCode(value.getAsString()) : "INVALID";
                }
                metadata.addProperty("code", code);
                for (String flag : new String[]{"ok", "submitted", "dispatchSubmitted", "dispatchAccepted", "accepted"})
                    metadata.addProperty(flag, booleanField(result, flag));
                values.add(key, metadata);
            } catch (RuntimeException | AssertionError sampling) { values.addProperty("resultSampling", samplingClass(sampling)); }
        }
        String attach(Throwable failure, DeviceHarness device, LocalControllerModelServer server, AtomicInteger approvals,
                      PerformanceTrace.Session trace) {
            try {
                if ("ENTERED".equals(string(values, "outcome"))) values.addProperty("outcome", "THREW_OTHER");
                synchronized (device.control) {
                    values.addProperty("epochAfter", device.control.getExecutionEpoch());
                    values.addProperty("paused", device.control.isPaused());
                    values.addProperty("cancelled", device.control.isCancelled());
                    values.addProperty("inFlight", device.control.hasInFlightDispatch());
                    values.addProperty("dispatchAttempts", device.control.getDispatchAttempts());
                }
                values.addProperty("approvals", approvals.get()); values.addProperty("httpRequests", server.requestCount());
                values.addProperty("traceOverwritten", trace.overwrittenCount());
                List<PerformanceTrace.Event> events = trace.events();
                if (trace.overwrittenCount() == 0) {
                    JsonObject counts = new JsonObject();
                    int finals = 0, completed = 0, local = 0, owned = 0, revision = 0, dispatch = 0;
                    for (int index = Math.max(0, traceStart); index < events.size(); index++) {
                        PerformanceTrace.Event event = events.get(index);
                        switch (event.stage) {
                            case SNAPSHOT_FINAL_LAUNCH: finals++; if (event.nodeCount >= 0) completed++; break;
                            case LOCAL_CONTROL_STALE: local++; break;
                            case OWNED_WINDOW_MISMATCH: owned++; break;
                            case OBSERVATION_REVISION_MISMATCH: revision++; break;
                            case DEVICE_DISPATCH_ATTEMPT: dispatch++; break;
                            default: break;
                        }
                    }
                    // Failed snapshot construction also emits an event with nodeCount=-1.
                    // No event does not prove a stage never began; stale can be unmarked.
                    counts.addProperty("scope", traceStart < 0 ? "PREPARATION" : "CALL_TO_FAILURE");
                    counts.addProperty("finalEvents", finals); counts.addProperty("finalCompleted", completed);
                    counts.addProperty("localStale", local); counts.addProperty("ownedMismatch", owned);
                    counts.addProperty("revisionMismatch", revision); counts.addProperty("dispatchEvents", dispatch);
                    values.add("trace", counts);
                }
                try { values.add("failureFocusBeforeCleanup", focusedOrigin(device, this).metadata()); }
                catch (RuntimeException | AssertionError sampling) { values.addProperty("focusSampling", samplingClass(sampling)); }
                String snapshot = values.toString();
                String boundedSnapshot = snapshot.length() < 2000 ? snapshot : "{\"diagnostic\":\"SIZE_LIMIT\"}";
                failure.addSuppressed(new NavigationSnapshot(boundedSnapshot));
                return boundedSnapshot;
            } catch (RuntimeException | AssertionError sampling) {
                String snapshot = "{\"diagnostic\":\"UNAVAILABLE\"}";
                try { failure.addSuppressed(new NavigationSnapshot(snapshot)); }
                catch (RuntimeException | AssertionError ignored) { /* Never replace the original failure. */ }
                return snapshot;
            }
        }
    }

    private static final class NavigationSnapshot extends Exception {
        NavigationSnapshot(String message) { super(message, null, false, false); }
    }

    private static String samplingClass(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        return name.substring(0, Math.min(80, name.length()));
    }

    private static AndroidDeviceTools guardedTools(DeviceHarness device, ControllerTestSettings settings,
                                                   AtomicInteger approvals) throws Exception {
        return new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                Mode.CONTINUOUS_NAVIGATION, settings.lease, (description, control, epoch) -> {
                    approvals.incrementAndGet(); fail("A rejected automatic navigation must not fall back to generic approval"); return false;
                });
    }

    private static boolean containsText(JsonObject data, String text) {
        for (JsonElement item : data.getAsJsonArray("nodes")) {
            if (text.equals(string(item.getAsJsonObject(), "text"))) return true;
        }
        return false;
    }

    private static void assertFalseFlag(JsonObject result, String name) {
        assertTrue(result.toString(), result.has(name) && result.get(name).isJsonPrimitive()
                && result.getAsJsonPrimitive(name).isBoolean());
        assertFalse(result.toString(), result.get(name).getAsBoolean());
    }

    private static void assertNoDispatch(PerformanceTrace.Session trace) {
        assertEquals("Lost trace events cannot establish absence of dispatch", 0, trace.overwrittenCount());
        for (PerformanceTrace.Event event : trace.events()) assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
    }

    private static String actualFixtureText(DeviceHarness device, String id) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (!FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/" + id);
                    try {
                        if (found.size() == 1 && found.get(0).refresh() && found.get(0).getText() != null) return found.get(0).getText().toString();
                    } finally { for (AccessibilityNodeInfo node : found) node.recycle(); }
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("Independent fixture evidence is unavailable for " + id);
    }

}
