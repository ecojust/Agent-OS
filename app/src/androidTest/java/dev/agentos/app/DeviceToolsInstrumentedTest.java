package dev.agentos.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Runs real accessibility operations against the separately installed offline fixture app. */
@RunWith(AndroidJUnit4.class)
public final class DeviceToolsInstrumentedTest {
    @Test public void slowObservationKeepsTheControlThreadResponsiveAndDiscardsPausedResults() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            device.launch("SlowActivity");
            JsonObject previous = device.observe();
            JsonObject oldClick = nodeArguments(previous, "increment");
            AtomicBoolean observationStarted = new AtomicBoolean();
            FutureTask<JsonObject> observation = new FutureTask<>(() -> {
                observationStarted.set(true);
                return device.tools.execute("observe", new JsonObject(), device.control);
            });
            ExecutorService worker = Executors.newSingleThreadExecutor();
            CountDownLatch heartbeat = new CountDownLatch(1);
            AtomicLong latency = new AtomicLong(Long.MAX_VALUE);
            AtomicBoolean wasInFlight = new AtomicBoolean();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    latency.set(SystemClock.elapsedRealtime() - intent.getLongExtra("startedAt", 0));
                    wasInFlight.set(observationStarted.get() && !observation.isDone());
                    device.control.pause();
                    heartbeat.countDown();
                }
            };
            IntentFilter filter = new IntentFilter("dev.agentos.fixture.OBSERVATION_BLOCKED");
            if (Build.VERSION.SDK_INT >= 33) device.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else device.context.registerReceiver(receiver, filter);
            try {
                AccessibilityEvent changed = device.automation.executeAndWaitForEvent(() ->
                        device.context.sendBroadcast(new Intent("dev.agentos.fixture.ARM_SLOW_OBSERVATION")
                                .setComponent(new ComponentName(FIXTURE, FIXTURE + ".SlowObservationReceiver"))),
                        event -> FIXTURE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 5_000);
                changed.recycle();
                // Allow the normal accessibility cache invalidation event to reach both services.
                SystemClock.sleep(200);
                worker.execute(observation);
                assertTrue("Slow fixture did not start its accessibility read", heartbeat.await(3, TimeUnit.SECONDS));
                assertTrue("Observation had already completed before the heartbeat", wasInFlight.get());
                assertTrue("Main thread was blocked by remote observation: " + latency.get() + " ms", latency.get() < 500);
                try {
                    observation.get(3, TimeUnit.SECONDS);
                    fail("The paused observation must not be delivered as a valid result");
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof InterruptedException);
                }
                assertTrue(device.control.isPaused());
                try {
                    device.tools.execute("click", oldClick, device.control);
                    fail("Paused task must not dispatch a click");
                } catch (InterruptedException expected) { }
                device.control.resume();
                device.withText("Count: 0");
            } finally {
                device.context.unregisterReceiver(receiver);
                observation.cancel(true);
                worker.shutdownNow();
            }
        }
    }

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
