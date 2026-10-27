package dev.agentos.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.agentos.core.RunControl;

import java.util.Locale;

/** Native presentation of actual budget counters; no task ownership or execution side effects. */
final class TaskUsageView extends LinearLayout {
    private final TextView heading, counts, times;
    private String taskId;
    private boolean finished;

    TaskUsageView(Context context) {
        super(context);
        requireMain();
        setId(R.id.task_usage);
        setOrientation(VERTICAL);
        setSaveEnabled(false);
        setVisibility(GONE);
        heading = text("本次任务用量", R.id.task_usage_heading, true);
        counts = text("", R.id.task_usage_counts, false);
        times = text("", R.id.task_usage_times, false);
        text("模型等待计入活动时间；暂停、等待确认分别计时。调用次数包含观察及失败调用。",
                R.id.task_usage_explanation, false);
        // A one-second timer must not announce every elapsed second to TalkBack.
        setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
    }

    boolean showsIdentity(AgentController.TaskUsage usage) {
        requireMain();
        return usage == null ? taskId == null
                : usage.taskId.equals(taskId) && usage.finished == finished;
    }

    void render(AgentController.TaskUsage usage) {
        requireMain();
        if (usage == null) {
            taskId = null;
            finished = false;
            setVisibility(GONE);
            replace(counts, ""); replace(times, "");
            return;
        }
        taskId = usage.taskId;
        finished = usage.finished;
        RunControl.BudgetSnapshot used = usage.consumed;
        replace(heading, usage.finished ? "本次任务用量 · 已结束" : "本次任务用量");
        replace(counts, "模型轮次 " + used.modelRounds + " / " + usage.budget.maxModelRounds
                + " · 工具调用 " + used.toolCalls + " / " + usage.budget.maxToolCalls);
        // Display actual over-budget values unchanged. Only remaining allowance is bounded at zero.
        replace(times, "活动 " + duration(used.activeMillis) + " / " + duration(usage.budget.maxActiveMillis)
                + " · 剩余 " + duration(Math.max(0, usage.budget.maxActiveMillis - used.activeMillis))
                + "\n暂停 " + duration(used.pausedMillis) + " · 等待确认 " + duration(used.approvalMillis));
        setVisibility(VISIBLE);
    }

    private TextView text(String value, int id, boolean bold) {
        TextView view = new TextView(getContext());
        view.setId(id);
        view.setSaveEnabled(false);
        view.setText(value);
        view.setTextSize(bold ? 14 : 12);
        view.setTextColor(getContext().getColor(bold ? R.color.agent_text : R.color.agent_muted));
        if (bold) { view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setAccessibilityHeading(true); }
        view.setPadding(0, Math.round(4 * getResources().getDisplayMetrics().density), 0, 0);
        addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return view;
    }

    private static void replace(TextView target, String value) {
        if (!value.contentEquals(target.getText())) target.setText(value);
    }

    private static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Usage presentation requires the UI thread");
    }
}
