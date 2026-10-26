package dev.agentos.app;

import android.app.Activity;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Runs real accessibility operations against the separately installed offline fixture app. */
@RunWith(AndroidJUnit4.class)
public final class DeviceToolsInstrumentedTest {
    @Test public void initialObservationFromAgentScreenCanLaunchAnAuthorizedApp() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            Activity activity = device.instrumentation.startActivitySync(new Intent(device.context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                device.instrumentation.waitForIdleSync();
                JsonObject ownScreen = awaitObservation(device.tools, device.control,
                        data -> device.context.getPackageName().equals(string(data, "packageName")));
                assertFalse(ownScreen.get("scoped").getAsBoolean());
                assertEquals(0, ownScreen.getAsJsonArray("nodes").size());
                JsonObject arguments = new JsonObject();
                arguments.addProperty("packageName", FIXTURE);
                JsonObject result = device.tools.execute("launch_app", arguments, device.control);
                assertTrue(result.toString(), result.get("ok").getAsBoolean());
                JsonObject opened = device.observe();
                assertEquals(FIXTURE, string(opened, "packageName"));
                assertTrue(opened.get("scoped").getAsBoolean());
            } finally { device.instrumentation.runOnMainSync(activity::finish); }
        }
    }

    @Test public void observesOnlyThePermittedAppWithBoundedData() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject observed = device.observe();
            assertEquals(FIXTURE, string(observed, "packageName"));
            assertTrue(observed.get("scoped").getAsBoolean());
            assertFalse(string(observed, "revision").isEmpty());
            assertTrue(observed.getAsJsonArray("nodes").size() > 0);
            assertTrue(observed.getAsJsonArray("nodes").size() <= 300);
            assertTrue(observed.toString().getBytes(StandardCharsets.UTF_8).length <= 64 * 1024);

            AndroidDeviceTools unscoped = new AndroidDeviceTools(device.service, Collections.emptySet(),
                    Collections.emptyMap(), false, (description, active) -> true);
            JsonObject result = unscoped.execute("observe", new JsonObject(), device.control);
            assertTrue(result.get("ok").getAsBoolean());
            JsonObject data = result.getAsJsonObject("data");
            assertFalse(data.get("scoped").getAsBoolean());
            assertEquals(0, data.getAsJsonArray("nodes").size());
            assertFalse(data.toString().contains("Test text"));
        }
    }

    @Test public void entersTextAndVerifiesTheActualSavedFixtureState() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject observed = device.observe();
            JsonObject text = nodeArguments(observed, "edit_value");
            text.addProperty("text", "Agent integration sample");
            JsonObject typed = device.tools.execute("type_text", text, device.control);
            assertTrue(typed.toString(), typed.get("ok").getAsBoolean());
            assertTrue(typed.get("dispatchAccepted").getAsBoolean());
            observed = device.withText("Agent integration sample");
            JsonObject clicked = device.tools.execute("click", nodeArguments(observed, "save_value"), device.control);
            assertTrue(clicked.toString(), clicked.get("ok").getAsBoolean());
            JsonObject saved = device.withText("Saved: Agent integration sample");
            assertEquals("Saved: Agent integration sample", string(node(saved, "saved_value"), "text"));
        }
    }

    @Test public void staleRevisionCannotRepeatACompletedClick() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject observed = device.observe();
            JsonObject oldArguments = nodeArguments(observed, "increment");
            JsonObject first = device.tools.execute("click", oldArguments, device.control);
            assertTrue(first.toString(), first.get("ok").getAsBoolean());
            device.withText("Count: 1");
            JsonObject stale = device.tools.execute("click", oldArguments, device.control);
            assertFalse(stale.get("ok").getAsBoolean());
            assertEquals("STALE_OBSERVATION", string(stale, "code"));
            device.withText("Count: 1");
        }
    }

    @Test public void scrollsAContainerWhileTheStopControlRemainsVisible() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject before = device.observe();
            Set<String> originalRows = new HashSet<>();
            for (JsonElement entry : before.getAsJsonArray("nodes")) {
                String text = string(entry.getAsJsonObject(), "text");
                if (text.startsWith("Scrollable test row ")) originalRows.add(text);
            }
            JsonObject arguments = nodeArguments(before, "fixture_scroll");
            arguments.addProperty("direction", "forward");
            JsonObject result = device.tools.execute("scroll", arguments, device.control);
            assertTrue(result.toString(), result.get("ok").getAsBoolean());
            awaitObservation(device.tools, device.control, data -> {
                for (JsonElement entry : data.getAsJsonArray("nodes")) {
                    String text = string(entry.getAsJsonObject(), "text");
                    if (text.startsWith("Scrollable test row ") && !originalRows.contains(text)) return true;
                }
                return false;
            });
            device.instrumentation.runOnMainSync(() -> assertTrue(device.service.isControlAvailable()));
        }
    }

    @Test public void changingActivitiesInvalidatesTheOldTarget() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject oldArguments = nodeArguments(device.observe(), "increment");
            device.launch("SecondActivity");
            device.withText("Second page reached");
            JsonObject stale = device.tools.execute("click", oldArguments, device.control);
            assertFalse(stale.get("ok").getAsBoolean());
            assertEquals("STALE_OBSERVATION", string(stale, "code"));
        }
    }

    @Test public void passwordContentIsRedactedAndCannotBeModified() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            device.launch("PasswordActivity");
            JsonObject observed = device.observe();
            assertFalse(observed.toString().contains("fixture-private-credential"));
            assertFalse(observed.toString().contains("fixture-private-description"));
            JsonObject password = node(observed, "password_field");
            assertTrue(password.get("password").getAsBoolean());
            JsonObject arguments = nodeArguments(observed, "password_field");
            arguments.addProperty("text", "must-not-be-written");
            JsonObject result = device.tools.execute("type_text", arguments, device.control);
            assertFalse(result.get("ok").getAsBoolean());
            assertFalse(device.observe().toString().contains("must-not-be-written"));
        }
    }

    @Test public void screenshotUnavailableIsReportedWithoutInvokingApproval() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AndroidDeviceTools tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                    false, (description, active) -> { fail("Unsupported capture must not ask for approval"); return false; });
            JsonObject args = new JsonObject();
            args.addProperty("expectedRevision", string(device.observe(), "revision"));
            JsonObject result = tools.execute("take_screenshot", args, device.control);
            assertFalse(result.get("ok").getAsBoolean());
            assertEquals("CAPABILITY_UNAVAILABLE", string(result, "code"));
            assertFalse(result.has("imageDataUrl"));
        }
    }

    @Test public void cancellationPreventsDispatchAndLeavesCounterUnchanged() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject args = nodeArguments(device.observe(), "increment");
            device.control.cancel();
            try {
                device.tools.execute("click", args, device.control);
                fail("Cancelled task must not dispatch");
            } catch (InterruptedException expected) {
                // Cooperative task cancellation is expected, not a successful device operation.
            }
            RunControl fresh = new RunControl(SettingsStore.currentProfileId(device.context));
            JsonObject observed = awaitObservation(device.tools, fresh,
                    data -> "Count: 0".equals(string(node(data, "counter_value"), "text")));
            assertEquals("Count: 0", string(node(observed, "counter_value"), "text"));
        }
    }

    @Test public void revokingAccessibilityCannotLeaveAUsableExecutor() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject args = nodeArguments(device.observe(), "increment");
            device.disableService();
            try {
                JsonObject result = device.tools.execute("click", args, device.control);
                assertFalse("Disconnected service must reject dispatch", result.get("ok").getAsBoolean());
            } catch (InterruptedException expected) {
                assertTrue("Service shutdown must invalidate the task", device.control.isCancelled());
            }
        }
    }
}
