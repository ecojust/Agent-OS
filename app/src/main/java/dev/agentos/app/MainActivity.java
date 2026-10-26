package dev.agentos.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
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
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import dev.agentos.core.ModelSettings;
import dev.agentos.core.ModelProbe;
import dev.agentos.core.RunControl;

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
    private boolean suppressSwitch;
    private boolean modelExpanded = true;
    private boolean hideCurrentResult;
    private boolean wasRunning;
    private SettingsStore store;
    private SettingsStore.SavedSettings draft = new SettingsStore.SavedSettings();
    private AgentController controller;
    private final Map<String, String> appLabels = new LinkedHashMap<>();
    private EditText endpointField, modelField, keyField, taskField;
    private TextView serviceState, statusText, configSummary, scopeSummary, messageText, logText, summaryText, approvalText;
    private LinearLayout modelFields, approvalPanel, runningButtons;
    private ProgressBar progress;
    private Button serviceButton, modelToggle, saveButton, resetButton, appsButton, startButton, pauseButton, stopButton, clearButton, allowButton, denyButton;
    private Switch confirmSwitch;
    private final List<View> configurationViews = new ArrayList<>();
    private String boundApprovalId = "";
    private String acceptedHttpEndpoint = "";
    private UiDraft retainedDraft;
    private RunControl modelProbe;
    private long probeGeneration;
    private Button probeButton, cancelProbeButton;
    private TextView probeResult;

    private static final class UiDraft {
        final SettingsStore.SavedSettings settings;
        final String task;
        final boolean expanded;
        UiDraft(SettingsStore.SavedSettings settings, String task, boolean expanded) {
            this.settings = settings;
            this.task = task;
            this.expanded = expanded;
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        controller = AgentController.get(this);
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
        renderState();
    }

    @Override protected void onStop() {
        cancelModelProbe("已离开配置页面，测试已取消。", false);
        controller.removeListener(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelModelProbe("测试已取消。", false);
        destroyed = true;
        storageWorker.shutdown();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        if (!ready || storageDamaged) return null;
        // Rotation retains these values in process memory only, never an instance-state file.
        return new UiDraft(readDraft(), taskField.getText().toString(), modelExpanded);
    }

    @Override public void onChanged() {
        if (!destroyed) renderState();
    }

    private void buildInterface() {
        LinearLayout root = column();
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
        marginAdd(modelFields, text("使用兼容 Chat Completions 的接口。界面文字和任务会发送至此地址，密钥仅加密保存在本机；公网地址必须使用 HTTPS。", 12, R.color.agent_muted, false), 8);
        probeButton = button("测试模型连接 / 工具调用", R.drawable.ic_settings, false);
        probeButton.setId(R.id.test_model);
        probeButton.setOnClickListener(view -> testModel());
        configurationViews.add(probeButton);
        marginAdd(modelFields, probeButton, 12);
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
        confirmSwitch = new Switch(this);
        confirmSwitch.setId(R.id.confirm_actions);
        confirmSwitch.setText("逐步确认");
        confirmSwitch.setTextSize(16);
        confirmSwitch.setTextColor(color(R.color.agent_text));
        confirmSwitch.setMinHeight(dp(48));
        confirmSwitch.setChecked(true);
        confirmSwitch.setEnabled(false);
        confirmSwitch.setContentDescription("逐步确认已开启，当前原型固定启用");
        confirmSwitch.setPadding(0, dp(8), 0, dp(8));
        confirmSwitch.setOnCheckedChangeListener((view, checked) -> {
            if (suppressSwitch) return;
            // This prototype deliberately has no unconfirmed UI-write path.
            suppressSwitch = true;
            confirmSwitch.setChecked(true);
            suppressSwitch = false;
            draft.confirmEachAction = true;
        });
        marginAdd(scope, confirmSwitch, 10);
        marginAdd(scope, text("当前原型固定开启：所有界面操作都先由你确认。拒绝后不会换另一种方式绕过该动作。", 12, R.color.agent_muted, false), 0);

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
        marginAdd(task, text("默认最多 30 轮模型请求、100 次工具调用、10 分钟活动时间。任务和界面内容不保存为历史文件。", 12, R.color.agent_muted, false), 10);

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
            String failure = "";
            Set<String> revoked = new LinkedHashSet<>();
            try {
                SettingsStore.requirePersonalProfile(getApplicationContext());
                loadedStore = new SettingsStore(getApplicationContext());
                loaded = loadedStore.load();
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
            String errorText = failure;
            main.post(() -> {
                if (destroyed) return;
                store = nextStore;
                busy = false;
                ready = nextStore != null;
                storageDamaged = next == null;
                if (next != null) {
                    draft = next;
                    if (retainedDraft != null && next.profileId.equals(retainedDraft.settings.profileId)) {
                        draft = retainedDraft.settings;
                        taskField.setText(retainedDraft.task);
                    }
                    bindDraft();
                    modelExpanded = retainedDraft != null ? retainedDraft.expanded : next.endpoint.trim().isEmpty() || next.model.trim().isEmpty();
                    retainedDraft = null;
                    if (!revoked.isEmpty()) showMessage("有 " + revoked.size() + " 个应用的安装身份已变化，授权已取消。需要继续操作时，请重新选择这些应用。", true);
                } else showMessage(errorText, true);
                updateModelPanel();
                renderState();
            });
        });
    }

    private void bindDraft() {
        endpointField.setText(draft.endpoint);
        keyField.setText(draft.apiKey);
        modelField.setText(draft.model);
        keyField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        suppressSwitch = true;
        draft.confirmEachAction = true;
        confirmSwitch.setChecked(true);
        suppressSwitch = false;
        refreshScopeSummary();
        configSummary.setText(draft.model.trim().isEmpty() ? "配置模型地址、名称和密钥" : "已保存模型 · " + draft.model);
    }

    private void saveConfiguration(boolean startTask) {
        if (!ready || busy || destroyed || modelProbe != null || controller.getState().running) return;
        if (storageDamaged) { showMessage("原配置无法读取。请先明确重置本机配置，再保存新的设置。", true); return; }
        SettingsStore.SavedSettings settings = readDraft();
        ModelSettings model = new ModelSettings(settings.endpoint, settings.apiKey, settings.model);
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
        busy = true;
        showMessage("正在加密保存设置…", false);
        renderState();
        storageWorker.execute(() -> {
            if (destroyed) return;
            String failure = "";
            Set<String> valid = new LinkedHashSet<>();
            try {
                valid = store.validateAllowedPackages(settings);
                if (!valid.equals(settings.allowedPackages)) throw new IOException("应用安装身份已变化，请重新选择允许操作的应用。");
                store.save(settings);
            } catch (IOException | RuntimeException error) {
                failure = "无法保存或验证配置。请检查当前资料和应用授权；若配置已损坏，请先重置后重新填写。";
            }
            String errorText = failure;
            Set<String> allowed = valid;
            main.post(() -> {
                if (destroyed) return;
                busy = false;
                if (!errorText.isEmpty()) { showMessage(errorText, true); renderState(); return; }
                draft = settings;
                configSummary.setText("已保存模型 · " + model.model);
                if (!startTask) { showMessage("设置已加密保存在当前个人资料中。", false); renderState(); return; }
                try {
                    // Start is only invoked from this still-live Activity after the save succeeds.
                    hideCurrentResult = false;
                    controller.start(model, goal, allowed, settings.confirmEachAction);
                    messageText.setVisibility(View.GONE);
                    View focus = getCurrentFocus();
                    if (focus != null) {
                        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                        if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                        focus.clearFocus();
                    }
                } catch (RuntimeException error) {
                    showMessage("任务未启动。请确认手机已解锁、操作服务已开启，且没有其他任务运行。", true);
                }
                renderState();
            });
        });
    }

    private SettingsStore.SavedSettings readDraft() {
        SettingsStore.SavedSettings copy = new SettingsStore.SavedSettings();
        copy.profileId = draft.profileId;
        copy.endpoint = endpointField.getText().toString().trim();
        copy.apiKey = keyField.getText().toString().trim();
        copy.model = modelField.getText().toString().trim();
        copy.confirmEachAction = true;
        copy.allowedPackages = new LinkedHashSet<>(draft.allowedPackages);
        copy.grants = new LinkedHashMap<>(draft.grants);
        return copy;
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
                    draft.allowedPackages = new LinkedHashSet<>(grants.keySet());
                    draft.grants = new LinkedHashMap<>(grants);
                    refreshScopeSummary();
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
                .setMessage("Agent OS 使用无障碍服务读取你授权的应用界面，并执行任务所需的点击、输入和滑动。相关界面文字会发送至你配置的模型服务。\n\n服务需由你在系统设置中手动开启。任务运行时会显示控制条；锁屏会停止任务，不会自行恢复。此服务用于自动化任务。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往系统设置", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (RuntimeException error) { showMessage("无法打开设置。请手动进入系统设置 → 无障碍，找到 Agent OS。", true); }
                }).show();
    }

    private void confirmReset() {
        if (store == null || busy || modelProbe != null || controller.getState().running) return;
        new AlertDialog.Builder(this).setTitle("重置本机配置？")
                .setMessage("会删除本机保存的模型地址、密钥、模型名称和应用授权。此操作不会删除目标应用的数据，也不会删除已发送到模型服务的数据。")
                .setNegativeButton("保留配置", null)
                .setPositiveButton("删除并重新配置", (dialog, which) -> {
                    if (destroyed || controller.getState().running) return;
                    busy = true;
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
        AgentController.State state = controller.getState();
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
        taskField.setEnabled(editable && !probing);
        resetButton.setEnabled(store != null && !busy && !state.running && !probing);
        serviceButton.setEnabled(!state.running && !busy && !probing);
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

    private void testModel() {
        if (!ready || busy || destroyed || storageDamaged || modelProbe != null || controller.getState().running) return;
        ModelSettings model = new ModelSettings(endpointField.getText().toString(), keyField.getText().toString(), modelField.getText().toString());
        try { model.validate(); }
        catch (IllegalArgumentException invalid) { showMessage(invalid.getMessage(), true); return; }
        if (model.endpoint.toLowerCase(Locale.ROOT).startsWith("http://") && !model.endpoint.equals(acceptedHttpEndpoint)) {
            new AlertDialog.Builder(this).setTitle("使用未加密的本地连接？")
                    .setMessage("此地址使用 HTTP。测试请求和密钥在传输时不会加密，请仅连接你信任的本机或局域网服务。你也可以改用 HTTPS。")
                    .setNegativeButton("返回修改", null)
                    .setPositiveButton("信任此连接", (dialog, which) -> {
                        if (destroyed || controller.getState().running) return;
                        acceptedHttpEndpoint = model.endpoint;
                        testModel();
                    }).show();
            return;
        }
        final RunControl control = new RunControl(draft.profileId);
        final long generation = ++probeGeneration;
        modelProbe = control;
        probeResult.setText("网络连接：正在检查\n工具调用：等待测试\n图像能力：未验证");
        probeResult.setTextColor(color(R.color.agent_muted));
        renderState();
        storageWorker.execute(() -> {
            if (destroyed) { control.cancel(); return; }
            try {
                ModelProbe.Result result = new ModelProbe().test(model, control);
                main.post(() -> {
                    if (destroyed || modelProbe != control || generation != probeGeneration || control.isCancelled()) return;
                    modelProbe = null;
                    probeResult.setText("网络连接：" + (result.connected ? "已连接" : "未连接")
                            + "\n工具调用：" + (result.toolCallingVerified ? "已验证" : "未验证")
                            + "\n图像能力：未验证\n" + result.message);
                    probeResult.setTextColor(color(result.toolCallingVerified ? R.color.agent_success : R.color.agent_error));
                    renderState();
                });
            } catch (InterruptedException cancelled) {
                main.post(() -> {
                    if (destroyed || modelProbe != control || generation != probeGeneration) return;
                    modelProbe = null;
                    probeResult.setText("测试已取消，连接与工具能力未验证。");
                    renderState();
                });
            } catch (RuntimeException failed) {
                // A failed adapter must never expose arbitrary response bodies or credentials.
                main.post(() -> {
                    if (destroyed || modelProbe != control || generation != probeGeneration) return;
                    modelProbe = null;
                    probeResult.setText("测试未完成，连接与工具能力未验证。请检查模型配置和网络后重试。");
                    probeResult.setTextColor(color(R.color.agent_error));
                    renderState();
                });
            }
        });
    }

    private void watchModelConfiguration() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                cancelModelProbe("配置已更改，请重新测试连接与工具调用。", true);
            }
            @Override public void afterTextChanged(Editable text) { }
        };
        endpointField.addTextChangedListener(watcher);
        keyField.addTextChangedListener(watcher);
        modelField.addTextChangedListener(watcher);
    }

    private void cancelModelProbe(String reason, boolean updateUi) {
        ++probeGeneration;
        RunControl current = modelProbe;
        modelProbe = null;
        if (current != null) current.cancel();
        if (!destroyed && probeResult != null && (current != null || updateUi)) {
            probeResult.setText(reason);
            probeResult.setTextColor(color(R.color.agent_muted));
        }
        if (updateUi && !destroyed && statusText != null) renderState();
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
