package dev.agentos.core;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Fixed, immutable task metadata for a user-requested preview; never accepts task or model content. */
public final class TaskDiagnostics {
    public enum Phase { RUNNING, WAITING_APPROVAL, PAUSED, STOPPING, FINISHED }
    public static final int MAX_REPORT_CHARS = 2048;
    private static final Set<String> KNOWN_CODES = Set.of(
            "MODEL_REPORT", "VERIFIED", "TASK_STOPPED", "TASK_PAUSED", "BUDGET_EXCEEDED",
            "CONFIGURATION_INVALID", "CONFIGURATION_CHANGED", "INTERNAL_ERROR", "INVALID_ARGUMENT",
            "INVALID_REQUEST", "INVALID_RESPONSE", "INVALID_RESULT", "INVALID_TASK", "UNSUPPORTED_VERSION",
            "CAPABILITY_UNAVAILABLE", "CONTEXT_LIMIT", "MODEL_AUTH", "MODEL_HTTP_ERROR", "MODEL_NOT_FOUND",
            "NETWORK_BUSY", "NETWORK_ERROR", "OBSERVATION_FAILED", "OBSERVATION_LIMIT", "OUTCOME_UNKNOWN",
            "PERMISSION_DENIED", "RATE_LIMIT", "REDIRECT_REJECTED", "REQUEST_CANCELLED", "REQUEST_CHANGED",
            "REQUEST_MEMORY_LIMIT", "RESPONSE_LIMIT", "STALE_OBSERVATION", "SYNTHETIC_IMAGE_ERROR", "TIMEOUT",
            "APPROVAL_EXPIRED", "APPROVAL_REJECTED", "USER_DENIED", "DISPATCH_IN_PROGRESS", "DISPATCH_SETTLED",
            "OTHER", "NOT_RECORDED");

    /** Local binding only; the random task identifier is deliberately omitted from the copied text. */
    public final String taskId;
    public final Phase phase;
    public final TaskResult.Kind resultKind;
    public final String resultCode;
    public final TaskBudget budget;
    public final RunControl.BudgetSnapshot consumed;
    public final String text;

    public TaskDiagnostics(String taskId, Phase phase, TaskResult.Kind resultKind, String resultCode,
                           TaskBudget budget, RunControl.BudgetSnapshot consumed) {
        Objects.requireNonNull(taskId, "taskId");
        boolean validIdentity;
        try { validIdentity = UUID.fromString(taskId).toString().equals(taskId); }
        catch (IllegalArgumentException malformed) { validIdentity = false; }
        if (!validIdentity) throw new IllegalArgumentException("Diagnostics require an original random task identity");
        this.taskId = taskId;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.resultKind = phase == Phase.FINISHED ? resultKind : null;
        this.resultCode = phase == Phase.FINISHED ? safeTerminalCode(resultCode) : "NONE";
        this.budget = Objects.requireNonNull(budget, "budget");
        this.consumed = Objects.requireNonNull(consumed, "consumed");
        // Both inputs are immutable values, with no reference to RunControl or model configuration.
        text = "Agent OS 任务诊断 v1\n"
                + "阶段: " + phase + "\n"
                + "结束种类: " + (this.resultKind == null ? "NONE" : this.resultKind.name()) + "\n"
                + "结束代码: " + this.resultCode + "\n"
                + "模型轮次: " + consumed.modelRounds + " / " + budget.maxModelRounds + "\n"
                + "工具调用: " + consumed.toolCalls + " / " + budget.maxToolCalls + "\n"
                + "活动时间(ms): " + consumed.activeMillis + " / " + budget.maxActiveMillis + "\n"
                + "暂停时间(ms): " + consumed.pausedMillis + "\n"
                + "等待确认时间(ms): " + consumed.approvalMillis + "\n\n"
                + "这是打开时的固定快照，不自动刷新；已结束任务的用量在结束时冻结。\n"
                + "调用次数包含观察和失败调用，不代表成功操作数。\n"
                + "报告不含任务正文、应用界面、模型回复、地址、密钥或原始日志；未知错误码记为 OTHER。";
        if (text.length() > MAX_REPORT_CHARS) throw new IllegalStateException("Diagnostic report exceeded its fixed bound");
    }

    /** Fixed allowlist: an uppercase provider string is still not safe diagnostic metadata. */
    public static String safeTerminalCode(String code) {
        if (code == null || code.isEmpty()) return "NOT_RECORDED";
        return KNOWN_CODES.contains(code) ? code : "OTHER";
    }
}
