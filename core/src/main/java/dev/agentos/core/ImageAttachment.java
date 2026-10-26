package dev.agentos.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

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
            int[] dimensions = validatePng(encoded);
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

    private static int[] validatePng(byte[] bytes) {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < 57) throw invalid();
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) throw invalid();
        int offset = 8, width = 0, height = 0, channels = 0, metadataSeen = 0;
        boolean header = false, image = false, ended = false;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) throw invalid();
            long lengthValue = unsignedInt(bytes, offset);
            if (lengthValue > bytes.length - offset - 12L) throw invalid();
            int length = (int) lengthValue;
            int type = intValue(bytes, offset + 4);
            CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
            if (crc.getValue() != unsignedInt(bytes, offset + 8 + length)) throw invalid();
            if (!header && type != 0x49484452) throw invalid();
            if (type == 0x49484452) {
                if (header || length != 13) throw invalid();
                width = intValue(bytes, offset + 8); height = intValue(bytes, offset + 12);
                int color = bytes[offset + 17] & 255;
                if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE
                        || (long) width * height > MAX_PIXELS || bytes[offset + 16] != 8
                        || (color != 2 && color != 6) || bytes[offset + 18] != 0
                        || bytes[offset + 19] != 0 || bytes[offset + 20] != 0) throw invalid();
                channels = color == 6 ? 4 : 3; header = true;
            } else if (type == 0x49444154) {
                compressed.write(bytes, offset + 8, length); image = true;
            } else if (type == 0x49454e44) {
                if (!image || length != 0 || offset + 12 != bytes.length) throw invalid();
                ended = true;
            } else {
                // Permit only fixed-size rendering metadata before IDAT. In particular,
                // APNG frames and compressed profiles/text cannot hide more decoded data.
                if (image) throw invalid();
                int metadataBit;
                if (type == 0x73524742 && length == 1 && (bytes[offset + 8] & 255) <= 3) metadataBit = 1;
                else if (type == 0x67414d41 && length == 4 && unsignedInt(bytes, offset + 8) != 0) metadataBit = 2;
                else if (type == 0x6348524d && length == 32) metadataBit = 4;
                else if (type == 0x70485973 && length == 9 && (bytes[offset + 16] & 255) <= 1) metadataBit = 8;
                else if (type == 0x73424954 && length == channels) {
                    for (int i = 0; i < channels; i++) if ((bytes[offset + 8 + i] & 255) < 1 || (bytes[offset + 8 + i] & 255) > 8) throw invalid();
                    metadataBit = 16;
                } else throw invalid();
                if ((metadataSeen & metadataBit) != 0) throw invalid();
                metadataSeen |= metadataBit;
            }
            offset += length + 12;
        }
        if (!ended || (long) width * height * channels > MAX_DECODED_BYTES) throw invalid();
        byte[] deflated = compressed.toByteArray();
        try { validateScanlines(deflated, width, height, channels); }
        finally { Arrays.fill(deflated, (byte) 0); }
        return new int[]{width, height};
    }

    private static void validateScanlines(byte[] compressed, int width, int height, int channels) {
        long rowBytes = (long) width * channels + 1;
        long expected = rowBytes * height, total = 0;
        Inflater inflater = new Inflater(); byte[] chunk = new byte[8192];
        try {
            inflater.setInput(compressed);
            while (!inflater.finished()) {
                int length = inflater.inflate(chunk);
                if (length == 0 && inflater.finished()) break;
                if (length == 0 || total + length > expected) throw invalid();
                for (int i = 0; i < length; i++) if ((total + i) % rowBytes == 0 && (chunk[i] & 255) > 4) throw invalid();
                total += length;
            }
            if (total != expected || inflater.getRemaining() != 0) throw invalid();
        } catch (DataFormatException invalid) { throw invalid(); }
        finally { Arrays.fill(chunk, (byte) 0); inflater.end(); }
    }

    private static void checkDimensionMetadata(JsonObject data, String name, int actual) {
        if (!data.has(name)) return;
        JsonElement value = data.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Integer.toString(actual).equals(value.getAsString())) throw invalid();
    }
    private static int intValue(byte[] value, int offset) {
        return (value[offset] & 255) << 24 | (value[offset + 1] & 255) << 16 | (value[offset + 2] & 255) << 8 | value[offset + 3] & 255;
    }
    private static long unsignedInt(byte[] value, int offset) { return intValue(value, offset) & 0xffffffffL; }
    private static boolean booleanTrue(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean(); }
    private static String string(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("图像附件类型、内容、大小或来源版本无效。"); }
}
