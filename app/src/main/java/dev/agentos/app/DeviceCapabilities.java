package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserManager;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.webkit.WebView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A read-only conditions report. It neither exercises device actions nor certifies compatibility. */
public final class DeviceCapabilities {
    private static final long GIB = 1024L * 1024L * 1024L;

    private DeviceCapabilities() { }

    /** Capture the small amount of window-owned information while the Activity is on its UI thread. */
    public static DisplaySnapshot captureDisplay(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("窗口信息需要在界面线程读取。");
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int maximumWidth = 0;
        int maximumHeight = 0;
        boolean windowAvailable = false;
        try {
            WindowManager windows = activity.getWindowManager();
            Rect current = windows.getCurrentWindowMetrics().getBounds();
            Rect maximum = windows.getMaximumWindowMetrics().getBounds();
            width = current.width();
            height = current.height();
            maximumWidth = maximum.width();
            maximumHeight = maximum.height();
            windowAvailable = true;
        } catch (RuntimeException unavailable) {
            // Resource dimensions remain useful, but must not be mislabeled as window metrics.
        }
        return new DisplaySnapshot(width, height, maximumWidth, maximumHeight, metrics.densityDpi,
                metrics.density, activity.getResources().getConfiguration().fontScale, windowAvailable);
    }

    /** All potentially slower package/service queries run on a worker, without creating a WebView. */
    public static Report collect(Context suppliedContext, DisplaySnapshot display, CancellationSignal cancellation) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("设备诊断需要在工作线程执行。");
        if (cancellation == null || display == null) throw new IllegalArgumentException("诊断参数不完整。");
        Context context = suppliedContext.getApplicationContext();
        check(cancellation);
        List<Section> sections = new ArrayList<>();

        List<Fact> installation = new ArrayList<>();
        installation.add(fact("运行方式", "可安装 APK，保留手机现有系统，不要求 Root 或解锁 Bootloader"));
        String appVersion = "未能读取";
        try {
            PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            appVersion = clean(own.versionName, 80) + "（构建 " + own.getLongVersionCode() + "）";
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { }
        installation.add(fact("执行器版本", appVersion));
        installation.add(fact("普通应用", "安装、启动和日常使用由原系统处理，不经过 Agent"));
        installation.add(fact("系统镜像", "本次诊断不提供刷机镜像或刷机兼容结论"));
        sections.add(section("运行方式", installation));
        check(cancellation);

        List<Fact> platform = new ArrayList<>();
        platform.add(fact("设备型号", clean(Build.MANUFACTURER, 60) + " / " + clean(Build.MODEL, 100)));
        platform.add(fact("Android", clean(Build.VERSION.RELEASE, 80) + " / API " + Build.VERSION.SDK_INT));
        platform.add(fact("系统报告的安全补丁", clean(Build.VERSION.SECURITY_PATCH, 40)));
        platform.add(fact("最低版本条件", Build.VERSION.SDK_INT >= 30
                ? "满足 APK 声明的 API 30 基线；不等于该机型已验证" : "低于 APK 声明的 API 30 基线"));
        platform.add(fact("系统报告的 ABI", abiText(Build.SUPPORTED_ABIS)));
        platform.add(fact("当前应用进程", Process.is64Bit() ? "64 位" : "32 位"));
        sections.add(section("系统与架构", platform));
        check(cancellation);

        Long totalMemory = null;
        Integer memoryClass = null;
        Boolean lowRam = null;
        Boolean memoryPressure = null;
        try {
            ActivityManager activityManager = context.getSystemService(ActivityManager.class);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memory);
                if (memory.totalMem > 0) totalMemory = memory.totalMem;
                memoryPressure = memory.lowMemory;
                lowRam = activityManager.isLowRamDevice();
                memoryClass = activityManager.getMemoryClass();
            }
        } catch (RuntimeException unavailable) { }
        boolean lowResourceHint = Boolean.TRUE.equals(lowRam) || totalMemory != null && totalMemory <= 4 * GIB;
        List<Fact> resources = new ArrayList<>();
        resources.add(fact("系统报告的总内存", totalMemory == null ? "未能读取" : String.format(Locale.ROOT, "约 %.1f GiB", (double) totalMemory / GIB)));
        resources.add(fact("系统 lowRam 标记", yesNo(lowRam)));
        resources.add(fact("当前内存压力标记", yesNo(memoryPressure)));
        resources.add(fact("应用标准 Java 堆额度", memoryClass == null ? "未能读取" : memoryClass + " MiB（不代表设备总内存）"));
        resources.add(fact("资源提示", lowResourceHint
                ? "建议按低资源样本验收；系统低内存标记或不超过 4 GiB 的内存条件，仅用于安排测试"
                : "资源参数仅供安排测试，不据此判断性能或兼容等级"));
        resources.add(fact("当前实现模式", "结构化观察、单个前台任务；截图和坐标手势未开放，执行器不内置本地模型"));
        resources.add(fact("性能验收", "未验证：尚未通过本机流畅度、CPU、峰值内存或耗电测量"));
        sections.add(section("内存与运行模式", resources));
        check(cancellation);

        List<Fact> screen = new ArrayList<>();
        screen.add(fact(display.windowMetricsAvailable ? "当前窗口像素" : "应用资源报告的像素", dimensions(display.width, display.height)));
        screen.add(fact("系统报告的最大窗口", dimensions(display.maximumWidth, display.maximumHeight)));
        screen.add(fact("逻辑显示密度", display.densityDpi > 0 ? display.densityDpi + " dpi" : "未能读取"));
        screen.add(fact("像素 / dp", positiveFloat(display.density, "%.2f")));
        screen.add(fact("系统字体缩放", positiveFloat(display.fontScale, "%.2f 倍")));
        screen.add(fact("说明", "窗口像素和逻辑密度受方向、多窗口及显示缩放影响，不等同于屏幕物理规格"));
        sections.add(section("显示与缩放", screen));
        check(cancellation);

        Boolean webViewFeature = null;
        PackageInfo webViewProvider = null;
        boolean webViewQueryCompleted = false;
        try {
            webViewFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
            webViewProvider = WebView.getCurrentWebViewPackage();
            webViewQueryCompleted = true;
        } catch (RuntimeException | LinkageError unavailable) { }
        List<Fact> web = new ArrayList<>();
        web.add(fact("平台 WebView 功能声明", yesNo(webViewFeature)));
        web.add(fact("当前提供方", webViewProvider != null ? clean(webViewProvider.packageName, 160)
                : webViewQueryCompleted ? "系统未返回已配置的提供方" : "未能读取"));
        web.add(fact("提供方版本", webViewProvider == null ? "未知" : clean(webViewProvider.versionName, 100)));
        web.add(fact("实际网页渲染", "未验证：仅查询系统提供方信息，没有创建 WebView 或发起网络请求"));
        web.add(fact("对 Agent 的影响", "执行器使用原生界面；目标应用是否依赖 WebView 需逐项验证"));
        sections.add(section("系统 WebView", web));
        check(cancellation);

        Boolean enabled = null;
        Boolean retrieveContent = null;
        Boolean reportViewIds = null;
        Boolean windowList = null;
        try {
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            if (manager != null) {
                enabled = false;
                for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    if (info.getResolveInfo() == null) continue;
                    ServiceInfo service = info.getResolveInfo().serviceInfo;
                    if (service == null || !context.getPackageName().equals(service.packageName)
                            || !AgentAccessibilityService.class.getName().equals(service.name)) continue;
                    enabled = true;
                    retrieveContent = (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;
                    reportViewIds = (info.flags & AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0;
                    windowList = (info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0;
                    break;
                }
            }
        } catch (RuntimeException unavailable) { }
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        boolean connected = service != null && service.isConnected();
        Boolean personalProfile = null;
        Boolean interactive = null;
        Boolean unlocked = null;
        try {
            UserManager users = context.getSystemService(UserManager.class);
            if (users != null) personalProfile = !users.isManagedProfile() && users.isSystemUser();
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null) interactive = power.isInteractive();
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard != null && users != null) unlocked = !keyguard.isKeyguardLocked() && users.isUserUnlocked();
        } catch (RuntimeException unavailable) { }
        List<Fact> execution = new ArrayList<>();
        execution.add(fact("设备所有者个人资料", yesNo(personalProfile)));
        execution.add(fact("亮屏 / 已解锁", yesNo(interactive) + " / " + yesNo(unlocked)));
        execution.add(fact("用户启用本应用服务", yesNo(enabled)));
        execution.add(fact("本应用服务连接", connected ? "已连接（采集时）" : "未连接（采集时）"));
        execution.add(fact("读取窗口结构能力", yesNo(retrieveContent)));
        execution.add(fact("节点标识 / 窗口列表", yesNo(reportViewIds) + " / " + yesNo(windowList)));
        execution.add(fact("能力边界", "接口声明和连接状态不等于目标应用可操作；节点内容、授权范围和当前界面在任务中重新检查"));
        execution.add(fact("停止入口", "任务启动时检测；本次诊断不显示操作悬浮条，也不点击、输入或读取其他应用界面"));
        execution.add(fact("下一步", Boolean.FALSE.equals(personalProfile)
                ? "请在设备所有者的个人资料中使用，首版不在工作资料或其他用户中执行"
                : Boolean.FALSE.equals(interactive) || Boolean.FALSE.equals(unlocked)
                ? "保持手机亮屏且已解锁，再从任务界面开始"
                : !Boolean.TRUE.equals(enabled) || !connected
                ? "返回主页，手动检查系统无障碍设置中的 Agent OS 服务，再重新读取状态"
                : "选择允许操作的应用后，由你发起小任务验证；本次诊断没有操作目标应用"));
        sections.add(section("无障碍与执行条件", execution));
        check(cancellation);

        List<Fact> acceptance = new ArrayList<>();
        acceptance.add(fact("本机真机验收", "未验证：本报告仅记录系统条件，不运行应用任务或性能验收"));
        acceptance.add(fact("近五年机型覆盖", "属于测试目标，不按上市时间、ABI、内存或品牌自动判定已支持"));
        acceptance.add(fact("正常手机使用", "保留原系统与应用执行路径，普通应用安装和使用不依赖 Agent 在线"));
        acceptance.add(fact("报告内容", "不包含设备唯一标识、账号、模型密钥、任务内容或其他应用界面；仅在你主动复制时写入剪贴板"));
        sections.add(section("验收状态与数据范围", acceptance));
        check(cancellation);

        String collectedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date());
        return new Report(sections, collectedAt, lowResourceHint);
    }

    private static void check(CancellationSignal signal) {
        signal.throwIfCanceled();
        if (Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
    }

    private static String clean(String raw, int max) {
        if (raw == null || raw.trim().isEmpty()) return "未知";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < raw.length() && result.length() < max; i++) {
            char item = raw.charAt(i);
            result.append(Character.isISOControl(item) ? ' ' : item);
        }
        return result.toString().trim();
    }

    private static String abiText(String[] abis) {
        if (abis == null || abis.length == 0) return "未知";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < abis.length && i < 8; i++) names.add(clean(abis[i], 40));
        return String.join(", ", names);
    }

    private static String dimensions(int width, int height) {
        return width > 0 && height > 0 ? width + " × " + height + " px" : "未能读取";
    }

    private static String positiveFloat(float value, String format) {
        return Float.isFinite(value) && value > 0 ? String.format(Locale.ROOT, format, value) : "未能读取";
    }

    private static String yesNo(Boolean value) { return value == null ? "未知 / 未查询到" : value ? "是" : "否"; }
    private static Fact fact(String label, String value) { return new Fact(label, value); }
    private static Section section(String title, List<Fact> facts) { return new Section(title, facts); }

    public static final class DisplaySnapshot {
        public final int width, height, maximumWidth, maximumHeight, densityDpi;
        public final float density, fontScale;
        public final boolean windowMetricsAvailable;

        public DisplaySnapshot(int width, int height, int maximumWidth, int maximumHeight, int densityDpi,
                               float density, float fontScale, boolean windowMetricsAvailable) {
            this.width = width;
            this.height = height;
            this.maximumWidth = maximumWidth;
            this.maximumHeight = maximumHeight;
            this.densityDpi = densityDpi;
            this.density = density;
            this.fontScale = fontScale;
            this.windowMetricsAvailable = windowMetricsAvailable;
        }
    }

    public static final class Fact {
        public final String label;
        public final String value;
        Fact(String label, String value) { this.label = label; this.value = value; }
    }

    public static final class Section {
        public final String title;
        public final List<Fact> facts;
        Section(String title, List<Fact> facts) {
            this.title = title;
            this.facts = Collections.unmodifiableList(new ArrayList<>(facts));
        }
    }

    public static final class Report {
        public final List<Section> sections;
        public final String text;
        public final boolean lowResourceHint;

        Report(List<Section> sections, String collectedAt, boolean lowResourceHint) {
            this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
            this.lowResourceHint = lowResourceHint;
            StringBuilder plain = new StringBuilder("Agent OS · 设备条件报告\n采集时间（本机）：" + collectedAt
                    + "\n诊断结论：仅查询系统条件，机型与性能验收未验证");
            for (Section section : sections) {
                plain.append("\n\n").append(section.title).append('\n');
                for (Fact fact : section.facts) plain.append(fact.label).append("：").append(fact.value).append('\n');
            }
            text = plain.toString().trim();
        }
    }
}
