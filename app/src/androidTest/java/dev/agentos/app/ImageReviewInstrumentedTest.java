package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.agentos.core.CoordinateNodeResolver;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.PngImage;
import dev.agentos.core.RunControl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Synthetic pixels exercise the real native review component, never app-window capture. */
@RunWith(AndroidJUnit4.class)
public final class ImageReviewInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_image_review_window_capture_blocked");

    @Test public void sameFinalPngIsDisplayedAndConsumedOnceAfterNativeConfirmation() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            AtomicReference<Button> staleButton = new AtomicReference<>();
            review.instrumentation.runOnMainSync(() -> {
                assertSecure(review.activity);
                assertTrue((review.dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                ImageView view = review.dialog.getWindow().getDecorView().findViewWithTag(ImageReviewView.PREVIEW);
                assertTrue(view.isShown());
                assertTrue(view.isLaidOut());
                Bitmap actual = ((BitmapDrawable) view.getDrawable()).getBitmap();
                assertFalse(actual.isMutable());
                assertEquals(32, actual.getWidth());
                assertEquals(24, actual.getHeight());
                assertEquals(Color.GREEN, actual.getPixel(4, 12));
                assertEquals(Color.MAGENTA, actual.getPixel(28, 12));
                TextView recipient = review.dialog.getWindow().getDecorView().findViewWithTag(ImageReviewView.RECIPIENT);
                assertTrue(recipient.getText().toString().contains(review.binding.recipient.endpoint));
                assertTrue(recipient.getText().toString().contains(review.binding.recipient.model));
                Button approve = review.button(ImageReviewView.APPROVE);
                staleButton.set(approve);
                assertTrue(approve.isEnabled());
                approve.performClick();
                assertFalse(approve.isEnabled());
                assertTrue("Approved image stays revocable until it is consumed", review.dialog.isShowing());
            });
            assertEquals(1, review.approvals.get());
            try (PngImage transferred = review.session.consume(review.approval.get())) {
                assertNotNull(transferred);
                byte[] actual = transferred.copyBytes();
                try { assertArrayEquals(review.png, actual); }
                finally { Arrays.fill(actual, (byte) 0); }
                assertNull(review.session.consume(review.approval.get()));
            }
            review.awaitDismissed();
            review.instrumentation.runOnMainSync(() -> {
                staleButton.get().performClick();
                assertSecure(review.activity);
                assertNull(((ImageView) review.dialog.getWindow().getDecorView()
                        .findViewWithTag(ImageReviewView.PREVIEW)).getDrawable());
            });
            assertEquals(1, review.approvals.get());
            assertEquals(1, review.closes.get());
            assertEquals(ImageReviewSession.State.CONSUMED, review.session.state());
        }
    }

    @Test public void rejectingReviewClearsPixelsAndOldNativeButtonCannotApprove() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            review.instrumentation.runOnMainSync(() -> {
                Button stale = review.button(ImageReviewView.APPROVE);
                review.button(ImageReviewView.REJECT).performClick();
                stale.performClick();
                assertFalse(review.dialog.isShowing());
                assertNull(((ImageView) review.dialog.getWindow().getDecorView()
                        .findViewWithTag(ImageReviewView.PREVIEW)).getDrawable());
            });
            assertEquals(ImageReviewSession.State.REJECTED, review.session.state());
            assertTrue(review.image.isClosed());
            assertEquals(0, review.approvals.get());
            assertEquals(1, review.closes.get());
        }
    }

    @Test public void closingApprovedHostRevokesTheUnconsumedPermit() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            review.instrumentation.runOnMainSync(() -> {
                review.button(ImageReviewView.APPROVE).performClick();
                review.button(ImageReviewView.REJECT).performClick();
                review.dialog.dismiss();
            });
            assertNotNull(review.approval.get());
            assertNull(review.session.consume(review.approval.get()));
            assertEquals(ImageReviewSession.State.INVALIDATED, review.session.state());
            assertTrue(review.image.isClosed());
            assertEquals(1, review.closes.get());
        }
    }

    @Test public void hostTimerReleasesPausedEpochWithoutWaitingForTheModelWorker() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            review.run.pause();
            review.run.resume();
            review.awaitDismissed();
            assertEquals(ImageReviewSession.State.INVALIDATED, review.session.state());
            assertTrue(review.image.isClosed());
            assertEquals(0, review.approvals.get());
        }
    }

    @Test public void hostTimerReleasesChangedRecipientConfiguration() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            review.current.set(binding(review.run, "config-b"));
            review.awaitDismissed();
            assertEquals(ImageReviewSession.State.INVALIDATED, review.session.state());
            assertTrue(review.image.isClosed());
            assertEquals(0, review.approvals.get());
        }
    }

    @Test public void independentHostTimerExpiresAtTheOriginalMonotonicDeadline() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            review.clock.set(ImageReviewSession.TIMEOUT_MILLIS);
            // Do not call session.state() here: the host's independent task must do the cleanup.
            review.awaitDismissed();
            assertEquals(ImageReviewSession.State.EXPIRED, review.session.state());
            assertTrue(review.image.isClosed());
            assertEquals(0, review.approvals.get());
        }
    }

    @Test public void destroyedActivityInvalidatesHostAndDoesNotClearItsSecureFlag() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            review.instrumentation.runOnMainSync(review.activity::finish);
            review.awaitDismissed();
            assertEquals(ImageReviewSession.State.INVALIDATED, review.session.state());
            assertTrue(review.image.isClosed());
            assertEquals(1, review.closes.get());
            review.instrumentation.runOnMainSync(() -> assertSecure(review.activity));
        }
    }

    @Test public void secondHostAdmissionClosesOnlyTheRejectedNewImage() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            RunControl anotherRun = new RunControl("user:synthetic");
            PngImage anotherImage = PngImage.fromBytes(review.png);
            ImageReviewSession.Binding anotherBinding = binding(anotherRun, "config-a");
            try (ImageReviewSession anotherSession = new ImageReviewSession(anotherBinding, anotherImage,
                    anotherRun, () -> anotherBinding)) {
                review.instrumentation.runOnMainSync(() -> {
                    SecureImageReviewDialog anotherDialog = new SecureImageReviewDialog(review.activity,
                            anotherSession, new SecureImageReviewDialog.Listener() {
                        @Override public void onApproved(ImageReviewSession.ApprovalToken token) { fail("Rejected host approved"); }
                        @Override public void onClosed(ImageReviewSession.State state) { }
                    });
                    try { anotherDialog.show(); fail("A second host must be rejected"); }
                    catch (IllegalStateException expected) { }
                    assertTrue(review.dialog.isShowing());
                    assertTrue(review.button(ImageReviewView.APPROVE).isEnabled());
                });
                assertEquals(ImageReviewSession.State.INVALIDATED, anotherSession.state());
                assertTrue(anotherImage.isClosed());
                assertFalse(review.image.isClosed());
            } finally { anotherRun.cancel(); }
        }
    }

    @Test public void closingUnshownDuplicateShellDoesNotRevokeItsActualHost() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            review.instrumentation.runOnMainSync(() -> {
                SecureImageReviewDialog duplicate = new SecureImageReviewDialog(review.activity,
                        review.session, new SecureImageReviewDialog.Listener() {
                    @Override public void onApproved(ImageReviewSession.ApprovalToken token) { fail("Unshown shell approved"); }
                    @Override public void onClosed(ImageReviewSession.State state) { }
                });
                duplicate.close();
                assertTrue(review.dialog.isShowing());
                assertTrue(review.button(ImageReviewView.APPROVE).isEnabled());
                assertEquals(ImageReviewSession.State.PRESENTED, review.session.state());
                assertFalse(review.image.isClosed());
                assertTrue(review.readinessDiagnostic(), review.nativeView().isReadyToPresent());
                assertTrue(review.readinessDiagnostic(), review.dialog.getWindow().getDecorView().hasWindowFocus());
                review.button(ImageReviewView.APPROVE).performClick();
            });
            assertEquals(1, review.approvals.get());
            assertEquals(0, review.closes.get());
            try (PngImage permitted = review.session.consume(review.approval.get())) { assertNotNull(permitted); }
            review.awaitDismissed();
        }
    }

    private static void assertSecure(Activity activity) {
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    private static ImageReviewSession.Binding binding(RunControl run, String configuration) {
        CoordinateNodeResolver.Binding observed = new CoordinateNodeResolver.Binding(run.getTaskId(),
                run.getExecutionEpoch(), run.getProfileId(), "dev.agentos.fixture", 0, 7,
                "synthetic-review-r1", new CoordinateNodeResolver.Bounds(0, 0, 400, 800));
        return new ImageReviewSession.Binding(observed, "synthetic-call", "synthetic-capture",
                new ImageReviewSession.AppIdentity(12000, 1,
                        Collections.singleton("0000000000000000000000000000000000000000000000000000000000000000")),
                new ImageReviewSession.Recipient("https://review.invalid/v1", "synthetic-vision-model"), configuration);
    }

    private static byte[] syntheticPng() {
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        try {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) bitmap.setPixel(x, y, x < 16 ? Color.GREEN : Color.MAGENTA);
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
            return encoded.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static final class Review implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final RunControl run = new RunControl("user:synthetic");
        final AtomicLong clock = new AtomicLong();
        final byte[] png = syntheticPng();
        final PngImage image = PngImage.fromBytes(png);
        final ImageReviewSession.Binding binding = binding(run, "config-a");
        final AtomicReference<ImageReviewSession.Binding> current = new AtomicReference<>(binding);
        final ImageReviewSession session = new ImageReviewSession(binding, image, run, current::get, clock::get);
        final AtomicReference<ImageReviewSession.ApprovalToken> approval = new AtomicReference<>();
        final AtomicInteger approvals = new AtomicInteger(), closes = new AtomicInteger();
        final Activity activity;
        SecureImageReviewDialog dialog;

        Review() {
            Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = instrumentation.startActivitySync(intent);
            instrumentation.waitForIdleSync();
        }

        void show() {
            instrumentation.runOnMainSync(() -> {
                dialog = new SecureImageReviewDialog(activity, session, new SecureImageReviewDialog.Listener() {
                    @Override public void onApproved(ImageReviewSession.ApprovalToken value) {
                        approval.set(value); approvals.incrementAndGet();
                    }
                    @Override public void onClosed(ImageReviewSession.State state) { closes.incrementAndGet(); }
                });
                dialog.show();
                // Decode completion cannot attach its result until this main callback returns.
                assertFalse(button(ImageReviewView.APPROVE).isEnabled());
                assertEquals(ImageReviewSession.State.PREPARING, session.state());
            });
        }

        Button button(String tag) { return dialog.getWindow().getDecorView().findViewWithTag(tag); }

        ImageReviewView nativeView() {
            View content = dialog.getWindow().getDecorView().findViewWithTag(ImageReviewView.CONTENT);
            return (ImageReviewView) content.getParent();
        }

        /** Synthetic host state only; no pixels, recipient details or other page content. */
        String readinessDiagnostic() {
            View decor = dialog.getWindow().getDecorView();
            ImageView preview = decor.findViewWithTag(ImageReviewView.PREVIEW);
            Rect visible = new Rect();
            boolean hasVisibleRect = preview.getGlobalVisibleRect(visible);
            return "Review state=" + session.state() + ", reason=" + session.reason()
                    + ", showing=" + dialog.isShowing() + ", focused=" + decor.hasWindowFocus()
                    + ", ready=" + nativeView().isReadyToPresent()
                    + ", previewAttached=" + preview.isAttachedToWindow()
                    + ", previewShown=" + preview.isShown() + ", previewLaidOut=" + preview.isLaidOut()
                    + ", previewLayoutRequested=" + preview.isLayoutRequested()
                    + ", previewVisibility=" + preview.getWindowVisibility()
                    + ", previewSize=" + preview.getWidth() + "x" + preview.getHeight()
                    + ", visibleRect=" + hasVisibleRect + ":" + visible.toShortString()
                    + ", approvalEnabled=" + button(ImageReviewView.APPROVE).isEnabled();
        }

        void awaitPresented() {
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            AtomicBoolean ready = new AtomicBoolean();
            AtomicReference<String> diagnostic = new AtomicReference<>();
            while (!ready.get() && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync(() -> {
                    ImageReviewSession.State state = session.state();
                    diagnostic.set(readinessDiagnostic());
                    assertTrue(diagnostic.get(), state == ImageReviewSession.State.PREPARING
                            || state == ImageReviewSession.State.PRESENTED);
                    ready.set(state == ImageReviewSession.State.PRESENTED
                            && dialog.getWindow().getDecorView().hasWindowFocus()
                            && nativeView().isReadyToPresent()
                            && button(ImageReviewView.APPROVE).isEnabled());
                });
                if (!ready.get()) SystemClock.sleep(25);
            }
            assertTrue("Synthetic native preview never became ready: " + diagnostic.get(), ready.get());
        }

        void awaitDismissed() {
            AtomicReference<Boolean> showing = new AtomicReference<>(true);
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (showing.get() && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync(() -> showing.set(dialog.isShowing()));
                if (showing.get()) SystemClock.sleep(25);
            }
            assertFalse("Secure review host retained invalid pixels", showing.get());
        }

        @Override public void close() {
            instrumentation.runOnMainSync(() -> {
                if (dialog != null) dialog.dismiss();
                activity.finish();
            });
            session.close();
            run.cancel();
            Arrays.fill(png, (byte) 0);
        }
    }
}
