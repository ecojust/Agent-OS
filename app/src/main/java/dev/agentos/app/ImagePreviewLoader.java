package dev.agentos.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import dev.agentos.core.ImageAttachment;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One process-wide decode or pending main-thread consumer, across all review hosts. */
final class ImagePreviewLoader {
    interface Decoder {
        Bitmap decode(InputStream input, BitmapFactory.Options options);
    }

    private static final int DECODE_TEMP_BYTES = 16 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor DECODER = new ThreadPoolExecutor(0, 1, 10,
            // One waiting request bridges a previous worker's post/return tail.
            // PREVIEW_SLOT still admits only one decode or pending main consumer.
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
        Thread worker = new Thread(task, "agent-os-image-preview");
        worker.setDaemon(true);
        return worker;
    }, new ThreadPoolExecutor.AbortPolicy());
    // Includes decoded results posted to main; worker completion alone must not free this slot.
    private static final AtomicBoolean PREVIEW_SLOT = new AtomicBoolean();

    private ImagePreviewLoader() { }

    /** Admission only: this is not a measure of RenderThread/GPU memory ownership. */
    static boolean isWorkPending() { return PREVIEW_SLOT.get(); }

    static void load(ImageReviewSession session, ImageReviewSession.HostToken hostToken,
                     Consumer<DecodedPreview> consumer) {
        load(session, hostToken, (input, options) -> BitmapFactory.decodeStream(input, null, options), consumer);
    }

    /**
     * The main-thread consumer owns the nullable result and must close an uninstalled
     * preview, including stale-host or attachment failure paths. Closing the host does
     * not cancel native decode or prematurely release a posted result's reservation.
     */
    static void load(ImageReviewSession session, ImageReviewSession.HostToken hostToken,
                     Decoder decoder, Consumer<DecodedPreview> consumer) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("请在主线程准备图片预览。");
        }
        Objects.requireNonNull(session);
        Objects.requireNonNull(hostToken);
        Objects.requireNonNull(decoder);
        Objects.requireNonNull(consumer);
        if (!ImageReviewPresentationCoordinator.isCurrent(session, hostToken)) {
            throw new IllegalStateException("图片审阅宿主已失效。");
        }
        if (!PREVIEW_SLOT.compareAndSet(false, true)) {
            throw new RejectedExecutionException("上一图片预览仍在释放资源。");
        }
        try { DECODER.execute(() -> decodePreview(session, hostToken, decoder, consumer)); }
        catch (RuntimeException | Error rejected) { PREVIEW_SLOT.set(false); throw rejected; }
    }

    private static void decodePreview(ImageReviewSession session, ImageReviewSession.HostToken hostToken,
                                      Decoder decoder, Consumer<DecodedPreview> consumer) {
        byte[] temporary = null;
        Bitmap bitmap = null;
        ImageWorkset.Reservation pixels = null, staging = null;
        DecodedPreview decoded = null;
        boolean posted = false;
        try {
            try {
                // Reserve before either allocation. This bounds the known payload
                // and pixel capacity we own; codec/GPU memory is additional.
                pixels = ImageWorkset.process().reserve(ImageAttachment.MAX_DECODED_BYTES);
                staging = ImageWorkset.process().reserve(DECODE_TEMP_BYTES);
                temporary = new byte[DECODE_TEMP_BYTES];
                try (PngImage.ReadLease encoded = session.openPreviewRead(hostToken)) {
                    if (encoded != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inScaled = false;
                        options.inMutable = false;
                        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        options.inTempStorage = temporary;
                        bitmap = decoder.decode(encoded, options);
                        if (bitmap != null && bitmap.getWidth() == session.width && bitmap.getHeight() == session.height
                                && !bitmap.isMutable() && bitmap.getConfig() == Bitmap.Config.ARGB_8888
                                && bitmap.getAllocationByteCount() <= pixels.bytes()) {
                            decoded = new DecodedPreview(bitmap, pixels);
                            bitmap = null;
                            pixels = null;
                        }
                    }
                }
            } catch (RuntimeException | OutOfMemoryError failedPreview) {
                // Decode errors may contain source details; expose only failed review.
                if (decoded != null) decoded.close();
                decoded = null;
            } finally {
                try {
                    if (bitmap != null) bitmap.recycle(); // Never exposed to a renderer.
                } finally {
                    if (temporary != null) Arrays.fill(temporary, (byte) 0);
                    try { if (staging != null) staging.close(); }
                    finally { if (pixels != null) pixels.close(); }
                }
            }
            DecodedPreview result = decoded;
            posted = MAIN.post(() -> {
                try { consumer.accept(result); }
                finally { PREVIEW_SLOT.set(false); }
            });
        } finally {
            if (!posted) {
                try {
                    // No main consumer owns this result. Revoke synchronously even
                    // if a stopped Handler cannot deliver normal UI cleanup.
                    session.invalidateHost(hostToken);
                } finally {
                    try { if (decoded != null) decoded.close(); }
                    finally { PREVIEW_SLOT.set(false); }
                }
            }
        }
    }
}
