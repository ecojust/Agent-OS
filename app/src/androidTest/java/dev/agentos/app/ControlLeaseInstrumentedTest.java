package dev.agentos.app;

import android.graphics.Rect;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Real control layout and request ownership, without granting a device-action bypass. */
@RunWith(AndroidJUnit4.class)
public final class ControlLeaseInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    @Test public void stableGeometryReusesLeaseAndApprovalInvalidatesBeforeCompactReturns() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AgentAccessibilityService service = device.service;
            AgentAccessibilityService.ControlLease original = lease(service);
            assertTrue(service.isControlLeaseCurrent(original));
            assertSame(original, lease(service));
            Rect[] actual = new Rect[1];
            device.instrumentation.runOnMainSync(() -> actual[0] = service.controlBounds());
            assertTrue(original.intersects(actual[0]));
            actual[0].set(-100, -100, -90, -90);
            assertFalse(original.intersects(actual[0]));
            assertTrue(service.isControlLeaseCurrent(original));

            CompletableFuture<AgentAccessibilityService.ControlLease> waiting;
            device.instrumentation.runOnMainSync(() -> service.showOverlay("Lease test", false,
                    "Synthetic approval\n" + String.join("\n", Collections.nCopies(20, "A bounded confirmation line."))));
            assertFalse(service.isControlLeaseCurrent(original));
            waiting = service.acquireControlLease();
            device.instrumentation.waitForIdleSync();
            assertFalse("Expanded approval must not publish a compact lease", waiting.isDone());
            try {
                device.instrumentation.runOnMainSync(() -> service.showOverlay("Lease test", false, ""));
                AgentAccessibilityService.ControlLease compact = waiting.get(5, TimeUnit.SECONDS);
                assertNotSame(original, compact);
                assertTrue(service.isControlLeaseCurrent(compact));
                assertSame(compact, lease(service));
            } finally { waiting.cancel(false); }
        }
    }

    @Test public void detachedControlsInvalidateLeaseAndCompletePendingAcquisition() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AgentAccessibilityService service = device.service;
            AgentAccessibilityService.ControlLease before = lease(service);
            device.instrumentation.runOnMainSync(() -> service.showOverlay("Lease test", false, "Synthetic approval"));
            CompletableFuture<AgentAccessibilityService.ControlLease> pending = service.acquireControlLease();
            device.instrumentation.waitForIdleSync();
            assertFalse(pending.isDone());
            device.instrumentation.runOnMainSync(service::hideOverlay);
            assertFalse(service.isControlLeaseCurrent(before));
            try {
                pending.get(3, TimeUnit.SECONDS);
                fail("Detached controls cannot grant a lease");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            } finally { pending.cancel(false); }
        }
    }

    @Test public void deviceRequestGateHasOneThreadOwnerAndDoesNotBlockUi() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            AgentAccessibilityService service = device.service;
            // No bind or permission bypass: exercise only process-wide gate ownership
            // on another instance, as created when a service is replaced.
            AgentAccessibilityService replacement = new AgentAccessibilityService();
            assertTrue(service.tryBeginDeviceCall());
            try {
                assertFalse("Replacing the service must not create a second execution lane", replacement.tryBeginDeviceCall());
                AtomicBoolean mainAcquired = new AtomicBoolean(true);
                AtomicBoolean wrongOwnerRejected = new AtomicBoolean(false);
                device.instrumentation.runOnMainSync(() -> {
                    mainAcquired.set(service.tryBeginDeviceCall());
                    try { service.endDeviceCall(); }
                    catch (IllegalStateException expected) { wrongOwnerRejected.set(true); }
                });
                assertFalse(mainAcquired.get());
                assertTrue(wrongOwnerRejected.get());
                assertFalse("A second request cannot reuse this worker's existing ownership", service.tryBeginDeviceCall());
            } finally { service.endDeviceCall(); }
            assertTrue(replacement.tryBeginDeviceCall());
            replacement.endDeviceCall();
        }
    }

    private static AgentAccessibilityService.ControlLease lease(AgentAccessibilityService service) throws Exception {
        CompletableFuture<AgentAccessibilityService.ControlLease> future = service.acquireControlLease();
        try { return future.get(5, TimeUnit.SECONDS); }
        finally { future.cancel(false); }
    }
}
