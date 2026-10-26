package dev.agentos.app;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import dev.agentos.core.AgentRunner;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import dev.agentos.core.TaskResult;

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

    public static final class State {
        public final String status;
        public final boolean running;
        public final boolean paused;
        public final String approval;
        public final String approvalId;
        public final List<String> log;
        public final String summary;

        State(String status, boolean running, boolean paused, String approval, String approvalId,
              List<String> log, String summary) {
            this.status = status;
            this.running = running;
            this.paused = paused;
            this.approval = approval;
            this.approvalId = approvalId;
            this.log = Collections.unmodifiableList(new ArrayList<>(log));
            this.summary = summary;
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
    private RunControl control;
    private PendingApproval pending;
    private String status = "准备就绪";
    private String summary = "";

    private static final class PendingApproval {
        final String id = UUID.randomUUID().toString();
        final String description;
        final long epoch;
        final long deadline = SystemClock.elapsedRealtime() + 60_000L;
        Boolean answer;
        PendingApproval(String description, long epoch) { this.description = description; this.epoch = epoch; }
    }

    private AgentController(Context context) { this.context = context.getApplicationContext(); }

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
            return new State(status, control != null && !control.isCancelled(),
                    control != null && control.isPaused(),
                    pending == null ? "" : pending.description,
                    pending == null ? "" : pending.id, log, summary);
        }
    }

    public void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void start(ModelSettings settings, String task, Set<String> allowedPackages,
                      boolean confirmEachAction) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("请从任务界面启动。");
        }
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) throw new IllegalStateException("请先在系统设置中启用 Agent OS 无障碍服务。");
        if (!isUnlocked()) throw new IllegalStateException("请保持手机亮屏并解锁后再启动。");
        if (settings == null || task == null || task.trim().isEmpty()) {
            throw new IllegalArgumentException("请填写模型设置和任务内容。");
        }
        settings.validate();
        SettingsStore.requirePersonalProfile(context);
        if (task.length() > 6000) throw new IllegalArgumentException("任务内容请控制在 6000 字以内。");
        Set<String> scope = new LinkedHashSet<>(allowedPackages == null
                ? Collections.emptySet() : allowedPackages);
        scope.remove(context.getPackageName());
        if (scope.isEmpty()) throw new IllegalArgumentException("请至少选择一个允许操作的应用。");

        final RunControl active = new RunControl(SettingsStore.currentProfileId(context));
        final long session;
        synchronized (lock) {
            if (control != null) throw new IllegalStateException("已有任务正在运行，请先停止。");
            session = ++generation;
            control = active;
            pending = null;
            log.clear();
            summary = "";
            status = "正在连接模型";
            appendLogLocked("任务已启动；允许操作 " + scope.size() + " 个应用。");
        }
        service.setOverlayCallbacks(callbacksFor(""));
        try {
            service.showOverlay(status, false, "");
        } catch (RuntimeException error) {
            stop();
            throw new IllegalStateException("无法显示任务控制条，请检查无障碍服务状态。");
        }
        publish();

        try { worker.execute(() -> {
            try {
                active.checkCancelled();
                SettingsStore store = new SettingsStore(context);
                SettingsStore.SavedSettings saved = store.load();
                if (!store.validateAllowedPackages(saved).containsAll(scope)
                        || !settings.endpoint.equals(saved.endpoint.trim())
                        || !settings.model.equals(saved.model.trim())
                        || !settings.apiKey.equals(saved.apiKey.trim())
                        || confirmEachAction != saved.confirmEachAction) {
                    finish(session, new TaskResult(TaskResult.Kind.FAILED,
                            "配置或授权已改变，请重新保存配置并选择允许的应用。", "CONFIGURATION_INVALID"));
                    return;
                }
                active.checkCancelled();
                AndroidDeviceTools tools = new AndroidDeviceTools(service, scope, saved.grants,
                        confirmEachAction, (description, run, epoch) -> requestApproval(session, description, run, epoch));
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
                });
                active.checkCancelled();
                runner.run(task.trim(), active);
                // A runner must finish exactly once. This fallback prevents stale UI on a faulty adapter.
                finish(session, "执行已结束，请检查目标应用中的实际结果。", false);
            } catch (InterruptedException interrupted) {
                finish(session, "任务已停止。", false);
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                // Exceptions may contain provider response bodies or input values; do not display them.
                finish(session, "执行中断，请检查模型配置、网络和无障碍服务状态。", false);
            } finally {
                active.cancel();
            }
        }); } catch (java.util.concurrent.RejectedExecutionException busy) {
            active.cancel();
            finish(session, "上一任务仍在释放资源，请稍后重试。", false);
        }
    }

    public void pauseResume() {
        synchronized (lock) {
            if (control == null) return;
            pending = null;
            if (control.isPaused()) {
                if (!isUnlocked()) { stop(); return; }
                control.resume();
                status = "正在重新观察";
                appendLogLocked("任务已继续；旧动作与旧确认已失效。");
            } else {
                control.pause();
                status = "已暂停";
                appendLogLocked("任务已暂停；当前已发出的系统动作可能仍在完成。");
            }
            lock.notifyAll();
        }
        publish();
    }

    public void stop() {
        RunControl cancelled;
        synchronized (lock) {
            if (control == null) return;
            cancelled = control;
            cancelled.cancel();
            control = null;
            ++generation;
            pending = null;
            status = "已停止";
            summary = "任务已停止。已完成的应用操作不会自动撤销。";
            appendLogLocked("任务已停止。");
            lock.notifyAll();
        }
        publish();
    }

    public void approve(String approvalId, boolean approved) {
        synchronized (lock) {
            if (pending == null || control == null || pending.answer != null
                    || control.isPaused() || !pending.id.equals(approvalId)) return;
            if (pending.epoch != control.getExecutionEpoch()
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

    private boolean requestApproval(long session, String description, RunControl run, long requestEpoch)
            throws InterruptedException {
        // The approval belongs to the originating request, never a later resume.
        PendingApproval request = new PendingApproval(safeText(description, 6000), requestEpoch);
        synchronized (lock) {
            run.checkExecutionEpoch(request.epoch);
            if (!isCurrentLocked(session)) throw new InterruptedException("Task changed");
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

    private void finish(long session, String result, boolean normallyEnded) {
        finish(session, new TaskResult(normallyEnded ? TaskResult.Kind.MODEL_REPORT : TaskResult.Kind.FAILED,
                result, ""));
    }

    private void finish(long session, TaskResult result) {
        synchronized (lock) {
            if (!isCurrentLocked(session)) return;
            control.cancel();
            control = null;
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
        main.post(() -> {
            publicationPending.set(false);
            State state = getState();
            AgentAccessibilityService service = AgentAccessibilityService.getInstance();
            if (service != null) {
                try {
                    if (state.running) {
                        service.setOverlayCallbacks(callbacksFor(state.approvalId));
                        service.showOverlay(state.status, state.paused, state.approval);
                    } else { service.hideOverlay(); service.setOverlayCallbacks(null); }
                } catch (RuntimeException error) {
                    if (state.running) stop();
                }
            } else if (state.running) {
                stop();
            }
            for (Listener listener : listeners) listener.onChanged();
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
            summary = ""; log.clear(); status = "准备就绪";
        }
        publish();
    }

    private AgentAccessibilityService.OverlayCallbacks callbacksFor(String approvalId) {
        return new AgentAccessibilityService.OverlayCallbacks() {
            @Override public void onPauseResume() { pauseResume(); }
            @Override public void onStop() { stop(); }
            @Override public void onApprove(boolean approved) { approve(approvalId, approved); }
            @Override public void onDevicePaused(String reason) {
                synchronized (lock) {
                    if (control == null || !control.isPaused()) return;
                    pending = null; status = safeText(reason, 200); appendLogLocked(status); lock.notifyAll();
                }
                publish();
            }
        };
    }

    private static String safeText(String value, int limit) {
        if (value == null) return "";
        String clean = value.replace('\u0000', ' ');
        return clean.length() > limit ? clean.substring(0, limit) + "…" : clean;
    }
}
