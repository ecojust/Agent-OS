package dev.agentos.app;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import dev.agentos.core.AgentRunner;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.DisclosureGuard;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.ModelTokenUsage;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.TaskResult;
import dev.agentos.core.TaskDiagnostics;
import dev.agentos.core.TaskActionReceipts;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one explicitly started task; it never resumes tasks after process death. */
public final class AgentController {
    public interface Listener { void onChanged(); }

    /** Synthetic model diagnostics only; none of these states authorizes a screenshot. */
    public enum VisualQualification {
        NOT_REQUESTED, CHECKING, SYNTHETIC_VERIFIED, UNVERIFIED, FAILED, BUSY, INVALIDATED
    }

    /** Immutable local-budget and reported-token counters; no prompt, response, settings or credentials. */
    public static final class TaskUsage {
        public final String taskId;
        public final boolean finished;
        public final TaskBudget budget;
        public final RunControl.BudgetSnapshot consumed;
        public final ModelTokenUsage modelTokens;

        private TaskUsage(RunControl owner, TaskBudget budget, boolean finished) {
            this.taskId = owner.getTaskId();
            this.finished = finished;
            this.budget = budget;
            this.consumed = owner.budgetSnapshot();
            this.modelTokens = owner.modelTokenUsageSnapshot();
        }
    }

    /** Process-local input ownership only; neither identity nor task text is persisted or exported. */
    public static final class TaskInputState {
        private final Object identity;
        public final boolean running;

        private TaskInputState(Object identity, boolean running) {
            this.identity = identity;
            this.running = running;
        }

        public boolean matches(TaskInputState other) {
            return other != null && identity == other.identity && running == other.running;
        }
    }

    public static final class State {
        public final String status;
        public final boolean running;
        public final boolean paused;
        public final String approval;
        public final String approvalId;
        public final List<String> log;
        public final String summary;
        /** Null before the first task or after explicit clearing. */
        public final TaskUsage usage;
        public final boolean visualRequested;
        public final VisualQualification visualQualification;
        public final TaskResult.Kind resultKind;
        public final String resultCode;
        public final TaskInputState taskInput;

        State(String status, boolean running, boolean paused, String approval, String approvalId,
              List<String> log, String summary, TaskUsage usage, boolean visualRequested,
              VisualQualification visualQualification, TaskResult.Kind resultKind, String resultCode,
              TaskInputState taskInput) {
            this.status = status;
            this.running = running;
            this.paused = paused;
            this.approval = approval;
            this.approvalId = approvalId;
            this.log = Collections.unmodifiableList(new ArrayList<>(log));
            this.summary = summary;
            this.usage = usage;
            this.visualRequested = visualRequested;
            this.visualQualification = visualQualification;
            this.resultKind = resultKind;
            this.resultCode = resultCode;
            this.taskInput = taskInput;
        }
    }

    private static volatile AgentController instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), r -> {
        Thread thread = new Thread(r, "agent-os-task");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean publicationPending = new AtomicBoolean();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final List<String> log = new ArrayList<>();
    private long generation;
    private long stopGeneration = -1, stoppedEpoch = -1, stoppedDispatchAttempts = -1;
    private RunControl control;
    private Object taskInputIdentity = new Object();
    private TaskBudget usageBudget;
    private TaskUsage completedUsage;
    private TaskResult.Kind completedResultKind;
    private String completedResultCode = "";
    private TaskActionReceipts actionReceipts;
    private TaskVisualQualification taskVisual;
    private boolean completedVisualRequested;
    private VisualQualification completedVisualQualification = VisualQualification.NOT_REQUESTED;
    private SettingsStore.ConfigurationLease configuration;
    private PendingApproval pending;
    private String status = "准备就绪";
    private String summary = "";
    // Same task/approval publications keep their callback identity. A log update
    // must not revoke the service's otherwise unchanged control-window lease.
    private AgentAccessibilityService.OverlayCallbacks overlayCallbacks;
    private RunControl overlayCallbackOwner;
    private long overlayCallbackGeneration = -1;
    private String overlayCallbackApprovalId = "";

    /** Accessed under the controller lock; holds no model settings, image or task text. */
    private static final class TaskVisualQualification {
        final RunControl owner;
        final SettingsStore.ConfigurationLease configuration;
        final long originalEpoch;
        final boolean requested;
        VisualQualification state;
        SavedVisionProbe.Verification verification;

        TaskVisualQualification(RunControl owner, SettingsStore.ConfigurationLease configuration,
                                long originalEpoch, boolean requested) {
            this.owner = owner; this.configuration = configuration;
            this.originalEpoch = originalEpoch; this.requested = requested;
            state = requested ? VisualQualification.CHECKING : VisualQualification.NOT_REQUESTED;
        }
    }

    private static final class PendingApproval {
        final String id = UUID.randomUUID().toString();
        final String description;
        final long epoch;
        final SettingsStore.ConfigurationLease configuration;
        final long deadline = SystemClock.elapsedRealtime() + 60_000L;
        Boolean answer;
        PendingApproval(String description, long epoch, SettingsStore.ConfigurationLease configuration) {
            this.description = description; this.epoch = epoch; this.configuration = configuration;
        }
    }

    private AgentController(Context context) { this.context = context.getApplicationContext(); }

    /**
     * The same factory binds production requests and package-level regression tests.
     * This check must not decrypt settings, query Android, acquire the store lock or
     * wait for invalidation listeners. The core request context cancels only its
     * originating RunControl when the published lease is no longer current.
     */
    static DisclosureGuard disclosureGuardFor(RunControl owner, SettingsStore.ConfigurationLease lease) {
        java.util.Objects.requireNonNull(owner, "owner");
        java.util.Objects.requireNonNull(lease, "lease");
        return (original, epoch) -> original == owner
                && owner.getProfileId().equals(lease.profileId)
                && epoch == owner.getExecutionEpoch()
                && SettingsStore.isCurrent(lease);
    }

    public static AgentController get(Context context) {
        if (instance == null) {
            synchronized (AgentController.class) {
                if (instance == null) instance = new AgentController(context);
            }
        }
        return instance;
    }

    public State getState() {
        synchronized (lock) {
            TaskInputState input = taskInputStateLocked();
            return new State(status, input.running,
                    control != null && control.isPaused(),
                    pending == null ? "" : pending.description,
                    pending == null ? "" : pending.id, log, summary, taskUsageLocked(),
                    taskVisual == null ? completedVisualRequested : taskVisual.requested,
                    visualQualificationLocked(), completedResultKind, completedResultCode, input);
        }
    }

    /** Pure memory snapshot for a UI draft; never starts, resumes, clears or publishes a task. */
    public TaskInputState getTaskInputState() {
        synchronized (lock) { return taskInputStateLocked(); }
    }

    private TaskInputState taskInputStateLocked() {
        return new TaskInputState(taskInputIdentity, control != null && !control.isCancelled());
    }

    /** Lightweight foreground polling: no log copy, publication, View, storage, Binder or network work. */
    public TaskUsage getTaskUsage() {
        synchronized (lock) { return taskUsageLocked(); }
    }

    /** Immutable local receipts only; viewing never publishes, queries the device or starts work. */
    public TaskActionReceipts getTaskActionReceipts() {
        synchronized (lock) { return actionReceipts; }
    }

    /** A frozen preview may outlive task completion, but not clearing or replacement. */
    public boolean isTaskActionReceiptsCurrent(TaskActionReceipts snapshot) {
        synchronized (lock) {
            return snapshot != null && actionReceipts != null
                    && snapshot.taskId.equals(actionReceipts.taskId);
        }
    }

    private void freezeActionReceiptsLocked() {
        if (actionReceipts != null) actionReceipts = actionReceipts.freeze();
    }

    /** User-requested numeric snapshot only: no log copy, platform query, network or publication. */
    public TaskDiagnostics getTaskDiagnostics() {
        synchronized (lock) {
            TaskUsage usage = taskUsageLocked();
            if (usage == null) return null;
            TaskDiagnostics.Phase phase = usage.finished ? TaskDiagnostics.Phase.FINISHED
                    : control.isCancelled() ? TaskDiagnostics.Phase.STOPPING
                    : control.isPaused() ? TaskDiagnostics.Phase.PAUSED
                    : pending != null ? TaskDiagnostics.Phase.WAITING_APPROVAL : TaskDiagnostics.Phase.RUNNING;
            return new TaskDiagnostics(usage.taskId, phase, completedResultKind, completedResultCode,
                    usage.budget, usage.consumed);
        }
    }

    /** A copied preview may refer to a stopped task, but never to a cleared or replacement task. */
    public boolean isTaskDiagnosticsCurrent(String taskId) {
        synchronized (lock) {
            String current = control != null ? control.getTaskId()
                    : completedUsage == null ? null : completedUsage.taskId;
            return taskId != null && taskId.equals(current);
        }
    }

    private TaskUsage taskUsageLocked() {
        return control == null ? completedUsage : new TaskUsage(control, usageBudget, false);
    }

    /** Call after cancellation has accounted the cutoff, before dropping the original owner. */
    private void freezeUsageLocked(RunControl owner) {
        if (control != owner || owner == null) return;
        if (completedUsage == null || !completedUsage.taskId.equals(owner.getTaskId()))
            completedUsage = new TaskUsage(owner, usageBudget, true);
    }

    /** Pure memory/clock checks: getters do not cancel, publish or initiate a probe. */
    private VisualQualification visualQualificationLocked() {
        TaskVisualQualification current = taskVisual;
        if (current == null) return completedVisualQualification;
        if (current.requested && (current.state == VisualQualification.CHECKING
                || current.state == VisualQualification.SYNTHETIC_VERIFIED)) {
            boolean sameEpoch = !current.owner.isCancelled() && !current.owner.isPaused()
                    && current.owner.getExecutionEpoch() == current.originalEpoch
                    && SettingsStore.isCurrent(current.configuration);
            if (!sameEpoch || current.state == VisualQualification.SYNTHETIC_VERIFIED
                    && (current.verification == null || !current.verification.isCurrentFor(
                            current.configuration, current.owner, current.originalEpoch))) {
                current.state = VisualQualification.INVALIDATED;
                current.verification = null;
            }
        }
        return current.state;
    }

    private void releaseVisualQualificationLocked(RunControl owner) {
        if (taskVisual == null || taskVisual.owner != owner) return;
        completedVisualRequested = taskVisual.requested;
        VisualQualification state = visualQualificationLocked();
        completedVisualQualification = state == VisualQualification.CHECKING
                || state == VisualQualification.SYNTHETIC_VERIFIED ? VisualQualification.INVALIDATED : state;
        taskVisual.verification = null;
        taskVisual = null;
    }

    public void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void start(ModelSettings settings, String task, Set<String> allowedPackages,
                      ActionConfirmationPolicy.Mode executionMode, SettingsStore.ConfigurationLease configurationLease) {
        start(settings, task, allowedPackages, executionMode, TaskBudget.DEFAULT, configurationLease);
    }

    public void start(ModelSettings settings, String task, Set<String> allowedPackages,
                      ActionConfirmationPolicy.Mode executionMode, TaskBudget taskBudget,
                      SettingsStore.ConfigurationLease configurationLease) {
        start(settings, task, allowedPackages, executionMode, taskBudget, configurationLease, false);
    }

    /** Explicit intent for this new task only; the legacy overloads remain structured-only. */
    public void start(ModelSettings settings, String task, Set<String> allowedPackages,
                      ActionConfirmationPolicy.Mode executionMode, TaskBudget taskBudget,
                      SettingsStore.ConfigurationLease configurationLease, boolean visualRequested) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("请从任务界面启动。");
        }
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) throw new IllegalStateException("请先在系统设置中启用 Agent OS 无障碍服务。");
        if (!isUnlocked()) throw new IllegalStateException("请保持手机亮屏并解锁后再启动。");
        if (settings == null || executionMode == null || task == null || task.trim().isEmpty()) {
            throw new IllegalArgumentException("请填写模型设置和任务内容。");
        }
        if (taskBudget == null || taskBudget.maxActiveMillis < 1000 || taskBudget.maxActiveMillis % 1000 != 0) {
            throw new IllegalArgumentException("任务活动时间须为 1–3600 秒的整数，请检查任务预算。");
        }
        settings.validate();
        SettingsStore.requirePersonalProfile(context);
        if (!SettingsStore.isCurrent(configurationLease)
                || !configurationLease.profileId.equals(SettingsStore.currentProfileId(context))) {
            throw new IllegalStateException("配置已改变，请重新保存后启动。");
        }
        if (task.length() > 6000) throw new IllegalArgumentException("任务内容请控制在 6000 字以内。");
        Set<String> scope = new LinkedHashSet<>(allowedPackages == null
                ? Collections.emptySet() : allowedPackages);
        scope.remove(context.getPackageName());
        if (scope.isEmpty()) throw new IllegalArgumentException("请至少选择一个允许操作的应用。");

        final RunControl active = new RunControl(SettingsStore.currentProfileId(context), taskBudget);
        // Capture before publishing the control: a pause in the queue must never
        // grant a new origin epoch to the old visual request.
        final TaskVisualQualification visual = new TaskVisualQualification(active, configurationLease,
                active.getExecutionEpoch(), visualRequested);
        final long session;
        synchronized (lock) {
            if (control != null) throw new IllegalStateException("已有任务正在运行，请先停止。");
            session = ++generation;
            control = active;
            taskInputIdentity = new Object();
            usageBudget = taskBudget;
            completedUsage = null;
            completedResultKind = null;
            completedResultCode = "";
            actionReceipts = TaskActionReceipts.empty(active.getTaskId());
            taskVisual = visual;
            completedVisualRequested = false;
            completedVisualQualification = VisualQualification.NOT_REQUESTED;
            configuration = configurationLease;
            pending = null;
            log.clear();
            summary = "";
            status = "正在连接模型";
            appendLogLocked("任务已启动；允许操作 " + scope.size() + " 个应用。");
            appendLogLocked("本次预算：" + taskBudget.maxModelRounds + " 轮模型请求、"
                    + taskBudget.maxToolCalls + " 次工具调用、" + taskBudget.maxActiveMillis / 1000
                    + " 秒活动时间；暂停和确认等待不计入活动时间。");
            appendLogLocked(executionMode == ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION
                    ? "连续导航：打开应用、滚动、回到桌面可连续执行；返回、点击和输入仍需确认。"
                    : "逐步确认：每次界面写操作都需确认。");
        }
        final SettingsStore.Registration configurationWatch = configurationLease.watch(
                () -> configurationInvalidated(session, active));
        if (active.isCancelled()) {
            configurationWatch.close();
            throw new IllegalStateException("配置已改变，请重新保存后启动。");
        }
        service.setOverlayCallbacks(callbacksFor(session, active, ""));
        try {
            service.showOverlay(status, false, "");
        } catch (RuntimeException error) {
            configurationWatch.close();
            stop();
            throw new IllegalStateException("无法显示任务控制条，请检查无障碍服务状态。");
        }
        publish();

        try { worker.execute(() -> {
            try {
                active.checkCancelled();
                SettingsStore store = new SettingsStore(context);
                active.checkCancelled();
                SettingsStore.LoadedSettings loaded = store.loadWithLease();
                SettingsStore.SavedSettings saved = loaded.settings;
                if (loaded.lease != configurationLease || !SettingsStore.isCurrent(configurationLease)
                        || !store.validateAllowedPackages(saved).containsAll(scope)
                        || !settings.endpoint.equals(saved.endpoint.trim())
                        || !settings.model.equals(saved.model.trim())
                        || !settings.apiKey.equals(saved.apiKey.trim())
                        || settings.maxOutputTokens != saved.maxOutputTokens
                        || executionMode != saved.executionMode) {
                    finish(session, new TaskResult(TaskResult.Kind.FAILED,
                            "配置或授权已改变，请重新保存配置并选择允许的应用。", "CONFIGURATION_INVALID"));
                    return;
                }
                if (!taskBudget.equals(saved.taskBudget)) {
                    finish(session, new TaskResult(TaskResult.Kind.FAILED,
                            "任务预算已改变，请重新保存配置后启动。", "CONFIGURATION_INVALID"));
                    return;
                }
                active.checkCancelled();
                if (visual.requested) qualifyTaskVisual(session, visual);
                active.checkCancelled();
                AndroidDeviceTools tools = new AndroidDeviceTools(service, scope, saved.grants,
                        executionMode, configurationLease, (description, run, epoch) -> requestApproval(session, description, run, epoch, configurationLease),
                        active, visual.originalEpoch, visualQualificationGuardFor(session, visual));
                try { tools.prepareVisualSupport(); }
                catch (TaskControlException preparationStopped) {
                    if (!"TASK_PAUSED".equals(preparationStopped.getCode())) throw preparationStopped;
                    // A pause during the one APK check abandons visual support,
                    // then enters the original runner's existing pause loop.
                    active.checkCancelled();
                    synchronized (lock) {
                        requireVisualTaskLocked(session, visual);
                        requireConfigurationLocked(configurationLease, active);
                        active.checkCancelled();
                        visual.state = VisualQualification.INVALIDATED;
                        visual.verification = null;
                    }
                }
                AgentRunner runner = new AgentRunner(settings, tools, new AgentRunner.Listener() {
                    @Override public void onStatus(String next) {
                        synchronized (lock) {
                            if (!isCurrentLocked(session)) return;
                            if (!active.isPaused() && pending == null) status = safeText(next, 160);
                        }
                        publish();
                    }
                    @Override public void onStep(String next) {
                        synchronized (lock) {
                            if (!isCurrentLocked(session)) return;
                            appendLogLocked(safeText(next, 600));
                        }
                        publish();
                    }
                    @Override public void onFinished(String result, boolean success) {
                        finish(session, result, success);
                    }
                    @Override public void onResult(TaskResult result) { finish(session, result); }
                    @Override public void onActionReceipts(TaskActionReceipts snapshot) {
                        synchronized (lock) {
                            // A stopped worker cannot update its frozen history, a
                            // replacement task or a user-cleared page. Never republish
                            // the control window for an action-receipt-only update.
                            if (!isCurrentLocked(session) || control != active || snapshot == null
                                    || !active.getTaskId().equals(snapshot.taskId) || actionReceipts == null
                                    || actionReceipts.frozen || snapshot.version <= actionReceipts.version) return;
                            actionReceipts = snapshot;
                        }
                    }
                }, active, disclosureGuardFor(active, configurationLease));
                active.checkCancelled();
                runner.run(task.trim(), active);
                // A runner must finish exactly once. This fallback prevents stale UI on a faulty adapter.
                finish(session, "执行已结束，请检查目标应用中的实际结果。", false);
            } catch (TaskControlException controlled) {
                // Queueing and configuration reads consume the same active budget
                // as execution. A budget stop is not a Java thread interruption.
                TaskResult.Kind kind = "BUDGET_EXCEEDED".equals(controlled.getCode())
                        ? TaskResult.Kind.BUDGET_EXCEEDED : TaskResult.Kind.STOPPED;
                finish(session, new TaskResult(kind, controlled.getMessage(), controlled.getCode()));
            } catch (InterruptedException interrupted) {
                finish(session, "任务已停止。", false);
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                // Exceptions may contain provider response bodies or input values; do not display them.
                finish(session, "执行中断，请检查模型配置、网络和无障碍服务状态。", false);
            } finally {
                configurationWatch.close();
                active.cancel();
            }
        }); } catch (java.util.concurrent.RejectedExecutionException busy) {
            configurationWatch.close();
            active.cancel();
            finish(session, "上一任务仍在释放资源，请稍后重试。", false);
        }
    }

    /** Runs once on the existing task worker, before any structured observation. */
    private void qualifyTaskVisual(long session, TaskVisualQualification visual) throws InterruptedException {
        VisualQualification result;
        SavedVisionProbe.Verification verification = null;
        try {
            visual.owner.checkExecutionEpoch(visual.originalEpoch);
            synchronized (lock) {
                requireVisualTaskLocked(session, visual);
                requireConfigurationLocked(visual.configuration, visual.owner);
                visual.owner.checkExecutionEpoch(visual.originalEpoch);
                status = "正在检查本次任务的图像输入";
                appendLogLocked("本次任务进行一次合成图片测试，计入模型轮次和活动时间；截图工具仍未开放。");
            }
            publish();
            SavedVisionProbe registry = SavedVisionProbe.process();
            SavedVisionProbe.Outcome outcome = registry.test(context, visual.configuration,
                    visual.owner, visual.originalEpoch);
            switch (outcome.status) {
                case VERIFIED:
                    verification = registry.current(visual.configuration);
                    result = VisualQualification.SYNTHETIC_VERIFIED;
                    break;
                case UNVERIFIED: result = VisualQualification.UNVERIFIED; break;
                case BUSY: result = VisualQualification.BUSY; break;
                default: result = VisualQualification.FAILED;
            }
        } catch (TaskControlException interrupted) {
            if (!"TASK_PAUSED".equals(interrupted.getCode())) throw interrupted;
            // A pause only discards this original visual attempt. The final
            // current-task/config/budget checks below must still pass before the
            // structured runner may enter its existing pause/resume loop.
            result = VisualQualification.INVALIDATED;
        } catch (java.io.IOException unavailable) {
            // Never expose storage/provider details or treat a cancelled task as
            // an ordinary failed test. The same final guards apply below.
            result = VisualQualification.FAILED;
        }
        visual.owner.checkCancelled();
        synchronized (lock) {
            requireVisualTaskLocked(session, visual);
            requireConfigurationLocked(visual.configuration, visual.owner);
            visual.owner.checkCancelled();
            if (visual.owner.isPaused() || visual.owner.getExecutionEpoch() != visual.originalEpoch) {
                result = VisualQualification.INVALIDATED;
                verification = null;
            } else if (result == VisualQualification.SYNTHETIC_VERIFIED
                    && (verification == null || !verification.isCurrentFor(
                            visual.configuration, visual.owner, visual.originalEpoch))) {
                result = VisualQualification.INVALIDATED;
                verification = null;
            }
            visual.state = result;
            visual.verification = result == VisualQualification.SYNTHETIC_VERIFIED ? verification : null;
            if (!visual.owner.isPaused()) status = "正在连接模型";
            appendLogLocked(visualQualificationMessage(result));
        }
        publish();
    }

    private DisclosureGuard visualQualificationGuardFor(long session, TaskVisualQualification original) {
        return (owner, epoch) -> {
            synchronized (lock) {
                return isCurrentLocked(session) && control == original.owner && taskVisual == original
                        && owner == original.owner && epoch == original.originalEpoch && original.requested
                        && original.state == VisualQualification.SYNTHETIC_VERIFIED && original.verification != null
                        && !owner.isCancelled() && !owner.isPaused() && owner.getExecutionEpoch() == epoch
                        && SettingsStore.isCurrent(original.configuration)
                        && original.verification.isCurrentFor(original.configuration, owner, epoch);
            }
        };
    }

    private void requireVisualTaskLocked(long session, TaskVisualQualification visual) throws TaskControlException {
        if (!isCurrentLocked(session) || control != visual.owner || taskVisual != visual)
            throw new TaskControlException("TASK_STOPPED", "原任务已结束，旧图像测试不能继续。");
    }

    private static String visualQualificationMessage(VisualQualification result) {
        switch (result) {
            case SYNTHETIC_VERIFIED:
                return "本次任务的合成图像理解测试通过；页面截图仍须具备设备能力、通过页面检查并逐图确认。";
            case BUSY:
                return "图像测试服务正忙，本次不重试，继续使用结构化工具。";
            case UNVERIFIED:
                return "本次合成图像理解未通过校验，继续使用结构化工具。";
            case INVALIDATED:
                return "本次图像资格已失效，不沿用旧记录或自动重测；继续后使用结构化工具。";
            default:
                return "本次图像测试未完成，继续使用结构化工具。";
        }
    }

    public void pauseResume() {
        synchronized (lock) {
            if (control == null) return;
            pending = null;
            if (!SettingsStore.isCurrent(configuration)) {
                configurationInvalidatedLocked(control);
            } else if (control.isPaused()) {
                if (!isUnlocked()) { stop(); return; }
                control.resume();
                status = "正在重新观察";
                appendLogLocked("任务已继续；旧动作与旧确认已失效。");
            } else {
                control.pause();
                status = "已暂停";
                appendLogLocked("任务已暂停；已进入提交阶段的动作可能仍在完成，请核对当前应用。");
            }
            lock.notifyAll();
        }
        publish();
    }

    public void stop() { stop(null); }

    /** An old task preview must never stop a replacement task. */
    public boolean stopIfCurrentTask(String taskId) {
        return taskId != null && stop(taskId);
    }

    private boolean stop(String expectedTaskId) {
        PerformanceTrace.Span stopTiming = PerformanceTrace.begin(PerformanceTrace.Stage.STOP_RECEIVED);
        RunControl cancelled;
        synchronized (lock) {
            if (control == null || (expectedTaskId != null && !expectedTaskId.equals(control.getTaskId()))) return false;
            stopTiming.finish();
            cancelled = control;
            BusyStopProbe.stopPoint(cancelled, BusyStopProbe.STOP_OWNED_ENTRY);
            cancelled.cancel();
            // Diagnostic upper bound: cancel's epoch mutation preceded hook completion and return.
            BusyStopProbe.stopPoint(cancelled, BusyStopProbe.CANCEL_RETURN);
            freezeUsageLocked(cancelled);
            freezeActionReceiptsLocked();
            completedResultKind = TaskResult.Kind.STOPPED;
            completedResultCode = "TASK_STOPPED";
            releaseVisualQualificationLocked(cancelled);
            PerformanceTrace.mark(PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED,
                    cancelled.getExecutionEpoch(), cancelled.getDispatchAttempts());
            control = null;
            taskInputIdentity = new Object();
            configuration = null;
            clearOverlayCallbacksLocked();
            ++generation;
            stopGeneration = generation;
            stoppedEpoch = cancelled.getExecutionEpoch();
            stoppedDispatchAttempts = cancelled.getDispatchAttempts();
            pending = null;
            status = "已停止";
            summary = cancelled.hasInFlightDispatch()
                    ? "任务已停止。有动作已进入提交阶段，可能仍在完成；请核对当前应用。"
                    : "任务已停止。已完成的应用操作不会自动撤销。";
            appendLogLocked("任务已停止。");
            lock.notifyAll();
        }
        publish();
        BusyStopProbe.stopPoint(cancelled, BusyStopProbe.STOP_RETURN);
        return true;
    }

    public void approve(String approvalId, boolean approved) {
        synchronized (lock) {
            if (pending == null || control == null || pending.answer != null
                    || control.isPaused() || !pending.id.equals(approvalId)) return;
            if (!SettingsStore.isCurrent(pending.configuration) || pending.configuration != configuration) {
                configurationInvalidatedLocked(control);
            } else if (pending.epoch != control.getExecutionEpoch()
                    || SystemClock.elapsedRealtime() >= pending.deadline) {
                control.pause(); pending = null; status = "确认已过期，任务已暂停";
            } else {
                pending.answer = approved;
                appendLogLocked(approved ? "已允许当前操作。" : "已拒绝当前操作。");
            }
            lock.notifyAll();
        }
        publish();
    }

    private boolean requestApproval(long session, String description, RunControl run, long requestEpoch,
                                    SettingsStore.ConfigurationLease configurationLease)
            throws InterruptedException {
        // The approval belongs to the originating request, never a later resume.
        PendingApproval request = new PendingApproval(safeText(description, 6000), requestEpoch, configurationLease);
        synchronized (lock) {
            run.checkExecutionEpoch(request.epoch);
            if (!isCurrentLocked(session)) throw new InterruptedException("Task changed");
            requireConfigurationLocked(configurationLease, run);
            pending = request;
            status = "等待确认（60 秒内有效）";
        }
        publish();
        run.beginApprovalWait();
        try {
            while (true) {
                run.checkExecutionEpoch(request.epoch);
                if (!isUnlocked()) {
                    stop();
                    throw new InterruptedException("Screen locked");
                }
                synchronized (lock) {
                    run.checkExecutionEpoch(request.epoch);
                    if (!isCurrentLocked(session)) throw new TaskControlException("TASK_STOPPED", "任务已结束。");
                    requireConfigurationLocked(configurationLease, run);
                    if (pending != request) throw new TaskControlException("TASK_PAUSED", "确认已失效。");
                    long remaining = request.deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) {
                        run.pause(); pending = null; status = "确认超时，任务已暂停";
                        appendLogLocked("确认超时，未提交该操作。");
                        throw new TaskControlException("TASK_PAUSED", "确认超时。");
                    }
                    if (request.answer != null) return request.answer;
                    lock.wait(remaining);
                }
            }
        } finally {
            run.endApprovalWait();
            synchronized (lock) {
                if (pending == request) {
                    pending = null;
                    if (isCurrentLocked(session)) status = run.isPaused() ? "已暂停" : "正在继续";
                }
            }
            publish();
        }
    }

    private void requireConfigurationLocked(SettingsStore.ConfigurationLease lease, RunControl run)
            throws TaskControlException {
        if (configuration != lease || !SettingsStore.isCurrent(lease)) {
            if (control == run) configurationInvalidatedLocked(run);
            throw new TaskControlException("TASK_STOPPED", "配置已改变，任务已停止。");
        }
    }

    private void configurationInvalidated(long session, RunControl active) {
        synchronized (lock) {
            // A delayed observer from a completed task must never cancel a new one.
            if (generation != session || control != active) return;
            configurationInvalidatedLocked(active);
        }
        publish();
    }

    private void configurationInvalidatedLocked(RunControl active) {
        // Use the existing cancellation hook to abort transport; never replace it.
        active.cancel();
        freezeUsageLocked(active);
        freezeActionReceiptsLocked();
        completedResultKind = TaskResult.Kind.STOPPED;
        completedResultCode = "CONFIGURATION_CHANGED";
        releaseVisualQualificationLocked(active);
        control = null;
        taskInputIdentity = new Object();
        configuration = null;
        clearOverlayCallbacksLocked();
        pending = null;
        ++generation;
        status = "配置已改变，任务已停止";
        summary = active.hasInFlightDispatch()
                ? "配置已改变，旧确认已失效。有动作已进入提交阶段，可能仍在完成；请核对当前应用。"
                : "配置已改变，旧任务和旧确认已失效，请重新启动任务。";
        appendLogLocked("配置已改变；任务已停止。");
        lock.notifyAll();
    }

    private void finish(long session, String result, boolean normallyEnded) {
        finish(session, new TaskResult(normallyEnded ? TaskResult.Kind.MODEL_REPORT : TaskResult.Kind.FAILED,
                result, ""));
    }

    private void finish(long session, TaskResult result) {
        synchronized (lock) {
            if (!isCurrentLocked(session)) return;
            control.cancel();
            freezeUsageLocked(control);
            freezeActionReceiptsLocked();
            completedResultKind = result.kind;
            completedResultCode = TaskDiagnostics.safeTerminalCode(result.code);
            releaseVisualQualificationLocked(control);
            control = null;
            taskInputIdentity = new Object();
            configuration = null;
            clearOverlayCallbacksLocked();
            pending = null;
            switch (result.kind) {
                case VERIFIED: status = "结果已验证"; break;
                case MODEL_REPORT: status = "执行已结束 · 模型报告待核对"; break;
                case STOPPED: status = "已停止"; break;
                case BUDGET_EXCEEDED: status = "达到任务预算，已停止"; break;
                default: status = "执行失败";
            }
            summary = safeText(result.summary, 8000);
            appendLogLocked(status + "；请核对目标应用的实际结果。");
            lock.notifyAll();
        }
        publish();
    }

    private boolean isCurrentLocked(long session) { return session == generation && control != null; }

    private boolean isUnlocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        return keyguard != null && power != null && !keyguard.isKeyguardLocked() && power.isInteractive();
    }

    private void publish() {
        if (!publicationPending.compareAndSet(false, true)) return;
        PerformanceTrace.Span queuedTiming = PerformanceTrace.begin(PerformanceTrace.Stage.UI_PUBLICATION_QUEUE);
        main.post(() -> {
            queuedTiming.finish();
            PerformanceTrace.Span publicationTiming = PerformanceTrace.begin(PerformanceTrace.Stage.UI_PUBLICATION_APPLIED);
            try {
            publicationPending.set(false);
            State state;
            long publishedGeneration;
            AgentAccessibilityService.OverlayCallbacks publishedCallbacks;
            synchronized (lock) {
                state = getState(); publishedGeneration = generation;
                publishedCallbacks = state.running ? callbacksFor(publishedGeneration, control, state.approvalId) : null;
            }
            AgentAccessibilityService service = AgentAccessibilityService.getInstance();
            if (service != null) {
                try {
                    if (state.running) {
                        service.setOverlayCallbacks(publishedCallbacks);
                        service.showOverlay(state.status, state.paused, state.approval);
                    } else { service.hideOverlay(); service.setOverlayCallbacks(null); }
                } catch (RuntimeException error) {
                    if (state.running) stop();
                }
            } else if (state.running) {
                stop();
            }
            for (Listener listener : listeners) listener.onChanged();
            synchronized (lock) {
                // A previously captured running state is not proof that the stop UI
                // has applied. Associate this marker with the actual stopped generation.
                if (!state.running && publishedGeneration == generation && publishedGeneration == stopGeneration) {
                    PerformanceTrace.mark(PerformanceTrace.Stage.STOP_UI_APPLIED, stoppedEpoch, stoppedDispatchAttempts);
                    stopGeneration = -1;
                }
            }
            } finally { publicationTiming.finish(); }
        });
    }

    private void appendLogLocked(String text) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        log.add(stamp + "  " + text);
        while (log.size() > 200) log.remove(0);
    }

    public void clearCompletedTask() {
        synchronized (lock) {
            if (control != null) return;
            taskInputIdentity = new Object();
            summary = ""; log.clear(); status = "准备就绪";
            completedUsage = null;
            completedResultKind = null;
            completedResultCode = "";
            actionReceipts = null;
            usageBudget = null;
            completedVisualRequested = false;
            completedVisualQualification = VisualQualification.NOT_REQUESTED;
        }
        publish();
    }

    private AgentAccessibilityService.OverlayCallbacks callbacksFor(long session, RunControl owner, String approvalId) {
        synchronized (lock) {
            if (!isCallbackOwnerCurrentLocked(session, owner)) return null;
            if (overlayCallbacks != null && overlayCallbackOwner == owner
                    && overlayCallbackGeneration == session && overlayCallbackApprovalId.equals(approvalId)) {
                return overlayCallbacks;
            }
            overlayCallbackOwner = owner;
            overlayCallbackGeneration = session;
            overlayCallbackApprovalId = approvalId;
            overlayCallbacks = new AgentAccessibilityService.OverlayCallbacks() {
                @Override public void onPauseResume() {
                    synchronized (lock) {
                        if (isCallbackOwnerCurrentLocked(session, owner)) pauseResume();
                    }
                }
                @Override public void onStop() {
                    synchronized (lock) {
                        if (isCallbackOwnerCurrentLocked(session, owner)) stop();
                    }
                }
                @Override public void onApprove(boolean approved) {
                    synchronized (lock) {
                        if (isCallbackOwnerCurrentLocked(session, owner)) approve(approvalId, approved);
                    }
                }
                @Override public void onDevicePaused(String reason) {
                    synchronized (lock) {
                        if (!isCallbackOwnerCurrentLocked(session, owner) || !owner.isPaused()) return;
                        pending = null; status = safeText(reason, 200); appendLogLocked(status); lock.notifyAll();
                    }
                    publish();
                }
            };
            return overlayCallbacks;
        }
    }

    private boolean isCallbackOwnerCurrentLocked(long session, RunControl owner) {
        return owner != null && control == owner && generation == session && !owner.isCancelled();
    }

    private void clearOverlayCallbacksLocked() {
        overlayCallbacks = null;
        overlayCallbackOwner = null;
        overlayCallbackGeneration = -1;
        overlayCallbackApprovalId = "";
    }

    private static String safeText(String value, int limit) {
        if (value == null) return "";
        String clean = value.replace('\u0000', ' ');
        return clean.length() > limit ? clean.substring(0, limit) + "…" : clean;
    }
}
