package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** In-memory at-most-dispatch ledger for one task. It is not a cross-app transaction. */
public final class ToolDispatcher implements AutoCloseable {
    private final ToolHandler handler;
    private final Object dispatchLock = new Object();
    private final Map<String, Entry> ledger = new LinkedHashMap<>();
    private String boundTask;
    private boolean closed;
    private volatile boolean unknownOutcome;

    public ToolDispatcher(ToolHandler handler) { this.handler = java.util.Objects.requireNonNull(handler); }

    public JsonObject dispatch(ToolRequest request, RunControl control) throws InterruptedException {
        control.checkExecutionEpoch(request.executionEpoch);
        control.consumeToolCall();
        if (request.protocolVersion != ToolCatalog.PROTOCOL_VERSION) return envelope(request, error("UNSUPPORTED_VERSION", "不支持的工具协议版本。"));
        if (!control.getTaskId().equals(request.taskId) || !control.getProfileId().equals(request.profileId)) return envelope(request, error("PERMISSION_DENIED", "请求不属于当前任务或用户资料。"));
        if (request.callId == null || !request.callId.matches("[A-Za-z0-9_.:-]{1,128}") || request.tool == null) return envelope(request, error("INVALID_ARGUMENT", "调用标识或工具无效。"));
        JsonObject arguments = request.getArguments();
        String fingerprint = request.executionEpoch + "|" + request.profileId + "|" + request.tool + "|" + canonical(arguments);
        Entry entry;
        synchronized (ledger) {
            if (closed) return envelope(request, error("TASK_STOPPED", "执行通道已关闭。"));
            if (boundTask == null) boundTask = request.taskId;
            if (!boundTask.equals(request.taskId)) return envelope(request, error("PERMISSION_DENIED", "执行通道不能跨任务复用。"));
            Entry previous = ledger.get(request.callId);
            if (previous != null) {
                if (!previous.fingerprint.equals(fingerprint)) return envelope(request, error("INVALID_ARGUMENT", "同一调用标识不能更换动作或参数。"));
                return previous.result == null ? envelope(request, error("IN_PROGRESS", "该调用正在处理，不会重复提交。")) : previous.result.deepCopy();
            }
            if (ledger.size() >= 1000) return envelope(request, error("BUDGET_EXCEEDED", "调用记录已达到上限。"));
            entry = new Entry(fingerprint); ledger.put(request.callId, entry);
        }
        JsonObject result;
        long dispatchAttempts = control.getDispatchAttempts();
        boolean enteredHandler = false;
        try {
            synchronized (dispatchLock) {
                control.checkExecutionEpoch(request.executionEpoch);
                if (!handler.availableTools().contains(request.tool)) result = error("CAPABILITY_UNAVAILABLE", "当前设备未提供此能力。");
                else {
                    try { ToolCatalog.validate(request.tool, arguments); }
                    catch (IllegalArgumentException invalid) { return save(entry, envelope(request, error("INVALID_ARGUMENT", invalid.getMessage()))); }
                    control.checkExecutionEpoch(request.executionEpoch);
                    enteredHandler = true;
                    result = handler.execute(request.tool, arguments.deepCopy(), control);
                    if (result == null || !result.has("ok") || !result.get("ok").isJsonPrimitive() || !result.get("ok").getAsJsonPrimitive().isBoolean())
                        result = error(ToolCatalog.isMutation(request.tool) ? "OUTCOME_UNKNOWN" : "INVALID_RESULT", "执行器没有返回可核对的结果。");
                    else result = result.deepCopy();
                    control.checkExecutionEpoch(request.executionEpoch);
                }
            }
        } catch (TaskControlException interrupted) {
            boolean uncertainWrite = ToolCatalog.isMutation(request.tool) && control.getDispatchAttempts() != dispatchAttempts;
            save(entry, envelope(request, error(uncertainWrite ? "OUTCOME_UNKNOWN" : interrupted.getCode(),
                    uncertainWrite ? "操作可能已提交，不能自动重发；请核对当前界面。" : interrupted.getMessage())));
            throw interrupted;
        } catch (InterruptedException interrupted) {
            save(entry, envelope(request, error(enteredHandler && ToolCatalog.isMutation(request.tool) ? "OUTCOME_UNKNOWN" : "TASK_STOPPED", "操作已中断；已提交的动作不能保证撤回。")));
            throw interrupted;
        } catch (Exception failed) {
            result = error(enteredHandler && ToolCatalog.isMutation(request.tool) ? "OUTCOME_UNKNOWN" : "DEVICE_ERROR", "无法确定设备操作结果；请核对当前界面，不能自动重复执行。");
        }
        if (ToolCatalog.isMutation(request.tool) && !result.has("dispatchAccepted") && result.get("ok").getAsBoolean()) result.add("dispatchAccepted", com.google.gson.JsonNull.INSTANCE);
        if (!result.has("dispatchSubmitted")) result.addProperty("dispatchSubmitted", control.getDispatchAttempts() != dispatchAttempts);
        if (!result.has("verification")) result.addProperty("verification", "unverified");
        return save(entry, envelope(request, result));
    }

    private JsonObject save(Entry entry, JsonObject result) {
        if (result.has("code") && "OUTCOME_UNKNOWN".equals(result.get("code").getAsString())) unknownOutcome = true;
        synchronized (ledger) { entry.result = result.deepCopy(); } return result;
    }
    public boolean hasUnknownOutcome() { return unknownOutcome; }
    public static JsonObject error(String code, String reason) {
        JsonObject result = new JsonObject(); result.addProperty("ok", false); result.addProperty("code", code); result.addProperty("error", reason);
        if (code.equals("OUTCOME_UNKNOWN")) {
            result.add("dispatchAccepted", com.google.gson.JsonNull.INSTANCE); result.add("dispatchSubmitted", com.google.gson.JsonNull.INSTANCE);
        } else { result.addProperty("dispatchAccepted", false); result.addProperty("dispatchSubmitted", false); }
        result.addProperty("verification", code.equals("OUTCOME_UNKNOWN") ? "unknown" : "unverified"); return result;
    }
    private static JsonObject envelope(ToolRequest request, JsonObject result) {
        result.addProperty("protocolVersion", ToolCatalog.PROTOCOL_VERSION); result.addProperty("taskId", request.taskId); result.addProperty("callId", request.callId);
        result.addProperty("executionEpoch", request.executionEpoch); result.addProperty("profileId", request.profileId); return result;
    }
    static String canonical(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : new TreeMap<>(value.getAsJsonObject().asMap()).entrySet()) sorted.add(entry.getKey(), com.google.gson.JsonParser.parseString(canonical(entry.getValue())));
            return sorted.toString();
        }
        if (value.isJsonArray()) { JsonArray array = new JsonArray(); for (JsonElement child : value.getAsJsonArray()) array.add(com.google.gson.JsonParser.parseString(canonical(child))); return array.toString(); }
        if (value.getAsJsonPrimitive().isNumber()) try { return new BigDecimal(value.getAsString()).stripTrailingZeros().toPlainString(); } catch (NumberFormatException ignored) { }
        return value.toString();
    }
    @Override public void close() { synchronized (ledger) { closed = true; ledger.clear(); } }
    private static final class Entry { final String fingerprint; JsonObject result; Entry(String fingerprint) { this.fingerprint = fingerprint; } }
}
