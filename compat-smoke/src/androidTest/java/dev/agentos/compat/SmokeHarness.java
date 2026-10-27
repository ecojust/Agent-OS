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
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
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
    static final Target CALCULATOR_TARGET = new Target("calculator-1.4.0", CALCULATOR, "1.4.0", 10, APK_SHA256);
    static final Target NOTES_TARGET = new Target("notes-1.7.0", "org.fossify.notes", "1.7.0", 13,
            "5a56e0e39cc488e1f3b947d3801006d3b7450ec73c67f03195c64c5fd3b6bced");
    final Target target;
    private final Context context;
    private final Bundle arguments;
    final UiAutomation automation;
    private String kernelQemu = "";
    private String bootQemu = "";
    private boolean dedicated;
    enum NotesStage {
        UNSET, LAUNCH, INITIAL_EDITOR, INITIAL_CAPTURE, OPEN_CREATE_DIALOG,
        SET_TITLE, SELECT_TEXT_TYPE, CONFIRM_CREATE, CREATED_EDITOR, CREATED_TITLE,
        SET_BODY, SAVE_BACKGROUND, REOPEN, REOPENED_EDITOR, VERIFY_PERSISTED, RESULT_CAPTURE
    }
    private NotesStage notesStage = NotesStage.UNSET;
    private String lastNotesAction = "NONE";
    private String lastNotesActionResult = "NOT_ATTEMPTED";
    private int notesActionAttempts;

    void notesDiagnosticStage(NotesStage stage) { notesStage = stage; }

    private void notesActionAttempt(String action) {
        lastNotesAction = action;
        lastNotesActionResult = "SUBMISSION_UNKNOWN";
        notesActionAttempts = Math.min(64, notesActionAttempts + 1);
    }

    private boolean notesActionResult(boolean accepted) {
        lastNotesActionResult = accepted ? "ACCEPTED" : "REJECTED";
        return accepted;
    }

    SmokeHarness(Instrumentation instrumentation, Bundle arguments) {
        this(instrumentation, arguments, CALCULATOR_TARGET);
    }

    SmokeHarness(Instrumentation instrumentation, Bundle arguments, Target target) {
        require(target == CALCULATOR_TARGET || target == NOTES_TARGET, "Unknown frozen application");
        this.target = target;
        context = instrumentation.getTargetContext();
        this.arguments = new Bundle(arguments);
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
    }

    static void validateArguments(Bundle args) {
        boolean versionTwo = "agent-os-third-party-smoke-v2".equals(args.getString("syntheticTestMarker"));
        require(versionTwo || "agent-os-third-party-smoke-v1".equals(args.getString("syntheticTestMarker")), "Dedicated smoke test marker is required");
        require((versionTwo ? "agent-os-third-party-fresh-install-v2" : "agent-os-third-party-fresh-install-v1")
                .equals(args.getString("freshInstallMarker")), "Fresh isolated installation marker is required");
        if (versionTwo) require("calculator-1.4.0-notes-1.7.0-v2".equals(args.getString("sampleId")), "Unknown frozen application set");
        require(matches(args.getString("deviceSerial"), "emulator-[0-9]{1,6}"), "An emulator transport serial is required");
        require(matches(args.getString("sourceSha"), "[0-9a-f]{40}"), "A source SHA is required");
        require(matches(args.getString("runId"), "[1-9][0-9]{0,19}"), "A CI run ID is required");
        require(matches(args.getString("runAttempt"), "[1-9][0-9]{0,5}"), "A CI attempt is required");
        require(matches(args.getString("expectedApi"), versionTwo ? "30|31|32|33|34|35|36|37" : "30|35|37"), "Only the frozen smoke API samples are allowed");
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
        require(target == CALCULATOR_TARGET, "Calculator verifier cannot accept another application");
        return verifyTargetIdentity();
    }

    JSONObject verifyTargetIdentity() throws Exception {
        require(dedicated, "Emulator guard has not passed");
        PackageInfo info = context.getPackageManager().getPackageInfo(target.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        require(target.packageName.equals(info.packageName), "Unexpected installed package");
        require(target.versionName.equals(info.versionName) && info.getLongVersionCode() == target.versionCode, "Installed application version differs from frozen release");
        require(info.applicationInfo != null && info.applicationInfo.minSdkVersion == 26
                && info.applicationInfo.targetSdkVersion == 36, "Installed application SDK identity differs");
        require(info.signingInfo != null && !info.signingInfo.hasMultipleSigners(), "Unexpected installed signing identity");
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        require(signers.length == 1 && CERTIFICATE_SHA256.equals(sha256(signers[0].toByteArray())), "Installed application certificate differs");
        require(info.splitNames == null || info.splitNames.length == 0, "Unexpected split package");
        String apkHash;
        try (InputStream input = new FileInputStream(info.applicationInfo.sourceDir)) { apkHash = digest(input, 12 * 1024 * 1024); }
        require(target.apkSha256.equals(apkHash), "Installed application APK bytes differ from the frozen official release");
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
        require(target == CALCULATOR_TARGET, "Application launcher cannot select another application");
        launchTarget();
    }

    void launchTarget() throws Exception {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(target.packageName);
        require(intent != null && intent.getComponent() != null && target.packageName.equals(intent.getComponent().getPackageName()), "No valid installed application launcher");
        int previousWindow = focusedTargetWindowId();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        awaitTargetPage(previousWindow);
    }

    private int focusedTargetWindowId() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        require(windows != null && windows.size() <= 64, "Pre-launch window inventory is unavailable");
        int previous = -1;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    if (root != null && target.packageName.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                        require(previous == -1, "More than one prior focused application window");
                        previous = window.getId();
                    }
                } finally { if (root != null) root.recycle(); }
            }
            return previous;
        } finally { recycle(windows); }
    }

    private void awaitTargetPage(int previousWindow) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        String lastRefusal = "No window observation";
        StableWindow readiness = new StableWindow(previousWindow);
        do {
            try (WindowSnapshot current = appWindow(target == NOTES_TARGET)) {
                if (readiness.observe(current.id, current.geometry, SystemClock.elapsedRealtime())) return;
                lastRefusal = "Waiting for the new application window and platform geometry to stabilize";
            }
            catch (AssertionError unavailable) {
                lastRefusal = unavailable.getMessage();
                readiness.reset();
            }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Application did not become the unique unobscured focused window: " + lastRefusal);
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
        require(target == CALCULATOR_TARGET, "Calculator window reader cannot select another application");
        return appWindow(false);
    }

    WindowSnapshot appWindow(boolean allowInputMethod) {
        require(!allowInputMethod || target == NOTES_TARGET, "Only the notes editing probe may inspect an IME");
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
            if (focused == null && target == NOTES_TARGET) throw new NotesObservationPending("focused_window_not_published");
            require(focused != null, "Focused application window is missing");
            require(focused.getDisplayId() == Display.DEFAULT_DISPLAY && focused.getType() == AccessibilityWindowInfo.TYPE_APPLICATION,
                    "Focused window has unsupported metadata: id=" + focused.getId() + " type=" + focused.getType()
                            + " display=" + focused.getDisplayId());
            root = focused.getRoot();
            if (root == null && target == NOTES_TARGET) throw new NotesObservationPending("application_root_not_published");
            CharSequence observedPackage = root == null ? null : root.getPackageName();
            if (root != null && target == NOTES_TARGET && (observedPackage == null || observedPackage.length() == 0))
                throw new NotesObservationPending("application_root_package_not_published");
            if (root == null || !target.packageName.contentEquals(observedPackage == null ? "" : observedPackage)) {
                // Preserve immediate refusal. These bounded diagnostics never
                // authorize a foreign root or turn it into a readiness retry.
                throw new AssertionError("Focused package is not the verified frozen application"
                        + (target == NOTES_TARGET ? focusedPackageDiagnostic(focused, root, observedPackage) : ""));
            }
            Rect bounds = new Rect(); focused.getBoundsInScreen(bounds);
            // The package has already been checked above. Empty geometry grants
            // no action; only reobserve this pending Notes publication.
            if (bounds.isEmpty() && target == NOTES_TARGET) throw new NotesObservationPending("application_bounds_not_published");
            require(!bounds.isEmpty(), "Application window has no bounds");
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            require(manager != null, "Platform window metrics are unavailable");
            WindowMetrics metrics = manager.getCurrentWindowMetrics();
            Rect display = metrics.getBounds();
            Insets bars = metrics.getWindowInsets().getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            require(!display.isEmpty() && display.contains(bounds), "Application bounds differ from default-display metrics");
            Rect inputMethod = new Rect();
            int inputMethodId = -1;
            for (AccessibilityWindowInfo other : windows) {
                if (other.getId() == focused.getId() || other.getDisplayId() != Display.DEFAULT_DISPLAY
                        || other.getLayer() < focused.getLayer()) continue;
                Rect overlay = new Rect(); other.getBoundsInScreen(overlay);
                if (!Rect.intersects(bounds, overlay)) continue;
                if (target == NOTES_TARGET && other.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    require(inputMethod.isEmpty(), "More than one input-method window");
                    String refusal = systemInputMethodRefusal(other, overlay, display);
                    require(refusal == null, "Unexpected input-method window: " + refusal
                            + " region=" + overlay.toShortString() + " display=" + display.toShortString());
                    inputMethod.set(overlay);
                    inputMethodId = other.getId();
                    continue;
                }
                require(isContainedInVisibleSystemBar(other.getType(), overlay, display, bars),
                        "Application is obscured by an unknown window: " + windowDiagnostic(other, overlay));
            }
            if (!allowInputMethod && !inputMethod.isEmpty()) throw new NotesObservationPending("verified_input_method_present");
            String geometry = bounds.toShortString() + "/" + display.toShortString() + "/" + bars;
            WindowSnapshot result = new WindowSnapshot(focused.getId(), bounds, root, geometry, inputMethod, inputMethodId);
            root = null;
            return result;
        } finally {
            if (root != null) root.recycle();
            recycle(windows);
        }
    }

    /** Metadata from this rejected read only; never record package names or UI text. */
    private String focusedPackageDiagnostic(AccessibilityWindowInfo window,
            AccessibilityNodeInfo root, CharSequence observedPackage) {
        Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
        int rootWindowId = root == null ? -1 : root.getWindowId();
        String metadata = " windowId=" + window.getId() + " rootWindowId=" + rootWindowId
                + " type=" + window.getType() + " display=" + window.getDisplayId()
                + " layer=" + window.getLayer() + " focused=" + window.isFocused()
                + " active=" + window.isActive() + " bounds=" + bounds.toShortString();
        return "; packageClass=" + focusedPackageClass(observedPackage) + metadata
                + " stage=" + notesStage + " lastAction=" + lastNotesAction
                + " actionResult=" + lastNotesActionResult + " actionAttempts=" + notesActionAttempts;
    }

    private String focusedPackageClass(CharSequence observedPackage) {
        if (observedPackage == null || observedPackage.length() == 0) return "EMPTY";
        if (target.packageName.contentEquals(observedPackage)) return "EXPECTED";
        try {
            ServiceInfo ime = configuredSystemInputMethod();
            if (ime != null && ime.packageName.contentEquals(observedPackage)) return "VERIFIED_DEFAULT_IME";
            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            PackageManager packages = context.getPackageManager();
            ResolveInfo resolvedHome = packages.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            List<ResolveInfo> homes = packages.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            ComponentName home = resolvedHome == null || resolvedHome.activityInfo == null ? null
                    : new ComponentName(resolvedHome.activityInfo.packageName, resolvedHome.activityInfo.name);
            if (home != null && homes != null && homes.size() <= 16 && home.getPackageName().contentEquals(observedPackage)) {
                for (ResolveInfo candidate : homes) {
                    if (candidate != null && candidate.activityInfo != null
                            && home.equals(new ComponentName(candidate.activityInfo.packageName, candidate.activityInfo.name))) {
                        return "RESOLVED_HOME";
                    }
                }
            }
            if (ime == null || home == null || homes == null || homes.isEmpty() || homes.size() > 16)
                return "IDENTITY_UNAVAILABLE";
            boolean resolvedHomeDeclared = false;
            for (ResolveInfo candidate : homes) {
                if (candidate != null && candidate.activityInfo != null
                        && home.equals(new ComponentName(candidate.activityInfo.packageName, candidate.activityInfo.name)))
                    resolvedHomeDeclared = true;
            }
            return resolvedHomeDeclared ? "OTHER_NONEMPTY" : "IDENTITY_UNAVAILABLE";
        } catch (RuntimeException unavailable) {
            // Diagnostics may be unavailable; the original refusal still wins.
            return "IDENTITY_UNAVAILABLE";
        }
    }

    private String systemInputMethodRefusal(AccessibilityWindowInfo window, Rect region, Rect display) {
        if (target != NOTES_TARGET || window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                || window.getDisplayId() != Display.DEFAULT_DISPLAY) return "unexpected_type_or_display";
        ServiceInfo service = configuredSystemInputMethod();
        if (service == null) return "configured_system_identity_unverified";
        AccessibilityNodeInfo root = window.getRoot();
        try {
            if (root == null) throw new NotesObservationPending("input_method_root_not_published");
            if (root.getPackageName() == null || root.getPackageName().length() == 0)
                throw new NotesObservationPending("input_method_root_package_not_published");
            if (!service.packageName.contentEquals(root.getPackageName())) return "foreign_root_package";
        } finally { if (root != null) root.recycle(); }
        // Accessibility reports a touchable region, which can translate during
        // the IME animation and need not include the navigation bar. This
        // bounded allowance is only for hiding the verified keyboard, never
        // for clicking or taking a screenshot through its region.
        if (!plausibleInputMethodRegion(region, display)) return "unexplained_geometry";
        return null;
    }

    private static boolean plausibleInputMethodRegion(Rect region, Rect display) {
        return !region.isEmpty() && !display.isEmpty() && Rect.intersects(region, display)
                && region.left >= display.left && region.right <= display.right
                && region.top >= display.top && region.height() < display.height()
                && (long) region.bottom <= (long) display.bottom + region.height();
    }

    ServiceInfo configuredSystemInputMethod() {
        require(dedicated && target == NOTES_TARGET, "Only the frozen notes probe can inspect its system keyboard");
        String configured = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName component = configured == null ? null : ComponentName.unflattenFromString(configured);
        InputMethodManager manager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (component == null || manager == null) return null;
        List<InputMethodInfo> enabled = manager.getEnabledInputMethodList();
        if (enabled == null || enabled.size() > 16) return null;
        ServiceInfo matched = null;
        for (InputMethodInfo method : enabled) {
            ServiceInfo service = method.getServiceInfo();
            if (service != null && component.equals(new ComponentName(service.packageName, service.name))) {
                if (matched != null) return null;
                matched = service;
            }
        }
        if (matched == null || !Manifest.permission.BIND_INPUT_METHOD.equals(matched.permission) || matched.applicationInfo == null
                || (matched.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) return null;
        return matched;
    }

    /** At most one verified-keyboard BACK within the original bounded preparation. */
    void dismissNotesKeyboard() throws Exception {
        try (NotesTarget ignored = awaitNotesTarget(null, new NotesWait())) { /* Strictly unobscured stable window. */ }
    }

    private NotesTarget awaitNotesTarget(String resource, NotesWait wait) throws Exception {
        require(target == NOTES_TARGET, "Notes helper used for another application");
        do {
            wait.requireTime();
            WindowSnapshot window = null;
            AccessibilityNodeInfo node = null;
            boolean retryRead = false;
            try {
                // This snapshot can only identify a keyboard to dismiss. It is
                // never used as the source of a click, input action or capture.
                try (WindowSnapshot keyboard = appWindow(true)) {
                    if (!keyboard.inputMethod.isEmpty()) {
                        wait.pending("verified_input_method_present");
                        if (!wait.backSent) {
                            // A published IME region can be entering or already
                            // hiding. First establish the expected Notes page;
                            // then require positive surface readiness, not the
                            // negation of the capture parser's hidden predicate.
                            String targetIdentity = notesKeyboardTarget(keyboard, resource);
                            long now = SystemClock.elapsedRealtime();
                            if (wait.backTargetStable(targetIdentity, now) && wait.takeBackSample(now)) {
                                wait.requireTime();
                                String shown = SmokeCapture.visibleNotesInputMethod(this);
                                wait.requireTime();
                                if (wait.backSurfaceStable(shown, SystemClock.elapsedRealtime())) {
                                    try (WindowSnapshot current = appWindow(true)) {
                                        wait.requireTime();
                                        if (current.inputMethod.isEmpty())
                                            throw new NotesObservationPending("input_method_disappeared_before_dismissal");
                                        if (!targetIdentity.equals(notesKeyboardTarget(current, resource)))
                                            throw new NotesObservationPending("notes_target_changed_before_keyboard_dismissal");
                                    }
                                    wait.requireTime();
                                    if (wait.claimBack()) sendKey(KeyEvent.KEYCODE_BACK);
                                }
                            }
                        }
                        retryRead = true;
                        continue;
                    }
                }
                wait.clearBackReadiness();
                wait.requireTime();
                window = appWindow(false);
                if (resource != null) {
                    wait.requireTime();
                    node = uniqueNode(window, resource);
                    requireUsableNotesNode(window, node);
                }
                Rect nodeBounds = new Rect();
                if (node != null) node.getBoundsInScreen(nodeBounds);
                String identity = window.id + "/" + window.geometry + "/" + nodeBounds.toShortString();
                if (wait.stable(identity)) {
                    wait.requireTime();
                    NotesTarget ready = new NotesTarget(window, node);
                    window = null; node = null;
                    return ready;
                }
                retryRead = true;
            } catch (NotesObservationPending pending) {
                retryRead = true;
                wait.clearBackReadiness();
                wait.pending(pending.getMessage());
            } finally {
                if (node != null) node.recycle();
                if (window != null) window.close();
                if (retryRead && SystemClock.elapsedRealtime() < wait.deadline) SystemClock.sleep(50);
            }
        } while (SystemClock.elapsedRealtime() < wait.deadline);
        throw wait.timeout();
    }

    /** Read-only preparation: does not authorize a click through the IME. */
    private String notesKeyboardTarget(WindowSnapshot window, String resource) {
        require(!window.inputMethod.isEmpty() && window.inputMethodId >= 0, "Verified IME metadata is unavailable");
        Rect bounds = new Rect();
        if (resource != null) {
            AccessibilityNodeInfo node = uniqueNode(window, resource);
            try {
                require(node.isEnabled() && node.isVisibleToUser() && !node.isPassword()
                        && node.getWindowId() == window.id, "Notes dismissal target is not current and visible");
                node.getBoundsInScreen(bounds);
                require(!bounds.isEmpty() && Rect.intersects(window.bounds, bounds), "Notes dismissal target is outside the application");
            } finally { node.recycle(); }
        }
        return window.id + "/" + window.geometry + "/" + window.inputMethodId
                + "/" + window.inputMethod.toShortString() + "/" + bounds.toShortString();
    }

    private void sendKey(int key) {
        require(dedicated && target == NOTES_TARGET && (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_HOME),
                "Unexpected notes navigation key");
        long now = SystemClock.uptimeMillis();
        notesActionAttempt(key == KeyEvent.KEYCODE_BACK ? "KEY_BACK" : "KEY_HOME");
        boolean down = automation.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0), true);
        if (!down) notesActionResult(false);
        require(down, "Key down was rejected");
        require(notesActionResult(automation.injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0), true)), "Key up was rejected");
    }

    void notesCreateDialog() throws Exception {
        // The frozen release declares new_note as an always-visible toolbar action.
        notesClick("new_note");
        // The following title-input preparation waits for the new dialog itself;
        // do not independently dismiss its keyboard twice before that action.
    }

    void notesClick(String resource) throws Exception {
        NotesWait wait = new NotesWait();
        try (NotesTarget ready = awaitNotesTarget(resource, wait)) {
            wait.requireTime();
            require(ready.node.isClickable(), "Notes resource is not clickable");
            // The actual action is outside all observation retry loops.
            notesActionAttempt("new_note".equals(resource) ? "CLICK_NEW_NOTE"
                    : "type_text_note".equals(resource) ? "CLICK_TEXT_TYPE"
                    : "android:id/button1".equals(resource) ? "CLICK_CONFIRM" : "CLICK_OTHER");
            require(notesActionResult(ready.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)), "Notes resource rejected its click");
        }
        automation.waitForIdle(150, 5_000);
    }

    void notesSetText(String resource, String text) throws Exception {
        require("locked_note_title".equals(resource) || "text_note_view".equals(resource), "Unexpected notes text field");
        require(text.length() <= 256, "Synthetic note is too large");
        NotesWait wait = new NotesWait();
        try (NotesTarget ready = awaitNotesTarget(resource, wait)) {
            require(ready.node.isEditable() && !ready.node.isPassword(), "Notes resource is not an unprotected editable field");
            Bundle data = new Bundle(); data.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            wait.requireTime();
            notesActionAttempt("locked_note_title".equals(resource) ? "SET_TITLE" : "SET_BODY");
            require(notesActionResult(ready.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, data)), "Notes text action was rejected");
        }
        // Verification shares the original deadline and one BACK allowance. It
        // may use that allowance if unused; it never resends the text action.
        notesAwaitText(resource, text, wait);
    }

    private void requireUsableNotesNode(WindowSnapshot window, AccessibilityNodeInfo node) {
        require(node != null && node.isVisibleToUser() && node.isEnabled() && node.getWindowId() == window.id,
                "Notes resource is not current, enabled and visible");
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        require(!bounds.isEmpty() && Rect.intersects(window.bounds, bounds) && window.inputMethod.isEmpty(),
                "Notes resource is outside the unobscured application window");
    }

    void awaitNotesResource(String resource) throws Exception {
        try (NotesTarget ignored = awaitNotesTarget(resource, new NotesWait())) { /* Readiness only. */ }
    }

    String notesAwaitText(String resource, String expected) throws Exception {
        return notesAwaitText(resource, expected, new NotesWait());
    }

    private String notesAwaitText(String resource, String expected, NotesWait wait) throws Exception {
        String actual = "";
        do {
            try (NotesTarget ready = awaitNotesTarget(resource, wait)) {
                actual = ready.node.getText() == null ? "" : ready.node.getText().toString();
                require(actual.length() <= 1024, "Unexpected notes text length");
                wait.requireTime();
                if (expected.equals(actual)) return actual;
            }
            if (SystemClock.elapsedRealtime() < wait.deadline) SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < wait.deadline);
        throw new ResultMismatch(actual);
    }

    String notesAssertTitle(String expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        String last = "exact_title_not_published";
        do {
            try (WindowSnapshot window = appWindow(false)) {
                List<AccessibilityNodeInfo> found = window.root.findAccessibilityNodeInfosByText(expected);
                int exact = 0;
                try {
                    require(found != null && found.size() <= 32, "Notes title result is unavailable or ambiguous");
                    for (AccessibilityNodeInfo node : found) {
                        if (node.isVisibleToUser() && node.getText() != null && expected.contentEquals(node.getText())) {
                            requireUsableNotesNode(window, node); exact++;
                        }
                    }
                } finally { if (found != null) for (AccessibilityNodeInfo node : found) node.recycle(); }
                if (exact == 1) {
                    require(SystemClock.elapsedRealtime() < deadline, "Created notes title arrived after its 5000ms observation deadline");
                    return expected;
                }
                require(exact == 0, "Created notes title is ambiguous: exact=" + exact);
                last = "exact_title_not_published";
            } catch (NotesObservationPending pending) {
                last = pending.getMessage();
            }
            // Creation publishes the database-backed pager title asynchronously.
            // Wait only for its exact text; never replay the create action, hide
            // an unknown window, accept a different title or drop uniqueness.
            if (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Created notes title did not become uniquely visible within 5000ms: " + last);
    }

    void notesBackgroundForSave() throws Exception {
        NotesWait wait = new NotesWait();
        try (NotesTarget ready = awaitNotesTarget(null, wait)) {
            wait.requireTime();
            sendKey(KeyEvent.KEYCODE_HOME);
        }
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (focusedTargetWindowId() != -1) {
            require(SystemClock.elapsedRealtime() < deadline, "Notes did not leave the foreground for normal save");
            SystemClock.sleep(50);
        }
        // Wait for actual accessibility idle after normal onPause, not a fabricated DB write.
        automation.waitForIdle(600, 5_000);
    }

    void notesRestartInFreshProcess() throws Exception {
        require(target == NOTES_TARGET && focusedTargetWindowId() == -1, "Notes must first complete normal background navigation");
        String before = shell("pidof " + target.packageName).trim();
        require(before.matches("[1-9][0-9]*"), "Expected one live notes process before restart");
        shell("am force-stop --user 0 " + target.packageName);
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (!shell("pidof " + target.packageName).trim().isEmpty()) {
            require(SystemClock.elapsedRealtime() < deadline, "Old notes process did not stop");
            SystemClock.sleep(50);
        }
        launchTarget();
        String after = shell("pidof " + target.packageName).trim();
        require(after.matches("[1-9][0-9]*") && !after.equals(before), "Notes did not reopen in a fresh process");
    }

    static boolean isContainedInVisibleSystemBar(int type, Rect region, Rect display, Insets bars) {
        // Match the product's platform-metrics policy. Titles and owner packages vary
        // across system images; only SYSTEM windows wholly inside visible platform
        // bar strips qualify. An IME, dialog, expanded shade or overlay still blocks.
        if (type != AccessibilityWindowInfo.TYPE_SYSTEM || region.isEmpty() || display.isEmpty()
                || !display.contains(region) || bars.left < 0 || bars.top < 0 || bars.right < 0 || bars.bottom < 0
                || bars.left + bars.right >= display.width() || bars.top + bars.bottom >= display.height()) return false;
        return (bars.top > 0 && new Rect(display.left, display.top, display.right, display.top + bars.top).contains(region))
                || (bars.bottom > 0 && new Rect(display.left, display.bottom - bars.bottom, display.right, display.bottom).contains(region))
                || (bars.left > 0 && new Rect(display.left, display.top, display.left + bars.left, display.bottom).contains(region))
                || (bars.right > 0 && new Rect(display.right - bars.right, display.top, display.right, display.bottom).contains(region));
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

    private AccessibilityNodeInfo uniqueNode(WindowSnapshot window, String id) {
        List<AccessibilityNodeInfo> found = window.root.findAccessibilityNodeInfosByViewId(id.startsWith("android:id/") ? id : target.packageName + ":id/" + id);
        AccessibilityNodeInfo selected = null;
        try {
            require(found != null && found.size() <= 16, "Missing or ambiguous application resource");
            for (AccessibilityNodeInfo node : found) {
                if (!node.isVisibleToUser()) continue;
                require(selected == null && node.getWindowId() == window.id, "Resource did not map to a unique current application window");
                require(target.packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName()), "Unexpected resource package");
                selected = AccessibilityNodeInfo.obtain(node);
            }
            if (selected == null && target == NOTES_TARGET) throw new NotesObservationPending("exact_resource_not_published");
            require(selected != null, "Visible application resource was not found");
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

    static final class Target {
        final String appId, packageName, versionName, apkSha256;
        final long versionCode;
        private Target(String appId, String packageName, String versionName, long versionCode, String apkSha256) {
            this.appId = appId; this.packageName = packageName; this.versionName = versionName;
            this.versionCode = versionCode; this.apkSha256 = apkSha256;
        }
    }

    static final class WindowSnapshot implements AutoCloseable {
        final int id; final Rect bounds; final AccessibilityNodeInfo root; final String geometry;
        final Rect inputMethod;
        final int inputMethodId;
        WindowSnapshot(int id, Rect bounds, AccessibilityNodeInfo root, String geometry) {
            this(id, bounds, root, geometry, new Rect(), -1);
        }
        WindowSnapshot(int id, Rect bounds, AccessibilityNodeInfo root, String geometry, Rect inputMethod, int inputMethodId) {
            this.id = id; this.bounds = new Rect(bounds); this.root = root; this.geometry = geometry;
            this.inputMethod = new Rect(inputMethod); this.inputMethodId = inputMethodId;
        }
        @Override public void close() { root.recycle(); }
    }

    private static final class NotesTarget implements AutoCloseable {
        final WindowSnapshot window;
        final AccessibilityNodeInfo node;
        NotesTarget(WindowSnapshot window, AccessibilityNodeInfo node) { this.window = window; this.node = node; }
        @Override public void close() { if (node != null) node.recycle(); window.close(); }
    }

    private static final class NotesWait {
        final long deadline = SystemClock.elapsedRealtime() + 5_000;
        boolean backSent;
        String identity, last = "target_not_stable";
        long since;
        String backTarget, backSurface, lastBackVisibility = "NOT_SAMPLED";
        long backTargetSince, backSurfaceSince, nextBackSample;
        int backSamples;
        boolean backTargetStable(String current, long now) {
            if (!current.equals(backTarget)) {
                backTarget = current; backTargetSince = now;
                backSurface = null; backSurfaceSince = 0;
                return false;
            }
            return now - backTargetSince >= 600;
        }
        boolean takeBackSample(long now) {
            if (backSent || now < nextBackSample || backSamples >= 4) return false;
            backSamples++; nextBackSample = now + 150; return true;
        }
        boolean backSurfaceStable(String current, long now) {
            lastBackVisibility = current == null ? "NOT_READY" : "VISIBLE";
            if (current == null) { backSurface = null; backSurfaceSince = 0; return false; }
            if (!current.equals(backSurface)) { backSurface = current; backSurfaceSince = now; return false; }
            return now - backSurfaceSince >= 150;
        }
        boolean claimBack() {
            if (backSent) return false;
            backSent = true; return true;
        }
        void clearBackReadiness() { backTarget = null; backSurface = null; backTargetSince = 0; backSurfaceSince = 0; }
        void pending(String reason) { identity = null; since = 0; last = reason; }
        boolean stable(String current) {
            long now = SystemClock.elapsedRealtime();
            if (!current.equals(identity)) { identity = current; since = now; last = "target_not_stable"; return false; }
            return now - since >= 600;
        }
        void requireTime() { if (SystemClock.elapsedRealtime() >= deadline) throw timeout(); }
        AssertionError timeout() { return new AssertionError("Notes observation timed out: reason=" + last + " backSent=" + backSent
                + " imeSamples=" + backSamples + " lastImeSurface=" + lastBackVisibility); }
    }

    static void verifyNotesBackPreparation() {
        // Pure data/time inputs, no input injection, app or screenshot evidence.
        NotesWait wait = new NotesWait();
        long originalDeadline = wait.deadline;
        require(!wait.backTargetStable("page-ime-A", 0) && !wait.backTargetStable("page-ime-A", 599)
                && wait.backTargetStable("page-ime-A", 600), "Keyboard dismissal skipped page stability");
        require(wait.takeBackSample(600) && !wait.takeBackSample(749), "Keyboard surface reads were not bounded in time");
        require(!wait.backSurfaceStable("surface-A", 600) && !wait.backSurfaceStable("surface-A", 749)
                && wait.backSurfaceStable("surface-A", 750), "One surface read admitted BACK");
        require(!wait.backSurfaceStable(null, 751) && !wait.backSurfaceStable("surface-A", 752), "Hidden/transitional surface kept old readiness");
        require(!wait.backTargetStable("page-ime-B", 800) && !wait.backSurfaceStable("surface-A", 801), "New target reused old surface readiness");
        require(wait.takeBackSample(900) && wait.takeBackSample(1050) && wait.takeBackSample(1200)
                && !wait.takeBackSample(1400), "Keyboard surface sampling exceeded its original preparation bound");
        wait.clearBackReadiness();
        require(!wait.takeBackSample(1600), "Readiness reset renewed surface sample budget");
        require(wait.claimBack() && !wait.claimBack(), "Keyboard dismissal was claimed twice");
        wait.clearBackReadiness();
        require(!wait.claimBack() && !wait.takeBackSample(1800), "Accepted or unknown BACK could be retried");
        require(wait.deadline == originalDeadline, "Readiness changes renewed the original timeout");
    }

    /** Only these known incomplete reads are eligible for another observation. */
    private static final class NotesObservationPending extends AssertionError {
        NotesObservationPending(String reason) { super(reason); }
    }

    static final class StableWindow {
        private final int previous;
        private int id = -1;
        private String geometry;
        private long since;
        StableWindow(int previous) { this.previous = previous; }
        boolean observe(int currentId, String currentGeometry, long now) {
            if (currentId == previous) { reset(); return false; }
            if (currentId != id || !currentGeometry.equals(geometry)) {
                id = currentId; geometry = currentGeometry; since = now; return false;
            }
            return now - since >= 600;
        }
        void reset() { id = -1; geometry = null; since = 0; }
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
