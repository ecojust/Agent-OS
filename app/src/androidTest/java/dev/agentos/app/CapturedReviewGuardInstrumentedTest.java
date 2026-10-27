package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import dev.agentos.core.ToolCatalog;
import dev.agentos.core.ToolRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/**
 * Real configuration and read-only target guards after synthetic ownership transfer.
 * No review UI, user approval, removal acknowledgement, disclosure or native capture is exercised.
 */
@RunWith(AndroidJUnit4.class)
public final class CapturedReviewGuardInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void contextUsesTheOriginalSavedRecipientAndRealInstallIdentityAndCachesOneImmutableBinding() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            try (Input input = env.input("trusted-review-context")) {
                long before = ImageWorkset.process().reservedBytes();
                AndroidDeviceTools.CapturedReviewContext context = input.prepare();
                assertSame(context, input.prepare()); assertSame(env.settings.lease, context.configuration);
                assertSame(context.binding, context.currentBinding());
                ImageReviewSession.Binding binding = context.binding;
                SettingsStore.AppGrant actual = SettingsStore.captureGrant(env.device.context, FIXTURE);
                assertTrue(SettingsStore.isGrantValid(env.device.context, actual));
                assertEquals(actual.uid, binding.applicationIdentity.uid);
                assertEquals(actual.firstInstallTime, binding.applicationIdentity.firstInstallTime);
                assertEquals(actual.signers, binding.applicationIdentity.signerDigests);
                assertEquals(new ImageReviewSession.Recipient(env.settings.model.endpoint, env.settings.model.model), binding.recipient);
                assertEquals(input.request.callId, binding.callId); assertEquals(input.receipt.captureId, binding.captureId);
                assertEquals(env.settings.lease.configurationToken, binding.configurationToken);
                assertEquals(input.request.taskId, binding.observation.taskId);
                assertEquals(input.request.executionEpoch, binding.observation.executionEpoch);
                assertEquals(input.request.profileId, binding.observation.profileId);
                assertEquals(input.receipt.packageName, binding.observation.packageName);
                assertEquals(input.receipt.windowId, binding.observation.windowId);
                assertEquals(input.receipt.displayId, binding.observation.displayId);
                assertEquals(input.receipt.revision, binding.observation.revision);
                Rect source = input.receipt.copyWindowBounds();
                assertEquals(source.left, binding.observation.windowBounds.left);
                assertEquals(source.top, binding.observation.windowBounds.top);
                assertEquals(source.right, binding.observation.windowBounds.right);
                assertEquals(source.bottom, binding.observation.windowBounds.bottom);
                assertThrows(UnsupportedOperationException.class, () -> binding.applicationIdentity.signerDigests.clear());
                assertEquals(before, ImageWorkset.process().reservedBytes()); assertFalse(input.receipt.isTransferred());
                input.transfer();
                long deadline = SystemClock.elapsedRealtime() + 5_000;
                input.validate(deadline);
                assertTrue(input.receipt.isFinalCheckAttempted());
                assertEquals(deadline, input.receipt.finalCheckDeadlineElapsedRealtimeMillis());
                assertFalse(input.png.isClosed()); assertEquals(before, ImageWorkset.process().reservedBytes());
                assertEquals(input.receipt.revision, string(env.observe(), "revision"));
                assertEquals(0, env.device.control.getDispatchAttempts()); assertGateFree(env.device);
            }
            env.assertDrained(); screenshots.capture("saved-recipient-real-install-binding-and-readonly-guard-not-review-proof");
        }
    }

    @Test public void foreignOrUntransferredInputsCannotSpendTheOneFinalAttemptAndClosedUntransferredImagesReject() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            try (Input first = env.input("context-first"); Input second = env.input("context-second")) {
                first.prepare(); second.prepare();
                assertReceipt(WindowPngConverter.ReceiptFailure.NOT_TRANSFERRED, () -> first.validate(SystemClock.elapsedRealtime() + 5_000));
                assertFalse(first.receipt.isFinalCheckAttempted());
                assertReceipt(WindowPngConverter.ReceiptFailure.WRONG_REQUEST,
                        () -> first.receipt.prepareReviewContext(second.request, env.tools, env.device.control));
                AndroidDeviceTools foreignAdapter = env.newTools();
                assertReceipt(WindowPngConverter.ReceiptFailure.WRONG_OWNER,
                        () -> first.receipt.prepareReviewContext(first.request, foreignAdapter, env.device.control));
                first.transfer();
                assertReceipt(WindowPngConverter.ReceiptFailure.ALREADY_TRANSFERRED,
                        () -> first.receipt.prepareReviewContext(first.request, env.tools, env.device.control));
                assertReceipt(WindowPngConverter.ReceiptFailure.WRONG_REQUEST,
                        () -> first.receipt.validateAfterReview(second.request, env.tools, env.device.control,
                                first.context, SystemClock.elapsedRealtime() + 5_000));
                assertReceipt(WindowPngConverter.ReceiptFailure.WRONG_OWNER,
                        () -> first.receipt.validateAfterReview(first.request, foreignAdapter, env.device.control,
                                first.context, SystemClock.elapsedRealtime() + 5_000));
                RunControl foreign = new RunControl(env.device.control.getProfileId());
                try {
                    assertReceipt(WindowPngConverter.ReceiptFailure.WRONG_OWNER,
                            () -> first.receipt.validateAfterReview(first.request, env.tools, foreign,
                                    first.context, SystemClock.elapsedRealtime() + 5_000));
                    assertFalse(foreign.isCancelled()); assertFalse(env.device.control.isCancelled());
                } finally { foreign.cancel(); }
                assertReceipt(WindowPngConverter.ReceiptFailure.WRONG_REVIEW_CONTEXT,
                        () -> first.receipt.validateAfterReview(first.request, env.tools, env.device.control,
                                second.context, SystemClock.elapsedRealtime() + 5_000));
                assertFalse(first.receipt.isFinalCheckAttempted());
                // Closing an empty transferred container cannot revoke another
                // owner's PNG. Its historical context still supports one read.
                first.receipt.close(); assertFalse(first.png.isClosed());
                first.validate(SystemClock.elapsedRealtime() + 5_000);
                assertReceipt(WindowPngConverter.ReceiptFailure.FINAL_CHECK_ALREADY_ATTEMPTED,
                        () -> first.validate(SystemClock.elapsedRealtime() + 5_000));
                second.receipt.close();
                assertReceipt(WindowPngConverter.ReceiptFailure.CLOSED,
                        () -> second.receipt.prepareReviewContext(second.request, env.tools, env.device.control));
                assertReceipt(WindowPngConverter.ReceiptFailure.CLOSED,
                        () -> second.validate(SystemClock.elapsedRealtime() + 5_000));
                assertFalse(second.receipt.isFinalCheckAttempted());
                assertEquals(0, env.device.control.getDispatchAttempts()); assertGateFree(env.device);
            }
            env.assertDrained(); screenshots.capture("wrong-context-and-untransferred-receipts-cannot-spend-legitimate-final-attempt");
        }
    }

    @Test public void historicalCaptureExpiryDoesNotRenewItsPermitButANewFiveSecondReadUsesCurrentCompactControls() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            try (Input input = env.input("historical-new-final-window")) {
                input.prepare(); input.transfer();
                long oldDeadline = input.receipt.originalCaptureDeadlineElapsedRealtimeMillis;
                AgentAccessibilityService.OwnedControlWindowIdentity oldControls = env.device.service.getOwnedControlWindowIdentity();
                assertNotNull(oldControls);
                env.device.instrumentation.runOnMainSync(() -> env.device.service.showOverlay("Review complete", false, ""));
                AgentAccessibilityService.ControlLease current = controls(env.device);
                assertFalse(env.device.service.isOwnedControlWindowCurrent(oldControls));
                assertTrue(env.device.service.isControlLeaseCurrent(current));
                assertEquals(input.receipt.revision, string(env.observe(), "revision"));
                long wait = oldDeadline - SystemClock.elapsedRealtime() + 1;
                assertTrue("Capture deadline unexpectedly moved", wait <= 5_001);
                if (wait > 0) SystemClock.sleep(wait);
                assertSame(input.context.binding, input.context.currentBinding());
                long finalDeadline = SystemClock.elapsedRealtime() + 5_000;
                int captureCalls = env.backendCalls.get();
                input.validate(finalDeadline);
                assertEquals(oldDeadline, input.receipt.originalCaptureDeadlineElapsedRealtimeMillis);
                assertEquals(finalDeadline, input.receipt.finalCheckDeadlineElapsedRealtimeMillis());
                assertEquals(captureCalls, env.backendCalls.get()); assertFalse(input.png.isClosed());
                assertEquals(0, env.device.control.getDispatchAttempts()); assertGateFree(env.device);
            }
            env.assertDrained(); screenshots.capture("expired-capture-remains-historical-new-bounded-read-reacquires-current-controls");
        }
    }

    @Test public void busyInvalidAndExpiredFinalAttemptsCannotRetryWithANewerDeadline() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            for (int mode = 0; mode < 3; mode++) {
                try (Input input = env.input("spent-final-attempt-" + mode)) {
                    input.prepare(); input.transfer();
                    long deadline = mode == 0 ? SystemClock.elapsedRealtime() + 5_000
                            : mode == 1 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - 1;
                    if (mode == 0) {
                        assertTrue(env.device.service.tryBeginDeviceCall());
                        try { assertWindow(AndroidDeviceTools.WindowCaptureFailure.DEVICE_BUSY, () -> input.validate(deadline)); assertGateBusy(env.device); }
                        finally { env.device.service.endDeviceCall(); }
                    } else assertWindow(mode == 1 ? AndroidDeviceTools.WindowCaptureFailure.INVALID_ARGUMENT
                            : AndroidDeviceTools.WindowCaptureFailure.EXPIRED, () -> input.validate(deadline));
                    assertTrue(input.receipt.isFinalCheckAttempted());
                    assertEquals(deadline, input.receipt.finalCheckDeadlineElapsedRealtimeMillis());
                    assertReceipt(WindowPngConverter.ReceiptFailure.FINAL_CHECK_ALREADY_ATTEMPTED,
                            () -> input.validate(SystemClock.elapsedRealtime() + 5_000));
                    assertEquals(deadline, input.receipt.finalCheckDeadlineElapsedRealtimeMillis());
                    assertFalse(input.png.isClosed()); assertGateFree(env.device);
                }
                env.assertDrained();
            }
            screenshots.capture("busy-invalid-expired-read-attempt-is-single-use-no-deadline-refresh");
        }
    }

    @Test public void actualTargetTextChangeAndActualWindowChangeRejectOriginalHistoricalRevision() throws Exception {
        try (Environment env = new Environment("SlowActivity")) {
            if (unsupported(env)) return;
            try (Input changedText = env.input("before-real-counter-change")) {
                changedText.prepare(); changedText.transfer();
                JsonObject original = env.device.observe();
                JsonObject clicked = env.device.tools.execute("click", nodeArguments(original, "increment"), env.device.control);
                assertTrue(clicked.toString(), clicked.get("ok").getAsBoolean());
                JsonObject after = env.device.withText("Count: 1");
                assertNotEquals(changedText.receipt.revision, string(after, "revision"));
                assertSame("Lifecycle binding is deliberately not a target-content approval", changedText.context.binding, changedText.context.currentBinding());
                long dispatched = env.device.control.getDispatchAttempts(); assertEquals(1, dispatched);
                assertWindow(AndroidDeviceTools.WindowCaptureFailure.STALE_OBSERVATION,
                        () -> changedText.validate(SystemClock.elapsedRealtime() + 5_000));
                assertEquals(dispatched, env.device.control.getDispatchAttempts()); assertFalse(changedText.png.isClosed());
            }
            try (Input changedWindow = env.input("before-real-window-change")) {
                changedWindow.prepare(); changedWindow.transfer();
                env.device.launch("SecondActivity"); JsonObject after = env.observe();
                assertNotEquals(changedWindow.receipt.windowId, after.get("windowId").getAsInt());
                assertSame(changedWindow.context.binding, changedWindow.context.currentBinding());
                long dispatched = env.device.control.getDispatchAttempts();
                assertWindow(AndroidDeviceTools.WindowCaptureFailure.STALE_OBSERVATION,
                        () -> changedWindow.validate(SystemClock.elapsedRealtime() + 5_000));
                assertEquals(dispatched, env.device.control.getDispatchAttempts()); assertGateFree(env.device);
            }
            env.assertDrained(); screenshots.capture("actual-counter-and-focus-window-change-reject-original-revision-without-new-writes");
        }
    }

    @Test public void anActuallyRegisteredUnknownOverlayIsStillAnOrdinaryFinalSnapshotObstruction() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            try (Input input = env.input("before-unknown-overlay")) {
                input.prepare(); input.transfer();
                try (UnknownOverlay unknown = new UnknownOverlay(env.device)) {
                    env.device.awaitUiIdle();
                    JsonObject blocked = awaitObservation(env.tools, env.device.control,
                            data -> FIXTURE.equals(string(data, "packageName")) && data.get("hasOcclusion").getAsBoolean());
                    unknown.assertActuallyOverlaps(input.receipt.copyWindowBounds());
                    assertNotEquals(input.receipt.revision, string(blocked, "revision"));
                    // It is expected to reject the original revision first. The
                    // independent actual-window/hasOcclusion checks above prove
                    // this negative really contains the unknown obstruction.
                    assertWindow(AndroidDeviceTools.WindowCaptureFailure.STALE_OBSERVATION,
                            () -> input.validate(SystemClock.elapsedRealtime() + 5_000));
                    assertEquals(0, env.device.control.getDispatchAttempts()); assertFalse(input.png.isClosed());
                    assertGateFree(env.device);
                }
            }
            env.assertDrained(); screenshots.capture("real-unknown-secure-overlay-remains-obstruction-no-review-window-whitelist");
        }
    }

    @Test public void publishedLeaseInvalidationAndPauseResumeInvalidateTheCachedLifecycleBindingAndOriginalFinalRead() throws Exception {
        for (boolean replaceConfiguration : new boolean[]{true, false}) {
            try (Environment env = new Environment("CapturePatternActivity")) {
                if (unsupported(env)) return;
                try (Input input = env.input(replaceConfiguration ? "real-lease-change" : "original-epoch-change")) {
                    input.prepare(); input.transfer();
                    if (replaceConfiguration) {
                        SettingsStore store = new SettingsStore(env.device.context); SettingsStore.SavedSettings saved = store.load();
                        try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, env.settings.lease)) {
                            held.start(saved); held.awaitPublished(); long started = SystemClock.elapsedRealtime();
                            assertNull(input.context.currentBinding());
                            assertWindow(AndroidDeviceTools.WindowCaptureFailure.CONFIGURATION_CHANGED,
                                    () -> input.validate(SystemClock.elapsedRealtime() + 5_000));
                            assertTrue("Pure context guard waited for storage/listeners", SystemClock.elapsedRealtime() - started < 1_000);
                            held.assertHeld(); assertFalse(env.device.control.isCancelled()); held.finish();
                        }
                    } else {
                        env.device.control.pause(); env.device.control.resume();
                        assertNull(input.context.currentBinding());
                        try { input.validate(SystemClock.elapsedRealtime() + 5_000); fail("Resuming cannot rebind original final read"); }
                        catch (TaskControlException staleEpoch) { assertEquals("TASK_PAUSED", staleEpoch.getCode()); }
                        assertFalse(env.device.control.isCancelled());
                    }
                    assertTrue(input.receipt.isFinalCheckAttempted()); assertFalse(input.png.isClosed());
                    assertReceipt(WindowPngConverter.ReceiptFailure.FINAL_CHECK_ALREADY_ATTEMPTED,
                            () -> input.validate(SystemClock.elapsedRealtime() + 5_000));
                    assertGateFree(env.device); assertEquals(0, env.device.control.getDispatchAttempts());
                }
                env.assertDrained();
            }
        }
        screenshots.capture("real-lease-and-original-epoch-current-binding-and-final-read-reject");
    }

    @Test public void finalDeadlineCannotReleaseADeviceGateWhoseRealRemoteRootReadHasNotReturned() throws Exception {
        try (Environment env = new Environment("SlowActivity")) {
            if (unsupported(env)) return;
            try (Input input = env.input("slow-final-root");
                 PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                input.prepare(); input.transfer(); controls(env.device);
                String token = UUID.randomUUID().toString(); ExecutorService worker = Executors.newSingleThreadExecutor();
                long deadline = SystemClock.elapsedRealtime() + 1_500;
                Future<Throwable> finalRead = null;
                try {
                    assertFalse(barrier(env.device, "arm", token).getBoolean("claimed"));
                    finalRead = worker.submit(() -> {
                        try { input.validate(deadline); return new AssertionError("Expired actual root read was accepted"); }
                        catch (Throwable failure) { return failure; }
                    });
                    long claimDeadline = SystemClock.elapsedRealtime() + 1_000; Bundle held;
                    do { held = barrier(env.device, "status", token); if (held.getBoolean("claimed")) break; SystemClock.sleep(20); }
                    while (SystemClock.elapsedRealtime() < claimDeadline);
                    assertTrue("The actual fixture root did not enter the test barrier", held.getBoolean("claimed"));
                    assertFalse(held.getBoolean("returned")); assertFalse(held.getBoolean("expired"));
                    long remaining = deadline - SystemClock.elapsedRealtime() + 1;
                    if (remaining > 0) SystemClock.sleep(remaining);
                    assertFalse(finalRead.isDone()); assertGateBusy(env.device);
                    assertFalse(input.png.isClosed()); assertEquals(input.receipt.encodedBytes, ImageWorkset.process().reservedBytes());
                    Bundle released = barrier(env.device, "finish", token);
                    assertTrue(released.getBoolean("returned")); assertFalse(released.getBoolean("expired"));
                    Throwable failure = finalRead.get(3, TimeUnit.SECONDS);
                    assertTrue(String.valueOf(failure), failure instanceof AndroidDeviceTools.WindowCaptureException);
                    assertEquals(AndroidDeviceTools.WindowCaptureFailure.EXPIRED, ((AndroidDeviceTools.WindowCaptureException) failure).code);
                    assertEquals(0, trace.overwrittenCount());
                    int finalSnapshots = 0;
                    for (PerformanceTrace.Event event : trace.events()) {
                        assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
                        if (event.stage != PerformanceTrace.Stage.SNAPSHOT_OBSERVE) continue;
                        finalSnapshots++; assertFalse(event.mainThread);
                        assertEquals(input.request.executionEpoch, event.epoch);
                        assertTrue(event.startNanos <= released.getLong("startedNanos"));
                        assertTrue(event.startNanos + event.durationNanos >= released.getLong("finishedNanos"));
                        // Incomplete snapshot sentinels, not a fabricated count of
                        // native child reads or proof of a more precise sub-stage.
                        assertEquals(-1, event.nodeCount); assertEquals(-1, event.snapshotJsonBytes);
                    }
                    assertEquals(1, finalSnapshots);
                    assertTrue(input.receipt.isFinalCheckAttempted()); assertEquals(deadline, input.receipt.finalCheckDeadlineElapsedRealtimeMillis());
                    assertReceipt(WindowPngConverter.ReceiptFailure.FINAL_CHECK_ALREADY_ATTEMPTED,
                            () -> input.validate(SystemClock.elapsedRealtime() + 5_000));
                    assertGateFree(env.device); assertEquals(0, env.device.control.getDispatchAttempts());
                } finally {
                    try { barrier(env.device, "finish", token); }
                    finally { barrier(env.device, "clear", token); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
                }
            }
            env.assertDrained(); screenshots.capture("real-remote-final-root-read-keeps-gate-until-return-after-fixed-deadline");
        }
    }

    private boolean unsupported(Environment env) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) return false;
        ToolRequest request = env.request("low-api-context");
        try { env.start(request, (id, callback) -> { env.backendCalls.incrementAndGet(); callback.failure(95); }); fail("Low API source was accepted"); }
        catch (AndroidDeviceTools.WindowCaptureException failure) { assertEquals(AndroidDeviceTools.WindowCaptureFailure.UNSUPPORTED_API, failure.code); }
        assertEquals(0, env.backendCalls.get()); env.assertDrained();
        screenshots.capture("review-context-unavailable-without-api34-capture-source"); return true;
    }

    private static final class Input implements AutoCloseable {
        final Environment env; final ToolRequest request; final WindowPngConverter.CapturedPng receipt;
        AndroidDeviceTools.CapturedReviewContext context; PngImage png;
        Input(Environment env, ToolRequest request, WindowPngConverter.CapturedPng receipt) { this.env = env; this.request = request; this.receipt = receipt; }
        AndroidDeviceTools.CapturedReviewContext prepare() throws Exception {
            context = receipt.prepareReviewContext(request, env.tools, env.device.control); return context;
        }
        void transfer() throws Exception { assertNull(png); png = receipt.claimForTesting(request, env.tools, env.device.control); }
        void validate(long deadline) throws Exception { receipt.validateAfterReview(request, env.tools, env.device.control, context, deadline); }
        @Override public void close() { try { if (png != null) png.close(); } finally { receipt.close(); } }
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
        final List<Input> inputs = new ArrayList<>();
        Environment(String fixtureActivity) throws Exception {
            try {
                await(() -> !WindowBufferCapturer.hasActiveCapture() && ImageWorkset.process().reservedBytes() == 0, "Previous image owners have not drained");
                device = new DeviceHarness(); server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(device.context, server); tools = newTools();
                device.launch(fixtureActivity); observe(); bounds = focusedBounds(device);
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
        Input input(String callId) throws Exception {
            Source source = source(); ToolRequest original = request(callId);
            WindowPngConverter.CapturedPng receipt = new WindowPngConverter().convertCaptured(capture(source, original));
            try { Input input = new Input(this, original, receipt); inputs.add(input); return input; }
            catch (RuntimeException | Error failure) { receipt.close(); throw failure; }
        }
        void awaitSlotDrained() throws Exception {
            await(() -> requests.stream().allMatch(WindowBufferCapturer.CaptureRequest::isWorkerDone)
                    && !WindowBufferCapturer.hasActiveCapture(), "Actual request/slot ownership has not drained");
        }
        void assertDrained() throws Exception { awaitSlotDrained(); assertEquals(0, ImageWorkset.process().reservedBytes()); assertNoCapability(); }
        void assertNoCapability() {
            assertEquals(0, server.requestCount());
            assertEquals(0, device.service.getServiceInfo().getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
            assertFalse(tools.availableTools().contains("take_screenshot"));
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try {
                if (device != null) device.control.cancel();
                for (Input input : inputs) input.close();
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
    private static final class UnknownOverlay implements AutoCloseable {
        final DeviceHarness device; final WindowManager manager; TextView view;
        UnknownOverlay(DeviceHarness device) {
            this.device = device; manager = device.service.getSystemService(WindowManager.class);
            try {
                device.instrumentation.runOnMainSync(() -> {
                    view = new TextView(device.service);
                    view.setText("Unregistered synthetic obstruction"); view.setClickable(true); view.setTextColor(Color.WHITE);
                    view.setBackgroundColor(Color.DKGRAY); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(120, 80,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.CENTER; params.setTitle("Agent OS task controls"); manager.addView(view, params);
                });
            } catch (RuntimeException | Error failure) { try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); } throw failure; }
        }
        void assertActuallyOverlaps(Rect target) {
            int[] id = {-1}; Rect actualView = new Rect();
            device.instrumentation.runOnMainSync(() -> {
                assertTrue(view.isAttachedToWindow()); assertTrue(view.isShown());
                AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
                try { id[0] = node.getWindowId(); } finally { node.recycle(); }
                int[] location = new int[2]; view.getLocationOnScreen(location);
                actualView.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
                assertTrue((((WindowManager.LayoutParams) view.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
            assertTrue(id[0] >= 0); assertTrue(Rect.intersects(target, actualView));
            assertNotEquals(device.service.getOwnedControlWindowIdentity().windowId, id[0]);
            // env.observe immediately before this used a fresh ordinary snapshot.
            List<AccessibilityWindowInfo> windows = device.service.getWindows(); int found = 0;
            try {
                for (AccessibilityWindowInfo window : windows) if (window.getId() == id[0]) {
                    found++; assertEquals(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, window.getType());
                    assertFalse(window.isFocused()); Rect listed = new Rect(); window.getBoundsInScreen(listed);
                    assertEquals(actualView, listed); assertTrue(Rect.intersects(target, listed));
                }
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
            assertEquals(1, found);
        }
        @Override public void close() { device.instrumentation.runOnMainSync(() -> { if (view != null && view.isAttachedToWindow()) manager.removeViewImmediate(view); }); }
    }
    private static AgentAccessibilityService.ControlLease controls(DeviceHarness device) throws Exception {
        CompletableFuture<AgentAccessibilityService.ControlLease> future = device.service.acquireControlLease();
        try { return future.get(5, TimeUnit.SECONDS); } finally { future.cancel(false); }
    }
    private static Bundle barrier(DeviceHarness device, String method, String token) {
        Bundle result = device.context.getContentResolver().call(Uri.parse("content://dev.agentos.fixture.slow-read-control"), method, token, null);
        assertNotNull(result); return result;
    }
    private static void assertGateFree(DeviceHarness device) {
        boolean acquired = device.service.tryBeginDeviceCall();
        try { assertTrue("Read-only guard leaked the actual device gate", acquired); }
        finally { if (acquired) device.service.endDeviceCall(); }
    }
    private static void assertGateBusy(DeviceHarness device) {
        boolean acquired = device.service.tryBeginDeviceCall(); if (acquired) device.service.endDeviceCall();
        assertFalse("Actual in-flight platform read must still own the gate", acquired);
    }
    private static Rect focusedBounds(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            Rect found = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) { assertNull(found); found = new Rect(); window.getBoundsInScreen(found); }
            assertNotNull(found); return found;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }
    private static void assertWindow(AndroidDeviceTools.WindowCaptureFailure code, CheckedAction action) throws Exception {
        try { action.run(); fail("Expected final window rejection " + code); }
        catch (AndroidDeviceTools.WindowCaptureException failure) { assertEquals(failure.toString(), code, failure.code); }
    }
    private static void assertReceipt(WindowPngConverter.ReceiptFailure code, CheckedAction action) throws Exception {
        try { action.run(); fail("Expected receipt rejection " + code); }
        catch (WindowPngConverter.ReceiptException failure) { assertEquals(failure.toString(), code, failure.code); }
    }
    private interface CheckedAction { void run() throws Exception; }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; if (first != next) first.addSuppressed(next); return first; }
}
