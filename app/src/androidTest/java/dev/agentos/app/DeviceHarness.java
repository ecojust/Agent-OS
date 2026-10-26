package dev.agentos.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Installs no production bypass: enables the ordinary service only on the test device. */
final class DeviceHarness implements AutoCloseable {
    static final String FIXTURE = "dev.agentos.fixture";
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Context context = instrumentation.getTargetContext();
    final UiAutomation automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    final RunControl control = new RunControl(SettingsStore.currentProfileId(context));
    final String originalServices;
    final String originalEnabled;
    AgentAccessibilityService service;
    AndroidDeviceTools tools;

    DeviceHarness() throws Exception {
        originalServices = readSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        originalEnabled = readSecure(Settings.Secure.ACCESSIBILITY_ENABLED);
        try {
            shell("input keyevent 224");
            shell("wm dismiss-keyguard");
            String enabled = originalServices == null ? "" : originalServices;
            if (!enabled.contains(COMPONENT)) enabled = enabled.isEmpty() ? COMPONENT : enabled + ":" + COMPONENT;
            writeSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            writeSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            try {
                await("Accessibility service did not connect", () -> {
                    service = AgentAccessibilityService.getInstance();
                    return service != null && service.isConnected();
                });
            } catch (AssertionError disconnected) {
                throw new AssertionError(disconnected.getMessage() + "\n" + connectionDiagnostics(), disconnected);
            }
            instrumentation.runOnMainSync(() -> {
                service.setOverlayCallbacks(new AgentAccessibilityService.OverlayCallbacks() {
                    @Override public void onPauseResume() {
                        if (control.isPaused()) control.resume(); else control.pause();
                    }
                    @Override public void onStop() { control.cancel(); }
                    @Override public void onApprove(boolean approved) { }
                });
                service.showOverlay("Integration test", false, "");
            });
            instrumentation.waitForIdleSync();
            tools = new AndroidDeviceTools(service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(context, FIXTURE)), false,
                    (description, active) -> true);
            launch("FixtureActivity");
        } catch (Throwable error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    void launch(String activity) throws Exception {
        Intent intent = new Intent().setComponent(new ComponentName(FIXTURE, FIXTURE + "." + activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        instrumentation.waitForIdleSync();
        awaitObservation(tools, control, data -> FIXTURE.equals(string(data, "packageName")));
    }

    JsonObject observe() throws Exception {
        return awaitObservation(tools, control, data -> FIXTURE.equals(string(data, "packageName")));
    }

    JsonObject withText(String text) throws Exception {
        try {
            return awaitObservation(tools, control, data -> {
                if (!data.has("nodes")) return false;
                for (JsonElement entry : data.getAsJsonArray("nodes")) {
                    if (text.equals(string(entry.getAsJsonObject(), "text"))) return true;
                }
                return false;
            });
        } catch (AssertionError missingText) {
            throw new AssertionError("Expected fixture text: " + text + "\n" + missingText.getMessage(), missingText);
        }
    }

    static JsonObject awaitObservation(AndroidDeviceTools tools, RunControl control,
                                       Predicate<JsonObject> predicate) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        String previousRevision = "";
        JsonObject last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            last = tools.execute("observe", new JsonObject(), control);
            if (last.has("ok") && last.get("ok").getAsBoolean() && last.has("data")) {
                JsonObject data = last.getAsJsonObject("data");
                String revision = string(data, "revision");
                if (!revision.isEmpty() && revision.equals(previousRevision) && predicate.test(data)) return data;
                previousRevision = revision;
            }
            SystemClock.sleep(100);
        }
        fail("No stable matching fixture observation; last result: " + last + "\n" + windowDiagnostics());
        return null;
    }

    static JsonObject node(JsonObject observation, String id) {
        for (JsonElement entry : observation.getAsJsonArray("nodes")) {
            JsonObject node = entry.getAsJsonObject();
            if ((FIXTURE + ":id/" + id).equals(string(node, "viewId"))) return node;
        }
        fail("Missing visible fixture node: " + id);
        return null;
    }

    static JsonObject nodeArguments(JsonObject observation, String id) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("nodeId", string(node(observation, id), "id"));
        arguments.addProperty("expectedRevision", string(observation, "revision"));
        return arguments;
    }

    static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    void disableService() throws Exception {
        writeSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        writeSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "0");
        await("Accessibility service remained connected", () -> AgentAccessibilityService.getInstance() == null);
    }

    AutoCloseable enableSoftwareKeyboard() {
        String original = readSecure("show_ime_with_hard_keyboard");
        writeSecure("show_ime_with_hard_keyboard", "1");
        return () -> writeSecure("show_ime_with_hard_keyboard", original);
    }

    String keyboardDiagnostics() {
        StringBuilder diagnostic = new StringBuilder("Synthetic input-method diagnostics:");
        for (String key : new String[]{"default_input_method", "enabled_input_methods", "show_ime_with_hard_keyboard"}) {
            diagnostic.append("\n ").append(key).append('=');
            try { diagnostic.append(readSecure(key)); }
            catch (RuntimeException restricted) {
                // New releases restrict individual settings even for a test process.
                // Never replace the original keyboard assertion with a diagnostic error.
                diagnostic.append("[app read restricted: ").append(restricted.getClass().getSimpleName()).append(']');
                try {
                    String value = shell("settings get secure " + key).trim();
                    diagnostic.append(" shell=").append(value.substring(0, Math.min(value.length(), 1_024)));
                } catch (Exception unavailable) {
                    diagnostic.append(" [shell unavailable: ").append(unavailable.getClass().getSimpleName()).append(']');
                }
            }
        }
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("dumpsys input_method")); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int length;
            while (output.size() < 16_384 && (length = input.read(buffer, 0, Math.min(buffer.length, 16_384 - output.size()))) != -1) {
                output.write(buffer, 0, length);
            }
            diagnostic.append("\n").append(output.toString(StandardCharsets.UTF_8.name()));
            if (output.size() == 16_384) diagnostic.append("\n[bounded input-method diagnostic]");
        } catch (Exception failure) {
            diagnostic.append("\ninput_method dump unavailable: ").append(failure.getClass().getSimpleName());
        }
        return diagnostic.toString();
    }

    AutoCloseable enableTouchExploration() {
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int previous = info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        assertNotNull(manager);
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        automation.setServiceInfo(info);
        Runnable restore = () -> {
            AccessibilityServiceInfo current = automation.getServiceInfo();
            current.flags = (current.flags & ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) | previous;
            automation.setServiceInfo(current);
        };
        try { await("Test accessibility focus requires touch exploration", manager::isTouchExplorationEnabled); }
        catch (Throwable failure) { restore.run(); throw failure; }
        return restore::run;
    }

    String shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand(command));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int length;
            while ((length = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, length);
                if (bytes.size() > 32_768) throw new IllegalStateException("Unexpected test command output size");
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String readSecure(String key) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try { return Settings.Secure.getString(context.getContentResolver(), key); }
        finally { automation.dropShellPermissionIdentity(); }
    }

    private void writeSecure(String key, String value) {
        // executeShellCommand is not a shell-language parser: quoted values can be stored
        // literally. Use the settings provider so component IDs and empty values stay exact.
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue("Could not write secure test setting " + key,
                    Settings.Secure.putString(context.getContentResolver(), key, value));
            if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(key)) {
                assertEquals("Secure test setting read-back mismatch: " + key,
                        value, Settings.Secure.getString(context.getContentResolver(), key));
            }
            // ACCESSIBILITY_ENABLED is also recomputed by the system while UiAutomation
            // is connected. The component list stays exact; service connection/disconnection
            // is the authoritative state, checked separately instead of racing the global flag.
        } finally { automation.dropShellPermissionIdentity(); }
    }

    static String windowDiagnostics() {
        AgentAccessibilityService active = AgentAccessibilityService.getInstance();
        if (active == null) return "Fixture window diagnostics: service disconnected";
        StringBuilder diagnostic = new StringBuilder("Fixture window diagnostics:");
        WindowManager manager = (WindowManager) active.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) {
            appendMetrics(diagnostic, "current", manager.getCurrentWindowMetrics());
            appendMetrics(diagnostic, "maximum", manager.getMaximumWindowMetrics());
        }
        List<AccessibilityWindowInfo> windows = active.getWindows();
        if (windows == null) return diagnostic.append(" unavailable").toString();
        try {
            for (AccessibilityWindowInfo window : windows) {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                diagnostic.append("\n type=").append(window.getType())
                        .append(" id=").append(window.getId())
                        .append(" title=").append(window.getTitle())
                        .append(" layer=").append(window.getLayer())
                        .append(" active=").append(window.isActive())
                        .append(" focused=").append(window.isFocused())
                        .append(" display=").append(window.getDisplayId())
                        .append(" bounds=").append(bounds.toShortString());
                if (Build.VERSION.SDK_INT >= 33) {
                    Region region = new Region();
                    window.getRegionInScreen(region);
                    diagnostic.append(" region=").append(region);
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) { diagnostic.append(" root=null"); continue; }
                try {
                    diagnostic.append(" rootId=").append(root.getWindowId())
                            .append(" rootPackage=").append(root.getPackageName())
                            .append(" rootClass=").append(root.getClassName())
                            .append(" visibleBeforeRefresh=").append(root.isVisibleToUser());
                    boolean refreshed = root.refresh();
                    diagnostic.append(" rootRefresh=").append(refreshed)
                            .append(" visibleAfterRefresh=").append(root.isVisibleToUser());
                } finally { root.recycle(); }
            }
            return diagnostic.toString();
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private static void appendMetrics(StringBuilder diagnostic, String name, WindowMetrics metrics) {
        WindowInsets insets = metrics.getWindowInsets();
        diagnostic.append("\n ").append(name).append("Metrics=").append(metrics.getBounds().toShortString())
                .append(" status=").append(insets.getInsets(WindowInsets.Type.statusBars()))
                .append(" navigation=").append(insets.getInsets(WindowInsets.Type.navigationBars()))
                .append(" stableBars=").append(insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()))
                .append(" cutout=").append(insets.getInsets(WindowInsets.Type.displayCutout()))
                .append(" statusVisible=").append(insets.isVisible(WindowInsets.Type.statusBars()))
                .append(" navigationVisible=").append(insets.isVisible(WindowInsets.Type.navigationBars()));
    }

    static int focusedWindowId(AgentAccessibilityService service) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) return -1;
        int found = -1;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                if (found != -1) return -1;
                found = window.getId();
            }
            return found;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private String connectionDiagnostics() {
        try {
            String dump = shell("dumpsys accessibility");
            if (dump.length() > 8_000) dump = dump.substring(0, 8_000) + "\n[truncated]";
            return "enabledServices=" + readSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    + "; accessibilityEnabled=" + readSecure(Settings.Secure.ACCESSIBILITY_ENABLED)
                    + "\n" + dump;
        } catch (Exception failure) { return "Connection diagnostics unavailable: " + failure.getClass().getSimpleName(); }
    }

    private static void await(String failure, java.util.function.BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        fail(failure);
    }

    @Override public void close() throws Exception {
        control.cancel();
        try {
            if (service != null) instrumentation.runOnMainSync(() -> {
                service.hideOverlay();
                service.setOverlayCallbacks(null);
            });
            shell("input keyevent 224");
            shell("wm dismiss-keyguard");
        } finally {
            try { writeSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices); }
            finally { writeSecure(Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled); }
        }
    }
}
