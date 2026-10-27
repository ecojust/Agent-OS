package dev.agentos.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentos.core.ImageReviewSession;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit bounded loopback script. The real probe path still reads its actual challenge PNG. */
final class NativeWindowTaskScript implements LocalVisionProbeServer.WindowTaskRoute {
    static final String PACKAGE = "org.fossify.math", CAPTURE_CALL = "native-window-capture-1";
    static final String FINAL_REPORT = "Native window disclosure test completed.";
    final AtomicInteger deviceImages = new AtomicInteger(), imageFreeFollowingRequests = new AtomicInteger();
    final AtomicReference<Reviewed> reviewed = new AtomicReference<>();
    private final boolean decline;
    private final File receivedFile;
    volatile String originalRevision;
    volatile int originalWindowId;
    volatile String receivedHash;
    volatile int receivedBytes;
    volatile JsonObject planningGeometry, captureFailure;

    NativeWindowTaskScript(boolean decline, File receivedFile) { this.decline = decline; this.receivedFile = receivedFile; }

    static final class Reviewed {
        final String sha256, revision, captureId;
        final int width, height, bytes, windowId;
        final int[] samples;
        final CompletableFuture<Void> removed;
        Reviewed(ImageReviewSession session, int[] actualSamples, CompletableFuture<Void> removed) {
            sha256 = session.imageSha256; width = session.width; height = session.height; bytes = session.encodedBytes;
            revision = session.binding.observation.revision; windowId = session.binding.observation.windowId;
            captureId = session.binding.captureId; samples = actualSamples.clone(); this.removed = removed;
        }
    }

    @Override public JsonObject respond(JsonObject request, int round) throws IOException {
        require(request.toString().contains(LocalVisionProbeServer.PRIVATE_TASK), "Explicit task marker missing");
        boolean screenshot = false, observe = false;
        for (JsonElement item : request.getAsJsonArray("tools")) {
            String tool = item.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
            require(!tool.equals("tap") && !tool.equals("swipe") && !tool.startsWith("agent_os_"), "Unsupported tool leaked");
            screenshot |= tool.equals("take_screenshot"); observe |= tool.equals("observe");
        }
        require(screenshot && observe, "Actual task did not advertise the qualified screenshot route");
        String image = imageUrl(request);
        if (round == 1) {
            require(image == null, "Device image arrived before review");
            JsonObject observed = latestObservation(request);
            require(PACKAGE.equals(text(observed, "packageName")) && observed.get("scoped").getAsBoolean(), "Wrong actual target observation");
            originalRevision = text(observed, "revision"); originalWindowId = observed.get("windowId").getAsInt();
            planningGeometry = NativeWindowGeometryDiagnostic.fromObservation(observed);
            require(!originalRevision.isEmpty(), "Actual revision missing");
            JsonObject args = new JsonObject(); args.addProperty("expectedRevision", originalRevision);
            return tool("take_screenshot", CAPTURE_CALL, args);
        }
        if (round == 2) {
            JsonObject metadata = captureMetadata(request);
            if (!metadata.has("ok") || !metadata.get("ok").getAsBoolean()) {
                String code = text(metadata, "code"), message = text(metadata, "error");
                JsonObject failure = new JsonObject();
                failure.addProperty("code", code.substring(0, Math.min(80, code.length())));
                failure.addProperty("error", message.substring(0, Math.min(180, message.length())));
                failure.addProperty("receivedAtElapsedRealtimeMillis", android.os.SystemClock.elapsedRealtime());
                failure.addProperty("imageUrlPresent", image != null);
                captureFailure = failure;
                throw new IOException("Actual capture result failed: " + failure.get("code").getAsString()
                        + "; " + failure.get("error").getAsString());
            }
            require(!decline, "A declined image task must stop without another model request");
            Reviewed expected = reviewed.get();
            require(expected != null && expected.removed.isDone() && !expected.removed.isCompletedExceptionally(), "HTTP image preceded actual host removal");
            require(expected.revision.equals(originalRevision) && expected.windowId == originalWindowId, "Reviewed target identity changed");
            JsonObject data = metadata.getAsJsonObject("data");
            require("platform_window".equals(text(data, "provenance")), "Synthetic receipt cannot prove a native capture");
            require(expected.sha256.equals(text(data, "sha256")) && expected.captureId.equals(text(data, "captureId"))
                    && expected.revision.equals(text(data, "revision")) && expected.windowId == data.get("windowId").getAsInt(), "Tool metadata differs from the actual reviewed session");
            receive(image, expected);
            require(deviceImages.incrementAndGet() == 1, "Device image repeated");
            return tool("observe", "native-window-following-observe", new JsonObject());
        }
        require(!decline, "A declined image task must stop without another model request");
        require(round == 3 && deviceImages.get() == 1 && image == null, "Image persisted or unexpected task round");
        require(!request.toString().contains("data:image/") && !request.toString().contains("imageDataUrl"), "Encoded image leaked into history");
        imageFreeFollowingRequests.incrementAndGet();
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", FINAL_REPORT);
        return envelope(message);
    }

    private void receive(String url, Reviewed expected) throws IOException {
        String prefix = "data:image/png;base64,";
        require(url != null && url.startsWith(prefix) && url.length() <= prefix.length() + 1_398_104, "Bounded PNG missing");
        byte[] encoded = Base64.decode(url.substring(prefix.length()), Base64.NO_WRAP);
        Bitmap decoded = null;
        try {
            require(encoded.length > 0 && encoded.length <= 1024 * 1024 && encoded.length == expected.bytes, "PNG size differs from review");
            String hash = sha256(encoded); require(expected.sha256.equals(hash), "HTTP PNG is not the actual reviewed PNG");
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            require(decoded != null && decoded.getWidth() == expected.width && decoded.getHeight() == expected.height, "Independent PNG decode dimensions differ");
            require(Math.max(expected.width, expected.height) <= 1280 && (long) expected.width * expected.height <= 1_048_576, "PNG limits were exceeded");
            require(Arrays.equals(expected.samples, samples(decoded)), "HTTP pixels differ from the actual preview drawable");
            // Only the actually approved and received synthetic Calculator payload
            // is retained as evidence. This is not a screenshot of the secure UI.
            try (FileOutputStream output = new FileOutputStream(receivedFile)) { output.write(encoded); }
            receivedHash = hash; receivedBytes = encoded.length;
        } finally { Arrays.fill(encoded, (byte) 0); if (decoded != null) decoded.recycle(); }
    }

    static int[] samples(Bitmap bitmap) {
        int[] values = new int[16]; int index = 0;
        for (int y = 1; y <= 4; y++) for (int x = 1; x <= 4; x++)
            values[index++] = bitmap.getPixel(Math.min(bitmap.getWidth() - 1, bitmap.getWidth() * x / 5),
                    Math.min(bitmap.getHeight() - 1, bitmap.getHeight() * y / 5));
        return values;
    }
    private static String imageUrl(JsonObject request) throws IOException {
        String found = null;
        for (JsonElement entry : request.getAsJsonArray("messages")) {
            JsonElement content = entry.getAsJsonObject().get("content");
            if (content == null || !content.isJsonArray()) continue;
            for (JsonElement part : content.getAsJsonArray()) {
                JsonObject value = part.getAsJsonObject();
                if (!"image_url".equals(text(value, "type"))) continue;
                require(found == null, "Multiple device images in one request");
                found = value.getAsJsonObject("image_url").get("url").getAsString();
            }
        }
        return found;
    }
    private static JsonObject latestObservation(JsonObject request) throws IOException {
        JsonArray messages = request.getAsJsonArray("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonObject message = messages.get(i).getAsJsonObject(); JsonElement content = message.get("content");
            if (!"user".equals(text(message, "role")) || content == null || !content.isJsonPrimitive()) continue;
            String text = content.getAsString(); if (!text.startsWith("最新本机观察")) continue;
            int start = text.indexOf('{'); if (start >= 0) return JsonParser.parseString(text.substring(start)).getAsJsonObject().getAsJsonObject("data");
        }
        throw new IOException("The actual Runner observation is missing");
    }
    private static JsonObject captureMetadata(JsonObject request) throws IOException {
        JsonObject found = null;
        for (JsonElement item : request.getAsJsonArray("messages")) {
            JsonObject message = item.getAsJsonObject();
            if ("tool".equals(text(message, "role")) && CAPTURE_CALL.equals(text(message, "tool_call_id"))) {
                require(found == null, "Duplicate capture result in history");
                found = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
            }
        }
        require(found != null, "Typed capture result metadata missing"); return found;
    }
    private static JsonObject tool(String name, String id, JsonObject args) {
        JsonObject function = new JsonObject(); function.addProperty("name", name); function.addProperty("arguments", args.toString());
        JsonObject call = new JsonObject(); call.addProperty("id", id); call.addProperty("type", "function"); call.add("function", function);
        JsonArray calls = new JsonArray(); calls.add(call); JsonObject message = new JsonObject();
        message.addProperty("role", "assistant"); message.add("tool_calls", calls); return envelope(message);
    }
    private static JsonObject envelope(JsonObject message) {
        JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject(); response.add("choices", choices); return response;
    }
    private static String text(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    private static String sha256(byte[] bytes) throws IOException {
        try { StringBuilder hash = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return hash.toString(); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 unavailable"); }
    }
    private static void require(boolean condition, String reason) throws IOException { if (!condition) throw new IOException(reason); }
}
