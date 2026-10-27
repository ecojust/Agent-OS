package dev.agentos.app;

import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy.Mode;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
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
            AndroidDeviceTools tools = guardedTools(device, settings, approvals);
            device.context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            device.awaitUiIdle();
            JsonObject before = awaitObservation(tools, device.control, data -> {
                String app = string(data, "packageName");
                return !app.isEmpty() && !FIXTURE.equals(app) && !device.context.getPackageName().equals(app);
            });
            assertFalse(before.get("scoped").getAsBoolean());
            assertEquals(0, before.getAsJsonArray("nodes").size());
            assertFalse("The focused Settings page itself must be rejected, without another overlay", before.get("hasOcclusion").getAsBoolean());
            String focusedPackage = actualFocusedPackage(device);
            assertEquals(string(before, "packageName"), focusedPackage);
            JsonObject args = new JsonObject(); args.addProperty("packageName", FIXTURE);
            try { tools.execute("launch_app", args, device.control); fail("Automatic launch replaced an unknown focused window"); }
            catch (TaskControlException paused) { assertEquals("TASK_PAUSED", paused.getCode()); }
            assertTrue(device.control.isPaused());
            assertEquals(0, approvals.get());
            assertEquals(0, device.control.getDispatchAttempts());
            assertEquals(focusedPackage, actualFocusedPackage(device));
            assertNoDispatch(trace);
            assertEquals(0, server.requestCount());
            screenshots.capture("continuous-navigation-rejects-unscoped-focused-settings");
        }
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

    private static String actualFocusedPackage(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try { return root.getPackageName() == null ? "" : root.getPackageName().toString(); }
                finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("No real focused window remains");
    }
}
