package dev.agentos.core;

import java.util.Objects;

/** Immutable ownership of one model request, including its original execution epoch. */
final class ModelRequestContext {
    final RunControl control;
    final long epoch;
    private final DisclosureGuard guard;
    private final DisclosureGuard imageGuard;

    ModelRequestContext(RunControl control, long epoch, DisclosureGuard guard) {
        this(control, epoch, guard, null);
    }

    ModelRequestContext(RunControl control, long epoch, DisclosureGuard guard, DisclosureGuard imageGuard) {
        this.control = Objects.requireNonNull(control);
        this.epoch = epoch;
        this.guard = Objects.requireNonNull(guard);
        this.imageGuard = imageGuard;
    }

    void check() throws TaskControlException {
        checkGuard(guard, "配置已改变，已停止向原模型服务发送数据。");
        if (imageGuard != null) checkGuard(imageGuard, "图像发送条件已失效，已停止向模型服务发送数据。");
    }

    private void checkGuard(DisclosureGuard condition, String message) throws TaskControlException {
        control.checkExecutionEpoch(epoch);
        boolean current;
        try { current = condition.isCurrent(control, epoch); }
        catch (RuntimeException invalidGuard) { current = false; }
        if (!current) {
            // Do not let a delayed old request cancel a resumed execution epoch.
            // Atomic cancellation detaches its hook under the control monitor,
            // then invokes it outside that monitor. Never wrap this in an outer lock.
            control.checkExecutionEpoch(epoch);
            if (!control.cancelIfExecutionEpoch(epoch)) control.checkExecutionEpoch(epoch);
            throw new TaskControlException("TASK_STOPPED", message);
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
