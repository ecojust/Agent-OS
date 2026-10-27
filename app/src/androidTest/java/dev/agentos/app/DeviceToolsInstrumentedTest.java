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
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
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
import java.util.function.Function;

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
            AndroidDeviceTools tools = AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true, () -> true,
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
                    device.awaitUiIdle();
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
        try (DeviceHarness device = new DeviceHarness(); AutoCloseable keyboardPreference = device.enableSoftwareKeyboard()) {
            Activity activity = device.instrumentation.startActivitySync(new Intent(device.context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                device.instrumentation.waitForIdleSync();
                device.awaitUiIdle();
                // Reproduce the real task-entry state: an owned text editor with a
                // genuinely visible system IME, rather than assuming idle means clear.
                long deadline = SystemClock.elapsedRealtime() + 8_000;
                boolean[] editorReady = {false};
                do {
                    device.instrumentation.runOnMainSync(() -> {
                        EditText editor = activity.findViewById(R.id.task_prompt);
                        editorReady[0] = editor != null && editor.isEnabled() && editor.hasWindowFocus();
                    });
                    if (!editorReady[0]) SystemClock.sleep(25);
                } while (!editorReady[0] && SystemClock.elapsedRealtime() < deadline);
                assertTrue("The owned task editor did not become ready", editorReady[0]);
                device.instrumentation.runOnMainSync(() -> {
                    EditText editor = activity.findViewById(R.id.task_prompt);
                    editor.requestFocus();
                    editor.post(() -> {
                        if (!editor.isFocused() || !editor.hasWindowFocus()) return;
                        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                        if (Build.VERSION.SDK_INT < 33 && keyboard != null) keyboard.isActive();
                        if (activity.getWindow().getInsetsController() != null) activity.getWindow().getInsetsController().show(WindowInsets.Type.ime());
                        if (keyboard != null) keyboard.showSoftInput(editor, 0);
                    });
                });
                boolean[] visibleInsets = {false};
                boolean imeWindow = false;
                String lastOwnWindows = "not_read";
                deadline = SystemClock.elapsedRealtime() + 5_000;
                do {
                    device.instrumentation.runOnMainSync(() -> {
                        WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                        visibleInsets[0] = insets != null && insets.isVisible(WindowInsets.Type.ime());
                    });
                    List<AccessibilityWindowInfo> windows = device.service.getWindows();
                    try {
                        lastOwnWindows = compactWindowMetadata(windows);
                        imeWindow = false;
                        for (AccessibilityWindowInfo window : windows) {
                            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) imeWindow = true;
                        }
                    } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
                    if (!(visibleInsets[0] && imeWindow)) SystemClock.sleep(50);
                } while (!(visibleInsets[0] && imeWindow) && SystemClock.elapsedRealtime() < deadline);
                if (!(visibleInsets[0] && imeWindow)) {
                    // Capture while the actual owned Activity is still foreground.
                    // Its protected window may block capture; never clear FLAG_SECURE.
                    screenshots.capture("owned-keyboard-visibility-mismatch");
                    fail("Owned editor keyboard visibility evidence did not agree: inset=" + visibleInsets[0] + ", window=" + imeWindow
                            + "\nLast loop service windows: " + lastOwnWindows
                            + "\n" + device.keyboardWindowDiagnostics() + "\n" + device.keyboardDiagnostics());
                }
                JsonObject ownScreen = device.hideOwnKeyboardAndObserve(activity, device.tools, device.control);
                assertFalse(ownScreen.get("scoped").getAsBoolean());
                assertEquals(0, ownScreen.getAsJsonArray("nodes").size());
                JsonObject arguments = new JsonObject();
                arguments.addProperty("packageName", FIXTURE);
                JsonObject result = device.tools.execute("launch_app", arguments, device.control);
                assertTrue(result.toString(), result.get("ok").getAsBoolean());
                JsonObject opened = device.observe();
                assertEquals(FIXTURE, string(opened, "packageName"));
                assertTrue(opened.get("scoped").getAsBoolean());
                screenshots.capture("agent-launch-after-owned-keyboard-actually-dismissed");
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

            AndroidDeviceTools unscoped = AndroidDeviceTools.forTesting(device.service, Collections.emptySet(),
                    Collections.emptyMap(), false, () -> true, (description, active, requestEpoch) -> true);
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
            JsonObject clicked = executePositivePlan(device, "click", "Agent integration sample",
                    current -> nodeArguments(current, "save_value"));
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
                JsonObject back = executePositivePlan(device, "back", "Second page reached",
                        DeviceToolsInstrumentedTest::revisionArguments);
                assertTrue(back.toString(), back.get("ok").getAsBoolean());
                device.withText("Count: 0");
                screenshots.capture("before-home");
                JsonObject home = executePositivePlan(device, "home", "Count: 0",
                        DeviceToolsInstrumentedTest::revisionArguments);
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
                device.awaitUiIdle();
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
                JsonObject back = executePositivePlan(device, "back", "Second page reached",
                        DeviceToolsInstrumentedTest::revisionArguments);
                assertTrue(back.toString(), back.get("ok").getAsBoolean());
                JsonObject restored = device.withText("Count: 0");
                assertEquals(focusedWindowId(device.service), restored.get("windowId").getAsInt());
            }
        }
    }

    /** Only for the ordinary positive, sequential actions above, never stale/paused/slow-write probes. */
    private static JsonObject executePositivePlan(DeviceHarness device, String tool, String expectedText,
                                                  Function<JsonObject, JsonObject> plan) throws Exception {
        long epoch = device.control.getExecutionEpoch();
        for (int attempt = 0; attempt <= 3; attempt++) {
            // A preceding write or screenshot can span input/layout/window events.
            // Re-establish the expected page and derive new arguments after real idle.
            device.awaitUiIdle();
            device.control.checkExecutionEpoch(epoch);
            JsonObject current = device.withText(expectedText);
            JsonObject arguments = plan.apply(current);
            long dispatches = device.control.getDispatchAttempts();
            JsonObject result = device.tools.execute(tool, arguments, device.control, epoch);
            assertTrue("Action result must declare a boolean ok: " + result,
                    explicitBoolean(result, "ok", true) || explicitBoolean(result, "ok", false));
            if (explicitBoolean(result, "ok", true)) return result;
            boolean unsubmittedStale = "STALE_OBSERVATION".equals(string(result, "code"))
                    && explicitBoolean(result, "ok", false) && explicitBoolean(result, "submitted", false);
            for (String accepted : new String[]{"accepted", "dispatchSubmitted", "dispatchAccepted"}) {
                if (result.has(accepted) && !explicitBoolean(result, accepted, false)) unsubmittedStale = false;
            }
            if (!unsubmittedStale) return result;
            assertEquals("A purportedly unsubmitted plan reached dispatch", dispatches, device.control.getDispatchAttempts());
            if (attempt == 3) return result;
        }
        throw new AssertionError("Positive planning exceeded its bounded attempts");
    }

    private static boolean explicitBoolean(JsonObject result, String key, boolean expected) {
        return result.has(key) && result.get(key).isJsonPrimitive()
                && result.getAsJsonPrimitive(key).isBoolean() && result.get(key).getAsBoolean() == expected;
    }

    private static JsonObject revisionArguments(JsonObject observed) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("expectedRevision", string(observed, "revision"));
        return arguments;
    }

    @Test public void showingTheKeyboardDoesNotSwitchObservationToAnOldApplicationWindow() throws Exception {
        try (DeviceHarness device = new DeviceHarness(); AutoCloseable keyboardPreference = device.enableSoftwareKeyboard()) {
            device.launch("KeyboardActivity");
            JsonObject before = device.observe();
            assertEquals("Keyboard visible: false", actualFixtureText(device, "keyboard_visible"));
            JsonObject clicked = device.tools.execute("click", nodeArguments(before, "show_keyboard"), device.control);
            assertTrue(clicked.toString(), clicked.get("ok").getAsBoolean());
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            boolean keyboard = false;
            boolean visibleInset = false;
            int observedOk = 0, observedStale = 0;
            String lastObservationCode = "not_attempted", lastWindows = "not_read";
            while (!(keyboard && visibleInset) && SystemClock.elapsedRealtime() < deadline) {
                // Exercise the executor's authoritative observation path, including
                // its platform cache refresh, rather than a stale test-only window read.
                JsonObject sample = device.tools.execute("observe", new JsonObject(), device.control);
                boolean sampleOk = sample.get("ok").getAsBoolean();
                lastObservationCode = sampleOk ? "OK" : string(sample, "code");
                if (sampleOk) observedOk++;
                else if ("STALE_OBSERVATION".equals(lastObservationCode)) observedStale++;
                assertTrue(sample.toString(), sample.get("ok").getAsBoolean()
                        || "STALE_OBSERVATION".equals(string(sample, "code")));
                keyboard = false;
                List<AccessibilityWindowInfo> windows = device.service.getWindows();
                try {
                    lastWindows = compactWindowMetadata(windows);
                    for (AccessibilityWindowInfo window : windows) {
                        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) keyboard = true;
                    }
                } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
                visibleInset = "Keyboard visible: true".equals(actualFixtureText(device, "keyboard_visible"));
                if (!(keyboard && visibleInset)) SystemClock.sleep(50);
            }
            if (!(keyboard && visibleInset)) {
                screenshots.capture("keyboard-not-visible");
                fail("Keyboard visibility evidence did not agree: window=" + keyboard + ", visibleInset=" + visibleInset
                        + "; observeOk=" + observedOk + ", observeStale=" + observedStale + ", lastCode=" + lastObservationCode
                        + "\nLast loop service windows: " + lastWindows
                        + "\n" + device.keyboardWindowDiagnostics() + "\n" + device.keyboardDiagnostics());
            }
            JsonObject observed = awaitObservation(device.tools, device.control,
                    data -> data.has("windowId") && data.get("windowId").getAsInt() == focusedWindowId(device.service));
            assertEquals(FIXTURE, string(observed, "packageName"));
            assertTrue(observed.get("focused").getAsBoolean());
            if (keyboardOverlapsObservedContent(device, observed)) {
                assertTrue("An overlapping keyboard must remain an obstruction", observed.get("hasOcclusion").getAsBoolean());
            }
            long attempts = device.control.getDispatchAttempts();
            JsonObject stale = device.tools.execute("click", nodeArguments(before, "show_keyboard"), device.control);
            assertFalse(stale.get("ok").getAsBoolean());
            assertEquals("STALE_OBSERVATION", string(stale, "code"));
            assertEquals("The pre-keyboard revision dispatched another action", attempts, device.control.getDispatchAttempts());
            screenshots.capture("focused-editor-with-keyboard");
            // BACK is the platform IME-dismiss action; ESC is not guaranteed to hide it.
            device.shell("input keyevent 4");
            device.withText("Keyboard visible: false");
        }
    }

    private static boolean keyboardOverlapsObservedContent(DeviceHarness device, JsonObject observed) {
        JsonObject root = observed.getAsJsonArray("nodes").get(0).getAsJsonObject();
        assertEquals("n", string(root, "id"));
        Rect content = new Rect(root.getAsJsonArray("bounds").get(0).getAsInt(),
                root.getAsJsonArray("bounds").get(1).getAsInt(), root.getAsJsonArray("bounds").get(2).getAsInt(),
                root.getAsJsonArray("bounds").get(3).getAsInt());
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            boolean focusedFound = false;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() != observed.get("windowId").getAsInt()) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                assertTrue("Observed content lost its current window", content.intersect(bounds));
                focusedFound = window.isFocused();
            }
            assertTrue("Observed application lost focus", focusedFound);
            boolean keyboardFound = false;
            boolean overlaps = false;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                keyboardFound = true;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                overlaps |= Rect.intersects(content, bounds);
            }
            assertTrue("The keyboard disappeared before the focused-window assertion", keyboardFound);
            // adjustResize may legitimately place the entire app above the keyboard.
            return overlaps;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
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
            JsonObject blockedArguments = null;
            try {
                device.awaitUiIdle();
                JsonObject obscured = awaitObservation(device.tools, device.control,
                        data -> data.get("hasOcclusion").getAsBoolean()
                                && node(data, "increment").get("obscured").getAsBoolean());
                blockedArguments = nodeArguments(obscured, "increment");
                long epoch = device.control.getExecutionEpoch();
                try {
                    JsonObject returned = device.tools.execute("click", blockedArguments, device.control);
                    AssertionError failure = new AssertionError("A different overlay must stop the target action; result=" + returned
                            + "; paused=" + device.control.isPaused() + "; cancelled=" + device.control.isCancelled()
                            + "; epoch=" + epoch + "->" + device.control.getExecutionEpoch()
                            + "; dispatchDelta=" + (device.control.getDispatchAttempts() - attempts));
                    try { screenshots.capture("obstruction-returned-before-cleanup"); }
                    catch (Throwable captureFailure) { failure.addSuppressed(captureFailure); }
                    throw failure;
                } catch (InterruptedException expected) {
                    assertTrue("The obscuring window must pause the task", device.control.isPaused());
                }
                assertEquals("Occluded action was dispatched", attempts, device.control.getDispatchAttempts());
            } finally {
                device.instrumentation.runOnMainSync(() -> manager.removeViewImmediate(blocker));
            }
            device.control.resume();
            device.withText("Count: 0");
            assertNotNull(blockedArguments);
            JsonObject stale = device.tools.execute("click", blockedArguments, device.control);
            assertFalse(stale.toString(), stale.get("ok").getAsBoolean());
            assertEquals("STALE_OBSERVATION", string(stale, "code"));
            assertFalse("Old obstruction evidence must not pause a resumed request after fresh observation", device.control.isPaused());
            assertEquals(attempts, device.control.getDispatchAttempts());
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
            AndroidDeviceTools tools = AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                    true, () -> true, (description, active, requestEpoch) -> { approvals.incrementAndGet(); return true; });
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
            AndroidDeviceTools tools = AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                    false, () -> true, (description, active, requestEpoch) -> { fail("Unsupported capture must not ask for approval"); return false; });
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

    @Test public void anOldExecutionEpochCannotLaunchAfterPauseAndResume() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            device.launch("SecondActivity");
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                    true, () -> true, (description, active, requestEpoch) -> { approvals.incrementAndGet(); return true; });
            JsonObject before = awaitObservation(tools, device.control,
                    data -> "Second page reached".equals(string(node(data, "second_marker"), "text")));
            assertEquals("Second page reached", actualFixtureText(device, "second_marker"));
            long originalEpoch = device.control.getExecutionEpoch();
            long dispatches = device.control.getDispatchAttempts();
            device.control.pause();
            device.control.resume();
            assertFalse(device.control.isPaused());
            assertNotEquals(originalEpoch, device.control.getExecutionEpoch());

            // launch_app has no expectedRevision argument. Its original task epoch
            // must still survive the dispatcher-to-device boundary unchanged.
            JsonObject arguments = new JsonObject();
            arguments.addProperty("packageName", FIXTURE);
            try {
                tools.execute("launch_app", arguments, device.control, originalEpoch);
                fail("An old queued launch must not adopt the resumed task's epoch");
            } catch (TaskControlException stale) { assertEquals("TASK_PAUSED", stale.getCode()); }
            assertEquals("An old launch must not ask for a new confirmation", 0, approvals.get());
            assertEquals("An old launch reached Android dispatch", dispatches, device.control.getDispatchAttempts());
            assertEquals(before.get("windowId").getAsInt(), focusedWindowId(device.service));
            assertEquals("Second page reached", actualFixtureText(device, "second_marker"));
            screenshots.capture("stale-epoch-launch-rejected-without-navigation");
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
