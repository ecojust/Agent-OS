package dev.agentos.core;

public final class TaskBudget {
    public static final int MIN_MODEL_ROUNDS = 1, MAX_MODEL_ROUNDS = 300;
    public static final int MIN_TOOL_CALLS = 1, MAX_TOOL_CALLS = 1000;
    public static final long MIN_ACTIVE_MILLIS = 1, MAX_ACTIVE_MILLIS = 3_600_000L;
    public static final TaskBudget DEFAULT = new TaskBudget(30, 100, 600_000);
    public final int maxModelRounds, maxToolCalls;
    public final long maxActiveMillis;
    public TaskBudget(int maxModelRounds, int maxToolCalls, long maxActiveMillis) {
        if (maxModelRounds < MIN_MODEL_ROUNDS || maxModelRounds > MAX_MODEL_ROUNDS
                || maxToolCalls < MIN_TOOL_CALLS || maxToolCalls > MAX_TOOL_CALLS
                || maxActiveMillis < MIN_ACTIVE_MILLIS || maxActiveMillis > MAX_ACTIVE_MILLIS) {
            throw new IllegalArgumentException("任务预算超出允许范围。");
        }
        this.maxModelRounds = maxModelRounds; this.maxToolCalls = maxToolCalls; this.maxActiveMillis = maxActiveMillis;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TaskBudget)) return false;
        TaskBudget that = (TaskBudget) other;
        return maxModelRounds == that.maxModelRounds && maxToolCalls == that.maxToolCalls
                && maxActiveMillis == that.maxActiveMillis;
    }

    @Override public int hashCode() {
        int hash = 31 * maxModelRounds + maxToolCalls;
        return 31 * hash + Long.hashCode(maxActiveMillis);
    }
}
