package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;

/** A synthetic model protocol test. It has no device executor or observation input. */
public final class ModelProbe {
    static final String TOOL_NAME = "agent_os_probe";
    private final HttpModelClient client;

    public ModelProbe() { this(new HttpModelClient()); }
    ModelProbe(HttpModelClient client) { this.client = java.util.Objects.requireNonNull(client); }

    /** Run on a worker thread. Cancellation remains distinguishable from a failed probe. */
    public Result test(ModelSettings settings, RunControl control) throws InterruptedException {
        if (settings == null || control == null) throw new IllegalArgumentException("测试配置和执行控制不能为空。");
        long epoch = control.getExecutionEpoch();
        control.checkExecutionEpoch(epoch);
        try {
            settings.validate();
            String nonce = UUID.randomUUID().toString();
            JsonArray messages = new JsonArray();
            messages.add(message("system", "这是一次合成协议测试，不包含真实用户任务、应用内容或设备操作。请只调用提供的无副作用测试工具，严格使用给定参数。"));
            messages.add(message("user", "调用 " + TOOL_NAME + " 一次，nonce 参数为：" + nonce + "。不要请求其他操作。"));
            control.consumeModelRound();
            JsonObject response = client.request(settings, messages, definitions(nonce), control, epoch);
            control.checkExecutionEpoch(epoch);
            if (verified(response, nonce)) {
                return new Result(true, true, "连接与工具调用协议测试通过。此次仅验证合成请求，不代表图像能力或实际手机任务已通过。", "TOOL_CALLING_VERIFIED");
            }
            return new Result(true, false, "服务已响应，但未通过工具调用校验。请检查模型是否支持工具调用及接口配置；尚不能据此执行手机任务。", "TOOL_CALLING_UNVERIFIED");
        } catch (ModelException failed) {
            control.checkExecutionEpoch(epoch);
            return new Result(false, false, failed.getMessage(), failed.code);
        } catch (IllegalArgumentException invalid) {
            control.checkExecutionEpoch(epoch);
            // Do not echo configuration values or parser diagnostics.
            return new Result(false, false, "模型配置无效，请检查服务地址、模型名称、密钥格式与 token 上限。", "INVALID_CONFIGURATION");
        }
    }

    private static JsonArray definitions(String nonce) {
        JsonObject value = new JsonObject(); value.addProperty("type", "string");
        JsonArray values = new JsonArray(); values.add(nonce); value.add("enum", values);
        JsonObject properties = new JsonObject(); properties.add("nonce", value);
        JsonObject parameters = new JsonObject(); parameters.addProperty("type", "object");
        parameters.add("properties", properties); parameters.addProperty("additionalProperties", false);
        JsonArray required = new JsonArray(); required.add("nonce"); parameters.add("required", required);
        JsonObject function = new JsonObject(); function.addProperty("name", TOOL_NAME);
        function.addProperty("description", "验证工具调用协议的无副作用工具，不执行任何设备操作。");
        function.add("parameters", parameters);
        JsonObject tool = new JsonObject(); tool.addProperty("type", "function"); tool.add("function", function);
        JsonArray tools = new JsonArray(); tools.add(tool); return tools;
    }

    private static boolean verified(JsonObject response, String nonce) {
        try {
            JsonArray choices = response.getAsJsonArray("choices");
            if (choices == null || choices.size() != 1) return false;
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !"assistant".equals(string(message.get("role")))) return false;
            JsonArray calls = message.getAsJsonArray("tool_calls");
            if (calls == null || calls.size() != 1) return false;
            JsonObject call = calls.get(0).getAsJsonObject();
            String id = string(call.get("id"));
            if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,128}") || !"function".equals(string(call.get("type")))) return false;
            JsonObject function = call.getAsJsonObject("function");
            if (function == null || !TOOL_NAME.equals(string(function.get("name")))) return false;
            String arguments = string(function.get("arguments"));
            if (arguments == null || arguments.length() > 4096) return false;
            JsonObject decoded = StrictJson.object(arguments);
            return decoded.size() == 1 && nonce.equals(string(decoded.get("nonce")));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject(); message.addProperty("role", role); message.addProperty("content", content); return message;
    }

    public static final class Result {
        /** A successful HTTP response containing a complete JSON object was received. */
        public final boolean connected;
        /** Only the single synthetic tool name, call shape and nonce were verified. */
        public final boolean toolCallingVerified;
        public final String message, code;
        Result(boolean connected, boolean toolCallingVerified, String message, String code) {
            this.connected = connected; this.toolCallingVerified = toolCallingVerified; this.message = message; this.code = code;
        }
    }
}
