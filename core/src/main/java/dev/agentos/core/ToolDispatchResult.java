package dev.agentos.core;

import com.google.gson.JsonObject;

/** Metadata may be retained; the separate image has one owner and must be closed. */
public final class ToolDispatchResult implements AutoCloseable {
    private final JsonObject metadata;
    private ImageAttachment image;
    ToolDispatchResult(JsonObject metadata, ImageAttachment image) { this.metadata = metadata.deepCopy(); this.image = image; }
    public JsonObject getResult() { return metadata.deepCopy(); }
    public synchronized ImageAttachment takeImage() { ImageAttachment result = image; image = null; return result; }
    @Override public synchronized void close() { if (image != null) { image.close(); image = null; } }
}
