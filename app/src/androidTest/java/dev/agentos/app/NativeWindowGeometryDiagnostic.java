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
import java.util.List;

/** Failure-only technical geometry: no node text, no screenshot, no predicate retry. */
final class NativeWindowGeometryDiagnostic {
    private static final int MAX_NODES = 96, MAX_NODE_METADATA_BYTES = 24 * 1024;
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
        JsonObject result = new JsonObject();
        result.addProperty("sampleRole", "Independent later sample after returned capture error; not the rejected predicate Snapshot");
        result.addProperty("sampledAtElapsedRealtimeMillis", SystemClock.elapsedRealtime());
        if (Looper.myLooper() == Looper.getMainLooper()) { result.addProperty("status", "wrong_thread"); return result; }
        // Allocate tracking storage before acquiring the device gate. Child
        // registration then cannot grow the list while a native node is owned.
        List<AccessibilityWindowInfo> windows = null; List<AccessibilityNodeInfo> owned = new ArrayList<>(MAX_NODES);
        if (!service.tryBeginDeviceCall()) { result.addProperty("status", "device_gate_busy"); return result; }
        long deadline = SystemClock.elapsedRealtime() + 1_000;
        try {
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
    private static String technical(CharSequence value) {
        String text = value == null ? "" : value.toString();
        return text.length() <= 160 && text.matches("[A-Za-z0-9_.$:/-]*") ? text : "[nontechnical]";
    }
}
