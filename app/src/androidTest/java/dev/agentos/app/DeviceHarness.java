package dev.agentos.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
        originalServices = shell("settings get secure enabled_accessibility_services").trim();
        originalEnabled = shell("settings get secure accessibility_enabled").trim();
        try {
            shell("input keyevent 224");
            shell("wm dismiss-keyguard");
            String enabled = "null".equals(originalServices) ? "" : originalServices;
            if (!enabled.contains(COMPONENT)) enabled = enabled.isEmpty() ? COMPONENT : enabled + ":" + COMPONENT;
            shell("settings put secure enabled_accessibility_services " + quote(enabled));
            shell("settings put secure accessibility_enabled 1");
            await("Accessibility service did not connect", () -> {
                service = AgentAccessibilityService.getInstance();
                return service != null && service.isConnected();
            });
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
        return awaitObservation(tools, control, data -> {
            if (!data.has("nodes")) return false;
            for (JsonElement entry : data.getAsJsonArray("nodes")) {
                if (text.equals(string(entry.getAsJsonObject(), "text"))) return true;
            }
            return false;
        });
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
        fail("No stable matching fixture observation; last result: " + last);
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
        shell("settings put secure enabled_accessibility_services ''");
        shell("settings put secure accessibility_enabled 0");
        await("Accessibility service remained connected", () -> AgentAccessibilityService.getInstance() == null);
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

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

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
            try { restore("enabled_accessibility_services", originalServices); }
            finally { restore("accessibility_enabled", originalEnabled); }
        }
    }

    private void restore(String key, String original) throws Exception {
        if ("null".equals(original)) shell("settings delete secure " + key);
        else shell("settings put secure " + key + " " + quote(original));
    }
}
