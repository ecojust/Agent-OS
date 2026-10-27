package dev.agentos.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Button;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Platform-issued window IDs only; no title, package, synthetic ID, or test registration bypass. */
@RunWith(AndroidJUnit4.class)
public final class OwnedControlWindowInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AgentAccessibilityService.OwnedControlWindowIdentity identity = identity(device.service);
            AgentAccessibilityService.ControlLease lease = lease(device.service);
            assertTrue(device.service.isOwnedControlWindowCurrent(identity));
            assertSame(identity, device.service.getOwnedControlWindowIdentity());
            assertEquals(identity.windowId, lease.windowId);
            assertEquals(identity.displayId, lease.displayId);
            assertEquals(Display.DEFAULT_DISPLAY, identity.displayId);
            assertEquals(identity.copyBounds(), lease.copyBounds());
            assertTrue(device.service.isControlLeaseCurrent(lease));
            assertRegistered(device, identity.windowId, identity.displayId, identity.copyBounds());

            Rect detachedCopy = identity.copyBounds();
            detachedCopy.setEmpty();
            assertFalse("Callers must not mutate the owned geometry", identity.copyBounds().isEmpty());
            JsonObject observation = device.observe();
            assertFalse("The registered control window is not an unknown obstruction", observation.get("hasOcclusion").getAsBoolean());
            assertFalse(device.tools.availableTools().contains("take_screenshot"));
            assertFalse(device.tools.availableTools().contains("tap"));
            assertFalse(device.tools.availableTools().contains("swipe"));
            screenshots.capture("real-owned-window-identity-and-lease-match");
        }
    }

    @Test public void matchingTitleAndPackageCannotClaimAnUnregisteredOverlay() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AtomicInteger approvals = new AtomicInteger();
            AndroidDeviceTools tools = AndroidDeviceTools.forTesting(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)), true, () -> true,
                    (description, active, epoch) -> { approvals.incrementAndGet(); return true; });
            AgentAccessibilityService.OwnedControlWindowIdentity original = identity(device.service);
            Rect target = bounds(node(device.observe(), "increment"));
            try (UnregisteredOverlay blocker = new UnregisteredOverlay(device, target)) {
                int blockerId = blocker.awaitWindowId();
                assertNotEquals("The additional window must have its own platform ID", original.windowId, blockerId);
                assertRegistered(device, blockerId, original.displayId, blocker.bounds());
                assertTrue("Adding another window must not register it as our controls",
                        device.service.isOwnedControlWindowCurrent(original));
                assertSame(original, device.service.getOwnedControlWindowIdentity());

                long epoch = device.control.getExecutionEpoch();
                JsonObject rejected = null;
                StringBuilder outcomes = new StringBuilder();
                // A platform window event can stale an observation. Only this read-only,
                // explicitly unsubmitted lookup may receive a bounded fresh observation.
                for (int attempt = 0; attempt < 3; attempt++) {
                    device.control.checkExecutionEpoch(epoch);
                    device.awaitUiIdle();
                    JsonObject obscured = awaitObservation(tools, device.control,
                            OwnedControlWindowInstrumentedTest::incrementIsObscured);
                    JsonObject increment = node(obscured, "increment");
                    assertEquals("[obscured]", string(increment, "text"));
                    Rect overlap = bounds(increment);
                    assertTrue("The target must lie behind the real additional window", overlap.intersect(blocker.bounds()));
                    JsonObject arguments = new JsonObject();
                    arguments.addProperty("x", overlap.centerX()); arguments.addProperty("y", overlap.centerY());
                    arguments.addProperty("coordinateSpace", "screen_pixels");
                    arguments.addProperty("action", "click");
                    arguments.addProperty("expectedRevision", string(obscured, "revision"));
                    rejected = tools.execute("resolve_node_at", arguments, device.control, epoch);
                    outcomes.append("attempt=").append(attempt + 1).append(" windowId=").append(blockerId)
                            .append(" result=").append(rejected).append('\n');
                    assertUnsubmitted(rejected);
                    assertEquals(outcomes.toString(), 0, approvals.get());
                    assertEquals(outcomes.toString(), 0, device.control.getDispatchAttempts());
                    device.control.checkExecutionEpoch(epoch);
                    if (!"STALE_OBSERVATION".equals(string(rejected, "code"))) break;
                    if (attempt == 2) fail("The read-only lookup stayed stale instead of proving obstruction: " + outcomes);
                }
                assertNotNull(rejected);
                assertEquals(outcomes.toString(), "CAPABILITY_UNAVAILABLE", string(rejected, "code"));
                assertEquals(outcomes.toString(), "BLOCKED_TARGET", string(rejected, "reasonCode"));
                assertTrue(device.service.isOwnedControlWindowCurrent(original));
                screenshots.capture("same-title-package-unregistered-secure-overlay-refused");
            }
            JsonObject restored = awaitObservation(tools, device.control, data -> FIXTURE.equals(string(data, "packageName"))
                    && !data.get("hasOcclusion").getAsBoolean());
            assertEquals("Count: 0", string(node(restored, "counter_value"), "text"));
            assertEquals(0, approvals.get());
            assertEquals(0, device.control.getDispatchAttempts());
            screenshots.capture("unregistered-overlay-removed-without-device-dispatch");
        }
    }

    @Test public void detachingAndRecreatingControlsCannotReviveAnOldIdentityOrLease() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AgentAccessibilityService service = device.service;
            AgentAccessibilityService.OwnedControlWindowIdentity before = identity(service);
            AgentAccessibilityService.ControlLease oldLease = lease(service);
            device.instrumentation.runOnMainSync(() -> {
                service.hideOverlay();
                assertNull(service.getOwnedControlWindowIdentity());
                assertFalse(service.isOwnedControlWindowCurrent(before));
                assertFalse(service.isControlLeaseCurrent(oldLease));
                service.showOverlay("Integration test", false, "");
            });
            AgentAccessibilityService.OwnedControlWindowIdentity after = identity(service);
            AgentAccessibilityService.ControlLease newLease = lease(service);
            assertNotSame(before, after);
            // Android may reuse integer IDs eventually. Object/attachment generation
            // must invalidate the old owner even when its dimensions are unchanged.
            assertFalse(service.isOwnedControlWindowCurrent(before));
            assertFalse(service.isControlLeaseCurrent(oldLease));
            assertTrue(service.isOwnedControlWindowCurrent(after));
            assertTrue(service.isControlLeaseCurrent(newLease));
            assertEquals(after.windowId, newLease.windowId);
            assertEquals(after.copyBounds(), newLease.copyBounds());
            assertRegistered(device, after.windowId, after.displayId, after.copyBounds());
            assertFalse(device.observe().get("hasOcclusion").getAsBoolean());
            screenshots.capture("recreated-real-controls-reject-previous-identity");
        }
    }

    @Test public void absentOrNotYetLaidOutControlsCannotIssueAWindowIdentity() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AgentAccessibilityService service = device.service;
            device.instrumentation.runOnMainSync(service::hideOverlay);
            assertNull(service.getOwnedControlWindowIdentity());
            rejectedIdentity(service.acquireOwnedControlWindowIdentity());
            AtomicReference<CompletableFuture<AgentAccessibilityService.OwnedControlWindowIdentity>> unready = new AtomicReference<>();
            try {
                device.instrumentation.runOnMainSync(() -> {
                    service.showOverlay("Integration test", false, "");
                    // This is the same main-thread turn as addView: no first layout
                    // has occurred. Never fabricate an ID or force a traversal.
                    assertNull("A newly added, unlaid-out View is not a registered identity",
                            service.getOwnedControlWindowIdentity());
                    CompletableFuture<AgentAccessibilityService.OwnedControlWindowIdentity> pending = service.acquireOwnedControlWindowIdentity();
                    unready.set(pending);
                    assertFalse("An unready window must not issue a successful identity",
                            pending.isDone() && !pending.isCompletedExceptionally());
                    service.hideOverlay();
                    assertNull(service.getOwnedControlWindowIdentity());
                });
                assertNotNull(unready.get());
                rejectedIdentity(unready.get());
            } finally {
                if (unready.get() != null) unready.get().cancel(false);
            }
            device.instrumentation.runOnMainSync(() -> service.showOverlay("Integration test", false, ""));
            AgentAccessibilityService.OwnedControlWindowIdentity ready = identity(service);
            assertTrue(service.isOwnedControlWindowCurrent(ready));
            assertRegistered(device, ready.windowId, ready.displayId, ready.copyBounds());
            screenshots.capture("identity-issued-only-after-real-window-ready");
        }
    }

    @Test public void changedPresentationAndCallbacksStillRevokeTheActualControlOwner() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AgentAccessibilityService service = device.service;
            AgentAccessibilityService.OwnedControlWindowIdentity initial = identity(service);
            AgentAccessibilityService.ControlLease initialLease = lease(service);
            safeMain(device, () -> {
                service.showOverlay("Updated task state", false, "");
                assertFalse(service.isOwnedControlWindowCurrent(initial));
                assertFalse(service.isControlLeaseCurrent(initialLease));
            });
            AgentAccessibilityService.OwnedControlWindowIdentity statusChanged = identity(service);
            safeMain(device, () -> {
                service.showOverlay("Updated task state", true, "");
                assertFalse(service.isOwnedControlWindowCurrent(statusChanged));
            });
            AgentAccessibilityService.OwnedControlWindowIdentity paused = identity(service);
            AgentAccessibilityService.ControlLease pausedLease = lease(service);
            safeMain(device, () -> {
                service.showOverlay("Updated task state", true, "Synthetic approval requires review");
                assertFalse(service.isOwnedControlWindowCurrent(paused));
                assertFalse(service.isControlLeaseCurrent(pausedLease));
            });
            AgentAccessibilityService.OwnedControlWindowIdentity expanded = identity(service);
            assertTrue("The actual approval window must change geometry", expanded.copyBounds().height() > paused.copyBounds().height());
            assertRegistered(device, expanded.windowId, expanded.displayId, expanded.copyBounds());
            screenshots.capture("changed-approval-and-real-window-geometry-revoke-previous-owner");

            safeMain(device, () -> {
                service.setOverlayCallbacks(new AgentAccessibilityService.OverlayCallbacks() {
                    @Override public void onPauseResume() { device.control.pause(); }
                    @Override public void onStop() { device.control.cancel(); }
                    @Override public void onApprove(boolean approved) { }
                });
                assertFalse(service.isOwnedControlWindowCurrent(expanded));
                service.showOverlay("Updated task state", true, "Synthetic approval requires review");
                assertFalse("An identical display cannot revive another callback owner's identity", service.isOwnedControlWindowCurrent(expanded));
            });
            AgentAccessibilityService.OwnedControlWindowIdentity replacement = identity(service);
            assertNotSame(expanded, replacement);
            assertTrue(service.isOwnedControlWindowCurrent(replacement));
            assertRegistered(device, replacement.windowId, replacement.displayId, replacement.copyBounds());
            assertEquals("The synthetic UI changes must not dispatch device actions", 0, device.control.getDispatchAttempts());
            screenshots.capture("callback-replacement-revokes-owner-even-with-identical-text");

            // Use the same registered View to prove the narrow historical collapse.
            // A real callback publication and a real layout, not fabricated IDs,
            // produce the one current compact lease.
            safeMain(device, () -> service.showOverlay("Collapse readiness", false, ""));
            // Run before creating the live collapse used below: a round trip must
            // not pre-invalidate that separate direct-drag regression's owner.
            dragOutAndBackToOriginalPosition(device);
            screenshots.capture("actual-control-drag-out-and-back-re-registration");
            AgentAccessibilityService.OwnedControlWindowIdentity beforeApproval = identity(service);
            AgentAccessibilityService.ControlLease beforeApprovalLease = lease(service);
            assertNull(service.approvalCollapseFor(beforeApprovalLease));
            safeMain(device, () -> service.showOverlay("Collapse readiness", false, "Synthetic approval requires review"));
            AgentAccessibilityService.OwnedControlWindowIdentity actualExpanded = identity(service);
            assertRegistered(device, actualExpanded.windowId, actualExpanded.displayId, actualExpanded.copyBounds());
            assertTrue(actualExpanded.copyBounds().height() > beforeApproval.copyBounds().height());
            safeMain(device, () -> {
                service.setOverlayCallbacks(callbacks(device));
                service.showOverlay("Collapse readiness", false, "");
            });
            AgentAccessibilityService.ControlLease compact = lease(service);
            AgentAccessibilityService.ApprovalCollapse collapse = service.approvalCollapseFor(compact);
            assertNotNull("The actual approval collapse must bind its first compact lease", collapse);
            assertSame(compact, collapse.compactLease());
            assertTrue(collapse.matchesOriginalAttachment(beforeApproval));
            assertTrue(collapse.matchesOriginalAttachment(actualExpanded));
            assertEquals(actualExpanded.copyBounds(), collapse.copyExpandedBounds());
            assertEquals(compact.copyBounds(), collapse.copyCompactBounds());
            assertTrue(service.isApprovalCollapseCurrent(collapse));
            assertRegistered(device, compact.windowId, compact.displayId, compact.copyBounds());
            Rect changedCopy = collapse.copyExpandedBounds(); changedCopy.setEmpty();
            assertEquals(actualExpanded.copyBounds(), collapse.copyExpandedBounds());
            changedCopy = collapse.copyCompactBounds(); changedCopy.setEmpty();
            assertEquals(compact.copyBounds(), collapse.copyCompactBounds());
            screenshots.capture("actual-expanded-to-compact-owner-record");

            // Dispatch touch events to the service's actual draggable title View.
            // A geometry change revokes the record immediately, before WMS settles.
            AtomicReference<ControlViews> beforeDragViews = new AtomicReference<>();
            AgentAccessibilityService.OwnedControlWindowIdentity beforeDragIdentity = identity(service);
            long beforeDragEpoch = device.control.getExecutionEpoch();
            safeMain(device, () -> {
                beforeDragViews.set(new ControlViews(service));
                // A tap/zero-distance gesture is not a move or a new attachment.
                dispatchTitleTouch(beforeDragViews.get().title, true, 0);
                assertTrue(service.isOwnedControlWindowCurrent(beforeDragIdentity));
                assertTrue(service.isControlLeaseCurrent(compact));
                assertTrue(service.isApprovalCollapseCurrent(collapse));
                assertSame(beforeDragViews.get().panel, controlField(service, "overlay"));
                dragActualStatus(device);
                assertFalse(service.isApprovalCollapseCurrent(collapse));
                assertFalse(service.isControlLeaseCurrent(compact));
                assertNull(service.approvalCollapseFor(compact));
            });
            AgentAccessibilityService.ControlLease dragged = lease(service);
            AgentAccessibilityService.OwnedControlWindowIdentity afterDragIdentity = identity(service);
            assertFalse(service.isOwnedControlWindowCurrent(beforeDragIdentity));
            assertFalse(service.isControlLeaseCurrent(compact));
            assertFalse(service.isApprovalCollapseCurrent(collapse));
            if (Build.VERSION.SDK_INT <= 32) {
                assertNotEquals("Legacy drag re-registers a real platform window", compact.windowId, dragged.windowId);
                assertFalse("The new window must not inherit the original attachment", dragged.matchesOriginalAttachment(beforeDragIdentity));
            } else {
                assertEquals(compact.windowId, dragged.windowId);
                assertTrue(dragged.matchesOriginalAttachment(beforeDragIdentity));
            }
            assertEquals("The re-registered window must preserve the actual requested drag position",
                    compact.copyBounds().top + Math.max(8, Math.round(12 * device.service.getResources().getDisplayMetrics().density)),
                    dragged.copyBounds().top);
            assertFreshRegisteredAfterDrag(device, dragged.windowId, dragged.displayId, dragged.copyBounds(), screenshots);
            safeMain(device, () -> {
                ControlViews old = beforeDragViews.get(), current = new ControlViews(service);
                assertEquals(old.status, current.status);
                assertEquals(old.approval, current.approval);
                assertEquals(old.paused, current.paused);
                assertEquals(old.pause.getText().toString(), current.pause.getText().toString());
                assertEquals(old.stop.getText().toString(), current.stop.getText().toString());
                if (Build.VERSION.SDK_INT <= 32) {
                    assertNotSame(old.panel, current.panel);
                    assertFalse(old.panel.isAttachedToWindow());
                    old.pause.performClick(); old.stop.performClick(); old.approve.performClick(); old.reject.performClick();
                    dispatchTitleDrag(old.title, false);
                    device.control.checkExecutionEpoch(beforeDragEpoch);
                    assertFalse(device.control.isPaused());
                    assertTrue(service.isOwnedControlWindowCurrent(afterDragIdentity));
                    assertTrue(service.isControlLeaseCurrent(dragged));
                    assertEquals(dragged.copyBounds(), service.controlBounds());
                } else assertSame(old.panel, current.panel);
            });
            assertNull("A later stable frame cannot reissue the old collapse", service.approvalCollapseFor(dragged));
            safeMain(device, () -> service.showOverlay("Collapse readiness", false, ""));
            assertSame(dragged, lease(service));
            assertFalse(service.isApprovalCollapseCurrent(collapse));
            assertNull(service.approvalCollapseFor(dragged));

            safeMain(device, () -> service.showOverlay("Collapse readiness", false, "Synthetic second approval"));
            AgentAccessibilityService.OwnedControlWindowIdentity secondExpanded = identity(service);
            assertRegistered(device, secondExpanded.windowId, secondExpanded.displayId, secondExpanded.copyBounds());
            safeMain(device, () -> {
                service.setOverlayCallbacks(callbacks(device));
                service.showOverlay("Collapse readiness", false, "");
            });
            AgentAccessibilityService.ControlLease secondCompact = lease(service);
            AgentAccessibilityService.ApprovalCollapse secondCollapse = service.approvalCollapseFor(secondCompact);
            assertNotNull(secondCollapse);
            assertTrue(service.isApprovalCollapseCurrent(secondCollapse));
            assertRegistered(device, secondCompact.windowId, secondCompact.displayId, secondCompact.copyBounds());
            safeMain(device, () -> {
                service.setOverlayCallbacks(callbacks(device));
                assertFalse(service.isApprovalCollapseCurrent(secondCollapse));
                assertNull(service.approvalCollapseFor(secondCompact));
                service.showOverlay("Collapse readiness", false, "");
            });
            AgentAccessibilityService.ControlLease rebound = lease(service);
            assertFalse(service.isApprovalCollapseCurrent(secondCollapse));
            assertNull("Extra callbacks cannot revive a consumed historical transition", service.approvalCollapseFor(rebound));
            assertRegistered(device, rebound.windowId, rebound.displayId, rebound.copyBounds());

            // Two callbacks before the same collapse must not re-arm a discarded
            // expanded owner merely because a compact frame eventually appears.
            safeMain(device, () -> service.showOverlay("Collapse readiness", false, "Synthetic third approval"));
            AgentAccessibilityService.OwnedControlWindowIdentity thirdExpanded = identity(service);
            assertRegistered(device, thirdExpanded.windowId, thirdExpanded.displayId, thirdExpanded.copyBounds());
            safeMain(device, () -> {
                service.setOverlayCallbacks(callbacks(device));
                service.setOverlayCallbacks(callbacks(device));
                service.showOverlay("Collapse readiness", false, "");
            });
            AgentAccessibilityService.ControlLease discarded = lease(service);
            assertRegistered(device, discarded.windowId, discarded.displayId, discarded.copyBounds());
            assertNull("An extra callback within the transition invalidates its original binding", service.approvalCollapseFor(discarded));
            assertFalse(service.isApprovalCollapseCurrent(collapse));
            assertFalse(service.isApprovalCollapseCurrent(secondCollapse));
            assertEquals("Only local control UI changed; no device action was dispatched", 0, device.control.getDispatchAttempts());
            screenshots.capture("drag-and-extra-callbacks-do-not-revive-collapse-record");
            dragThroughInputDispatcher(device, discarded);
            screenshots.capture("real-input-dispatcher-control-drag");
            safeMain(device, () -> {
                ControlViews current = new ControlViews(service);
                assertTrue(current.pause.isShown() && current.pause.isEnabled());
                assertTrue(current.pause.performClick());
                assertTrue("The current actual pause Button retains its callback", device.control.isPaused());
                device.control.resume();
                assertTrue(current.stop.isShown() && current.stop.isEnabled());
                assertTrue(current.stop.performClick());
                assertTrue("The current actual stop Button retains its callback", device.control.isCancelled());
            });
        }
    }

    private static AgentAccessibilityService.OverlayCallbacks callbacks(DeviceHarness device) {
        return new AgentAccessibilityService.OverlayCallbacks() {
            @Override public void onPauseResume() { device.control.pause(); }
            @Override public void onStop() { device.control.cancel(); }
            @Override public void onApprove(boolean approved) { }
        };
    }

    /** Read-only actual control references; constructed only on the main thread. */
    private static final class ControlViews {
        final ViewGroup panel;
        final TextView title;
        final Button pause, stop, approve, reject;
        final String status, approval;
        final boolean paused;
        ControlViews(AgentAccessibilityService service) {
            panel = (ViewGroup) controlField(service, "overlay");
            title = (TextView) controlField(service, "statusView");
            pause = (Button) controlField(service, "pauseButton");
            stop = (Button) ((ViewGroup) panel.getChildAt(1)).getChildAt(1);
            approve = (Button) controlField(service, "approveButton");
            reject = (Button) controlField(service, "rejectButton");
            status = (String) controlField(service, "currentStatus");
            approval = (String) controlField(service, "currentApproval");
            paused = (Boolean) controlField(service, "currentPaused");
        }
    }

    private static Object controlField(AgentAccessibilityService service, String name) {
        try {
            Field field = AgentAccessibilityService.class.getDeclaredField(name);
            field.setAccessible(true); return field.get(service);
        } catch (ReflectiveOperationException failed) { throw new AssertionError("Actual control field is unavailable: " + name, failed); }
    }

    /** Caller is on the main thread; dispatches to the actual View, never a fake identity. */
    private static void dragActualStatus(DeviceHarness device) {
        TextView title = (TextView) controlField(device.service, "statusView");
        assertNotNull(title);
        assertTrue(title.isAttachedToWindow() && title.isShown());
        dispatchTitleDrag(title, true);
    }

    private static void dispatchTitleDrag(TextView title, boolean mustHandle) {
        dispatchTitleTouch(title, mustHandle, Math.max(8, Math.round(12 * title.getResources().getDisplayMetrics().density)));
    }

    private static void dispatchTitleTouch(TextView title, boolean mustHandle, float delta) {
        float x = title.getWidth() / 2f, y = title.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent move = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, x, y + delta, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, x, y + delta, 0);
        try {
            boolean a = title.dispatchTouchEvent(down), b = title.dispatchTouchEvent(move), c = title.dispatchTouchEvent(up);
            if (mustHandle) assertTrue(a && b && c);
        } finally { up.recycle(); move.recycle(); down.recycle(); }
    }

    /** Prove a real intermediate View position, then return the same gesture to its original y. */
    private void dragOutAndBackToOriginalPosition(DeviceHarness device) throws Exception {
        AgentAccessibilityService service = device.service;
        AgentAccessibilityService.ControlLease before = lease(service);
        AgentAccessibilityService.OwnedControlWindowIdentity original = service.getOwnedControlWindowIdentity();
        assertNotNull(original);
        assertTrue(service.isOwnedControlWindowCurrent(original));
        assertEquals(before.copyBounds(), original.copyBounds());
        assertRegistered(device, before.windowId, before.displayId, before.copyBounds());
        AtomicReference<ControlViews> views = new AtomicReference<>();
        float[] point = new float[3];
        long downTime = SystemClock.uptimeMillis();
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        boolean[] ended = {false};
        try {
            safeMain(device, () -> {
                requireRegistrationTime(deadline);
                ControlViews actual = new ControlViews(service); views.set(actual);
                assertTrue(actual.title.isAttachedToWindow() && actual.title.isShown());
                point[0] = actual.title.getWidth() / 2f; point[1] = actual.title.getHeight() / 2f;
                point[2] = Math.max(8, Math.round(12 * service.getResources().getDisplayMetrics().density));
                assertTrue(dispatchTitleEvent(actual.title, downTime, MotionEvent.ACTION_DOWN, point[0], point[1]));
                assertTrue(dispatchTitleEvent(actual.title, downTime, MotionEvent.ACTION_MOVE, point[0], point[1] + point[2]));
                assertFalse(service.isControlLeaseCurrent(before));
            });
            AgentAccessibilityService.ControlLease outward = leaseBeforeDeadline(service, deadline);
            Rect moved = before.copyBounds(); moved.offset(0, Math.round(point[2]));
            assertEquals("The outward update must reach the actual View before moving back", moved, outward.copyBounds());
            assertEquals(before.windowId, outward.windowId);
            assertTrue(outward.matchesOriginalAttachment(original));
            safeMain(device, () -> {
                requireRegistrationTime(deadline);
                ControlViews actual = new ControlViews(service);
                assertSame(views.get().panel, actual.panel);
                assertTrue(dispatchTitleEvent(actual.title, downTime, MotionEvent.ACTION_MOVE, point[0], point[1]));
                assertTrue(dispatchTitleEvent(actual.title, downTime, MotionEvent.ACTION_UP, point[0], point[1]));
                ended[0] = true;
            });
        } finally {
            if (!ended[0] && views.get() != null) safeMain(device, () ->
                    dispatchTitleEvent(views.get().title, downTime, MotionEvent.ACTION_CANCEL, point[0], point[1]));
        }
        AgentAccessibilityService.ControlLease returned = leaseBeforeDeadline(service, deadline);
        assertEquals("Returning to the starting y must preserve the exact original final geometry", before.copyBounds(), returned.copyBounds());
        assertFalse(service.isControlLeaseCurrent(before));
        assertFalse(service.isOwnedControlWindowCurrent(original));
        if (Build.VERSION.SDK_INT <= 32) {
            assertNotEquals("An actual move requires re-registration even when net displacement is zero", before.windowId, returned.windowId);
            assertFalse(returned.matchesOriginalAttachment(original));
        } else {
            assertEquals(before.windowId, returned.windowId);
            assertTrue(returned.matchesOriginalAttachment(original));
        }
        assertFreshRegisteredAfterDrag(device, returned.windowId, returned.displayId, returned.copyBounds(), screenshots, deadline);
        safeMain(device, () -> {
            requireRegistrationTime(deadline);
            ControlViews actual = new ControlViews(service), old = views.get();
            assertEquals(old.status, actual.status); assertEquals(old.approval, actual.approval); assertEquals(old.paused, actual.paused);
            if (Build.VERSION.SDK_INT <= 32) {
                assertNotSame(old.panel, actual.panel); assertFalse(old.panel.isAttachedToWindow());
            } else assertSame(old.panel, actual.panel);
        });
        requireRegistrationTime(deadline);
        assertEquals(0, device.control.getDispatchAttempts());
    }

    private static AgentAccessibilityService.ControlLease leaseBeforeDeadline(AgentAccessibilityService service, long deadline) throws Exception {
        requireRegistrationTime(deadline);
        CompletableFuture<AgentAccessibilityService.ControlLease> pending = service.acquireControlLease();
        try {
            requireRegistrationTime(deadline);
            AgentAccessibilityService.ControlLease result = pending.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            requireRegistrationTime(deadline);
            return result;
        } finally { pending.cancel(false); }
    }

    private static boolean dispatchTitleEvent(TextView title, long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        try { return title.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    /** A separate real InputDispatcher gesture, never injected while holding the main thread. */
    private void dragThroughInputDispatcher(DeviceHarness device, AgentAccessibilityService.ControlLease before) throws Exception {
        AgentAccessibilityService service = device.service;
        AgentAccessibilityService.OwnedControlWindowIdentity original = service.getOwnedControlWindowIdentity();
        assertNotNull(original);
        assertTrue(service.isControlLeaseCurrent(before));
        assertTrue(service.isOwnedControlWindowCurrent(original));
        assertEquals(original.windowId, before.windowId);
        assertEquals(original.copyBounds(), before.copyBounds());
        AtomicReference<ControlViews> views = new AtomicReference<>();
        int[] point = new int[3];
        safeMain(device, () -> {
            assertTrue(service.isOwnedControlWindowCurrent(original));
            ControlViews actual = new ControlViews(service); views.set(actual);
            assertTrue(actual.title.isAttachedToWindow() && actual.title.isShown());
            int[] location = new int[2]; actual.title.getLocationOnScreen(location);
            Rect title = new Rect(location[0], location[1], location[0] + actual.title.getWidth(), location[1] + actual.title.getHeight());
            assertFalse(title.isEmpty());
            assertTrue(original.copyBounds().contains(title));
            point[0] = title.centerX(); point[1] = title.centerY();
            point[2] = Math.max(8, Math.round(12 * service.getResources().getDisplayMetrics().density));
            WindowManager manager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            assertNotNull(manager);
            Rect display = manager.getCurrentWindowMetrics().getBounds();
            assertTrue(display.contains(point[0], point[1]));
            assertTrue(display.contains(point[0], point[1] + point[2]));
            assertTrue("This small real gesture must not reach the drag clamp",
                    original.copyBounds().bottom + point[2] < display.bottom - Math.round(48 * service.getResources().getDisplayMetrics().density));
        });
        long epoch = device.control.getExecutionEpoch();
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        long downTime = SystemClock.uptimeMillis();
        boolean released = false;
        try {
            requireRegistrationTime(deadline);
            assertTrue("Real DOWN must enter InputDispatcher", injectTouch(device, downTime, MotionEvent.ACTION_DOWN, point[0], point[1]));
            SystemClock.sleep(24);
            requireRegistrationTime(deadline);
            assertTrue("Real MOVE must enter InputDispatcher", injectTouch(device, downTime, MotionEvent.ACTION_MOVE, point[0], point[1] + point[2]));
            SystemClock.sleep(24);
            requireRegistrationTime(deadline);
            released = injectTouch(device, downTime, MotionEvent.ACTION_UP, point[0], point[1] + point[2]);
            assertTrue("Real UP must enter InputDispatcher", released);
        } finally {
            // End an incomplete physical touch even after the test deadline; never replay the gesture.
            if (!released) injectTouch(device, downTime, MotionEvent.ACTION_CANCEL, point[0], point[1] + point[2]);
        }
        requireRegistrationTime(deadline);
        CompletableFuture<AgentAccessibilityService.ControlLease> pending = service.acquireControlLease();
        AgentAccessibilityService.ControlLease after;
        try {
            requireRegistrationTime(deadline);
            after = pending.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            requireRegistrationTime(deadline);
        } finally { pending.cancel(false); }
        AgentAccessibilityService.OwnedControlWindowIdentity current = service.getOwnedControlWindowIdentity();
        assertNotNull(current);
        assertTrue(service.isOwnedControlWindowCurrent(current));
        assertTrue(service.isControlLeaseCurrent(after));
        assertEquals(current.windowId, after.windowId);
        assertEquals(current.copyBounds(), after.copyBounds());
        Rect expected = before.copyBounds(); expected.offset(0, point[2]);
        assertEquals("The real input gesture must move the actual controls", expected, after.copyBounds());
        assertFalse(service.isControlLeaseCurrent(before));
        assertFalse(service.isOwnedControlWindowCurrent(original));
        if (Build.VERSION.SDK_INT <= 32) {
            assertNotEquals(before.windowId, after.windowId);
            assertFalse(after.matchesOriginalAttachment(original));
        } else {
            assertEquals(before.windowId, after.windowId);
            assertTrue(after.matchesOriginalAttachment(original));
        }
        assertFreshRegisteredAfterDrag(device, after.windowId, after.displayId, after.copyBounds(), screenshots, deadline);
        safeMain(device, () -> {
            requireRegistrationTime(deadline);
            ControlViews actual = new ControlViews(service), old = views.get();
            assertEquals(old.status, actual.status);
            assertEquals(old.approval, actual.approval);
            assertEquals(old.paused, actual.paused);
            assertTrue(actual.pause.isAttachedToWindow() && actual.pause.isShown() && actual.pause.isEnabled());
            assertTrue(actual.stop.isAttachedToWindow() && actual.stop.isShown() && actual.stop.isEnabled());
            if (Build.VERSION.SDK_INT <= 32) {
                assertNotSame(old.panel, actual.panel);
                assertFalse(old.panel.isAttachedToWindow());
            } else assertSame(old.panel, actual.panel);
        });
        requireRegistrationTime(deadline);
        device.control.checkExecutionEpoch(epoch);
        assertEquals("The input gesture controls the overlay, not the target app", 0, device.control.getDispatchAttempts());
    }

    private static boolean injectTouch(DeviceHarness device, long downTime, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            return device.automation.injectInputEvent(event, true);
        } finally { event.recycle(); }
    }

    /** Preserve failures without throwing an uncaught assertion on the application main thread. */
    @FunctionalInterface private interface MainAction { void run() throws Exception; }

    private static void safeMain(DeviceHarness device, MainAction action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable failed) { failure.set(failed); }
        });
        Throwable failed = failure.get();
        if (failed instanceof Error) throw (Error) failed;
        if (failed instanceof RuntimeException) throw (RuntimeException) failed;
        if (failed != null) throw new AssertionError("Main-thread test action failed", failed);
    }

    private static AgentAccessibilityService.OwnedControlWindowIdentity identity(AgentAccessibilityService service) throws Exception {
        CompletableFuture<AgentAccessibilityService.OwnedControlWindowIdentity> future = service.acquireOwnedControlWindowIdentity();
        try { return future.get(5, TimeUnit.SECONDS); }
        finally { future.cancel(false); }
    }

    private static AgentAccessibilityService.ControlLease lease(AgentAccessibilityService service) throws Exception {
        CompletableFuture<AgentAccessibilityService.ControlLease> future = service.acquireControlLease();
        try { return future.get(5, TimeUnit.SECONDS); }
        finally { future.cancel(false); }
    }

    private static void rejectedIdentity(CompletableFuture<AgentAccessibilityService.OwnedControlWindowIdentity> future) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            fail("An absent or not-yet-ready window cannot issue an identity");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        } finally { future.cancel(false); }
    }

    /** Only the completed real drag needs a cache-fresh registration observation. */
    private static void assertFreshRegisteredAfterDrag(DeviceHarness device, int id, int displayId, Rect bounds,
                                                       ScreenshotEvidence screenshots) throws Exception {
        assertFreshRegisteredAfterDrag(device, id, displayId, bounds, screenshots, SystemClock.elapsedRealtime() + 5_000);
    }

    private static void assertFreshRegisteredAfterDrag(DeviceHarness device, int id, int displayId, Rect bounds,
                                                       ScreenshotEvidence screenshots, long deadline) throws Exception {
        assertTrue("No device operation may overlap the drag registration read", device.service.tryBeginDeviceCall());
        try { assertRegistered(device, id, displayId, bounds, true, deadline); }
        catch (AssertionError original) {
            // The original five-second assertion has already failed. These sequential,
            // read-only measurements cannot convert it to success or renew any identity.
            String diagnostics;
            try { diagnostics = OwnedControlDragDiagnostics.sample(device, id, displayId, bounds); }
            catch (Throwable unavailable) {
                original.addSuppressed(unavailable);
                diagnostics = "Drag measurements unavailable: " + unavailable.getClass().getSimpleName();
            }
            try { screenshots.capture("drag-registration-failed-before-cleanup"); }
            catch (Throwable captureFailure) { original.addSuppressed(captureFailure); }
            throw new AssertionError(original.getMessage() + "\n" + diagnostics, original);
        } finally { device.service.endDeviceCall(); }
    }

    /** Both genuine and look-alike windows satisfy this metadata; it is not proof of ownership. */
    private static void assertRegistered(DeviceHarness device, int id, int displayId, Rect bounds) throws Exception {
        assertRegistered(device, id, displayId, bounds, false);
    }

    private static void assertRegistered(DeviceHarness device, int id, int displayId, Rect bounds, boolean fresh) throws Exception {
        assertRegistered(device, id, displayId, bounds, fresh, SystemClock.elapsedRealtime() + 5_000);
    }

    private static void assertRegistered(DeviceHarness device, int id, int displayId, Rect bounds, boolean fresh, long deadline) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                List<AccessibilityWindowInfo> windows = fresh
                        ? AndroidDeviceTools.freshWindows(device.service, () -> requireRegistrationTime(deadline))
                        : device.service.getWindows();
                try {
                    if (fresh) requireRegistrationTime(deadline);
                    if (windows != null) for (AccessibilityWindowInfo window : windows) {
                        if (window.getId() != id) continue;
                        Rect actual = new Rect(); window.getBoundsInScreen(actual);
                        if (actual.isEmpty() || !bounds.equals(actual)) continue;
                        if (fresh) requireRegistrationTime(deadline);
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root == null) continue;
                        try {
                            if (fresh) requireRegistrationTime(deadline);
                            assertEquals(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, window.getType());
                            assertEquals(displayId, window.getDisplayId());
                            assertFalse("Task controls must not take the target's input focus", window.isFocused());
                            assertEquals("Agent OS task controls", String.valueOf(window.getTitle()));
                            assertEquals(device.service.getPackageName(), String.valueOf(root.getPackageName()));
                            assertEquals(id, root.getWindowId());
                            if (fresh) requireRegistrationTime(deadline);
                            return;
                        } finally { root.recycle(); }
                    }
                } finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
            } catch (RegistrationDeadlineExpired expired) {
                break; // Same original timeout/failure, never accept a late read or extend the deadline.
            }
            SystemClock.sleep(40);
        }
        fail("Real window did not register with id=" + id + " display=" + displayId + " bounds=" + bounds
                + "\n" + windowDiagnostics());
    }

    private static void requireRegistrationTime(long deadline) {
        if (SystemClock.elapsedRealtime() >= deadline) throw new RegistrationDeadlineExpired();
    }

    private static final class RegistrationDeadlineExpired extends RuntimeException { }

    private static boolean incrementIsObscured(JsonObject data) {
        if (!FIXTURE.equals(string(data, "packageName")) || !data.get("focused").getAsBoolean()
                || !data.get("scoped").getAsBoolean() || data.get("truncated").getAsBoolean()
                || !data.get("hasOcclusion").getAsBoolean()) return false;
        for (JsonElement entry : data.getAsJsonArray("nodes")) {
            JsonObject candidate = entry.getAsJsonObject();
            if ((FIXTURE + ":id/increment").equals(string(candidate, "viewId")))
                return candidate.get("obscured").getAsBoolean();
        }
        return false;
    }

    private static void assertUnsubmitted(JsonObject result) {
        for (String flag : new String[]{"ok", "submitted", "dispatchSubmitted", "dispatchAccepted"}) {
            assertTrue(result.toString(), result.has(flag) && result.get(flag).isJsonPrimitive()
                    && result.getAsJsonPrimitive(flag).isBoolean());
            assertFalse(result.toString(), result.get(flag).getAsBoolean());
        }
        assertFalse("A rejected lookup cannot return a candidate", result.has("data") || result.has("nodeId") || result.has("candidate"));
    }

    private static Rect bounds(JsonObject node) {
        return new Rect(node.getAsJsonArray("bounds").get(0).getAsInt(), node.getAsJsonArray("bounds").get(1).getAsInt(),
                node.getAsJsonArray("bounds").get(2).getAsInt(), node.getAsJsonArray("bounds").get(3).getAsInt());
    }

    private static final class UnregisteredOverlay implements AutoCloseable {
        private final DeviceHarness device;
        private final WindowManager manager;
        private final TextView view;

        UnregisteredOverlay(DeviceHarness device, Rect target) {
            this.device = device;
            manager = (WindowManager) device.service.getSystemService(Context.WINDOW_SERVICE);
            assertNotNull(manager);
            view = new TextView(device.service);
            try {
                device.instrumentation.runOnMainSync(() -> {
                    view.setText("Unregistered control-window fixture");
                    view.setBackgroundColor(Color.rgb(80, 30, 30));
                    view.setClickable(true);
                    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(target.width(), target.height(),
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    params.x = target.left; params.y = target.top;
                    params.setTitle("Agent OS task controls");
                    manager.addView(view, params);
                });
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        int awaitWindowId() {
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                int[] actual = {-1};
                device.instrumentation.runOnMainSync(() -> {
                    if (!view.isAttachedToWindow() || !view.isLaidOut() || view.getWindowToken() == null) return;
                    AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
                    if (info == null) return;
                    try { actual[0] = info.getWindowId(); }
                    finally { info.recycle(); }
                });
                if (actual[0] >= 0) return actual[0];
                SystemClock.sleep(40);
            }
            throw new AssertionError("The real additional View did not receive an accessibility window ID\n" + windowDiagnostics());
        }

        Rect bounds() {
            Rect result = new Rect();
            device.instrumentation.runOnMainSync(() -> {
                assertTrue(view.isAttachedToWindow());
                int[] point = new int[2]; view.getLocationOnScreen(point);
                result.set(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight());
            });
            return result;
        }

        @Override public void close() {
            device.instrumentation.runOnMainSync(() -> {
                if (view.getParent() != null) manager.removeViewImmediate(view);
            });
        }
    }
}
