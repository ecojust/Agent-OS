package dev.agentos.core;

public final class TaskResult {
    public enum Kind { MODEL_REPORT, VERIFIED, STOPPED, FAILED, BUDGET_EXCEEDED }
    public final Kind kind;
    public final String summary;
    public final String code;
    public final boolean verified;
    public TaskResult(Kind kind, String summary, String code) {
        this.kind = kind; this.summary = summary; this.code = code; this.verified = kind == Kind.VERIFIED;
    }
}
