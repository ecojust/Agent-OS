package dev.agentos.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Screen coordinates select a verified node for a later confirmed action; they never dispatch input. */
@RunWith(AndroidJUnit4.class)
public final class ResolveNodeAtInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void uniqueActionCandidatesAreReadOnlyAndASelectedClickStillRequiresConfirmation() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = tools(device, approvals);
            assertTrue(tools.availableTools().contains("resolve_node_at"));
            assertFalse(tools.availableTools().contains("tap"));
            assertFalse(tools.availableTools().contains("swipe"));

            JsonObject screen = observed(device, tools);
            // Some platform versions expose an empty editor's hint as node text.
            // Compare independent before/after evidence instead of assuming "".
            String initialEditorText = actualText(device, "edit_value");
            JsonObject scroll = node(screen, "fixture_scroll");
            Rect scrollBounds = bounds(scroll);
            Rect controls = new Rect();
            device.instrumentation.runOnMainSync(() -> controls.set(device.service.controlBounds()));
            assertTrue("The ordinary full-window scroll container must cross the task controls", Rect.intersects(scrollBounds, controls));
            assertFalse("The scroll query point must be outside task controls", controls.contains(scrollBounds.centerX(), scrollBounds.centerY()));
            assertTrue(scroll.get("scrollable").getAsBoolean());
            resolvedNode(device, tools, screen, "fixture_scroll", "scroll_forward");
            assertNoDispatch(device, approvals);
            resolvedNode(device, tools, screen, "edit_value", "type_text");
            // This is the ordinary fixture, whose button has a scrollable ancestor.
            // An ancestor that cannot click must not make the click ambiguous.
            assertTrue(node(screen, "fixture_scroll").get("scrollable").getAsBoolean());
            assertFalse(node(screen, "fixture_scroll").get("clickable").getAsBoolean());
            JsonObject selected = resolvedNode(device, tools, screen, "increment", "click");
            assertNoDispatch(device, approvals);
            assertEquals("Count: 0", actualText(device, "counter_value"));
            assertEquals("Read-only resolution must not change editor text", initialEditorText, actualText(device, "edit_value"));
            JsonObject unchanged = observed(device, tools);
            assertEquals("Resolving candidates must not scroll or otherwise change the fixture",
                    string(screen, "revision"), string(unchanged, "revision"));

            JsonObject action = new JsonObject();
            action.addProperty("nodeId", string(selected, "nodeId"));
            action.addProperty("expectedRevision", string(selected, "revision"));
            JsonObject clicked = tools.execute("click", action, device.control);
            assertTrue(clicked.toString(), clicked.get("ok").getAsBoolean());
            assertEquals("Resolution must not replace the ordinary action's confirmation", 1, approvals.get());
            assertEquals(1, device.control.getDispatchAttempts());
            awaitObservation(tools, device.control, data -> "Count: 1".equals(string(node(data, "counter_value"), "text")));
            assertEquals("Count: 1", actualText(device, "counter_value"));
            screenshots.capture("resolved-node-click-confirmed-once");
        }
    }

    @Test public void aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = tools(device, approvals);
            JsonObject previous = observed(device, tools);
            JsonObject oldPoint = request(previous, bounds(node(previous, "increment")), "click");
            device.launch("SecondActivity");
            JsonObject stale = tools.execute("resolve_node_at", oldPoint, device.control);
            rejected(stale, "STALE_OBSERVATION");
            assertNoDispatch(device, approvals);
            assertEquals("Second page reached", actualText(device, "second_marker"));
            screenshots.capture("old-window-coordinate-plan-rejected");
        }
    }

    @Test public void passwordRegionsAndTaskControlIntersectionsNeverResolveToActionableTargets() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = tools(device, approvals);
            device.launch("PasswordActivity");
            JsonObject passwordScreen = observed(device, tools);
            assertTrue(node(passwordScreen, "password_field").get("password").getAsBoolean());
            JsonObject secret = tools.execute("resolve_node_at",
                    request(passwordScreen, bounds(node(passwordScreen, "password_field")), "type_text"), device.control);
            rejected(secret, "CAPABILITY_UNAVAILABLE");
            assertEquals("BLOCKED_TARGET", string(secret, "reasonCode"));
            assertFalse(secret.toString().contains("fixture-private-credential"));
            assertFalse(secret.toString().contains("fixture-private-description"));
            assertNoDispatch(device, approvals);
            assertEquals("Password writes: 0", actualText(device, "password_changes"));
            screenshots.capture("password-coordinate-resolution-denied");

            device.launch("CoveredActivity");
            JsonObject coveredScreen = observed(device, tools);
            Rect covered = bounds(node(coveredScreen, "increment"));
            Rect controls = new Rect();
            device.instrumentation.runOnMainSync(() -> controls.set(device.service.controlBounds()));
            assertTrue("The fixture must really intersect task controls", covered.intersect(controls));
            JsonObject blocked = tools.execute("resolve_node_at", request(coveredScreen, covered, "click"), device.control);
            rejected(blocked, "CAPABILITY_UNAVAILABLE");
            assertEquals("BLOCKED_TARGET", string(blocked, "reasonCode"));
            assertNoDispatch(device, approvals);
            assertEquals("Count: 0", actualText(device, "counter_value"));
            screenshots.capture("control-overlap-coordinate-resolution-denied");
        }
    }

    @Test public void aRealUnknownOverlayPreventsCoordinateResolutionWithoutDispatchOrApproval() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = tools(device, approvals);
            Rect target = bounds(node(observed(device, tools), "increment"));
            WindowManager manager = (WindowManager) device.service.getSystemService(Context.WINDOW_SERVICE);
            assertNotNull(manager);
            TextView blocker = new TextView(device.service);
            device.instrumentation.runOnMainSync(() -> {
                blocker.setText("Resolver obstruction fixture");
                blocker.setBackgroundColor(Color.rgb(80, 30, 30));
                blocker.setClickable(true);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(target.width(), target.height(),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = target.left; params.y = target.top;
                params.setTitle("Coordinate resolver obstruction");
                manager.addView(blocker, params);
            });
            try {
                device.awaitUiIdle();
                JsonObject obscured = awaitObservation(tools, device.control, data -> data.get("hasOcclusion").getAsBoolean()
                        && node(data, "increment").get("obscured").getAsBoolean());
                JsonObject rejected = tools.execute("resolve_node_at",
                        request(obscured, bounds(node(obscured, "increment")), "click"), device.control);
                rejected(rejected, "CAPABILITY_UNAVAILABLE");
                assertEquals("BLOCKED_TARGET", string(rejected, "reasonCode"));
                assertNoDispatch(device, approvals);
                assertEquals("Count: 0", actualText(device, "counter_value"));
                screenshots.capture("unknown-window-coordinate-resolution-denied");
            } finally {
                device.instrumentation.runOnMainSync(() -> manager.removeViewImmediate(blocker));
            }
        }
    }

    @Test public void twoRealClickableTargetsAtTheSamePointRemainAmbiguous() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = tools(device, approvals);
            device.launch("OverlappingTargetsActivity");
            JsonObject screen = observed(device, tools);
            JsonObject parent = node(screen, "overlap_parent"), child = node(screen, "overlap_child");
            assertTrue("The parent must be a real actionable accessibility node", parent.get("clickable").getAsBoolean());
            assertTrue("The child must be a real actionable accessibility node", child.get("clickable").getAsBoolean());
            assertNotEquals(string(parent, "id"), string(child, "id"));
            Rect intersection = bounds(parent);
            assertTrue("Both real targets must contain the requested point", intersection.intersect(bounds(child)));
            JsonObject ambiguous = tools.execute("resolve_node_at", request(screen, intersection, "click"), device.control);
            rejected(ambiguous, "CAPABILITY_UNAVAILABLE");
            assertEquals("AMBIGUOUS_TARGET", string(ambiguous, "reasonCode"));
            assertNoDispatch(device, approvals);
            assertEquals("Parent clicks: 0", actualText(device, "overlap_parent_count"));
            assertEquals("Child clicks: 0", actualText(device, "overlap_child_count"));
            screenshots.capture("two-clickable-nodes-coordinate-resolution-ambiguous");
        }
    }

    private static AndroidDeviceTools tools(DeviceHarness device, AtomicInteger approvals) throws Exception {
        return new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true,
                (description, control, epoch) -> { approvals.incrementAndGet(); return true; });
    }

    private static JsonObject observed(DeviceHarness device, AndroidDeviceTools tools) throws Exception {
        device.awaitUiIdle();
        JsonObject screen = awaitObservation(tools, device.control, data -> FIXTURE.equals(string(data, "packageName")));
        assertTrue(screen.get("scoped").getAsBoolean());
        assertFalse("A truncated fixture cannot provide complete resolution evidence", screen.get("truncated").getAsBoolean());
        return screen;
    }

    private static JsonObject resolvedNode(DeviceHarness device, AndroidDeviceTools tools, JsonObject screen,
                                           String target, String action) throws Exception {
        JsonObject expected = node(screen, target);
        JsonObject result = tools.execute("resolve_node_at", request(screen, bounds(expected), action), device.control);
        assertTrue(result.toString(), result.get("ok").getAsBoolean());
        assertTrue(result.has("submitted") && result.get("submitted").isJsonPrimitive()
                && result.getAsJsonPrimitive("submitted").isBoolean());
        assertFalse(result.get("submitted").getAsBoolean());
        JsonObject selected = result.getAsJsonObject("data");
        assertEquals(string(expected, "id"), string(selected, "nodeId"));
        assertEquals(string(screen, "revision"), string(selected, "revision"));
        assertEquals(FIXTURE, string(selected, "packageName"));
        assertEquals(screen.get("windowId"), selected.get("windowId"));
        assertEquals(action, string(selected, "action"));
        assertEquals("screen_pixels", string(selected, "coordinateSpace"));
        assertEquals(expected.getAsJsonArray("bounds"), selected.getAsJsonArray("bounds"));
        assertEquals(device.control.getTaskId(), string(selected, "taskId"));
        assertEquals(device.control.getProfileId(), string(selected, "profileId"));
        assertEquals(device.control.getExecutionEpoch(), selected.get("executionEpoch").getAsLong());
        assertTrue(selected.get("candidateOnly").getAsBoolean());
        assertTrue("Resolution must not bypass later action confirmation", selected.get("requiresConfirmation").getAsBoolean());
        return selected;
    }

    private static JsonObject request(JsonObject screen, Rect bounds, String action) {
        assertFalse(bounds.isEmpty());
        JsonObject arguments = new JsonObject();
        arguments.addProperty("x", bounds.centerX()); arguments.addProperty("y", bounds.centerY());
        arguments.addProperty("coordinateSpace", "screen_pixels");
        arguments.addProperty("action", action);
        arguments.addProperty("expectedRevision", string(screen, "revision"));
        return arguments;
    }

    private static Rect bounds(JsonObject node) {
        return new Rect(node.getAsJsonArray("bounds").get(0).getAsInt(), node.getAsJsonArray("bounds").get(1).getAsInt(),
                node.getAsJsonArray("bounds").get(2).getAsInt(), node.getAsJsonArray("bounds").get(3).getAsInt());
    }

    private static void rejected(JsonObject result, String code) {
        assertFalse(result.toString(), result.get("ok").getAsBoolean());
        assertEquals(result.toString(), code, string(result, "code"));
        assertFalse("Rejected resolution must not return an actionable node", result.has("nodeId")
                || result.has("data") && result.getAsJsonObject("data").has("nodeId"));
    }

    private static void assertNoDispatch(DeviceHarness device, AtomicInteger approvals) {
        assertEquals("Read-only resolution requested confirmation", 0, approvals.get());
        assertEquals("Read-only resolution dispatched input", 0, device.control.getDispatchAttempts());
    }

    private static String actualText(DeviceHarness device, String id) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (!FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/" + id);
                    try {
                        if (nodes.size() == 1 && nodes.get(0).refresh()) return nodes.get(0).getText() == null ? "" : nodes.get(0).getText().toString();
                    } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("Independent fixture field is unavailable: " + id);
    }
}
