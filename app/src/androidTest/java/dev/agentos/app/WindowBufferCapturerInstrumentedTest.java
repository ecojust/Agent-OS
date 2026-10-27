package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Real saved configuration/device guards plus synthetic buffers; never native screenshot evidence. */
@RunWith(AndroidJUnit4.class)
public final class WindowBufferCapturerInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void oneClaimAndLastReaderOwnTheSlotAndLogicalReservation() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend);
            backend.awaitEntered();
            HardwareBuffer buffer = env.buffer(0);
            env.success(backend, buffer, SystemClock.uptimeMillis());
            try (WindowBufferCapturer.CapturedWindow captured = request.awaitAndClaim()) {
                assertEquals(env.bounds.width(), captured.metadata.width);
                assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), captured.metadata.colorSpace);
                assertFailure(WindowBufferCapturer.Failure.ALREADY_CLAIMED, request::awaitAndClaim);
                try (OwnedWindowBuffer.ReadLease reader = captured.openRead()) {
                    assertSame(buffer, reader.buffer());
                    captured.close(); request.close();
                    assertFalse(buffer.isClosed()); assertTrue(WindowBufferCapturer.hasActiveCapture());
                    assertEquals(env.logicalBytes(), ImageWorkset.process().reservedBytes());
                }
                assertTrue(buffer.isClosed());
            }
            env.awaitDrained(request); env.assertNoCaptureCapability();
            screenshots.capture("synthetic-buffer-claimed-once-last-reader-retains-slot-no-native-capture");
        }
    }

    @Test public void callbackBeforeSubmitReturnsCannotReleaseTheActualDeviceGate() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(true);
            WindowBufferCapturer.CaptureRequest request = env.start(backend);
            backend.awaitEntered();
            HardwareBuffer buffer = env.buffer(0);
            env.success(backend, buffer, SystemClock.uptimeMillis());
            assertFalse(request.hasSubmissionReturned()); assertFalse(request.isWorkerDone());
            assertGateBusy(env.device); assertTrue(WindowBufferCapturer.hasActiveCapture());
            env.device.control.cancel();
            try { request.awaitAndClaim(); fail("Cancelled caller must not wait for synchronous submission"); }
            catch (TaskControlException stopped) { assertEquals("TASK_STOPPED", stopped.getCode()); }
            assertGateBusy(env.device); assertTrue(WindowBufferCapturer.hasActiveCapture());
            backend.release.countDown();
            env.awaitDrained(request); assertTrue(buffer.isClosed());
            screenshots.capture("early-callback-does-not-release-blocked-submission-gate");
        }
    }

    @Test public void returnedSubmissionLetsStructuredObserveRunWhileTheImageSlotStaysBusy() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend);
            backend.awaitEntered(); await(request::hasSubmissionReturned, "Submission did not return");
            JsonObject fresh = env.observe();
            assertEquals(FIXTURE, string(fresh, "packageName"));
            assertFailure(WindowBufferCapturer.Failure.BUSY, () -> env.start(new ControlledBackend(false)));
            HardwareBuffer buffer = env.buffer(0);
            env.success(backend, buffer, SystemClock.uptimeMillis());
            try (WindowBufferCapturer.CapturedWindow captured = request.awaitAndClaim()) {
                assertEquals(env.bounds.height(), captured.metadata.height);
            }
            env.awaitDrained(request); env.assertNoCaptureCapability();
            screenshots.capture("structured-observation-continues-while-synthetic-image-callback-is-pending");
        }
    }

    @Test public void cancelledLateResultClosesWithoutReadingAnotherDeviceSnapshot() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend);
            backend.awaitEntered(); await(request::hasSubmissionReturned, "Submission did not return");
            env.device.control.cancel();
            try { request.awaitAndClaim(); fail("Stopped capture must not publish a result"); }
            catch (TaskControlException stopped) { assertEquals("TASK_STOPPED", stopped.getCode()); }
            await(request::isWorkerDone, "Stopped worker did not finish");
            assertTrue(WindowBufferCapturer.hasActiveCapture());
            HardwareBuffer buffer = env.buffer(0);
            try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                env.success(backend, buffer, SystemClock.uptimeMillis());
                env.awaitDrained(request);
                assertEquals(0, trace.overwrittenCount());
                for (PerformanceTrace.Event event : trace.events())
                    assertFalse("A cancelled result must not read a new snapshot", event.stage.name().startsWith("SNAPSHOT_"));
            }
            assertTrue(buffer.isClosed());
            screenshots.capture("cancelled-late-synthetic-buffer-closed-without-post-snapshot");
        }
    }

    @Test public void submissionExceptionRetainsTheSlotUntilAnActualTerminalCallback() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            backend.afterEntryFailure = new IOException("Synthetic uncertain submission");
            WindowBufferCapturer.CaptureRequest request = env.start(backend);
            backend.awaitEntered();
            assertFailure(WindowBufferCapturer.Failure.SUBMISSION_UNCERTAIN, request::awaitAndClaim);
            await(request::isWorkerDone, "Failed worker did not finish");
            assertTrue(WindowBufferCapturer.hasActiveCapture());
            assertEquals(env.logicalBytes(), ImageWorkset.process().reservedBytes());
            env.observe();
            backend.callback.failure(71);
            env.awaitDrained(request);
            screenshots.capture("uncertain-submission-holds-slot-until-explicit-synthetic-failure-callback");
        }
    }

    @Test public void missingCallbackTimesOutButDoesNotPretendTheNativeRequestEnded() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend);
            backend.awaitEntered();
            assertFailure(WindowBufferCapturer.Failure.TIMEOUT, request::awaitAndClaim);
            await(request::isWorkerDone, "Expired worker did not finish");
            assertTrue(WindowBufferCapturer.hasActiveCapture());
            assertEquals(env.logicalBytes(), ImageWorkset.process().reservedBytes());
            env.observe();
            backend.callback.failure(72);
            env.awaitDrained(request);
            screenshots.capture("missing-callback-expiry-keeps-slot-until-real-test-terminal-signal");
        }
    }

    @Test public void duplicateAliasesAndOldRequestCleanupCannotCloseNewCaptureOwnership() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend first = new ControlledBackend(true);
            WindowBufferCapturer.CaptureRequest old = env.start(first); first.awaitEntered();
            HardwareBuffer primary = env.buffer(0), independent = env.buffer(0), alias = alias(primary);
            env.handles.add(alias);
            env.success(first, primary, SystemClock.uptimeMillis());
            env.success(first, primary, SystemClock.uptimeMillis());
            env.success(first, alias, SystemClock.uptimeMillis());
            env.success(first, independent, SystemClock.uptimeMillis());
            assertFalse(primary.isClosed()); assertTrue(alias.isClosed()); assertTrue(independent.isClosed());
            assertEquals(3, old.duplicateCallbacks()); first.release.countDown();
            try (WindowBufferCapturer.CapturedWindow result = old.awaitAndClaim()) { assertEquals(env.bounds.width(), result.metadata.width); }
            env.awaitDrained(old);
            ControlledBackend second = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest current = env.start(second); second.awaitEntered();
            old.close(); env.success(first, primary, SystemClock.uptimeMillis());
            assertTrue(WindowBufferCapturer.hasActiveCapture());
            HardwareBuffer fresh = env.buffer(0);
            env.success(second, fresh, SystemClock.uptimeMillis());
            try (WindowBufferCapturer.CapturedWindow result = current.awaitAndClaim()) {
                try (OwnedWindowBuffer.ReadLease reader = result.openRead()) { assertSame(fresh, reader.buffer()); }
            }
            env.awaitDrained(current);
            screenshots.capture("duplicate-java-identities-and-old-token-do-not-close-new-buffer-owner");
        }
    }

    @Test public void fullLogicalBudgetRejectsBeforeBackendAndWrongDimensionsCloseTheResult() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend none = new ControlledBackend(false);
            try (ImageWorkset.Reservation full = ImageWorkset.process().reserve(ImageWorkset.process().limitBytes())) {
                WindowBufferCapturer.CaptureRequest rejected = env.start(none);
                assertFailure(WindowBufferCapturer.Failure.BUDGET_EXCEEDED, rejected::awaitAndClaim);
                await(() -> rejected.isWorkerDone() && !WindowBufferCapturer.hasActiveCapture(), "Budget rejection did not release slot");
                assertEquals(0, none.calls.get());
            }
            ControlledBackend wrong = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(wrong); wrong.awaitEntered();
            HardwareBuffer buffer = env.buffer(1);
            env.success(wrong, buffer, SystemClock.uptimeMillis());
            assertFailure(WindowBufferCapturer.Failure.INVALID_BUFFER, request::awaitAndClaim);
            env.awaitDrained(request); assertTrue(buffer.isClosed());
            screenshots.capture("logical-budget-before-submission-and-real-buffer-metadata-rejection");
        }
    }

    @Test public void changedFocusedWindowRejectsARealSyntheticBufferDuringPostValidation() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend); backend.awaitEntered();
            await(request::hasSubmissionReturned, "Submission did not return");
            env.device.launch("SecondActivity");
            HardwareBuffer buffer = env.buffer(0);
            env.success(backend, buffer, SystemClock.uptimeMillis());
            try { request.awaitAndClaim(); fail("Changed focus must reject the old result"); }
            catch (AndroidDeviceTools.WindowCaptureException stale) {
                assertEquals(AndroidDeviceTools.WindowCaptureFailure.STALE_OBSERVATION, stale.code);
            }
            env.awaitDrained(request); assertTrue(buffer.isClosed());
            screenshots.capture("fresh-post-snapshot-rejects-old-window-synthetic-buffer");
        }
    }

    @Test public void revokedConfigurationRejectsNewReadsWithoutClosingAnExistingReaderEarly() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend); backend.awaitEntered();
            HardwareBuffer buffer = env.buffer(0);
            env.success(backend, buffer, SystemClock.uptimeMillis());
            try (WindowBufferCapturer.CapturedWindow captured = request.awaitAndClaim(); OwnedWindowBuffer.ReadLease reader = captured.openRead()) {
                SettingsStore store = new SettingsStore(env.device.context);
                SettingsStore.SavedSettings saved = store.load();
                try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, env.settings.lease)) {
                    held.start(saved); held.awaitPublished();
                    try { captured.openRead(); fail("Revoked configuration must reject a new reader"); }
                    catch (AndroidDeviceTools.WindowCaptureException changed) {
                        assertEquals(AndroidDeviceTools.WindowCaptureFailure.CONFIGURATION_CHANGED, changed.code);
                    }
                    assertFalse(env.device.control.isCancelled()); held.assertHeld();
                    assertFalse(buffer.isClosed()); assertSame(buffer, reader.buffer());
                    assertTrue(WindowBufferCapturer.hasActiveCapture()); held.finish();
                }
            }
            env.awaitDrained(request); assertTrue(buffer.isClosed());
            screenshots.capture("revoked-real-configuration-rejects-new-read-while-existing-reader-drains");
        }
    }

    @Test public void disabledProductionCapabilityFailsBeforeNativeAndDoesNotPoisonTheSlot() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            for (int attempt = 0; attempt < 2; attempt++) {
                WindowBufferCapturer.CaptureRequest request = env.startProduction();
                assertFailure(WindowBufferCapturer.Failure.PLATFORM_UNAVAILABLE, request::awaitAndClaim);
                env.awaitDrained(request);
            }
            env.assertNoCaptureCapability();
            screenshots.capture("actual-production-capability-disabled-no-native-call-and-no-slot-poisoning");
        }
    }

    @Test public void timestampsOutsideTheActualSubmissionAndCallbackIntervalAreRejected() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            for (long invalid : new long[]{0, Long.MAX_VALUE}) {
                ControlledBackend backend = new ControlledBackend(false);
                WindowBufferCapturer.CaptureRequest request = env.start(backend); backend.awaitEntered();
                HardwareBuffer buffer = env.buffer(0);
                env.success(backend, buffer, invalid);
                assertFailure(WindowBufferCapturer.Failure.INVALID_BUFFER, request::awaitAndClaim);
                env.awaitDrained(request); assertTrue(buffer.isClosed());
            }
            screenshots.capture("synthetic-capture-stale-and-future-timestamps-rejected");
        }
    }

    @Test public void closingAnUnclaimedReadyResultCannotLoseItsNativeOwner() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend); backend.awaitEntered();
            HardwareBuffer buffer = env.buffer(0); env.success(backend, buffer, SystemClock.uptimeMillis());
            await(request::isWorkerDone, "Ready result worker did not return");
            assertFalse(buffer.isClosed()); assertTrue(WindowBufferCapturer.hasActiveCapture());
            request.close();
            assertFailure(WindowBufferCapturer.Failure.ABANDONED, request::awaitAndClaim);
            env.awaitDrained(request); assertTrue(buffer.isClosed());
            screenshots.capture("unclaimed-ready-result-close-retains-a-real-owner-until-native-close");
        }
    }

    @Test public void closingDuringTheRealPostSnapshotKeepsTheWorkersNativeReadLeaseAlive() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            env.device.launch("SlowActivity"); env.observe(); env.bounds = focusedBounds(env.device);
            ControlledBackend backend = new ControlledBackend(false);
            WindowBufferCapturer.CaptureRequest request = env.start(backend); backend.awaitEntered();
            await(request::hasSubmissionReturned, "Submission did not return");
            String token = UUID.randomUUID().toString();
            try {
                assertFalse(barrier(env.device, "arm", token).getBoolean("claimed"));
                HardwareBuffer buffer = env.buffer(0); env.success(backend, buffer, SystemClock.uptimeMillis());
                long deadline = SystemClock.elapsedRealtime() + 2_000;
                Bundle blocked;
                do {
                    blocked = barrier(env.device, "status", token);
                    if (blocked.getBoolean("claimed")) break;
                    SystemClock.sleep(20);
                } while (SystemClock.elapsedRealtime() < deadline);
                assertTrue("The actual post-result root read did not enter the fixture barrier", blocked.getBoolean("claimed"));
                assertFalse(blocked.getBoolean("returned")); assertFalse(blocked.getBoolean("expired"));
                request.close();
                assertFailure(WindowBufferCapturer.Failure.ABANDONED, request::awaitAndClaim);
                assertFalse("The post worker still owns its native read lease", buffer.isClosed());
                assertTrue(WindowBufferCapturer.hasActiveCapture()); assertGateBusy(env.device);
                assertEquals(env.logicalBytes(), ImageWorkset.process().reservedBytes());
                Bundle finished = barrier(env.device, "finish", token);
                assertTrue(finished.getBoolean("returned")); assertFalse(finished.getBoolean("expired"));
                env.awaitDrained(request); assertTrue(buffer.isClosed());
                screenshots.capture("caller-close-during-real-post-read-preserves-native-reader-until-return");
            } finally {
                request.close(); env.device.control.cancel();
                try { barrier(env.device, "finish", token); }
                finally { barrier(env.device, "clear", token); }
            }
        }
    }

    private boolean unsupported(Environment env) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) return false;
        try { env.start(new ControlledBackend(false)); fail("Low API must not start window capture"); }
        catch (AndroidDeviceTools.WindowCaptureException unavailable) { assertEquals(AndroidDeviceTools.WindowCaptureFailure.UNSUPPORTED_API, unavailable.code); }
        assertFalse(WindowBufferCapturer.hasActiveCapture()); env.assertNoCaptureCapability();
        screenshots.capture("raw-window-capturer-unavailable-below-api34-no-native-call"); return true;
    }

    private static final class ControlledBackend implements WindowBufferCapturer.Backend {
        final CountDownLatch entered = new CountDownLatch(1), release;
        final AtomicInteger calls = new AtomicInteger();
        volatile WindowBufferCapturer.Callback callback;
        volatile IOException afterEntryFailure;
        ControlledBackend(boolean blocked) { release = new CountDownLatch(blocked ? 1 : 0); }
        @Override public void submit(int windowId, WindowBufferCapturer.Callback callback) throws Exception {
            assertTrue(windowId >= 0); this.callback = callback; calls.incrementAndGet(); entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IOException("Synthetic submission gate timed out");
            if (afterEntryFailure != null) throw afterEntryFailure;
        }
        void awaitEntered() throws Exception { assertTrue("Actual B1 submission did not enter the controlled backend", entered.await(5, TimeUnit.SECONDS)); }
    }

    private static final class Environment implements AutoCloseable {
        DeviceHarness device;
        LocalControllerModelServer server;
        ControllerTestSettings settings;
        AndroidDeviceTools tools;
        Rect bounds;
        final List<WindowBufferCapturer.CaptureRequest> requests = new ArrayList<>();
        final List<ControlledBackend> backends = new ArrayList<>();
        final List<HardwareBuffer> handles = new ArrayList<>();
        final Set<HardwareBuffer> transferred = Collections.newSetFromMap(new IdentityHashMap<>());
        Environment() throws Exception {
            try {
                await(() -> !WindowBufferCapturer.hasActiveCapture() && ImageWorkset.process().reservedBytes() == 0, "Previous image resources have not drained");
                device = new DeviceHarness(); server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(device.context, server);
                tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                        ActionConfirmationPolicy.Mode.CONFIRM_ALL, settings.lease,
                        (message, owner, epoch) -> { throw new AssertionError("Raw capture is not disclosure approval"); });
                device.launch("CapturePatternActivity"); observe(); bounds = focusedBounds(device);
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        JsonObject observe() throws Exception { return awaitObservation(tools, device.control, data -> FIXTURE.equals(string(data, "packageName"))); }
        WindowBufferCapturer.CaptureRequest start(ControlledBackend backend) throws Exception {
            backends.add(backend);
            JsonObject current = observe();
            WindowBufferCapturer.CaptureRequest request = WindowBufferCapturer.forTesting(tools, backend)
                    .start(device.control, device.control.getExecutionEpoch(), string(current, "revision"));
            requests.add(request); return request;
        }
        WindowBufferCapturer.CaptureRequest startProduction() throws Exception {
            JsonObject current = observe();
            WindowBufferCapturer.CaptureRequest request = new WindowBufferCapturer(tools)
                    .start(device.control, device.control.getExecutionEpoch(), string(current, "revision"));
            requests.add(request); return request;
        }
        long logicalBytes() { return (long) bounds.width() * bounds.height() * 8; }
        HardwareBuffer buffer(int extraWidth) {
            HardwareBuffer buffer = HardwareBuffer.create(bounds.width() + extraWidth, bounds.height(), HardwareBuffer.RGBA_8888, 1,
                    HardwareBuffer.USAGE_CPU_READ_RARELY | HardwareBuffer.USAGE_CPU_WRITE_RARELY);
            handles.add(buffer); return buffer;
        }
        void success(ControlledBackend backend, HardwareBuffer buffer, long timestamp) {
            WindowBufferCapturer.Callback callback = backend.callback;
            assertNotNull(callback);
            transferred.add(buffer); // Ownership transfers when the callback is entered.
            callback.success(buffer, ColorSpace.get(ColorSpace.Named.SRGB), timestamp);
        }
        void awaitDrained(WindowBufferCapturer.CaptureRequest request) throws Exception {
            await(() -> request.isWorkerDone() && !WindowBufferCapturer.hasActiveCapture(), "Synthetic capture did not drain");
            assertEquals(0, ImageWorkset.process().reservedBytes());
        }
        void assertNoCaptureCapability() {
            assertEquals(0, device.control.getDispatchAttempts()); assertEquals(0, server.requestCount());
            assertEquals(0, device.service.getServiceInfo().getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
            assertFalse(tools.availableTools().contains("take_screenshot"));
        }
        @Override public void close() throws Exception {
            Throwable error = null;
            try {
                if (device != null) device.control.cancel();
                for (WindowBufferCapturer.CaptureRequest request : requests) request.close();
                for (ControlledBackend backend : backends) backend.release.countDown();
                // A worker may have crossed its last guard just before cancel.
                // Wait for actual synchronous return before looking up callbacks;
                // a one-time early null check could miss a newly entered backend.
                await(() -> requests.stream().allMatch(WindowBufferCapturer.CaptureRequest::isWorkerDone), "Test workers did not actually return");
                for (ControlledBackend backend : backends)
                    if (backend.callback != null) backend.callback.failure(99); // Explicit test terminal, never force-clear a slot.
                await(() -> !WindowBufferCapturer.hasActiveCapture(), "Test cleanup did not receive/close all owned results");
                for (HardwareBuffer handle : handles)
                    if (transferred.contains(handle)) assertTrue("Transferred test handle was not closed by its actual owner", handle.isClosed());
            } catch (Exception | Error failed) { error = failed; }
            finally {
                for (HardwareBuffer handle : handles) if (!transferred.contains(handle)) {
                    try { handle.close(); } catch (RuntimeException | Error failed) { error = append(error, failed); }
                }
                try { if (settings != null) settings.close(); }
                catch (Exception | Error failed) { error = append(error, failed); }
                finally {
                    try { if (server != null) server.close(); }
                    catch (Exception | Error failed) { error = append(error, failed); }
                    finally { try { if (device != null) device.close(); } catch (Exception | Error failed) { error = append(error, failed); } }
                }
            }
            if (error instanceof Error) throw (Error) error;
            if (error != null) throw (Exception) error;
        }
    }

    private static HardwareBuffer alias(HardwareBuffer original) {
        Parcel parcel = Parcel.obtain();
        try { original.writeToParcel(parcel, 0); parcel.setDataPosition(0); return HardwareBuffer.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private static Bundle barrier(DeviceHarness device, String method, String token) {
        Bundle result = device.context.getContentResolver().call(Uri.parse("content://dev.agentos.fixture.slow-read-control"), method, token, null);
        assertNotNull(result); return result;
    }
    private static Rect focusedBounds(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            Rect bounds = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) { assertNull(bounds); bounds = new Rect(); window.getBoundsInScreen(bounds); }
            assertNotNull(bounds); return bounds;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }
    private static void assertGateBusy(DeviceHarness device) {
        boolean acquired = device.service.tryBeginDeviceCall();
        if (acquired) device.service.endDeviceCall();
        assertFalse("Actual synchronous device gate must remain owned", acquired);
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }
    private static void assertFailure(WindowBufferCapturer.Failure code, CheckedAction action) throws Exception {
        try { action.run(); fail("Expected capture failure " + code); }
        catch (WindowBufferCapturer.CaptureException failure) { assertEquals(failure.toString(), code, failure.code); }
    }
    private interface CheckedAction { void run() throws Exception; }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; first.addSuppressed(next); return first; }
}
