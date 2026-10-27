package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.agentos.core.CoordinateNodeResolver;
import dev.agentos.core.ImageAttachment;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;
import dev.agentos.core.TaskControlException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Actual service-owned windows and synthetic PNGs; no platform screenshot or image disclosure. */
@RunWith(AndroidJUnit4.class)
public final class ImageReviewOverlayInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_image_review_overlay_capture_blocked");

    @Test public void secureNonFocusingWindowReviewsTheSamePngOnceAndCannotReviveAnOldIdentity() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity", false);
             Review review = env.review()) {
            review.show();
            review.awaitPresented();
            AgentAccessibilityService.OwnedReviewWindowIdentity first = review.identity();
            assertRealWindows(env, review, first);
            AtomicReference<Button> oldApprove = new AtomicReference<>();
            AtomicReference<Bitmap> displayed = new AtomicReference<>();
            env.main(() -> {
                View root = review.host.rootView();
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type);
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
                assertFalse(root.hasWindowFocus());
                ImageView view = root.findViewWithTag(ImageReviewView.PREVIEW);
                assertTrue(view.isShown());
                assertTrue(view.isLaidOut());
                Bitmap pixels = ((BitmapDrawable) view.getDrawable()).getBitmap();
                displayed.set(pixels);
                assertFalse(pixels.isMutable());
                assertEquals(32, pixels.getWidth()); assertEquals(24, pixels.getHeight());
                assertEquals(Color.GREEN, pixels.getPixel(4, 12));
                assertEquals(Color.MAGENTA, pixels.getPixel(28, 12));
                TextView recipient = root.findViewWithTag(ImageReviewView.RECIPIENT);
                assertTrue(recipient.getText().toString().contains(review.binding.recipient.endpoint));
                assertTrue(recipient.getText().toString().contains(review.binding.recipient.model));
                buttonWithText(root, "放大图片").performClick();
                assertSame(pixels, ((BitmapDrawable) view.getDrawable()).getBitmap());
                buttonWithText(root, "显示全图").performClick();
                assertSame(pixels, ((BitmapDrawable) view.getDrawable()).getBitmap());
            });
            assertRealWindows(env, review, review.identity());
            Rect detachedBounds = first.copyBounds(); detachedBounds.setEmpty();
            assertFalse(first.copyBounds().isEmpty());
            env.main(() -> {
                oldApprove.set(review.button(ImageReviewView.APPROVE));
                oldApprove.get().performClick();
                assertFalse(oldApprove.get().isEnabled());
                assertTrue("Approved but unconsumed image must remain revocable", review.host.rootView().isAttachedToWindow());
            });
            assertEquals(1, review.approvals.get());
            assertRealWindows(env, review, review.identity());
            try (PngImage transferred = review.session.consume(review.approval.get())) {
                assertSame("Permission transfers the reviewed immutable PNG, not a reconstruction", review.image, transferred);
                assertPngEquals(review.png, transferred);
                assertNull(review.session.consume(review.approval.get()));
            }
            review.awaitEnded();
            assertEquals(ImageReviewSession.State.CONSUMED, review.session.state());
            assertFalse(env.device.service.isOwnedReviewWindowCurrent(first));
            env.main(() -> {
                oldApprove.get().performClick();
                assertNull(((ImageView) review.host.rootView().findViewWithTag(ImageReviewView.PREVIEW)).getDrawable());
                assertFalse("A bitmap already exposed to renderer is not forcibly recycled", displayed.get().isRecycled());
            });
            assertEquals(1, review.approvals.get()); assertEquals(1, review.closes.get());

            try (Review next = env.review()) {
                next.show(); next.awaitPresented();
                AgentAccessibilityService.OwnedReviewWindowIdentity replacement = next.identity();
                assertNotSame(first, replacement);
                review.host.close();
                env.main(() -> oldApprove.get().performClick());
                assertFalse(env.device.service.isOwnedReviewWindowCurrent(first));
                assertTrue(env.device.service.isOwnedReviewWindowCurrent(replacement));
                assertFalse(next.host.isEnded()); assertEquals(0, next.approvals.get());
            }
            assertNoCaptureOrNetwork(env);
        }
    }

    @Test public void externalWindowMetadataRevokesApprovalBeforeAnAsyncVerifierCanReturn() throws Exception {
        for (int eventType : new int[]{AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}) {
            try (Environment env = new Environment("CapturePatternActivity", false);
                 Review review = env.review()) {
                review.show(); review.awaitPresented();
                int actualTargetId = review.binding.observation.windowId;
                long originalEpoch = env.device.control.getExecutionEpoch();
                env.main(() -> {
                    review.button(ImageReviewView.APPROVE).performClick();
                    assertEquals(1, review.approvals.get());
                    assertNotNull(review.approval.get());
                    assertEquals(ImageReviewSession.State.APPROVED, review.session.state());
                    assertTrue(SettingsStore.isCurrent(env.settings.lease));
                    assertEquals(originalEpoch, env.device.control.getExecutionEpoch());
                    // Deterministic injection into the real host's metadata callback. This
                    // does not pretend a real focus/geometry change occurred in the system.
                    review.host.onAccessibilityEvent(eventType, actualTargetId,
                            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                                    ? AccessibilityEvent.WINDOWS_CHANGE_BOUNDS : 0);
                    // Stay in this same main turn: a background validation result cannot
                    // be posted back before the stale approval is attempted here.
                    try (PngImage stale = review.session.consume(review.approval.get())) {
                        assertNull("External metadata must revoke the existing token synchronously", stale);
                    }
                    assertEquals(ImageReviewSession.State.INVALIDATED, review.session.state());
                    assertEquals(originalEpoch, env.device.control.getExecutionEpoch());
                    assertFalse(env.device.control.isPaused());
                    assertFalse(env.device.control.isCancelled());
                });
                review.awaitEnded();
                assertTrue(review.image.isClosed()); assertEquals(1, review.closes.get());
                assertNoCaptureOrNetwork(env);
            }
        }
    }

    @Test public void pauseAndImmediateResumeRevokeAnAlreadyApprovedOriginalEpoch() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity", false);
             Review review = env.review()) {
            review.show(); review.awaitPresented(); review.approve();
            long original = env.device.control.getExecutionEpoch();
            env.device.control.pause(); env.device.control.resume();
            assertTrue(env.device.control.getExecutionEpoch() > original);
            review.awaitEnded(); // Observe host cleanup before asking session.state(), which also checks validity.
            assertEquals(ImageReviewSession.State.INVALIDATED, review.session.state());
            assertNull(review.session.consume(review.approval.get()));
            assertTrue(review.image.isClosed()); assertEquals(1, review.closes.get());
            assertNoCaptureOrNetwork(env);
        }
    }

    @Test public void replacingTheActuallySavedConfigurationRevokesUnconsumedApproval() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity", false);
             Review review = env.review()) {
            review.show(); review.awaitPresented(); review.approve();
            SettingsStore store = new SettingsStore(env.device.context);
            SettingsStore.ConfigurationLease replacement = store.save(store.load());
            assertFalse(SettingsStore.isCurrent(env.settings.lease));
            assertTrue(SettingsStore.isCurrent(replacement));
            assertNotEquals(env.settings.lease.configurationToken, replacement.configurationToken);
            review.awaitEnded();
            assertNull(review.session.consume(review.approval.get()));
            assertEquals(ImageReviewSession.State.INVALIDATED, review.session.state());
            assertEquals(1, review.closes.get()); assertTrue(review.image.isClosed());
            assertNoCaptureOrNetwork(env);
        }
    }

    @Test public void closingDuringActualDecodeRetainsTheReaderAndSharedSlotUntilItReturns() throws Exception {
        GatedDecoder decoder = new GatedDecoder();
        try (Environment env = new Environment("CapturePatternActivity", false);
             Review review = env.review()) {
            try {
                review.show(decoder);
                decoder.awaitEntered();
                env.main(() -> assertFalse(review.button(ImageReviewView.APPROVE).isEnabled()));
                review.host.close(); // Off main: synchronously revokes, without closing the worker's reader.
                assertTrue(review.image.isClosed());
                review.awaitEnded();
                assertTrue(ImagePreviewLoader.isWorkPending());
                assertTrue(SecureImageReviewDialog.isPreviewWorkPending());
                assertTrue("Native decoder still owns the PNG reader and its known pixel capacity",
                        ImageWorkset.process().reservedBytes() >= env.baseline + review.image.encodedBytes
                                + ImageAttachment.MAX_DECODED_BYTES);
                try (Review blocked = env.reviewFrom(review.binding)) {
                    AtomicInteger extraDecodes = new AtomicInteger();
                    env.main(() -> {
                        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                                () -> blocked.showOnMain((input, options) -> {
                                    extraDecodes.incrementAndGet();
                                    return BitmapFactory.decodeStream(input, null, options);
                                }));
                    });
                    assertEquals(0, extraDecodes.get());
                    assertTrue(blocked.image.isClosed());
                }
                assertEquals(0, review.approvals.get());
                decoder.release.countDown();
                await(() -> !ImagePreviewLoader.isWorkPending(), "Late decode did not release its work slot");
                assertFalse(decoder.expired.get()); assertNotNull(decoder.pixels.get());
                assertEquals(Color.GREEN, decoder.leftPixel.get());
                assertEquals(Color.MAGENTA, decoder.rightPixel.get());
                assertTrue("Never-attached late pixels must be recycled", decoder.pixels.get().isRecycled());
                await(() -> ImageWorkset.process().reservedBytes() == env.baseline, "Late reader retained known capacity");
                assertEquals(1, review.closes.get());
                assertNoCaptureOrNetwork(env);
            } finally { decoder.release.countDown(); }
        }
    }

    @Test public void theReviewWindowRemainsAnUnknownObstructionForOrdinaryNodeWrites() throws Exception {
        // Each action gets a new real task/host: a rejected write pauses its original epoch.
        for (String action : new String[]{"click", "type_text", "scroll"}) {
            try (Environment env = new Environment("FixtureActivity", false);
                 Review review = env.review()) {
                AtomicInteger approvals = new AtomicInteger();
                AndroidDeviceTools tools = new AndroidDeviceTools(env.device.service,
                        Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(env.device.context, FIXTURE)),
                        env.settings.executionMode, env.settings.lease,
                        (description, control, epoch) -> { approvals.incrementAndGet(); return true; });
                review.show(); review.awaitPresented();
                AgentAccessibilityService.OwnedReviewWindowIdentity identity = review.identity();
                String id = "click".equals(action) ? "increment" : "type_text".equals(action) ? "edit_value" : "fixture_scroll";
                JsonObject blocked = awaitObservation(tools, env.device.control, data ->
                        FIXTURE.equals(string(data, "packageName")) && data.get("hasOcclusion").getAsBoolean()
                                && "[obscured]".equals(string(node(data, id), "text")));
                Rect target = nodeBounds(node(blocked, id));
                assertTrue("The actual review window must overlap the requested node",
                        Rect.intersects(target, identity.copyBounds()));
                JsonObject args = nodeArguments(blocked, id);
                if ("type_text".equals(action)) args.addProperty("text", "must-not-be-written");
                if ("scroll".equals(action)) args.addProperty("direction", "forward");
                long epoch = env.device.control.getExecutionEpoch();
                TaskControlException paused = assertThrows(TaskControlException.class,
                        () -> tools.execute(action, args, env.device.control, epoch));
                assertEquals("TASK_PAUSED", paused.getCode());
                assertTrue(env.device.control.isPaused());
                assertEquals(0, approvals.get()); assertEquals(0, env.device.control.getDispatchAttempts());
                review.awaitEnded();
                env.device.control.resume();
                JsonObject unchanged = awaitObservation(tools, env.device.control,
                        data -> FIXTURE.equals(string(data, "packageName")) && !data.get("hasOcclusion").getAsBoolean());
                assertEquals("Count: 0", string(node(unchanged, "counter_value"), "text"));
                assertEquals("Saved: empty", string(node(unchanged, "saved_value"), "text"));
                assertFalse(string(node(unchanged, "edit_value"), "text").contains("must-not-be-written"));
                assertEquals("No scroll or other platform action may be dispatched", 0, env.device.control.getDispatchAttempts());
                assertNoCaptureOrNetwork(env);
            }
        }
    }

    @Test public void aRealOverlayExcludesRealDialogShellsWithoutRevokingItsOwnSession() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity", true);
             Review review = env.review(); Review rejected = env.reviewFrom(review.binding)) {
            review.show(); review.awaitPresented();
            env.main(() -> {
                SecureImageReviewDialog duplicate = dialog(env.activity, review);
                SecureImageReviewDialog different = dialog(env.activity, rejected);
                try {
                    // The Activity is in background. Exact admission text proves shared arbitration,
                    // not a later Activity/window readiness failure.
                    assertEquals("已有图片审阅正在进行。",
                            assertThrows(IllegalStateException.class, duplicate::show).getMessage());
                    assertEquals("已有图片审阅正在进行。",
                            assertThrows(IllegalStateException.class, different::show).getMessage());
                    assertTrue(review.button(ImageReviewView.APPROVE).isEnabled());
                    assertTrue(ImageReviewPresentationCoordinator.hasHost());
                    assertFalse(review.image.isClosed()); assertTrue(rejected.image.isClosed());
                } finally { duplicate.dismiss(); different.dismiss(); }
            });
            assertEquals(0, review.closes.get()); assertEquals(0, review.approvals.get());
            assertRealWindows(env, review, review.identity());
            assertNoCaptureOrNetwork(env);
        }
    }

    @Test public void aRealDialogExcludesTheOverlayBeforeAnySecondWindowOrDecoderIsCreated() throws Exception {
        try (Environment env = new Environment("CapturePatternActivity", true);
             Review review = env.review(); Review rejected = env.reviewFrom(review.binding)) {
            env.bringActivityForward();
            AtomicReference<SecureImageReviewDialog> dialog = new AtomicReference<>();
            try {
                env.main(() -> { dialog.set(dialog(env.activity, review)); dialog.get().show(); });
                await(() -> {
                    AtomicBoolean presented = new AtomicBoolean();
                    env.main(() -> presented.set(review.session.state() == ImageReviewSession.State.PRESENTED
                            && dialog.get().getWindow().getDecorView().hasWindowFocus()));
                    return presented.get();
                }, "The real dialog never became the active host");
                AtomicInteger extraDecodes = new AtomicInteger();
                env.main(() -> {
                    assertTrue((dialog.get().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                    assertEquals("已有图片审阅正在进行。", assertThrows(IllegalStateException.class,
                            () -> rejected.showOnMain((input, options) -> {
                                extraDecodes.incrementAndGet();
                                return BitmapFactory.decodeStream(input, null, options);
                            })).getMessage());
                    assertEquals("此图片已有审阅窗口。", assertThrows(IllegalStateException.class,
                            () -> review.showOnMain((input, options) -> {
                                extraDecodes.incrementAndGet();
                                return BitmapFactory.decodeStream(input, null, options);
                            })).getMessage());
                    assertTrue(dialog.get().isShowing());
                    assertFalse(review.image.isClosed()); assertTrue(rejected.image.isClosed());
                    assertNull(env.device.service.getOwnedReviewWindowIdentity());
                });
                assertEquals(0, extraDecodes.get()); assertEquals(0, review.approvals.get());
                assertNoCaptureOrNetwork(env);
            } finally { env.main(() -> { if (dialog.get() != null) dialog.get().dismiss(); }); }
        }
    }

    private static SecureImageReviewDialog dialog(Activity activity, Review review) {
        return new SecureImageReviewDialog(activity, review.session, new SecureImageReviewDialog.Listener() {
            @Override public void onApproved(ImageReviewSession.ApprovalToken token) { fail("Admission-only dialog must not approve"); }
            @Override public void onClosed(ImageReviewSession.State state) { }
        });
    }

    private static void assertNoCaptureOrNetwork(Environment env) {
        int capabilities = env.device.service.getServiceInfo().getCapabilities();
        assertEquals(0, capabilities & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
        assertEquals(0, capabilities & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES);
        assertFalse(env.device.tools.availableTools().contains("take_screenshot"));
        assertFalse(env.device.tools.availableTools().contains("tap"));
        assertFalse(env.device.tools.availableTools().contains("swipe"));
        assertEquals("Reviewing synthetic local pixels must not contact the configured model", 0, env.server.requestCount());
    }

    private static void assertRealWindows(Environment env, Review review,
                                          AgentAccessibilityService.OwnedReviewWindowIdentity identity) {
        assertNotNull(identity);
        assertTrue(env.device.service.isOwnedReviewWindowCurrent(identity));
        assertEquals(Display.DEFAULT_DISPLAY, identity.displayId);
        AgentAccessibilityService.OwnedControlWindowIdentity controls = env.device.service.getOwnedControlWindowIdentity();
        assertNotNull(controls); assertNotEquals(controls.windowId, identity.windowId);
        assertFalse(Rect.intersects(controls.copyBounds(), identity.copyBounds()));
        List<AccessibilityWindowInfo> windows = testWindows(env.device);
        int focused = 0, targets = 0, reviews = 0, actualControls = 0;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.isFocused()) {
                    focused++;
                    assertEquals(review.binding.observation.windowId, window.getId());
                }
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getId() == review.binding.observation.windowId) {
                    targets++; assertTrue(window.isFocused());
                    assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, window.getType());
                    assertEquals(review.binding.observation.displayId, window.getDisplayId());
                    assertEquals(review.targetBounds, bounds);
                }
                if (window.getId() == identity.windowId) {
                    reviews++; assertFalse(window.isFocused());
                    assertEquals(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, window.getType());
                    assertEquals(identity.displayId, window.getDisplayId()); assertEquals(identity.copyBounds(), bounds);
                }
                if (window.getId() == controls.windowId) {
                    actualControls++; assertFalse(window.isFocused());
                    assertEquals(controls.copyBounds(), bounds);
                }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        assertEquals(1, focused); assertEquals(1, targets); assertEquals(1, reviews); assertEquals(1, actualControls);
        env.main(() -> assertTrue("Pause and Stop controls remain available", env.device.service.isControlAvailable()));
    }

    /** Separate UiAutomation connection; never changes the product service configuration. */
    private static List<AccessibilityWindowInfo> testWindows(DeviceHarness device) {
        AccessibilityServiceInfo info = device.automation.getServiceInfo();
        assertNotNull(info);
        // Sequential fresh metadata read; the platform may share a cache across connections in this process.
        device.automation.setServiceInfo(info);
        List<AccessibilityWindowInfo> windows = device.automation.getWindows();
        assertNotNull(windows);
        return windows;
    }

    private static Rect actualFocusedBounds(DeviceHarness device, int expectedId) {
        List<AccessibilityWindowInfo> windows = testWindows(device);
        Rect found = null; int focused = 0;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                focused++; assertEquals(expectedId, window.getId());
                assertEquals(Display.DEFAULT_DISPLAY, window.getDisplayId());
                assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, window.getType());
                found = new Rect(); window.getBoundsInScreen(found);
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        assertEquals(1, focused); assertNotNull(found); assertFalse(found.isEmpty());
        return found;
    }

    private static Rect nodeBounds(JsonObject node) {
        JsonArray values = node.getAsJsonArray("bounds");
        return new Rect(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsInt(), values.get(3).getAsInt());
    }

    private static Button buttonWithText(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        throw new AssertionError("Missing review button: " + text);
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void assertPngEquals(byte[] expected, PngImage image) throws Exception {
        byte[] actual = new byte[image.encodedBytes];
        try (PngImage.ReadLease reader = image.openRead()) {
            int offset = 0;
            while (offset < actual.length) {
                int count = reader.read(actual, offset, actual.length - offset);
                assertTrue(count > 0); offset += count;
            }
            assertEquals(-1, reader.read()); assertArrayEquals(expected, actual);
        } finally { Arrays.fill(actual, (byte) 0); }
    }

    private static byte[] syntheticPng() {
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        try {
            for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++)
                bitmap.setPixel(x, y, x < 16 ? Color.GREEN : Color.MAGENTA);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            return bytes.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
        assertTrue(message, condition.getAsBoolean());
    }

    private static final class GatedDecoder implements ImagePreviewLoader.Decoder {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean expired = new AtomicBoolean();
        final AtomicReference<Bitmap> pixels = new AtomicReference<>();
        final AtomicInteger leftPixel = new AtomicInteger(), rightPixel = new AtomicInteger();
        @Override public Bitmap decode(InputStream input, BitmapFactory.Options options) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) { expired.set(true); throw new IllegalStateException("Synthetic decode latch expired"); }
                Bitmap actual = BitmapFactory.decodeStream(input, null, options);
                pixels.set(actual);
                if (actual != null) { leftPixel.set(actual.getPixel(4, 12)); rightPixel.set(actual.getPixel(28, 12)); }
                return actual;
            } catch (InterruptedException interrupted) {
                expired.set(true); Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic decode interrupted", interrupted);
            }
        }
        void awaitEntered() throws InterruptedException {
            assertTrue("Actual decode worker did not start", entered.await(5, TimeUnit.SECONDS));
        }
    }

    private static final class Review implements AutoCloseable {
        final Environment env;
        final byte[] png = syntheticPng();
        final PngImage image = PngImage.fromBytes(png);
        final ImageReviewSession.Binding binding;
        final Rect targetBounds;
        final ImageReviewSession session;
        final AtomicReference<ImageReviewSession.ApprovalToken> approval = new AtomicReference<>();
        final AtomicInteger approvals = new AtomicInteger(), closes = new AtomicInteger();
        SecureImageReviewOverlay host;

        Review(Environment env, ImageReviewSession.Binding binding) {
            this.env = env; this.binding = binding;
            CoordinateNodeResolver.Bounds rect = binding.observation.windowBounds;
            targetBounds = new Rect(rect.left, rect.top, rect.right, rect.bottom);
            session = new ImageReviewSession(binding, image, env.device.control,
                    () -> SettingsStore.isCurrent(env.settings.lease) ? binding : null);
        }

        void show() { show((input, options) -> BitmapFactory.decodeStream(input, null, options)); }
        void show(ImagePreviewLoader.Decoder decoder) { env.main(() -> showOnMain(decoder)); }
        void showOnMain(ImagePreviewLoader.Decoder decoder) {
            host = env.device.service.showImageReview(session, env.device.control, binding.observation.executionEpoch,
                    env.settings.lease, new SecureImageReviewOverlay.Listener() {
                        @Override public void onApproved(ImageReviewSession.ApprovalToken token) {
                            approval.set(token); approvals.incrementAndGet();
                        }
                        @Override public void onClosed(ImageReviewSession.State state, ImageReviewSession.Reason reason) {
                            closes.incrementAndGet();
                        }
                    }, decoder);
            assertNotNull(host);
            assertFalse("No decoder or identity response has returned to main in this turn", button(ImageReviewView.APPROVE).isEnabled());
        }
        Button button(String tag) { return host.rootView().findViewWithTag(tag); }
        void approve() {
            env.main(() -> { assertTrue(button(ImageReviewView.APPROVE).isEnabled()); button(ImageReviewView.APPROVE).performClick(); });
            assertEquals(1, approvals.get()); assertNotNull(approval.get());
        }
        AgentAccessibilityService.OwnedReviewWindowIdentity identity() throws Exception {
            CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> future =
                    env.device.service.acquireOwnedReviewWindowIdentity(host);
            try { return future.get(5, TimeUnit.SECONDS); }
            finally { future.cancel(false); }
        }
        void awaitPresented() {
            try {
                await(() -> {
                    AtomicBoolean ready = new AtomicBoolean();
                    env.main(() -> {
                        assertFalse("Review ended before the real window was verified: " + session.reason(), host.isEnded());
                        ready.set(session.state() == ImageReviewSession.State.PRESENTED
                                && host.rootView().isAttachedToWindow() && !host.rootView().hasWindowFocus()
                                && button(ImageReviewView.APPROVE).isEnabled());
                    });
                    return ready.get();
                }, "The actual non-focusing review did not become presentable");
            } catch (AssertionError failed) {
                throw new AssertionError(failed.getMessage() + "\n" + host.diagnosticSummary(), failed);
            }
        }
        void awaitEnded() {
            await(() -> {
                AtomicBoolean ended = new AtomicBoolean();
                env.main(() -> ended.set(host.isEnded() && !host.rootView().isAttachedToWindow() && closes.get() == 1));
                return ended.get();
            }, "Invalidated review retained its actual window or callback");
        }
        @Override public void close() {
            try { if (host != null) { host.close(); env.main(() -> { }); } }
            finally { session.close(); Arrays.fill(png, (byte) 0); }
        }
    }

    private static final class Environment implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final long baseline = ImageWorkset.process().reservedBytes();
        DeviceHarness device;
        LocalControllerModelServer server;
        ControllerTestSettings settings;
        Activity activity;
        Integer originalAutomationFlags;
        Environment(String fixture, boolean includeActivity) throws Exception {
            try {
                if (includeActivity) activity = instrumentation.startActivitySync(new Intent(
                        instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                device = new DeviceHarness();
                AccessibilityServiceInfo testInfo = device.automation.getServiceInfo();
                assertNotNull(testInfo);
                originalAutomationFlags = testInfo.flags;
                testInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                device.automation.setServiceInfo(testInfo);
                server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(device.context, server);
                if (!"FixtureActivity".equals(fixture)) device.launch(fixture);
                device.awaitUiIdle();
                assertFalse(ImageReviewPresentationCoordinator.hasHost());
                assertFalse(ImagePreviewLoader.isWorkPending());
            } catch (Exception | Error failed) {
                try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); }
                throw failed;
            }
        }
        void main(Runnable action) { instrumentation.runOnMainSync(action); }
        Review review() throws Exception {
            JsonObject observation = device.observe();
            Rect bounds = actualFocusedBounds(device, observation.get("windowId").getAsInt());
            SettingsStore.AppGrant grant = SettingsStore.captureGrant(device.context, FIXTURE);
            CoordinateNodeResolver.Binding observed = new CoordinateNodeResolver.Binding(device.control.getTaskId(),
                    device.control.getExecutionEpoch(), device.control.getProfileId(), FIXTURE,
                    Display.DEFAULT_DISPLAY, observation.get("windowId").getAsInt(), string(observation, "revision"),
                    new CoordinateNodeResolver.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom));
            ImageReviewSession.Binding binding = new ImageReviewSession.Binding(observed,
                    "synthetic-overlay-call", "synthetic-overlay-capture",
                    new ImageReviewSession.AppIdentity(grant.uid, grant.firstInstallTime, grant.signers),
                    new ImageReviewSession.Recipient(settings.model.endpoint, settings.model.model),
                    settings.lease.configurationToken);
            return new Review(this, binding);
        }
        Review reviewFrom(ImageReviewSession.Binding binding) { return new Review(this, binding); }
        void bringActivityForward() {
            assertNotNull(activity);
            device.context.startActivity(new Intent(device.context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            await(() -> {
                AtomicBoolean focused = new AtomicBoolean();
                main(() -> focused.set(activity.hasWindowFocus() && !activity.isDestroyed()));
                return focused.get();
            }, "The actual owned Activity did not return to foreground");
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try { if (activity != null) main(activity::finish); }
            catch (RuntimeException | Error failed) { failure = failed; }
            try {
                if (device != null && originalAutomationFlags != null) {
                    AccessibilityServiceInfo testInfo = device.automation.getServiceInfo();
                    if (testInfo != null) { testInfo.flags = originalAutomationFlags; device.automation.setServiceInfo(testInfo); }
                }
            } catch (RuntimeException | Error failed) { if (failure == null) failure = failed; else failure.addSuppressed(failed); }
            try { if (device != null) device.close(); }
            catch (Exception | Error failed) { if (failure == null) failure = failed; else failure.addSuppressed(failed); }
            try { if (settings != null) settings.close(); }
            catch (Exception | Error failed) { if (failure == null) failure = failed; else failure.addSuppressed(failed); }
            try { if (server != null) server.close(); }
            catch (Exception | Error failed) { if (failure == null) failure = failed; else failure.addSuppressed(failed); }
            try {
                await(() -> !ImagePreviewLoader.isWorkPending(), "Decode or pending consumer outlived cleanup");
                await(() -> ImageWorkset.process().reservedBytes() == baseline, "Review retained known image capacity");
                assertFalse(ImageReviewPresentationCoordinator.hasHost());
            } catch (RuntimeException | Error failed) { if (failure == null) failure = failed; else failure.addSuppressed(failed); }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
    }
}
