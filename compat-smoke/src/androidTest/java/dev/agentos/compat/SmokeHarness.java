package dev.agentos.compat;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Test-only shell and UI access. None of these controls are linked into the product. */
final class SmokeHarness {
    static final String CALCULATOR = "org.fossify.math";
    static final String AGENT = "dev.agentos.app";
    static final String COMPONENT = AGENT + "/" + AGENT + ".AgentAccessibilityService";
    static final String APK_SHA256 = "7c5443c22d6d62c67dfe80df33ea9161ff0fa440f92590bcb45d7008b57613ed";
    static final String CERTIFICATE_SHA256 = "affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292";
    private static final Pattern SERVICE_RECORD = Pattern.compile("ServiceRecord\\{[0-9a-fA-F]+ u0 dev\\.agentos\\.app/(?:\\.AgentAccessibilityService|dev\\.agentos\\.app\\.AgentAccessibilityService)(?: c:[A-Za-z][A-Za-z0-9_.]*)?\\}");
    private final Context context;
    private final Bundle arguments;
    final UiAutomation automation;
    private String kernelQemu = "";
    private String bootQemu = "";
    private boolean dedicated;

    SmokeHarness(Instrumentation instrumentation, Bundle arguments) {
        context = instrumentation.getTargetContext();
        this.arguments = new Bundle(arguments);
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
    }

    static void validateArguments(Bundle args) {
        require("agent-os-third-party-smoke-v1".equals(args.getString("syntheticTestMarker")), "Dedicated smoke test marker is required");
        require("agent-os-third-party-fresh-install-v1".equals(args.getString("freshInstallMarker")), "Fresh isolated installation marker is required");
        require(matches(args.getString("deviceSerial"), "emulator-[0-9]{1,6}"), "An emulator transport serial is required");
        require(matches(args.getString("sourceSha"), "[0-9a-f]{40}"), "A source SHA is required");
        require(matches(args.getString("runId"), "[1-9][0-9]{0,19}"), "A CI run ID is required");
        require(matches(args.getString("runAttempt"), "[1-9][0-9]{0,5}"), "A CI attempt is required");
        require(matches(args.getString("expectedApi"), "30|35|37"), "Only the frozen smoke API samples are allowed");
    }

    void requireDedicatedEmulator() throws Exception {
        kernelQemu = shell("getprop ro.kernel.qemu").trim();
        bootQemu = shell("getprop ro.boot.qemu").trim();
        require("1".equals(kernelQemu), "Physical devices are refused: ro.kernel.qemu is not 1");
        require(bootQemu.isEmpty() || "1".equals(bootQemu), "Unexpected emulator boot property");
        require(Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64"), "This smoke sample is restricted to x86_64 emulators");
        require(Integer.toString(Build.VERSION.SDK_INT).equals(arguments.getString("expectedApi")), "Actual API differs from the frozen sample");
        require("0".equals(shell("am get-current-user").trim()), "Only the dedicated owner-user emulator is supported");
        require("dev.agentos.compat".equals(context.getPackageName()), "Unexpected instrumentation target");
        dedicated = true;
    }

    static JSONObject source(Bundle arguments) throws Exception {
        return new JSONObject().put("sha", arguments.getString("sourceSha"))
                .put("runId", arguments.getString("runId")).put("attempt", arguments.getString("runAttempt"))
                .put("expectedApi", arguments.getString("expectedApi")).put("deviceSerial", arguments.getString("deviceSerial"));
    }

    JSONObject device() throws Exception {
        return new JSONObject().put("sdkInt", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                .put("kernelQemu", kernelQemu).put("bootQemu", bootQemu)
                .put("kind", dedicated ? "emulator" : "unverified")
                .put("abi", Arrays.toString(Build.SUPPORTED_ABIS));
    }

    JSONObject verifyCalculatorIdentity() throws Exception {
        require(dedicated, "Emulator guard has not passed");
        PackageInfo info = context.getPackageManager().getPackageInfo(CALCULATOR, PackageManager.GET_SIGNING_CERTIFICATES);
        require(CALCULATOR.equals(info.packageName), "Unexpected installed package");
        require("1.4.0".equals(info.versionName) && info.getLongVersionCode() == 10, "Installed calculator version differs from frozen release");
        require(info.applicationInfo != null && info.applicationInfo.minSdkVersion == 26
                && info.applicationInfo.targetSdkVersion == 36, "Installed calculator SDK identity differs");
        require(info.signingInfo != null && !info.signingInfo.hasMultipleSigners(), "Unexpected installed signing identity");
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        require(signers.length == 1 && CERTIFICATE_SHA256.equals(sha256(signers[0].toByteArray())), "Installed calculator certificate differs");
        require(info.splitNames == null || info.splitNames.length == 0, "Unexpected split package");
        String apkHash;
        try (InputStream input = new FileInputStream(info.applicationInfo.sourceDir)) { apkHash = digest(input, 12 * 1024 * 1024); }
        require(APK_SHA256.equals(apkHash), "Installed calculator APK bytes differ from the frozen official release");
        return new JSONObject().put("package", info.packageName).put("versionName", info.versionName)
                .put("versionCode", Long.toString(info.getLongVersionCode())).put("certificateSha256", CERTIFICATE_SHA256)
                .put("apkSha256", apkHash).put("minSdk", info.applicationInfo.minSdkVersion)
                .put("targetSdk", info.applicationInfo.targetSdkVersion).put("uid", info.applicationInfo.uid);
    }

    String readSecure(String key) throws Exception {
        require("enabled_accessibility_services".equals(key) || "accessibility_enabled".equals(key), "Unexpected setting key");
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try { return Settings.Secure.getString(context.getContentResolver(), key); }
        finally { automation.dropShellPermissionIdentity(); }
    }

    void requireCleanServiceBaseline(String original) {
        require(original == null || original.isEmpty(), "Dedicated emulator already has an enabled accessibility service");
    }

    void setServiceState(String state) throws Exception {
        require(dedicated, "Emulator guard has not passed");
        require("off".equals(state) || "idle".equals(state), "Unknown test state");
        writeSecure("enabled_accessibility_services", "idle".equals(state) ? COMPONENT : "");
        writeSecure("accessibility_enabled", "idle".equals(state) ? "1" : "0");
    }

    void restoreServiceSettings(String services, String enabled) throws Exception {
        writeSecure("enabled_accessibility_services", services);
        writeSecure("accessibility_enabled", enabled);
        awaitServiceState("off", "restored");
        require(equal(services, readSecure("enabled_accessibility_services")), "Accessibility component list did not restore exactly");
    }

    private void writeSecure(String key, String value) throws Exception {
        require(dedicated, "Emulator guard has not passed");
        require("enabled_accessibility_services".equals(key) || "accessibility_enabled".equals(key), "Unexpected setting key");
        // UiAutomation executes argv, not shell syntax: quotes would become part
        // of component IDs and empty values. The provider preserves all three.
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            require(Settings.Secure.putString(context.getContentResolver(), key, value), "Could not write secure test setting");
            if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(key)) {
                require(equal(value, Settings.Secure.getString(context.getContentResolver(), key)),
                        "Secure accessibility component round-trip mismatch");
            }
            // The system recomputes ACCESSIBILITY_ENABLED while UiAutomation is
            // connected; actual bound/unbound service evidence is checked separately.
        } finally { automation.dropShellPermissionIdentity(); }
    }

    JSONObject awaitServiceState(String state, String stage) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        JSONObject last;
        do {
            last = observeServiceState(state, stage);
            if (last.getBoolean("verified")) return last;
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new ServiceStateFailure(last);
    }

    JSONObject verifyServiceState(String state, String stage) throws Exception {
        JSONObject observed = observeServiceState(state, stage);
        if (!observed.getBoolean("verified")) throw new ServiceStateFailure(observed);
        return observed;
    }

    private JSONObject observeServiceState(String state, String stage) throws Exception {
        String enabled = readSecure("enabled_accessibility_services");
        String master = readSecure("accessibility_enabled");
        String access = shell("dumpsys -t 2 accessibility");
        String services = shell("dumpsys -t 2 activity services " + AGENT);
        String activities = shell("dumpsys -t 2 activity activities");
        String bound = section(access, "Bound services:", "Enabled services:");
        String enabledDump = section(access, "Enabled services:", "Binding services:");
        String binding = section(access, "Binding services:", "Crashed services:");
        boolean parsed = bound != null && enabledDump != null && binding != null
                && hasOwnerUserAttributes(access);
        boolean record = hasAgentServiceRecord(services);
        boolean connection = services.contains("ConnectionRecord{");
        boolean enabledTarget = COMPONENT.equals(enabled) || (AGENT + "/.AgentAccessibilityService").equals(enabled);
        boolean noAgentActivity = !activities.contains(AGENT + "/") && !activities.contains(AGENT + ".MainActivity");
        boolean noAgentWindows = noAgentWindows();
        String label = context.getPackageManager().getServiceInfo(
                new ComponentName(AGENT, AGENT + ".AgentAccessibilityService"), 0)
                .loadLabel(context.getPackageManager()).toString();
        boolean verified;
        if ("off".equals(state)) {
            verified = parsed && (enabled == null || enabled.isEmpty()) && count(bound, "Service[") == 0
                    && !enabledDump.contains(AGENT) && !binding.contains(AGENT) && !record && noAgentActivity && noAgentWindows;
        } else {
            verified = parsed && enabledTarget && "1".equals(master) && count(bound, "Service[") == 1
                    && bound.contains("Service[label=" + label + ",") && enabledDump.contains(AGENT)
                    && !binding.contains(AGENT) && record && connection && noAgentActivity && noAgentWindows;
        }
        JSONObject raw = new JSONObject().put("settingsEnabledServices", enabled == null ? JSONObject.NULL : enabled)
                .put("settingsAccessibilityEnabled", master == null ? JSONObject.NULL : master)
                .put("accessibilityBoundServices", bounded(bound))
                .put("accessibilityEnabledServices", bounded(enabledDump))
                .put("accessibilityBindingServices", bounded(binding))
                .put("activityServiceRecords", matchingLines(services, "ServiceRecord{", "ConnectionRecord{", "app=ProcessRecord{"))
                .put("agentActivityAbsent", noAgentActivity).put("agentWindowsAbsent", noAgentWindows)
                .put("freshInstallNoAgentActivityStarted", true);
        return new JSONObject().put("stage", stage).put("state", "off".equals(state) ? "disabled" : "bound_idle")
                .put("verified", verified).put("observedAt", System.currentTimeMillis()).put("raw", raw);
    }

    private boolean noAgentWindows() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null || windows.size() > 64) return false;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try { if (AGENT.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false; }
                finally { root.recycle(); }
            }
            return true;
        } finally { recycle(windows); }
    }

    void launchCalculator() throws Exception {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(CALCULATOR);
        require(intent != null && intent.getComponent() != null && CALCULATOR.equals(intent.getComponent().getPackageName()), "No valid installed calculator launcher");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        awaitCalculatorPage();
        automation.waitForIdle(350, 8_000);
        try (WindowSnapshot ignored = calculatorWindow()) { /* Recheck after entry animation. */ }
    }

    private void awaitCalculatorPage() {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        String lastRefusal = "No window observation";
        do {
            try (WindowSnapshot ignored = calculatorWindow()) { return; }
            catch (AssertionError unavailable) {
                lastRefusal = unavailable.getMessage();
                SystemClock.sleep(100);
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Calculator did not become the unique unobscured focused window: " + lastRefusal);
    }

    void perform(String id, int action) throws Exception {
        require(action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK, "Unexpected test action");
        try (WindowSnapshot window = calculatorWindow()) {
            AccessibilityNodeInfo node = uniqueNode(window, id);
            try {
                require(node.isEnabled() && node.isVisibleToUser(), "Calculator resource is not visible and enabled");
                require(action == AccessibilityNodeInfo.ACTION_CLICK ? node.isClickable() : node.isLongClickable(), "Calculator resource does not expose the expected action");
                require(node.performAction(action), "Calculator resource rejected the action");
            } finally { node.recycle(); }
        }
        automation.waitForIdle(150, 5_000);
        try (WindowSnapshot ignored = calculatorWindow()) { /* Still the same supported page type. */ }
    }

    String readText(String id) {
        try (WindowSnapshot window = calculatorWindow()) {
            AccessibilityNodeInfo node = uniqueNode(window, id);
            try {
                String text = node.getText() == null ? "" : node.getText().toString();
                require(text.length() <= 64, "Unexpected calculator text length");
                return text;
            } finally { node.recycle(); }
        }
    }

    String awaitResult(String expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        String actual = "";
        do {
            actual = readText("result");
            if (expected.equals(actual)) return actual;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new ResultMismatch(actual);
    }

    WindowSnapshot calculatorWindow() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        require(windows != null && windows.size() <= 64, "Window inventory is unavailable or truncated");
        AccessibilityWindowInfo focused = null;
        AccessibilityNodeInfo root = null;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                require(focused == null, "More than one focused window");
                focused = window;
            }
            require(focused != null && focused.getDisplayId() == Display.DEFAULT_DISPLAY
                    && focused.getType() == AccessibilityWindowInfo.TYPE_APPLICATION, "Focused window is not the default-display application");
            root = focused.getRoot();
            require(root != null && CALCULATOR.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()), "Focused package is not the verified calculator");
            Rect bounds = new Rect(); focused.getBoundsInScreen(bounds);
            require(!bounds.isEmpty(), "Calculator window has no bounds");
            for (AccessibilityWindowInfo other : windows) {
                if (other.getId() == focused.getId() || other.getDisplayId() != Display.DEFAULT_DISPLAY
                        || other.getLayer() < focused.getLayer()) continue;
                Rect overlay = new Rect(); other.getBoundsInScreen(overlay);
                if (!Rect.intersects(bounds, overlay)) continue;
                require(isTrustedSystemBar(other, overlay, bounds), "Calculator is obscured by an unknown window: " + windowDiagnostic(other, overlay));
            }
            WindowSnapshot result = new WindowSnapshot(focused.getId(), bounds, root);
            root = null;
            return result;
        } finally {
            if (root != null) root.recycle();
            recycle(windows);
        }
    }

    private boolean isTrustedSystemBar(AccessibilityWindowInfo window, Rect bar, Rect app) {
        if (window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) return false;
        String title = window.getTitle() == null ? "" : window.getTitle().toString();
        if (!isKnownSystemBarTitle(title)) return false;
        AccessibilityNodeInfo root = window.getRoot();
        try {
            if (root != null && !"com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
        } finally { if (root != null) root.recycle(); }
        int edge = Math.round(context.getResources().getDisplayMetrics().density * 96);
        return (bar.height() <= edge && (bar.top <= app.top || bar.bottom >= app.bottom))
                || (bar.width() <= edge && (bar.left <= app.left || bar.right >= app.right));
    }

    static boolean isKnownSystemBarTitle(String title) {
        // AOSP's navigation bar uses accessibilityTitle=R.string.nav_bar;
        // the default test locale resolves it to "Navigation bar", not its WM title.
        return title.equals("StatusBar") || title.equals("NavigationBar")
                || title.equals("NavigationBar0") || title.equals("Navigation bar");
    }

    private static String windowDiagnostic(AccessibilityWindowInfo window, Rect bounds) {
        // Metadata only, on the guarded synthetic device. No node text or screen pixels.
        String title = window.getTitle() == null ? "" : window.getTitle().toString();
        if (title.length() > 80) title = title.substring(0, 80);
        String packageName = "[no root]";
        AccessibilityNodeInfo root = window.getRoot();
        try {
            if (root != null) packageName = root.getPackageName() == null ? "[no package]" : root.getPackageName().toString();
        } finally { if (root != null) root.recycle(); }
        if (packageName.length() > 120) packageName = packageName.substring(0, 120);
        return "id=" + window.getId() + " type=" + window.getType() + " layer=" + window.getLayer()
                + " bounds=" + bounds.toShortString() + " title=" + title + " package=" + packageName;
    }

    private static AccessibilityNodeInfo uniqueNode(WindowSnapshot window, String id) {
        List<AccessibilityNodeInfo> found = window.root.findAccessibilityNodeInfosByViewId(CALCULATOR + ":id/" + id);
        AccessibilityNodeInfo selected = null;
        try {
            require(found != null && found.size() <= 16, "Missing or ambiguous calculator resource");
            for (AccessibilityNodeInfo node : found) {
                if (!node.isVisibleToUser()) continue;
                require(selected == null && node.getWindowId() == window.id, "Resource did not map to a unique current calculator window");
                require(CALCULATOR.contentEquals(node.getPackageName() == null ? "" : node.getPackageName()), "Unexpected resource package");
                selected = AccessibilityNodeInfo.obtain(node);
            }
            require(selected != null, "Visible calculator resource was not found");
            AccessibilityNodeInfo result = selected; selected = null; return result;
        } finally {
            if (selected != null) selected.recycle();
            if (found != null) for (AccessibilityNodeInfo node : found) node.recycle();
        }
    }

    String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (out.size() + read > 512 * 1024) throw new IllegalStateException("Shell evidence exceeded its bound");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String section(String text, String start, String end) {
        int first = text.indexOf(start);
        if (first < 0 || text.indexOf(start, first + start.length()) >= 0) return null;
        int last = text.indexOf(end, first + start.length());
        return last < 0 ? null : text.substring(first + start.length(), last).trim();
    }

    static boolean hasOwnerUserAttributes(String dump) {
        // Android's ICU regex parser requires a literal closing brace to be escaped.
        return Pattern.compile("attributes:\\{id=0(?:,|\\})").matcher(dump).find();
    }

    static boolean hasAgentServiceRecord(String dump) {
        // Recent platform versions append an optional calling-package field.
        return SERVICE_RECORD.matcher(dump).find();
    }

    private static String matchingLines(String text, String... needles) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            boolean match = false;
            for (String needle : needles) if (line.contains(needle)) match = true;
            if (match && result.length() < 4096) result.append(line.trim()).append('\n');
        }
        return bounded(result.toString());
    }

    private static String bounded(String text) { return text == null ? "[section unavailable]" : text.substring(0, Math.min(text.length(), 4096)); }
    private static int count(String text, String needle) { if (text == null) return 0; int n = 0, i = 0; while ((i = text.indexOf(needle, i)) >= 0) { n++; i += needle.length(); } return n; }
    private static boolean equal(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static boolean matches(String value, String expression) { return value != null && value.matches(expression); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void recycle(List<AccessibilityWindowInfo> windows) { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
    static String sha256(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String digest(InputStream input, int maximum) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int n, total = 0;
        while ((n = input.read(buffer)) != -1) { total += n; require(total <= maximum, "Digest input exceeds its frozen size bound"); hash.update(buffer, 0, n); }
        return hex(hash.digest());
    }
    private static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(); for (byte value : bytes) b.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return b.toString(); }

    static final class WindowSnapshot implements AutoCloseable {
        final int id; final Rect bounds; final AccessibilityNodeInfo root;
        WindowSnapshot(int id, Rect bounds, AccessibilityNodeInfo root) { this.id = id; this.bounds = new Rect(bounds); this.root = root; }
        @Override public void close() { root.recycle(); }
    }

    static final class ServiceStateFailure extends AssertionError {
        final JSONObject observation;
        ServiceStateFailure(JSONObject observation) { super("Actual Agent service state did not match the frozen smoke state"); this.observation = observation; }
    }

    static final class ResultMismatch extends AssertionError {
        final String actual;
        ResultMismatch(String actual) { super("Calculator result did not match the expected arithmetic value"); this.actual = actual; }
    }
}
