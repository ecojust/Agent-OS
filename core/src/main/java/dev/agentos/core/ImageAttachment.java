package dev.agentos.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;

/** One encoded, task-bound image. Never place this object or its data in a ledger or log. */
public final class ImageAttachment implements AutoCloseable {
    public static final int MAX_ENCODED_BYTES = 1024 * 1024;
    public static final int MAX_EDGE = 1280;
    public static final int MAX_PIXELS = 1024 * 1024;
    public static final int MAX_DECODED_BYTES = MAX_PIXELS * 4;
    private static final String PNG_PREFIX = "data:image/png;base64,";
    public final String taskId, profileId, callId, revision;
    public final long executionEpoch;
    public final int width, height, encodedBytes;
    private PngImage image;
    private boolean consumed;

    private ImageAttachment(ToolRequest request, String revision, PngImage image) {
        taskId = request.taskId; profileId = request.profileId; callId = request.callId;
        executionEpoch = request.executionEpoch; this.revision = revision;
        this.image = image; encodedBytes = image.encodedBytes;
        width = image.width; height = image.height;
    }

    static ImageAttachment fromLocalResult(ToolRequest request, JsonObject result, JsonElement source) {
        return fromLocalResult(request, result, source, ImageWorkset.process());
    }

    static ImageAttachment fromLocalResult(ToolRequest request, JsonObject result, JsonElement source, ImageWorkset workset) {
        if (!"take_screenshot".equals(request.tool) || !booleanTrue(result.get("ok"))) throw invalid();
        JsonObject data = result.has("data") && result.get("data").isJsonObject() ? result.getAsJsonObject("data") : null;
        String revision = string(request.getArguments().get("expectedRevision"));
        if (data == null || revision == null || !revision.equals(string(data.get("revision")))) throw invalid();
        String value = string(source);
        // PNG is deliberately the only format until the platform can provide a
        // separately validated bounded decoder for other encodings.
        if (value == null || !value.startsWith(PNG_PREFIX)
                || value.length() - PNG_PREFIX.length() > ((MAX_ENCODED_BYTES + 2) / 3) * 4) throw invalid();
        int characters = value.length() - PNG_PREFIX.length();
        int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        int remainder = characters % 4;
        int size = characters / 4 * 3 + (remainder == 2 ? 1 : remainder == 3 ? 2 : 0) - padding;
        if (remainder == 1 || padding > 0 && remainder != 0 || size <= 0 || size > MAX_ENCODED_BYTES) throw invalid();
        byte[] encoded = null;
        ImageWorkset.Reservation temporary = null;
        PngImage owned = null;
        boolean transferred = false;
        // The original JSON String remains caller-owned. Decode through it without
        // making a complete substring or a second encoded ASCII array.
        try {
            temporary = workset.reserve(size);
            encoded = new byte[size];
            AsciiSource ascii = new AsciiSource(value, PNG_PREFIX.length());
            try (InputStream decoder = Base64.getDecoder().wrap(ascii)) {
                int offset = 0;
                while (offset < encoded.length) {
                    int count = decoder.read(encoded, offset, encoded.length - offset);
                    if (count < 0) throw invalid();
                    offset += count;
                }
                // Decoder streams stop at padding. Check the source as well so they
                // cannot silently accept trailing characters that decode(String) rejects.
                if (decoder.read() != -1 || ascii.position != value.length()) throw invalid();
            }
            owned = PngImage.fromBytes(encoded, workset);
            checkDimensionMetadata(data, "width", owned.width);
            checkDimensionMetadata(data, "height", owned.height);
            ImageAttachment attachment = new ImageAttachment(request, revision, owned);
            transferred = true;
            return attachment;
        } catch (IOException | RuntimeException badImage) {
            throw invalid();
        } finally {
            // Erase the temporary bytes before admitting another allocation against
            // their capacity; a try-with-resources token would close too early here.
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            if (!transferred && owned != null) owned.close();
            if (temporary != null) temporary.close();
        }
    }

    /** Transfers the same PNG owner once; the receiving request must close it even on failure. */
    synchronized PngImage consumePng(RunControl control, long epoch, String currentRevision) throws TaskControlException {
        try {
            control.checkExecutionEpoch(executionEpoch);
            if (epoch != executionEpoch || !taskId.equals(control.getTaskId()) || !profileId.equals(control.getProfileId())
                    || !revision.equals(currentRevision) || image == null || consumed) throw invalid();
            control.checkExecutionEpoch(executionEpoch);
            PngImage result = image;
            consumed = true; image = null;
            return result;
        } finally { close(); }
    }

    public synchronized boolean isClosed() { return image == null; }
    public synchronized boolean isConsumed() { return consumed; }

    @Override public synchronized void close() {
        if (image != null) { image.close(); image = null; }
    }

    JsonObject metadata() {
        JsonObject value = new JsonObject(); value.addProperty("mimeType", "image/png");
        value.addProperty("width", width); value.addProperty("height", height);
        value.addProperty("encodedBytes", encodedBytes); value.addProperty("revision", revision);
        value.addProperty("delivery", "single_use"); return value;
    }

    private static void checkDimensionMetadata(JsonObject data, String name, int actual) {
        if (!data.has(name)) return;
        JsonElement value = data.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Integer.toString(actual).equals(value.getAsString())) throw invalid();
    }
    private static boolean booleanTrue(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean(); }
    private static String string(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("图像附件类型、内容、大小或来源版本无效。"); }

    private static final class AsciiSource extends InputStream {
        private final String value;
        int position;
        AsciiSource(String value, int position) { this.value = value; this.position = position; }
        @Override public int read() throws IOException {
            if (position == value.length()) return -1;
            char character = value.charAt(position++);
            if (character > 127) throw new IOException("Invalid Base64 character.");
            return character;
        }
    }
}
