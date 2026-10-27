package dev.agentos.app;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real Android controller/network/approval/device loop; model replies are deterministic test data. */
@RunWith(AndroidJUnit4.class)
public final class AgentControllerInstrumentedTest {
    public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    @Rule public final TestRule evidence = (base, description) ->
            ("scriptReplansOnlyDefinitelyUnsubmittedWritesAndNeverRepeatsAcceptedOrUnknownWrites".equals(description.getMethodName())
                    ? new ScreenshotEvidence("non_visual_synthetic_planner_regression") : screenshots).apply(base, description);

    @Test public void approvedHttpPlansTypeSaveAndClickWithFreshDeviceObservations() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            Set<String> approved = new HashSet<>();
            try {
                Rect input = inputBounds(device);
                assertFalse("The input must be below the compact controls", controlsIntersect(device, input));
                start(device, controller, settings, "Use the synthetic fixture to save the sample text and increment its counter once.");
                long deadline = SystemClock.elapsedRealtime() + 30_000;
                while (controller.getState().running && SystemClock.elapsedRealtime() < deadline) {
                    server.assertHealthy();
                    AgentController.State state = controller.getState();
                    if (!state.approvalId.isEmpty() && approved.add(state.approvalId)) {
                        assertFalse(state.approval.isEmpty());
                        ExpandedApproval shown = awaitExpandedApproval(device, controller, state, deadline);
                        if (approved.size() == 1) assertTrue("The actual expanded approval must cover the original input",
                                Rect.intersects(input, shown.identity.copyBounds()));
                        screenshots.capture("approval-" + approved.size());
                        String current = state.approvalId;
                        safeMain(device, () -> controller.approve("unrelated-" + current, true));
                        assertEquals("An unrelated confirmation must not replace the pending request", current, controller.getState().approvalId);
                        // Let the actual expanded window remain visible while the user
                        // reads it. Do not accelerate approval past WMS publication.
                        long thinkingUntil = SystemClock.elapsedRealtime() + 1_200;
                        assertTrue("The original task deadline must cover the review pause", thinkingUntil < deadline);
                        while (SystemClock.elapsedRealtime() < thinkingUntil) {
                            server.assertHealthy();
                            assertEquals(current, controller.getState().approvalId);
                            Thread.sleep(25);
                        }
                        safeMain(device, () -> {
                            assertTrue("Review must not extend the original task deadline", SystemClock.elapsedRealtime() < deadline);
                            assertEquals(current, controller.getState().approvalId);
                            assertTrue(device.service.isOwnedControlWindowCurrent(shown.identity));
                            assertSame(shown.button, controlField(device.service, "approveButton"));
                            assertEquals(state.approval, controlField(device.service, "currentApproval"));
                            assertEquals(controlField(device.service, "callbackGeneration"),
                                    controlField(device.service, "displayedCallbackGeneration"));
                            assertTrue(shown.button.isAttachedToWindow() && shown.button.isShown() && shown.button.isEnabled());
                            assertTrue("Approve through the actual displayed Button", shown.button.performClick());
                        });
                    }
                    SystemClock.sleep(25);
                }
                assertFalse("Controller did not finish: " + controller.getState().status, controller.getState().running);
                server.assertHealthy();
                assertEquals("Exactly three writes must be accepted by the actual executor", 3, server.acceptedWriteCount());
                assertTrue("Each accepted write needs a distinct confirmation", approved.size() >= 3);
                assertTrue("Safety checks may reject a plan before or after confirmation", approved.size() <= server.writePlanCount());
                assertTrue(server.writePlanCount() <= LocalControllerModelServer.MAX_WRITE_PLANS);
                assertTrue("Final result must come from actual device observations", server.finalStateObserved());
                assertTrue(server.requestCount() >= 4 && server.requestCount() <= LocalControllerModelServer.MAX_SCENARIO_REQUESTS);
                assertEquals("Saved: " + LocalControllerModelServer.TEXT, fixtureText(device, "saved_value"));
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
                assertEquals("执行已结束 · 模型报告待核对", controller.getState().status);
                assertTrue(controller.getState().summary.contains("模型报告"));
                screenshots.capture("verified-fixture-result");
                for (String oldApproval : approved) controller.approve(oldApproval, true);
                assertFalse("Finished task sent another model request", server.receivedAnotherRequest(server.requestCount(), 350));
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
            } catch (AssertionError failure) {
                try { screenshots.capture("failure-before-controller-cleanup"); }
                catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
                try { failure.addSuppressed(new AssertionError(DeviceHarness.windowDiagnostics())); }
                catch (Throwable diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
                // Instrumentation may omit suppressed exceptions from JUnit XML.
                // Put only fixed classifications and safe protocol fields in its message.
                throw new AssertionError(controllerFailureDiagnostic(controller, server, approved.size()), failure);
            } finally { clear(device, controller); }
        }
    }

    @Test public void scriptReplansOnlyDefinitelyUnsubmittedWritesAndNeverRepeatsAcceptedOrUnknownWrites() throws Exception {
        JsonObject accepted = JsonParser.parseString("{\"ok\":true,\"submitted\":true,\"accepted\":true,\"dispatchSubmitted\":true,\"dispatchAccepted\":true}").getAsJsonObject();
        JsonObject stale = JsonParser.parseString("{\"ok\":false,\"code\":\"STALE_OBSERVATION\",\"submitted\":false,\"dispatchSubmitted\":false,\"dispatchAccepted\":false}").getAsJsonObject();
        try (LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT)) {
            JsonObject first = singlePlan(server.respond(planningRequest(syntheticObservation("r1", false, false, 0), null, null), 1));
            JsonObject retry = singlePlan(server.respond(planningRequest(syntheticObservation("r2", false, false, 0), planId(first), stale), 2));
            assertNotEquals(planId(first), planId(retry));
            assertEquals("type_text", retry.getAsJsonObject("function").get("name").getAsString());
            JsonObject retryArgs = JsonParser.parseString(retry.getAsJsonObject("function").get("arguments").getAsString()).getAsJsonObject();
            assertEquals("r2", retryArgs.get("expectedRevision").getAsString());
            assertEquals("edit_value-r2", retryArgs.get("nodeId").getAsString());
            assertEquals(2, server.writePlanCount());
            assertEquals(0, server.acceptedWriteCount());
            String diagnostic = server.safeDiagnostics();
            assertTrue(diagnostic.contains("lastReceivedRequestRound=2"));
            assertTrue(diagnostic.contains("scope=received-requests-only"));
            assertTrue(diagnostic.contains("finalExecutionResultMayBeAbsent=true"));
            assertTrue(diagnostic.contains("code=STALE_OBSERVATION"));
            assertTrue(diagnostic.contains("dispatchSubmitted=false,dispatchAccepted=false"));
            assertFalse(diagnostic.contains(LocalControllerModelServer.SYNTHETIC_KEY));

            // An accepted action can take time to appear; an unchanged observation
            // permits bounded reads only, never another text or counter write.
            assertReadOnlyPlans(server.respond(planningRequest(syntheticObservation("r2", false, false, 0), planId(retry), accepted), 3));
            assertEquals(2, server.writePlanCount());
            JsonObject save = singlePlan(server.respond(planningRequest(syntheticObservation("r3", true, false, 0), null, null), 4));
            JsonObject increment = singlePlan(server.respond(planningRequest(syntheticObservation("r4", true, true, 0), planId(save), accepted), 5));
            assertReadOnlyPlans(server.respond(planningRequest(syntheticObservation("r4", true, true, 0), planId(increment), accepted), 6));
            assertEquals(4, server.writePlanCount());
            JsonObject finalReply = server.respond(planningRequest(syntheticObservation("r5", true, true, 1), null, null), 7);
            assertFalse(finalReply.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").has("tool_calls"));
            assertTrue(server.finalStateObserved());
            assertEquals(3, server.acceptedWriteCount());
            assertEquals(4, server.writePlanCount());
        }

        // Missing, unknown, contradictory or other failed results must not be
        // treated as a safe stale rejection, even when the visible counter is 0.
        String[] unsafeResults = {
                "{\"ok\":false,\"code\":\"OUTCOME_UNKNOWN\",\"submitted\":null}",
                "{\"ok\":false,\"code\":\"STALE_OBSERVATION\"}",
                "{\"ok\":false,\"code\":\"STALE_OBSERVATION\",\"submitted\":false,\"dispatchSubmitted\":true}",
                "{\"ok\":false,\"code\":\"STALE_OBSERVATION\",\"submitted\":false,\"accepted\":true}",
                "{\"ok\":false,\"code\":\"PERMISSION_DENIED\",\"submitted\":false}",
                null
        };
        for (String unsafe : unsafeResults) {
            try (LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT)) {
                JsonObject first = singlePlan(server.respond(planningRequest(syntheticObservation("r1", false, false, 0), null, null), 1));
                JsonObject result = unsafe == null ? null : JsonParser.parseString(unsafe).getAsJsonObject();
                assertThrows(IOException.class, () -> server.respond(planningRequest(syntheticObservation("r2", false, false, 0), planId(first), result), 2));
                assertEquals(1, server.writePlanCount());
                assertEquals(0, server.acceptedWriteCount());
                assertFalse(server.finalStateObserved());
            }
        }

        for (LocalControllerModelServer.Scenario scenario : new LocalControllerModelServer.Scenario[]{
                LocalControllerModelServer.Scenario.SAVE_AND_COUNT, LocalControllerModelServer.Scenario.REJECT_CLICK}) {
            try (LocalControllerModelServer server = new LocalControllerModelServer(scenario)) {
                JsonObject current = singlePlan(server.respond(planningRequest(syntheticObservation("r1", false, false, 0), null, null), 1));
                Set<String> callIds = new HashSet<>(); callIds.add(planId(current));
                for (int retry = 1; retry <= LocalControllerModelServer.MAX_WRITE_REPLANS; retry++) {
                    current = singlePlan(server.respond(planningRequest(syntheticObservation("r" + (retry + 1), false, false, 0), planId(current), stale), retry + 1));
                    assertTrue(callIds.add(planId(current)));
                }
                String exhausted = planId(current);
                assertThrows(IOException.class, () -> server.respond(planningRequest(syntheticObservation("latest", false, false, 0), exhausted, stale), 5));
                assertEquals(1 + LocalControllerModelServer.MAX_WRITE_REPLANS, server.writePlanCount());
                assertEquals(0, server.acceptedWriteCount());
            }
        }

        String[] rejectionResults = {
                accepted.toString(),
                "{\"ok\":false,\"code\":\"USER_DENIED\",\"submitted\":false}",
                "{\"ok\":false,\"code\":\"OUTCOME_UNKNOWN\",\"submitted\":null}",
                "{\"ok\":false,\"code\":\"STALE_OBSERVATION\"}",
                "{\"ok\":false,\"code\":\"STALE_OBSERVATION\",\"submitted\":false,\"dispatchSubmitted\":true}",
                null
        };
        for (String rejected : rejectionResults) {
            try (LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK)) {
                JsonObject first = singlePlan(server.respond(planningRequest(syntheticObservation("r1", false, false, 0), null, null), 1));
                JsonObject retry = singlePlan(server.respond(planningRequest(syntheticObservation("r2", false, false, 0), planId(first), stale), 2));
                assertNotEquals(planId(first), planId(retry));
                assertEquals("click", retry.getAsJsonObject("function").get("name").getAsString());
                JsonObject arguments = JsonParser.parseString(retry.getAsJsonObject("function").get("arguments").getAsString()).getAsJsonObject();
                assertEquals("r2", arguments.get("expectedRevision").getAsString());
                assertEquals("increment-r2", arguments.get("nodeId").getAsString());
                JsonObject result = rejected == null ? null : JsonParser.parseString(rejected).getAsJsonObject();
                assertThrows(IOException.class, () -> server.respond(planningRequest(syntheticObservation("r3", false, false, 0), planId(retry), result), 3));
                assertEquals(2, server.writePlanCount());
                assertFalse(server.finalStateObserved());
            }
        }

        assertEquals("FAILED", controllerStatusClass("执行失败"));
        assertEquals("OTHER", controllerStatusClass(LocalControllerModelServer.SYNTHETIC_KEY));
        assertEquals("THREE_INVALID_TURNS", controllerSummaryClass("模型连续三轮未提供可执行的有效操作，任务已停止。"));
        assertEquals("OTHER", controllerSummaryClass(LocalControllerModelServer.SYNTHETIC_KEY));
        try (LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT)) {
            JsonObject first = singlePlan(server.respond(planningRequest(syntheticObservation("r1", false, false, 0), null, null), 1));
            JsonObject observation = syntheticObservation("r2", false, false, 0);
            observation.addProperty("windowId", LocalControllerModelServer.SYNTHETIC_KEY);
            observation.addProperty("focused", LocalControllerModelServer.SYNTHETIC_KEY);
            JsonObject malformed = new JsonObject();
            malformed.addProperty("ok", false);
            malformed.addProperty("code", LocalControllerModelServer.SYNTHETIC_KEY);
            malformed.addProperty("submitted", LocalControllerModelServer.SYNTHETIC_KEY);
            malformed.add("dispatchAccepted", new JsonObject());
            assertThrows(IOException.class, () -> server.respond(planningRequest(observation, planId(first), malformed), 2));
            String diagnostic = server.safeDiagnostics();
            assertTrue(diagnostic.contains("windowId=invalid focused=invalid"));
            assertTrue(diagnostic.contains("code=other,submitted=invalid"));
            assertTrue(diagnostic.contains("dispatchAccepted=invalid"));
            assertFalse("Diagnostic metadata must not echo arbitrary strings", diagnostic.contains(LocalControllerModelServer.SYNTHETIC_KEY));
            assertTrue(diagnostic.length() <= 4009);
        }
    }

    private static final class ExpandedApproval {
        final AgentAccessibilityService.OwnedControlWindowIdentity identity;
        final Button button;
        ExpandedApproval(AgentAccessibilityService.OwnedControlWindowIdentity identity, Button button) {
            this.identity = identity; this.button = button;
        }
    }

    private static ExpandedApproval awaitExpandedApproval(DeviceHarness device, AgentController controller,
                                                          AgentController.State expected, long taskDeadline) throws Exception {
        long deadline = Math.min(taskDeadline, SystemClock.elapsedRealtime() + 5_000);
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<ExpandedApproval> candidate = new AtomicReference<>();
            safeMain(device, () -> {
                assertEquals("The pending approval must remain the same", expected.approvalId, controller.getState().approvalId);
                AgentAccessibilityService.OwnedControlWindowIdentity identity = device.service.getOwnedControlWindowIdentity();
                Button button = (Button) controlField(device.service, "approveButton");
                if (identity == null || !device.service.isOwnedControlWindowCurrent(identity)
                        || button == null || !button.isAttachedToWindow() || !button.isShown() || !button.isEnabled()
                        || !expected.approval.equals(controlField(device.service, "currentApproval"))
                        || !controlField(device.service, "callbackGeneration").equals(
                                controlField(device.service, "displayedCallbackGeneration"))) return;
                if (!identity.copyBounds().equals(device.service.controlBounds())) return;
                candidate.set(new ExpandedApproval(identity, button));
            });
            ExpandedApproval actual = candidate.get();
            if (actual != null && registeredExpandedWindow(device, actual.identity)
                    && device.service.isOwnedControlWindowCurrent(actual.identity)
                    && SystemClock.elapsedRealtime() < deadline) return actual;
            Thread.sleep(25);
        }
        throw new AssertionError("Displayed approval did not reach its actual current accessibility window within the original deadline");
    }

    private static boolean registeredExpandedWindow(DeviceHarness device,
            AgentAccessibilityService.OwnedControlWindowIdentity identity) {
        boolean matched = false; int count = 0;
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                if (window.getId() != identity.windowId) continue;
                count++;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || window.getDisplayId() != identity.displayId || window.isFocused()
                        || !identity.copyBounds().equals(bounds)) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    matched = root.getWindowId() == identity.windowId
                            && device.service.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
                } finally { root.recycle(); }
            }
            return count == 1 && matched;
        } finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static Object controlField(AgentAccessibilityService service, String name) {
        try {
            Field field = AgentAccessibilityService.class.getDeclaredField(name);
            field.setAccessible(true); return field.get(service);
        } catch (ReflectiveOperationException failed) { throw new AssertionError("Cannot inspect actual control field: " + name, failed); }
    }

    /** Main-thread assertions must fail the test, not terminate the instrumentation process. */
    private static void safeMain(DeviceHarness device, Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable failed) { failure.set(failed); }
        });
        Throwable failed = failure.get();
        if (failed instanceof Error) throw (Error) failed;
        if (failed instanceof RuntimeException) throw (RuntimeException) failed;
        if (failed != null) throw new AssertionError("Main-thread test action failed", failed);
    }

    private static String controllerFailureDiagnostic(AgentController controller,
                                                      LocalControllerModelServer server, int approvals) {
        // Diagnostic-only: do not extend the original 30-second execution deadline,
        // stop the task, retry a plan, or change the failing assertion into success.
        String workerState = diagnosticWorkerBarrier(controller);
        AgentController.State state = controller.getState();
        return "Controller fixture assertion failed; inspect the original cause.\n"
                + "controller={running=" + state.running + ",paused=" + state.paused
                + ",approvalPending=" + !state.approvalId.isEmpty() + ",approvals=" + approvals
                + ",status=" + controllerStatusClass(state.status)
                + ",summaryClass=" + controllerSummaryClass(state.summary)
                + ",worker=" + workerState + "}\n" + server.safeDiagnostics();
    }

    private static String diagnosticWorkerBarrier(AgentController controller) {
        if (controller.getState().running) return "not-awaited-task-running";
        Future<?> barrier = null;
        try {
            Field field = AgentController.class.getDeclaredField("worker");
            field.setAccessible(true);
            ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(controller);
            barrier = worker.submit(() -> { });
            barrier.get(2, TimeUnit.SECONDS);
            return "settled";
        } catch (TimeoutException timeout) {
            return "barrier-timeout";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "barrier-interrupted";
        } catch (Exception unavailable) {
            return "barrier-unavailable";
        } finally {
            // Cancel only our harmless queued no-op; never cancel the controller worker.
            if (barrier != null && !barrier.isDone()) barrier.cancel(false);
        }
    }

    private static String controllerStatusClass(String status) {
        if (status == null || status.isEmpty()) return "EMPTY";
        switch (status) {
            case "执行失败": return "FAILED";
            case "已停止": return "STOPPED";
            case "达到任务预算，已停止": return "BUDGET_EXCEEDED";
            case "配置已改变，任务已停止": return "CONFIGURATION_CHANGED";
            case "结果已验证": return "VERIFIED";
            case "执行已结束 · 模型报告待核对": return "MODEL_REPORT";
            case "准备就绪": return "READY";
            default: return "OTHER";
        }
    }

    private static String controllerSummaryClass(String summary) {
        if (summary == null || summary.isEmpty()) return "EMPTY";
        switch (summary) {
            case "模型连续三轮未提供可执行的有效操作，任务已停止。": return "THREE_INVALID_TURNS";
            case "操作可能已提交但结果未知；任务已停止，请手动核对，未自动重发。": return "OUTCOME_UNKNOWN";
            case "你已拒绝该动作，任务已结束，不会改用其他方式执行。": return "USER_DENIED";
            case "配置或授权已改变，请重新保存配置并选择允许的应用。": return "CONFIGURATION_INVALID";
            case "任务预算已改变，请重新保存配置后启动。": return "BUDGET_CHANGED";
            case "任务执行中断；请检查当前界面后重新开始。": return "INTERNAL_ERROR";
            default: return "OTHER";
        }
    }

    private static JsonObject syntheticObservation(String revision, boolean typed, boolean saved, int count) {
        JsonObject data = new JsonObject();
        data.addProperty("packageName", DeviceHarness.FIXTURE); data.addProperty("scoped", true); data.addProperty("revision", revision);
        JsonArray nodes = new JsonArray();
        String[][] values = {{"edit_value", typed ? LocalControllerModelServer.TEXT : ""}, {"save_value", "Save"},
                {"saved_value", saved ? "Saved: " + LocalControllerModelServer.TEXT : "Saved: empty"},
                {"increment", "Increment"}, {"counter_value", "Count: " + count}};
        for (String[] value : values) {
            JsonObject node = new JsonObject();
            node.addProperty("id", value[0] + "-" + revision); node.addProperty("viewId", DeviceHarness.FIXTURE + ":id/" + value[0]);
            node.addProperty("text", value[1]); nodes.add(node);
        }
        data.add("nodes", nodes); return data;
    }

    private static JsonObject planningRequest(JsonObject observation, String callId, JsonObject result) {
        JsonArray messages = new JsonArray();
        if (result != null) {
            JsonObject tool = new JsonObject(); tool.addProperty("role", "tool");
            tool.addProperty("tool_call_id", callId); tool.addProperty("content", result.toString()); messages.add(tool);
        }
        JsonObject observed = new JsonObject(); observed.addProperty("ok", true); observed.add("data", observation);
        JsonObject latest = new JsonObject(); latest.addProperty("role", "user");
        latest.addProperty("content", "最新本机观察（合成测试数据）：" + observed); messages.add(latest);
        JsonObject request = new JsonObject(); request.add("messages", messages); return request;
    }

    private static JsonObject singlePlan(JsonObject response) {
        JsonArray plans = response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls");
        assertEquals(1, plans.size()); return plans.get(0).getAsJsonObject();
    }

    private static String planId(JsonObject plan) { return plan.get("id").getAsString(); }

    private static void assertReadOnlyPlans(JsonObject response) {
        JsonArray plans = response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls");
        assertEquals(2, plans.size());
        for (JsonElement value : plans) {
            String name = value.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
            assertTrue("Accepted writes must never be replayed", "observe".equals(name) || "wait".equals(name));
        }
    }

    @Test public void pausingAfterConfirmationWhileLayoutIsPendingNeverDispatchesTheOldInput() throws Exception {
        verifyInterruptionBeforeConfirmedLayout(false);
    }

    @Test public void stoppingAfterConfirmationWhileLayoutIsPendingRejectsLateApprovalAndNeverWrites() throws Exception {
        verifyInterruptionBeforeConfirmedLayout(true);
    }

    private void verifyInterruptionBeforeConfirmedLayout(boolean stop) throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            CountDownLatch releaseMain = new CountDownLatch(1);
            AtomicBoolean barrierExpired = new AtomicBoolean();
            try {
                Rect input = inputBounds(device);
                assertFalse(controlsIntersect(device, input));
                start(device, controller, settings, "Prepare synthetic text input and wait for confirmation.");
                AgentController.State pending = awaitApproval(controller, server);
                int requestBaseline = server.requestCount();
                assertTrue(requestBaseline >= 1 && requestBaseline <= 1 + LocalControllerModelServer.MAX_WRITE_REPLANS);
                awaitExpandedControlsCoverInput(device, input);
                CountDownLatch mainBlocked = new CountDownLatch(1);
                new Handler(Looper.getMainLooper()).post(() -> {
                    mainBlocked.countDown();
                    try { if (!releaseMain.await(3, TimeUnit.SECONDS)) barrierExpired.set(true); }
                    catch (InterruptedException interrupted) { barrierExpired.set(true); Thread.currentThread().interrupt(); }
                });
                try {
                    assertTrue("Test UI barrier was not reached", mainBlocked.await(1, TimeUnit.SECONDS));
                    // These public controller methods are thread-safe. Keep the UI frame
                    // pending so confirmation cannot race past the cancellation check.
                    controller.approve(pending.approvalId, true);
                    long deadline = SystemClock.elapsedRealtime() + 1_500;
                    while (!controller.getState().approvalId.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(10);
                    }
                    assertTrue("Confirmation gate did not return", controller.getState().approvalId.isEmpty());
                    assertTrue(controller.getState().running);
                    if (stop) {
                        controller.stop();
                        assertFalse(controller.getState().running);
                        assertTrue(controller.getState().approvalId.isEmpty());
                        // A late old confirmation and a resume click must not revive
                        // the stopped generation while the queued UI work is blocked.
                        controller.approve(pending.approvalId, true);
                        controller.pauseResume();
                        assertFalse(controller.getState().running);
                        assertEquals("已停止", controller.getState().status);
                    } else {
                        controller.pauseResume();
                        assertTrue(controller.getState().paused);
                    }
                } finally { releaseMain.countDown(); }
                device.instrumentation.waitForIdleSync();
                assertFalse("The artificial test UI barrier reached its hard limit", barrierExpired.get());
                assertNotEquals(LocalControllerModelServer.TEXT, fixtureText(device, "edit_value"));
                assertEquals("Saved: empty", fixtureText(device, "saved_value"));
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                assertFalse("Interrupted task requested a new plan", server.receivedAnotherRequest(requestBaseline, 350));
                assertEquals(requestBaseline, server.requestCount());
                server.assertHealthy();
                if (stop) {
                    controller.approve(pending.approvalId, true);
                    assertFalse(controller.getState().running);
                    assertEquals("已停止", controller.getState().status);
                }
                screenshots.capture((stop ? "stopped" : "paused") + "-before-confirmed-layout-dispatch");
            } finally {
                releaseMain.countDown();
                clear(device, controller);
            }
        }
    }

    @Test public void rejectingTheCurrentApprovalEndsTheTaskWithoutAWriteOrAnotherPlan() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            try {
                start(device, controller, settings, "Request one synthetic counter increment and wait for confirmation.");
                AgentController.State pending = awaitApproval(controller, server);
                int requestBaseline = server.requestCount();
                assertTrue(requestBaseline >= 1 && requestBaseline <= 1 + LocalControllerModelServer.MAX_WRITE_REPLANS);
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                screenshots.capture("before-rejection");
                device.instrumentation.runOnMainSync(() -> controller.approve(pending.approvalId, false));
                awaitStopped(controller, server, 10_000);
                assertEquals("执行失败", controller.getState().status);
                assertTrue(controller.getState().summary.contains("拒绝"));
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                controller.approve(pending.approvalId, true);
                assertFalse("Rejected task requested a fallback plan", server.receivedAnotherRequest(requestBaseline, 350));
                assertEquals(requestBaseline, server.requestCount());
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                server.assertHealthy();
                screenshots.capture("rejection-left-counter-unchanged");
            } finally { clear(device, controller); }
        }
    }

    @Test public void theDefaultRoundBudgetStopsBeforeRequestingTheNextWritePlan() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.EXHAUST_ROUND_BUDGET);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            try {
                start(device, controller, settings, "Observe the synthetic fixture without changing its counter.");
                awaitStopped(controller, server, 40_000);
                assertEquals("达到任务预算，已停止", controller.getState().status);
                assertEquals(TaskBudget.DEFAULT.maxModelRounds, server.requestCount());
                assertTrue(controller.getState().approvalId.isEmpty());
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                assertFalse("Budget-limited task requested its next write plan",
                        server.receivedAnotherRequest(TaskBudget.DEFAULT.maxModelRounds, 350));
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                server.assertHealthy();
                screenshots.capture("budget-ended-without-write");
            } finally { clear(device, controller); }
        }
    }

    private static void start(DeviceHarness device, AgentController controller, ControllerTestSettings settings, String task) {
        device.instrumentation.runOnMainSync(() -> controller.start(settings.model, task,
                Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, settings.lease));
        assertTrue("Controller did not enter a running state", controller.getState().running);
    }

    private static Rect inputBounds(DeviceHarness device) throws Exception {
        JsonObject input = DeviceHarness.node(device.observe(), "edit_value");
        return new Rect(input.getAsJsonArray("bounds").get(0).getAsInt(), input.getAsJsonArray("bounds").get(1).getAsInt(),
                input.getAsJsonArray("bounds").get(2).getAsInt(), input.getAsJsonArray("bounds").get(3).getAsInt());
    }

    private static boolean controlsIntersect(DeviceHarness device, Rect target) {
        boolean[] intersects = {false};
        device.instrumentation.runOnMainSync(() -> intersects[0] = Rect.intersects(target, device.service.controlBounds()));
        return intersects[0];
    }

    private static void awaitExpandedControlsCoverInput(DeviceHarness device, Rect input) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (!controlsIntersect(device, input) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
        assertTrue("Expanded confirmation must cover the same input that compact controls leave free", controlsIntersect(device, input));
    }

    private static AgentController.State awaitApproval(AgentController controller, LocalControllerModelServer server) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            server.assertHealthy();
            AgentController.State state = controller.getState();
            if (!state.approvalId.isEmpty()) return state;
            if (!state.running) fail("Task ended before confirmation: " + state.status + " / " + state.summary);
            SystemClock.sleep(25);
        }
        throw new AssertionError("Controller did not request confirmation");
    }

    private static void awaitStopped(AgentController controller, LocalControllerModelServer server, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (controller.getState().running && SystemClock.elapsedRealtime() < deadline) {
            server.assertHealthy();
            SystemClock.sleep(25);
        }
        assertFalse("Controller did not terminate: " + controller.getState().status, controller.getState().running);
        server.assertHealthy();
    }

    private static String fixtureText(DeviceHarness device, String id) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            List<AccessibilityWindowInfo> windows = device.service.getWindows();
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (!window.isFocused()) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    try {
                        if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/" + id);
                        try {
                            if (!nodes.isEmpty() && nodes.get(0).getText() != null) return nodes.get(0).getText().toString();
                        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                    } finally { root.recycle(); }
                }
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected fixture result is not visible: " + id + "\n" + DeviceHarness.windowDiagnostics());
    }

    private static void clear(DeviceHarness device, AgentController controller) {
        device.instrumentation.runOnMainSync(() -> {
            controller.stop();
            controller.clearCompletedTask();
        });
        device.instrumentation.waitForIdleSync();
    }
}
