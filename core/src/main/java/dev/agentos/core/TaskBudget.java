package dev.agentos.core;

public final class TaskBudget {
    public static final TaskBudget DEFAULT = new TaskBudget(30, 100, 600_000);
    public final int maxModelRounds, maxToolCalls;
    public final long maxActiveMillis;
    public TaskBudget(int maxModelRounds, int maxToolCalls, long maxActiveMillis) {
        if (maxModelRounds < 1 || maxModelRounds > 300 || maxToolCalls < 1 || maxToolCalls > 1000
                || maxActiveMillis < 1 || maxActiveMillis > 3_600_000) throw new IllegalArgumentException("任务预算超出允许范围。");
        this.maxModelRounds = maxModelRounds; this.maxToolCalls = maxToolCalls; this.maxActiveMillis = maxActiveMillis;
    }
}
