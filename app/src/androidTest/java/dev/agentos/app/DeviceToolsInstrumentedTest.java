package dev.agentos.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Runs real accessibility operations against the separately installed offline fixture app. */
@RunWith(AndroidJUnit4.class)
public final class DeviceToolsInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    @Test public void slowObservationKeepsTheControlThreadResponsiveAndDiscardsPausedResults() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            device.launch("SlowActivity");
            JsonObject previous = device.observe();
            if (!previous.toString().contains("Slow observation generation 0")) {
                fail("Slow fixture node is missing from the real accessibility tree: " + previous + "\n" + windowDiagnostics());
            }
            JsonObject oldClick = nodeArguments(previous, "increment");
            AtomicBoolean observationStarted = new AtomicBoolean();
            FutureTask<JsonObject> observation = new FutureTask<>(() -> {
                observationStarted.set(true);
                return device.tools.execute("observe", new JsonObject(), device.control);
            });
            ExecutorService worker = Executors.newSingleThreadExecutor();
            CountDownLatch heartbeat = new CountDownLatch(1);
            CountDownLatch armed = new CountDownLatch(1);
            AtomicLong latency = new AtomicLong(Long.MAX_VALUE);
            AtomicBoolean wasInFlight = new AtomicBoolean();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if ("dev.agentos.fixture.OBSERVATION_ARMED".equals(intent.getAction())) {
                        armed.countDown();
                        return;
                    }
                    latency.set(SystemClock.elapsedRealtime() - intent.getLongExtra("startedAt", 0));
                    wasInFlight.set(observationStarted.get() && !observation.isDone());
                    device.control.pause();
                    heartbeat.countDown();
                }
            };
            IntentFilter filter = new IntentFilter("dev.agentos.fixture.OBSERVATION_BLOCKED");
            filter.addAction("dev.agentos.fixture.OBSERVATION_ARMED");
            registerFixtureReceiver(device.context, receiver, filter);
            try {
                AccessibilityEvent changed = device.automation.executeAndWaitForEvent(() ->
                        device.context.sendBroadcast(new Intent("dev.agentos.fixture.ARM_SLOW_OBSERVATION")
                                .setComponent(new ComponentName(FIXTURE, FIXTURE + ".SlowObservationReceiver"))),
                        event -> FIXTURE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 5_000);
                changed.recycle();
                assertTrue("Fixture never acknowledged the slow-node arm request", armed.await(3, TimeUnit.SECONDS));
                // Allow the normal accessibility cache invalidation event to reach both services.
                SystemClock.sleep(200);
                worker.execute(observation);
                boolean received = heartbeat.await(3, TimeUnit.SECONDS);
                assertTrue("Slow fixture did not start its accessibility read; armed=" + (armed.getCount() == 0)
                        + "; observation=" + completedObservation(observation), received);
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

    @Test public void slowWritePreparationRemainsResponsiveAndPausePreventsApprovalAndDispatch() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            device.launch("SlowActivity");
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true,
                    (description, control, requestEpoch) -> { approvals.incrementAndGet(); return true; });
            ExecutorService worker = Executors.newSingleThreadExecutor();
            AtomicBoolean pauseOnRead = new AtomicBoolean();
            AtomicBoolean operationStarted = new AtomicBoolean();
            AtomicBoolean inFlight = new AtomicBoolean();
            AtomicLong latency = new AtomicLong(Long.MAX_VALUE);
            AtomicReference<FutureTask<JsonObject>> operation = new AtomicReference<>();
            AtomicReference<CountDownLatch> heartbeat = new AtomicReference<>();
            AtomicReference<CountDownLatch> armed = new AtomicReference<>();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!"write-preparation-root".equals(intent.getStringExtra("readKind"))) return;
                    if ("dev.agentos.fixture.OBSERVATION_ARMED".equals(intent.getAction())) {
                        armed.get().countDown();
                        return;
                    }
                    latency.set(SystemClock.elapsedRealtime() - intent.getLongExtra("startedAt", 0));
                    inFlight.set(operationStarted.get() && operation.get() != null && !operation.get().isDone());
                    if (pauseOnRead.get()) device.control.pause();
                    heartbeat.get().countDown();
                }
            };
            IntentFilter events = new IntentFilter("dev.agentos.fixture.OBSERVATION_BLOCKED");
            events.addAction("dev.agentos.fixture.OBSERVATION_ARMED");
            registerFixtureReceiver(device.context, receiver, events);
            try {
                for (int phase = 0; phase < 2; phase++) {
                    JsonObject before = device.observe();
                    JsonObject arguments = nodeArguments(before, "increment");
                    pauseOnRead.set(phase == 1);
                    operationStarted.set(false);
                    inFlight.set(false);
                    latency.set(Long.MAX_VALUE);
                    heartbeat.set(new CountDownLatch(1));
                    armed.set(new CountDownLatch(1));
                    FutureTask<JsonObject> action = new FutureTask<>(() -> {
                        operationStarted.set(true);
                        return tools.execute("click", arguments, device.control);
                    });
                    operation.set(action);
                    AccessibilityEvent invalidated = device.automation.executeAndWaitForEvent(() ->
                            device.context.sendBroadcast(new Intent("dev.agentos.fixture.ARM_SLOW_WRITE_PREPARATION")
                                    .setComponent(new ComponentName(FIXTURE, FIXTURE + ".SlowObservationReceiver"))),
                            event -> FIXTURE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                                    && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 5_000);
                    invalidated.recycle();
                    assertTrue("Fixture did not arm its unchanged root", armed.get().await(3, TimeUnit.SECONDS));
                    device.automation.waitForIdle(200, 3_000);
                    worker.execute(action);
                    boolean received = heartbeat.get().await(3, TimeUnit.SECONDS);
                    assertTrue("Slow preparation was not exercised: " + completedObservation(action), received);
                    assertTrue("Root delay did not occur inside the actual mutation", inFlight.get());
                    assertTrue("Write preparation blocked the control thread: " + latency.get() + " ms", latency.get() < 500);
                    if (phase == 0) {
                        JsonObject accepted = action.get(8, TimeUnit.SECONDS);
                        assertTrue("Unchanged delayed revision must remain a valid write: " + accepted, accepted.get("ok").getAsBoolean());
                        assertEquals(1, approvals.get());
                        assertEquals(1, device.control.getDispatchAttempts());
                        device.withText("Count: 1");
                    } else {
                        try {
                            action.get(8, TimeUnit.SECONDS);
                            fail("Paused write preparation must not reach confirmation");
                        } catch (ExecutionException cancelled) { assertTrue(cancelled.getCause() instanceof InterruptedException); }
                        assertTrue(device.control.isPaused());
                        assertEquals("Pause must occur before confirmation", 1, approvals.get());
                        assertEquals("Pause must occur before dispatch", 1, device.control.getDispatchAttempts());
                        device.control.resume();
                        JsonObject stale = tools.execute("click", arguments, device.control);
                        assertFalse(stale.get("ok").getAsBoolean());
                        assertEquals("STALE_OBSERVATION", string(stale, "code"));
                        assertEquals(1, approvals.get());
                        assertEquals(1, device.control.getDispatchAttempts());
                        device.withText("Count: 1");
                    }
                }
                screenshots.capture("slow-preparation-paused-without-second-write");
            } finally {
                device.context.unregisterReceiver(receiver);
                FutureTask<JsonObject> pending = operation.get();
                if (pending != null) pending.cancel(true);
                worker.shutdownNow();
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void registerFixtureReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        // This test receives a broadcast from the separately installed fixture process.
        // API 30-32 have only the legacy exported-by-default overload without flag constants.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
    }

    private static String completedObservation(FutureTask<JsonObject> observation) {
        if (!observation.isDone()) return "still running";
        try { return String.valueOf(observation.get()); }
        catch (Exception failure) { return failure.toString(); }
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
                    Collections.emptyMap(), false, (description, active, requestEpoch) -> true);
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

    @Test public void ordinarySystemBarsDoNotPreventBackOrHome() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            try {
                JsonObject opened = device.tools.execute("click", nodeArguments(device.observe(), "open_second"), device.control);
                assertTrue(opened.toString(), opened.get("ok").getAsBoolean());
                JsonObject second = device.withText("Second page reached");
                JsonObject arguments = new JsonObject();
                arguments.addProperty("expectedRevision", string(second, "revision"));
                JsonObject back = device.tools.execute("back", arguments, device.control);
                assertTrue(back.toString(), back.get("ok").getAsBoolean());
                JsonObject first = device.withText("Count: 0");
                screenshots.capture("before-home");
                arguments.addProperty("expectedRevision", string(first, "revision"));
                JsonObject home = device.tools.execute("home", arguments, device.control);
                assertTrue(home.toString(), home.get("ok").getAsBoolean());
                JsonObject launcher;
                try {
                    launcher = awaitObservation(device.tools, device.control,
                            data -> !FIXTURE.equals(string(data, "packageName")));
                } catch (AssertionError notHome) {
                    throw new AssertionError("After accepted home action: " + notHome.getMessage(), notHome);
                }
                assertFalse(launcher.get("scoped").getAsBoolean());
                assertFalse(device.control.isPaused());
            } catch (Throwable failure) {
                // Capture while the service and its original window list are still present.
                try { screenshots.capture("failure-before-service-cleanup"); }
                catch (Throwable captureFailure) { failure.addSuppressed(captureFailure); }
                throw failure;
            }
        }
    }

    @Test public void observationsAndWritesFollowTheInputFocusedWindowAcrossNavigation() throws Exception {
        try (DeviceHarness device = new DeviceHarness(); AutoCloseable focusMode = device.enableTouchExploration()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                JsonObject first = device.observe();
                assertTrue(first.get("focused").getAsBoolean());
                assertEquals(focusedWindowId(device.service), first.get("windowId").getAsInt());
                JsonObject oldClick = nodeArguments(first, "increment");
                List<AccessibilityWindowInfo> windows = device.service.getWindows();
                try {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window.getId() != first.get("windowId").getAsInt()) continue;
                        AccessibilityNodeInfo root = window.getRoot();
                        assertNotNull(root);
                        try {
                            List<AccessibilityNodeInfo> targets = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/increment");
                            try {
                                assertFalse(targets.isEmpty());
                                assertTrue("Fixture could not receive accessibility focus",
                                        targets.get(0).isAccessibilityFocused()
                                                || targets.get(0).performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS));
                            } finally { for (AccessibilityNodeInfo target : targets) target.recycle(); }
                        } finally { root.recycle(); }
                    }
                } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }

                // Keep the old activity alive: input focus moves to a new window while the
                // previous accessibility target can still exist in the application's task.
                device.context.startActivity(new Intent().setComponent(new ComponentName(FIXTURE, FIXTURE + ".SecondActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                JsonObject second = device.withText("Second page reached");
                assertTrue(second.get("focused").getAsBoolean());
                assertEquals(focusedWindowId(device.service), second.get("windowId").getAsInt());
                assertNotEquals(first.get("windowId").getAsInt(), second.get("windowId").getAsInt());
                screenshots.capture("focused-second-page-" + attempt);
                long dispatches = device.control.getDispatchAttempts();
                JsonObject rejected = device.tools.execute("click", oldClick, device.control);
                assertFalse(rejected.toString(), rejected.get("ok").getAsBoolean());
                assertEquals("STALE_OBSERVATION", string(rejected, "code"));
                assertEquals(dispatches, device.control.getDispatchAttempts());
                JsonObject backArguments = new JsonObject();
                backArguments.addProperty("expectedRevision", string(second, "revision"));
                JsonObject back = device.tools.execute("back", backArguments, device.control);
                assertTrue(back.toString(), back.get("ok").getAsBoolean());
                JsonObject restored = device.withText("Count: 0");
                assertEquals(focusedWindowId(device.service), restored.get("windowId").getAsInt());
            }
        }
    }

    @Test public void showingTheKeyboardDoesNotSwitchObservationToAnOldApplicationWindow() throws Exception {
        try (DeviceHarness device = new DeviceHarness(); AutoCloseable keyboardPreference = device.enableSoftwareKeyboard()) {
            JsonObject before = device.observe();
            JsonObject clicked = device.tools.execute("click", nodeArguments(before, "edit_value"), device.control);
            assertTrue(clicked.toString(), clicked.get("ok").getAsBoolean());
            // ACTION_CLICK can succeed even when InputMethodManager declines its
            // show request. Establish this keyboard scenario with one real user
            // touch; it does not retry a model action or enable production gestures.
            JsonObject input = node(before, "edit_value");
            float x = (input.getAsJsonArray("bounds").get(0).getAsFloat()
                    + input.getAsJsonArray("bounds").get(2).getAsFloat()) / 2;
            float y = (input.getAsJsonArray("bounds").get(1).getAsFloat()
                    + input.getAsJsonArray("bounds").get(3).getAsFloat()) / 2;
            long touchStarted = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(touchStarted, touchStarted, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(touchStarted, touchStarted + 1, MotionEvent.ACTION_UP, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                assertTrue("The synthetic user's editor touch was not delivered", device.automation.injectInputEvent(down, true));
                assertTrue("The synthetic user's editor release was not delivered", device.automation.injectInputEvent(up, true));
            } finally { down.recycle(); up.recycle(); }
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            boolean keyboard = false;
            while (!keyboard && SystemClock.elapsedRealtime() < deadline) {
                List<AccessibilityWindowInfo> windows = device.service.getWindows();
                try {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) keyboard = true;
                    }
                } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
                if (!keyboard) SystemClock.sleep(50);
            }
            if (!keyboard) {
                screenshots.capture("keyboard-not-visible");
                fail("The synthetic editor did not show its keyboard\n" + windowDiagnostics() + "\n" + device.keyboardDiagnostics());
            }
            JsonObject observed = awaitObservation(device.tools, device.control,
                    data -> data.has("windowId") && data.get("windowId").getAsInt() == focusedWindowId(device.service));
            assertEquals(FIXTURE, string(observed, "packageName"));
            assertTrue(observed.get("focused").getAsBoolean());
            screenshots.capture("focused-editor-with-keyboard");
            device.shell("input keyevent 111");
            device.withText("Count: 0");
        }
    }

    @Test public void anUnrecognizedOverlayStillBlocksTheTargetAndPausesBeforeDispatch() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            JsonObject target = node(device.observe(), "increment");
            int left = target.getAsJsonArray("bounds").get(0).getAsInt();
            int top = target.getAsJsonArray("bounds").get(1).getAsInt();
            int right = target.getAsJsonArray("bounds").get(2).getAsInt();
            int bottom = target.getAsJsonArray("bounds").get(3).getAsInt();
            TextView blocker = new TextView(device.service);
            WindowManager manager = (WindowManager) device.service.getSystemService(Context.WINDOW_SERVICE);
            assertNotNull(manager);
            device.instrumentation.runOnMainSync(() -> {
                blocker.setText("Unrecognized integration overlay");
                blocker.setBackgroundColor(Color.rgb(80, 30, 30));
                blocker.setClickable(true);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(right - left, bottom - top,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = left;
                params.y = top;
                // A touchable, non-focusable window appears in the interactive-window list
                // while keeping the fixture's application root active for observation.
                // Deliberately not the trusted Agent control window's identity.
                params.setTitle("Fixture test obstruction");
                manager.addView(blocker, params);
            });
            long attempts = device.control.getDispatchAttempts();
            try {
                JsonObject obscured = awaitObservation(device.tools, device.control,
                        data -> data.get("hasOcclusion").getAsBoolean()
                                && node(data, "increment").get("obscured").getAsBoolean());
                try {
                    device.tools.execute("click", nodeArguments(obscured, "increment"), device.control);
                    fail("A different overlay must stop the target action");
                } catch (InterruptedException expected) {
                    assertTrue("The obscuring window must pause the task", device.control.isPaused());
                }
                assertEquals("Occluded action was dispatched", attempts, device.control.getDispatchAttempts());
            } finally {
                device.instrumentation.runOnMainSync(() -> manager.removeViewImmediate(blocker));
            }
            device.control.resume();
            device.withText("Count: 0");
        }
    }

    @Test public void theCompactStopControlsStillPreventClickingACoveredFixtureTarget() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            device.launch("CoveredActivity");
            JsonObject observed = device.observe();
            JsonObject target = node(observed, "increment");
            Rect bounds = new Rect(target.getAsJsonArray("bounds").get(0).getAsInt(),
                    target.getAsJsonArray("bounds").get(1).getAsInt(),
                    target.getAsJsonArray("bounds").get(2).getAsInt(),
                    target.getAsJsonArray("bounds").get(3).getAsInt());
            device.instrumentation.runOnMainSync(() -> assertTrue("Fixture target must actually be covered by the compact controls",
                    Rect.intersects(bounds, device.service.controlBounds())));
            long dispatched = device.control.getDispatchAttempts();
            JsonObject result = device.tools.execute("click", nodeArguments(observed, "increment"), device.control);
            assertFalse(result.toString(), result.get("ok").getAsBoolean());
            assertEquals("CAPABILITY_UNAVAILABLE", string(result, "code"));
            assertEquals(dispatched, device.control.getDispatchAttempts());
            device.withText("Count: 0");
            screenshots.capture("compact-controls-still-cover-target");
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
            assertEquals("Password writes: 0", actualFixtureText(device, "password_changes"));
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                    true, (description, active, requestEpoch) -> { approvals.incrementAndGet(); return true; });
            long dispatches = device.control.getDispatchAttempts();
            JsonObject arguments = nodeArguments(observed, "password_field");
            arguments.addProperty("text", "must-not-be-written");
            JsonObject result = tools.execute("type_text", arguments, device.control);
            assertFalse(result.get("ok").getAsBoolean());
            assertEquals("PERMISSION_DENIED", string(result, "code"));
            assertEquals(0, approvals.get());
            assertEquals(dispatches, device.control.getDispatchAttempts());
            // Read the fixture's separate TextWatcher counter directly through the
            // platform, so executor redaction cannot conceal an accidental write.
            assertEquals("Password writes: 0", actualFixtureText(device, "password_changes"));
            assertFalse(device.observe().toString().contains("must-not-be-written"));
        }
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
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/" + id);
                    try {
                        if (nodes.size() == 1 && nodes.get(0).refresh() && nodes.get(0).getText() != null) {
                            return nodes.get(0).getText().toString();
                        }
                    } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("Independent fixture evidence is unavailable: " + id);
    }

    @Test public void screenshotUnavailableIsReportedWithoutInvokingApproval() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AndroidDeviceTools tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                    false, (description, active, requestEpoch) -> { fail("Unsupported capture must not ask for approval"); return false; });
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
