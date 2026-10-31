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
import org.json.JSONArray;

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
    private int notesHomeAttempts;
    private NotesConfirmation notesConfirmation;

    int notesHomeAttempts() { return notesHomeAttempts; }

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
        boolean versionFour = "agent-os-third-party-smoke-v4".equals(args.getString("syntheticTestMarker"));
        require(versionFour || "agent-os-third-party-smoke-v1".equals(args.getString("syntheticTestMarker")), "Dedicated smoke test marker is required");
        require((versionFour ? "agent-os-third-party-fresh-install-v4" : "agent-os-third-party-fresh-install-v1")
                .equals(args.getString("freshInstallMarker")), "Fresh isolated installation marker is required");
        if (versionFour) require("calculator-1.4.0-notes-1.7.0-v4".equals(args.getString("sampleId")), "Unknown frozen application set");
        require(matches(args.getString("deviceSerial"), "emulator-[0-9]{1,6}"), "An emulator transport serial is required");
        require(matches(args.getString("sourceSha"), "[0-9a-f]{40}"), "A source SHA is required");
        require(matches(args.getString("runId"), "[1-9][0-9]{0,19}"), "A CI run ID is required");
        require(matches(args.getString("runAttempt"), "[1-9][0-9]{0,5}"), "A CI attempt is required");
        require(matches(args.getString("expectedApi"), versionFour ? "30|31|32|33|34|35|36|37" : "30|35|37"), "Only the frozen smoke API samples are allowed");
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
        return appWindow(allowInputMethod, false);
    }

    private WindowSnapshot appWindow(boolean allowInputMethod, boolean inspectNonIntersectingInputMethod) {
        return appWindow(allowInputMethod, inspectNonIntersectingInputMethod, null);
    }

    private WindowSnapshot appWindow(boolean allowInputMethod, boolean inspectNonIntersectingInputMethod,
                                     NotesConfirmation confirmation) {
        require(!allowInputMethod || target == NOTES_TARGET, "Only the notes editing probe may inspect an IME");
        require(!inspectNonIntersectingInputMethod || (allowInputMethod && target == NOTES_TARGET),
                "Only Notes confirmation may inspect a nonintersecting input method");
        NotesImeInventory inventory = confirmation == null ? null : confirmation.beginInventory();
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (inventory != null) inventory.readCompletedElapsedMillis = SystemClock.elapsedRealtime();
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
            if (inventory != null) {
                inventory.focusedWindowId = focused.getId();
                inventory.focusedLayer = focused.getLayer();
                // These are metadata getters on the same returned inventory;
                // no second getWindows, getRoot or shell observation is made.
                for (AccessibilityWindowInfo observed : windows) {
                    if (observed.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
                        inventory.ime(observed.getId(), observed.getDisplayId(), observed.getLayer());
                }
                inventory.scanComplete = true;
            }
            for (AccessibilityWindowInfo other : windows) {
                if (other.getId() == focused.getId() || other.getDisplayId() != Display.DEFAULT_DISPLAY
                        || other.getLayer() < focused.getLayer()) continue;
                Rect overlay = new Rect(); other.getBoundsInScreen(overlay);
                // The v4 confirmation must distinguish a keyboard outside the
                // dialog from no keyboard. Ordinary reads retain their filter.
                if (inspectNonIntersectingInputMethod
                        ? !inspectConfirmationWindow(other.getType(), bounds, overlay)
                        : !Rect.intersects(bounds, overlay)) continue;
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
            WindowSnapshot result = new WindowSnapshot(focused.getId(), bounds, root, geometry, inputMethod, inputMethodId, display);
            root = null;
            return result;
        } finally {
            if (root != null) root.recycle();
            recycle(windows);
        }
    }

    private static boolean inspectConfirmationWindow(int type, Rect app, Rect other) {
        return type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || Rect.intersects(app, other);
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
            if (root.getWindowId() != window.getId()) return "input_method_root_window_mismatch";
        } finally { if (root != null) root.recycle(); }
        // Accessibility reports a touchable region, which can translate during
        // the IME animation and need not include the navigation bar. This
        // bounded allowance only permits observing an animated keyboard. The
        // editing helper separately requires settled geometry and an uncovered
        // target; capture still rejects every visible keyboard.
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

    /** Ordinary editing retains v3's rule: a known keyboard is not a navigation request. */
    private NotesTarget awaitNotesTarget(String resource, NotesWait wait) throws Exception {
        return awaitNotesTarget(resource, null, wait);
    }

    private NotesTarget awaitNotesTarget(String resource, String exactText, NotesWait wait) throws Exception {
        return awaitNotesTarget(resource, exactText, wait, null, false);
    }

    private NotesTarget awaitNotesTarget(String resource, String exactText, NotesWait wait,
                                         NotesConfirmation confirmation, boolean afterBackDecision) throws Exception {
        require(target == NOTES_TARGET && (resource != null || exactText != null), "A real Notes target is required");
        do {
            wait.requireTime();
            wait.beginObservation(notesStage, NotesResource.forTarget(resource));
            WindowSnapshot window = null;
            AccessibilityNodeInfo node = null;
            boolean retryRead = false;
            try {
                window = appWindow(true, confirmation != null, confirmation);
                wait.observation.window(window.id, window.bounds, window.display, window.inputMethodId, window.inputMethod);
                Rect titleBounds = null;
                if (confirmation != null) {
                    wait.requireTime();
                    confirmation.requireDialog(window.id);
                    if (afterBackDecision) {
                        if (!window.inputMethod.isEmpty()) throw new NotesObservationPending("confirmation_input_method_still_present");
                        // Bind the title and button to this same fresh window, never
                        // a button with the same resource ID in a replacement dialog.
                        wait.beginObservation(notesStage, NotesResource.TITLE_FIELD);
                        wait.observation.window(window.id, window.bounds, window.display, window.inputMethodId, window.inputMethod);
                        AccessibilityNodeInfo title = uniqueNode(window, "locked_note_title", wait.observation);
                        try {
                            requireUsableNotesNode(window, title, wait.observation);
                            confirmation.title(title, false, false);
                            titleBounds = new Rect(wait.observation.targetBounds);
                        } finally { title.recycle(); }
                        wait.beginObservation(notesStage, NotesResource.forTarget(resource));
                        wait.observation.window(window.id, window.bounds, window.display, window.inputMethodId, window.inputMethod);
                    }
                }
                node = resource != null ? uniqueNode(window, resource, wait.observation) : uniqueNotesText(window, exactText);
                requireUsableNotesNode(window, node, wait.observation);
                if (confirmation != null && !afterBackDecision) confirmation.title(node, true, !window.inputMethod.isEmpty());
                if (!window.inputMethod.isEmpty() && !window.display.contains(window.inputMethod))
                    throw new NotesObservationPending("input_method_geometry_not_settled");
                Rect nodeBounds = new Rect(); node.getBoundsInScreen(nodeBounds);
                String identity = window.id + "/" + window.geometry + "/" + nodeBounds.toShortString()
                        + "/" + window.inputMethodId + "/" + window.inputMethod.toShortString()
                        + (titleBounds == null ? "" : "/title=" + titleBounds.toShortString());
                if (!wait.targetStable(identity, SystemClock.elapsedRealtime())) {
                    if (confirmation != null) confirmation.clearHiddenReadiness();
                    retryRead = true; continue;
                }
                if (!window.inputMethod.isEmpty() && !wait.surfaceReady) {
                    wait.pendingReason("verified_input_method_not_ready");
                    if (wait.takeSurfaceSample(SystemClock.elapsedRealtime())) {
                        wait.requireTime();
                        if (confirmation != null) {
                            confirmation.requireIme(configuredSystemInputMethod());
                            wait.requireTime();
                        }
                        String shown = SmokeCapture.visibleNotesInputMethod(this);
                        wait.requireTime();
                        wait.observeSurface(shown, SystemClock.elapsedRealtime());
                    }
                    // Reacquire the actual app, target and IME after the shell read.
                    // No click or text action uses the pre-read accessibility tree.
                    retryRead = true; continue;
                }
                if (confirmation != null && afterBackDecision && !confirmation.imeHiddenVerified) {
                    wait.pendingReason("confirmation_input_method_hidden_not_verified");
                    if (confirmation.takeHiddenSample(SystemClock.elapsedRealtime())) {
                        wait.requireTime();
                        confirmation.requireIme(configuredSystemInputMethod());
                        wait.requireTime();
                        NotesHiddenSample sample = confirmation.beginHiddenDiagnostic();
                        String hidden;
                        try {
                            hidden = SmokeCapture.hiddenNotesInputMethodForConfirmation(this, sample.wms);
                        } finally {
                            sample.endedElapsedMillis = SystemClock.elapsedRealtime();
                        }
                        wait.requireTime();
                        confirmation.observeHidden(hidden, SystemClock.elapsedRealtime());
                    }
                    // A hidden sample cannot authorize a click using a tree from
                    // before the shell read. The next iteration reacquires all nodes.
                    retryRead = true; continue;
                }
                wait.requireTime();
                NotesTarget ready = new NotesTarget(window, node);
                window = null; node = null;
                return ready;
            } catch (NotesObservationPending pending) {
                retryRead = true;
                if (confirmation != null) confirmation.clearHiddenReadiness();
                wait.pending(pending.getMessage());
            } finally {
                if (node != null) node.recycle();
                if (window != null) window.close();
                if (retryRead && SystemClock.elapsedRealtime() < wait.deadline) SystemClock.sleep(50);
            }
        } while (SystemClock.elapsedRealtime() < wait.deadline);
        throw wait.timeout();
    }

    private AccessibilityNodeInfo uniqueNotesText(WindowSnapshot window, String text) {
        List<AccessibilityNodeInfo> found = window.root.findAccessibilityNodeInfosByText(text);
        AccessibilityNodeInfo exact = null;
        try {
            require(found != null && found.size() <= 32, "Notes title result is unavailable or ambiguous");
            for (AccessibilityNodeInfo node : found) {
                if (node.isVisibleToUser() && node.getText() != null && text.contentEquals(node.getText())) {
                    require(exact == null, "Created notes title is ambiguous");
                    exact = AccessibilityNodeInfo.obtain(node);
                }
            }
            if (exact == null) throw new NotesObservationPending("exact_title_not_published");
            AccessibilityNodeInfo result = exact; exact = null; return result;
        } finally {
            if (exact != null) exact.recycle();
            if (found != null) for (AccessibilityNodeInfo node : found) node.recycle();
        }
    }

    private void sendKey(int key) {
        require(dedicated && target == NOTES_TARGET && key == KeyEvent.KEYCODE_HOME,
                "Unexpected notes navigation key");
        long now = SystemClock.uptimeMillis();
        require(notesHomeAttempts == 0, "Normal background navigation cannot be repeated");
        notesHomeAttempts++;
        notesActionAttempt("KEY_HOME");
        boolean down = automation.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0), true);
        if (!down) notesActionResult(false);
        require(down, "Key down was rejected");
        require(notesActionResult(automation.injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0), true)), "Key up was rejected");
    }

    void notesCreateDialog() throws Exception {
        // The frozen release declares new_note as an always-visible toolbar action.
        notesClick("new_note");
        // The following title-input preparation observes the new dialog and its
        // actual uncovered target; the app's default keyboard remains enabled.
    }

    /** v4 only: one BACK immediately before confirming this exact creation dialog. */
    void notesConfirmCreate(String expectedTitle) throws Exception {
        require(dedicated && target == NOTES_TARGET && notesStage == NotesStage.CONFIRM_CREATE
                && notesConfirmation == null, "Unexpected or repeated Notes confirmation preparation");
        require(expectedTitle != null && expectedTitle.length() <= 256, "Invalid synthetic title bound");
        NotesConfirmation confirmation = new NotesConfirmation(expectedTitle);
        notesConfirmation = confirmation;
        NotesWait wait = confirmation.wait;
        Throwable originalFailure = null;
        try {
            confirmation.stage = ConfirmationStage.TITLE_PREPARATION;
            confirmation.requireIme(configuredSystemInputMethod());
            try (NotesTarget title = awaitNotesTarget("locked_note_title", null, wait, confirmation, false)) {
                wait.requireTime();
                confirmation.backDecisionInventory = confirmation.lastInventory;
                if (!title.window.inputMethod.isEmpty()) {
                    require(wait.surfaceReady, "Visible keyboard preparation was not completed");
                    confirmation.requireIme(configuredSystemInputMethod());
                    confirmation.backPreconditionVerified = true;
                    sendConfirmationBack(confirmation);
                }
            }
            // Changing from title to button resets geometric stability, while
            // the original deadline and all consumed samples remain unchanged.
            confirmation.enterHiddenPreparation();
            try (NotesTarget button = awaitNotesTarget("android:id/button1", null, wait, confirmation, true)) {
                wait.requireTime();
                confirmation.requireDialog(button.window.id);
                confirmation.requireIme(configuredSystemInputMethod());
                require(confirmation.imeHiddenVerified && button.window.inputMethod.isEmpty()
                        && button.node.isClickable(), "Notes confirmation was not independently ready");
                confirmation.finalDialogWindowId = button.window.id;
                confirmation.confirmTargetReached = true;
                wait.requireTime();
                confirmation.stage = ConfirmationStage.CONFIRM_SUBMISSION;
                confirmation.confirmAttempts++;
                notesActionAttempt("CLICK_CONFIRM");
                confirmation.confirmAccepted = button.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                notesActionResult(confirmation.confirmAccepted);
                require(confirmation.confirmAccepted, "Notes resource rejected its click");
                long completed = SystemClock.elapsedRealtime();
                if (completed >= wait.deadline) throw wait.timeout();
                confirmation.elapsedMillis = completed - confirmation.started;
                confirmation.stage = ConfirmationStage.CONFIRM_ACCEPTED;
            }
        } catch (Exception | AssertionError original) {
            originalFailure = original;
            throw original;
        } finally {
            if (confirmation.stage != ConfirmationStage.CONFIRM_ACCEPTED)
                confirmation.elapsedMillis = Math.max(0, SystemClock.elapsedRealtime() - confirmation.started);
            // Freeze after the original elapsed-time accounting, but before
            // caller capture/cleanup. The original failure and v4 JSON stay intact.
            if (originalFailure != null) confirmation.attachFailureDiagnostic(originalFailure);
        }
        automation.waitForIdle(150, 5_000);
    }

    private void sendConfirmationBack(NotesConfirmation confirmation) throws Exception {
        confirmation.wait.requireTime();
        confirmation.claimBack();
        confirmation.stage = ConfirmationStage.BACK_SUBMISSION;
        long downTime = SystemClock.uptimeMillis();
        Throwable failure = null;
        notesActionAttempt("KEY_BACK");
        confirmation.backDownAttempted = true;
        try {
            confirmation.backDownAccepted = automation.injectInputEvent(
                    new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true);
            require(confirmation.backDownAccepted, "BACK key down was rejected");
        } catch (Exception | AssertionError original) { failure = original; }
        finally {
            // One paired UP closes the already-started attempt, even if DOWN's
            // synchronous call failed or overran the deadline. No second DOWN.
            confirmation.backUpAttempted = true;
            try {
                confirmation.backUpAccepted = automation.injectInputEvent(new KeyEvent(downTime,
                        SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true);
                require(confirmation.backUpAccepted, "BACK key up was rejected");
            } catch (Exception | AssertionError cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (confirmation.backDownAccepted != null && confirmation.backUpAccepted != null)
            notesActionResult(confirmation.backDownAccepted && confirmation.backUpAccepted);
        if (failure instanceof AssertionError) throw (AssertionError) failure;
        if (failure != null) throw (Exception) failure;
        confirmation.wait.requireTime();
    }

    JSONObject notesConfirmationResult() throws Exception {
        return notesConfirmation == null ? new NotesConfirmation(null).json() : notesConfirmation.json();
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
        // Verification shares the original deadline. It never navigates away,
        // dismisses the keyboard or resends the text action.
        notesAwaitText(resource, text, wait);
    }

    private void requireUsableNotesNode(WindowSnapshot window, AccessibilityNodeInfo node, NotesRead observation) {
        observation.readPhase = NotesReadPhase.TARGET_CHECK;
        observation.targetPresent = node != null;
        // Record only the original short-circuited reads, in their original order.
        require(node != null && observation.visible(node.isVisibleToUser()) && observation.enabled(node.isEnabled())
                        && !node.isPassword() && observation.windowId(node.getWindowId()) == window.id,
                "Notes resource is not current, enabled and visible");
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        observation.targetBounds = new Rect(bounds);
        try {
            require(fullyInside(window.bounds, bounds), "Notes resource is not fully inside its original application window");
        } catch (AssertionError original) {
            NotesTargetGeometryDiagnostic.attach(original, observation, lastNotesAction, lastNotesActionResult,
                    notesActionAttempts, notesHomeAttempts);
            throw original;
        }
        if (!uncoveredByInputMethod(bounds, window.inputMethod))
            throw new NotesObservationPending("notes_target_covered_by_verified_input_method");
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

    String notesAssertTitle(String expected) throws Exception {
        try (NotesTarget ready = awaitNotesTarget(null, expected, new NotesWait())) {
            return expected;
        }
    }

    private static boolean fullyInside(Rect app, Rect target) {
        return !app.isEmpty() && !target.isEmpty() && app.contains(target);
    }

    private static boolean uncoveredByInputMethod(Rect target, Rect ime) {
        return ime.isEmpty() || !Rect.intersects(target, ime);
    }

    void notesBackgroundForSave() throws Exception {
        NotesWait wait = new NotesWait();
        try (NotesTarget ready = awaitNotesTarget("text_note_view", wait)) {
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
        return uniqueNode(window, id, null);
    }

    private AccessibilityNodeInfo uniqueNode(WindowSnapshot window, String id, NotesRead observation) {
        List<AccessibilityNodeInfo> found = window.root.findAccessibilityNodeInfosByViewId(id.startsWith("android:id/") ? id : target.packageName + ":id/" + id);
        AccessibilityNodeInfo selected = null;
        try {
            if (observation != null) observation.matches(found == null ? null : found.size());
            require(found != null && found.size() <= 16, "Missing or ambiguous application resource");
            for (AccessibilityNodeInfo node : found) {
                if (!node.isVisibleToUser()) continue;
                if (observation != null) observation.visibleMatchCount++;
                require(selected == null && node.getWindowId() == window.id, "Resource did not map to a unique current application window");
                require(target.packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName()), "Unexpected resource package");
                selected = AccessibilityNodeInfo.obtain(node);
            }
            if (observation != null) observation.matchScanComplete = true;
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
        final Rect inputMethod, display;
        final int inputMethodId;
        WindowSnapshot(int id, Rect bounds, AccessibilityNodeInfo root, String geometry) {
            this(id, bounds, root, geometry, new Rect(), -1, bounds);
        }
        WindowSnapshot(int id, Rect bounds, AccessibilityNodeInfo root, String geometry, Rect inputMethod, int inputMethodId, Rect display) {
            this.id = id; this.bounds = new Rect(bounds); this.root = root; this.geometry = geometry;
            this.inputMethod = new Rect(inputMethod); this.inputMethodId = inputMethodId;
            this.display = new Rect(display);
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
        String identity, last = "target_not_stable", surface, lastVisibility = "NOT_SAMPLED";
        long since, surfaceSince, nextSample;
        int samples;
        boolean surfaceReady;
        NotesRead observation = new NotesRead(NotesStage.UNSET, NotesResource.NOT_SELECTED, NotesReadPhase.NOT_STARTED);
        void beginObservation(NotesStage stage, NotesResource resource) {
            // Last observation only: an incomplete new window read must never
            // inherit the previous iteration's node or geometry.
            observation = new NotesRead(stage, resource, NotesReadPhase.WINDOW_PENDING);
        }
        boolean targetStable(String current, long now) {
            if (!current.equals(identity)) {
                identity = current; since = now; clearSurface(); last = "target_not_stable"; return false;
            }
            return now - since >= 600;
        }
        boolean takeSurfaceSample(long now) {
            if (surfaceReady || now < nextSample || samples >= 4) return false;
            samples++; nextSample = now + 150; return true;
        }
        void observeSurface(String current, long now) {
            lastVisibility = current == null ? "NOT_READY" : "VISIBLE";
            if (current == null) { clearSurface(); return; }
            if (!current.equals(surface)) { surface = current; surfaceSince = now; surfaceReady = false; return; }
            surfaceReady = now - surfaceSince >= 150;
        }
        void clearSurface() { surface = null; surfaceSince = 0; surfaceReady = false; }
        void pendingReason(String reason) { last = reason; }
        void pending(String reason) { identity = null; since = 0; clearSurface(); last = reason; }
        void requireTime() { if (SystemClock.elapsedRealtime() >= deadline) throw timeout(); }
        AssertionError timeout() { return new NotesPreparationTimeout("Notes v4 observation timed out: reason=" + last
                + " automaticBackPolicy=single-back-only-before-notes-create-confirm imeSamples=" + samples
                + " lastImeSurface=" + lastVisibility, observation); }
    }

    private enum ConfirmationStage {
        NOT_STARTED, TITLE_PREPARATION, BACK_SUBMISSION, HIDDEN_PREPARATION, CONFIRM_SUBMISSION, CONFIRM_ACCEPTED
    }

    /** Metadata from one existing getWindows result, without node/root/text reads. */
    private static final class NotesImeInventory {
        static final int CAPACITY = 8;
        final int sequence;
        final long startedElapsedMillis;
        long readCompletedElapsedMillis = -1;
        int focusedWindowId = -1, focusedLayer = -1, imeCount;
        boolean scanComplete;
        final int[][] entries = new int[CAPACITY][];
        NotesImeInventory(int sequence, long startedElapsedMillis) {
            this.sequence = sequence; this.startedElapsedMillis = startedElapsedMillis;
        }
        void ime(int id, int display, int layer) {
            if (imeCount < CAPACITY) entries[imeCount] = new int[]{id, display, layer};
            imeCount++;
        }
        JSONObject json() throws Exception {
            JSONArray observed = new JSONArray();
            for (int[] entry : entries) if (entry != null) {
                String filter = entry[0] == focusedWindowId ? "SAME_WINDOW"
                        : entry[1] != Display.DEFAULT_DISPLAY ? "OTHER_DISPLAY"
                        : entry[2] < focusedLayer ? "BELOW_FOCUSED" : "CANDIDATE_REQUIRES_IDENTITY";
                observed.put(new JSONArray().put(entry[0]).put(entry[1]).put(entry[2]).put(filter));
            }
            return new JSONObject().put("sequence", sequence).put("startedElapsedMillis", startedElapsedMillis)
                    .put("readCompletedElapsedMillis", readCompletedElapsedMillis)
                    .put("focusedWindowId", focusedWindowId).put("focusedLayer", focusedLayer)
                    .put("scanComplete", scanComplete).put("imeCount", imeCount)
                    .put("notRetained", Math.max(0, imeCount - CAPACITY)).put("imeEntries", observed);
        }
    }

    private static final class NotesHiddenSample {
        final int ordinal;
        final long startedElapsedMillis;
        final NotesImeInventory beforeInventory;
        final JSONObject wms = new JSONObject();
        long endedElapsedMillis = -1;
        NotesHiddenSample(int ordinal, NotesImeInventory beforeInventory) {
            this.ordinal = ordinal; this.beforeInventory = beforeInventory;
            startedElapsedMillis = SystemClock.elapsedRealtime();
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("ordinal", ordinal).put("startedElapsedMillis", startedElapsedMillis)
                    .put("endedElapsedMillis", endedElapsedMillis).put("wms", wms)
                    .put("beforeInventory", beforeInventory == null ? JSONObject.NULL : beforeInventory.json());
        }
    }

    private static final class NotesConfirmation {
        static final int MAX_JSON_CHARS = 2048;
        static final int MAX_DIAGNOSTIC_CHARS = 16 * 1024;
        static final String DIAGNOSTIC_MARKER = "NOTES_CONFIRMATION_DIAGNOSTIC_V1 ";
        final NotesWait wait = new NotesWait();
        final long started = wait.deadline - 5_000;
        private final String expectedTitle;
        private Integer imeUid;
        private String imeComponent, hiddenIdentity;
        private long hiddenSince, nextHiddenSample;
        ConfirmationStage stage = ConfirmationStage.NOT_STARTED;
        int backAttempts, hiddenSamples, confirmAttempts;
        long elapsedMillis;
        boolean backDownAttempted, backUpAttempted, backPreconditionVerified, imeHiddenVerified, confirmTargetReached;
        Boolean backDownAccepted, backUpAccepted, preBackTitleMatched, preBackTitleFocused, preBackTitleEditable;
        Boolean postTitleMatched, postTitleFocused, postTitleEditable, confirmAccepted;
        Integer originalDialogWindowId, finalDialogWindowId;
        private int inventorySequence, hiddenDiagnosticCount;
        private NotesImeInventory lastInventory, backDecisionInventory;
        private final NotesHiddenSample[] hiddenDiagnostics = new NotesHiddenSample[4];
        NotesConfirmation(String expectedTitle) { this.expectedTitle = expectedTitle; }
        NotesImeInventory beginInventory() {
            lastInventory = new NotesImeInventory(++inventorySequence, SystemClock.elapsedRealtime());
            return lastInventory;
        }
        NotesHiddenSample beginHiddenDiagnostic() {
            NotesHiddenSample sample = new NotesHiddenSample(hiddenSamples, lastInventory);
            if (hiddenDiagnosticCount < hiddenDiagnostics.length) hiddenDiagnostics[hiddenDiagnosticCount] = sample;
            hiddenDiagnosticCount++;
            return sample;
        }
        void attachFailureDiagnostic(Throwable original) {
            String message;
            try {
                JSONArray samples = new JSONArray();
                for (NotesHiddenSample sample : hiddenDiagnostics) if (sample != null) samples.put(sample.json());
                String json = new JSONObject().put("clock", "SystemClock.elapsedRealtime")
                        .put("confirmationStartedElapsedMillis", started).put("deadlineElapsedMillis", wait.deadline)
                        .put("frozenElapsedMillis", SystemClock.elapsedRealtime()).put("stage", stage.name())
                        .put("hiddenSamplesClaimed", hiddenSamples).put("hiddenDiagnosticsObserved", hiddenDiagnosticCount)
                        .put("hiddenDiagnosticsNotRetained", Math.max(0, hiddenDiagnosticCount - hiddenDiagnostics.length))
                        .put("inventoryEntryColumns", new JSONArray().put("windowId").put("displayId").put("layer").put("filter"))
                        .put("backDecisionInventory", backDecisionInventory == null ? JSONObject.NULL : backDecisionInventory.json())
                        .put("lastInventory", lastInventory == null ? JSONObject.NULL : lastInventory.json())
                        .put("hiddenSamples", samples).put("diagnosticTruncated", false).toString();
                message = DIAGNOSTIC_MARKER + json;
                if (message.length() > MAX_DIAGNOSTIC_CHARS) {
                    message = DIAGNOSTIC_MARKER + "{\"diagnosticTruncated\":true,\"untruncatedChars\":"
                            + message.length() + "}";
                }
            } catch (Exception | AssertionError unavailable) {
                message = DIAGNOSTIC_MARKER + "{\"diagnosticSerializationFailed\":true}";
            }
            AssertionError diagnostic = new AssertionError(message);
            diagnostic.setStackTrace(new StackTraceElement[0]);
            original.addSuppressed(diagnostic);
        }
        void requireDialog(int id) {
            require(id >= 0, "Creation dialog window identity is unavailable");
            if (originalDialogWindowId == null) originalDialogWindowId = id;
            require(originalDialogWindowId == id, "Original creation dialog window changed");
        }
        void requireIme(ServiceInfo ime) {
            require(ime != null && ime.applicationInfo != null, "Configured system input method is unverified");
            String component = new ComponentName(ime.packageName, ime.name).flattenToString();
            if (imeUid == null) { imeUid = ime.applicationInfo.uid; imeComponent = component; }
            require(imeUid == ime.applicationInfo.uid && imeComponent.equals(component), "Configured input method identity changed");
        }
        void title(AccessibilityNodeInfo title, boolean beforeDecision, boolean needsInputFocus) {
            CharSequence actual = title.getText();
            recordTitle(actual != null && expectedTitle.contentEquals(actual), title.isEditable(),
                    title.isFocused(), beforeDecision, needsInputFocus);
        }
        void recordTitle(boolean matched, boolean editable, boolean focused, boolean beforeDecision, boolean needsInputFocus) {
            if (beforeDecision) {
                preBackTitleMatched = matched; preBackTitleEditable = editable; preBackTitleFocused = focused;
            } else {
                postTitleMatched = matched; postTitleEditable = editable; postTitleFocused = focused;
            }
            require(matched && editable, "Original creation title anchor changed or is not editable");
            // Input focus is needed to authorize BACK, not to click a safe button
            // after independently proving that the keyboard is hidden.
            require(!needsInputFocus || focused, "Creation title does not hold the input focus required for BACK");
        }
        void enterHiddenPreparation() {
            stage = ConfirmationStage.HIDDEN_PREPARATION;
            wait.pending("confirmation_target_changed");
            clearHiddenReadiness();
        }
        void claimBack() {
            require(backAttempts == 0 && backPreconditionVerified && wait.surfaceReady
                    && Boolean.TRUE.equals(preBackTitleMatched) && Boolean.TRUE.equals(preBackTitleEditable)
                    && Boolean.TRUE.equals(preBackTitleFocused), "BACK has no remaining verified creation-dialog allowance");
            backAttempts = 1;
        }
        boolean takeHiddenSample(long now) {
            if (imeHiddenVerified || now < nextHiddenSample || hiddenSamples >= 4) return false;
            hiddenSamples++; nextHiddenSample = now + 150; return true;
        }
        void observeHidden(String identity, long now) {
            if (identity == null) { clearHiddenReadiness(); return; }
            if (!identity.equals(hiddenIdentity)) { hiddenIdentity = identity; hiddenSince = now; imeHiddenVerified = false; return; }
            imeHiddenVerified = now - hiddenSince >= 150;
        }
        void clearHiddenReadiness() { hiddenIdentity = null; hiddenSince = 0; imeHiddenVerified = false; }
        JSONObject json() throws Exception {
            JSONObject result = new JSONObject().put("policy", "once-verified-create-dialog").put("stage", stage.name())
                    .put("backAttempts", backAttempts).put("backDownAttempted", backDownAttempted)
                    .put("backDownAccepted", value(backDownAccepted)).put("backUpAttempted", backUpAttempted)
                    .put("backUpAccepted", value(backUpAccepted)).put("backPreconditionVerified", backPreconditionVerified)
                    .put("visibleSamples", wait.samples).put("hiddenSamples", hiddenSamples).put("elapsedMillis", elapsedMillis)
                    .put("originalDialogWindowId", value(originalDialogWindowId)).put("finalDialogWindowId", value(finalDialogWindowId))
                    .put("preBackTitleMatched", value(preBackTitleMatched)).put("preBackTitleFocused", value(preBackTitleFocused))
                    .put("preBackTitleEditable", value(preBackTitleEditable)).put("postTitleMatched", value(postTitleMatched))
                    .put("postTitleFocused", value(postTitleFocused)).put("postTitleEditable", value(postTitleEditable))
                    .put("imeHiddenVerified", imeHiddenVerified).put("confirmTargetReached", confirmTargetReached)
                    .put("confirmAttempts", confirmAttempts).put("confirmAccepted", value(confirmAccepted));
            require(result.toString().length() <= MAX_JSON_CHARS, "Notes confirmation record exceeded its fixed bound");
            return result;
        }
        private static Object value(Object value) { return value == null ? JSONObject.NULL : value; }
    }

    private enum NotesResource {
        NOT_SELECTED, NEW_NOTE, TITLE_FIELD, TEXT_TYPE, CONFIRM_BUTTON, BODY_FIELD, EXACT_TITLE, OTHER_RESOURCE;
        static NotesResource forTarget(String resource) {
            if (resource == null) return EXACT_TITLE;
            switch (resource) {
                case "new_note": return NEW_NOTE;
                case "locked_note_title": return TITLE_FIELD;
                case "type_text_note": return TEXT_TYPE;
                case "android:id/button1": return CONFIRM_BUTTON;
                case "text_note_view": return BODY_FIELD;
                default: return OTHER_RESOURCE;
            }
        }
    }
    private enum NotesReadPhase { NOT_STARTED, WINDOW_PENDING, WINDOW_READY, RESOURCE_LOOKUP, TARGET_CHECK }

    /** Primitive metadata from one existing read; never retains a platform object or text. */
    private static final class NotesRead {
        final NotesStage stage;
        final NotesResource resource;
        NotesReadPhase readPhase;
        Integer appWindowId, displayId, imeWindowId, matchedCount, visibleMatchCount, targetWindowId;
        Rect appBounds, displayBounds, imeBounds, targetBounds;
        Boolean targetPresent, targetVisible, targetEnabled;
        boolean matchScanComplete;
        NotesRead(NotesStage stage, NotesResource resource, NotesReadPhase readPhase) {
            this.stage = stage; this.resource = resource; this.readPhase = readPhase;
        }
        void window(int id, Rect bounds, Rect display, int inputMethodId, Rect inputMethod) {
            readPhase = NotesReadPhase.WINDOW_READY;
            appWindowId = id; appBounds = new Rect(bounds);
            // appWindow already required DEFAULT_DISPLAY before returning.
            displayId = Display.DEFAULT_DISPLAY; displayBounds = new Rect(display);
            imeWindowId = inputMethodId; imeBounds = new Rect(inputMethod);
        }
        void matches(Integer count) {
            readPhase = NotesReadPhase.RESOURCE_LOOKUP;
            matchedCount = count; visibleMatchCount = count == null ? null : 0;
            matchScanComplete = false;
        }
        boolean visible(boolean value) { targetVisible = value; return value; }
        boolean enabled(boolean value) { targetEnabled = value; return value; }
        int windowId(int value) { targetWindowId = value; return value; }
    }

    /** Frozen before cleanup; serialization performs no node, window or platform reads. */
    private static final class NotesReadSnapshot {
        static final int MAX_JSON_CHARS = 2048;
        final NotesStage stage;
        final NotesResource resource;
        final NotesReadPhase readPhase;
        final Integer appWindowId, displayId, imeWindowId, matchedCount, visibleMatchCount, targetWindowId;
        private final Rect appBounds, displayBounds, imeBounds, targetBounds;
        final Boolean targetPresent, targetVisible, targetEnabled;
        final boolean matchScanComplete;
        NotesReadSnapshot(NotesRead read) {
            stage = read.stage; resource = read.resource; readPhase = read.readPhase;
            appWindowId = read.appWindowId; displayId = read.displayId; imeWindowId = read.imeWindowId;
            matchedCount = read.matchedCount; visibleMatchCount = read.visibleMatchCount; targetWindowId = read.targetWindowId;
            appBounds = copy(read.appBounds); displayBounds = copy(read.displayBounds);
            imeBounds = copy(read.imeBounds); targetBounds = copy(read.targetBounds);
            targetPresent = read.targetPresent; targetVisible = read.targetVisible; targetEnabled = read.targetEnabled;
            matchScanComplete = read.matchScanComplete;
        }
        JSONObject json() throws Exception {
            JSONObject result = new JSONObject().put("schemaVersion", 1).put("snapshotKind", "LAST_OBSERVATION")
                    .put("operationStage", stage.name()).put("resourceClass", resource.name()).put("readPhase", readPhase.name())
                    .put("appWindowId", value(appWindowId)).put("appBounds", rect(appBounds))
                    .put("displayId", value(displayId)).put("displayBounds", rect(displayBounds))
                    .put("imeWindowId", value(imeWindowId)).put("imeBounds", rect(imeBounds))
                    .put("matchedCount", value(matchedCount)).put("visibleMatchCount", value(visibleMatchCount))
                    .put("matchScanComplete", matchScanComplete).put("targetPresent", value(targetPresent))
                    .put("targetBounds", rect(targetBounds)).put("targetVisible", value(targetVisible))
                    .put("targetEnabled", value(targetEnabled)).put("targetWindowId", value(targetWindowId));
            require(result.toString().length() <= MAX_JSON_CHARS, "Notes preparation diagnostic exceeded its fixed bound");
            return result;
        }
        private static Rect copy(Rect value) { return value == null ? null : new Rect(value); }
        private static Object value(Object value) { return value == null ? JSONObject.NULL : value; }
        private static Object rect(Rect value) {
            return value == null ? JSONObject.NULL : new JSONArray().put(value.left).put(value.top).put(value.right).put(value.bottom);
        }
    }

    static final class NotesPreparationTimeout extends AssertionError {
        private final NotesReadSnapshot snapshot;
        NotesPreparationTimeout(String message, NotesRead read) { super(message); snapshot = new NotesReadSnapshot(read); }
        JSONObject diagnostic() throws Exception { return snapshot.json(); }
    }

    /** Failure-only metadata from the original ordered reads, not an atomic platform snapshot. */
    private static final class NotesTargetGeometryDiagnostic {
        static final String MARKER = "NOTES_TARGET_GEOMETRY_DIAGNOSTIC_V1 ";
        static final int MAX_CHARS = 4096;

        static void attach(Throwable original, NotesRead read, String lastAction, String lastActionResult,
                           int actionAttempts, int homeAttempts) {
            String message;
            try {
                // Freeze before the target/window cleanup; no additional platform or clock reads.
                NotesReadSnapshot snapshot = new NotesReadSnapshot(read);
                String json = new JSONObject().put("schemaVersion", 1).put("guard", "TARGET_FULLY_INSIDE")
                        .put("snapshot", snapshot.json()).put("lastAction", action(lastAction))
                        .put("lastActionResult", result(lastActionResult))
                        .put("actionCounterScope", "CURRENT_HARNESS")
                        .put("actionAttempts", actionAttempts).put("actionAttemptCountCap", 64)
                        .put("actionAttemptCountAtLimit", actionAttempts >= 64).put("homeAttempts", homeAttempts)
                        .put("diagnosticTruncated", false).toString();
                message = bounded(json);
            } catch (Exception | AssertionError unavailable) {
                message = MARKER + "{\"diagnosticSerializationFailed\":true}";
            }
            try {
                AssertionError diagnostic = new AssertionError(message);
                diagnostic.setStackTrace(new StackTraceElement[0]);
                original.addSuppressed(diagnostic);
            } catch (Exception | AssertionError unavailable) {
                // Even attachment failure must not replace the original geometry refusal.
            }
        }

        private static String bounded(String json) {
            String message = MARKER + json;
            return message.length() <= MAX_CHARS ? message : MARKER
                    + "{\"diagnosticTruncated\":true,\"untruncatedChars\":" + message.length() + "}";
        }

        private static String action(String value) {
            if (value == null) return "OTHER_ACTION";
            switch (value) {
                case "NONE": case "CLICK_NEW_NOTE": case "CLICK_TEXT_TYPE": case "CLICK_CONFIRM":
                case "CLICK_OTHER": case "SET_TITLE": case "SET_BODY": case "KEY_HOME": case "KEY_BACK":
                    return value;
                default: return "OTHER_ACTION";
            }
        }

        private static String result(String value) {
            if (value == null) return "OTHER_RESULT";
            switch (value) {
                case "NOT_ATTEMPTED": case "SUBMISSION_UNKNOWN": case "ACCEPTED": case "REJECTED":
                    return value;
                default: return "OTHER_RESULT";
            }
        }
    }

    static void verifyNotesEditingPreparation() {
        // Boundary fixtures only: never UI/app success or screenshot evidence.
        Rect app = new Rect(0, 40, 1080, 1920), ime = new Rect(0, 1200, 1080, 1920);
        require(fullyInside(app, new Rect(10, 100, 900, 1200))
                && uncoveredByInputMethod(new Rect(10, 100, 900, 1200), ime), "Uncovered editor boundary was rejected");
        require(!fullyInside(app, new Rect(-1, 100, 900, 1100))
                && !fullyInside(app, new Rect(10, 0, 900, 1100))
                && !fullyInside(app, new Rect(10, 100, 1081, 1100))
                && !fullyInside(app, new Rect(10, 100, 900, 1921))
                && !fullyInside(app, new Rect()), "Clipped or empty target was admitted");
        require(!uncoveredByInputMethod(new Rect(10, 1100, 900, 1201), ime)
                && !uncoveredByInputMethod(new Rect(10, 1300, 900, 1500), ime), "Covered target was admitted");
        require(uncoveredByInputMethod(new Rect(10, 100, 900, 1100), new Rect()), "Keyboard-free editor was rejected");
        NotesWait wait = new NotesWait(); long deadline = wait.deadline;
        require(!wait.targetStable("app/target/ime-A", 0) && !wait.targetStable("app/target/ime-A", 599)
                && wait.targetStable("app/target/ime-A", 600), "Editing skipped target stability");
        require(wait.takeSurfaceSample(600) && !wait.takeSurfaceSample(749), "Surface reads were not bounded in time");
        wait.observeSurface("surface-A", 600);
        require(!wait.surfaceReady, "One surface read admitted editing");
        wait.observeSurface("surface-A", 750);
        require(wait.surfaceReady, "Two stable surface reads were rejected");
        require(!wait.targetStable("app/target/ime-B", 800) && !wait.surfaceReady, "Changed geometry reused IME readiness");
        wait.observeSurface("surface-A", 900); wait.observeSurface(null, 1050);
        require(!wait.surfaceReady && wait.surface == null, "Hidden/transition state retained IME readiness");
        require(wait.takeSurfaceSample(900) && wait.takeSurfaceSample(1050) && wait.takeSurfaceSample(1200)
                && !wait.takeSurfaceSample(1400), "Surface sampling exceeded its original preparation bound");
        wait.pending("not_published");
        require(!wait.takeSurfaceSample(1600) && wait.deadline == deadline, "Readiness reset renewed sampling or timeout");
        verifyNotesPreparationDiagnostics();
        verifyNotesTargetGeometryDiagnostics();
        verifyNotesConfirmationPreparation();
    }

    private static void verifyNotesConfirmationPreparation() {
        // Pure state boundaries, never synthetic platform acceptance or UI evidence.
        try {
            Rect dialog = new Rect(8, 24, 312, 300), separateIme = new Rect(0, 394, 320, 640);
            require(!Rect.intersects(dialog, separateIme)
                    && inspectConfirmationWindow(AccessibilityWindowInfo.TYPE_INPUT_METHOD, dialog, separateIme)
                    && !inspectConfirmationWindow(AccessibilityWindowInfo.TYPE_SYSTEM, dialog, separateIme)
                    && !inspectConfirmationWindow(AccessibilityWindowInfo.TYPE_APPLICATION, dialog, separateIme)
                    && inspectConfirmationWindow(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                            dialog, new Rect(10, 100, 100, 200)),
                    "Confirmation lost a separate IME or bypassed an intersecting unknown window");
            NotesConfirmation confirmation = new NotesConfirmation("private-title-marker");
            long deadline = confirmation.wait.deadline;
            JSONObject initial = confirmation.json();
            require(initial.isNull("backDownAccepted") && initial.isNull("backUpAccepted")
                    && initial.isNull("confirmAccepted") && initial.getInt("backAttempts") == 0,
                    "Unattempted confirmation became a rejected or accepted action");
            confirmation.requireDialog(12); confirmation.requireDialog(12);
            requireConfirmationRefusal(() -> confirmation.requireDialog(13), "A replacement dialog was admitted");
            confirmation.recordTitle(true, true, false, true, false);
            confirmation.wait.surfaceReady = true;
            confirmation.backPreconditionVerified = true;
            requireConfirmationRefusal(confirmation::claimBack, "A title without input focus authorized BACK");
            requireConfirmationRefusal(() -> confirmation.recordTitle(true, true, false, true, true),
                    "The pre-BACK focus guard was bypassed");
            confirmation.recordTitle(true, true, true, true, true);
            confirmation.claimBack();
            requireConfirmationRefusal(confirmation::claimBack, "BACK allowance was renewed after its claim");
            confirmation.backDownAttempted = true;
            confirmation.backUpAttempted = true; confirmation.backUpAccepted = true;
            JSONObject partial = confirmation.json();
            require(partial.getInt("backAttempts") == 1 && partial.isNull("backDownAccepted")
                    && partial.getBoolean("backUpAccepted") && !partial.getBoolean("imeHiddenVerified"),
                    "An unknown DOWN or successful paired UP was treated as hidden");
            confirmation.backDownAccepted = false;
            require(!confirmation.json().getBoolean("backDownAccepted"), "A rejected DOWN was lost");
            confirmation.recordTitle(true, true, false, false, false);
            require(!confirmation.postTitleFocused, "Post-hidden confirmation wrongly requires input focus");
            requireConfirmationRefusal(() -> confirmation.recordTitle(false, true, false, false, false),
                    "A changed title was admitted after BACK");

            NotesConfirmation phase = new NotesConfirmation("private-title-marker");
            require(!phase.wait.targetStable("same-rectangle", 0) && phase.wait.targetStable("same-rectangle", 600),
                    "Title geometry did not require its original stability interval");
            require(phase.takeHiddenSample(600) && !phase.takeHiddenSample(749), "Hidden sampling lacked spacing");
            phase.observeHidden("surface-A", 600);
            require(!phase.imeHiddenVerified, "One hidden sample admitted confirmation");
            require(phase.takeHiddenSample(750), "Second hidden sample was unavailable");
            phase.observeHidden("surface-A", 750);
            require(phase.imeHiddenVerified, "Two same-identity hidden samples were rejected");
            phase.enterHiddenPreparation();
            require(!phase.imeHiddenVerified && phase.hiddenSamples == 2
                    && !phase.wait.targetStable("same-rectangle", 800)
                    && !phase.wait.targetStable("same-rectangle", 1399)
                    && phase.wait.targetStable("same-rectangle", 1400),
                    "Title-to-button transition reused readiness when rectangles were equal");
            phase.observeHidden("surface-A", 1500); phase.observeHidden("surface-B", 1650);
            require(!phase.imeHiddenVerified, "Hidden readiness mixed different WMS identities");
            phase.observeHidden(null, 1800);
            require(!phase.imeHiddenVerified && phase.hiddenIdentity == null,
                    "An incomplete hidden sample preserved readiness");

            NotesConfirmation budget = new NotesConfirmation(null);
            long budgetDeadline = budget.wait.deadline;
            for (int i = 0; i < 4; i++) {
                require(budget.wait.takeSurfaceSample(i * 150) && budget.takeHiddenSample(i * 150),
                        "Independent visible and hidden budgets were not available");
                budget.wait.pending("boundary_reset"); budget.clearHiddenReadiness();
            }
            budget.enterHiddenPreparation();
            require(!budget.wait.takeSurfaceSample(600) && !budget.takeHiddenSample(600)
                    && budget.wait.samples == 4 && budget.hiddenSamples == 4 && budget.wait.deadline == budgetDeadline,
                    "A readiness or phase reset renewed the four-plus-four budget or deadline");
            partial.put("backAttempts", 99);
            require(confirmation.json().getInt("backAttempts") == 1 && confirmation.wait.deadline == deadline
                    && confirmation.originalDialogWindowId == 12, "Receipt mutation changed preparation state");
            confirmation.backAttempts = Integer.MAX_VALUE; confirmation.confirmAttempts = Integer.MAX_VALUE;
            confirmation.wait.samples = Integer.MAX_VALUE; confirmation.hiddenSamples = Integer.MAX_VALUE;
            confirmation.elapsedMillis = Long.MAX_VALUE;
            confirmation.originalDialogWindowId = Integer.MIN_VALUE; confirmation.finalDialogWindowId = Integer.MAX_VALUE;
            String bounded = confirmation.json().toString();
            require(bounded.length() <= NotesConfirmation.MAX_JSON_CHARS && !bounded.contains("private-title-marker")
                    && !bounded.contains("surface-A"), "Confirmation output leaked content or exceeded its bound");
            verifyConfirmationFailureDiagnostics();
        } catch (Exception failure) { throw new AssertionError("Notes confirmation preparation boundary check failed", failure); }
    }

    private static void verifyConfirmationFailureDiagnostics() throws Exception {
        NotesConfirmation confirmation = new NotesConfirmation("private-title-marker");
        long deadline = confirmation.wait.deadline;
        NotesImeInventory decision = confirmation.beginInventory();
        decision.focusedWindowId = 12; decision.focusedLayer = 5;
        decision.ime(12, 0, 5); decision.ime(13, 1, 6); decision.ime(14, 0, 4); decision.ime(15, 0, 5);
        decision.scanComplete = true;
        confirmation.backDecisionInventory = decision;
        JSONArray entries = decision.json().getJSONArray("imeEntries");
        for (int index = 0; index < 4; index++) require(entries.getJSONArray(index).getString(3).equals(
                new String[]{"SAME_WINDOW", "OTHER_DISPLAY", "BELOW_FOCUSED", "CANDIDATE_REQUIRES_IDENTITY"}[index]),
                "IME diagnostic filtering does not match the original inventory guards");
        for (int index = 0; index < 4; index++) {
            require(confirmation.takeHiddenSample(index * 150), "Diagnostic fixture lacks original hidden allowance");
            NotesImeInventory inventory = confirmation.beginInventory();
            inventory.focusedWindowId = Integer.MAX_VALUE; inventory.focusedLayer = Integer.MAX_VALUE;
            for (int item = 0; item < 64; item++) inventory.ime(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE);
            inventory.scanComplete = true;
            NotesHiddenSample sample = confirmation.beginHiddenDiagnostic();
            JSONObject fields = new JSONObject();
            for (String field : new String[]{"isOnScreen", "isVisible", "isReadyForDisplay()", "mLastHidden",
                    "mEnterAnimationPending", "mAnimatingExit", "mHasSurface", "Surface: shown"})
                fields.put(field, new JSONObject().put("count", Integer.MAX_VALUE).put("value", "CONFLICT"));
            sample.wms.put("parseStage", "visibility").put("outcome", "REFUSED")
                    .put("refusal", "input_method_is_visible_animating_or_unverified")
                    .put("fields", fields).put("selectedToken", "ffffffffffffffffffffffffffffffff")
                    .put("ownerUid", Integer.MAX_VALUE).put("ownerMatches", false)
                    .put("displayMatches", false).put("packageMatches", false)
                    .put("inputMethodRecordCount", Integer.MAX_VALUE).put("recordIndex", Integer.MAX_VALUE)
                    .put("recordEnd", "next_window").put("dumpStartedElapsedMillis", Long.MAX_VALUE)
                    .put("dumpEndedElapsedMillis", Long.MAX_VALUE);
            sample.endedElapsedMillis = Long.MAX_VALUE;
        }
        require(!confirmation.takeHiddenSample(600) && confirmation.wait.deadline == deadline,
                "Diagnostic collection renewed sampling or time limits");
        String originalResult = confirmation.json().toString();
        AssertionError failure = new AssertionError("original-failure");
        confirmation.attachFailureDiagnostic(failure);
        String frozen = failure.getSuppressed()[0].getMessage();
        require("original-failure".equals(failure.getMessage()) && originalResult.equals(confirmation.json().toString())
                && !confirmation.imeHiddenVerified && confirmation.backAttempts == 0 && confirmation.confirmAttempts == 0,
                "Diagnostics changed the original failure, result or action authority");
        require(frozen.length() <= NotesConfirmation.MAX_DIAGNOSTIC_CHARS && !frozen.contains("private-title-marker"),
                "Diagnostic message leaked title text or exceeded its limit");
        JSONObject saved = new JSONObject(frozen.substring(NotesConfirmation.DIAGNOSTIC_MARKER.length()));
        require(!saved.getBoolean("diagnosticTruncated") && saved.getJSONArray("hiddenSamples").length() == 4
                && saved.getJSONObject("lastInventory").getInt("notRetained") == 56,
                "Diagnostic prefix loss is not explicit");
        java.io.StringWriter trace = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        require(trace.toString().contains("Suppressed: java.lang.AssertionError: " + NotesConfirmation.DIAGNOSTIC_MARKER),
                "Failure stack trace omitted the bounded diagnostic");
        decision.imeCount = 0;
        confirmation.hiddenDiagnostics[0].wms.put("outcome", "MUTATED_AFTER_FAILURE");
        require(frozen.equals(failure.getSuppressed()[0].getMessage()), "Later mutation changed frozen failure evidence");
        // Exercise only the diagnostic overflow fallback; this does not add a real sample.
        confirmation.hiddenDiagnostics[0].wms.put("syntheticOverflow", new String(new char[NotesConfirmation.MAX_DIAGNOSTIC_CHARS]).replace('\0', 'x'));
        AssertionError oversized = new AssertionError("original-failure");
        confirmation.attachFailureDiagnostic(oversized);
        String fallback = oversized.getSuppressed()[0].getMessage();
        require(fallback.length() <= NotesConfirmation.MAX_DIAGNOSTIC_CHARS
                && new JSONObject(fallback.substring(NotesConfirmation.DIAGNOSTIC_MARKER.length())).getBoolean("diagnosticTruncated"),
                "Diagnostic overflow was not bounded and explicitly marked");
    }

    private static void requireConfirmationRefusal(Runnable action, String message) {
        boolean refused = false;
        try { action.run(); } catch (AssertionError expected) { refused = true; }
        require(refused, message);
    }

    private static void verifyNotesPreparationDiagnostics() {
        // Same-source value fixtures, not device observations or app acceptance.
        try {
            NotesWait wait = new NotesWait(); long deadline = wait.deadline;
            wait.beginObservation(NotesStage.CONFIRM_CREATE, NotesResource.forTarget("android:id/button1"));
            Rect app = new Rect(0, 24, 320, 616), display = new Rect(0, 0, 320, 640);
            Rect ime = new Rect(0, 390, 320, 640), target = new Rect(220, 400, 300, 448);
            wait.observation.window(12, app, display, 14, ime);
            wait.observation.matches(1); wait.observation.visibleMatchCount = 1; wait.observation.matchScanComplete = true;
            wait.observation.readPhase = NotesReadPhase.TARGET_CHECK; wait.observation.targetPresent = true;
            wait.observation.visible(true); wait.observation.enabled(true); wait.observation.windowId(12);
            wait.observation.targetBounds = new Rect(target);
            wait.pending("notes_target_covered_by_verified_input_method");
            NotesPreparationTimeout failure = (NotesPreparationTimeout) wait.timeout();
            String before = failure.diagnostic().toString();
            require(before.contains("CONFIRM_BUTTON") && failure.diagnostic().getInt("matchedCount") == 1
                    && failure.diagnostic().getJSONArray("targetBounds").getInt(1) == 400,
                    "The last observation lost its same-read values");
            app.setEmpty(); display.setEmpty(); ime.setEmpty(); target.setEmpty();
            wait.observation.appBounds.setEmpty(); wait.observation.targetBounds.setEmpty();
            wait.observation.visibleMatchCount = 0;
            failure.diagnostic().put("appWindowId", 999);
            require(before.equals(failure.diagnostic().toString()), "Cleanup or JSON mutation changed the frozen diagnostic");
            require(failure.getMessage().equals("Notes v4 observation timed out: reason=notes_target_covered_by_verified_input_method"
                            + " automaticBackPolicy=single-back-only-before-notes-create-confirm imeSamples=0 lastImeSurface=NOT_SAMPLED"),
                    "Geometry diagnostics changed the original timeout message");
            wait.beginObservation(NotesStage.CONFIRM_CREATE, NotesResource.forTarget("private-resource-marker"));
            JSONObject unread = ((NotesPreparationTimeout) wait.timeout()).diagnostic();
            require("OTHER_RESOURCE".equals(unread.getString("resourceClass")) && !unread.toString().contains("private-resource-marker")
                    && unread.isNull("appWindowId") && unread.isNull("targetBounds") && unread.isNull("matchedCount")
                    && unread.isNull("targetVisible") && !unread.getBoolean("matchScanComplete"),
                    "An incomplete last read leaked a resource or reused earlier geometry");
            wait.observation.matches(2); wait.observation.matchScanComplete = true;
            JSONObject invisible = ((NotesPreparationTimeout) wait.timeout()).diagnostic();
            require(invisible.getInt("matchedCount") == 2 && invisible.getInt("visibleMatchCount") == 0,
                    "Invisible matches were confused with a missing query");
            wait.observation.matches(0); wait.observation.matchScanComplete = true;
            require(((NotesPreparationTimeout) wait.timeout()).diagnostic().getInt("matchedCount") == 0,
                    "Zero exact matches were not preserved");
            Rect extremes = new Rect(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
            wait.observation.window(Integer.MIN_VALUE, extremes, extremes, Integer.MAX_VALUE, extremes);
            wait.observation.targetBounds = new Rect(extremes); wait.observation.targetWindowId = Integer.MIN_VALUE;
            wait.observation.matchedCount = Integer.MAX_VALUE; wait.observation.visibleMatchCount = Integer.MAX_VALUE;
            require(((NotesPreparationTimeout) wait.timeout()).diagnostic().toString().length() <= NotesReadSnapshot.MAX_JSON_CHARS
                    && wait.deadline == deadline && wait.samples == 0 && !wait.surfaceReady,
                    "Diagnostic bounds or collection altered the original wait budget");
        } catch (Exception failure) { throw new AssertionError("Notes preparation diagnostic boundary check failed", failure); }
    }

    private static void verifyNotesTargetGeometryDiagnostics() {
        // Value fixtures only: no UI action, extra observation or claim of application success.
        try {
            NotesWait wait = new NotesWait(); long deadline = wait.deadline;
            wait.beginObservation(NotesStage.SET_TITLE, NotesResource.forTarget("locked_note_title"));
            NotesRead read = wait.observation;
            read.window(3, new Rect(0, 24, 320, 616), new Rect(0, 0, 320, 640), -1, new Rect());
            read.matches(1); read.visibleMatchCount = 1; read.matchScanComplete = true;
            read.readPhase = NotesReadPhase.TARGET_CHECK; read.targetPresent = true;
            read.visible(true); read.enabled(true); read.windowId(3);
            read.targetBounds = new Rect(-1, 25, 200, 100);
            String snapshotBefore = new NotesReadSnapshot(read).json().toString();
            require(!fullyInside(read.appBounds, read.targetBounds), "Geometry fixture was not outside its window");
            AssertionError original = new AssertionError("original-private-error-marker");
            Exception cause = new Exception("original-private-cause-marker"); original.initCause(cause);
            Throwable prior = new AssertionError("original-prior-suppressed-marker"); original.addSuppressed(prior);
            StackTraceElement[] stackBefore = original.getStackTrace();
            NotesTargetGeometryDiagnostic.attach(original, read, "CLICK_NEW_NOTE", "ACCEPTED", 1, 0);
            require(original.getClass() == AssertionError.class && original.getMessage().equals("original-private-error-marker")
                    && original.getCause() == cause && java.util.Arrays.equals(stackBefore, original.getStackTrace())
                    && original.getSuppressed().length == 2 && original.getSuppressed()[0] == prior,
                    "Geometry diagnostic replaced the original failure or prior suppressed data");
            String frozen = original.getSuppressed()[1].getMessage();
            JSONObject diagnostic = new JSONObject(frozen.substring(NotesTargetGeometryDiagnostic.MARKER.length()));
            require(frozen.startsWith(NotesTargetGeometryDiagnostic.MARKER)
                    && "TARGET_FULLY_INSIDE".equals(diagnostic.getString("guard"))
                    && snapshotBefore.equals(diagnostic.getJSONObject("snapshot").toString())
                    && "CLICK_NEW_NOTE".equals(diagnostic.getString("lastAction"))
                    && "ACCEPTED".equals(diagnostic.getString("lastActionResult"))
                    && diagnostic.getInt("actionAttempts") == 1 && !diagnostic.getBoolean("actionAttemptCountAtLimit")
                    && diagnostic.getInt("homeAttempts") == 0 && !diagnostic.getBoolean("diagnosticTruncated")
                    && !frozen.contains("private-error-marker") && !frozen.contains("private-cause-marker"),
                    "Geometry diagnostic lost the original read or leaked unrelated failure text");
            read.appBounds.setEmpty(); read.targetBounds.setEmpty(); read.imeBounds.set(1, 2, 3, 4);
            read.targetWindowId = 99; read.visibleMatchCount = 0;
            require(frozen.equals(original.getSuppressed()[1].getMessage()) && wait.deadline == deadline
                    && wait.samples == 0 && !wait.surfaceReady,
                    "Cleanup mutated frozen geometry or diagnostic collection changed the wait budget");

            Rect extremes = new Rect(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
            read.window(Integer.MIN_VALUE, extremes, extremes, Integer.MAX_VALUE, extremes);
            read.targetWindowId = Integer.MAX_VALUE; read.matchedCount = Integer.MAX_VALUE;
            read.visibleMatchCount = Integer.MAX_VALUE;
            for (Rect target : new Rect[]{new Rect(), new Rect(20, 30, 10, 5), extremes}) {
                read.targetBounds = new Rect(target);
                AssertionError boundary = new AssertionError("boundary");
                NotesTargetGeometryDiagnostic.attach(boundary, read, "private-action-marker", "private-result-marker", 64, 1);
                String message = boundary.getSuppressed()[0].getMessage();
                JSONObject saved = new JSONObject(message.substring(NotesTargetGeometryDiagnostic.MARKER.length()));
                JSONArray rect = saved.getJSONObject("snapshot").getJSONArray("targetBounds");
                require(rect.getInt(0) == target.left && rect.getInt(1) == target.top
                        && rect.getInt(2) == target.right && rect.getInt(3) == target.bottom
                        && message.length() <= NotesTargetGeometryDiagnostic.MAX_CHARS
                        && "OTHER_ACTION".equals(saved.getString("lastAction"))
                        && "OTHER_RESULT".equals(saved.getString("lastActionResult"))
                        && saved.getInt("actionAttempts") == 64 && saved.getInt("actionAttemptCountCap") == 64
                        && saved.getBoolean("actionAttemptCountAtLimit") && !message.contains("private-"),
                        "Geometry diagnostic normalized a rectangle, exceeded its bound or exposed unknown action text");
            }
            AssertionError unavailable = new AssertionError("original serialization failure");
            NotesTargetGeometryDiagnostic.attach(unavailable, null, null, null, 0, 0);
            require(unavailable.getMessage().equals("original serialization failure") && unavailable.getSuppressed().length == 1
                    && unavailable.getSuppressed()[0].getMessage().equals(NotesTargetGeometryDiagnostic.MARKER
                    + "{\"diagnosticSerializationFailed\":true}"), "Serialization failure replaced the original refusal");
            String overflow = NotesTargetGeometryDiagnostic.bounded(new String(new char[NotesTargetGeometryDiagnostic.MAX_CHARS])
                    .replace('\0', 'x'));
            JSONObject truncated = new JSONObject(overflow.substring(NotesTargetGeometryDiagnostic.MARKER.length()));
            require(overflow.length() <= NotesTargetGeometryDiagnostic.MAX_CHARS && truncated.getBoolean("diagnosticTruncated")
                    && truncated.getInt("untruncatedChars") > NotesTargetGeometryDiagnostic.MAX_CHARS,
                    "Geometry overflow was not bounded and explicit");
        } catch (Exception failure) { throw new AssertionError("Notes target geometry diagnostic boundary check failed", failure); }
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
