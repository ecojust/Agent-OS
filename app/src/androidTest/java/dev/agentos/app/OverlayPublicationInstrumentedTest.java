package dev.agentos.app;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Real controller/settings/HTTP/service flow; reflection only reads callbacks or republishes state. */
@RunWith(AndroidJUnit4.class)
public final class OverlayPublicationInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private boolean failureCaptured;

    @Test public void unchangedControllerPublicationsKeepIdentityUntilANewApprovalIsPublished() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            try {
                start(device, controller, settings, "Prepare the synthetic fixture text and wait for each confirmation.");
                AgentController.State pending = awaitApproval(controller, server, "");
                screenshots.capture("real-controller-awaiting-first-approval");
                Binding first = binding(device, controller, pending.approvalId);
                int requestBaseline = server.requestCount();
                assertTrue(requestBaseline >= 1 && requestBaseline <= 1 + LocalControllerModelServer.MAX_WRITE_REPLANS);

                for (int publication = 0; publication < 8; publication++) {
                    device.instrumentation.runOnMainSync(() -> {
                        // This is the ordinary production publication method: no state or
                        // callback is replaced by the test, including coalesced publications.
                        publish(controller); publish(controller); publish(controller);
                    });
                    device.instrumentation.waitForIdleSync();
                    assertSameState(first.state, controller.getState());
                    assertSameCallbacks(device, controller, first.callbacks);
                    assertSame("A log-only publication must not replace the registered window identity",
                            first.identity, device.service.getOwnedControlWindowIdentity());
                    assertTrue(device.service.isOwnedControlWindowCurrent(first.identity));
                }
                assertFalse("Same-value publication must not execute the pending plan",
                        server.receivedAnotherRequest(requestBaseline, 350));
                assertEquals("Count: 0", fixtureText(device, "counter_value"));

                // One real confirmation may either dispatch this text action or yield a
                // definitely-unsubmitted stale result. The fixture alone bounds replanning.
                device.instrumentation.runOnMainSync(() -> first.callbacks.onApprove(true));
                AgentController.State nextApproval = awaitApproval(controller, server, first.state.approvalId);
                Binding next = binding(device, controller, nextApproval.approvalId);
                assertNotSame("A different confirmation must receive a different callback binding", first.callbacks, next.callbacks);
                assertNotSame(first.identity, next.identity);
                assertFalse(device.service.isOwnedControlWindowCurrent(first.identity));
                assertTrue(device.service.isOwnedControlWindowCurrent(next.identity));
                int nextBaseline = server.requestCount();
                device.instrumentation.runOnMainSync(() -> {
                    first.callbacks.onApprove(true); assertSameState(next.state, controller.getState());
                    first.callbacks.onApprove(false); assertSameState(next.state, controller.getState());
                });
                device.instrumentation.waitForIdleSync();
                assertSameState(next.state, controller.getState());
                assertSameCallbacks(device, controller, next.callbacks);
                assertFalse("Old confirmation callbacks must not answer the new request",
                        server.receivedAnotherRequest(nextBaseline, 350));
                assertEquals("Count: 0", fixtureText(device, "counter_value"));
                assertTrue(server.writePlanCount() >= 2);
                assertTrue(server.writePlanCount() <= LocalControllerModelServer.MAX_WRITE_PLANS);
                assertTrue("Only the first text action has been approved", server.acceptedWriteCount() <= 1);
                server.assertHealthy();
                screenshots.capture("new-approval-rebound-old-approval-ignored");
            } catch (Exception | AssertionError failure) {
                captureFailure(failure);
                throw failure;
            } finally { clear(device, controller); }
        }
    }

    @Test public void callbacksFromStoppedTaskCannotPauseStopApproveOrRenameTheReplacementTask() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer oldServer = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings oldSettings = new ControllerTestSettings(device.context, oldServer);
             LocalControllerModelServer newServer = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK)) {
            AgentController controller = AgentController.get(device.context);
            try {
                start(device, controller, oldSettings, "Old synthetic task: request a counter action but wait for permission.");
                AgentController.State oldApproval = awaitApproval(controller, oldServer, "");
                Binding previous = binding(device, controller, oldApproval.approvalId);
                int oldRequests = oldServer.requestCount();
                device.instrumentation.runOnMainSync(previous.callbacks::onStop);
                device.instrumentation.waitForIdleSync();
                assertFalse(controller.getState().running);
                assertFalse(device.service.isOwnedControlWindowCurrent(previous.identity));

                try (ControllerTestSettings newSettings = new ControllerTestSettings(device.context, newServer)) {
                    try {
                        start(device, controller, newSettings, "Replacement synthetic task: request a counter action and wait for permission.");
                        AgentController.State nextApproval = awaitApproval(controller, newServer, "");
                        screenshots.capture("replacement-controller-task-awaiting-approval");
                        Binding replacement = binding(device, controller, nextApproval.approvalId);
                        assertNotSame(previous.callbacks, replacement.callbacks);
                        assertNotEquals(previous.state.approvalId, replacement.state.approvalId);
                        assertNotSame(previous.identity, replacement.identity);
                        assertFalse(device.service.isOwnedControlWindowCurrent(previous.identity));
                        int newRequests = newServer.requestCount();

                        invokeOldCallbacks(device, controller, previous.callbacks, replacement.state);
                        assertSameCallbacks(device, controller, replacement.callbacks);
                        assertSame(replacement.identity, device.service.getOwnedControlWindowIdentity());
                        assertTrue(device.service.isOwnedControlWindowCurrent(replacement.identity));
                        assertFalse(newServer.receivedAnotherRequest(newRequests, 350));

                        // A current callback must still work. Once the replacement really
                        // is paused, an old onDevicePaused callback must not rename its state.
                        device.instrumentation.runOnMainSync(replacement.callbacks::onPauseResume);
                        device.instrumentation.waitForIdleSync();
                        device.awaitUiIdle();
                        AgentController.State paused = controller.getState();
                        assertTrue(paused.running); assertTrue(paused.paused); assertTrue(paused.approvalId.isEmpty());
                        assertEquals("已暂停", paused.status);
                        invokeOldCallbacks(device, controller, previous.callbacks, paused);
                        assertFalse("A stale task must not resume planning", newServer.receivedAnotherRequest(newRequests, 350));
                        assertFalse("The stopped task must not resume either", oldServer.receivedAnotherRequest(oldRequests, 350));
                        assertEquals("Count: 0", fixtureText(device, "counter_value"));
                        assertEquals(0, oldServer.acceptedWriteCount()); assertEquals(0, newServer.acceptedWriteCount());
                        oldServer.assertHealthy(); newServer.assertHealthy();
                        screenshots.capture("paused-replacement-ignores-stopped-task-callbacks");
                    } catch (Exception | AssertionError failure) {
                        captureFailure(failure);
                        throw failure;
                    } finally { clear(device, controller); }
                }
            } catch (Exception | AssertionError failure) {
                captureFailure(failure);
                throw failure;
            } finally { clear(device, controller); }
        }
    }

    private static void invokeOldCallbacks(DeviceHarness device, AgentController controller,
                                           AgentAccessibilityService.OverlayCallbacks old,
                                           AgentController.State expected) {
        device.instrumentation.runOnMainSync(() -> {
            old.onPauseResume(); assertSameState(expected, controller.getState());
            old.onStop(); assertSameState(expected, controller.getState());
            old.onApprove(true); assertSameState(expected, controller.getState());
            old.onApprove(false); assertSameState(expected, controller.getState());
            old.onDevicePaused("stopped-task-callback-must-not-rename-current-task");
            assertSameState(expected, controller.getState());
        });
        device.instrumentation.waitForIdleSync();
        assertSameState(expected, controller.getState());
    }

    private static void assertSameState(AgentController.State expected, AgentController.State actual) {
        assertEquals(expected.running, actual.running); assertEquals(expected.paused, actual.paused);
        assertEquals(expected.status, actual.status); assertEquals(expected.approvalId, actual.approvalId);
        assertEquals(expected.approval, actual.approval); assertEquals(expected.summary, actual.summary);
        assertEquals("Ignored callbacks must not append misleading task messages", expected.log, actual.log);
    }

    private static void start(DeviceHarness device, AgentController controller, ControllerTestSettings settings, String task) {
        device.instrumentation.runOnMainSync(() -> controller.start(settings.model, task,
                Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, settings.lease));
        assertTrue(controller.getState().running);
    }

    private static AgentController.State awaitApproval(AgentController controller, LocalControllerModelServer server, String previous) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            server.assertHealthy(); AgentController.State state = controller.getState();
            if (!state.approvalId.isEmpty() && !state.approvalId.equals(previous)) return state;
            if (!state.running) fail("Controller ended before a new approval: " + state.status + " / " + state.summary);
            SystemClock.sleep(25);
        }
        throw new AssertionError("Real controller did not publish a new confirmation");
    }

    private static Binding binding(DeviceHarness device, AgentController controller, String approvalId) throws Exception {
        device.instrumentation.runOnMainSync(() -> publish(controller));
        device.instrumentation.waitForIdleSync(); device.awaitUiIdle();
        CompletableFuture<AgentAccessibilityService.OwnedControlWindowIdentity> pending = device.service.acquireOwnedControlWindowIdentity();
        AgentAccessibilityService.OwnedControlWindowIdentity identity;
        try { identity = pending.get(6, TimeUnit.SECONDS); }
        finally { pending.cancel(false); }
        AgentAccessibilityService.OverlayCallbacks[] callbacks = {null};
        device.instrumentation.runOnMainSync(() -> {
            callbacks[0] = readCallbacks(device.service, AgentAccessibilityService.class, "callbacks");
            assertNotNull(callbacks[0]);
            assertSame(callbacks[0], readCallbacks(controller, AgentController.class, "overlayCallbacks"));
        });
        AgentController.State state = controller.getState();
        assertTrue(state.running); assertFalse(state.paused); assertEquals(approvalId, state.approvalId);
        assertTrue(device.service.isOwnedControlWindowCurrent(identity));
        return new Binding(callbacks[0], identity, state);
    }

    private static void assertSameCallbacks(DeviceHarness device, AgentController controller,
                                           AgentAccessibilityService.OverlayCallbacks expected) {
        device.instrumentation.runOnMainSync(() -> {
            assertSame(expected, readCallbacks(device.service, AgentAccessibilityService.class, "callbacks"));
            assertSame(expected, readCallbacks(controller, AgentController.class, "overlayCallbacks"));
        });
    }

    private static AgentAccessibilityService.OverlayCallbacks readCallbacks(Object owner, Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name); field.setAccessible(true);
            return (AgentAccessibilityService.OverlayCallbacks) field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect the real callback binding", failure); }
    }

    private static void publish(AgentController controller) {
        try {
            Method method = AgentController.class.getDeclaredMethod("publish"); method.setAccessible(true); method.invoke(controller);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot trigger ordinary controller publication", failure); }
    }

    /** Independent platform observation: never run another device tool through the held approval gate. */
    private static String fixtureText(DeviceHarness device, String id) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            List<AccessibilityWindowInfo> windows = device.automation.getWindows();
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (!window.isFocused()) continue;
                    AccessibilityNodeInfo root = window.getRoot(); if (root == null) continue;
                    try {
                        if (!DeviceHarness.FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/" + id);
                        try { if (!nodes.isEmpty() && nodes.get(0).getText() != null) return nodes.get(0).getText().toString(); }
                        finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                    } finally { root.recycle(); }
                }
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Synthetic fixture value is not visible: " + id);
    }

    private void captureFailure(Throwable failure) {
        if (failureCaptured) return;
        failureCaptured = true;
        try { screenshots.capture("failure-before-controller-cleanup"); }
        catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
        try { failure.addSuppressed(new AssertionError(DeviceHarness.windowDiagnostics())); }
        catch (Throwable diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
    }

    private static void clear(DeviceHarness device, AgentController controller) {
        device.instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
        device.instrumentation.waitForIdleSync();
    }

    private static final class Binding {
        final AgentAccessibilityService.OverlayCallbacks callbacks;
        final AgentAccessibilityService.OwnedControlWindowIdentity identity;
        final AgentController.State state;
        Binding(AgentAccessibilityService.OverlayCallbacks callbacks,
                AgentAccessibilityService.OwnedControlWindowIdentity identity, AgentController.State state) {
            this.callbacks = callbacks; this.identity = identity; this.state = state;
        }
    }
}
