package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import dev.agentos.core.ImageAttachment;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;
import dev.agentos.core.RunControl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                byte[] actual = new byte[transferred.encodedBytes]; // Test-owned comparison only.
                try (PngImage.ReadLease reader = transferred.openRead()) {
                    int offset = 0;
                    while (offset < actual.length) {
                        int count = reader.read(actual, offset, actual.length - offset);
                        assertTrue("Reviewed PNG ended early", count > 0);
                        offset += count;
                    }
                    assertEquals(-1, reader.read());
                    assertArrayEquals(review.png, actual);
                } finally { Arrays.fill(actual, (byte) 0); }
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

    @Test public void fullKnownBufferBudgetRejectsBeforeThePlatformDecoderIsCalled() throws Exception {
        ImageWorkset workset = ImageWorkset.process();
        AtomicInteger decodes = new AtomicInteger();
        try (Review review = new Review((input, options) -> {
            decodes.incrementAndGet();
            return BitmapFactory.decodeStream(input, null, options);
        })) {
            try (ImageWorkset.Reservation occupied = workset.reserve(workset.limitBytes() - workset.reservedBytes())) {
                review.show();
                review.awaitDismissed();
                assertEquals("Budget admission must precede BitmapFactory", 0, decodes.get());
                assertTrue(review.image.isClosed());
                assertEquals(0, review.approvals.get());
                assertEquals(review.startingReservedBytes + occupied.bytes(), workset.reservedBytes());
            }
        }
    }

    @Test public void invalidatedOwnerKeepsAnInFlightReaderValidAndLatePixelsAreRecycled() throws Exception {
        GatedDecoder decoder = new GatedDecoder();
        try (Review review = new Review(decoder)) {
            try {
                review.show();
                decoder.awaitEntered();
                review.dialog.dismiss(); // Non-main invalidation must precede queued UI cleanup.
                review.awaitDismissed();
                assertTrue(review.image.isClosed());
                assertTrue("The running reader and decoder must keep their reservations",
                        ImageWorkset.process().reservedBytes() >= review.startingReservedBytes
                                + review.image.encodedBytes + ImageAttachment.MAX_DECODED_BYTES);
                decoder.release.countDown();
                awaitReservedBytes(review.startingReservedBytes);
                assertFalse(decoder.expired.get());
                assertNotNull("Closing the owner corrupted a running decode", decoder.bitmap.get());
                assertEquals(Color.GREEN, decoder.leftPixel.get());
                assertEquals(Color.MAGENTA, decoder.rightPixel.get());
                assertTrue("A late bitmap never attached to a renderer must be recycled", decoder.bitmap.get().isRecycled());
                assertEquals(0, review.approvals.get());
            } finally { decoder.release.countDown(); }
        }
    }

    @Test public void decodedResultRemainsReservedUntilTheMainConsumerDiscardsIt() throws Exception {
        GatedDecoder decoder = new GatedDecoder();
        try (Review review = new Review(decoder)) {
            try {
                review.show();
                decoder.awaitEntered();
                review.instrumentation.runOnMainSync(() -> {
                    // Hold only this synthetic callback while a 32x24 decode finishes;
                    // the posted install/discard cannot run until this callback returns.
                    decoder.release.countDown();
                    awaitDecodedOnMain(decoder);
                    long held = review.startingReservedBytes + review.image.encodedBytes
                            + ImageAttachment.MAX_DECODED_BYTES;
                    awaitReservedBytesOnMain(held);
                    assertNotNull(decoder.bitmap.get());
                    assertFalse(decoder.bitmap.get().isRecycled());
                    review.dialog.dismiss();
                    assertTrue(review.image.isClosed());
                    assertEquals("The pending main consumer still owns the decoded bitmap",
                            review.startingReservedBytes + ImageAttachment.MAX_DECODED_BYTES,
                            ImageWorkset.process().reservedBytes());
                    assertFalse(decoder.bitmap.get().isRecycled());
                });
                awaitReservedBytes(review.startingReservedBytes);
                assertFalse(decoder.expired.get());
                assertTrue(decoder.bitmap.get().isRecycled());
                assertEquals(0, review.approvals.get());
            } finally { decoder.release.countDown(); }
        }
    }

    @Test public void clearingAnAttachedPreviewReleasesItsKnownOwnerWithoutRecyclingRendererPixels() throws Exception {
        try (Review review = new Review()) {
            review.show();
            review.awaitPresented();
            assertEquals(review.startingReservedBytes + review.image.encodedBytes + ImageAttachment.MAX_DECODED_BYTES,
                    ImageWorkset.process().reservedBytes());
            review.instrumentation.runOnMainSync(() -> {
                ImageView view = review.dialog.getWindow().getDecorView().findViewWithTag(ImageReviewView.PREVIEW);
                Bitmap exposed = ((BitmapDrawable) view.getDrawable()).getBitmap();
                review.dialog.dismiss();
                assertNull(view.getDrawable());
                assertFalse("An exposed bitmap may still belong to RenderThread", exposed.isRecycled());
                assertEquals(review.startingReservedBytes, ImageWorkset.process().reservedBytes());
                // Returning owner quota is deliberately not proof of native/GPU release.
                assertSecure(review.activity);
            });
            assertTrue(review.image.isClosed());
            assertEquals(0, review.approvals.get());
        }
    }

    @Test public void decoderFailureReturnsReservationsAndAdmitsTheNextRealPreview() throws Exception {
        AtomicInteger decodes = new AtomicInteger();
        try (Review failed = new Review((input, options) -> {
            decodes.incrementAndGet();
            throw new IllegalStateException("Synthetic platform decoder failure");
        })) {
            failed.show();
            failed.awaitDismissed();
            awaitReservedBytes(failed.startingReservedBytes);
            assertEquals(1, decodes.get());
            assertTrue(failed.image.isClosed());
            assertEquals(0, failed.approvals.get());
        }
        try (Review next = new Review()) {
            next.show();
            next.awaitPresented();
            assertFalse(next.image.isClosed());
            assertEquals(ImageReviewSession.State.PRESENTED, next.session.state());
        }
    }

    private static void awaitReservedBytes(long expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (ImageWorkset.process().reservedBytes() != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10);
        }
        assertEquals("Known preview owners did not release their reservations", expected, ImageWorkset.process().reservedBytes());
    }

    private static void awaitReservedBytesOnMain(long expected) {
        long deadline = SystemClock.elapsedRealtime() + 2_000;
        while (ImageWorkset.process().reservedBytes() != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1);
        }
        assertEquals("Decoder did not finish its temporary work before the main callback", expected, ImageWorkset.process().reservedBytes());
    }

    private static void awaitDecodedOnMain(GatedDecoder decoder) {
        try { assertTrue("Synthetic decode did not finish", decoder.decoded.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class GatedDecoder implements SecureImageReviewDialog.PreviewDecoder {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), decoded = new CountDownLatch(1);
        final AtomicBoolean expired = new AtomicBoolean();
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        final AtomicInteger leftPixel = new AtomicInteger(), rightPixel = new AtomicInteger();

        @Override public Bitmap decode(InputStream input, BitmapFactory.Options options) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    expired.set(true);
                    throw new IllegalStateException("Synthetic decoder barrier expired");
                }
                Bitmap value = BitmapFactory.decodeStream(input, null, options);
                bitmap.set(value);
                if (value != null) {
                    leftPixel.set(value.getPixel(4, 12));
                    rightPixel.set(value.getPixel(28, 12));
                }
                return value;
            } catch (InterruptedException interrupted) {
                expired.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic decoder interrupted", interrupted);
            } finally { decoded.countDown(); }
        }

        void awaitEntered() throws Exception {
            assertTrue("The actual decode worker did not start", entered.await(3, TimeUnit.SECONDS));
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
        final long startingReservedBytes = ImageWorkset.process().reservedBytes();
        final byte[] png = syntheticPng();
        final PngImage image = PngImage.fromBytes(png);
        final ImageReviewSession.Binding binding = binding(run, "config-a");
        final AtomicReference<ImageReviewSession.Binding> current = new AtomicReference<>(binding);
        final ImageReviewSession session = new ImageReviewSession(binding, image, run, current::get, clock::get);
        final AtomicReference<ImageReviewSession.ApprovalToken> approval = new AtomicReference<>();
        final AtomicInteger approvals = new AtomicInteger(), closes = new AtomicInteger();
        final Activity activity;
        final SecureImageReviewDialog.PreviewDecoder decoder;
        SecureImageReviewDialog dialog;

        Review() {
            this((input, options) -> BitmapFactory.decodeStream(input, null, options));
        }

        Review(SecureImageReviewDialog.PreviewDecoder decoder) {
            this.decoder = decoder;
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
                }, decoder);
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
            awaitReservedBytes(startingReservedBytes);
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (SecureImageReviewDialog.isPreviewWorkPending() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10);
            }
            assertFalse("A pending preview consumer outlived test cleanup", SecureImageReviewDialog.isPreviewWorkPending());
        }
    }
}
