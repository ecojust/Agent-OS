package dev.agentos.app;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Saved output limits flow through the actual controller, adapter and loopback HTTP. */
@RunWith(AndroidJUnit4.class)
public final class OutputTokenControllerInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    @Test public void theSavedNonDefaultLimitReachesTheActualControllersFirstHttpRequest() throws Exception { verify(false); }
    @Test public void anUnsavedCallerLimitFailsBeforeObservationOrAnyHttpRequest() throws Exception { verify(true); }

    private void verify(boolean mismatch) throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.EXHAUST_ROUND_BUDGET);
             ControllerTestSettings originalSettings = new ControllerTestSettings(device.context, server)) {
            SettingsStore store = new SettingsStore(device.context); SettingsStore.SavedSettings saved = store.load();
            saved.maxOutputTokens = 8192; saved.taskBudget = new TaskBudget(1, 100, 600_000);
            SettingsStore.ConfigurationLease lease = store.save(saved);
            ModelSettings model = new ModelSettings(saved.endpoint, saved.apiKey, saved.model, mismatch ? 2048 : 8192);
            AgentController controller = AgentController.get(device.context);
            assertFalse("This fixture must not interrupt another task", controller.getState().running);
            settle(controller);
            onMain(device, () -> device.service.showOverlay("正在连接模型", false, ""));
            device.service.whenControlsReadyForAction().get(5, TimeUnit.SECONDS); device.awaitUiIdle();
            com.google.gson.JsonObject ready = DeviceHarness.awaitObservation(device.tools, device.control,
                    data -> DeviceHarness.FIXTURE.equals(DeviceHarness.string(data, "packageName"))
                            && data.get("focused").getAsBoolean() && !data.get("hasOcclusion").getAsBoolean());
            assertEquals("Count: 0", DeviceHarness.string(DeviceHarness.node(ready, "counter_value"), "text"));
            AtomicInteger approvals = new AtomicInteger();
            AgentController.Listener listener = () -> { if (!controller.getState().approvalId.isEmpty()) approvals.incrementAndGet(); };
            controller.addListener(listener);
            try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                try {
                    onMain(device, () -> controller.start(model, "Use the synthetic fixture with the saved per-response output limit.",
                            Collections.singleton(DeviceHarness.FIXTURE), saved.executionMode, saved.taskBudget, lease));
                    AgentController.State finished = awaitStopped(controller, server); settle(controller); device.instrumentation.waitForIdleSync();
                    finished = controller.getState(); assertFalse(finished.running); assertTrue(finished.approvalId.isEmpty()); assertEquals(0, approvals.get());
                    assertEquals("Missing trace events cannot prove the absence of work", 0, trace.overwrittenCount());
                    for (PerformanceTrace.Stage forbidden : new PerformanceTrace.Stage[]{PerformanceTrace.Stage.SNAPSHOT_PREPARE,
                            PerformanceTrace.Stage.SNAPSHOT_FINAL_LAUNCH, PerformanceTrace.Stage.SNAPSHOT_FINAL_MUTATION, PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT})
                        assertEquals("Unexpected device write stage: " + forbidden, 0, count(trace, forbidden));
                    if (mismatch) {
                        assertEquals("执行失败", finished.status);
                        assertTrue("Expected saved-model verification failure: " + finished.summary, finished.summary.contains("配置或授权已改变"));
                        assertEquals(0, count(trace, PerformanceTrace.Stage.SNAPSHOT_OBSERVE));
                        assertEquals(0, server.requestCount()); assertTrue(server.receivedMaxTokens().isEmpty());
                    } else {
                        assertEquals("达到任务预算，已停止", finished.status); assertTrue(finished.summary, finished.summary.contains("模型轮次已达到上限"));
                        assertTrue("The configured request needs an actual successful observation", successfulObservations(trace) > 0);
                        assertEquals(1, server.requestCount()); assertEquals(Collections.singletonList(8192), server.receivedMaxTokens());
                    }
                    assertEquals(8192, store.load().maxOutputTokens); assertTrue(SettingsStore.isCurrent(lease));
                    assertEquals(0, server.writePlanCount()); assertEquals(0, server.acceptedWriteCount());
                    assertFalse(server.receivedAnotherRequest(mismatch ? 0 : 1, 150)); server.assertHealthy();
                    assertFixtureUnchanged(device);
                    screenshots.capture(mismatch ? "output-token-mismatch-rejected-before-observation" : "saved-output-token-limit-real-http");
                } catch (Exception | Error failure) {
                    failure.addSuppressed(new AssertionError(server.safeDiagnostics())); throw failure;
                } finally { onMain(device, controller::stop); settle(controller); onMain(device, controller::clearCompletedTask); }
            } finally { controller.removeListener(listener); }
        }
    }
    private static AgentController.State awaitStopped(AgentController controller, LocalControllerModelServer server) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000; AgentController.State state;
        do { server.assertHealthy(); state = controller.getState(); assertTrue("A limit check must not ask for a device action", state.approvalId.isEmpty());
            if (!state.running) return state; SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Actual controller did not finish: " + state.status + " / " + state.summary);
    }
    private static void settle(AgentController controller) throws Exception {
        Field field = AgentController.class.getDeclaredField("worker"); field.setAccessible(true);
        ((ThreadPoolExecutor) field.get(controller)).submit(() -> { }).get(10, TimeUnit.SECONDS);
    }
    private static int count(PerformanceTrace.Session trace, PerformanceTrace.Stage stage) {
        int result = 0; for (PerformanceTrace.Event event : trace.events()) if (event.stage == stage) result++; return result;
    }
    private static int successfulObservations(PerformanceTrace.Session trace) {
        int result = 0; for (PerformanceTrace.Event event : trace.events())
            if (event.stage == PerformanceTrace.Stage.SNAPSHOT_OBSERVE && event.nodeCount > 0) { assertFalse(event.mainThread); result++; }
        return result;
    }
    private static void assertFixtureUnchanged(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue; AccessibilityNodeInfo root = window.getRoot(); if (root == null) continue;
                try {
                    if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    assertText(root, "counter_value", "Count: 0"); assertText(root, "saved_value", "Saved: empty"); return;
                } finally { root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        fail("The real fixture result is not visible");
    }
    private static void assertText(AccessibilityNodeInfo root, String id, String expected) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/" + id);
        try { assertEquals(1, nodes.size()); assertTrue(nodes.get(0).refresh()); assertEquals(expected, String.valueOf(nodes.get(0).getText())); }
        finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
    }
    private static void onMain(DeviceHarness device, Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> { try { action.run(); } catch (RuntimeException | Error failed) { failure.set(failed); } });
        if (failure.get() instanceof Error) throw (Error) failure.get(); if (failure.get() instanceof RuntimeException) throw (RuntimeException) failure.get();
    }
}
