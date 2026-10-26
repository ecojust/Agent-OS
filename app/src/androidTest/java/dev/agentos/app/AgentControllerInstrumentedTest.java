package dev.agentos.app;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
            try {
                Rect input = inputBounds(device);
                assertFalse("The input must be below the compact controls", controlsIntersect(device, input));
                start(device, controller, settings, "Use the synthetic fixture to save the sample text and increment its counter once.");
                Set<String> approved = new HashSet<>();
                long deadline = SystemClock.elapsedRealtime() + 30_000;
                while (controller.getState().running && SystemClock.elapsedRealtime() < deadline) {
                    server.assertHealthy();
                    AgentController.State state = controller.getState();
                    if (!state.approvalId.isEmpty() && approved.add(state.approvalId)) {
                        assertFalse(state.approval.isEmpty());
                        if (approved.size() == 1) awaitExpandedControlsCoverInput(device, input);
                        screenshots.capture("approval-" + approved.size());
                        String current = state.approvalId;
                        device.instrumentation.runOnMainSync(() -> controller.approve("unrelated-" + current, true));
                        assertEquals("An unrelated confirmation must not replace the pending request", current, controller.getState().approvalId);
                        device.instrumentation.runOnMainSync(() -> controller.approve(current, true));
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
                throw failure;
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
                Collections.singleton(DeviceHarness.FIXTURE), true));
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
