package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * One synthetic image challenge, with no device executor, observation or user task input.
 * A successful result describes this challenge only, not general visual competence or
 * permission to capture/disclose device images. Run the probe on a worker thread.
 */
public final class VisionModelProbe {
    private static final String TOOL_NAME = "agent_os_vision_probe";
    private static final String[] COLOR_NAMES = {"red", "green", "blue", "yellow", "cyan", "magenta", "black", "white"};
    private static final int[] RGB = {0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0x00ffff, 0xff00ff, 0x000000, 0xffffff};
    private static final int WIDTH = 512, HEIGHT = 256, CELL = 128, MARGIN = 8;
    private static final int MAX_PNG_BYTES = 64 * 1024;
    private final HttpModelClient client;

    public VisionModelProbe() { this(new HttpModelClient()); }
    VisionModelProbe(HttpModelClient client) { this.client = java.util.Objects.requireNonNull(client); }

    /** Cancellation or an invalidated epoch is propagated, never presented as an ordinary failure. */
    public Result test(ModelSettings settings, RunControl control) throws InterruptedException {
        if (settings == null || control == null) throw new IllegalArgumentException("测试配置和执行控制不能为空。");
        return test(settings, control, control.getExecutionEpoch(), ModelRequestContext.syntheticTestGuard());
    }

    /**
     * Runs the same synthetic challenge bound to its original execution and a
     * caller-owned disclosure guard. The guard must be a pure, bounded memory
     * check; it is rechecked during generation and at the HTTP transport's
     * existing checkpoints. It is not screenshot permission or page approval.
     * No guard can retract bytes already sent or make a check atomic with I/O.
     */
    public Result test(ModelSettings settings, RunControl control, long originalEpoch,
                       DisclosureGuard guard) throws InterruptedException {
        if (settings == null || control == null || guard == null)
            throw new IllegalArgumentException("测试配置、执行控制和发送守卫不能为空。");
        ModelRequestContext context = new ModelRequestContext(control, originalEpoch, guard);
        context.check();
        JsonArray messages = null;
        Challenge challenge = null;
        try {
            settings.validate();
            String nonce = UUID.randomUUID().toString();
            challenge = challenge(context);
            context.check();
            messages = messages(nonce, challenge.png);
            context.check();
            control.consumeModelRound();
            JsonObject response = client.request(settings, messages, definitions(nonce), context);
            context.check();
            boolean verified = verified(response, nonce, challenge.order);
            context.check();
            if (verified) return new Result(true, true,
                    "本次合成图片理解测试通过：模型正确返回了随机色块顺序。结果不代表真实界面理解、手机任务或截图权限已验证。",
                    "SYNTHETIC_IMAGE_VERIFIED");
            return new Result(true, false,
                    "服务已响应，但本次合成图片理解未通过校验。请检查模型是否支持图片输入与工具调用；不会据此开放截图或设备操作。",
                    "SYNTHETIC_IMAGE_UNVERIFIED");
        } catch (ModelException failed) {
            context.check();
            return new Result(false, false, failed.getMessage(), failed.code);
        } catch (IllegalArgumentException invalid) {
            context.check();
            return new Result(false, false, "模型配置无效，请检查服务地址、模型名称、密钥格式与 token 上限。", "INVALID_CONFIGURATION");
        } finally {
            if (messages != null) messages.asList().clear();
            if (challenge != null) challenge.close();
        }
    }

    private static JsonArray messages(String nonce, byte[] png) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject(); system.addProperty("role", "system");
        system.addProperty("content", "This is a synthetic image understanding check with no real user task or device data. "
                + "Only call the provided no-effect test function once. Do not request any other operation.");
        messages.add(system);
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject(); text.addProperty("type", "text");
        text.addProperty("text", "Read the attached grid of 2 rows and 4 columns. Ignore the gray margins. "
                + "Read the top row from left to right, then the bottom row from left to right. "
                + "Return exactly 8 color names using only red, green, blue, yellow, cyan, magenta, black, white. "
                + "Each allowed color appears once; inspect the image rather than assuming the order of this list. "
                + "Call " + TOOL_NAME + " once with nonce \"" + nonce + "\" and colors in that image order.");
        content.add(text);
        JsonObject url = new JsonObject(); url.addProperty("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        JsonObject image = new JsonObject(); image.addProperty("type", "image_url"); image.add("image_url", url); content.add(image);
        JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.add("content", content); messages.add(user);
        return messages;
    }

    private static JsonArray definitions(String nonce) {
        JsonObject nonceField = new JsonObject(); nonceField.addProperty("type", "string");
        JsonArray nonces = new JsonArray(); nonces.add(nonce); nonceField.add("enum", nonces);
        JsonObject item = new JsonObject(); item.addProperty("type", "string");
        JsonArray allowed = new JsonArray(); for (String name : COLOR_NAMES) allowed.add(name); item.add("enum", allowed);
        JsonObject colors = new JsonObject(); colors.addProperty("type", "array"); colors.add("items", item);
        colors.addProperty("minItems", 8); colors.addProperty("maxItems", 8); colors.addProperty("uniqueItems", true);
        JsonObject properties = new JsonObject(); properties.add("nonce", nonceField); properties.add("colors", colors);
        JsonObject parameters = new JsonObject(); parameters.addProperty("type", "object"); parameters.add("properties", properties);
        parameters.addProperty("additionalProperties", false);
        JsonArray required = new JsonArray(); required.add("nonce"); required.add("colors"); parameters.add("required", required);
        JsonObject function = new JsonObject(); function.addProperty("name", TOOL_NAME);
        function.addProperty("description", "Reports one synthetic image challenge; has no side effects and performs no device action.");
        function.add("parameters", parameters);
        JsonObject tool = new JsonObject(); tool.addProperty("type", "function"); tool.add("function", function);
        JsonArray tools = new JsonArray(); tools.add(tool); return tools;
    }

    private static boolean verified(JsonObject response, String nonce, int[] order) {
        try {
            JsonArray choices = response.getAsJsonArray("choices");
            if (choices == null || choices.size() != 1) return false;
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !"assistant".equals(string(message.get("role")))) return false;
            JsonArray calls = message.getAsJsonArray("tool_calls");
            if (calls == null || calls.size() != 1) return false;
            JsonObject call = calls.get(0).getAsJsonObject(); String id = string(call.get("id"));
            if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,128}") || !"function".equals(string(call.get("type")))) return false;
            JsonObject function = call.getAsJsonObject("function");
            if (function == null || !TOOL_NAME.equals(string(function.get("name")))) return false;
            String arguments = string(function.get("arguments"));
            return arguments != null && arguments.length() <= 4096 && verifiedArguments(arguments, nonce, order);
        } catch (RuntimeException malformed) { return false; }
    }

    /** Reject duplicate keys as well as unknown keys and non-string/coerced values. */
    private static boolean verifiedArguments(String arguments, String nonce, int[] order) {
        try (JsonReader reader = new JsonReader(new StringReader(arguments))) {
            reader.setStrictness(Strictness.STRICT); reader.beginObject();
            boolean nonceSeen = false, colorsSeen = false;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("nonce")) {
                    if (nonceSeen || reader.peek() != JsonToken.STRING || !nonce.equals(reader.nextString())) return false;
                    nonceSeen = true;
                } else if (name.equals("colors")) {
                    if (colorsSeen || reader.peek() != JsonToken.BEGIN_ARRAY) return false;
                    colorsSeen = true; reader.beginArray(); int index = 0;
                    while (reader.hasNext()) {
                        if (index >= 8 || reader.peek() != JsonToken.STRING || !COLOR_NAMES[order[index]].equals(reader.nextString())) return false;
                        index++;
                    }
                    reader.endArray(); if (index != 8) return false;
                } else return false;
            }
            reader.endObject();
            return nonceSeen && colorsSeen && reader.peek() == JsonToken.END_DOCUMENT;
        } catch (Exception malformed) { return false; }
    }

    private static Challenge challenge(ModelRequestContext context) throws TaskControlException, ModelException {
        context.check();
        int[] order = {0, 1, 2, 3, 4, 5, 6, 7};
        SecureRandom random = new SecureRandom();
        for (int index = order.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1), swap = order[index]; order[index] = order[other]; order[other] = swap;
        }
        byte[] raw = new byte[(WIDTH * 3 + 1) * HEIGHT];
        byte[] scratch = new byte[8192], compressed = null;
        Deflater deflater = new Deflater();
        try {
            int offset = 0;
            for (int y = 0; y < HEIGHT; y++) {
                context.check(); raw[offset++] = 0;
                for (int x = 0; x < WIDTH; x++) {
                    int localX = x % CELL, localY = y % CELL;
                    int color = localX < MARGIN || localX >= CELL - MARGIN || localY < MARGIN || localY >= CELL - MARGIN
                            ? 0x808080 : RGB[order[(y / CELL) * 4 + x / CELL]];
                    raw[offset++] = (byte) (color >> 16); raw[offset++] = (byte) (color >> 8); raw[offset++] = (byte) color;
                }
            }
            deflater.setInput(raw); deflater.finish();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            while (!deflater.finished()) {
                context.check();
                int count = deflater.deflate(scratch);
                if (count == 0 || encoded.size() + count > MAX_PNG_BYTES - 57) throw imageError();
                encoded.write(scratch, 0, count);
            }
            compressed = encoded.toByteArray();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10}; png.write(signature, 0, signature.length);
            ByteArrayOutputStream header = new ByteArrayOutputStream(); writeInt(header, WIDTH); writeInt(header, HEIGHT);
            header.write(8); header.write(2); header.write(0); header.write(0); header.write(0);
            writeChunk(png, "IHDR", header.toByteArray()); writeChunk(png, "IDAT", compressed); writeChunk(png, "IEND", new byte[0]);
            if (png.size() > MAX_PNG_BYTES) throw imageError();
            context.check();
            return new Challenge(order, png.toByteArray());
        } finally {
            deflater.end(); Arrays.fill(raw, (byte) 0); Arrays.fill(scratch, (byte) 0);
            if (compressed != null) Arrays.fill(compressed, (byte) 0);
        }
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24); output.write(value >>> 16); output.write(value >>> 8); output.write(value);
    }
    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] contents) {
        writeInt(output, contents.length); CRC32 crc = new CRC32();
        for (int index = 0; index < 4; index++) { int value = type.charAt(index); output.write(value); crc.update(value); }
        output.write(contents, 0, contents.length); crc.update(contents); writeInt(output, (int) crc.getValue());
    }
    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static ModelException imageError() {
        return new ModelException("SYNTHETIC_IMAGE_ERROR", "无法在当前预算内生成合成测试图片，未发送图片请求。");
    }

    private static final class Challenge implements AutoCloseable {
        private final int[] order;
        private final byte[] png;
        Challenge(int[] order, byte[] png) { this.order = order; this.png = png; }
        @Override public void close() { Arrays.fill(order, 0); Arrays.fill(png, (byte) 0); }
    }

    public static final class Result {
        /** A successful HTTP response containing a complete JSON object was received. */
        public final boolean connected;
        /** Only this random grid and its one no-effect tool response were verified. */
        public final boolean syntheticImageVerified;
        public final String message, code;
        Result(boolean connected, boolean syntheticImageVerified, String message, String code) {
            this.connected = connected; this.syntheticImageVerified = syntheticImageVerified;
            this.message = message; this.code = code;
        }
    }
}
