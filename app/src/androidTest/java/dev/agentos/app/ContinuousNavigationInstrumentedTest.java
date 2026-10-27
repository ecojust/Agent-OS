package dev.agentos.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy.Mode;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Real Android navigation in an explicitly chosen mode; no arbitrary-input auto-approval. */
@RunWith(AndroidJUnit4.class)
public final class ContinuousNavigationInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void launchScrollAndHomeChangeTheRealUiWithoutAnApproval() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            final String expectedHomePackage = defaultHomePackage(device);
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = navigationTools(device, (description, control, epoch) -> {
                approvals.incrementAndGet(); fail("Reviewed navigation must not request a separate confirmation"); return false;
            });
            Activity activity = device.instrumentation.startActivitySync(new Intent(device.context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                device.hideOwnKeyboardAndObserve(activity, tools, device.control);
                plan(device, tools, "launch_app", data -> device.context.getPackageName().equals(string(data, "packageName")),
                        ignored -> launchArguments());
                JsonObject opened = fixture(tools, device);
                assertTrue(opened.get("scoped").getAsBoolean());
                assertEquals("Count: 0", string(node(opened, "counter_value"), "text"));
                Set<String> originalRows = rows(opened);
                plan(device, tools, "scroll", ContinuousNavigationInstrumentedTest::isFixture,
                        data -> scrollArguments(data, "forward"));
                JsonObject scrolled = awaitObservation(tools, device.control, data -> hasNewRow(data, originalRows));
                assertNotEquals(string(opened, "revision"), string(scrolled, "revision"));
                screenshots.capture("continuous-navigation-real-scroll-without-confirmation");
                plan(device, tools, "home", ContinuousNavigationInstrumentedTest::isFixture,
                        ContinuousNavigationInstrumentedTest::revisionArguments);
                JsonObject launcher = awaitObservation(tools, device.control,
                        data -> expectedHomePackage.equals(string(data, "packageName")));
                assertEquals(expectedHomePackage, string(launcher, "packageName"));
                assertFalse(launcher.get("scoped").getAsBoolean());
                assertEquals(0, launcher.getAsJsonArray("nodes").size());
                assertEquals(0, approvals.get());
                assertEquals(3, device.control.getDispatchAttempts());
                assertFalse(device.control.isPaused());
                device.instrumentation.runOnMainSync(() -> assertTrue(device.service.isControlAvailable()));
                screenshots.capture("continuous-navigation-real-home-without-confirmation");
            } finally { device.instrumentation.runOnMainSync(activity::finish); }
        }
    }

    @Test public void textClickAndBackStillRequireDistinctApprovalsInNavigationMode() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            Set<String> confirmedTools = new HashSet<>();
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = navigationTools(device, (description, control, epoch) -> {
                assertEquals(device.control, control);
                control.checkExecutionEpoch(epoch);
                for (String tool : new String[]{"type_text", "click", "back"}) {
                    if (description.contains("\n动作：" + tool + "\n") || description.endsWith("\n动作：" + tool)) {
                        confirmedTools.add(tool); approvals.incrementAndGet(); return true;
                    }
                }
                fail("Unexpected tool sought generic-input approval: " + description); return false;
            });
            plan(device, tools, "type_text", ContinuousNavigationInstrumentedTest::isFixture, data -> {
                JsonObject args = nodeArguments(data, "edit_value"); args.addProperty("text", "Navigation mode keeps input confirmation"); return args;
            });
            awaitObservation(tools, device.control, data -> hasText(data, "Navigation mode keeps input confirmation"));
            plan(device, tools, "click", data -> hasText(data, "Navigation mode keeps input confirmation"),
                    data -> nodeArguments(data, "open_second"));
            awaitObservation(tools, device.control, data -> hasText(data, "Second page reached"));
            plan(device, tools, "back", data -> hasText(data, "Second page reached"),
                    ContinuousNavigationInstrumentedTest::revisionArguments);
            JsonObject returned = awaitObservation(tools, device.control,
                    data -> hasText(data, "Navigation mode keeps input confirmation"));
            assertEquals("Count: 0", string(node(returned, "counter_value"), "text"));
            assertEquals(new HashSet<>(java.util.Arrays.asList("type_text", "click", "back")), confirmedTools);
            assertTrue("Every accepted input/back operation needs its own approval", approvals.get() >= 3);
            assertEquals(3, device.control.getDispatchAttempts());
            screenshots.capture("continuous-navigation-retains-input-and-back-approval");
        }
    }

    @Test public void rejectingInputCannotBeBypassedWithAnyNavigationTool() throws Exception {
        try (DeviceHarness device = new DeviceHarness(); PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = navigationTools(device, (description, control, epoch) -> {
                approvals.incrementAndGet(); return false;
            });
            JsonObject before = fixture(tools, device);
            assertError(tools.execute("click", nodeArguments(before, "increment"), device.control), "USER_DENIED");
            assertError(tools.execute("launch_app", launchArguments(), device.control), "PERMISSION_DENIED");
            assertError(tools.execute("scroll", scrollArguments(before, "forward"), device.control), "PERMISSION_DENIED");
            assertError(tools.execute("home", revisionArguments(before), device.control), "PERMISSION_DENIED");
            assertEquals(1, approvals.get());
            assertEquals(0, device.control.getDispatchAttempts());
            assertNoDispatch(trace);
            JsonObject unchanged = fixture(tools, device);
            assertEquals(before.get("windowId"), unchanged.get("windowId"));
            assertEquals("Count: 0", string(node(unchanged, "counter_value"), "text"));
            screenshots.capture("continuous-navigation-denial-cannot-switch-tools");
        }
    }

    @Test public void staleObservationsAndOldEpochsCannotNavigateAfterPauseAndResume() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AndroidDeviceTools tools = navigationTools(device, (description, control, epoch) -> {
                fail("Navigation safety checks must not be converted into a new approval"); return false;
            });
            JsonObject before = fixture(tools, device);
            JsonObject oldScroll = scrollArguments(before, "forward");
            Set<String> originalRows = rows(before);
            plan(device, tools, "scroll", ContinuousNavigationInstrumentedTest::isFixture,
                    data -> scrollArguments(data, "forward"));
            JsonObject moved = awaitObservation(tools, device.control, data -> hasNewRow(data, originalRows));
            long dispatches = device.control.getDispatchAttempts();
            assertError(tools.execute("scroll", oldScroll, device.control), "STALE_OBSERVATION");
            long oldEpoch = device.control.getExecutionEpoch();
            JsonObject beforePause = scrollArguments(moved, "backward");
            device.control.pause();
            assertPaused(() -> tools.execute("launch_app", launchArguments(), device.control, oldEpoch));
            device.control.resume();
            assertNotEquals(oldEpoch, device.control.getExecutionEpoch());
            assertPaused(() -> tools.execute("launch_app", launchArguments(), device.control, oldEpoch));
            assertPaused(() -> tools.execute("scroll", beforePause, device.control, oldEpoch));
            assertError(tools.execute("scroll", beforePause, device.control), "STALE_OBSERVATION");
            assertEquals(dispatches, device.control.getDispatchAttempts());
            JsonObject unchanged = fixture(tools, device);
            assertEquals(moved.get("windowId"), unchanged.get("windowId"));
            assertEquals(rows(moved), rows(unchanged));
            // Resume permits only a fresh plan; all old requests above remain unsubmitted.
            plan(device, tools, "scroll", ContinuousNavigationInstrumentedTest::isFixture,
                    data -> scrollArguments(data, "backward"));
            awaitObservation(tools, device.control, data -> hasText(data, "Count: 0"));
            assertEquals(dispatches + 1, device.control.getDispatchAttempts());
            screenshots.capture("continuous-navigation-resume-requires-fresh-plan");
        }
    }

    @Test public void unknownWindowsStillPauseEveryAutomaticNavigationToolBeforeDispatch() throws Exception {
        try (DeviceHarness device = new DeviceHarness(); PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AndroidDeviceTools tools = navigationTools(device, (description, control, epoch) -> {
                fail("Occlusion must stop before an approval or automatic navigation"); return false;
            });
            JsonObject target = node(fixture(tools, device), "increment");
            int left = target.getAsJsonArray("bounds").get(0).getAsInt();
            int top = target.getAsJsonArray("bounds").get(1).getAsInt();
            int right = target.getAsJsonArray("bounds").get(2).getAsInt();
            int bottom = target.getAsJsonArray("bounds").get(3).getAsInt();
            WindowManager manager = (WindowManager) device.service.getSystemService(Context.WINDOW_SERVICE);
            assertNotNull(manager);
            TextView blocker = new TextView(device.service);
            device.instrumentation.runOnMainSync(() -> {
                blocker.setText("Unrecognized navigation obstruction");
                blocker.setBackgroundColor(Color.rgb(80, 30, 30));
                blocker.setClickable(true);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(right - left, bottom - top,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT; params.x = left; params.y = top;
                params.setTitle("Fixture navigation obstruction");
                manager.addView(blocker, params);
            });
            try {
                device.awaitUiIdle();
                for (String name : new String[]{"scroll", "home", "launch_app"}) {
                    JsonObject blocked = awaitObservation(tools, device.control,
                            data -> data.get("hasOcclusion").getAsBoolean()
                                    && node(data, "fixture_scroll").get("obscured").getAsBoolean());
                    JsonObject args = "launch_app".equals(name) ? launchArguments()
                            : "scroll".equals(name) ? scrollArguments(blocked, "forward") : revisionArguments(blocked);
                    assertPaused(() -> tools.execute(name, args, device.control));
                    assertTrue(device.control.isPaused());
                    assertEquals(0, device.control.getDispatchAttempts());
                    device.control.resume();
                }
                assertNoDispatch(trace);
                screenshots.capture("continuous-navigation-unknown-window-still-blocks");
            } finally { device.instrumentation.runOnMainSync(() -> manager.removeViewImmediate(blocker)); }
            if (device.control.isPaused()) device.control.resume();
            assertEquals("Count: 0", string(node(fixture(tools, device), "counter_value"), "text"));
        }
    }

    @Test public void realHttpNavigationUsesTheSavedModeAndObservesEachAcceptedWriteWithoutApproval() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.CONTINUOUS_NAVIGATION,
                     defaultHomePackage(device));
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server, Mode.CONTINUOUS_NAVIGATION);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AgentController controller = AgentController.get(device.context);
            try {
                device.instrumentation.runOnMainSync(() -> controller.start(settings.model,
                        "Go home, then reopen the authorized fixture. Do not change any fixture data.",
                        Collections.singleton(FIXTURE), Mode.CONTINUOUS_NAVIGATION, settings.lease));
                assertTrue(controller.getState().running);
                long deadline = SystemClock.elapsedRealtime() + 30_000;
                while (controller.getState().running && SystemClock.elapsedRealtime() < deadline) {
                    server.assertHealthy();
                    AgentController.State state = controller.getState();
                    assertTrue("The saved navigation mode was not honored: " + state.approval, state.approvalId.isEmpty());
                    SystemClock.sleep(25);
                }
                assertFalse("Controller did not finish: " + controller.getState().status, controller.getState().running);
                server.assertHealthy();
                assertEquals("The server did not receive both accepted navigation results", 2, server.acceptedWriteCount());
                assertTrue(server.writePlanCount() <= 2 + LocalControllerModelServer.MAX_WRITE_REPLANS);
                assertTrue("Model report requires the observed result of every prior write", server.finalStateObserved());
                assertEquals("HOME must be the actual resolved default launcher", server.expectedHomePackage(), server.observedHomePackage());
                assertTrue(server.requestCount() >= 3 && server.requestCount() <= LocalControllerModelServer.MAX_SCENARIO_REQUESTS);
                assertObservationsBetweenDispatches(trace, 2);
                assertEquals("Count: 0", actualFixtureText(device, "counter_value"));
                assertEquals("Saved: empty", actualFixtureText(device, "saved_value"));
                assertEquals("执行已结束 · 模型报告待核对", controller.getState().status);
                screenshots.capture("continuous-navigation-http-unconfirmed-observed-result");
            } catch (Exception | AssertionError failure) {
                // Preserve the running/stopped state and real windows before cleanup
                // removes the overlay or changes the controller generation.
                AssertionError diagnostic = new AssertionError("Real HTTP navigation failed: " + failure.getMessage()
                        + "\n" + navigationDiagnostics(controller, server, trace), failure);
                try { screenshots.capture("continuous-navigation-http-failure-before-cleanup"); }
                catch (Throwable captureFailure) { diagnostic.addSuppressed(captureFailure); }
                throw diagnostic;
            } finally {
                device.instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
                device.instrumentation.waitForIdleSync();
            }
        }
    }

    private static String navigationDiagnostics(AgentController controller, LocalControllerModelServer server,
                                                PerformanceTrace.Session trace) {
        AgentController.State state = controller.getState();
        StringBuilder text = new StringBuilder("Controller{running=").append(state.running)
                .append(",paused=").append(state.paused).append(",pending=").append(!state.approvalId.isEmpty())
                .append(",status=").append(bounded(state.status, 200))
                .append(",summary=").append(bounded(state.summary, 1_000)).append("}\nHTTP{requests=")
                .append(server.requestCount()).append(",writePlans=").append(server.writePlanCount())
                .append(",acceptedResults=").append(server.acceptedWriteCount())
                .append(",finalObserved=").append(server.finalStateObserved())
                .append(",expectedHome=").append(bounded(server.expectedHomePackage(), 180))
                .append(",observedHome=").append(bounded(server.observedHomePackage(), 180)).append('}');
        for (int index = Math.max(0, state.log.size() - 12); index < state.log.size(); index++) {
            text.append("\nlog: ").append(bounded(state.log.get(index), 240));
        }
        java.util.Map<PerformanceTrace.Stage, Integer> counts = new java.util.EnumMap<>(PerformanceTrace.Stage.class);
        List<PerformanceTrace.Event> important = new java.util.ArrayList<>();
        for (PerformanceTrace.Event event : trace.events()) {
            counts.put(event.stage, counts.getOrDefault(event.stage, 0) + 1);
            if (event.stage.name().startsWith("SNAPSHOT_") || event.stage == PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT
                    || event.stage == PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED) important.add(event);
        }
        text.append("\ntrace{overwritten=").append(trace.overwrittenCount()).append(",counts=").append(counts).append('}');
        for (int index = Math.max(0, important.size() - 20); index < important.size(); index++) {
            PerformanceTrace.Event event = important.get(index);
            text.append("\ntrace: ").append(event.stage).append(" offsetMs=")
                    .append((event.startNanos - trace.startedNanos) / 1_000_000)
                    .append(" durationMs=").append(event.durationNanos / 1_000_000)
                    .append(" epoch=").append(event.epoch).append(" nodes=").append(event.nodeCount)
                    .append(" truncated=").append(event.truncated).append(" main=").append(event.mainThread)
                    .append(" dispatchAttempts=").append(event.dispatchAttempts);
        }
        try { text.append('\n').append(bounded(windowDiagnostics(), 12_000)); }
        catch (RuntimeException unavailable) { text.append("\nWindow diagnostics unavailable: ").append(unavailable.getClass().getSimpleName()); }
        return text.toString();
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "null";
        return value.length() <= limit ? value : value.substring(0, limit) + "[truncated]";
    }

    @Test public void navigationModeCannotEscalateAConfigurationSavedForConfirmation() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.CONTINUOUS_NAVIGATION,
                     defaultHomePackage(device));
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server, Mode.CONFIRM_ALL);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            AgentController controller = AgentController.get(device.context);
            try {
                assertEquals(Mode.CONFIRM_ALL, new SettingsStore(device.context).load().executionMode);
                assertTrue(SettingsStore.isCurrent(settings.lease));
                device.instrumentation.runOnMainSync(() -> controller.start(settings.model,
                        "This synthetic request must not upgrade the saved confirmation mode.",
                        Collections.singleton(FIXTURE), Mode.CONTINUOUS_NAVIGATION, settings.lease));
                long deadline = SystemClock.elapsedRealtime() + 10_000;
                while (controller.getState().running && SystemClock.elapsedRealtime() < deadline) {
                    server.assertHealthy();
                    assertTrue(controller.getState().approvalId.isEmpty());
                    assertEquals(0, server.requestCount());
                    SystemClock.sleep(25);
                }
                AgentController.State rejected = controller.getState();
                assertFalse("Configuration mismatch was not rejected", rejected.running);
                // The public State carries the CONFIGURATION_INVALID branch's summary,
                // not its internal TaskResult code. Assert that distinct failure exactly.
                assertEquals("执行失败", rejected.status);
                assertEquals("配置或授权已改变，请重新保存配置并选择允许的应用。", rejected.summary);
                assertTrue(rejected.approvalId.isEmpty());
                assertTrue(SettingsStore.isCurrent(settings.lease));
                assertEquals(Mode.CONFIRM_ALL, new SettingsStore(device.context).load().executionMode);
                assertFalse("Rejected mode reached the model", server.receivedAnotherRequest(0, 250));
                assertEquals(0, server.requestCount());
                assertEquals(0, server.writePlanCount());
                assertEquals(0, server.acceptedWriteCount());
                server.assertHealthy();
                assertNoDispatch(trace);
                for (PerformanceTrace.Event event : trace.events()) {
                    assertNotEquals("A mismatched mode started observing the device", PerformanceTrace.Stage.SNAPSHOT_OBSERVE, event.stage);
                }
                assertEquals("Count: 0", actualFixtureText(device, "counter_value"));
                screenshots.capture("continuous-navigation-saved-mode-mismatch-rejected");
            } finally {
                device.instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
                device.instrumentation.waitForIdleSync();
            }
        }
    }

    private static String defaultHomePackage(DeviceHarness device) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = device.context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull("The dedicated test device needs an actual default HOME", resolved);
        assertNotNull("Default HOME did not resolve to an activity", resolved.activityInfo);
        String application = resolved.activityInfo.packageName;
        assertNotNull(application);
        assertFalse(application.isEmpty());
        assertNotEquals(FIXTURE, application);
        assertNotEquals(device.context.getPackageName(), application);
        return application;
    }

    /** Controller completion removes its controls, so verify the fixture independently of tool readiness. */
    private static String actualFixtureText(DeviceHarness device, String id) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
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
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Independent fixture evidence is unavailable: " + id + "\n" + windowDiagnostics());
    }

    private static AndroidDeviceTools navigationTools(DeviceHarness device, AndroidDeviceTools.ApprovalGate gate) throws Exception {
        return AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                Mode.CONTINUOUS_NAVIGATION, () -> true, gate);
    }

    private static JsonObject fixture(AndroidDeviceTools tools, DeviceHarness device) throws Exception {
        return awaitObservation(tools, device.control, ContinuousNavigationInstrumentedTest::isFixture);
    }
    private static boolean isFixture(JsonObject data) { return FIXTURE.equals(string(data, "packageName")); }
    private static JsonObject launchArguments() {
        JsonObject args = new JsonObject(); args.addProperty("packageName", FIXTURE); return args;
    }
    private static JsonObject revisionArguments(JsonObject data) {
        JsonObject args = new JsonObject(); args.addProperty("expectedRevision", string(data, "revision")); return args;
    }
    private static JsonObject scrollArguments(JsonObject data, String direction) {
        JsonObject args = nodeArguments(data, "fixture_scroll"); args.addProperty("direction", direction); return args;
    }
    private static Set<String> rows(JsonObject data) {
        Set<String> result = new HashSet<>();
        for (JsonElement value : data.getAsJsonArray("nodes")) {
            String text = string(value.getAsJsonObject(), "text");
            if (text.startsWith("Scrollable test row ")) result.add(text);
        }
        return result;
    }
    private static boolean hasNewRow(JsonObject data, Set<String> previous) {
        for (String row : rows(data)) if (!previous.contains(row)) return true;
        return false;
    }
    private static boolean hasText(JsonObject data, String text) {
        for (JsonElement value : data.getAsJsonArray("nodes")) if (text.equals(string(value.getAsJsonObject(), "text"))) return true;
        return false;
    }
    private static void assertError(JsonObject result, String code) {
        assertTrue(result.toString(), explicitBoolean(result, "ok", false));
        assertEquals(result.toString(), code, string(result, "code"));
        assertTrue(result.toString(), explicitBoolean(result, "submitted", false));
        assertTrue(result.toString(), explicitBoolean(result, "dispatchSubmitted", false));
    }
    private interface Request { JsonObject run() throws Exception; }
    private static void assertPaused(Request request) throws Exception {
        try { request.run(); fail("Old or obstructed navigation was not interrupted"); }
        catch (TaskControlException expected) { assertEquals("TASK_PAUSED", expected.getCode()); }
    }
    private static void assertNoDispatch(PerformanceTrace.Session trace) {
        assertEquals(0, trace.overwrittenCount());
        for (PerformanceTrace.Event event : trace.events()) assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
    }
    private static void assertObservationsBetweenDispatches(PerformanceTrace.Session trace, int expectedDispatches) {
        assertEquals(0, trace.overwrittenCount());
        java.util.List<PerformanceTrace.Event> dispatches = new java.util.ArrayList<>();
        for (PerformanceTrace.Event event : trace.events()) {
            if (event.stage == PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT) dispatches.add(event);
        }
        dispatches.sort(java.util.Comparator.comparingLong(event -> event.startNanos));
        assertEquals(expectedDispatches, dispatches.size());
        for (int index = 0; index < dispatches.size(); index++) {
            long after = dispatches.get(index).startNanos;
            long before = index + 1 < dispatches.size() ? dispatches.get(index + 1).startNanos : Long.MAX_VALUE;
            boolean observed = false;
            for (PerformanceTrace.Event event : trace.events()) {
                if (event.stage == PerformanceTrace.Stage.SNAPSHOT_OBSERVE && event.startNanos > after
                        && event.startNanos + event.durationNanos < before && event.nodeCount >= 0) observed = true;
            }
            assertTrue("An accepted navigation was not followed by a completed device observation", observed);
        }
    }
    private static boolean explicitBoolean(JsonObject value, String name, boolean expected) {
        return value.has(name) && value.get(name).isJsonPrimitive() && value.getAsJsonPrimitive(name).isBoolean()
                && value.get(name).getAsBoolean() == expected;
    }

    /** Only an explicitly unsubmitted stale result permits a bounded new plan, never replay of a write. */
    private static JsonObject plan(DeviceHarness device, AndroidDeviceTools tools, String name,
                                   Predicate<JsonObject> expectedPage, Function<JsonObject, JsonObject> arguments) throws Exception {
        long epoch = device.control.getExecutionEpoch();
        for (int attempt = 0; attempt <= 3; attempt++) {
            device.awaitUiIdle();
            device.control.checkExecutionEpoch(epoch);
            JsonObject observed = awaitObservation(tools, device.control, expectedPage);
            long dispatches = device.control.getDispatchAttempts();
            JsonObject result = tools.execute(name, arguments.apply(observed), device.control, epoch);
            if (explicitBoolean(result, "ok", true)) {
                assertTrue(result.toString(), explicitBoolean(result, "dispatchSubmitted", true));
                assertTrue(result.toString(), explicitBoolean(result, "dispatchAccepted", true));
                return result;
            }
            assertError(result, "STALE_OBSERVATION");
            for (String flag : new String[]{"accepted", "dispatchAccepted"}) {
                if (result.has(flag)) assertTrue(result.toString(), explicitBoolean(result, flag, false));
            }
            assertEquals("An unsubmitted plan reached dispatch", dispatches, device.control.getDispatchAttempts());
            if (attempt == 3) fail("Unsubmitted stale plans exceeded the bounded replan allowance: " + result);
        }
        throw new AssertionError("Unreachable planning state");
    }
}
