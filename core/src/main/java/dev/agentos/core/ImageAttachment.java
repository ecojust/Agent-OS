package dev.agentos.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
    private byte[] encoded;
    private boolean consumed;

    private ImageAttachment(ToolRequest request, String revision, byte[] encoded, int[] dimensions) {
        taskId = request.taskId; profileId = request.profileId; callId = request.callId;
        executionEpoch = request.executionEpoch; this.revision = revision;
        this.encoded = encoded; encodedBytes = encoded.length;
        width = dimensions[0]; height = dimensions[1];
    }

    static ImageAttachment fromLocalResult(ToolRequest request, JsonObject result, JsonElement source) {
        if (!"take_screenshot".equals(request.tool) || !booleanTrue(result.get("ok"))) throw invalid();
        JsonObject data = result.has("data") && result.get("data").isJsonObject() ? result.getAsJsonObject("data") : null;
        String revision = string(request.getArguments().get("expectedRevision"));
        if (data == null || revision == null || !revision.equals(string(data.get("revision")))) throw invalid();
        String value = string(source);
        // PNG is deliberately the only format until the platform can provide a
        // separately validated bounded decoder for other encodings.
        if (value == null || !value.startsWith(PNG_PREFIX)
                || value.length() - PNG_PREFIX.length() > ((MAX_ENCODED_BYTES + 2) / 3) * 4) throw invalid();
        byte[] encoded = null;
        try {
            encoded = Base64.getDecoder().decode(value.substring(PNG_PREFIX.length()));
            if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) throw invalid();
            int[] dimensions = PngValidator.validate(encoded);
            checkDimensionMetadata(data, "width", dimensions[0]);
            checkDimensionMetadata(data, "height", dimensions[1]);
            return new ImageAttachment(request, revision, encoded, dimensions);
        } catch (RuntimeException badImage) {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            throw invalid();
        }
    }

    /** Makes the one permitted transport copy; callers must release their request on completion. */
    synchronized String consumeDataUrl(RunControl control, long epoch, String currentRevision) throws TaskControlException {
        try {
            control.checkExecutionEpoch(executionEpoch);
            if (epoch != executionEpoch || !taskId.equals(control.getTaskId()) || !profileId.equals(control.getProfileId())
                    || !revision.equals(currentRevision) || encoded == null || consumed) throw invalid();
            consumed = true;
            String value = PNG_PREFIX + Base64.getEncoder().encodeToString(encoded);
            control.checkExecutionEpoch(executionEpoch);
            return value;
        } finally { close(); }
    }

    public synchronized boolean isClosed() { return encoded == null; }
    public synchronized boolean isConsumed() { return consumed; }

    @Override public synchronized void close() {
        if (encoded != null) { Arrays.fill(encoded, (byte) 0); encoded = null; }
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
}
