package dev.agentos.app;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

/** Test-only technical geometry: no node text, screenshot, or permission/predicate retry. */
final class NativeWindowGeometryDiagnostic {
    private static final int MAX_NODES = 96, MAX_NODE_METADATA_BYTES = 24 * 1024;
    private static final Pattern RECTANGLE = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    private NativeWindowGeometryDiagnostic() { }

    static JsonObject fromObservation(JsonObject original) {
        JsonObject result = new JsonObject();
        result.addProperty("sampleRole", "Sanitized geometry from the actual first planning request; not a new observation");
        for (String key : new String[]{"windowId", "scoped", "truncated", "hasOcclusion"})
            if (original.has(key)) result.add(key, original.get(key).deepCopy());
        JsonArray nodes = new JsonArray(); JsonArray actual = original.getAsJsonArray("nodes");
        int metadataBytes = 0;
        if (actual != null) for (int i = 0; i < Math.min(MAX_NODES, actual.size()); i++) {
            JsonObject source = actual.get(i).getAsJsonObject(), node = new JsonObject();
            for (String key : new String[]{"id", "viewId", "role"})
                if (source.has(key)) node.addProperty(key, technical(source.get(key).getAsString()));
            for (String key : new String[]{"focused", "selected", "editable", "password", "obscured", "contentTruncated"})
                if (source.has(key)) node.add(key, source.get(key).deepCopy());
            JsonArray rect = source.getAsJsonArray("bounds");
            if (rect != null && rect.size() == 4) {
                JsonArray copy = new JsonArray(); for (JsonElement value : rect) copy.add(value.getAsInt()); node.add("bounds", copy);
            }
            metadataBytes += node.toString().getBytes(StandardCharsets.UTF_8).length;
            if (metadataBytes > MAX_NODE_METADATA_BYTES) break;
            nodes.add(node);
        }
        result.add("nodes", nodes); result.addProperty("metadataTruncated", actual != null && nodes.size() < actual.size());
        return result;
    }

    static JsonObject sample(AgentAccessibilityService service) {
        return sample(service, SystemClock.elapsedRealtime() + 1_000,
                "Independent later sample after returned capture error; not the rejected predicate Snapshot");
    }

    /** Geometry readiness only. No capture, model qualification, or policy permit is issued. */
    static String readinessSignature(AgentAccessibilityService service, long preparationDeadline) {
        JsonObject sampled = sample(service, Math.min(preparationDeadline, SystemClock.elapsedRealtime() + 1_000),
                "Pre-start readiness sample; not a task observation or capture authorization");
        if (SystemClock.elapsedRealtime() >= preparationDeadline || !sampled.has("status")
                || !"sampled".equals(sampled.get("status").getAsString())
                || sampled.get("metadataTruncated").getAsBoolean()) return null;
        Rect display = rectangle(sampled.get("displayBounds").getAsString());
        Rect window = rectangle(sampled.get("windowBounds").getAsString());
        Rect root = rectangle(sampled.get("rootBounds").getAsString());
        Rect safe = rectangle(sampled.get("safeContentBounds").getAsString());
        int rotation = sampled.get("rotation").getAsInt(), windowId = sampled.get("windowId").getAsInt();
        if (!new Rect(0, 0, 320, 640).equals(display) || !display.equals(window) || !window.equals(root)
                || windowId < 0 || safe == null || safe.isEmpty() || !window.contains(safe) || (rotation != 0 && rotation != 2)) return null;
        int formula = 0, result = 0, equals = 0;
        List<String> nodes = new ArrayList<>(), windows = new ArrayList<>();
        for (JsonElement value : sampled.getAsJsonArray("nodes")) {
            JsonObject node = value.getAsJsonObject(); boolean visible = node.get("visible").getAsBoolean();
            Rect box = rectangle(node.get("bounds").getAsString());
            if (visible && (box == null || box.isEmpty() || !window.contains(box)
                    || node.get("windowId").getAsInt() != windowId || node.get("sensitive").getAsBoolean())) return null;
            if (visible) {
                String id = node.get("viewId").getAsString();
                boolean required = id.equals(NativeWindowTaskScript.PACKAGE + ":id/formula")
                        || id.equals(NativeWindowTaskScript.PACKAGE + ":id/result")
                        || id.equals(NativeWindowTaskScript.PACKAGE + ":id/btn_equals");
                if (required && (!"android.widget.TextView".equals(node.get("role").getAsString()) || !safe.contains(box))) return null;
                if (id.equals(NativeWindowTaskScript.PACKAGE + ":id/formula")) formula++;
                if (id.equals(NativeWindowTaskScript.PACKAGE + ":id/result")) result++;
                if (id.equals(NativeWindowTaskScript.PACKAGE + ":id/btn_equals")) equals++;
            }
            nodes.add(node.toString());
        }
        if (formula != 1 || result != 1 || equals != 1) return null;
        for (JsonElement value : sampled.getAsJsonArray("windows")) windows.add(value.toString());
        Collections.sort(windows); Collections.sort(nodes);
        if (SystemClock.elapsedRealtime() >= preparationDeadline) return null;
        return windowId + ":" + window.toShortString() + ":" + root.toShortString() + ":"
                + display.toShortString() + ":" + safe.toShortString() + ":" + rotation + ":" + windows + ":" + nodes;
    }

    private static JsonObject sample(AgentAccessibilityService service, long deadline, String role) {
        JsonObject result = new JsonObject();
        result.addProperty("sampleRole", role);
        result.addProperty("sampledAtElapsedRealtimeMillis", SystemClock.elapsedRealtime());
        if (Looper.myLooper() == Looper.getMainLooper()) { result.addProperty("status", "wrong_thread"); return result; }
        // Allocate tracking storage before acquiring the device gate. Child
        // registration then cannot grow the list while a native node is owned.
        List<AccessibilityWindowInfo> windows = null; List<AccessibilityNodeInfo> owned = new ArrayList<>(MAX_NODES);
        if (!service.tryBeginDeviceCall()) { result.addProperty("status", "device_gate_busy"); return result; }
        try {
            check(deadline);
            windows = AndroidDeviceTools.freshWindows(service, () -> check(deadline));
            check(deadline);
            if (windows == null || windows.size() > 40) throw new IOException("Window inventory unavailable");
            JsonArray inventory = new JsonArray(); AccessibilityWindowInfo target = null; int focused = 0;
            for (AccessibilityWindowInfo window : windows) {
                check(deadline); JsonObject item = new JsonObject(); Rect box = new Rect(); window.getBoundsInScreen(box);
                item.addProperty("id", window.getId()); item.addProperty("display", window.getDisplayId());
                item.addProperty("type", window.getType()); item.addProperty("layer", window.getLayer());
                item.addProperty("focused", window.isFocused()); item.addProperty("bounds", box.toShortString()); inventory.add(item);
                if (window.isFocused()) { target = window; focused++; }
            }
            result.add("windows", inventory); result.addProperty("focusedWindowCount", focused);
            if (focused != 1 || target == null || target.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                    || target.getDisplayId() != Display.DEFAULT_DISPLAY) { result.addProperty("status", "unsupported_focus"); return result; }
            Rect windowBounds = new Rect(); target.getBoundsInScreen(windowBounds);
            result.addProperty("windowId", target.getId()); result.addProperty("windowBounds", windowBounds.toShortString());
            AccessibilityNodeInfo root = target.getRoot(); if (root == null) { result.addProperty("status", "missing_root"); return result; }
            owned.add(root); check(deadline);
            if (!NativeWindowTaskScript.PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                result.addProperty("status", "non_pinned_target"); return result;
            }
            Rect rootBounds = new Rect(); root.getBoundsInScreen(rootBounds); result.addProperty("rootBounds", rootBounds.toShortString());
            WindowManager manager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) throw new IOException("Window metrics unavailable");
            WindowMetrics metrics = manager.getCurrentWindowMetrics(); check(deadline);
            Rect display = metrics.getBounds();
            Insets safe = metrics.getWindowInsets().getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
            Rect safeBounds = new Rect(display.left + safe.left, display.top + safe.top, display.right - safe.right, display.bottom - safe.bottom);
            Rect observed = new Rect(rootBounds); if (!observed.intersect(windowBounds) || !safeBounds.intersect(observed)) safeBounds.setEmpty();
            result.addProperty("displayBounds", display.toShortString()); result.addProperty("safeContentBounds", safeBounds.toShortString());
            result.addProperty("rotation", manager.getDefaultDisplay().getRotation());
            JsonArray nodes = new JsonArray(); boolean[] truncated = {false};
            visit(root, "n", 0, windowBounds, deadline, owned, nodes, truncated, new int[]{0});
            result.add("nodes", nodes); result.addProperty("metadataTruncated", truncated[0]);
            check(deadline); result.addProperty("status", "sampled");
        } catch (Exception unavailable) {
            result.addProperty("status", "unavailable"); result.addProperty("errorClass", unavailable.getClass().getSimpleName());
        } finally {
            try { for (AccessibilityNodeInfo node : owned) node.recycle(); }
            finally { try { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
                finally { service.endDeviceCall(); } }
        }
        result.addProperty("completedAtElapsedRealtimeMillis", SystemClock.elapsedRealtime());
        return result;
    }

    private static void visit(AccessibilityNodeInfo node, String path, int depth, Rect window, long deadline,
                              List<AccessibilityNodeInfo> owned, JsonArray nodes, boolean[] truncated, int[] metadataBytes) throws IOException {
        check(deadline);
        if (depth > 30 || nodes.size() >= MAX_NODES || metadataBytes[0] > MAX_NODE_METADATA_BYTES) { truncated[0] = true; return; }
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds); JsonObject item = new JsonObject();
        item.addProperty("id", path); item.addProperty("viewId", technical(node.getViewIdResourceName()));
        item.addProperty("role", technical(node.getClassName())); item.addProperty("bounds", bounds.toShortString());
        item.addProperty("windowId", node.getWindowId()); item.addProperty("visible", node.isVisibleToUser());
        item.addProperty("emptyBounds", bounds.isEmpty()); item.addProperty("withinWindow", window.contains(bounds));
        item.addProperty("focused", node.isFocused()); item.addProperty("selected", node.isSelected());
        boolean sensitive = node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
        item.addProperty("sensitive", sensitive);
        metadataBytes[0] += item.toString().getBytes(StandardCharsets.UTF_8).length;
        if (metadataBytes[0] > MAX_NODE_METADATA_BYTES) { truncated[0] = true; return; }
        nodes.add(item);
        if (sensitive) return;
        for (int i = 0; i < node.getChildCount(); i++) {
            check(deadline); if (owned.size() >= MAX_NODES || metadataBytes[0] > MAX_NODE_METADATA_BYTES) { truncated[0] = true; break; }
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            owned.add(child); visit(child, path + "." + i, depth + 1, window, deadline, owned, nodes, truncated, metadataBytes);
        }
    }
    private static void check(long deadline) throws IOException {
        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Diagnostic deadline exceeded");
    }
    private static Rect rectangle(String value) {
        Matcher match = RECTANGLE.matcher(value);
        if (!match.matches()) return null;
        try { return new Rect(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4))); }
        catch (NumberFormatException unavailable) { return null; }
    }
    private static String technical(CharSequence value) {
        String text = value == null ? "" : value.toString();
        return text.length() <= 160 && text.matches("[A-Za-z0-9_.$:/-]*") ? text : "[nontechnical]";
    }
}
