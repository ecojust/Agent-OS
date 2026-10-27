package dev.agentos.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Looper;
import dev.agentos.core.ImageAttachment;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/** Internal worker conversion; it does not grant capture, review, or image disclosure permission. */
final class WindowPngConverter {
    enum Failure { UNSUPPORTED_FORMAT, INVALID_METADATA, BUDGET_UNAVAILABLE, ALLOCATION_EXCEEDED,
        ALLOCATION_FAILED, RAW_STILL_IN_USE, ENCODE_FAILED, PNG_TOO_LARGE, INVALID_PNG }
    static final class ConversionException extends Exception {
        final Failure code;
        final long actualAllocatedBytes, reservedBytes;
        ConversionException(Failure code) { this(code, -1, -1); }
        ConversionException(Failure code, long actual, long reserved) {
            super("Window PNG conversion failed: " + code + (actual < 0 ? "" : "; actual allocation=" + actual + "; reserved=" + reserved));
            this.code = code; actualAllocatedBytes = actual; reservedBytes = reserved;
        }
    }
    @FunctionalInterface interface SoftwareCopy { Bitmap copy(Bitmap hardware) throws Exception; }
    @FunctionalInterface interface PngEncoder { boolean encode(Bitmap software, OutputStream output) throws Exception; }
    private final SoftwareCopy copier;
    private final PngEncoder encoder;

    WindowPngConverter() {
        this(hardware -> hardware.copy(Bitmap.Config.ARGB_8888, false),
                (software, output) -> software.compress(Bitmap.CompressFormat.PNG, 100, output));
    }
    private WindowPngConverter(SoftwareCopy copier, PngEncoder encoder) {
        this.copier = Objects.requireNonNull(copier); this.encoder = Objects.requireNonNull(encoder);
    }
    /** Narrow worker allocation/encoding seams; all ownership, guards, and validation stay in convert. */
    static WindowPngConverter forTesting(SoftwareCopy copier, PngEncoder encoder) {
        return new WindowPngConverter(copier, encoder);
    }

    /** Consumes source ownership. All native/software cleanup occurs on this same worker. */
    PngImage convert(WindowBufferCapturer.CapturedWindow source) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Window conversion requires a worker");
        Objects.requireNonNull(source);
        Resources resources;
        try { resources = new Resources(source); }
        catch (RuntimeException | Error failed) { source.close(); throw failed; }
        WindowBufferCapturer.ConversionHold hold = null;
        PngImage image = null;
        Throwable failure = null;
        try {
            hold = source.beginConversion();
            // Allocate and retain the finite cleanup record before any native or
            // software allocation. A failed close must not lose its references.
            hold.retainUntilClosed(resources);
            source.checkConversionCurrent();
            resources.reader = source.openRead();
            HardwareBuffer raw = resources.reader.buffer();
            WindowBufferCapturer.Metadata metadata = source.metadata;
            if (metadata.format != HardwareBuffer.RGBA_8888 || metadata.layers != 1
                    || metadata.colorSpace == null || !metadata.colorSpace.equals(ColorSpace.get(ColorSpace.Named.SRGB)))
                throw new ConversionException(Failure.UNSUPPORTED_FORMAT);
            if (raw.isClosed() || raw.getWidth() != metadata.width || raw.getHeight() != metadata.height
                    || raw.getFormat() != metadata.format || raw.getLayers() != metadata.layers || raw.getUsage() != metadata.usage)
                throw new ConversionException(Failure.INVALID_METADATA);
            long fullBytes = pixelBytes(metadata.width, metadata.height);
            if (resources.reader.reduceToValidatedLogicalBytes() != fullBytes) throw new ConversionException(Failure.INVALID_METADATA);
            source.checkConversionCurrent();

            // Admission precedes the explicit full-size software copy. Hardware
            // wrapping shares raw pixels; platform/driver overhead is not measured.
            resources.full.capacity = reserve(fullBytes);
            resources.wrapped.bitmap = Bitmap.wrapHardwareBuffer(raw, metadata.colorSpace);
            if (resources.wrapped.bitmap == null) throw new ConversionException(Failure.ALLOCATION_FAILED);
            source.checkConversionCurrent();
            resources.full.bitmap = copier.copy(resources.wrapped.bitmap);
            validateSoftware(resources.full.bitmap, metadata.width, metadata.height, false, resources.full.capacity.bytes());
            source.checkConversionCurrent();

            // Never exposed to UI/renderer. Confirm the wrapper was recycled
            // before closing the borrowing reader or the original native owner.
            rethrowIfPresent(resources.releaseRaw(null));
            if (!source.isActuallyClosed()) throw new ConversionException(Failure.RAW_STILL_IN_USE);
            source.checkConversionCurrent();

            int[] target = targetSize(metadata.width, metadata.height);
            if (target[0] != metadata.width || target[1] != metadata.height) {
                resources.resized.capacity = reserve(pixelBytes(target[0], target[1]));
                source.checkConversionCurrent();
                resources.resized.bitmap = Bitmap.createBitmap(target[0], target[1], Bitmap.Config.ARGB_8888, true,
                        ColorSpace.get(ColorSpace.Named.SRGB));
                validateSoftware(resources.resized.bitmap, target[0], target[1], true, resources.resized.capacity.bytes());
                resources.canvas = new Canvas(resources.resized.bitmap);
                resources.canvas.drawBitmap(resources.full.bitmap,
                        new Rect(0, 0, metadata.width, metadata.height), new Rect(0, 0, target[0], target[1]),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                rethrowIfPresent(resources.detachCanvas(null));
                source.checkConversionCurrent();
                rethrowIfPresent(resources.full.release(null));
            }
            Bitmap finalBitmap = resources.resized.bitmap == null ? resources.full.bitmap : resources.resized.bitmap;
            source.checkConversionCurrent();
            resources.encodedCapacity = reserve(ImageAttachment.MAX_ENCODED_BYTES);
            resources.encoded = new byte[ImageAttachment.MAX_ENCODED_BYTES];
            BoundedPngOutput output = new BoundedPngOutput(source, resources.encoded);
            boolean compressed;
            try { compressed = encoder.encode(finalBitmap, output); }
            catch (Exception | Error encodeFailure) { output.rethrowFailure(); throw encodeFailure; }
            output.rethrowFailure();
            source.checkConversionCurrent();
            if (!compressed || output.count == 0) throw new ConversionException(Failure.ENCODE_FAILED);
            try { image = PngImage.fromBytes(resources.encoded, 0, output.count); }
            catch (IllegalStateException exhausted) { throw new ConversionException(Failure.BUDGET_UNAVAILABLE); }
            catch (IllegalArgumentException invalid) { throw new ConversionException(Failure.INVALID_PNG); }
            if (image.width != target[0] || image.height != target[1]) throw new ConversionException(Failure.INVALID_PNG);
        } catch (Exception | Error failed) { failure = failed; }

        // No return from inside the allocation scope: cleanup or hold failure
        // must close the final PNG rather than cancelling a pending return of it.
        failure = resources.cleanup(failure);
        if (hold != null && resources.isCleanupComplete()) {
            try { hold.close(); } catch (RuntimeException | Error failed) { failure = append(failure, failed); }
        }
        if (failure == null) {
            try { source.checkConversionCurrent(); }
            catch (Exception | Error failed) { failure = failed; }
        }
        if (failure != null) {
            if (image != null) {
                try { image.close(); } catch (RuntimeException | Error failed) { failure = append(failure, failed); }
            }
            rethrow(failure);
        }
        return image;
    }

    /** One preallocated, bounded record retained by the capture slot if cleanup cannot be confirmed. */
    private static final class Resources implements WindowBufferCapturer.ConversionResources {
        final WindowBufferCapturer.CapturedWindow source;
        final Pixels wrapped = new Pixels(), full = new Pixels(), resized = new Pixels();
        OwnedWindowBuffer.ReadLease reader;
        Canvas canvas;
        boolean canvasDetachAttempted, rawCloseAttempted, rawCloseCompleted;
        ImageWorkset.Reservation encodedCapacity;
        byte[] encoded;
        private volatile boolean cleanupComplete;
        Resources(WindowBufferCapturer.CapturedWindow source) { this.source = source; }
        @Override public boolean isCleanupComplete() { return cleanupComplete; }
        Throwable detachCanvas(Throwable failure) {
            if (canvas != null && !canvasDetachAttempted) {
                canvasDetachAttempted = true;
                try { canvas.setBitmap(null); canvas = null; }
                catch (RuntimeException | Error failed) { failure = append(failure, failed); }
            }
            return failure;
        }
        Throwable releaseRaw(Throwable failure) {
            failure = wrapped.release(failure);
            if (wrapped.bitmap != null) return failure;
            if (reader != null) {
                try { reader.close(); reader = null; }
                catch (RuntimeException | Error failed) { return append(failure, failed); }
            }
            if (!rawCloseAttempted) {
                rawCloseAttempted = true;
                try { source.close(); rawCloseCompleted = true; }
                catch (RuntimeException | Error failed) { failure = append(failure, failed); }
            }
            return failure;
        }
        Throwable cleanup(Throwable failure) {
            failure = detachCanvas(failure);
            failure = releaseRaw(failure);
            // A Canvas still referring to pixels forbids their recycling/accounting release.
            if (canvas == null) {
                failure = full.release(failure);
                failure = resized.release(failure);
            }
            if (encoded != null) { Arrays.fill(encoded, (byte) 0); encoded = null; }
            if (encodedCapacity != null) {
                try { encodedCapacity.close(); encodedCapacity = null; }
                catch (RuntimeException | Error failed) { failure = append(failure, failed); }
            }
            cleanupComplete = canvas == null && wrapped.isReleased() && reader == null && rawCloseCompleted
                    && full.isReleased() && resized.isReleased() && encoded == null && encodedCapacity == null;
            if (!cleanupComplete && failure == null)
                failure = new IllegalStateException("Window conversion cleanup was not confirmed; ownership is retained.");
            return failure;
        }
    }
    private static final class Pixels {
        Bitmap bitmap;
        ImageWorkset.Reservation capacity;
        boolean recycleAttempted;
        boolean isReleased() { return bitmap == null && capacity == null; }
        Throwable release(Throwable failure) {
            if (bitmap != null) {
                if (!recycleAttempted) {
                    recycleAttempted = true;
                    try { if (!bitmap.isRecycled()) bitmap.recycle(); }
                    catch (RuntimeException | Error failed) { failure = append(failure, failed); }
                }
                try { if (bitmap.isRecycled()) bitmap = null; }
                catch (RuntimeException | Error failed) { failure = append(failure, failed); }
            }
            if (bitmap == null && capacity != null) {
                try { capacity.close(); capacity = null; }
                catch (RuntimeException | Error failed) { failure = append(failure, failed); }
            }
            return failure;
        }
    }

    /** Dimension arithmetic only; callers still need a real capture and sufficient raw/full budget. */
    static int[] targetSize(int width, int height) throws ConversionException {
        if (width <= 0 || height <= 0) throw new ConversionException(Failure.INVALID_METADATA);
        long pixels = Math.multiplyExact((long) width, height);
        double scale = Math.min(1, Math.min((double) ImageAttachment.MAX_EDGE / Math.max(width, height),
                Math.sqrt((double) ImageAttachment.MAX_PIXELS / pixels)));
        int targetWidth = Math.max(1, (int) Math.floor(width * scale));
        int targetHeight = Math.max(1, (int) Math.floor(height * scale));
        if (targetWidth > width || targetHeight > height || targetWidth > ImageAttachment.MAX_EDGE
                || targetHeight > ImageAttachment.MAX_EDGE || (long) targetWidth * targetHeight > ImageAttachment.MAX_PIXELS)
            throw new ConversionException(Failure.INVALID_METADATA);
        return new int[]{targetWidth, targetHeight};
    }
    private static long pixelBytes(int width, int height) throws ConversionException {
        if (width <= 0 || height <= 0) throw new ConversionException(Failure.INVALID_METADATA);
        return Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
    }
    private static ImageWorkset.Reservation reserve(long bytes) throws ConversionException {
        try { return ImageWorkset.process().reserve(bytes); }
        catch (IllegalStateException exhausted) { throw new ConversionException(Failure.BUDGET_UNAVAILABLE); }
    }
    private static void validateSoftware(Bitmap bitmap, int width, int height, boolean mutable, long reserved) throws ConversionException {
        if (bitmap == null) throw new ConversionException(Failure.ALLOCATION_FAILED);
        long actual = bitmap.getAllocationByteCount();
        if (actual > reserved) throw new ConversionException(Failure.ALLOCATION_EXCEEDED, actual, reserved);
        if (bitmap.isRecycled() || bitmap.getWidth() != width || bitmap.getHeight() != height
                || bitmap.getConfig() != Bitmap.Config.ARGB_8888 || bitmap.isMutable() != mutable
                || !ColorSpace.get(ColorSpace.Named.SRGB).equals(bitmap.getColorSpace()))
            throw new ConversionException(Failure.INVALID_METADATA);
    }
    private static void rethrowIfPresent(Throwable failure) throws Exception { if (failure != null) rethrow(failure); }
    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next); return first;
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        throw new IllegalStateException(failure);
    }

    /** Fixed-capacity output: no growing buffers, trimming copy, or full Base64 representation. */
    private static final class BoundedPngOutput extends OutputStream {
        private final WindowBufferCapturer.CapturedWindow source;
        private final byte[] bytes;
        private int count;
        private Throwable failure;
        BoundedPngOutput(WindowBufferCapturer.CapturedWindow source, byte[] bytes) { this.source = source; this.bytes = bytes; }
        private void check(int length) throws IOException {
            try {
                source.checkConversionCurrent();
                if (length > bytes.length - count) throw new ConversionException(Failure.PNG_TOO_LARGE);
            } catch (Exception | Error failed) {
                if (failure == null) failure = failed;
                if (failed instanceof Error) throw (Error) failed;
                throw new IOException("Window PNG output was rejected", failed);
            }
        }
        @Override public void write(int value) throws IOException { check(1); bytes[count++] = (byte) value; }
        @Override public void write(byte[] source, int offset, int length) throws IOException {
            Objects.requireNonNull(source);
            if (offset < 0 || length < 0 || offset > source.length - length) throw new IndexOutOfBoundsException();
            check(length); System.arraycopy(source, offset, bytes, count, length); count += length;
        }
        void rethrowFailure() throws Exception { if (failure != null) rethrow(failure); }
    }
}
