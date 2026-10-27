package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.WindowManager;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Shared host/decoder protocol; the second host is synthetic, not an actual overlay. */
@RunWith(AndroidJUnit4.class)
public final class ImageReviewPresentationInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence(
            "shared_review_admission_protocol_no_window_capture");

    @Test public void anotherHostImplementationExcludesDialogsWithoutRevokingItsDuplicateSession() {
        try (Harness harness = new Harness(); ReviewData first = new ReviewData();
             ReviewData rejected = new ReviewData(); ReviewData replacement = new ReviewData()) {
            harness.main(() -> {
                SyntheticHost other = new SyntheticHost(first);
                SyntheticHost next = new SyntheticHost(replacement);
                try {
                    assertNotNull(other.claim());
                    SecureImageReviewDialog unshown = harness.dialog(first, null);
                    unshown.close();
                    assertEquals(ImageReviewSession.State.PREPARING, first.session.state());
                    SecureImageReviewDialog duplicate = harness.dialog(first, null);
                    assertThrows(IllegalStateException.class, duplicate::show);
                    assertFalse(first.image.isClosed());
                    assertTrue(ImageReviewPresentationCoordinator.isCurrent(first.session, other.token));

                    SecureImageReviewDialog different = harness.dialog(rejected, null);
                    assertThrows(IllegalStateException.class, different::show);
                    assertTrue(rejected.image.isClosed());
                    assertEquals(ImageReviewSession.State.INVALIDATED, rejected.session.state());
                    assertFalse(first.image.isClosed());

                    other.close();
                    assertNotNull(next.claim());
                    // A late release from a different host cannot clear replacement admission.
                    ImageReviewPresentationCoordinator.release(other);
                    assertTrue(ImageReviewPresentationCoordinator.isCurrent(replacement.session, next.token));
                    assertFalse(replacement.image.isClosed());
                    assertSecure(harness.activity);
                } finally { other.close(); next.close(); }
            });
        }
    }

    @Test public void closingADialogCannotAdmitAnotherImplementationsDecodeBeforeItsReaderFinishes() throws Exception {
        GatedDecoder decoder = new GatedDecoder();
        try (Harness harness = new Harness(); ReviewData first = new ReviewData(); ReviewData next = new ReviewData()) {
            SecureImageReviewDialog dialog = harness.show(first, decoder);
            try {
                decoder.awaitEntered();
                dialog.dismiss(); // Invalidate synchronously before queued main-thread teardown.
                assertTrue(first.image.isClosed());
                harness.main(() -> {
                    assertFalse(dialog.isShowing());
                    assertFalse(ImageReviewPresentationCoordinator.hasHost());
                    SyntheticHost replacement = new SyntheticHost(next);
                    try {
                        assertNotNull(replacement.claim());
                        assertThrows(RejectedExecutionException.class, replacement::load);
                        assertTrue(ImagePreviewLoader.isWorkPending());
                        assertTrue(SecureImageReviewDialog.isPreviewWorkPending());
                        assertEquals(0, replacement.decodedResults.get());
                    } finally { replacement.close(); }
                    assertTrue("The original native decoder retains its PNG reader and pixel reservation",
                            ImageWorkset.process().reservedBytes() >= harness.baseline
                                    + first.image.encodedBytes + ImageAttachment.MAX_DECODED_BYTES);
                });
                decoder.release.countDown();
                await(() -> !ImagePreviewLoader.isWorkPending(), "Late decode consumer retained its work slot");
                assertNotNull(decoder.bitmap.get());
                assertTrue(decoder.bitmap.get().isRecycled());
                assertEquals(Color.GREEN, decoder.pixel.get());
                assertFalse(decoder.expired.get());
                await(() -> ImageWorkset.process().reservedBytes() == harness.baseline,
                        "Closing both hosts did not release the original reader's reservations");
            } finally { decoder.release.countDown(); harness.main(dialog::dismiss); }
        }
    }

    @Test public void aPendingResultFromAnotherHostKeepsDialogsOutUntilTheMainConsumerDiscardsIt() throws Exception {
        GatedDecoder decoder = new GatedDecoder();
        try (Harness harness = new Harness(); ReviewData first = new ReviewData(); ReviewData next = new ReviewData()) {
            SyntheticHost other = new SyntheticHost(first);
            try {
                harness.main(() -> {
                    assertNotNull(other.claim());
                    ImagePreviewLoader.load(first.session, other.token, decoder, other::accept);
                });
                decoder.awaitEntered();
                harness.main(() -> {
                    // Only this test callback blocks main; the actual 32x24 decoder is on its worker.
                    decoder.release.countDown();
                    decoder.awaitDecodedOnMain();
                    await(() -> ImageWorkset.process().reservedBytes() == harness.baseline
                                    + first.image.encodedBytes + next.image.encodedBytes + ImageAttachment.MAX_DECODED_BYTES,
                            "Decoder did not release staging before its main consumer");
                    assertNotNull(decoder.bitmap.get());
                    assertFalse(decoder.bitmap.get().isRecycled());
                    other.close();
                    assertFalse(ImageReviewPresentationCoordinator.hasHost());
                    assertTrue(ImagePreviewLoader.isWorkPending());

                    SecureImageReviewDialog denied = harness.dialog(next, null);
                    assertThrows(RejectedExecutionException.class, denied::show);
                    assertFalse(denied.isShowing());
                    assertTrue(next.image.isClosed());
                    assertTrue(first.image.isClosed());
                    assertEquals(0, other.decodedResults.get());
                    assertFalse("Pending main consumer still owns the unexposed pixels", decoder.bitmap.get().isRecycled());
                    assertEquals(harness.baseline + ImageAttachment.MAX_DECODED_BYTES,
                            ImageWorkset.process().reservedBytes());
                    assertTrue(ImagePreviewLoader.isWorkPending());
                });
                await(() -> !ImagePreviewLoader.isWorkPending(), "Pending result did not finish its main consumer");
                assertEquals(1, other.decodedResults.get());
                assertTrue(decoder.bitmap.get().isRecycled());
                assertFalse(decoder.expired.get());
                await(() -> ImageWorkset.process().reservedBytes() == harness.baseline,
                        "Pending result retained known capacity after discard");
            } finally { decoder.release.countDown(); harness.main(other::close); }
        }
    }

    /** This protocol adapter deliberately has no window and never marks a session presented. */
    private static final class SyntheticHost implements AutoCloseable {
        final ReviewData data;
        final AtomicInteger decodedResults = new AtomicInteger();
        ImageReviewSession.HostToken token;
        boolean closed;

        SyntheticHost(ReviewData data) { this.data = data; }

        ImageReviewSession.HostToken claim() {
            if (closed || token != null) throw new IllegalStateException("One-shot synthetic host");
            token = ImageReviewPresentationCoordinator.claim(this, data.session);
            return token;
        }

        void load() { ImagePreviewLoader.load(data.session, token, this::accept); }

        void accept(DecodedPreview result) {
            decodedResults.incrementAndGet();
            // This host tests ownership only; no image is ever attached to a renderer.
            if (result != null) result.close();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (token != null) data.session.invalidateHost(token);
            else if (!ImageReviewPresentationCoordinator.isSessionOwnedByAnother(this, data.session)) {
                data.session.invalidate(ImageReviewSession.Reason.HOST_LOST);
            }
            ImageReviewPresentationCoordinator.release(this);
        }
    }

    private static final class GatedDecoder implements SecureImageReviewDialog.PreviewDecoder {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), decoded = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        final AtomicInteger pixel = new AtomicInteger();
        final AtomicBoolean expired = new AtomicBoolean();

        @Override public Bitmap decode(InputStream input, BitmapFactory.Options options) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    expired.set(true);
                    throw new IllegalStateException("Synthetic decoder barrier expired");
                }
                Bitmap result = BitmapFactory.decodeStream(input, null, options);
                bitmap.set(result);
                if (result != null) pixel.set(result.getPixel(4, 12));
                return result;
            } catch (InterruptedException interrupted) {
                expired.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic decoder interrupted", interrupted);
            } finally { decoded.countDown(); }
        }

        void awaitEntered() throws Exception {
            assertTrue("Actual decoder worker did not start", entered.await(3, TimeUnit.SECONDS));
        }

        void awaitDecodedOnMain() {
            try { assertTrue("Synthetic decoder did not return", decoded.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }

    private static final class ReviewData implements AutoCloseable {
        final RunControl run = new RunControl("user:synthetic");
        final byte[] png = syntheticPng();
        final PngImage image = PngImage.fromBytes(png);
        final ImageReviewSession.Binding binding = binding(run);
        final ImageReviewSession session = new ImageReviewSession(binding, image, run, () -> binding);

        @Override public void close() { session.close(); run.cancel(); Arrays.fill(png, (byte) 0); }
    }

    private static final class Harness implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final long baseline = ImageWorkset.process().reservedBytes();
        final Activity activity;

        Harness() {
            assertFalse("Previous review retained host admission", ImageReviewPresentationCoordinator.hasHost());
            assertFalse("Previous review retained preview work", ImagePreviewLoader.isWorkPending());
            activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
        }

        void main(Runnable work) { instrumentation.runOnMainSync(work); }

        SecureImageReviewDialog dialog(ReviewData data, SecureImageReviewDialog.PreviewDecoder decoder) {
            assertSecure(activity);
            SecureImageReviewDialog.Listener listener = new SecureImageReviewDialog.Listener() {
                @Override public void onApproved(ImageReviewSession.ApprovalToken approval) { fail("No protocol test approves an image"); }
                @Override public void onClosed(ImageReviewSession.State state) { }
            };
            return decoder == null ? new SecureImageReviewDialog(activity, data.session, listener)
                    : new SecureImageReviewDialog(activity, data.session, listener, decoder);
        }

        SecureImageReviewDialog show(ReviewData data, SecureImageReviewDialog.PreviewDecoder decoder) {
            AtomicReference<SecureImageReviewDialog> result = new AtomicReference<>();
            main(() -> { result.set(dialog(data, decoder)); result.get().show(); });
            return result.get();
        }

        @Override public void close() {
            main(activity::finish);
            await(() -> !ImagePreviewLoader.isWorkPending(), "Preview work survived test cleanup");
            await(() -> ImageWorkset.process().reservedBytes() == baseline, "Known image capacity survived test cleanup");
            assertFalse("Host admission survived test cleanup", ImageReviewPresentationCoordinator.hasHost());
        }
    }

    private static void await(BooleanSupplier condition, String failure) {
        long deadline = SystemClock.elapsedRealtime() + 3_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(1);
        assertTrue(failure, condition.getAsBoolean());
    }

    private static void assertSecure(Activity activity) {
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    private static ImageReviewSession.Binding binding(RunControl run) {
        CoordinateNodeResolver.Binding observed = new CoordinateNodeResolver.Binding(run.getTaskId(), run.getExecutionEpoch(),
                run.getProfileId(), "dev.agentos.fixture", 0, 7, "synthetic-admission-r1",
                new CoordinateNodeResolver.Bounds(0, 0, 400, 800));
        return new ImageReviewSession.Binding(observed, "synthetic-admission-call", "synthetic-admission-capture",
                new ImageReviewSession.AppIdentity(12000, 1, Collections.singleton(
                        "0000000000000000000000000000000000000000000000000000000000000000")),
                new ImageReviewSession.Recipient("https://review.invalid/v1", "synthetic-vision-model"), "synthetic-configuration");
    }

    private static byte[] syntheticPng() {
        Bitmap image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        try {
            image.eraseColor(Color.GREEN);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            return output.toByteArray();
        } finally { image.recycle(); }
    }
}
