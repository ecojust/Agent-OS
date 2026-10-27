package dev.agentos.core;

import java.util.Objects;

/** Immutable ownership of one model request, including its original execution epoch. */
final class ModelRequestContext {
    final RunControl control;
    final long epoch;
    private final DisclosureGuard guard;

    ModelRequestContext(RunControl control, long epoch, DisclosureGuard guard) {
        this.control = Objects.requireNonNull(control);
        this.epoch = epoch;
        this.guard = Objects.requireNonNull(guard);
    }

    void check() throws TaskControlException {
        control.checkExecutionEpoch(epoch);
        boolean current;
        try { current = guard.isCurrent(control, epoch); }
        catch (RuntimeException invalidGuard) { current = false; }
        if (!current) {
            // Do not let a delayed old request cancel a resumed execution epoch.
            // cancel's hook only invalidates the future and schedules disconnection.
            synchronized (control) {
                control.checkExecutionEpoch(epoch);
                control.cancel();
            }
            throw new TaskControlException("TASK_STOPPED", "配置已改变，已停止向原模型服务发送数据。");
        }
        control.checkExecutionEpoch(epoch);
    }

    /** Only the fixed, device-free protocol probes and same-package synthetic tests use this. */
    static ModelRequestContext syntheticRequest(RunControl control, long epoch) {
        return new ModelRequestContext(control, epoch, syntheticTestGuard());
    }

    /** Not a production constructor or permission source; unavailable outside this package. */
    static DisclosureGuard syntheticTestGuard() { return (control, epoch) -> true; }
}
