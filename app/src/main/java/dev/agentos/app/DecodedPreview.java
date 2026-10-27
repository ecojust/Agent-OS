package dev.agentos.app;

import android.graphics.Bitmap;

import dev.agentos.core.ImageWorkset;

import java.util.Objects;

/**
 * Owns one decoded preview and its known pixel-capacity reservation. Once exposed
 * to a View, releasing this owner does not prove RenderThread/GPU memory release.
 */
final class DecodedPreview implements AutoCloseable {
    private Bitmap bitmap;
    private ImageWorkset.Reservation reservation;
    private boolean rendererExposed;

    DecodedPreview(Bitmap bitmap, ImageWorkset.Reservation reservation) {
        this.bitmap = Objects.requireNonNull(bitmap);
        this.reservation = Objects.requireNonNull(reservation);
    }

    /** Mark before returning the Bitmap, including an attachment that later fails. */
    synchronized Bitmap exposeToRenderer() {
        if (rendererExposed || bitmap == null || bitmap.isRecycled() || bitmap.isMutable()) {
            throw new IllegalStateException("图片预览已失效。");
        }
        rendererExposed = true;
        return bitmap;
    }

    /** A View must first detach its Drawable before releasing an exposed owner. */
    @Override public synchronized void close() {
        Bitmap released = bitmap;
        ImageWorkset.Reservation owned = reservation;
        bitmap = null;
        reservation = null;
        try {
            // A bitmap never posted to a renderer can be reclaimed directly.
            if (released != null && !rendererExposed) released.recycle();
        } finally {
            if (owned != null) owned.close();
        }
    }
}
