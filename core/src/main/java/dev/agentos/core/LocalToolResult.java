package dev.agentos.core;

import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * A local adapter result with optional single-owner PNG data outside JSON. This
 * container does not grant screenshot, disclosure or device-action permission.
 * The handler owns the container until it returns successfully and must close it
 * if its own later work throws. The dispatcher then closes or transfers it.
 */
public final class LocalToolResult implements AutoCloseable {
    private final JsonObject result;
    private final boolean pngResult;
    private final DisclosureGuard imageGuard;
    private PngImage image;

    private LocalToolResult(JsonObject result, PngImage image, boolean pngResult, DisclosureGuard imageGuard) {
        this.result = result == null ? null : result.deepCopy();
        this.image = image;
        this.pngResult = pngResult;
        this.imageGuard = imageGuard;
    }

    /** Legacy textual/null results retain their existing dispatcher validation. */
    public static LocalToolResult metadata(JsonObject result) {
        return new LocalToolResult(result, null, false, null);
    }

    /**
     * Takes ownership at entry, even if copying metadata or creating the container
     * fails. No PNG encoding, array clone, new reader or second image reservation
     * is performed. Only the dispatcher may validate and attach this result.
     */
    public static LocalToolResult withPng(JsonObject result, PngImage image) {
        Objects.requireNonNull(image, "image");
        try { return new LocalToolResult(result, image, true, null); }
        catch (RuntimeException | Error failure) { image.close(); throw failure; }
    }

    /**
     * Also carries a local, bounded memory-only condition into this image's one
     * model request. It is not serialized and is not capture/review permission.
     * Owns the PNG at entry, including a null guard or metadata-copy failure.
     */
    public static LocalToolResult withPng(JsonObject result, PngImage image, DisclosureGuard imageGuard) {
        Objects.requireNonNull(image, "image");
        try { return new LocalToolResult(result, image, true, Objects.requireNonNull(imageGuard, "imageGuard")); }
        catch (RuntimeException | Error failure) { image.close(); throw failure; }
    }

    public JsonObject getResult() { return result == null ? null : result.deepCopy(); }

    // No closeable ownership: the immutable condition travels with the PNG, not JSON.
    DisclosureGuard imageGuard() { return imageGuard; }

    // Keep the original result kind after consumption: returning the same container
    // again must not turn an already-consumed PNG into an innocent textual result.
    boolean hasPngResult() { return pngResult; }

    synchronized PngImage takePng() {
        PngImage transferred = image;
        image = null;
        return transferred;
    }

    @Override public synchronized void close() {
        if (image != null) { image.close(); image = null; }
    }
}
