package dev.agentos.core;

import java.util.UUID;
import java.util.function.LongSupplier;

/** One task identity and a monotonically invalidated execution epoch. */
public final class RunControl {
    private static final long MAX_PAUSED_NANOS = 600_000_000_000L;
    private final String taskId = UUID.randomUUID().toString();
    private final String profileId;
    private final TaskBudget budget;
    private final LongSupplier nanoClock;
    private long epoch = 1, lastAccounted, pausedSince, activeNanos, pausedNanos, approvalNanos;
    private boolean cancelled, paused;
    private int approvalWaits, modelRounds, toolCalls;
    private long dispatchAttempts;
    private DispatchTicket inFlightDispatch;
    private Runnable cancelHook;

    public RunControl() { this("current-personal"); }
    public RunControl(String profileId) { this(profileId, TaskBudget.DEFAULT); }
    public RunControl(String profileId, TaskBudget budget) { this(profileId, budget, System::nanoTime); }
    RunControl(String profileId, TaskBudget budget, LongSupplier nanoClock) {
        if (profileId == null || profileId.trim().isEmpty() || profileId.length() > 128) throw new IllegalArgumentException("资料身份无效。");
        this.profileId = profileId; this.budget = java.util.Objects.requireNonNull(budget);
        this.nanoClock = java.util.Objects.requireNonNull(nanoClock); lastAccounted = nanoClock.getAsLong();
    }
    public String getTaskId() { return taskId; }
    public String getProfileId() { return profileId; }
    public synchronized long getExecutionEpoch() { return epoch; }
    /** Compatibility counter only; it does not track an in-flight operation or system acceptance. */
    public synchronized void markDispatchAttempt(long expectedEpoch) throws TaskControlException {
        checkExecutionEpoch(expectedEpoch); dispatchAttempts++;
    }
    public synchronized long getDispatchAttempts() { return dispatchAttempts; }

    /**
     * Admits one local submission after slow validation has finished. This is not
     * evidence that the platform accepted the action. The worker must recheck the
     * ticket and any immutable platform guard immediately before invoking its API.
     * No platform call or waiting takes place while this monitor is held.
     */
    public synchronized DispatchTicket beginDispatch(long expectedEpoch) throws TaskControlException {
        checkExecutionEpoch(expectedEpoch);
        if (inFlightDispatch != null)
            throw new TaskControlException("DISPATCH_IN_PROGRESS", "上一个设备提交仍未结束，不能开始新的提交。");
        DispatchTicket ticket = new DispatchTicket(this, expectedEpoch);
        inFlightDispatch = ticket; dispatchAttempts++;
        return ticket;
    }

    /** Includes a cancelled or paused operation until its owning worker settles it. */
    public synchronized boolean hasInFlightDispatch() { return inFlightDispatch != null; }

    /**
     * Ownership stays with the worker performing the action, normally in a
     * try-with-resources block. UI cancellation must not close this ticket for
     * that worker: an external call may still be running. Closing releases only
     * this admission; it neither verifies a result nor changes task/epoch state.
     */
    public static final class DispatchTicket implements AutoCloseable {
        private final RunControl owner;
        public final long executionEpoch;
        private boolean settled;

        private DispatchTicket(RunControl owner, long executionEpoch) {
            this.owner = owner; this.executionEpoch = executionEpoch;
        }

        /** A last local check, not an atomic transaction with the external API. */
        public void checkExecutionEpoch() throws TaskControlException {
            synchronized (owner) {
                owner.checkExecutionEpoch(executionEpoch);
                if (settled || owner.inFlightDispatch != this)
                    throw new TaskControlException("DISPATCH_SETTLED", "设备提交已结束，不能复用旧提交凭据。");
            }
        }

        public boolean isSettled() { synchronized (owner) { return settled; } }

        /** Idempotent and available even after cancellation, expiry or an API exception. */
        public void settle() {
            synchronized (owner) {
                if (settled) return;
                settled = true;
                if (owner.inFlightDispatch == this) owner.inFlightDispatch = null;
            }
        }

        @Override public void close() { settle(); }
    }

    public void cancel() {
        Runnable hook;
        synchronized (this) {
            if (cancelled) return;
            account(); cancelled = true; paused = false; epoch++; approvalWaits = 0;
            hook = cancelHook; cancelHook = null; notifyAll();
        }
        runHook(hook);
    }
    public void pause() {
        Runnable hook;
        synchronized (this) {
            if (cancelled || paused) return;
            account(); paused = true; pausedSince = nanoClock.getAsLong(); epoch++;
            hook = cancelHook; cancelHook = null; notifyAll();
        }
        runHook(hook);
    }
    public synchronized void resume() {
        if (cancelled || !paused) return;
        account();
        if (nanoClock.getAsLong() - pausedSince >= MAX_PAUSED_NANOS) {
            cancelled = true; paused = false; epoch++; notifyAll(); return;
        }
        paused = false; epoch++; notifyAll();
    }
    public synchronized boolean isCancelled() { return cancelled; }
    public synchronized boolean isPaused() { return paused; }
    public synchronized void checkCancelled() throws TaskControlException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new TaskControlException("TASK_STOPPED", "任务已停止。");
        account();
        if (activeNanos / 1_000_000 >= budget.maxActiveMillis) throw new TaskControlException("BUDGET_EXCEEDED", "任务活动时间已达到上限。");
    }
    public synchronized void checkExecutionEpoch(long expected) throws TaskControlException {
        checkCancelled();
        if (paused || expected != epoch) throw new TaskControlException("TASK_PAUSED", "执行代次已失效，继续后须重新观察。");
    }
    public synchronized void awaitIfPaused() throws InterruptedException {
        checkCancelled();
        while (paused && !cancelled) {
            long remaining = MAX_PAUSED_NANOS - (nanoClock.getAsLong() - pausedSince);
            if (remaining <= 0) { cancel(); throw new TaskControlException("TASK_STOPPED", "暂停保留时间已到，任务已结束。"); }
            wait(Math.max(1, Math.min(1000, remaining / 1_000_000)));
            checkCancelled();
        }
        checkCancelled();
    }
    /** Hooks must be nonblocking; the model gateway's hook only invalidates its future. */
    public void setCancelHook(Runnable hook) {
        boolean callNow;
        synchronized (this) { callNow = cancelled || paused; if (!callNow) cancelHook = hook; }
        if (callNow) runHook(hook);
    }
    public synchronized void consumeModelRound() throws TaskControlException {
        checkCancelled();
        if (modelRounds >= budget.maxModelRounds) throw new TaskControlException("BUDGET_EXCEEDED", "模型轮次已达到上限。");
        modelRounds++;
    }
    public synchronized void consumeToolCall() throws TaskControlException {
        checkCancelled();
        if (toolCalls >= budget.maxToolCalls) throw new TaskControlException("BUDGET_EXCEEDED", "工具调用次数已达到上限。");
        toolCalls++;
    }
    public synchronized void beginApprovalWait() { account(); if (!cancelled) approvalWaits++; }
    public synchronized void endApprovalWait() { account(); if (approvalWaits > 0) approvalWaits--; }
    public synchronized long remainingActiveMillis() throws TaskControlException {
        checkCancelled(); return Math.max(1, budget.maxActiveMillis - activeNanos / 1_000_000);
    }
    public synchronized BudgetSnapshot budgetSnapshot() {
        account(); return new BudgetSnapshot(modelRounds, toolCalls, activeNanos / 1_000_000, pausedNanos / 1_000_000, approvalNanos / 1_000_000);
    }
    private void account() {
        long now = nanoClock.getAsLong(), elapsed = Math.max(0, now - lastAccounted); lastAccounted = now;
        if (cancelled) return;
        if (paused) pausedNanos += elapsed;
        else if (approvalWaits > 0) approvalNanos += elapsed;
        else activeNanos += elapsed;
    }
    private static void runHook(Runnable hook) { if (hook != null) try { hook.run(); } catch (RuntimeException ignored) { } }
    public static final class BudgetSnapshot {
        public final int modelRounds, toolCalls;
        public final long activeMillis, pausedMillis, approvalMillis;
        BudgetSnapshot(int modelRounds, int toolCalls, long activeMillis, long pausedMillis, long approvalMillis) {
            this.modelRounds = modelRounds; this.toolCalls = toolCalls; this.activeMillis = activeMillis; this.pausedMillis = pausedMillis; this.approvalMillis = approvalMillis;
        }
    }
}
