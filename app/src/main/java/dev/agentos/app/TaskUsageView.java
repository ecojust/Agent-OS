package dev.agentos.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.agentos.core.RunControl;
import dev.agentos.core.ModelTokenUsage;

import java.util.Locale;

/** Native local-budget and server-reported token presentation; no execution side effects. */
final class TaskUsageView extends LinearLayout {
    private final TextView heading, counts, times, tokensHeading, inputTokens, outputTokens, totalTokens;
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
        tokensHeading = text("", R.id.task_token_usage_heading, true);
        inputTokens = text("", R.id.task_token_usage_input, false);
        outputTokens = text("", R.id.task_token_usage_output, false);
        totalTokens = text("", R.id.task_token_usage_total, false);
        text("按字段汇总服务端有效报告，缺失值不补算；调用不保证送达或计费。此处不是费用或累计预算，“每次回复 Token 上限”另行设置。",
                R.id.task_token_usage_explanation, false);
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
            replace(tokensHeading, ""); replace(inputTokens, ""); replace(outputTokens, ""); replace(totalTokens, "");
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
        ModelTokenUsage tokens = usage.modelTokens;
        String coverage = tokens.calls == 0 ? "尚未请求"
                : tokens.completeness == ModelTokenUsage.Completeness.COMPLETE ? "已登记调用全部有统计"
                : tokens.completeness == ModelTokenUsage.Completeness.PARTIAL ? "部分统计已报告" : "用量未知";
        replace(tokensHeading, "服务端 Token · " + coverage);
        replace(inputTokens, tokenField("输入", tokens.prompt, tokens.calls));
        replace(outputTokens, tokenField("输出", tokens.completion, tokens.calls));
        replace(totalTokens, tokenField("总计", tokens.total, tokens.calls));
        setVisibility(VISIBLE);
    }

    private static String tokenField(String label, ModelTokenUsage.Field value, long calls) {
        if (calls == 0) return label + "：尚未请求";
        String coverage = "（有效报告 " + value.reportedCalls + "/" + calls + " 次调用）";
        if (value.overflowed) return label + "：未知，累计超过可表示范围" + coverage;
        if (value.subtotal == null) return label + "：未知" + coverage;
        return label + (value.reportedCalls == calls ? "：" : "已报告小计：") + value.subtotal + coverage;
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
