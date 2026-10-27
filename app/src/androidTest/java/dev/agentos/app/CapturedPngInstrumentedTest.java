package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import dev.agentos.core.ToolCatalog;
import dev.agentos.core.ToolRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Historical receipt/ownership evidence with synthetic pixels; no native screenshot or disclosure authority. */
@RunWith(AndroidJUnit4.class)
public final class CapturedPngInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void typedReceiptKeepsOriginalRequestAndImmutableGeometryAndTransfersOnePngOwner() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            Source source = env.source(); ToolRequest original = env.request("receipt-original");
            WindowBufferCapturer.CapturedWindow captured = env.capture(source, original);
            Rect window = captured.permit.copyWindowBounds(), root = captured.permit.copyRootBounds(), safe = captured.permit.copySafeContent();
            try (WindowPngConverter.CapturedPng receipt = new WindowPngConverter().convertCaptured(captured)) {
                assertSame("Keep the trusted immutable original, not a newly assigned call", original, receipt.getOriginalRequest());
                assertEquals(original.taskId, receipt.taskId); assertEquals(original.profileId, receipt.profileId);
                assertEquals(original.executionEpoch, receipt.executionEpoch);
                assertEquals(original.getArguments().get("expectedRevision").getAsString(), receipt.revision);
                assertEquals(env.settings.lease.configurationToken, receipt.configurationToken);
                assertEquals(FIXTURE, receipt.packageName); assertEquals(captured.permit.captureId, receipt.captureId);
                assertEquals(captured.permit.windowId, receipt.windowId); assertEquals(captured.permit.displayId, receipt.displayId);
                assertEquals(captured.permit.rotation, receipt.rotation);
                assertEquals(captured.metadata.uptimeTimestamp, receipt.captureUptimeMillis);
                assertTrue(receipt.convertedElapsedRealtimeMillis < receipt.originalCaptureDeadlineElapsedRealtimeMillis);
                assertEquals(captured.captureDeadlineElapsedRealtimeMillis(), receipt.originalCaptureDeadlineElapsedRealtimeMillis);
                assertTrue(receipt.originalCaptureDeadlineElapsedRealtimeMillis <= captured.permit.deadlineElapsedRealtimeMs);
                assertEquals(window, receipt.copyWindowBounds()); assertEquals(root, receipt.copyRootBounds()); assertEquals(safe, receipt.copySafeContent());
                receipt.copyWindowBounds().setEmpty(); receipt.copyRootBounds().setEmpty(); receipt.copySafeContent().setEmpty();
                assertEquals(window, receipt.copyWindowBounds()); assertEquals(root, receipt.copyRootBounds()); assertEquals(safe, receipt.copySafeContent());
                JsonObject detachedArguments = receipt.getOriginalRequest().getArguments();
                detachedArguments.addProperty("expectedRevision", "foreign-revision");
                assertEquals(original.getArguments(), receipt.getOriginalRequest().getArguments());
                assertFalse(receipt.isPlatformCapture()); assertFalse(receipt.isTransferred()); assertFalse(receipt.isClosed());
                assertTrue(source.buffer.isClosed()); env.awaitSlotDrained();
                assertEquals(receipt.encodedBytes, ImageWorkset.process().reservedBytes());
                ToolRequest equivalent = copy(original, original.protocolVersion, original.taskId, original.callId,
                        original.executionEpoch, original.profileId, original.tool, original.getArguments());
                try (PngImage png = receipt.claimForTesting(equivalent, env.tools, env.device.control)) {
                    assertEquals(receipt.sha256, png.sha256); assertEquals(receipt.encodedBytes, png.encodedBytes);
                    assertEquals(receipt.width, png.width); assertEquals(receipt.height, png.height);
                    assertTrue(receipt.isTransferred()); assertEquals(receipt.encodedBytes, ImageWorkset.process().reservedBytes());
                    receipt.close(); receipt.close();
                    assertFalse("Receipt close cannot revoke transferred caller ownership", png.isClosed());
                    assertReceiptFailure(WindowPngConverter.ReceiptFailure.ALREADY_TRANSFERRED,
                            () -> receipt.claimForTesting(original, env.tools, env.device.control));
                    int[] target = WindowPngConverter.targetSize(env.bounds.width(), env.bounds.height());
                    assertPixels(png, target[0], target[1]);
                }
            }
            env.assertDrained(); screenshots.capture("synthetic-receipt-original-request-immutable-history-and-one-owned-png");
        }
    }

    @Test public void malformedTypedRequestsRejectBeforeBackendAndDefaultPlatformCapabilityRemainsDisabled() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ToolRequest valid = env.request("request-validation");
            JsonObject extra = valid.getArguments(); extra.addProperty("imageDataUrl", "not-accepted");
            JsonObject missing = new JsonObject();
            List<ToolRequest> invalid = List.of(
                    copy(valid, 99, valid.taskId, valid.callId, valid.executionEpoch, valid.profileId, valid.tool, valid.getArguments()),
                    copy(valid, valid.protocolVersion, "another-task", valid.callId, valid.executionEpoch, valid.profileId, valid.tool, valid.getArguments()),
                    copy(valid, valid.protocolVersion, valid.taskId, "invalid call id", valid.executionEpoch, valid.profileId, valid.tool, valid.getArguments()),
                    copy(valid, valid.protocolVersion, valid.taskId, valid.callId, valid.executionEpoch, "foreign-profile", valid.tool, valid.getArguments()),
                    copy(valid, valid.protocolVersion, valid.taskId, valid.callId, valid.executionEpoch, valid.profileId, "observe", valid.getArguments()),
                    copy(valid, valid.protocolVersion, valid.taskId, valid.callId, valid.executionEpoch, valid.profileId, valid.tool, extra),
                    copy(valid, valid.protocolVersion, valid.taskId, valid.callId, valid.executionEpoch, valid.profileId, valid.tool, missing),
                    copy(valid, valid.protocolVersion, valid.taskId, valid.callId, valid.executionEpoch, valid.profileId, valid.tool, null));
            AtomicInteger calls = new AtomicInteger();
            for (ToolRequest request : invalid) {
                try { env.start(request, (id, callback) -> { calls.incrementAndGet(); callback.failure(95); }); fail("Malformed typed request was accepted"); }
                catch (WindowBufferCapturer.CaptureException failure) { assertEquals(WindowBufferCapturer.Failure.INVALID_REQUEST, failure.code); }
                assertFalse(WindowBufferCapturer.hasActiveCapture());
            }
            assertEquals(0, calls.get());
            WindowBufferCapturer.CaptureRequest platform = new WindowBufferCapturer(env.tools).start(valid, env.device.control);
            env.requests.add(platform);
            try (WindowBufferCapturer.CapturedWindow unexpected = platform.awaitAndClaim()) { fail("Main APK must not acquire a native capture"); }
            catch (WindowBufferCapturer.CaptureException failure) { assertEquals(WindowBufferCapturer.Failure.PLATFORM_UNAVAILABLE, failure.code); }
            env.assertDrained(); env.assertNoCapability();
            screenshots.capture("malformed-typed-request-zero-backend-and-main-platform-capability-still-disabled");
        }
    }

    @Test public void foreignRequestsOwnersAndSwappedReceiptsCannotBurnTheLegitimateOwner() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ToolRequest firstRequest = env.request("receipt-first");
            try (WindowPngConverter.CapturedPng first = new WindowPngConverter().convertCaptured(env.capture(env.source(), firstRequest))) {
                ToolRequest secondRequest = env.request("receipt-second");
                try (WindowPngConverter.CapturedPng second = new WindowPngConverter().convertCaptured(env.capture(env.source(), secondRequest))) {
                    assertNotEquals(first.captureId, second.captureId);
                    JsonObject anotherRevision = firstRequest.getArguments(); anotherRevision.addProperty("expectedRevision", "foreign-revision");
                    List<ToolRequest> changed = List.of(secondRequest,
                            copy(firstRequest, 99, firstRequest.taskId, firstRequest.callId, firstRequest.executionEpoch, firstRequest.profileId, firstRequest.tool, firstRequest.getArguments()),
                            copy(firstRequest, firstRequest.protocolVersion, "foreign-task", firstRequest.callId, firstRequest.executionEpoch, firstRequest.profileId, firstRequest.tool, firstRequest.getArguments()),
                            copy(firstRequest, firstRequest.protocolVersion, firstRequest.taskId, firstRequest.callId, firstRequest.executionEpoch + 1, firstRequest.profileId, firstRequest.tool, firstRequest.getArguments()),
                            copy(firstRequest, firstRequest.protocolVersion, firstRequest.taskId, firstRequest.callId, firstRequest.executionEpoch, "foreign-profile", firstRequest.tool, firstRequest.getArguments()),
                            copy(firstRequest, firstRequest.protocolVersion, firstRequest.taskId, firstRequest.callId, firstRequest.executionEpoch, firstRequest.profileId, "observe", firstRequest.getArguments()),
                            copy(firstRequest, firstRequest.protocolVersion, firstRequest.taskId, firstRequest.callId, firstRequest.executionEpoch, firstRequest.profileId, firstRequest.tool, anotherRevision));
                    for (ToolRequest changedRequest : changed)
                        assertReceiptFailure(WindowPngConverter.ReceiptFailure.WRONG_REQUEST,
                                () -> first.claimForTesting(changedRequest, env.tools, env.device.control));
                    AndroidDeviceTools foreignAdapter = env.newTools();
                    assertReceiptFailure(WindowPngConverter.ReceiptFailure.WRONG_OWNER,
                            () -> first.claimForTesting(firstRequest, foreignAdapter, env.device.control));
                    RunControl foreignControl = new RunControl(env.device.control.getProfileId());
                    try {
                        assertReceiptFailure(WindowPngConverter.ReceiptFailure.WRONG_OWNER,
                                () -> first.claimForTesting(firstRequest, env.tools, foreignControl));
                        assertFalse(foreignControl.isCancelled()); assertFalse(env.device.control.isCancelled());
                    } finally { foreignControl.cancel(); }
                    assertReceiptFailure(WindowPngConverter.ReceiptFailure.WRONG_REQUEST,
                            () -> second.claimForTesting(firstRequest, env.tools, env.device.control));
                    assertFalse(first.isTransferred()); assertFalse(second.isTransferred());
                    assertEquals((long) first.encodedBytes + second.encodedBytes, ImageWorkset.process().reservedBytes());
                    try (PngImage a = first.claimForTesting(firstRequest, env.tools, env.device.control);
                         PngImage b = second.claimForTesting(secondRequest, env.tools, env.device.control)) {
                        assertNotSame(a, b); assertEquals(first.sha256, a.sha256); assertEquals(second.sha256, b.sha256);
                    }
                }
            }
            env.assertDrained(); screenshots.capture("foreign-call-owner-and-swapped-receipt-cannot-consume-valid-historical-owner");
        }
    }

    @Test public void syntheticReceiptsCannotAuthorizePlatformHandoffAndDefaultCloseReleasesPng() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ToolRequest original = env.request("synthetic-provenance");
            WindowPngConverter.CapturedPng receipt = new WindowPngConverter().convertCaptured(env.capture(env.source(), original));
            try (WindowPngConverter.CapturedPng owned = receipt) {
                assertFalse(receipt.isPlatformCapture());
                assertReceiptFailure(WindowPngConverter.ReceiptFailure.NON_PLATFORM_SOURCE,
                        () -> receipt.claimForReview(original, env.tools, env.device.control));
                assertFalse(receipt.isTransferred()); assertFalse(receipt.isClosed());
                assertEquals(receipt.encodedBytes, ImageWorkset.process().reservedBytes());
            }
            assertTrue(receipt.isClosed()); assertFalse(receipt.isTransferred());
            assertReceiptFailure(WindowPngConverter.ReceiptFailure.CLOSED,
                    () -> receipt.claimForTesting(original, env.tools, env.device.control));
            receipt.close(); env.assertDrained();
            screenshots.capture("synthetic-origin-cannot-authorize-platform-review-default-close-releases-encoding");
        }
    }

    @Test public void publishedConfigurationInvalidationRejectsBeforeWaitingListenersWithoutCancellingAnotherTask() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ToolRequest original = env.request("receipt-configuration");
            try (WindowPngConverter.CapturedPng receipt = new WindowPngConverter().convertCaptured(env.capture(env.source(), original))) {
                SettingsStore store = new SettingsStore(env.device.context);
                SettingsStore.SavedSettings saved = store.load();
                try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, env.settings.lease)) {
                    held.start(saved); held.awaitPublished();
                    long checkStarted = SystemClock.elapsedRealtime();
                    try (PngImage unexpected = receipt.claimForTesting(original, env.tools, env.device.control)) {
                        fail("A published invalidation must reject without waiting for storage listeners");
                    } catch (AndroidDeviceTools.WindowCaptureException failure) {
                        assertEquals(AndroidDeviceTools.WindowCaptureFailure.CONFIGURATION_CHANGED, failure.code);
                    }
                    assertTrue("Historical guard unexpectedly waited for storage/listener work",
                            SystemClock.elapsedRealtime() - checkStarted < 1_000);
                    held.assertHeld(); assertFalse(receipt.isTransferred()); assertFalse(env.device.control.isCancelled());
                    held.finish();
                }
            }
            env.assertDrained(); screenshots.capture("receipt-reads-original-real-lease-before-invalidation-listeners-return");
        }
    }

    @Test public void pauseThenResumeCannotRebindHistoricalPngToTheNewEpoch() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ToolRequest original = env.request("receipt-original-epoch");
            try (WindowPngConverter.CapturedPng receipt = new WindowPngConverter().convertCaptured(env.capture(env.source(), original))) {
                env.device.control.pause(); env.device.control.resume();
                assertTrue(env.device.control.getExecutionEpoch() > original.executionEpoch);
                try (PngImage unexpected = receipt.claimForTesting(original, env.tools, env.device.control)) { fail("Old epoch must remain invalid"); }
                catch (TaskControlException paused) { assertEquals("TASK_PAUSED", paused.getCode()); }
                ToolRequest rebound = copy(original, original.protocolVersion, original.taskId, original.callId,
                        env.device.control.getExecutionEpoch(), original.profileId, original.tool, original.getArguments());
                assertReceiptFailure(WindowPngConverter.ReceiptFailure.WRONG_REQUEST,
                        () -> receipt.claimForTesting(rebound, env.tools, env.device.control));
                assertEquals(original.executionEpoch, receipt.executionEpoch); assertFalse(receipt.isTransferred());
            }
            env.assertDrained(); screenshots.capture("historical-receipt-keeps-original-epoch-after-pause-resume");
        }
    }

    @Test public void untypedSourcesAndEncoderExceptionOrErrorNeverLeakOrMintAReceipt() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            Source rawSource = env.source();
            WindowBufferCapturer.CapturedWindow untyped = env.captureUntyped(rawSource);
            try (WindowPngConverter.CapturedPng unexpected = new WindowPngConverter().convertCaptured(untyped)) {
                fail("Legacy raw conversion cannot gain a trusted call ID after capture");
            } catch (WindowPngConverter.ReceiptException rejected) {
                assertEquals(WindowPngConverter.ReceiptFailure.MISSING_TYPED_REQUEST, rejected.code);
            }
            assertTrue(rawSource.buffer.isClosed()); env.assertDrained();
            for (Throwable injected : new Throwable[]{new IOException("Synthetic encoder failure"), new AssertionError("Synthetic encoder error")}) {
                ToolRequest original = env.request("encoder-failure-" + injected.getClass().getSimpleName());
                Source source = env.source(); WindowBufferCapturer.CapturedWindow captured = env.capture(source, original);
                WindowPngConverter failing = WindowPngConverter.forTesting(hardware -> hardware.copy(Bitmap.Config.ARGB_8888, false),
                        (bitmap, output) -> {
                            if (injected instanceof Error) throw (Error) injected;
                            throw (IOException) injected;
                        });
                Throwable actual = null;
                try (WindowPngConverter.CapturedPng unexpected = failing.convertCaptured(captured)) { fail("Encoder failure minted a receipt"); }
                catch (Throwable failed) { actual = failed; }
                assertSame(injected, actual); assertTrue(source.buffer.isClosed()); env.assertDrained();
            }
            screenshots.capture("legacy-capture-cannot-gain-call-identity-and-exception-error-close-every-owner");
        }
    }

    @Test public void originalFiveSecondDeadlineBlocksLateMintButDoesNotBecomeANewHistoricalPermit() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            ToolRequest firstRequest = env.request("historical-before-deadline");
            try (WindowPngConverter.CapturedPng first = new WindowPngConverter().convertCaptured(env.capture(env.source(), firstRequest))) {
                long originalDeadline = first.originalCaptureDeadlineElapsedRealtimeMillis;
                ToolRequest secondRequest = env.request("late-conversion-must-fail");
                Source secondSource = env.source(); WindowBufferCapturer.CapturedWindow second = env.capture(secondSource, secondRequest);
                CountDownLatch encoded = new CountDownLatch(1), release = new CountDownLatch(1);
                WindowPngConverter delayed = WindowPngConverter.forTesting(hardware -> hardware.copy(Bitmap.Config.ARGB_8888, false),
                        (bitmap, output) -> {
                            boolean result = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                            encoded.countDown();
                            if (!release.await(7, TimeUnit.SECONDS)) throw new IOException("Test encoder release deadline elapsed");
                            return result;
                        });
                ExecutorService worker = Executors.newSingleThreadExecutor();
                Future<Throwable> conversion = worker.submit(() -> {
                    try (WindowPngConverter.CapturedPng unexpected = delayed.convertCaptured(second)) {
                        return new AssertionError("Expired converter minted a receipt");
                    } catch (Throwable failed) { return failed; }
                });
                try {
                    assertTrue(encoded.await(2, TimeUnit.SECONDS));
                    assertTrue(secondSource.buffer.isClosed()); assertTrue(WindowBufferCapturer.hasActiveCapture());
                    // One real bounded five-second wait covers both sides. Never
                    // refresh the permit or use a synthetic 60-second review clock.
                    long untilExpired = second.permit.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime() + 1;
                    assertTrue("Original capture deadline was unexpectedly extended", untilExpired <= 5_001);
                    if (untilExpired > 0) SystemClock.sleep(untilExpired);
                    release.countDown();
                    Throwable failed = conversion.get(3, TimeUnit.SECONDS);
                    assertTrue(String.valueOf(failed), failed instanceof AndroidDeviceTools.WindowCaptureException);
                    assertEquals(AndroidDeviceTools.WindowCaptureFailure.EXPIRED, ((AndroidDeviceTools.WindowCaptureException) failed).code);
                    env.awaitSlotDrained();
                    assertTrue(SystemClock.elapsedRealtime() > originalDeadline);
                    assertEquals(originalDeadline, first.originalCaptureDeadlineElapsedRealtimeMillis);
                    assertTrue(first.convertedElapsedRealtimeMillis < originalDeadline);
                    assertEquals(first.encodedBytes, ImageWorkset.process().reservedBytes());
                    int callsBefore = env.backendCalls.get();
                    try (PngImage image = first.claimForTesting(firstRequest, env.tools, env.device.control)) {
                        assertEquals(first.sha256, image.sha256); assertEquals(callsBefore, env.backendCalls.get());
                    }
                } finally {
                    release.countDown(); worker.shutdown();
                    assertTrue("Late conversion worker did not actually return", worker.awaitTermination(8, TimeUnit.SECONDS));
                }
            }
            env.assertDrained(); screenshots.capture("fixed-capture-expiry-rejects-late-mint-history-does-not-refresh-or-recapture");
        }
    }

    private boolean unsupported(Environment env) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) return false;
        ToolRequest request = env.request("low-api");
        try { env.start(request, (id, callback) -> { env.backendCalls.incrementAndGet(); callback.failure(96); }); fail("Low API capture was accepted"); }
        catch (AndroidDeviceTools.WindowCaptureException unavailable) { assertEquals(AndroidDeviceTools.WindowCaptureFailure.UNSUPPORTED_API, unavailable.code); }
        assertEquals(0, env.backendCalls.get()); env.assertDrained();
        screenshots.capture("typed-window-receipt-unavailable-below-api34-no-native-capture"); return true;
    }
    private static ToolRequest copy(ToolRequest ignored, int version, String task, String call, long epoch,
                                    String profile, String tool, JsonObject arguments) {
        return new ToolRequest(version, task, call, epoch, profile, tool, arguments);
    }
    private static void assertReceiptFailure(WindowPngConverter.ReceiptFailure code, PngAction action) throws Exception {
        try (PngImage unexpected = action.run()) { fail("Expected receipt rejection " + code); }
        catch (WindowPngConverter.ReceiptException failure) { assertEquals(failure.toString(), code, failure.code); }
    }
    private interface PngAction { PngImage run() throws Exception; }
    private static void assertPixels(PngImage image, int width, int height) throws Exception {
        Bitmap decoded;
        try (PngImage.ReadLease reader = image.openRead()) { decoded = BitmapFactory.decodeStream(reader); }
        assertNotNull(decoded);
        try { assertEquals(width, decoded.getWidth()); assertEquals(height, decoded.getHeight()); assertEquals(Color.CYAN, decoded.getPixel(width / 2, height / 2)); }
        finally { decoded.recycle(); }
    }
    private static final class Source {
        final HardwareBuffer buffer;
        final ColorSpace colorSpace;
        volatile boolean transferred;
        Source(HardwareBuffer buffer, ColorSpace colorSpace) { this.buffer = buffer; this.colorSpace = colorSpace; }
    }
    private static final class Environment implements AutoCloseable {
        DeviceHarness device;
        LocalControllerModelServer server;
        ControllerTestSettings settings;
        AndroidDeviceTools tools;
        Rect bounds;
        final AtomicInteger backendCalls = new AtomicInteger();
        final List<Source> sources = new ArrayList<>();
        final List<WindowBufferCapturer.CaptureRequest> requests = new ArrayList<>();
        final List<WindowBufferCapturer.CapturedWindow> captures = new ArrayList<>();
        Environment() throws Exception {
            try {
                await(() -> !WindowBufferCapturer.hasActiveCapture() && ImageWorkset.process().reservedBytes() == 0, "Previous image owners have not drained");
                device = new DeviceHarness(); server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(device.context, server); tools = newTools();
                device.launch("CapturePatternActivity"); observe(); bounds = focusedBounds(device);
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        AndroidDeviceTools newTools() throws Exception {
            return new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                    ActionConfirmationPolicy.Mode.CONFIRM_ALL, settings.lease,
                    (message, control, epoch) -> { throw new AssertionError("Receipt is not a disclosure approval"); });
        }
        JsonObject observe() throws Exception { return awaitObservation(tools, device.control, data -> FIXTURE.equals(string(data, "packageName"))); }
        ToolRequest request(String callId) throws Exception {
            JsonObject data = observe(), arguments = new JsonObject(); arguments.addProperty("expectedRevision", string(data, "revision"));
            return new ToolRequest(ToolCatalog.PROTOCOL_VERSION, device.control.getTaskId(), callId,
                    device.control.getExecutionEpoch(), device.control.getProfileId(), "take_screenshot", arguments);
        }
        Source source() {
            if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("Synthetic window sources require API 34");
            Bitmap software = null, hardware = null; HardwareBuffer buffer = null; boolean retained = false;
            try {
                software = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888); software.eraseColor(Color.CYAN);
                hardware = software.copy(Bitmap.Config.HARDWARE, false); assertNotNull(hardware);
                buffer = hardware.getHardwareBuffer(); assertNotNull(buffer);
                Source source = new Source(buffer, hardware.getColorSpace()); sources.add(source); retained = true; return source;
            } finally {
                if (hardware != null) hardware.recycle(); if (software != null) software.recycle(); if (!retained && buffer != null) buffer.close();
            }
        }
        WindowBufferCapturer.Backend backend(Source source) {
            return (id, callback) -> {
                assertTrue(id >= 0); backendCalls.incrementAndGet(); source.transferred = true;
                callback.success(source.buffer, source.colorSpace, SystemClock.uptimeMillis());
            };
        }
        WindowBufferCapturer.CaptureRequest start(ToolRequest request, WindowBufferCapturer.Backend backend) throws Exception {
            WindowBufferCapturer.CaptureRequest started = WindowBufferCapturer.forTesting(tools, backend).start(request, device.control);
            requests.add(started); return started;
        }
        WindowBufferCapturer.CapturedWindow capture(Source source, ToolRequest request) throws Exception {
            WindowBufferCapturer.CapturedWindow captured = start(request, backend(source)).awaitAndClaim(); captures.add(captured); return captured;
        }
        WindowBufferCapturer.CapturedWindow captureUntyped(Source source) throws Exception {
            JsonObject current = observe();
            WindowBufferCapturer.CaptureRequest request = WindowBufferCapturer.forTesting(tools, backend(source))
                    .start(device.control, device.control.getExecutionEpoch(), string(current, "revision"));
            requests.add(request); WindowBufferCapturer.CapturedWindow captured = request.awaitAndClaim(); captures.add(captured); return captured;
        }
        void awaitSlotDrained() throws Exception {
            await(() -> requests.stream().allMatch(WindowBufferCapturer.CaptureRequest::isWorkerDone)
                    && !WindowBufferCapturer.hasActiveCapture(), "Actual request/slot ownership has not drained");
        }
        void assertDrained() throws Exception { awaitSlotDrained(); assertEquals(0, ImageWorkset.process().reservedBytes()); assertNoCapability(); }
        void assertNoCapability() {
            assertEquals(0, server.requestCount()); assertEquals(0, device.control.getDispatchAttempts());
            assertEquals(0, device.service.getServiceInfo().getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
            assertFalse(tools.availableTools().contains("take_screenshot"));
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try {
                if (device != null) device.control.cancel();
                for (WindowBufferCapturer.CapturedWindow captured : captures) captured.close();
                for (WindowBufferCapturer.CaptureRequest request : requests) request.close();
                awaitSlotDrained();
                for (Source source : sources) if (source.transferred) assertTrue("Transferred native handle must be closed by its owner", source.buffer.isClosed());
                assertEquals(0, ImageWorkset.process().reservedBytes());
            } catch (Exception | Error failed) { failure = failed; }
            finally {
                for (Source source : sources) if (!source.transferred) try { source.buffer.close(); }
                    catch (RuntimeException | Error failed) { failure = append(failure, failed); }
                try { if (settings != null) settings.close(); } catch (Exception | Error failed) { failure = append(failure, failed); }
                finally {
                    try { if (server != null) server.close(); } catch (Exception | Error failed) { failure = append(failure, failed); }
                    finally { try { if (device != null) device.close(); } catch (Exception | Error failed) { failure = append(failure, failed); } }
                }
            }
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof Exception) throw (Exception) failure;
        }
    }
    private static Rect focusedBounds(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            Rect found = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) { assertNull(found); found = new Rect(); window.getBoundsInScreen(found); }
            assertNotNull(found); return found;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; if (first != next) first.addSuppressed(next); return first; }
}
