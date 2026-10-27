package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.content.Context;
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
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.DisclosureGuard;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;
import dev.agentos.core.RunControl;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Actual service windows/events and synthetic image owners; no production native capture or HTTP disclosure. */
@RunWith(AndroidJUnit4.class)
public final class VisualEventGenerationInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            env.main(() -> env.device.service.showOverlay("Phase 100", false, ""));
            controls(env.device); env.device.awaitUiIdle();
            try (Input input = env.input("status-publication-guard")) {
                input.prepare(); input.transfer();
                DisclosureGuard guard = input.validate(SystemClock.elapsedRealtime() + 5_000);
                long generation = env.device.service.visualEventGeneration();
                AgentAccessibilityService.OwnedControlWindowIdentity original = env.device.service.getOwnedControlWindowIdentity();
                assertNotNull(original); assertTrue(guard.isCurrent(env.device.control, input.request.executionEpoch));
                for (String status : new String[] {"Phase 101", "Phase 102", "Phase 103"}) {
                    AccessibilityEvent delivered = env.device.automation.executeAndWaitForEvent(
                            () -> env.main(() -> env.device.service.showOverlay(status, false, "")),
                            event -> event.getWindowId() == original.windowId
                                    && (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                                    || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                                    && (event.getContentChangeTypes() & AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) != 0),
                            5_000);
                    try { assertEquals(original.windowId, delivered.getWindowId()); }
                    finally { delivered.recycle(); }
                    AgentAccessibilityService.ControlLease current = controls(env.device);
                    env.device.awaitUiIdle(); env.instrumentation.waitForIdleSync();
                    assertEquals("The positive is specifically a same-frame status update", original.copyBounds(), current.copyBounds());
                    assertEquals("A real owned cosmetic event must not revoke this generation", generation,
                            env.device.service.visualEventGeneration());
                    assertTrue("The original local image guard must survive the actual status publication",
                            guard.isCurrent(env.device.control, input.request.executionEpoch));
                }
                assertEquals(0, env.device.control.getDispatchAttempts());
            }
            env.assertDrained(); screenshots.capture("real-owned-status-events-preserve-local-image-guard-no-native-capture-or-http");
        }
    }

    @Test public void actualControlGrowthAndRootVisibilityAndUnknownWindowsRevokeWithoutAnyDeviceAction() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            env.main(() -> env.device.service.showOverlay("Short", false, ""));
            Rect old = controls(env.device).copyBounds(); env.device.awaitUiIdle();
            long before = env.device.service.visualEventGeneration();
            env.main(() -> env.device.service.showOverlay("First status line\nSecond status line", false, ""));
            Rect grown = controls(env.device).copyBounds(); env.device.awaitUiIdle();
            assertTrue("Actual text layout must grow the control window", grown.height() > old.height());
            assertFalse(env.device.service.isVisualEventGenerationCurrent(before));
            View actualRoot = (View) read(env.device.service, "overlay");
            long visible = env.device.service.visualEventGeneration();
            env.main(() -> actualRoot.setVisibility(View.GONE)); env.instrumentation.waitForIdleSync();
            env.main(() -> assertFalse(actualRoot.isShown()));
            assertFalse("Root visibility is a direct revocation, not dependent on a future accessibility event",
                    env.device.service.isVisualEventGenerationCurrent(visible));
            env.main(() -> actualRoot.setVisibility(View.VISIBLE)); controls(env.device); env.device.awaitUiIdle();
            long noObstruction = env.device.service.visualEventGeneration();
            try (UnknownOverlay unknown = new UnknownOverlay(env.device)) {
                env.device.awaitUiIdle(); env.observe(); unknown.assertActuallyOverlaps(env.bounds);
                assertFalse("A real unregistered interactive window must revoke the previous generation",
                        env.device.service.isVisualEventGenerationCurrent(noObstruction));
            }
            env.device.awaitUiIdle();
            assertEquals(0, env.device.control.getDispatchAttempts()); env.assertNoCapability();
            screenshots.capture("actual-control-growth-root-hidden-and-unregistered-window-revoke-generation");
        }
    }

    @Test public void onlyTheActualStableReviewAttachmentCanExemptCosmeticMetadataAndRetiredIdsCannot() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            try (Input input = env.input("current-review-event")) {
                input.prepare(); input.transfer();
                ImageReviewSession session = new ImageReviewSession(input.context.binding, input.png, env.device.control,
                        input.context::currentBinding);
                input.png = null; // The real session now owns the synthetic immutable image.
                AtomicReference<SecureImageReviewOverlay> shown = new AtomicReference<>();
                AtomicReference<Throwable> callbackError = new AtomicReference<>();
                AccessibilityEvent retired = null;
                try {
                    long unattached = env.device.service.visualEventGeneration();
                    env.main(() -> shown.set(env.device.service.showImageReview(session, env.device.control,
                            input.request.executionEpoch, env.settings.lease, new SecureImageReviewOverlay.Listener() {
                                @Override public void onApproved(ImageReviewSession.ApprovalToken token) {
                                    callbackError.set(new AssertionError("This event test never approves disclosure"));
                                }
                                @Override public void onClosed(ImageReviewSession.State state, ImageReviewSession.Reason reason) { }
                            })));
                    SecureImageReviewOverlay host = shown.get(); assertNotNull(host);
                    await(() -> {
                        AtomicBoolean ready = new AtomicBoolean();
                        env.main(() -> {
                            assertFalse(host.diagnosticSummary(), host.isEnded());
                            ready.set(session.state() == ImageReviewSession.State.PRESENTED && host.rootView().isAttachedToWindow()
                                    && !host.rootView().hasWindowFocus()
                                    && ((android.widget.Button) host.rootView().findViewWithTag(ImageReviewView.APPROVE)).isEnabled());
                        });
                        return ready.get();
                    }, "Real secure review did not become presented");
                    CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> future = host.acquireIdentity();
                    AgentAccessibilityService.OwnedReviewWindowIdentity identity;
                    try { identity = future.get(5, TimeUnit.SECONDS); } finally { future.cancel(false); }
                    env.device.awaitUiIdle();
                    assertFalse(env.device.service.isVisualEventGenerationCurrent(unattached));
                    AtomicReference<AccessibilityEvent> prepared = new AtomicReference<>();
                    env.main(() -> {
                        // Explicit metadata injection. The source ID is obtained from the
                        // actual registered root; no fabricated window or consent is used.
                        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                        event.setSource(host.rootView()); event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT);
                        assertEquals(identity.windowId, event.getWindowId());
                        long before = env.device.service.visualEventGeneration();
                        env.device.service.onAccessibilityEvent(event);
                        assertEquals(before, env.device.service.visualEventGeneration());
                        prepared.set(event);
                    });
                    retired = prepared.get(); assertNotNull(retired);
                    AccessibilityEvent mixed = AccessibilityEvent.obtain(retired);
                    try {
                        mixed.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT | AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
                        long before = env.device.service.visualEventGeneration();
                        env.main(() -> env.device.service.onAccessibilityEvent(mixed));
                        assertFalse("Mixed/unknown content bits are not cosmetic exemptions",
                                env.device.service.isVisualEventGenerationCurrent(before));
                    } finally { mixed.recycle(); }
                    CompletableFuture<Void> removed = host.whenRemoved(); env.main(host::close); removed.get(5, TimeUnit.SECONDS);
                    env.device.awaitUiIdle(); controls(env.device);
                    assertFalse(env.device.service.isOwnedReviewWindowCurrent(identity));
                    long afterRemoval = env.device.service.visualEventGeneration();
                    AccessibilityEvent late = retired;
                    env.main(() -> env.device.service.onAccessibilityEvent(late));
                    assertFalse("An event carrying the genuine retired ID must have no remaining exemption",
                            env.device.service.isVisualEventGenerationCurrent(afterRemoval));
                    assertNull(callbackError.get()); assertEquals(0, env.device.control.getDispatchAttempts());
                } finally {
                    if (retired != null) retired.recycle();
                    SecureImageReviewOverlay host = shown.get();
                    try {
                        if (host != null) { CompletableFuture<Void> removed = host.whenRemoved(); env.main(host::close); removed.get(5, TimeUnit.SECONDS); }
                    } finally { session.close(); }
                }
            }
            env.assertDrained(); screenshots.capture("real-secure-review-current-vs-retired-identity-injected-metadata-no-approval");
        }
    }

    @Test public void returnedGuardIsBoundToTheOriginalOwnerEpochLeaseAndActualTargetEvents() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (Environment env = new Environment("SlowActivity")) {
                if (unsupported(env)) return;
                try (Input input = env.input("guard-owner-" + mode)) {
                    input.prepare(); input.transfer(); DisclosureGuard guard = input.validate(SystemClock.elapsedRealtime() + 5_000);
                    RunControl foreign = new RunControl(env.device.control.getProfileId());
                    try {
                        assertTrue(guard.isCurrent(env.device.control, input.request.executionEpoch));
                        assertFalse(guard.isCurrent(foreign, input.request.executionEpoch));
                        assertFalse(guard.isCurrent(env.device.control, input.request.executionEpoch + 1));
                        assertTrue(guard.isCurrent(env.device.control, input.request.executionEpoch));
                        if (mode == 0) { env.device.control.pause(); env.device.control.resume(); }
                        else if (mode == 1) { SettingsStore store = new SettingsStore(env.context); store.save(store.load()); }
                        else {
                            long before = env.device.service.visualEventGeneration();
                            AccessibilityNodeInfo root = env.device.automation.getRootInActiveWindow(); assertNotNull(root);
                            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/increment");
                            try { assertEquals(1, nodes.size()); assertTrue(nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
                            finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); root.recycle(); }
                            env.device.withText("Count: 1"); env.device.awaitUiIdle();
                            assertFalse(env.device.service.isVisualEventGenerationCurrent(before));
                        }
                        assertFalse(guard.isCurrent(env.device.control, input.request.executionEpoch));
                        assertFalse("Memory-only guard must not cancel the original task", env.device.control.isCancelled());
                        assertFalse(foreign.isCancelled()); assertEquals(0, env.device.control.getDispatchAttempts());
                    } finally { foreign.cancel(); }
                }
                env.assertDrained();
            }
        }
        screenshots.capture("local-returned-guard-original-owner-epoch-real-lease-and-real-target-events");
    }

    @Test public void deliveredUnknownMetadataDuringOneActualFinalRootReadRejectsWithoutRetryOrEarlyGateRelease() throws Exception {
        try (Environment env = new Environment("SlowActivity")) {
            if (unsupported(env)) return;
            try (Input input = env.input("event-during-final-root")) {
                input.prepare(); input.transfer(); controls(env.device);
                String token = UUID.randomUUID().toString(); ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    assertFalse(barrier(env.device, "arm", token).getBoolean("claimed")); env.device.awaitUiIdle();
                    try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                        long deadline = SystemClock.elapsedRealtime() + 5_000;
                        Future<Throwable> work = worker.submit(() -> {
                            try { input.validate(deadline); return new AssertionError("Changed generation was accepted"); }
                            catch (Throwable failure) { return failure; }
                        });
                        long claimDeadline = SystemClock.elapsedRealtime() + 1_000;
                        Bundle held;
                        do { held = barrier(env.device, "status", token); if (held.getBoolean("claimed")) break; SystemClock.sleep(20); }
                        while (SystemClock.elapsedRealtime() < claimDeadline);
                        assertTrue("Actual fixture root did not enter barrier", held.getBoolean("claimed"));
                        assertFalse(held.getBoolean("returned")); assertFalse(held.getBoolean("expired"));
                        assertGateBusy(env.device);
                        AccessibilityEvent unknown = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                        try {
                            assertTrue(unknown.getWindowId() < 0);
                            env.main(() -> env.device.service.onAccessibilityEvent(unknown));
                        } finally { unknown.recycle(); }
                        assertFalse("Native work owns its gate until its actual return", work.isDone()); assertGateBusy(env.device);
                        Bundle released = barrier(env.device, "finish", token); assertTrue(released.getBoolean("returned")); assertFalse(released.getBoolean("expired"));
                        Throwable rejected = work.get(3, TimeUnit.SECONDS);
                        assertTrue(String.valueOf(rejected), rejected instanceof AndroidDeviceTools.WindowCaptureException);
                        assertEquals(AndroidDeviceTools.WindowCaptureFailure.STALE_OBSERVATION,
                                ((AndroidDeviceTools.WindowCaptureException) rejected).code);
                        int attempts = 0;
                        for (PerformanceTrace.Event event : trace.events()) {
                            assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
                            if (event.stage == PerformanceTrace.Stage.SNAPSHOT_OBSERVE) {
                                attempts++; assertFalse(event.mainThread); assertEquals(-1, event.nodeCount); assertEquals(-1, event.snapshotJsonBytes);
                            }
                        }
                        assertEquals(0, trace.overwrittenCount()); assertEquals(1, attempts);
                        assertTrue(input.receipt.isFinalCheckAttempted()); assertEquals(deadline, input.receipt.finalCheckDeadlineElapsedRealtimeMillis());
                        assertReceipt(WindowPngConverter.ReceiptFailure.FINAL_CHECK_ALREADY_ATTEMPTED,
                                () -> input.validate(SystemClock.elapsedRealtime() + 5_000));
                        assertGateFree(env.device); assertEquals(0, env.device.control.getDispatchAttempts());
                    }
                } finally {
                    try { barrier(env.device, "finish", token); }
                    finally { barrier(env.device, "clear", token); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
                }
            }
            env.assertDrained(); screenshots.capture("injected-unknown-event-during-real-final-root-read-single-rejection-no-native-dispatch");
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
        DisclosureGuard validate(long deadline) throws Exception { return receipt.validateAfterReview(request, env.tools, env.device.control, context, deadline); }
        @Override public void close() { try { if (png != null) png.close(); } finally { receipt.close(); } }
    }
    private static final class Source {
        final HardwareBuffer buffer;
        final ColorSpace colorSpace;
        volatile boolean transferred;
        Source(HardwareBuffer buffer, ColorSpace colorSpace) { this.buffer = buffer; this.colorSpace = colorSpace; }
    }
    private static final class Environment implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final AgentController controller = AgentController.get(context);
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
                assertFalse("Do not replace an active task", controller.getState().running);
                TaskUsageTestHarness.drainWorker((ThreadPoolExecutor) read(controller, "worker"));
                main(controller::clearCompletedTask); instrumentation.waitForIdleSync();
                server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(context, server);
                // Saved-configuration notifications and any old idle publication
                // settle before DeviceHarness installs its actual task callbacks.
                TaskUsageTestHarness.drainWorker((ThreadPoolExecutor) read(controller, "worker"));
                instrumentation.waitForIdleSync();
                device = new DeviceHarness(); tools = newTools();
                device.launch(fixtureActivity); observe(); bounds = focusedBounds(device);
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        void main(Runnable operation) { onMain(instrumentation, operation); }
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
            assertFalse(device.service.hasWindowCaptureCapability());
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
                onMain(device.instrumentation, () -> {
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
            onMain(device.instrumentation, () -> {
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
        @Override public void close() { onMain(device.instrumentation, () -> { if (view != null && view.isAttachedToWindow()) manager.removeViewImmediate(view); }); }
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
    private static Object read(Object owner, String field) throws Exception {
        Field declared = owner.getClass().getDeclaredField(field); declared.setAccessible(true); return declared.get(owner);
    }
    private static void onMain(Instrumentation instrumentation, Runnable operation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { operation.run(); }
            catch (RuntimeException | Error problem) { failure.set(problem); }
        });
        Throwable problem = failure.get();
        if (problem instanceof Error) throw (Error) problem;
        if (problem instanceof RuntimeException) throw (RuntimeException) problem;
    }
}
