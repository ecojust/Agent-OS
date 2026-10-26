package dev.agentos.app;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Real Android controller/network/approval/device loop; model replies are deterministic test data. */
@RunWith(AndroidJUnit4.class)
public final class AgentControllerInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void approvedHttpPlansTypeSaveAndClickWithFreshDeviceObservations() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            try {
                start(device, controller, settings, "Use the synthetic fixture to save the sample text and increment its counter once.");
                Set<String> approved = new HashSet<>();
                long deadline = SystemClock.elapsedRealtime() + 30_000;
                while (controller.getState().running && SystemClock.elapsedRealtime() < deadline) {
                    server.assertHealthy();
                    AgentController.State state = controller.getState();
                    if (!state.approvalId.isEmpty() && approved.add(state.approvalId)) {
                        assertFalse(state.approval.isEmpty());
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
                assertEquals(3, approved.size());
                assertTrue("Final result must come from actual device observations", server.finalStateObserved());
                assertTrue(server.requestCount() >= 4 && server.requestCount() <= 13);
                assertEquals("Saved: " + LocalControllerModelServer.TEXT, fixtureText(device, "saved_value"));
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
                assertEquals("执行已结束 · 模型报告待核对", controller.getState().status);
                assertTrue(controller.getState().summary.contains("模型报告"));
                screenshots.capture("verified-fixture-result");
                for (String oldApproval : approved) controller.approve(oldApproval, true);
                assertFalse("Finished task sent another model request", server.receivedAnotherRequest(server.requestCount(), 350));
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
            } finally { clear(device, controller); }
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
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                screenshots.capture("before-rejection");
                device.instrumentation.runOnMainSync(() -> controller.approve(pending.approvalId, false));
                awaitStopped(controller, server, 10_000);
                assertEquals("执行失败", controller.getState().status);
                assertTrue(controller.getState().summary.contains("拒绝"));
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                controller.approve(pending.approvalId, true);
                assertFalse("Rejected task requested a fallback plan", server.receivedAnotherRequest(1, 350));
                assertEquals(1, server.requestCount());
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
