package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ImageAttachment;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.LocalToolResult;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import dev.agentos.core.ToolCatalog;
import dev.agentos.core.ToolDispatchResult;
import dev.agentos.core.ToolDispatcher;
import dev.agentos.core.ToolHandler;
import dev.agentos.core.ToolRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/**
 * Synthetic window buffers, actual secure review windows and real saved configuration.
 * Covers the internal review/removal/final-guard handoff, not platform capture,
 * Controller routing, supported-page policy or a model/network disclosure.
 */
@RunWith(AndroidJUnit4.class)
public final class CapturedImageReviewCoordinatorInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence(Build.VERSION.SDK_INT < 34
                    ? "coordinator_unavailable_without_api34_capture_source"
                    : "secure_captured_image_review_coordinator_capture_blocked");

    @Test public void actualApprovalRemovesTheExactHostBeforeReturningOneTypedImageToTheDispatcher() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            Input input = env.input("coordinator-positive");
            Seen shown = new Seen(env);
            try (Operation operation = env.review(input, shown)) {
                shown.awaitPresented(); shown.assertActualWindow(input);
                assertEquals(input.receipt.sha256, shown.session.imageSha256);
                assertEquals(input.receipt.width, shown.session.width);
                assertEquals(input.receipt.height, shown.session.height);
                assertEquals(input.request.executionEpoch, shown.session.binding.observation.executionEpoch);
                assertEquals(env.settings.lease.configurationToken, shown.session.binding.configurationToken);
                assertEquals(new ImageReviewSession.Recipient(env.settings.model.endpoint, env.settings.model.model), shown.session.binding.recipient);
                env.main(() -> {
                    ImageView preview = shown.preview();
                    assertTrue(preview.getDrawable() instanceof BitmapDrawable);
                    Bitmap bitmap = ((BitmapDrawable) preview.getDrawable()).getBitmap();
                    assertEquals(input.receipt.width, bitmap.getWidth()); assertEquals(input.receipt.height, bitmap.getHeight());
                    assertEquals(Color.CYAN, bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2));
                    assertTrue(shown.content().isReadyToPresent());
                });
                assertGateFree(env.device);
                CompletableFuture<Void> independent = shown.host.whenRemoved();
                CompletableFuture<Void> cancelled = shown.host.whenRemoved();
                assertTrue(cancelled.cancel(false)); assertFalse(independent.isDone());
                shown.click(ImageReviewView.APPROVE);
                try (LocalToolResult result = operation.takeSuccess()) {
                    independent.get(5, TimeUnit.SECONDS); shown.assertRemoved();
                    assertEquals(ImageReviewSession.State.CONSUMED, shown.session.state());
                    assertTrue(input.receipt.isTransferred()); assertTrue(input.receipt.isClosed());
                    assertTrue(input.receipt.isFinalCheckAttempted());
                    JsonObject metadata = result.getResult(), data = metadata.getAsJsonObject("data");
                    assertTrue(metadata.toString(), metadata.get("ok").getAsBoolean());
                    assertEquals(input.receipt.sha256, string(data, "sha256"));
                    assertEquals(input.receipt.captureId, string(data, "captureId"));
                    assertEquals(input.receipt.revision, string(data, "revision"));
                    assertEquals("synthetic_test", string(data, "provenance"));
                    assertFalse(metadata.has("imageDataUrl"));
                    await(() -> !ImagePreviewLoader.isWorkPending(), "Preview loader still owns work after actual removal");
                    assertEquals(input.receipt.encodedBytes, ImageWorkset.process().reservedBytes());
                    AtomicInteger calls = new AtomicInteger();
                    ToolHandler typed = new ToolHandler() {
                        @Override public JsonObject execute(String name, JsonObject arguments, RunControl control) { throw new AssertionError("Original request overload required"); }
                        @Override public LocalToolResult execute(ToolRequest original, RunControl control) {
                            assertSame(input.request, original); assertSame(env.device.control, control);
                            assertEquals(1, calls.incrementAndGet()); return result;
                        }
                        @Override public Set<String> availableTools() { return Collections.singleton("take_screenshot"); }
                    };
                    try (ToolDispatcher dispatcher = new ToolDispatcher(typed);
                         ToolDispatchResult delivered = dispatcher.dispatchWithAttachment(input.request, env.device.control);
                         ImageAttachment image = delivered.takeImage()) {
                        assertNotNull(delivered.getResult().toString(), image);
                        assertEquals(input.receipt.width, image.width); assertEquals(input.receipt.height, image.height);
                        assertEquals(input.receipt.encodedBytes, image.encodedBytes);
                        assertEquals(input.request.callId, image.callId); assertEquals(input.receipt.revision, image.revision);
                        assertEquals(input.request.executionEpoch, image.executionEpoch);
                        assertEquals(input.receipt.sha256, string(delivered.getResult().getAsJsonObject("data"), "sha256"));
                        assertFalse(image.isClosed());
                        try (ToolDispatchResult duplicate = dispatcher.dispatchWithAttachment(input.request, env.device.control);
                             ImageAttachment unexpectedReplay = duplicate.takeImage()) {
                            assertNull(unexpectedReplay);
                            assertEquals("not_replayed", string(duplicate.getResult().getAsJsonObject("imageAttachment"), "delivery"));
                        }
                        assertEquals(1, calls.get());
                    }
                }
            }
            env.assertDrained();
        }
    }

    @Test public void syntheticOriginAndMainThreadRequestsCannotOpenAPlatformReviewAndConsumeTheirReceipt() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            Input synthetic = env.input("coordinator-platform-reject");
            try (LocalToolResult unexpected = CapturedImageReviewCoordinator.reviewPlatform(
                    synthetic.request, env.device.control, env.tools, synthetic.receipt)) {
                fail("A synthetic capture must never become a platform disclosure");
            } catch (WindowPngConverter.ReceiptException rejected) {
                assertEquals(WindowPngConverter.ReceiptFailure.NON_PLATFORM_SOURCE, rejected.code);
            }
            assertTrue(synthetic.receipt.isClosed()); assertFalse(synthetic.receipt.isTransferred());
            env.assertDrained();
            Input main = env.input("coordinator-main-reject"); AtomicReference<Throwable> failure = new AtomicReference<>();
            env.main(() -> {
                try (LocalToolResult unexpected = CapturedImageReviewCoordinator.reviewSyntheticForTesting(
                        main.request, env.device.control, env.tools, main.receipt)) { fail("Main must not wait for local review"); }
                catch (Throwable caught) { failure.set(caught); }
            });
            assertReview(CapturedImageReviewCoordinator.Failure.WRONG_THREAD, failure.get());
            assertTrue(main.receipt.isClosed()); assertFalse(main.receipt.isTransferred());
            assertFalse(ImageReviewPresentationCoordinator.hasHost()); assertNull(env.device.service.getOwnedReviewWindowIdentity());
            env.assertDrained();
        }
    }

    @Test public void cancellationWhileMainIsOccupiedDoesNotPublishALateHostOrReplaceTheOriginalStopHook() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            Input input = env.input("coordinator-queued-cancel"); Seen shown = new Seen(env);
            AtomicInteger stops = new AtomicInteger(); env.device.control.setCancelHook(stops::incrementAndGet);
            MainGate gate = new MainGate(); new Handler(Looper.getMainLooper()).post(gate::hold);
            try {
                gate.awaitEntered();
                try (Operation operation = env.review(input, shown)) {
                    await(() -> input.receipt.isTransferred() || operation.isDone(), "Coordinator never claimed its original image");
                    assertTrue(input.receipt.isTransferred());
                    // The queue may not yet contain show; either timing must leave
                    // no real host after main drains. No fake show acknowledgement.
                    env.device.control.cancel(); assertTask("TASK_STOPPED", operation.awaitFailure());
                    assertEquals(1, stops.get()); assertEquals(0, shown.calls.get());
                    assertTrue(input.receipt.isClosed()); assertFalse(input.receipt.isFinalCheckAttempted());
                }
            } finally { gate.release(); }
            env.main(() -> { }); gate.assertHealthy();
            shown.checkObserverFailure(); assertEquals(0, shown.calls.get());
            assertNull(env.device.service.getOwnedReviewWindowIdentity());
            assertFalse(ImageReviewPresentationCoordinator.hasHost()); env.assertDrained();
        }
    }

    @Test public void cancellingAfterTheServiceReturnedAHostClosesOnlyThatHostAfterTheObserverReturns() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            Input input = env.input("coordinator-host-published-cancel"); Seen shown = new Seen(env); MainGate observerGate = new MainGate();
            CapturedImageReviewCoordinator.HostObserver observer = (host, session) -> { shown.onShown(host, session); observerGate.hold(); };
            Operation operation = env.review(input, observer, decoder(), WindowManager::removeViewImmediate);
            try {
                observerGate.awaitEntered(); shown.awaitObserved();
                // The service returned its actual host, but layout/attachment is
                // not asserted at that point. While main is held, inspect only
                // the callback's published identity and thread-safe lifecycle.
                env.device.control.cancel(); assertTask("TASK_STOPPED", operation.awaitFailure());
                assertTrue(input.receipt.isClosed()); assertFalse(input.receipt.isFinalCheckAttempted());
                assertFalse(shown.host.whenRemoved().isDone());
            } finally { observerGate.release(); }
            try (Operation finished = operation) {
                env.main(() -> { }); observerGate.assertHealthy();
                shown.host.whenRemoved().get(5, TimeUnit.SECONDS); shown.assertRemoved();
                assertEquals(1, shown.calls.get()); env.assertDrained();
            }
        }
    }

    @Test public void originalEpochAndPublishedConfigurationAreCheckedWhileWaitingForActualApproval() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            try (Environment env = new Environment("CapturePatternActivity")) {
                if (unsupported(env)) return;
                Input input = env.input("coordinator-original-context-" + mode); Seen shown = new Seen(env);
                SettingsStore store = new SettingsStore(env.device.context);
                SettingsStore.SavedSettings saved = store.load();
                // Register ahead of the actual overlay watcher, so notification
                // delivery cannot stand in for the coordinator's memory guard.
                try (HeldConfigurationInvalidation held = mode == 1 ? new HeldConfigurationInvalidation(store, env.settings.lease) : null;
                     Operation operation = env.review(input, shown)) {
                    shown.awaitPresented(); assertGateFree(env.device);
                    if (mode == 0) {
                        env.device.control.pause(); env.device.control.resume();
                        assertTask("TASK_PAUSED", operation.awaitFailure()); assertFalse(env.device.control.isCancelled());
                    } else {
                        held.start(saved); held.awaitPublished();
                        Throwable failure = operation.awaitFailure();
                        assertReview(CapturedImageReviewCoordinator.Failure.CONTEXT_CHANGED, failure);
                        held.assertHeld(); held.finish();
                    }
                    shown.host.whenRemoved().get(5, TimeUnit.SECONDS); shown.assertRemoved();
                    assertFalse(input.receipt.isFinalCheckAttempted()); assertTrue(input.receipt.isClosed());
                }
                env.assertDrained();
            }
        }
    }

    @Test public void aRejectedHostsOldButtonsAndCloseCannotApproveOrRemoveTheNextRealHost() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            Input first = env.input("coordinator-first-reject"); Seen old = new Seen(env);
            AtomicReference<Button> oldApprove = new AtomicReference<>();
            try (Operation operation = env.review(first, old)) {
                old.awaitPresented(); env.main(() -> oldApprove.set(old.button(ImageReviewView.APPROVE)));
                old.click(ImageReviewView.REJECT);
                assertReview(CapturedImageReviewCoordinator.Failure.APPROVAL_REJECTED, operation.awaitFailure());
                old.host.whenRemoved().get(5, TimeUnit.SECONDS); old.assertRemoved();
                assertFalse(first.receipt.isFinalCheckAttempted());
            }
            env.assertDrained();
            Input second = env.input("coordinator-next-reject"); Seen current = new Seen(env);
            try (Operation operation = env.review(second, current)) {
                current.awaitPresented(); AgentAccessibilityService.OwnedReviewWindowIdentity identity = env.device.service.getOwnedReviewWindowIdentity();
                assertNotNull(identity);
                env.main(() -> {
                    oldApprove.get().performClick(); old.host.close();
                    assertFalse(current.host.isEnded()); assertTrue(current.host.rootView().isAttachedToWindow());
                    assertTrue(env.device.service.isOwnedReviewWindowCurrent(identity));
                    assertTrue(current.button(ImageReviewView.APPROVE).isEnabled());
                    assertEquals(ImageReviewSession.State.PRESENTED, current.session.state());
                    assertFalse(current.host.whenRemoved().isDone());
                });
                assertFalse(operation.isDone()); current.click(ImageReviewView.REJECT);
                assertReview(CapturedImageReviewCoordinator.Failure.APPROVAL_REJECTED, operation.awaitFailure());
                current.host.whenRemoved().get(5, TimeUnit.SECONDS); current.assertRemoved();
            }
            env.assertDrained();
        }
    }

    @Test public void aStillAttachedApprovedHostTimesOutWithoutFinalValidationOrAnImageResult() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity")) {
            if (unsupported(env)) return;
            Input input = env.input("coordinator-remove-refused"); Seen shown = new Seen(env);
            AtomicBoolean refuse = new AtomicBoolean(true); AtomicInteger removes = new AtomicInteger();
            SecureImageReviewOverlay.WindowRemover remover = (manager, root) -> {
                removes.incrementAndGet(); if (!refuse.get()) manager.removeViewImmediate(root);
            };
            try (Operation operation = env.review(input, shown, decoder(), remover)) {
                try {
                    shown.awaitPresented(); shown.click(ImageReviewView.APPROVE);
                    assertReview(CapturedImageReviewCoordinator.Failure.REMOVAL_TIMEOUT, operation.awaitFailure());
                    assertTrue(removes.get() > 0); assertFalse(input.receipt.isFinalCheckAttempted());
                    assertTrue(input.receipt.isClosed()); assertEquals(ImageReviewSession.State.CONSUMED, shown.session.state());
                    env.main(() -> {
                        assertTrue(shown.host.isEnded()); assertTrue(shown.host.rootView().isAttachedToWindow());
                        assertNull(shown.preview().getDrawable()); assertTrue(ImageReviewPresentationCoordinator.hasHost());
                        assertFalse(shown.host.whenRemoved().isDone());
                    });
                    await(() -> !ImagePreviewLoader.isWorkPending(), "Decode work did not drain");
                    assertEquals(0, ImageWorkset.process().reservedBytes()); assertGateFree(env.device);
                } finally {
                    refuse.set(false); if (shown.host != null) env.main(shown.host::close);
                }
                shown.host.whenRemoved().get(5, TimeUnit.SECONDS); shown.assertRemoved();
            }
            env.assertDrained();
        }
    }

    @Test public void actualFixtureMutationDuringRemovalIsRejectedByTheFreshFinalGuard() throws Exception {
        try (Environment env = new Environment("SlowActivity")) {
            if (unsupported(env)) return;
            Input input = env.input("coordinator-target-mutates"); Seen shown = new Seen(env); MainGate removedGate = new MainGate();
            SecureImageReviewOverlay.WindowRemover remover = (manager, root) -> { manager.removeViewImmediate(root); removedGate.hold(); };
            try (Operation operation = env.review(input, shown, decoder(), remover)) {
                shown.awaitPresented();
                // The click runs on main; the coordinator worker subsequently
                // posts actual removal, which stops before acknowledgement, not
                // before detach. Keep click assertions on the test's sync path.
                shown.click(ImageReviewView.APPROVE);
                try {
                    removedGate.awaitEntered(); assertFalse(shown.host.whenRemoved().isDone());
                    assertEquals(ImageReviewSession.State.CONSUMED, shown.session.state()); assertGateFree(env.device);
                    // This explicit synthetic test action is outside the agent's
                    // dispatcher and runs against the real fixture process. The
                    // production coordinator never gets a fake final snapshot.
                    incrementActualFixture(env, input.receipt.windowId);
                    assertEquals(0, env.device.control.getDispatchAttempts());
                } finally { removedGate.release(); }
                Throwable failure = operation.awaitFailure();
                assertTrue(String.valueOf(failure), failure instanceof AndroidDeviceTools.WindowCaptureException);
                assertEquals(AndroidDeviceTools.WindowCaptureFailure.STALE_OBSERVATION,
                        ((AndroidDeviceTools.WindowCaptureException) failure).code);
                removedGate.assertHealthy(); shown.host.whenRemoved().get(5, TimeUnit.SECONDS); shown.assertRemoved();
                assertTrue(input.receipt.isFinalCheckAttempted()); assertTrue(input.receipt.isClosed());
                assertEquals(0, env.device.control.getDispatchAttempts()); assertGateFree(env.device);
            }
            env.assertDrained();
        }
    }

    private boolean unsupported(Environment env) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) return false;
        JsonObject observed = env.observe(), arguments = new JsonObject();
        arguments.addProperty("expectedRevision", string(observed, "revision"));
        ToolRequest original = new ToolRequest(ToolCatalog.PROTOCOL_VERSION, env.device.control.getTaskId(),
                "coordinator-low-api", env.device.control.getExecutionEpoch(), env.device.control.getProfileId(), "take_screenshot", arguments);
        WindowBufferCapturer unavailable = WindowBufferCapturer.forTesting(env.tools,
                (id, callback) -> { env.backendCalls.incrementAndGet(); callback.failure(96); });
        try (WindowBufferCapturer.CaptureRequest unexpected = unavailable.start(original, env.device.control)) {
            env.requests.add(unexpected); fail("A low API must not issue the source needed for review");
        } catch (AndroidDeviceTools.WindowCaptureException rejected) {
            assertEquals(AndroidDeviceTools.WindowCaptureFailure.UNSUPPORTED_API, rejected.code);
        }
        assertEquals(0, env.backendCalls.get()); env.assertDrained();
        screenshots.capture("coordinator-unavailable-without-api34-capture-source-zero-submissions"); return true;
    }
    private static ImagePreviewLoader.Decoder decoder() { return (input, options) -> BitmapFactory.decodeStream(input, null, options); }

    private static final class Seen implements CapturedImageReviewCoordinator.HostObserver {
        final Environment env; final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        final CountDownLatch observed = new CountDownLatch(1);
        volatile SecureImageReviewOverlay host; volatile ImageReviewSession session;
        Seen(Environment env) { this.env = env; }
        @Override public void onShown(SecureImageReviewOverlay actual, ImageReviewSession current) {
            try {
                int count = calls.incrementAndGet();
                assertEquals(Looper.getMainLooper(), Looper.myLooper()); assertEquals(1, count);
                assertNotNull(actual); assertNotNull(current);
                // WindowManager.addView/show returning does not prove attachment
                // or presentation. Those stay mandatory in awaitPresented below.
                session = current; host = actual;
            } catch (RuntimeException | Error failed) {
                // This callback runs asynchronously on the app main thread.
                // Preserve failures for the instrumentation thread, not a crash
                // that discards the remaining device-suite denominator.
                observerFailure.compareAndSet(null, failed);
            } finally { observed.countDown(); }
        }
        void checkObserverFailure() { rethrowUnchecked(observerFailure.get()); }
        void awaitObserved() throws Exception {
            assertTrue("Actual service did not return a review host", observed.await(6, TimeUnit.SECONDS));
            checkObserverFailure(); assertEquals(1, calls.get()); assertNotNull(host); assertNotNull(session);
        }
        Button button(String tag) { return host.rootView().findViewWithTag(tag); }
        ImageView preview() { return host.rootView().findViewWithTag(ImageReviewView.PREVIEW); }
        ImageReviewView content() { return (ImageReviewView) ((ViewGroup) host.rootView()).getChildAt(0); }
        void click(String tag) { env.main(() -> { assertTrue(button(tag).isEnabled()); assertTrue(button(tag).performClick()); }); }
        void awaitPresented() throws Exception {
            awaitObserved();
            try {
                await(() -> {
                    checkObserverFailure();
                    AtomicBoolean ready = new AtomicBoolean();
                    env.main(() -> {
                        assertFalse("Actual review ended: " + session.reason(), host.isEnded());
                        ready.set(session.state() == ImageReviewSession.State.PRESENTED && host.rootView().isAttachedToWindow()
                                && !host.rootView().hasWindowFocus() && button(ImageReviewView.APPROVE).isEnabled()
                                && content().isReadyToPresent());
                    }); return ready.get();
                }, "Actual secure review did not become ready");
            } catch (AssertionError failure) { throw new AssertionError(failure.getMessage() + "\n" + host.diagnosticSummary(), failure); }
        }
        void assertActualWindow(Input input) throws Exception {
            CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> pending = env.device.service.acquireOwnedReviewWindowIdentity(host);
            AgentAccessibilityService.OwnedReviewWindowIdentity identity;
            try { identity = pending.get(5, TimeUnit.SECONDS); } finally { pending.cancel(false); }
            assertTrue(env.device.service.isOwnedReviewWindowCurrent(identity));
            env.main(() -> {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) host.rootView().getLayoutParams();
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type);
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
                assertFalse(host.rootView().hasWindowFocus());
            });
            List<AccessibilityWindowInfo> windows = freshWindows(env.device); int reviews = 0, focused = 0;
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.isFocused()) { focused++; assertEquals(input.receipt.windowId, window.getId()); assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, window.getType()); }
                    if (window.getId() == identity.windowId) {
                        reviews++; assertEquals(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, window.getType());
                        assertFalse(window.isFocused()); assertEquals(identity.displayId, window.getDisplayId());
                        Rect bounds = new Rect(); window.getBoundsInScreen(bounds); assertEquals(identity.copyBounds(), bounds);
                    }
                }
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
            assertEquals(1, reviews); assertEquals(1, focused);
        }
        void assertRemoved() {
            checkObserverFailure();
            env.main(() -> {
                assertFalse(host.rootView().isAttachedToWindow()); assertNull(preview().getDrawable());
                assertTrue(host.whenRemoved().isDone()); assertFalse(ImageReviewPresentationCoordinator.hasHost());
                assertNull(env.device.service.getOwnedReviewWindowIdentity());
            });
        }
    }

    /** A bounded main-thread scheduling barrier, never an approval/removal substitute. */
    private static final class MainGate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean expired = new AtomicBoolean();
        void hold() {
            entered.countDown();
            try { if (!release.await(4, TimeUnit.SECONDS)) expired.set(true); }
            catch (InterruptedException interrupted) { expired.set(true); Thread.currentThread().interrupt(); }
        }
        void awaitEntered() throws Exception { assertTrue("Main scheduling barrier was not reached", entered.await(3, TimeUnit.SECONDS)); }
        void release() { release.countDown(); }
        void assertHealthy() { assertFalse("Emergency barrier timeout cannot replace explicit release", expired.get()); }
    }

    /** Future is notification only; the actual worker/result retain independent ownership. */
    private static final class Operation implements AutoCloseable {
        final Environment env; final ExecutorService worker = Executors.newSingleThreadExecutor();
        final AtomicReference<LocalToolResult> output = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>(); final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean started = new AtomicBoolean(); final Future<?> future;
        Operation(Environment env, Input input, CapturedImageReviewCoordinator.HostObserver observer,
                  ImagePreviewLoader.Decoder decoder, SecureImageReviewOverlay.WindowRemover remover) {
            this.env = env;
            future = worker.submit(() -> {
                started.set(true);
                LocalToolResult result = null;
                try {
                    result = CapturedImageReviewCoordinator.reviewSyntheticForTesting(input.request, env.device.control,
                            env.tools, input.receipt, observer, decoder, remover);
                    output.set(result); result = null;
                } catch (Throwable failed) { failure.set(failed); }
                finally { try { if (result != null) result.close(); } finally { done.countDown(); } }
            });
        }
        boolean isDone() { return done.getCount() == 0; }
        void awaitDone() throws Exception { assertTrue("Actual coordinator worker did not finish", done.await(8, TimeUnit.SECONDS)); }
        Throwable awaitFailure() throws Exception {
            awaitDone(); LocalToolResult unexpected = output.getAndSet(null);
            if (unexpected != null) try (LocalToolResult ignored = unexpected) { fail("Unexpected typed image escaped the failed review"); }
            Throwable caught = failure.get(); assertNotNull("No typed result or failure", caught); return caught;
        }
        LocalToolResult takeSuccess() throws Exception {
            awaitDone(); if (failure.get() != null) throw new AssertionError("Actual coordinator failed", failure.get());
            LocalToolResult result = output.getAndSet(null); assertNotNull(result); return result;
        }
        @Override public void close() throws Exception {
            if (!isDone()) { env.device.control.cancel(); future.cancel(true); }
            worker.shutdownNow();
            try {
                assertTrue("Review worker retained resources after cleanup", worker.awaitTermination(5, TimeUnit.SECONDS));
                // A cancelled queued Future may never enter Runnable.finally.
                // Only actual executor termination proves it cannot start now;
                // its unconsumed receipt still belongs to Environment.inputs.
                if (!started.get()) done.countDown();
                assertTrue(isDone());
            } finally { LocalToolResult unused = output.getAndSet(null); if (unused != null) unused.close(); }
        }
    }

    private static final class Input implements AutoCloseable {
        final ToolRequest request; final WindowPngConverter.CapturedPng receipt;
        Input(ToolRequest request, WindowPngConverter.CapturedPng receipt) { this.request = request; this.receipt = receipt; }
        @Override public void close() { receipt.close(); }
    }
    private static final class Source {
        final HardwareBuffer buffer; final ColorSpace colorSpace; volatile boolean transferred;
        Source(HardwareBuffer buffer, ColorSpace colorSpace) { this.buffer = buffer; this.colorSpace = colorSpace; }
    }
    private static final class Environment implements AutoCloseable {
        DeviceHarness device; LocalControllerModelServer server; ControllerTestSettings settings; AndroidDeviceTools tools; Rect bounds;
        Integer originalAutomationFlags;
        final AtomicInteger backendCalls = new AtomicInteger();
        final List<Source> sources = new ArrayList<>(); final List<WindowBufferCapturer.CaptureRequest> requests = new ArrayList<>();
        final List<WindowBufferCapturer.CapturedWindow> captures = new ArrayList<>(); final List<Input> inputs = new ArrayList<>();
        final List<Operation> operations = new ArrayList<>();
        Environment(String fixture) throws Exception {
            try {
                await(() -> !WindowBufferCapturer.hasActiveCapture() && !ImagePreviewLoader.isWorkPending()
                        && !ImageReviewPresentationCoordinator.hasHost() && ImageWorkset.process().reservedBytes() == 0, "Previous image owners have not drained");
                device = new DeviceHarness(); AccessibilityServiceInfo info = device.automation.getServiceInfo(); assertNotNull(info);
                originalAutomationFlags = info.flags; info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; device.automation.setServiceInfo(info);
                server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(device.context, server);
                tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                        ActionConfirmationPolicy.Mode.CONFIRM_ALL, settings.lease,
                        (message, control, epoch) -> { throw new AssertionError("Structured confirmation must not replace image review"); });
                device.launch(fixture); observe(); bounds = focusedBounds(device);
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        void main(Runnable action) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            device.instrumentation.runOnMainSync(() -> {
                try { action.run(); }
                catch (RuntimeException | Error failed) { failure.set(failed); }
            });
            // runOnMainSync schedules a main Runnable; propagate its assertions
            // on the instrumentation thread rather than terminating the process.
            rethrowUnchecked(failure.get());
        }
        JsonObject observe() throws Exception { return awaitObservation(tools, device.control, data -> FIXTURE.equals(string(data, "packageName"))); }
        Source source() {
            if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("Synthetic hardware input requires API 34");
            Bitmap software = null, hardware = null; HardwareBuffer buffer = null; boolean retained = false;
            try {
                software = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888); software.eraseColor(Color.CYAN);
                hardware = software.copy(Bitmap.Config.HARDWARE, false); assertNotNull(hardware);
                buffer = hardware.getHardwareBuffer(); assertNotNull(buffer);
                Source source = new Source(buffer, hardware.getColorSpace()); sources.add(source); retained = true; return source;
            } finally { if (hardware != null) hardware.recycle(); if (software != null) software.recycle(); if (!retained && buffer != null) buffer.close(); }
        }
        Input input(String id) throws Exception {
            Source source = source(); JsonObject observed = observe(), arguments = new JsonObject();
            arguments.addProperty("expectedRevision", string(observed, "revision"));
            ToolRequest original = new ToolRequest(ToolCatalog.PROTOCOL_VERSION, device.control.getTaskId(), id,
                    device.control.getExecutionEpoch(), device.control.getProfileId(), "take_screenshot", arguments);
            WindowBufferCapturer capturer = WindowBufferCapturer.forTesting(tools, (windowId, callback) -> {
                assertEquals(observed.get("windowId").getAsInt(), windowId); backendCalls.incrementAndGet(); source.transferred = true;
                callback.success(source.buffer, source.colorSpace, SystemClock.uptimeMillis());
            });
            WindowBufferCapturer.CaptureRequest request = capturer.start(original, device.control); requests.add(request);
            WindowBufferCapturer.CapturedWindow captured = request.awaitAndClaim(); captures.add(captured);
            WindowPngConverter.CapturedPng receipt = new WindowPngConverter().convertCaptured(captured);
            try { Input input = new Input(original, receipt); inputs.add(input); return input; }
            catch (RuntimeException | Error failed) { receipt.close(); throw failed; }
        }
        Operation review(Input input, Seen shown) { return review(input, shown, decoder(), WindowManager::removeViewImmediate); }
        Operation review(Input input, CapturedImageReviewCoordinator.HostObserver observer,
                         ImagePreviewLoader.Decoder decoder, SecureImageReviewOverlay.WindowRemover remover) {
            Operation operation = new Operation(this, input, observer, decoder, remover); operations.add(operation); return operation;
        }
        void assertDrained() throws Exception {
            await(() -> requests.stream().allMatch(WindowBufferCapturer.CaptureRequest::isWorkerDone)
                    && !WindowBufferCapturer.hasActiveCapture() && !ImagePreviewLoader.isWorkPending()
                    && !ImageReviewPresentationCoordinator.hasHost() && ImageWorkset.process().reservedBytes() == 0,
                    "Actual image/capture/review owners have not drained");
            assertEquals(0, server.requestCount()); int caps = device.service.getServiceInfo().getCapabilities();
            assertEquals(0, caps & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
            assertEquals(0, caps & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES);
            assertFalse(tools.availableTools().contains("take_screenshot")); assertFalse(tools.availableTools().contains("tap"));
            assertFalse(tools.availableTools().contains("swipe"));
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try {
                if (device != null) device.control.cancel();
                for (Operation operation : operations) try { operation.close(); } catch (Exception | Error failed) { failure = append(failure, failed); }
                for (Input input : inputs) input.close(); for (WindowBufferCapturer.CapturedWindow captured : captures) captured.close();
                for (WindowBufferCapturer.CaptureRequest request : requests) request.close();
                if (device != null) main(() -> { });
                await(() -> requests.stream().allMatch(WindowBufferCapturer.CaptureRequest::isWorkerDone)
                        && !WindowBufferCapturer.hasActiveCapture() && !ImagePreviewLoader.isWorkPending()
                        && !ImageReviewPresentationCoordinator.hasHost(), "Cleanup did not drain actual owners");
                for (Source source : sources) if (source.transferred) assertTrue("Transferred buffer was not closed by its owner", source.buffer.isClosed());
                assertEquals(0, ImageWorkset.process().reservedBytes());
            } catch (Exception | Error failed) { failure = append(failure, failed); }
            finally {
                for (Source source : sources) if (!source.transferred) try { source.buffer.close(); } catch (RuntimeException | Error failed) { failure = append(failure, failed); }
                try {
                    if (device != null && originalAutomationFlags != null) {
                        AccessibilityServiceInfo info = device.automation.getServiceInfo(); assertNotNull(info);
                        info.flags = originalAutomationFlags; device.automation.setServiceInfo(info);
                    }
                } catch (Exception | Error failed) { failure = append(failure, failed); }
                try { if (settings != null) settings.close(); } catch (Exception | Error failed) { failure = append(failure, failed); }
                finally {
                    try { if (server != null) server.close(); } catch (Exception | Error failed) { failure = append(failure, failed); }
                    finally { try { if (device != null) device.close(); } catch (Exception | Error failed) { failure = append(failure, failed); } }
                }
            }
            if (failure instanceof Error) throw (Error) failure; if (failure instanceof Exception) throw (Exception) failure;
        }
    }

    private static List<AccessibilityWindowInfo> freshWindows(DeviceHarness device) {
        // Sequential fresh test-connection metadata; not an independent AIC cache.
        AccessibilityServiceInfo info = device.automation.getServiceInfo(); assertNotNull(info); device.automation.setServiceInfo(info);
        List<AccessibilityWindowInfo> windows = device.automation.getWindows(); assertNotNull(windows); return windows;
    }
    private static Rect focusedBounds(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = freshWindows(device);
        try {
            Rect result = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) { assertNull(result); result = new Rect(); window.getBoundsInScreen(result); }
            assertNotNull(result); return result;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }
    private static void incrementActualFixture(Environment env, int expectedWindow) throws Exception {
        AccessibilityNodeInfo root = actualFixtureRoot(env.device, expectedWindow);
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/increment");
            try { assertEquals(1, nodes.size()); assertTrue(nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
            finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
        } finally { root.recycle(); }
        long deadline = SystemClock.elapsedRealtime() + 1_500; boolean changed = false;
        while (!changed && SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo current = actualFixtureRoot(env.device, expectedWindow);
            try {
                List<AccessibilityNodeInfo> counters = current.findAccessibilityNodeInfosByViewId(FIXTURE + ":id/counter_value");
                try { assertEquals(1, counters.size()); changed = "Count: 1".contentEquals(counters.get(0).getText()); }
                finally { for (AccessibilityNodeInfo counter : counters) counter.recycle(); }
            } finally { current.recycle(); }
            if (!changed) SystemClock.sleep(10);
        }
        assertTrue("Actual fixture counter did not change while removal was unacknowledged", changed);
    }
    private static AccessibilityNodeInfo actualFixtureRoot(DeviceHarness device, int expectedWindow) {
        AccessibilityServiceInfo info = device.automation.getServiceInfo(); assertNotNull(info); device.automation.setServiceInfo(info);
        AccessibilityNodeInfo root = device.automation.getRootInActiveWindow(); assertNotNull(root);
        boolean transferred = false;
        try { assertEquals(FIXTURE, String.valueOf(root.getPackageName())); assertEquals(expectedWindow, root.getWindowId()); transferred = true; return root; }
        finally { if (!transferred) root.recycle(); }
    }
    private static void assertGateFree(DeviceHarness device) {
        boolean acquired = device.service.tryBeginDeviceCall();
        try { assertTrue("UI waiting or finished guard retained the device gate", acquired); }
        finally { if (acquired) device.service.endDeviceCall(); }
    }
    private static void assertReview(CapturedImageReviewCoordinator.Failure expected, Throwable actual) {
        assertTrue(String.valueOf(actual), actual instanceof CapturedImageReviewCoordinator.ReviewException);
        assertEquals(expected, ((CapturedImageReviewCoordinator.ReviewException) actual).failure);
    }
    private static void assertTask(String expected, Throwable actual) {
        assertTrue(String.valueOf(actual), actual instanceof TaskControlException); assertEquals(expected, ((TaskControlException) actual).getCode());
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }
    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure != null) throw new AssertionError("Unexpected callback failure", failure);
    }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; if (first != next) first.addSuppressed(next); return first; }
}
