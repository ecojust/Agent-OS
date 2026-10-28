package dev.agentos.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.agentos.core.ModelSettings;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ModelProbe;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.TaskDiagnostics;
import dev.agentos.core.TaskActionReceipts;
import dev.agentos.core.VisionModelProbe;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A local, user-initiated task console. No chat text is written to disk or restored after process death. */
public final class MainActivity extends Activity implements AgentController.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService storageWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "agent-os-settings");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean destroyed;
    private boolean ready;
    private boolean busy;
    private boolean storageDamaged;
    private boolean suppressModeChange;
    private boolean modelExpanded = true;
    private boolean budgetExpanded, suppressBudgetChange;
    private String[] budgetTexts = {"30", "100", "600"};
    private boolean hideCurrentResult;
    private boolean wasRunning;
    private SettingsStore store;
    private SettingsStore.SavedSettings draft = new SettingsStore.SavedSettings();
    private AgentController controller;
    private final Map<String, String> appLabels = new LinkedHashMap<>();
    private EditText endpointField, modelField, keyField, maxOutputTokensField, taskField;
    private EditText budgetRounds, budgetTools, budgetSeconds;
    private TextView budgetSummary;
    private LinearLayout budgetFields;
    private Button budgetToggle;
    private TextView serviceState, statusText, configSummary, scopeSummary, messageText, logText, summaryText, approvalText;
    private LinearLayout modelFields, approvalPanel, runningButtons;
    private LinearLayout taskFocusAnchor;
    private ProgressBar progress;
    private TaskUsageView taskUsageView;
    private Button taskDiagnosticsButton;
    private AlertDialog taskDiagnosticsDialog;
    private TaskDiagnostics displayedTaskDiagnostics;
    private Button taskActionReceiptsButton;
    private AlertDialog taskActionReceiptsDialog;
    private TaskActionReceipts displayedTaskActionReceipts;
    private final Runnable taskUsageRefresh = () -> {
        if (!this.resumed || destroyed || taskUsageView == null) return;
        renderTaskUsage(controller.getTaskUsage(), true);
    };
    private Button serviceButton, modelToggle, saveButton, resetButton, appsButton, startButton, pauseButton, stopButton, clearButton, allowButton, denyButton;
    private RadioGroup executionModes;
    private final List<View> configurationViews = new ArrayList<>();
    private String boundApprovalId = "";
    private String acceptedHttpEndpoint = "";
    private UiDraft retainedDraft;
    private RunControl modelProbe;
    private long probeGeneration;
    private enum ProbeKind { TOOL, VISION, SAVED_VISION }
    private ProbeKind displayedVisionKind = ProbeKind.VISION;
    private long configurationGeneration;
    private long savedConfigurationGeneration = -1;
    private SettingsStore.ConfigurationLease savedConfigurationLease;
    private SavedProbeOwner savedProbeOwner;
    private String savedProbeState = "未建立";

    /** Main-thread lifetime; no credentials, response, image or worker are retained. */
    private static final class SavedProbeOwner {
        final RunControl control;
        final long epoch, configurationGeneration;
        final SettingsStore.ConfigurationLease lease;
        SettingsStore.Registration registration;
        SavedVisionProbe.Verification verification;
        Runnable expiry;
        SavedProbeOwner(RunControl control, SettingsStore.ConfigurationLease lease, long generation) {
            this.control = control; this.epoch = control.getExecutionEpoch();
            this.lease = lease; this.configurationGeneration = generation;
        }
    }
    private ProbeKind activeProbeKind;
    private boolean resumed, toolProbeTested, toolVerified, imageProbeTested, syntheticImageVerified;
    private String probeConnection = "未测试", probeMessage = "";
    private int probeResultColor = R.color.agent_muted;
    private AlertDialog probeTrustDialog;
    private Button probeButton, visionProbeButton, cancelProbeButton;
    private TextView probeResult;
    private Button compatibilityButton;
    private final ThreadPoolExecutor diagnosticsWorker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "agent-os-device-report");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private long diagnosticsGeneration;
    private CancellationSignal diagnosticsCancellation;
    private Future<?> diagnosticsFuture;
    private AlertDialog diagnosticsDialog;
    private DeviceCapabilities.Report deviceReport;

    private static final class UiDraft {
        final SettingsStore.SavedSettings settings;
        final String task;
        final String maxOutputTokensText;
        final boolean expanded;
        final boolean budgetExpanded;
        final String[] budgetTexts;
        UiDraft(SettingsStore.SavedSettings settings, String task, String maxOutputTokensText, boolean expanded,
                boolean budgetExpanded, String[] budgetTexts) {
            this.settings = settings;
            this.task = task;
            this.maxOutputTokensText = maxOutputTokensText;
            this.expanded = expanded;
            this.budgetExpanded = budgetExpanded;
            this.budgetTexts = budgetTexts.clone();
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        controller = AgentController.get(this);
        diagnosticsWorker.allowCoreThreadTimeOut(true);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof UiDraft) retainedDraft = (UiDraft) retained;
        buildInterface();
        loadConfiguration();
    }

    @Override protected void onStart() {
        super.onStart();
        controller.addListener(this);
        renderState();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        renderState();
        renderTaskUsage(controller.getTaskUsage(), true);
    }

    @Override protected void onPause() {
        resumed = false;
        closeTaskDiagnostics(true);
        closeTaskActionReceipts(true);
        main.removeCallbacks(taskUsageRefresh);
        dismissProbeTrustDialog();
        invalidateSavedProbe("已失效，请重新测试");
        renderProbeResult();
        super.onPause();
    }

    @Override protected void onStop() {
        closeDeviceDiagnostics(true);
        cancelModelProbe("已离开配置页面，测试已取消。", false);
        invalidateSavedProbe("已失效，请重新测试");
        renderProbeResult();
        controller.removeListener(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        closeTaskDiagnostics(true);
        closeTaskActionReceipts(true);
        main.removeCallbacks(taskUsageRefresh);
        closeDeviceDiagnostics(true);
        cancelModelProbe("测试已取消。", false);
        invalidateSavedProbe("已失效，请重新测试");
        destroyed = true;
        diagnosticsWorker.shutdownNow();
        storageWorker.shutdown();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        if (!ready || storageDamaged) return null;
        // Rotation retains these values in process memory only, never an instance-state file.
        return new UiDraft(readDraft(), taskField.getText().toString(), maxOutputTokensField.getText().toString(),
                modelExpanded, budgetExpanded, budgetTexts);
    }

    @Override public void onChanged() {
        if (!destroyed) renderState();
    }

    private void buildInterface() {
        LinearLayout root = column();
        taskFocusAnchor = root;
        // Ending an edit must have a non-editor destination. Otherwise clearFocus
        // can select another input and recreate the keyboard while a task starts.
        root.setFocusableInTouchMode(true);
        root.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setBackgroundColor(color(R.color.agent_background));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout content = column();
        content.setPadding(dp(18), dp(20), dp(18), dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_agent_os);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout heading = column();
        heading.setPadding(dp(12), 0, 0, 0);
        heading.addView(text("Agent OS", 27, R.color.agent_text, true));
        heading.addView(text("让任务在手机上完成", 13, R.color.agent_muted, false));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(header);
        TextView intro = text("在已解锁的手机上执行。你可以看到每一步，并随时接管或停止。", 14, R.color.agent_muted, false);
        marginAdd(content, intro, 12);

        messageText = text("", 14, R.color.agent_error, false);
        messageText.setPadding(dp(14), dp(12), dp(14), dp(12));
        messageText.setBackground(shape(color(R.color.agent_error_surface), 14, 0));
        messageText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        messageText.setVisibility(View.GONE);
        marginAdd(content, messageText, 14);

        LinearLayout service = card(content);
        service.addView(sectionTitle("手机操作服务", R.drawable.ic_shield));
        serviceState = text("正在检查服务…", 14, R.color.agent_muted, false);
        marginAdd(service, serviceState, 8);
        serviceButton = button("前往开启无障碍服务", R.drawable.ic_shield, false);
        serviceButton.setId(R.id.open_accessibility);
        serviceButton.setOnClickListener(view -> explainAccessibility());
        marginAdd(service, serviceButton, 10);
        TextView capabilities = text("首版支持授权应用的结构化界面读取、节点点击、输入与滚动。截图和坐标手势暂未启用；自绘、密码或无法观察的界面不会自动改用坐标点击。", 12, R.color.agent_muted, false);
        marginAdd(service, capabilities, 8);
        compatibilityButton = button("设备与兼容性", R.drawable.ic_shield, false);
        compatibilityButton.setId(R.id.device_compatibility);
        compatibilityButton.setOnClickListener(view -> showDeviceDiagnostics());
        marginAdd(service, compatibilityButton, 8);

        LinearLayout configuration = card(content);
        modelToggle = button("模型连接 · 展开设置", R.drawable.ic_settings, false);
        modelToggle.setId(R.id.configuration_toggle);
        modelToggle.setOnClickListener(view -> { modelExpanded = !modelExpanded; updateModelPanel(); });
        configuration.addView(modelToggle, new LinearLayout.LayoutParams(-1, -2));
        configSummary = text("配置模型地址、名称和密钥", 13, R.color.agent_muted, false);
        marginAdd(configuration, configSummary, 4);
        modelFields = column();
        marginAdd(configuration, modelFields, 10);
        endpointField = field(modelFields, "服务地址", "https://你的服务地址/v1", R.id.settings_endpoint, false, 2048);
        endpointField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        modelField = field(modelFields, "模型名称", "填写支持工具调用的模型名称", R.id.settings_model, false, 200);
        keyField = field(modelFields, "API 密钥", "本地服务不需要密钥时可留空", R.id.settings_api_key, true, 8192);
        Button reveal = button("显示 / 隐藏密钥", 0, false);
        reveal.setOnClickListener(view -> {
            boolean masked = keyField.getTransformationMethod() instanceof PasswordTransformationMethod;
            keyField.setTransformationMethod(masked ? null : PasswordTransformationMethod.getInstance());
            keyField.setSelection(keyField.length());
        });
        configurationViews.add(reveal);
        marginAdd(modelFields, reveal, 4);
        maxOutputTokensField = field(modelFields, "每次回复 Token 上限", "2048（128–8192）",
                R.id.settings_max_output_tokens, false, 24);
        maxOutputTokensField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        maxOutputTokensField.setSelectAllOnFocus(true);
        marginAdd(modelFields, text("用于每次模型回复和测试请求。通过 max_tokens 请求服务限制输出；服务可能拒绝或不遵循此值，不包括输入 Token，也不是任务总量或费用上限。", 12,
                R.color.agent_muted, false), 6);
        marginAdd(modelFields, text("使用兼容 Chat Completions 的接口。界面文字和任务会发送至此地址，密钥仅加密保存在本机；公网地址必须使用 HTTPS。", 12, R.color.agent_muted, false), 8);
        probeButton = button("测试模型连接 / 工具调用", R.drawable.ic_settings, false);
        probeButton.setId(R.id.test_model);
        probeButton.setOnClickListener(view -> testModel());
        configurationViews.add(probeButton);
        marginAdd(modelFields, probeButton, 12);
        visionProbeButton = button("测试图像理解", R.drawable.ic_settings, false);
        visionProbeButton.setId(R.id.test_model_vision);
        visionProbeButton.setOnClickListener(view -> startModelProbe(displayedVisionKind));
        configurationViews.add(visionProbeButton);
        marginAdd(modelFields, visionProbeButton, 8);
        marginAdd(modelFields, text("只上传自动生成的合成色块，不读取屏幕或发送任务内容。已保存配置会建立短期诊断记录；未保存的修改仅测试当前填写内容。记录不开放截图能力。", 12,
                R.color.agent_muted, false), 6);
        cancelProbeButton = button("取消测试", R.drawable.ic_stop, false);
        cancelProbeButton.setId(R.id.cancel_model_test);
        cancelProbeButton.setOnClickListener(view -> cancelModelProbe("测试已取消，未操作手机应用。", true));
        cancelProbeButton.setVisibility(View.GONE);
        marginAdd(modelFields, cancelProbeButton, 4);
        probeResult = text("网络连接：未测试\n工具调用：未测试\n图像能力：未验证", 12, R.color.agent_muted, false);
        probeResult.setId(R.id.model_test_result);
        probeResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        marginAdd(modelFields, probeResult, 8);
        marginAdd(modelFields, text("测试仅发送固定的合成请求，不读取或操作手机应用，也不发送你的任务或界面内容。可能产生模型调用费用；测试不会保存配置。", 12, R.color.agent_muted, false), 6);
        saveButton = button("保存模型与授权设置", R.drawable.ic_check, false);
        saveButton.setId(R.id.save_settings);
        saveButton.setOnClickListener(view -> saveConfiguration(false));
        configurationViews.add(saveButton);
        marginAdd(modelFields, saveButton, 12);
        resetButton = button("重置本机配置", 0, false);
        resetButton.setId(R.id.reset_settings);
        resetButton.setTextColor(color(R.color.agent_error));
        resetButton.setOnClickListener(view -> confirmReset());
        marginAdd(modelFields, resetButton, 4);

        LinearLayout scope = card(content);
        scope.addView(sectionTitle("允许操作的应用", R.drawable.ic_apps));
        scopeSummary = text("尚未选择应用", 14, R.color.agent_muted, false);
        marginAdd(scope, scopeSummary, 8);
        appsButton = button("选择应用", R.drawable.ic_apps, false);
        appsButton.setId(R.id.choose_apps);
        appsButton.setOnClickListener(view -> loadApps());
        configurationViews.add(appsButton);
        marginAdd(scope, appsButton, 8);
        marginAdd(scope, text("仅限当前个人资料。应用重新安装或身份变化后，需要重新授权。", 12, R.color.agent_muted, false), 8);
        marginAdd(scope, text("执行模式", 15, R.color.agent_text, true), 14);
        executionModes = new RadioGroup(this);
        executionModes.setId(R.id.confirm_actions);
        executionModes.setOrientation(RadioGroup.VERTICAL);
        executionModes.setSaveEnabled(false);
        addExecutionMode("逐步确认（默认）", R.id.execution_mode_confirm_all);
        addExecutionMode("连续导航", R.id.execution_mode_continuous_navigation);
        executionModes.check(R.id.execution_mode_confirm_all);
        executionModes.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressModeChange) return;
            if (!ready || busy || storageDamaged || modelProbe != null || controller.getState().running
                    || checkedId != R.id.execution_mode_confirm_all && checkedId != R.id.execution_mode_continuous_navigation) {
                bindExecutionMode();
                return;
            }
            configurationEdited();
            draft.executionMode = checkedId == R.id.execution_mode_continuous_navigation
                    ? ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION : ActionConfirmationPolicy.Mode.CONFIRM_ALL;
            renderProbeResult();
            renderState();
        });
        marginAdd(scope, executionModes, 4);
        TextView modeExplanation = text("逐步确认会先确认所有界面操作。连续导航仅允许打开已授权应用、滚动和回到桌面免逐次确认；返回、点击和输入仍须确认。\n由你在本机选择，保存或开始任务后生效，模型不能更改。拒绝后不会换另一种方式绕过该动作。", 12, R.color.agent_muted, false);
        modeExplanation.setId(R.id.execution_mode_explanation);
        marginAdd(scope, modeExplanation, 4);

        LinearLayout budget = card(content);
        budgetToggle = button("任务预算 · 展开设置", R.drawable.ic_settings, false);
        budgetToggle.setId(R.id.task_budget_toggle);
        budgetToggle.setOnClickListener(view -> {
            if (!canEditBudget()) return;
            budgetExpanded = !budgetExpanded;
            updateBudgetPanel();
        });
        configurationViews.add(budgetToggle);
        budget.addView(budgetToggle, new LinearLayout.LayoutParams(-1, -2));
        budgetSummary = text("", 14, R.color.agent_muted, false);
        budgetSummary.setId(R.id.task_budget_summary);
        marginAdd(budget, budgetSummary, 6);
        boolean compactBudget = getResources().getConfiguration().screenWidthDp < 400
                || getResources().getConfiguration().fontScale >= 1.3f;
        budgetFields = compactBudget ? column() : row();
        budgetFields.setId(R.id.task_budget_fields);
        budgetRounds = budgetField(budgetFields, "模型轮次", R.id.task_budget_rounds);
        budgetTools = budgetField(budgetFields, "工具调用", R.id.task_budget_tools);
        budgetSeconds = budgetField(budgetFields, "活动时间（秒）", R.id.task_budget_seconds);
        marginAdd(budget, budgetFields, 8);
        TextView budgetExplanation = text("达到任一上限后任务停止。暂停和确认等待不计活动时间；次数不会因暂停而重置。", 12, R.color.agent_muted, false);
        budgetExplanation.setId(R.id.task_budget_explanation);
        marginAdd(budget, budgetExplanation, 6);
        bindBudgetTexts(budgetTexts);
        updateBudgetPanel();

        LinearLayout task = card(content);
        task.addView(sectionTitle("这次想完成什么？", R.drawable.ic_play));
        taskField = new EditText(this);
        taskField.setId(R.id.task_prompt);
        decorateField(taskField);
        taskField.setHint("例如：打开设置，找到字体大小设置页，停在那里等我确认。");
        taskField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        taskField.setGravity(Gravity.TOP | Gravity.START);
        taskField.setMinLines(3);
        taskField.setMaxLines(8);
        taskField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6000)});
        marginAdd(task, taskField, 12);
        marginAdd(task, text("任务使用上方所选预算。任务和界面内容不保存为历史文件。", 12, R.color.agent_muted, false), 10);

        LinearLayout results = card(content);
        results.addView(sectionTitle("当前任务", R.drawable.ic_agent_os));
        statusText = text("正在读取本机配置…", 16, R.color.agent_text, true);
        statusText.setId(R.id.status_text);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        marginAdd(results, statusText, 10);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(color(R.color.agent_primary)));
        progress.setVisibility(View.GONE);
        marginAdd(results, progress, 8);
        taskUsageView = new TaskUsageView(this);
        marginAdd(results, taskUsageView, 12);

        approvalPanel = column();
        approvalPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        approvalPanel.setBackground(shape(color(R.color.agent_soft), 12, 0));
        approvalText = text("", 14, R.color.agent_text, false);
        approvalPanel.addView(approvalText);
        allowButton = button("允许这一次", R.drawable.ic_check, true);
        allowButton.setId(R.id.approval_allow);
        denyButton = button("拒绝当前动作", R.drawable.ic_stop, false);
        denyButton.setId(R.id.approval_deny);
        marginAdd(approvalPanel, allowButton, 10);
        marginAdd(approvalPanel, denyButton, 4);
        approvalPanel.setVisibility(View.GONE);
        marginAdd(results, approvalPanel, 12);
        summaryText = text("", 14, R.color.agent_text, false);
        summaryText.setId(R.id.summary_text);
        summaryText.setTextIsSelectable(true);
        summaryText.setVisibility(View.GONE);
        marginAdd(results, summaryText, 12);
        logText = text("发起任务后，这里会显示执行阶段。", 12, R.color.agent_muted, false);
        logText.setId(R.id.task_log);
        logText.setTextIsSelectable(true);
        marginAdd(results, logText, 12);
        taskActionReceiptsButton = button("操作回执", R.drawable.ic_check, false);
        taskActionReceiptsButton.setId(R.id.task_action_receipts);
        taskActionReceiptsButton.setContentDescription("查看本次任务的本机操作回执与未验证状态");
        taskActionReceiptsButton.setOnClickListener(view -> showTaskActionReceipts());
        marginAdd(results, taskActionReceiptsButton, 8);
        taskDiagnosticsButton = button("任务诊断", R.drawable.ic_settings, false);
        taskDiagnosticsButton.setId(R.id.task_diagnostics);
        taskDiagnosticsButton.setContentDescription("预览本次任务的脱敏状态与用量报告");
        taskDiagnosticsButton.setOnClickListener(view -> showTaskDiagnostics());
        marginAdd(results, taskDiagnosticsButton, 8);
        clearButton = button("清空本页任务内容", 0, false);
        clearButton.setId(R.id.clear_task);
        clearButton.setOnClickListener(view -> {
            if (controller.getState().running) return;
            taskField.setText("");
            controller.clearCompletedTask();
            hideCurrentResult = true;
            renderState();
        });
        marginAdd(results, clearButton, 8);

        LinearLayout actions = column();
        actions.setBackgroundColor(color(R.color.agent_surface));
        actions.setPadding(dp(18), dp(8), dp(18), dp(10));
        startButton = button("开始执行任务", R.drawable.ic_play, true);
        startButton.setId(R.id.start_task);
        startButton.setOnClickListener(view -> saveConfiguration(true));
        actions.addView(startButton, new LinearLayout.LayoutParams(-1, -2));
        runningButtons = row();
        pauseButton = button("暂停", R.drawable.ic_pause, false);
        pauseButton.setId(R.id.pause_task);
        pauseButton.setContentDescription("暂停任务并接管手机");
        pauseButton.setOnClickListener(view -> controller.pauseResume());
        stopButton = button("停止", R.drawable.ic_stop, false);
        stopButton.setId(R.id.stop_task);
        stopButton.setTextColor(color(R.color.agent_error));
        stopButton.setOnClickListener(view -> controller.stop());
        LinearLayout.LayoutParams pauseLayout = new LinearLayout.LayoutParams(0, -2, 1);
        pauseLayout.setMarginEnd(dp(8));
        runningButtons.addView(pauseButton, pauseLayout);
        runningButtons.addView(stopButton, new LinearLayout.LayoutParams(0, -2, 1));
        runningButtons.setVisibility(View.GONE);
        actions.addView(runningButtons);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        root.requestFocus();
        watchModelConfiguration();
        updateModelPanel();
        renderState();
    }

    private void loadConfiguration() {
        busy = true;
        storageWorker.execute(() -> {
            if (destroyed) return;
            SettingsStore loadedStore = null;
            SettingsStore.SavedSettings loaded = null;
            SettingsStore.ConfigurationLease loadedLease = null;
            String failure = "";
            Set<String> revoked = new LinkedHashSet<>();
            try {
                SettingsStore.requirePersonalProfile(getApplicationContext());
                loadedStore = new SettingsStore(getApplicationContext());
                SettingsStore.LoadedSettings persisted = loadedStore.loadWithLease();
                loaded = persisted.settings;
                loadedLease = persisted.lease;
                Set<String> valid = loadedStore.validateAllowedPackages(loaded);
                revoked.addAll(loaded.allowedPackages);
                revoked.removeAll(valid);
                loaded.allowedPackages.retainAll(valid);
                loaded.grants.keySet().retainAll(valid);
            } catch (IOException error) {
                failure = "无法读取已保存的配置。原副本已保留，请在模型连接中选择“重置本机配置”后重新设置。";
            } catch (RuntimeException error) {
                failure = "当前资料无法执行任务。首版仅支持已解锁的设备所有者个人资料。";
            }
            SettingsStore nextStore = loadedStore;
            SettingsStore.SavedSettings next = loaded;
            SettingsStore.ConfigurationLease nextLease = loadedLease;
            String errorText = failure;
            main.post(() -> {
                if (destroyed) return;
                store = nextStore;
                busy = false;
                ready = nextStore != null;
                storageDamaged = next == null;
                if (next != null) {
                    boolean retained = retainedDraft != null && next.profileId.equals(retainedDraft.settings.profileId);
                    draft = next;
                    if (retainedDraft != null && next.profileId.equals(retainedDraft.settings.profileId)) {
                        draft = retainedDraft.settings;
                        taskField.setText(retainedDraft.task);
                    }
                    bindDraft();
                    if (retainedDraft != null && next.profileId.equals(retainedDraft.settings.profileId)) {
                        maxOutputTokensField.setText(retainedDraft.maxOutputTokensText);
                        budgetTexts = retainedDraft.budgetTexts.clone();
                        budgetExpanded = retainedDraft.budgetExpanded;
                        bindBudgetTexts(budgetTexts);
                        updateBudgetPanel();
                    }
                    modelExpanded = retainedDraft != null ? retainedDraft.expanded : next.endpoint.trim().isEmpty() || next.model.trim().isEmpty();
                    retainedDraft = null;
                    // Bind all fields first: their TextWatchers invalidate prior baselines.
                    savedConfigurationLease = nextLease;
                    savedConfigurationGeneration = !retained && revoked.isEmpty() && nextLease != null
                            ? configurationGeneration : -1;
                    if (!revoked.isEmpty()) showMessage("有 " + revoked.size() + " 个应用的安装身份已变化，授权已取消。需要继续操作时，请重新选择这些应用。", true);
                } else showMessage(errorText, true);
                updateModelPanel();
                renderState();
            });
        });
    }

    private void addExecutionMode(String label, int id) {
        RadioButton option = new RadioButton(this);
        option.setId(id);
        option.setText(label);
        option.setTextSize(16);
        option.setTextColor(color(R.color.agent_text));
        option.setButtonTintList(ColorStateList.valueOf(color(R.color.agent_primary)));
        option.setMinHeight(dp(48));
        option.setSaveEnabled(false);
        option.setEnabled(false);
        executionModes.addView(option, new RadioGroup.LayoutParams(-1, -2));
        // Disabling a RadioGroup alone does not disable its individual choices.
        configurationViews.add(option);
    }

    private void bindExecutionMode() {
        suppressModeChange = true;
        executionModes.check(draft.executionMode == ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION
                ? R.id.execution_mode_continuous_navigation : R.id.execution_mode_confirm_all);
        suppressModeChange = false;
    }

    private void bindDraft() {
        endpointField.setText(draft.endpoint);
        keyField.setText(draft.apiKey);
        modelField.setText(draft.model);
        maxOutputTokensField.setText(String.valueOf(draft.maxOutputTokens));
        keyField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        bindExecutionMode();
        budgetTexts = new String[]{String.valueOf(draft.taskBudget.maxModelRounds), String.valueOf(draft.taskBudget.maxToolCalls),
                String.valueOf(draft.taskBudget.maxActiveMillis / 1000)};
        bindBudgetTexts(budgetTexts);
        updateBudgetPanel();
        refreshScopeSummary();
        configSummary.setText(draft.model.trim().isEmpty() ? "配置模型地址、名称和密钥" : "已保存模型 · " + draft.model);
    }

    private void saveConfiguration(boolean startTask) {
        if (!ready || busy || destroyed || modelProbe != null || controller.getState().running) return;
        invalidateSavedProbe("已失效，请重新测试");
        dismissProbeTrustDialog();
        renderProbeResult();
        if (storageDamaged) { showMessage("原配置无法读取。请先明确重置本机配置，再保存新的设置。", true); return; }
        Integer selectedMaxOutputTokens = readMaxOutputTokens(true);
        if (selectedMaxOutputTokens == null) {
            modelExpanded = true;
            updateModelPanel();
            showMessage("每次回复 Token 上限须为 128–8192 的整数。", true);
            return;
        }
        TaskBudget selectedBudget = readBudget(true);
        if (selectedBudget == null) {
            budgetExpanded = true;
            updateBudgetPanel();
            showMessage("请修正任务预算后再保存或开始。", true);
            return;
        }
        SettingsStore.SavedSettings settings = readDraft();
        settings.taskBudget = selectedBudget;
        settings.maxOutputTokens = selectedMaxOutputTokens;
        ModelSettings model = new ModelSettings(settings.endpoint, settings.apiKey, settings.model, settings.maxOutputTokens);
        try { model.validate(); }
        catch (IllegalArgumentException invalid) {
            showMessage(invalid.getMessage(), true);
            modelExpanded = true;
            updateModelPanel();
            return;
        }
        if (model.endpoint.toLowerCase(Locale.ROOT).startsWith("http://") && !model.endpoint.equals(acceptedHttpEndpoint)) {
            new AlertDialog.Builder(this).setTitle("使用未加密的本地连接？")
                    .setMessage("此地址使用 HTTP。任务、界面文字和密钥在传输时不会加密，请仅连接你信任的本机或局域网服务。你也可以改用 HTTPS。")
                    .setNegativeButton("返回修改", null)
                    .setPositiveButton("信任此连接", (dialog, which) -> {
                        if (destroyed || controller.getState().running) return;
                        acceptedHttpEndpoint = model.endpoint;
                        saveConfiguration(startTask);
                    }).show();
            return;
        }
        String goal = taskField.getText().toString().trim();
        if (startTask) {
            if (goal.isEmpty()) { taskField.setError("请先描述要完成的任务"); taskField.requestFocus(); return; }
            if (settings.allowedPackages.isEmpty()) { showMessage("请先选择允许操作的应用。", true); return; }
            if (AgentAccessibilityService.getInstance() == null) { showMessage("请先在系统设置中手动启用 Agent OS 手机操作服务。", true); return; }
        }
        final long savedUiGeneration = configurationGeneration;
        busy = true;
        showMessage("正在加密保存设置…", false);
        renderState();
        storageWorker.execute(() -> {
            if (destroyed) return;
            String failure = "";
            Set<String> valid = new LinkedHashSet<>();
            SettingsStore.ConfigurationLease savedLease = null;
            try {
                valid = store.validateAllowedPackages(settings);
                if (!valid.equals(settings.allowedPackages)) throw new IOException("应用安装身份已变化，请重新选择允许操作的应用。");
                savedLease = store.save(settings);
            } catch (IOException | RuntimeException error) {
                failure = "无法保存或验证配置。请检查当前资料和应用授权；若配置已损坏，请先重置后重新填写。";
            }
            String errorText = failure;
            Set<String> allowed = valid;
            SettingsStore.ConfigurationLease configurationLease = savedLease;
            main.post(() -> {
                if (destroyed) return;
                busy = false;
                if (!errorText.isEmpty()) { showMessage(errorText, true); renderState(); return; }
                draft = settings;
                savedConfigurationLease = configurationLease;
                savedConfigurationGeneration = savedUiGeneration;
                configSummary.setText("已保存模型 · " + model.model);
                if (!startTask) { showMessage("设置已加密保存在当前个人资料中。", false); renderState(); return; }
                try {
                    // Start is only invoked from this still-live Activity after the save succeeds.
                    hideCurrentResult = false;
                    dismissOwnTaskKeyboard();
                    // Request dismissal before the worker starts observing. This is
                    // asynchronous; the device's final obstruction guard still applies.
                    controller.start(model, goal, allowed, settings.executionMode, settings.taskBudget, configurationLease);
                    messageText.setVisibility(View.GONE);
                } catch (RuntimeException error) {
                    showMessage("任务未启动。请确认手机已解锁、操作服务已开启，且没有其他任务运行。", true);
                }
                renderState();
            });
        });
    }

    /** End this Activity's edit without transferring focus to another input. */
    void dismissOwnTaskKeyboard() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Keyboard dismissal requires the UI thread");
        View previous = getCurrentFocus();
        if (taskFocusAnchor == null || !taskFocusAnchor.requestFocus() || !taskFocusAnchor.isFocused()) {
            throw new IllegalStateException("The task screen could not leave its editor");
        }
        View tokenOwner = previous == null ? taskFocusAnchor : previous;
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(tokenOwner.getWindowToken(), 0);
        if (getWindow().getInsetsController() != null) getWindow().getInsetsController().hide(WindowInsets.Type.ime());
    }

    private SettingsStore.SavedSettings readDraft() {
        SettingsStore.SavedSettings copy = new SettingsStore.SavedSettings();
        copy.profileId = draft.profileId;
        copy.endpoint = endpointField.getText().toString().trim();
        copy.apiKey = keyField.getText().toString().trim();
        copy.model = modelField.getText().toString().trim();
        Integer selectedMaxOutputTokens = readMaxOutputTokens(false);
        // Invalid editor text is retained separately by UiDraft, never saved as
        // configuration authority or silently substituted for a new request.
        copy.maxOutputTokens = selectedMaxOutputTokens == null ? draft.maxOutputTokens : selectedMaxOutputTokens;
        copy.confirmEachAction = true;
        copy.executionMode = draft.executionMode;
        copy.taskBudget = draft.taskBudget;
        copy.allowedPackages = new LinkedHashSet<>(draft.allowedPackages);
        copy.grants = new LinkedHashMap<>(draft.grants);
        return copy;
    }

    private Integer readMaxOutputTokens(boolean reportErrors) {
        String value = maxOutputTokensField.getText().toString().trim();
        Integer parsed = null;
        if (value.matches("[0-9]{1,4}")) {
            int number = Integer.parseInt(value);
            if (number >= ModelSettings.MIN_MAX_OUTPUT_TOKENS && number <= ModelSettings.MAX_MAX_OUTPUT_TOKENS)
                parsed = number;
        }
        if (reportErrors) maxOutputTokensField.setError(parsed == null
                ? "每次回复 Token 上限须为 128–8192 的整数。" : null);
        return parsed;
    }

    private boolean canEditBudget() {
        return ready && !busy && !destroyed && !storageDamaged && modelProbe == null && !controller.getState().running;
    }

    private EditText budgetField(LinearLayout parent, String label, int id) {
        LinearLayout cell = column();
        cell.setPadding(0, 0, dp(8), 0);
        cell.addView(text(label, 12, R.color.agent_muted, false));
        EditText field = new EditText(this);
        field.setId(id);
        decorateField(field);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setSaveEnabled(false);
        field.setEnabled(false);
        cell.addView(field, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams cellLayout = parent.getOrientation() == LinearLayout.VERTICAL
                ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getOrientation() == LinearLayout.VERTICAL) cellLayout.bottomMargin = dp(6);
        parent.addView(cell, cellLayout);
        configurationViews.add(field);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                if (suppressBudgetChange) return;
                if (!canEditBudget()) { bindBudgetTexts(budgetTexts); return; }
                configurationEdited();
                field.setError(null);
                budgetTexts = new String[]{budgetRounds.getText().toString(), budgetTools.getText().toString(), budgetSeconds.getText().toString()};
                updateBudgetSummary();
                renderProbeResult();
                renderState();
            }
        });
        return field;
    }

    private void bindBudgetTexts(String[] values) {
        suppressBudgetChange = true;
        try {
            EditText[] fields = {budgetRounds, budgetTools, budgetSeconds};
            for (int index = 0; index < fields.length; index++) {
                if (fields[index] != null) { fields[index].setText(values[index]); fields[index].setError(null); }
            }
        } finally { suppressBudgetChange = false; }
        updateBudgetSummary();
    }

    private TaskBudget readBudget(boolean reportErrors) {
        Integer rounds = budgetValue(budgetRounds, TaskBudget.MAX_MODEL_ROUNDS, reportErrors);
        Integer tools = budgetValue(budgetTools, TaskBudget.MAX_TOOL_CALLS, reportErrors);
        Integer seconds = budgetValue(budgetSeconds, (int) (TaskBudget.MAX_ACTIVE_MILLIS / 1000), reportErrors);
        return rounds == null || tools == null || seconds == null ? null : new TaskBudget(rounds, tools, seconds * 1000L);
    }

    private Integer budgetValue(EditText field, int maximum, boolean reportErrors) {
        if (field == null) return null;
        String raw = field.getText().toString().trim();
        try {
            if (raw.length() > 10 || !raw.matches("[0-9]+")) throw new NumberFormatException();
            int value = Integer.parseInt(raw);
            if (value < 1 || value > maximum) throw new NumberFormatException();
            if (reportErrors) field.setError(null);
            return value;
        } catch (NumberFormatException invalid) {
            if (reportErrors) field.setError("请输入 1–" + maximum + " 的整数");
            return null;
        }
    }

    private void updateBudgetSummary() {
        if (budgetSummary == null) return;
        TaskBudget value = readBudget(false);
        budgetSummary.setText(value == null ? "预算待修正：请填写范围内的整数"
                : value.maxModelRounds + " 轮模型 · " + value.maxToolCalls + " 次工具 · " + (value.maxActiveMillis / 1000) + " 秒活动");
    }

    private void updateBudgetPanel() {
        budgetFields.setVisibility(budgetExpanded ? View.VISIBLE : View.GONE);
        budgetToggle.setText(budgetExpanded ? "任务预算 · 收起设置" : "任务预算 · 展开设置");
        updateBudgetSummary();
    }

    private void loadApps() {
        if (!ready || busy || destroyed || modelProbe != null || controller.getState().running || storageDamaged) return;
        busy = true;
        appsButton.setText("正在读取应用…");
        renderState();
        storageWorker.execute(() -> {
            if (destroyed) return;
            try {
                SettingsStore.requirePersonalProfile(getApplicationContext());
                List<AndroidDeviceTools.AppInfo> choices = new ArrayList<>(AndroidDeviceTools.installedApps(getApplicationContext()));
                choices.removeIf(app -> app.packageName.equals(getPackageName()));
                Collator collator = Collator.getInstance();
                choices.sort((a, b) -> collator.compare(a.label, b.label));
                main.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    appsButton.setText("选择应用");
                    renderState();
                    if (choices.isEmpty()) { showMessage("没有找到当前资料中可授权的应用。", true); return; }
                    for (AndroidDeviceTools.AppInfo app : choices) appLabels.put(app.packageName, app.label);
                    showAppPicker(choices);
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    appsButton.setText("选择应用");
                    showMessage("无法读取当前个人资料的应用，请解锁设备后重试。", true);
                    renderState();
                });
            }
        });
    }

    private void showAppPicker(List<AndroidDeviceTools.AppInfo> choices) {
        LinearLayout body = column();
        body.setPadding(dp(20), dp(8), dp(20), 0);
        EditText search = new EditText(this);
        search.setId(R.id.app_search);
        decorateField(search);
        search.setSingleLine(true);
        search.setHint("搜索应用名称或包名");
        body.addView(search, new LinearLayout.LayoutParams(-1, -2));
        ListView list = new ListView(this);
        list.setId(R.id.app_choices);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setDividerHeight(0);
        int displayHeight = getResources().getDisplayMetrics().heightPixels;
        body.addView(list, new LinearLayout.LayoutParams(-1, Math.max(dp(144), Math.min(dp(380), displayHeight / 3))));
        Set<String> selected = new LinkedHashSet<>(draft.allowedPackages);
        List<AndroidDeviceTools.AppInfo> visible = new ArrayList<>();
        Runnable filter = () -> {
            String needle = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            List<String> labels = new ArrayList<>();
            for (AndroidDeviceTools.AppInfo app : choices) {
                if (needle.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(needle) || app.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                    visible.add(app);
                    labels.add(app.label + "\n" + app.packageName);
                }
            }
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels));
            for (int i = 0; i < visible.size(); i++) list.setItemChecked(i, selected.contains(visible.get(i).packageName));
        };
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= visible.size()) return;
            String packageName = visible.get(position).packageName;
            if (list.isItemChecked(position)) selected.add(packageName); else selected.remove(packageName);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        filter.run();
        new AlertDialog.Builder(this).setTitle("允许 Agent 操作哪些应用？").setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认选择", (dialog, which) -> authorizeSelection(selected)).show();
    }

    private void authorizeSelection(Set<String> packages) {
        if (destroyed || busy || controller.getState().running) return;
        busy = true;
        renderState();
        storageWorker.execute(() -> {
            if (destroyed) return;
            try {
                Map<String, SettingsStore.AppGrant> grants = store.grantPackages(packages);
                main.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    configurationEdited();
                    draft.allowedPackages = new LinkedHashSet<>(grants.keySet());
                    draft.grants = new LinkedHashMap<>(grants);
                    refreshScopeSummary();
                    renderProbeResult();
                    showMessage("已选择 " + grants.size() + " 个应用。保存设置或开始任务时会保存本次授权。", false);
                    renderState();
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    showMessage("应用安装身份已变化或无法读取。请重新打开应用列表再选择。", true);
                    renderState();
                });
            }
        });
    }

    private void explainAccessibility() {
        if (destroyed) return;
        new AlertDialog.Builder(this).setTitle("开启手机操作服务")
                .setMessage("Agent OS 使用无障碍服务读取你授权的应用界面，并执行节点点击、输入和结构化滚动。相关界面文字会发送至你配置的模型服务。\n\n默认逐步确认；选择连续导航后，仅启动已授权应用、滚动和回到桌面免逐次确认，点击、输入和返回仍需确认。截图外发和坐标手势尚未开放。\n\n服务需由你在系统设置中手动开启。任务运行时会显示控制条；锁屏会停止任务，不会自行恢复。此服务用于自动化任务。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往系统设置", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (RuntimeException error) { showMessage("无法打开设置。请手动进入系统设置 → 无障碍，找到 Agent OS。", true); }
                }).show();
    }

    private void confirmReset() {
        if (store == null || busy || modelProbe != null || controller.getState().running) return;
        new AlertDialog.Builder(this).setTitle("重置本机配置？")
                .setMessage("会删除本机保存的模型地址、密钥、模型名称和应用授权，并重置执行模式和任务预算。此操作不会删除目标应用的数据，也不会删除已发送到模型服务的数据。")
                .setNegativeButton("保留配置", null)
                .setPositiveButton("删除并重新配置", (dialog, which) -> {
                    if (destroyed || controller.getState().running) return;
                    invalidateSavedProbe("已失效，请重新测试");
                    savedConfigurationLease = null;
                    savedConfigurationGeneration = -1;
                    dismissProbeTrustDialog();
                    busy = true;
                    renderProbeResult();
                    renderState();
                    storageWorker.execute(() -> {
                        if (destroyed) return;
                        try {
                            store.reset();
                            SettingsStore.SavedSettings blank = store.load();
                            main.post(() -> {
                                if (destroyed) return;
                                draft = blank;
                                storageDamaged = false;
                                ready = true;
                                busy = false;
                                modelExpanded = true;
                                bindDraft();
                                showMessage("本机配置已重置，请重新填写模型并选择允许操作的应用。", false);
                                updateModelPanel();
                                renderState();
                            });
                        } catch (IOException | RuntimeException error) {
                            main.post(() -> {
                                if (destroyed) return;
                                busy = false;
                                showMessage("配置重置未完成，请解锁设备后重试。", true);
                                renderState();
                            });
                        }
                    });
                }).show();
    }

    private void renderState() {
        if (destroyed || statusText == null) return;
        refreshSavedProbe();
        displayedVisionKind = savedVisionLease() == null ? ProbeKind.VISION : ProbeKind.SAVED_VISION;
        if (visionProbeButton != null) visionProbeButton.setText(displayedVisionKind == ProbeKind.VISION
                ? "测试当前填写的图像理解" : "测试已保存配置的图像理解");
        renderProbeResult();
        AgentController.State state = controller.getState();
        renderTaskUsage(state.usage, false);
        taskDiagnosticsButton.setEnabled(state.usage != null);
        taskActionReceiptsButton.setEnabled(state.usage != null);
        if (displayedTaskActionReceipts != null
                && !controller.isTaskActionReceiptsCurrent(displayedTaskActionReceipts)) closeTaskActionReceipts(true);
        if (displayedTaskDiagnostics != null
                && !controller.isTaskDiagnosticsCurrent(displayedTaskDiagnostics.taskId)) closeTaskDiagnostics(true);
        if (wasRunning && !state.running) taskField.setText("");
        wasRunning = state.running;
        boolean service = AgentAccessibilityService.getInstance() != null;
        serviceState.setText(service ? "服务已连接 · 任务启动后才会操作" : "服务未开启 · 需要你手动授权");
        serviceState.setTextColor(color(service ? R.color.agent_success : R.color.agent_muted));
        serviceButton.setText(service ? "查看系统服务设置" : "前往开启无障碍服务");
        boolean editable = ready && !busy && !state.running && !storageDamaged;
        boolean probing = modelProbe != null;
        for (View view : configurationViews) view.setEnabled(editable && !probing);
        // Editing any connection field cancels the old request before another probe can start.
        endpointField.setEnabled(editable);
        keyField.setEnabled(editable);
        modelField.setEnabled(editable);
        maxOutputTokensField.setEnabled(editable);
        taskField.setEnabled(editable && !probing);
        resetButton.setEnabled(store != null && !busy && !state.running && !probing);
        serviceButton.setEnabled(!state.running && !busy && !probing);
        compatibilityButton.setEnabled(!state.running && !busy && !probing);
        modelToggle.setEnabled(!probing);
        cancelProbeButton.setVisibility(probing ? View.VISIBLE : View.GONE);
        startButton.setEnabled(editable && service && !probing);
        startButton.setText(probing ? "正在测试模型…" : busy ? "正在准备…" : "开始执行任务");
        startButton.setVisibility(state.running ? View.GONE : View.VISIBLE);
        runningButtons.setVisibility(state.running ? View.VISIBLE : View.GONE);
        pauseButton.setText(state.paused ? "继续" : "暂停");
        pauseButton.setContentDescription(state.paused ? "重新观察并继续当前任务" : "暂停任务并接管手机");
        setButtonIcon(pauseButton, state.paused ? R.drawable.ic_play : R.drawable.ic_pause, false);
        clearButton.setEnabled(!state.running);
        statusText.setText(probing ? "正在测试模型（未操作手机）" : !ready && busy ? "正在读取本机配置…" : state.status);
        progress.setVisibility(probing || state.running && !state.paused && state.approval.isEmpty() ? View.VISIBLE : View.GONE);
        String approvalId = state.approvalId == null ? "" : state.approvalId;
        if (!approvalId.equals(boundApprovalId)) {
            allowButton.cancelPendingInputEvents();
            denyButton.cancelPendingInputEvents();
            allowButton.setPressed(false);
            denyButton.setPressed(false);
            boundApprovalId = approvalId;
        }
        boolean approvalVisible = state.running && !state.approval.isEmpty() && !approvalId.isEmpty();
        approvalPanel.setVisibility(approvalVisible ? View.VISIBLE : View.GONE);
        approvalText.setText("等待你的确认\n\n" + state.approval + "\n\n本次确认仅对当前动作有效，60 秒未确认将暂停。");
        allowButton.setEnabled(approvalVisible && !state.paused);
        denyButton.setEnabled(approvalVisible && !state.paused);
        allowButton.setOnClickListener(view -> controller.approve(approvalId, true));
        denyButton.setOnClickListener(view -> controller.approve(approvalId, false));
        boolean showResult = !hideCurrentResult && state.summary != null && !state.summary.trim().isEmpty();
        summaryText.setVisibility(showResult ? View.VISIBLE : View.GONE);
        summaryText.setText(showResult ? state.summary : "");
        if (hideCurrentResult || state.log.isEmpty()) logText.setText("发起任务后，这里会显示执行阶段。");
        else {
            StringBuilder events = new StringBuilder();
            int start = Math.max(0, state.log.size() - 40);
            if (start > 0) events.append("仅显示最近 40 条事件\n");
            for (int i = start; i < state.log.size(); i++) {
                if (events.length() > 0) events.append('\n');
                events.append(state.log.get(i));
            }
            logText.setText(events.toString());
        }
    }

    /** Periodic refresh is isolated from renderState/publish and never republishes the control overlay. */
    private void renderTaskUsage(AgentController.TaskUsage usage, boolean tick) {
        if (taskUsageView == null || destroyed) return;
        // Identity/terminal changes show immediately; ordinary same-task events
        // leave the usage view to its single foreground one-second tick.
        if (tick || !taskUsageView.showsIdentity(usage)) taskUsageView.render(usage);
        if (!resumed || usage == null || usage.finished) {
            main.removeCallbacks(taskUsageRefresh);
        } else if (!main.hasCallbacks(taskUsageRefresh)) {
            main.postDelayed(taskUsageRefresh, 1_000);
        }
    }

    private void refreshScopeSummary() {
        if (draft.allowedPackages.isEmpty()) { scopeSummary.setText("尚未选择应用"); return; }
        StringBuilder labels = new StringBuilder();
        int shown = 0;
        for (String packageName : draft.allowedPackages) {
            if (shown++ == 4) break;
            if (labels.length() > 0) labels.append("、");
            labels.append(appLabels.getOrDefault(packageName, packageName));
        }
        scopeSummary.setText("已选择 " + draft.allowedPackages.size() + " 个应用\n" + labels + (draft.allowedPackages.size() > 4 ? " 等" : ""));
    }

    private void showTaskDiagnostics() {
        if (destroyed || !resumed || isFinishing() || taskDiagnosticsDialog != null) return;
        final TaskDiagnostics snapshot = controller.getTaskDiagnostics();
        if (snapshot == null || !controller.isTaskDiagnosticsCurrent(snapshot.taskId)) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("任务诊断")
                .setMessage(snapshot.text)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制报告", null);
        if (snapshot.phase != TaskDiagnostics.Phase.FINISHED) builder.setNeutralButton("停止任务", null);
        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() == null) return;
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        taskDiagnosticsDialog = dialog;
        displayedTaskDiagnostics = snapshot;
        dialog.setOnDismissListener(ignored -> {
            if (taskDiagnosticsDialog == dialog) closeTaskDiagnostics(false);
        });
        try { dialog.show(); }
        catch (RuntimeException unavailable) {
            closeTaskDiagnostics(false);
            showMessage("当前窗口无法显示任务诊断，请返回页面后重试。", true);
            return;
        }
        TextView report = dialog.findViewById(android.R.id.message);
        report.setId(R.id.task_diagnostic_report);
        report.setTextSize(13);
        report.setTextIsSelectable(true);
        report.setSaveEnabled(false);
        Button copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        copy.setId(R.id.copy_task_diagnostic_report);
        copy.setAllCaps(false);
        copy.setMinHeight(dp(48));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setMinHeight(dp(48));
        if (snapshot.phase != TaskDiagnostics.Phase.FINISHED) {
            Button stop = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            stop.setId(R.id.stop_task_from_diagnostics);
            stop.setAllCaps(false);
            stop.setMinHeight(dp(48));
            stop.setTextColor(color(R.color.agent_error));
            stop.setOnClickListener(view -> {
                if (destroyed || !resumed || taskDiagnosticsDialog != dialog || displayedTaskDiagnostics != snapshot) return;
                controller.stopIfCurrentTask(snapshot.taskId);
                closeTaskDiagnostics(true);
            });
        }
        copy.setOnClickListener(view -> {
            // Copy exactly the immutable preview, never reread a newer task/report.
            if (destroyed || !resumed || taskDiagnosticsDialog != dialog || displayedTaskDiagnostics != snapshot) return;
            if (!controller.isTaskDiagnosticsCurrent(snapshot.taskId)) {
                closeTaskDiagnostics(true);
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                Toast.makeText(this, "系统剪贴板不可用，请稍后重试。", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("Agent OS 任务诊断", snapshot.text));
                copy.setText("报告已复制");
            } catch (RuntimeException unavailable) {
                Toast.makeText(this, "无法复制报告，请稍后重试。", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void closeTaskDiagnostics(boolean dismiss) {
        AlertDialog previous = taskDiagnosticsDialog;
        taskDiagnosticsDialog = null;
        displayedTaskDiagnostics = null;
        if (dismiss && previous != null) previous.dismiss();
    }

    private void showTaskActionReceipts() {
        if (destroyed || !resumed || isFinishing() || taskActionReceiptsDialog != null) return;
        final TaskActionReceipts snapshot = controller.getTaskActionReceipts();
        if (snapshot == null || !controller.isTaskActionReceiptsCurrent(snapshot)) return;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("本次任务操作回执")
                .setMessage(actionReceiptsText(snapshot))
                .setNegativeButton("关闭", null).create();
        if (dialog.getWindow() == null) return;
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        taskActionReceiptsDialog = dialog;
        displayedTaskActionReceipts = snapshot;
        dialog.setOnDismissListener(ignored -> {
            if (taskActionReceiptsDialog == dialog) closeTaskActionReceipts(false);
        });
        try { dialog.show(); }
        catch (RuntimeException unavailable) {
            closeTaskActionReceipts(false);
            try { dialog.dismiss(); }
            catch (RuntimeException cleanup) { unavailable.addSuppressed(cleanup); }
            showMessage("当前窗口无法显示操作回执，请返回页面后重试。", true);
            return;
        }
        TextView report = dialog.findViewById(android.R.id.message);
        report.setId(R.id.task_action_receipts_report);
        report.setTextSize(14);
        report.setSaveEnabled(false);
        // A bounded immutable preview: no polling, live text updates, clipboard
        // action or operation replay while the user reads it.
        Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        close.setAllCaps(false);
        close.setMinHeight(dp(48));
    }

    private void closeTaskActionReceipts(boolean dismiss) {
        AlertDialog previous = taskActionReceiptsDialog;
        taskActionReceiptsDialog = null;
        displayedTaskActionReceipts = null;
        if (dismiss && previous != null) previous.dismiss();
    }

    /** Only fixed user-facing labels and bounded ordinal/counts are rendered. */
    private static String actionReceiptsText(TaskActionReceipts snapshot) {
        StringBuilder text = new StringBuilder("仅记录本机工具回执。系统接受不等于目标完成；模型报告也不代表目标已验证。\n这是打开时的快照，不自动更新。\n");
        text.append(snapshot.frozen ? "任务已结束，保留以下回执。" : "关闭后重新打开可查看后续回执。");
        if (snapshot.omittedCount > 0)
            text.append("\n仅显示最近 ").append(TaskActionReceipts.MAX_ENTRIES).append(" 项，另有 ")
                    .append(snapshot.omittedCount).append(" 项较早回执未显示。");
        if (snapshot.entries.isEmpty()) text.append("\n\n本次任务尚无操作回执；目标仍未验证。");
        for (TaskActionReceipts.Entry entry : snapshot.entries) {
            text.append("\n\n第 ").append(entry.ordinal).append(" 项 · ").append(actionReceiptLabel(entry.action));
            switch (entry.stage) {
                case WAITING_RESULT: text.append("\n正在等待最终回执，提交情况需核对"); break;
                case NO_FINAL_RESULT: text.append("\n最终回执未返回，提交情况需核对"); break;
                default: text.append("\n本机回执已返回");
            }
            text.append("\n是否提交：").append(actionReceiptFact(entry.submitted))
                    .append("\n系统接受：").append(actionReceiptFact(entry.accepted))
                    .append("\n目标验证：").append(entry.verification == TaskActionReceipts.Verification.UNKNOWN
                            ? "结果未知" : "未验证")
                    .append('\n').append(actionReceiptHint(entry));
        }
        return text.toString();
    }

    private static String actionReceiptHint(TaskActionReceipts.Entry entry) {
        if (entry.stage != TaskActionReceipts.Stage.RETURNED)
            return "最终回执未完整返回，请核对目标应用，勿重复操作。";
        if (entry.submitted == TaskActionReceipts.Fact.UNKNOWN
                || entry.accepted == TaskActionReceipts.Fact.UNKNOWN)
            return "操作结果尚不明确，请核对目标应用，勿重复操作。";
        if (entry.accepted == TaskActionReceipts.Fact.YES)
            return entry.verification == TaskActionReceipts.Verification.UNKNOWN
                    ? "系统已接受，但目标结果未知；请查看目标应用的实际结果。"
                    : "系统已接受，目标尚未验证；请查看目标应用的实际结果。";
        if (entry.submitted == TaskActionReceipts.Fact.YES)
            return "请求已提交，系统未接受；请核对目标应用。";
        if ("USER_DENIED".equals(entry.code) || "APPROVAL_REJECTED".equals(entry.code))
            return "未提交：你已拒绝本次操作。";
        if ("STALE_OBSERVATION".equals(entry.code))
            return "未提交：界面条件已改变，需要重新观察并确认。";
        if ("APPROVAL_EXPIRED".equals(entry.code))
            return "未提交：确认已过期，需要重新观察并确认。";
        if ("TASK_STOPPED".equals(entry.code) || "TASK_PAUSED".equals(entry.code))
            return "未提交：任务已停止或暂停。";
        return "未提交：本次操作未满足执行条件，请核对目标应用。";
    }

    private static String actionReceiptLabel(TaskActionReceipts.Action action) {
        switch (action) {
            case LAUNCH_APP: return "打开应用";
            case CLICK: return "点击";
            case TYPE_TEXT: return "输入文字";
            case SCROLL: return "滚动";
            case BACK: return "返回";
            case HOME: return "回到桌面";
            default: return "操作";
        }
    }

    private static String actionReceiptFact(TaskActionReceipts.Fact value) {
        switch (value) {
            case YES: return "是";
            case NO: return "否";
            default: return "未知";
        }
    }

    private void showDeviceDiagnostics() {
        if (destroyed || isFinishing() || busy || modelProbe != null || controller.getState().running || diagnosticsDialog != null) return;
        final DeviceCapabilities.DisplaySnapshot display = DeviceCapabilities.captureDisplay(this);
        final CancellationSignal cancellation = new CancellationSignal();
        final long generation = ++diagnosticsGeneration;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设备与兼容性")
                .setMessage("正在读取本机系统条件…\n\n不会操作手机应用、读取任务或联系模型服务。本页诊断不代表机型或性能已通过验收。")
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制脱敏报告", null)
                .create();
        diagnosticsDialog = dialog;
        diagnosticsCancellation = cancellation;
        deviceReport = null;
        dialog.setOnDismissListener(ignored -> {
            if (diagnosticsDialog == dialog) closeDeviceDiagnostics(false);
        });
        try { dialog.show(); }
        catch (RuntimeException unavailable) {
            closeDeviceDiagnostics(false);
            showMessage("当前窗口无法显示设备报告，请返回页面后重试。", true);
            return;
        }
        TextView reportView = dialog.findViewById(android.R.id.message);
        reportView.setId(R.id.device_report);
        reportView.setTextSize(14);
        reportView.setTextIsSelectable(true);
        Button copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        copy.setId(R.id.copy_device_report);
        copy.setAllCaps(false);
        copy.setMinHeight(dp(48));
        copy.setEnabled(false);
        copy.setOnClickListener(view -> {
            DeviceCapabilities.Report shown = deviceReport;
            if (destroyed || diagnosticsDialog != dialog || shown == null) return;
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                Toast.makeText(this, "系统剪贴板不可用，请稍后重试。", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("Agent OS 设备条件报告", shown.text));
                copy.setText("报告已复制");
            } catch (RuntimeException unavailable) {
                Toast.makeText(this, "无法复制报告，请稍后重试。", Toast.LENGTH_SHORT).show();
            }
        });
        try {
            diagnosticsWorker.purge();
            diagnosticsFuture = diagnosticsWorker.submit(() -> {
                try {
                    DeviceCapabilities.Report report = DeviceCapabilities.collect(getApplicationContext(), display, cancellation);
                    main.post(() -> {
                        if (!isCurrentDiagnostics(dialog, cancellation, generation)) return;
                        deviceReport = report;
                        reportView.setText(styledDeviceReport(report));
                        copy.setEnabled(true);
                    });
                } catch (OperationCanceledException cancelled) {
                    // Dismissal and lifecycle changes discard the snapshot without any UI callback.
                } catch (RuntimeException unavailable) {
                    main.post(() -> {
                        if (!isCurrentDiagnostics(dialog, cancellation, generation)) return;
                        reportView.setText("系统条件查询未完成。请关闭后重试。\n\n没有操作手机应用，也没有发送网络请求；本次未产生兼容或性能验收结论。正常应用仍由原系统运行。");
                    });
                }
            });
        } catch (RejectedExecutionException unavailable) {
            reportView.setText("上一项系统查询尚未返回，请关闭后稍后重试。\n\n这不会阻止你手动使用手机应用。当前没有兼容或性能验收结论。");
        }
    }

    private boolean isCurrentDiagnostics(AlertDialog dialog, CancellationSignal cancellation, long generation) {
        return !destroyed && diagnosticsDialog == dialog && dialog.isShowing()
                && diagnosticsCancellation == cancellation && !cancellation.isCanceled() && diagnosticsGeneration == generation;
    }

    private CharSequence styledDeviceReport(DeviceCapabilities.Report report) {
        SpannableString content = new SpannableString(report.text);
        int firstLine = report.text.indexOf('\n');
        if (firstLine > 0) content.setSpan(new StyleSpan(Typeface.BOLD), 0, firstLine, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (DeviceCapabilities.Section section : report.sections) {
            int position = report.text.indexOf("\n\n" + section.title + "\n");
            if (position >= 0) content.setSpan(new StyleSpan(Typeface.BOLD), position + 2,
                    position + 2 + section.title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return content;
    }

    private void closeDeviceDiagnostics(boolean dismiss) {
        ++diagnosticsGeneration;
        AlertDialog oldDialog = diagnosticsDialog;
        diagnosticsDialog = null;
        deviceReport = null;
        CancellationSignal oldCancellation = diagnosticsCancellation;
        diagnosticsCancellation = null;
        if (oldCancellation != null) oldCancellation.cancel();
        Future<?> oldFuture = diagnosticsFuture;
        diagnosticsFuture = null;
        if (oldFuture != null) oldFuture.cancel(true);
        diagnosticsWorker.purge();
        if (dismiss && oldDialog != null && oldDialog.isShowing()) oldDialog.dismiss();
    }

    private void testModel() { startModelProbe(ProbeKind.TOOL); }

    private boolean canStartModelProbe() {
        return ready && resumed && !busy && !destroyed && !isFinishing() && !storageDamaged
                && modelProbe == null && probeTrustDialog == null && !controller.getState().running;
    }

    private void startModelProbe(ProbeKind kind) {
        if (!canStartModelProbe()) return;
        final SettingsStore.ConfigurationLease lease = kind == ProbeKind.SAVED_VISION ? savedVisionLease() : null;
        if (kind == ProbeKind.SAVED_VISION && lease == null) {
            // A stale saved-mode click must not silently become a draft request.
            renderState();
            showMessage("已保存配置已变化，请重新保存，或明确测试当前填写内容。", false);
            return;
        }
        final long uiGeneration = configurationGeneration;
        Integer selectedMaxOutputTokens = readMaxOutputTokens(true);
        if (selectedMaxOutputTokens == null) {
            showMessage("每次回复 Token 上限须为 128–8192 的整数。", true);
            return;
        }
        ModelSettings model = new ModelSettings(endpointField.getText().toString(), keyField.getText().toString(),
                modelField.getText().toString(), selectedMaxOutputTokens);
        try { model.validate(); }
        catch (IllegalArgumentException invalid) { showMessage(invalid.getMessage(), true); return; }
        if (kind != ProbeKind.TOOL) {
            invalidateSavedProbe("已失效，请重新测试");
            renderProbeResult();
        }
        if (model.endpoint.toLowerCase(Locale.ROOT).startsWith("http://") && !model.endpoint.equals(acceptedHttpEndpoint)) {
            final long generation = ++probeGeneration;
            AlertDialog prompt = new AlertDialog.Builder(this).setTitle("使用未加密的本地连接？")
                    .setMessage("此地址使用 HTTP。测试请求、合成测试图片和密钥在传输时不会加密，请仅连接你信任的本机或局域网服务。你也可以改用 HTTPS。")
                    .setNegativeButton("返回修改", null)
                    .setPositiveButton("信任此连接", (dialog, which) -> {
                        // The prompt authorizes only the captured configuration and this foreground lifecycle.
                        if (probeTrustDialog != dialog || generation != probeGeneration || !resumed
                                || destroyed || isFinishing() || !modelMatchesFields(model)
                                || uiGeneration != configurationGeneration
                                || kind == ProbeKind.SAVED_VISION && savedVisionLease() != lease) return;
                        probeTrustDialog = null;
                        if (!canStartModelProbe()) return;
                        acceptedHttpEndpoint = model.endpoint;
                        beginModelProbe(kind, model, lease, uiGeneration);
                    }).create();
            probeTrustDialog = prompt;
            prompt.setOnDismissListener(dialog -> {
                if (probeTrustDialog == dialog) {
                    probeTrustDialog = null; ++probeGeneration;
                    if (!destroyed) renderState();
                }
            });
            if (prompt.getWindow() != null) prompt.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            prompt.show();
            return;
        }
        beginModelProbe(kind, model, lease, uiGeneration);
    }

    private boolean modelMatchesFields(ModelSettings model) {
        Integer selectedMaxOutputTokens = readMaxOutputTokens(false);
        if (selectedMaxOutputTokens == null) return false;
        ModelSettings current = new ModelSettings(endpointField.getText().toString(), keyField.getText().toString(),
                modelField.getText().toString(), selectedMaxOutputTokens);
        return model.endpoint.equals(current.endpoint) && model.model.equals(current.model)
                && model.apiKey.equals(current.apiKey) && model.maxOutputTokens == current.maxOutputTokens;
    }

    private void beginModelProbe(ProbeKind kind, ModelSettings model,
                                 SettingsStore.ConfigurationLease lease, long uiGeneration) {
        if (!canStartModelProbe() || !modelMatchesFields(model) || uiGeneration != configurationGeneration
                || kind == ProbeKind.SAVED_VISION && (lease == null || savedVisionLease() != lease)) return;
        final RunControl control = new RunControl(draft.profileId);
        final long generation = ++probeGeneration;
        final SavedProbeOwner owner = kind == ProbeKind.SAVED_VISION
                ? new SavedProbeOwner(control, lease, uiGeneration) : null;
        modelProbe = control;
        activeProbeKind = kind;
        probeConnection = "正在检查";
        probeMessage = "";
        probeResultColor = R.color.agent_muted;
        if (kind == ProbeKind.TOOL) { toolProbeTested = true; toolVerified = false; }
        else { imageProbeTested = true; syntheticImageVerified = false; }
        if (owner != null) {
            savedProbeOwner = owner;
            savedProbeState = "正在测试";
            try {
                // A synchronous invalidation can occur during watch registration.
                // It only cancels this original control; View work is posted below.
                owner.registration = lease.watch(() -> {
                    owner.control.cancelIfExecutionEpoch(owner.epoch);
                    main.post(() -> {
                        if (destroyed || savedProbeOwner != owner) return;
                        invalidateSavedProbe("已失效，请重新测试");
                        renderProbeResult(); renderState();
                    });
                });
                if (!savedOwnerIsCurrent(owner)) {
                    invalidateSavedProbe("已失效，请重新测试"); renderProbeResult(); renderState(); return;
                }
            } catch (RuntimeException unavailable) {
                invalidateSavedProbe("已失效，请重新测试"); renderProbeResult(); renderState(); return;
            }
        }
        renderProbeResult(); renderState();
        try { storageWorker.execute(() -> {
            if (destroyed) { control.cancel(); return; }
            try {
                final boolean connected, verified;
                final String message;
                final SavedVisionProbe.Verification record;
                if (owner != null) {
                    SavedVisionProbe.Outcome result = SavedVisionProbe.process().test(
                            getApplicationContext(), owner.lease, control, owner.epoch);
                    connected = result.connected;
                    record = result.status == SavedVisionProbe.Status.VERIFIED
                            ? SavedVisionProbe.process().current(owner.lease) : null;
                    verified = record != null && record.isCurrentFor(owner.lease, control, owner.epoch);
                    message = result.status == SavedVisionProbe.Status.BUSY ? "已有已保存配置测试正在进行，请稍后重试。"
                            : verified ? "本次已保存配置的合成图片测试通过；记录最多保留 10 分钟，并受本次测试剩余时间预算限制。"
                            : "本次未建立有效的已保存配置图像记录。请检查模型配置和网络后重试。";
                } else if (kind == ProbeKind.VISION) {
                    VisionModelProbe.Result result = new VisionModelProbe().test(model, control);
                    connected = result.connected; verified = result.syntheticImageVerified; message = result.message; record = null;
                } else {
                    ModelProbe.Result result = new ModelProbe().test(model, control);
                    connected = result.connected; verified = result.toolCallingVerified; message = result.message; record = null;
                }
                if (!main.post(() -> {
                    if (destroyed || modelProbe != control || generation != probeGeneration || control.isCancelled()) return;
                    if (owner != null && (!savedOwnerIsCurrent(owner)
                            || verified && !record.isCurrentFor(owner.lease, control, owner.epoch))) {
                        invalidateSavedProbe("已失效，请重新测试"); renderProbeResult(); renderState(); return;
                    }
                    modelProbe = null;
                    activeProbeKind = null;
                    if (owner != null && verified) {
                        owner.verification = record;
                        savedProbeState = "当前有效";
                        scheduleSavedProbeExpiry(owner);
                        // Scheduling can synchronously expire the original budget or fail.
                        // Do not publish success after that path has released the owner.
                        refreshSavedProbe();
                        if (savedProbeOwner != owner) {
                            renderProbeResult(); renderState(); return;
                        }
                    } else {
                        control.cancel();
                        if (owner != null) invalidateSavedProbe("未建立");
                    }
                    probeConnection = connected ? "已连接" : "未连接";
                    if (kind == ProbeKind.TOOL) toolVerified = verified;
                    else syntheticImageVerified = verified;
                    probeMessage = message;
                    probeResultColor = verified ? R.color.agent_success : R.color.agent_error;
                    renderProbeResult(); renderState();
                })) control.cancel();
            } catch (InterruptedException cancelled) {
                main.post(() -> {
                    if (destroyed || modelProbe != control || generation != probeGeneration) return;
                    cancelModelProbe("测试已取消，未操作手机应用。", true);
                });
            } catch (IOException | RuntimeException failed) {
                // No arbitrary provider body, stored value or exception is shown.
                main.post(() -> {
                    if (destroyed || modelProbe != control || generation != probeGeneration) return;
                    cancelModelProbe("本次测试未完成。请检查模型配置和网络后重试。", false);
                    probeResultColor = R.color.agent_error;
                    renderProbeResult(); renderState();
                });
            }
        }); } catch (RejectedExecutionException unavailable) {
            cancelModelProbe("测试未启动，请重新打开配置页面后重试。", true);
        }
    }

    /** A saved probe must use this exact loaded/saved baseline, never edited fields. */
    private SettingsStore.ConfigurationLease savedVisionLease() {
        return savedConfigurationGeneration == configurationGeneration
                && SettingsStore.isCurrent(savedConfigurationLease) ? savedConfigurationLease : null;
    }

    private void configurationEdited() {
        configurationGeneration++;
        invalidateSavedProbe("已失效，请重新测试");
    }

    private boolean savedOwnerIsCurrent(SavedProbeOwner owner) {
        if (savedProbeOwner != owner || owner.configurationGeneration != configurationGeneration
                || savedVisionLease() != owner.lease || !resumed || destroyed || isFinishing()) return false;
        try { owner.control.checkExecutionEpoch(owner.epoch); return true; }
        catch (InterruptedException invalid) { return false; }
    }

    private void invalidateSavedProbe(String state) {
        SavedProbeOwner owner = savedProbeOwner;
        if (owner == null) return;
        savedProbeOwner = null;
        savedProbeState = state;
        syntheticImageVerified = false;
        probeResultColor = R.color.agent_muted;
        if (owner.expiry != null) main.removeCallbacks(owner.expiry);
        if (owner.registration != null) owner.registration.close();
        owner.control.cancelIfExecutionEpoch(owner.epoch);
        if (modelProbe == owner.control) {
            ++probeGeneration;
            modelProbe = null;
            activeProbeKind = null;
            probeConnection = "未验证";
            syntheticImageVerified = false;
        }
    }

    private void refreshSavedProbe() {
        SavedProbeOwner owner = savedProbeOwner;
        if (owner != null && (!savedOwnerIsCurrent(owner) || owner.verification != null
                && !owner.verification.isCurrentFor(owner.lease, owner.control, owner.epoch)))
            invalidateSavedProbe("已失效，请重新测试");
    }

    private void scheduleSavedProbeExpiry(SavedProbeOwner owner) {
        if (savedProbeOwner != owner || owner.verification == null) return;
        try {
            long remaining = Math.min(owner.verification.expiresAtElapsedRealtimeMillis - SystemClock.elapsedRealtime(),
                    owner.control.remainingActiveMillis());
            if (remaining <= 0) { invalidateSavedProbe("已失效，请重新测试"); return; }
            if (owner.expiry == null) owner.expiry = () -> {
                if (destroyed || savedProbeOwner != owner) return;
                refreshSavedProbe();
                if (savedProbeOwner == owner) {
                    scheduleSavedProbeExpiry(owner);
                    if (savedProbeOwner == owner) return;
                }
                renderProbeResult(); renderState();
            };
            // Another real attempt can supersede this same lease without a store
            // notification. Keep one bounded memory-only check, with no redraw
            // while current; never poll/decrypt the store or extend either deadline.
            if (!main.postDelayed(owner.expiry, Math.min(remaining, 1_000)))
                invalidateSavedProbe("已失效，请重新测试");
        } catch (InterruptedException expired) { invalidateSavedProbe("已失效，请重新测试"); }
    }

    private void renderProbeResult() {
        if (probeResult == null) return;
        refreshSavedProbe();
        String tools = activeProbeKind == ProbeKind.TOOL ? "正在测试"
                : !toolProbeTested ? "未测试" : toolVerified ? "已验证" : "未验证";
        String image = activeProbeKind == ProbeKind.VISION || activeProbeKind == ProbeKind.SAVED_VISION ? "正在测试合成图片"
                : !imageProbeTested ? "未验证" : syntheticImageVerified ? "本次合成图片已验证" : "本次合成图片未验证";
        probeResult.setText("网络连接：" + probeConnection + "\n工具调用：" + tools + "\n图像能力：" + image + "\n已保存配置记录：" + savedProbeState
                + (probeMessage.isEmpty() ? "" : "\n" + probeMessage));
        probeResult.setTextColor(color(probeResultColor));
    }

    private void watchModelConfiguration() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                configurationEdited();
                maxOutputTokensField.setError(null);
                cancelModelProbe("", false);
                probeConnection = "未测试";
                toolProbeTested = toolVerified = imageProbeTested = syntheticImageVerified = false;
                probeMessage = "配置已更改，请重新测试连接、工具调用与图像理解。";
                probeResultColor = R.color.agent_muted;
                renderProbeResult();
                if (!destroyed && statusText != null) renderState();
            }
            @Override public void afterTextChanged(Editable text) { }
        };
        endpointField.addTextChangedListener(watcher);
        keyField.addTextChangedListener(watcher);
        modelField.addTextChangedListener(watcher);
        maxOutputTokensField.addTextChangedListener(watcher);
    }

    private void cancelModelProbe(String reason, boolean updateUi) {
        ++probeGeneration;
        dismissProbeTrustDialog();
        RunControl current = modelProbe;
        if (savedProbeOwner != null && savedProbeOwner.control == current)
            invalidateSavedProbe("已失效，请重新测试");
        modelProbe = null;
        activeProbeKind = null;
        if (current != null) current.cancel();
        if (!destroyed && probeResult != null && (current != null || updateUi)) {
            if (current != null) probeConnection = "未验证";
            probeMessage = reason;
            probeResultColor = R.color.agent_muted;
            renderProbeResult();
        }
        if (updateUi && !destroyed && statusText != null) renderState();
    }

    private void dismissProbeTrustDialog() {
        AlertDialog previous = probeTrustDialog;
        if (previous == null) return;
        probeTrustDialog = null;
        ++probeGeneration;
        previous.dismiss();
    }

    private void updateModelPanel() {
        modelFields.setVisibility(modelExpanded ? View.VISIBLE : View.GONE);
        modelToggle.setText(modelExpanded ? "模型连接 · 收起设置" : "模型连接 · 展开设置");
    }

    private void showMessage(String message, boolean error) {
        messageText.setText(message == null ? "操作未完成，请重试。" : message);
        messageText.setTextColor(color(error ? R.color.agent_error : R.color.agent_text));
        messageText.setBackground(shape(color(error ? R.color.agent_error_surface : R.color.agent_soft), 14, 0));
        messageText.setVisibility(View.VISIBLE);
    }

    private EditText field(LinearLayout parent, String label, String hint, int id, boolean secret, int maxLength) {
        marginAdd(parent, text(label, 13, R.color.agent_muted, true), 10);
        EditText field = new EditText(this);
        field.setId(id);
        decorateField(field);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        if (secret) field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        configurationViews.add(field);
        marginAdd(parent, field, 5);
        return field;
    }

    private void decorateField(EditText field) {
        field.setTextSize(15);
        field.setTextColor(color(R.color.agent_text));
        field.setHintTextColor(color(R.color.agent_muted));
        field.setPadding(dp(12), dp(12), dp(12), dp(12));
        field.setMinHeight(dp(48));
        field.setBackground(shape(color(R.color.agent_input), 12, color(R.color.agent_border)));
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(shape(color(R.color.agent_surface), 18, color(R.color.agent_border)));
        marginAdd(parent, panel, 14);
        return panel;
    }

    private LinearLayout sectionTitle(String title, int icon) {
        LinearLayout row = row();
        ImageView image = new ImageView(this);
        image.setImageDrawable(tintedIcon(icon, color(R.color.agent_primary)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(image, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView label = text(title, 16, R.color.agent_text, true);
        label.setPadding(dp(9), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private Button button(String label, int icon, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setGravity(Gravity.CENTER);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{color(R.color.agent_muted), color(primary ? R.color.agent_primary_text : R.color.agent_primary)}));
        int base = color(primary ? R.color.agent_primary : R.color.agent_soft);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, shape(color(R.color.agent_border), 12, 0));
        background.addState(new int[]{android.R.attr.state_pressed}, shape(blend(base, color(R.color.agent_text), .12f), 12, 0));
        background.addState(new int[]{android.R.attr.state_focused}, shape(base, 12, color(R.color.agent_primary)));
        background.addState(new int[]{}, shape(base, 12, 0));
        button.setBackgroundTintList(null);
        button.setBackground(background);
        button.setCompoundDrawablePadding(dp(6));
        setButtonIcon(button, icon, primary);
        return button;
    }

    private void setButtonIcon(Button button, int resource, boolean primary) {
        Drawable drawable = resource == 0 ? null : tintedIcon(resource, color(primary ? R.color.agent_primary_text : R.color.agent_primary));
        if (drawable != null) drawable.setBounds(0, 0, dp(20), dp(20));
        button.setCompoundDrawablesRelative(drawable, null, null, null);
    }

    private Drawable tintedIcon(int resource, int tint) {
        Drawable icon = getDrawable(resource).mutate();
        if (resource != R.drawable.ic_agent_os) icon.setTint(tint);
        return icon;
    }

    private static int blend(int from, int to, float amount) {
        int red = Math.round(((from >> 16) & 255) * (1 - amount) + ((to >> 16) & 255) * amount);
        int green = Math.round(((from >> 8) & 255) * (1 - amount) + ((to >> 8) & 255) * amount);
        int blue = Math.round((from & 255) * (1 - amount) + (to & 255) * amount);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color(color));
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private int color(int resource) { return getColor(resource); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }
    private void marginAdd(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }
}
