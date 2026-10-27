package dev.agentos.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.Activity;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Installs no production bypass: enables the ordinary service only on the test device. */
final class DeviceHarness implements AutoCloseable {
    static final String FIXTURE = "dev.agentos.fixture";
    private static final String COMPONENT = "dev.agentos.app/dev.agentos.app.AgentAccessibilityService";
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Context context = instrumentation.getTargetContext();
    final UiAutomation automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    final RunControl control = new RunControl(SettingsStore.currentProfileId(context));
    final String originalServices;
    final String originalEnabled;
    AgentAccessibilityService service;
    AndroidDeviceTools tools;

    DeviceHarness() throws Exception {
        originalServices = readSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        originalEnabled = readSecure(Settings.Secure.ACCESSIBILITY_ENABLED);
        try {
            shell("input keyevent 224");
            shell("wm dismiss-keyguard");
            String enabled = originalServices == null ? "" : originalServices;
            if (!enabled.contains(COMPONENT)) enabled = enabled.isEmpty() ? COMPONENT : enabled + ":" + COMPONENT;
            writeSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            writeSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            try {
                await("Accessibility service did not connect", () -> {
                    service = AgentAccessibilityService.getInstance();
                    return service != null && service.isConnected();
                });
            } catch (AssertionError disconnected) {
                throw new AssertionError(disconnected.getMessage() + "\n" + connectionDiagnostics(), disconnected);
            }
            instrumentation.runOnMainSync(() -> {
                service.setOverlayCallbacks(new AgentAccessibilityService.OverlayCallbacks() {
                    @Override public void onPauseResume() {
                        if (control.isPaused()) control.resume(); else control.pause();
                    }
                    @Override public void onStop() { control.cancel(); }
                    @Override public void onApprove(boolean approved) { }
                });
                service.showOverlay("Integration test", false, "");
            });
            instrumentation.waitForIdleSync();
            tools = AndroidDeviceTools.forTesting(service, Collections.singleton(FIXTURE),
                    Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(context, FIXTURE)), false, () -> true,
                    (description, active, requestEpoch) -> true);
            launch("FixtureActivity");
        } catch (Throwable error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    void launch(String activity) throws Exception {
        Intent intent = new Intent().setComponent(new ComponentName(FIXTURE, FIXTURE + "." + activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        instrumentation.waitForIdleSync();
        awaitObservation(tools, control, data -> FIXTURE.equals(string(data, "packageName")));
        awaitUiIdle();
        // A package can become focused before its entry animation or layout ends.
        // Read the baseline again after actual cross-process event quiescence.
        awaitObservation(tools, control, data -> FIXTURE.equals(string(data, "packageName")));
    }

    void awaitUiIdle() throws Exception {
        // Instrumentation's main-thread idle only covers our own process. Fixture
        // activities and system window animations report through UiAutomation.
        automation.waitForIdle(500, 8_000);
    }

    /** Dismiss only this app's own editor, then prove that the actual launch origin is unobstructed. */
    JsonObject hideOwnKeyboardAndObserve(Activity activity, AndroidDeviceTools executor, RunControl active) throws Exception {
        assertEquals("Only the test-owned application Activity may dismiss its keyboard",
                context.getPackageName(), activity.getPackageName());
        assertTrue("The task-keyboard flow belongs to the actual MainActivity", activity instanceof MainActivity);
        // MainActivity binds encrypted settings asynchronously. Waiting only for
        // its package/focus could race that later bind and its layout/IME changes.
        await("The owned configuration screen did not finish loading", () -> {
            boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                View save = activity.findViewById(R.id.save_settings);
                View confirm = activity.findViewById(R.id.execution_mode_confirm_all);
                View navigation = activity.findViewById(R.id.execution_mode_continuous_navigation);
                ready[0] = save != null && save.isEnabled() && confirm != null && confirm.isEnabled()
                        && navigation != null && navigation.isEnabled();
            });
            return ready[0];
        });
        try {
            instrumentation.runOnMainSync(() -> ((MainActivity) activity).dismissOwnTaskKeyboard());
            await("The owned Activity's keyboard did not finish hiding", () -> {
                boolean[] hidden = {false};
                instrumentation.runOnMainSync(() -> {
                    WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                    hidden[0] = ownRootHasFocus(activity) && insets != null && !insets.isVisible(WindowInsets.Type.ime());
                });
                return hidden[0];
            });
            awaitUiIdle();
            JsonObject own = awaitObservation(executor, active,
                    data -> context.getPackageName().equals(string(data, "packageName"))
                            && data.get("focused").getAsBoolean() && !data.get("hasOcclusion").getAsBoolean());
            assertEquals(focusedWindowId(service), own.get("windowId").getAsInt());
            instrumentation.runOnMainSync(() -> {
                assertTrue("An editor regained focus after dismissal", ownRootHasFocus(activity));
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                assertNotNull(insets);
                assertFalse("The owned IME reopened before launch", insets.isVisible(WindowInsets.Type.ime()));
            });
            return own;
        } catch (Exception | AssertionError failure) {
            throw new AssertionError("The owned Activity was not ready for launch: " + ownActivityDiagnostics(activity)
                    + "\n" + windowDiagnostics(), failure);
        }
    }

    private static boolean ownRootHasFocus(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        View focus = activity.getCurrentFocus();
        return content != null && content.getChildCount() == 1 && focus == content.getChildAt(0)
                && focus.isFocused() && !focus.onCheckIsTextEditor();
    }

    /** No text values: capture current focus/IME/control geometry before a failed test cleans up. */
    String ownActivityDiagnostics(Activity activity) {
        String[] state = {"unavailable"};
        instrumentation.runOnMainSync(() -> {
            View focus = activity.getCurrentFocus();
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            state[0] = "own{destroyed=" + activity.isDestroyed() + ",windowFocus=" + activity.hasWindowFocus()
                    + ",rootFocus=" + ownRootHasFocus(activity) + ",imeVisible="
                    + (insets == null ? "missing" : insets.isVisible(WindowInsets.Type.ime()))
                    + ",imeInsets=" + (insets == null ? "missing" : insets.getInsets(WindowInsets.Type.ime()))
                    + ",focusClass=" + (focus == null ? "none" : focus.getClass().getSimpleName())
                    + ",focusId=" + (focus == null ? -1 : focus.getId())
                    + ",isTextEditor=" + (focus != null && focus.onCheckIsTextEditor())
                    + ",controlAvailable=" + service.isControlAvailable() + ",controlBounds=" + service.controlBounds().toShortString() + "}";
        });
        return state[0];
    }

    JsonObject observe() throws Exception {
        return awaitObservation(tools, control, data -> FIXTURE.equals(string(data, "packageName")));
    }

    JsonObject withText(String text) throws Exception {
        try {
            return awaitObservation(tools, control, data -> {
                if (!data.has("nodes")) return false;
                for (JsonElement entry : data.getAsJsonArray("nodes")) {
                    if (text.equals(string(entry.getAsJsonObject(), "text"))) return true;
                }
                return false;
            });
        } catch (AssertionError missingText) {
            throw new AssertionError("Expected fixture text: " + text + "\n" + missingText.getMessage(), missingText);
        }
    }

    static JsonObject awaitObservation(AndroidDeviceTools tools, RunControl control,
                                       Predicate<JsonObject> predicate) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        String previousRevision = "";
        JsonObject last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            last = tools.execute("observe", new JsonObject(), control);
            if (last.has("ok") && last.get("ok").getAsBoolean() && last.has("data")) {
                JsonObject data = last.getAsJsonObject("data");
                String revision = string(data, "revision");
                if (!revision.isEmpty() && revision.equals(previousRevision) && predicate.test(data)) return data;
                previousRevision = revision;
            }
            SystemClock.sleep(100);
        }
        fail("No stable matching fixture observation\n" + serverWindowDiagnostics()
                + "\n" + windowDiagnostics() + "\nlast result: " + last);
        return null;
    }

    static JsonObject node(JsonObject observation, String id) {
        for (JsonElement entry : observation.getAsJsonArray("nodes")) {
            JsonObject node = entry.getAsJsonObject();
            if ((FIXTURE + ":id/" + id).equals(string(node, "viewId"))) return node;
        }
        fail("Missing visible fixture node: " + id);
        return null;
    }

    static JsonObject nodeArguments(JsonObject observation, String id) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("nodeId", string(node(observation, id), "id"));
        arguments.addProperty("expectedRevision", string(observation, "revision"));
        return arguments;
    }

    static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    void disableService() throws Exception {
        writeSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        writeSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "0");
        await("Accessibility service remained connected", () -> AgentAccessibilityService.getInstance() == null);
    }

    AutoCloseable enableSoftwareKeyboard() throws Exception {
        String original = readSecureFromShell("show_ime_with_hard_keyboard");
        String selected = Build.VERSION.SDK_INT >= 36 ? readSecureFromShell("default_input_method") : null;
        String enabled = Build.VERSION.SDK_INT >= 36 ? readSecureFromShell("enabled_input_methods") : null;
        String subtype = Build.VERSION.SDK_INT >= 36 ? readSecureFromShell("selected_input_method_subtype") : null;
        String history = Build.VERSION.SDK_INT >= 36 ? readSecureFromShell("input_methods_subtype_history") : null;
        if (Build.VERSION.SDK_INT >= 36 && (selected == null || selected.length() > 256
                || !selected.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+") || enabled == null)) {
            throw new AssertionError("The dedicated emulator must already have a selected stock input method");
        }
        try {
            writeSecure("show_ime_with_hard_keyboard", "1");
            assertEquals("1", readSecureFromShell("show_ime_with_hard_keyboard"));
            if (Build.VERSION.SDK_INT >= 36) {
                // These system images retained FALSE in the stock IME observer even
                // after the provider stored 1. Rebind that same component so its new
                // service instance reads the setting; never replace or clear the IME.
                shell("ime disable " + selected);
                shell("ime enable " + selected);
                shell("ime set " + selected);
                assertEquals("The original input method was not reselected", selected, readSecureFromShell("default_input_method"));
            }
        } catch (Exception | AssertionError failure) {
            try { restoreKeyboardSettings(original, selected, enabled, subtype, history); }
            catch (Exception | AssertionError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return () -> restoreKeyboardSettings(original, selected, enabled, subtype, history);
    }

    private String readSecureFromShell(String key) throws Exception {
        String value = shell("settings get secure " + key).trim();
        return "null".equals(value) ? null : value;
    }

    private void restoreKeyboardSettings(String original, String selected, String enabled,
                                         String subtype, String history) throws Exception {
        if (Build.VERSION.SDK_INT < 36) {
            writeSecure("show_ime_with_hard_keyboard", original);
            return;
        }
        Throwable failure = null;
        for (KeyboardRestoreStep step : new KeyboardRestoreStep[]{
                () -> writeSecure("enabled_input_methods", enabled),
                () -> {
                    shell("ime set " + selected);
                    assertEquals("The original input-method selection was not restored", selected,
                            readSecureFromShell("default_input_method"));
                },
                () -> writeSecure("selected_input_method_subtype", subtype),
                () -> writeSecure("input_methods_subtype_history", history),
                () -> writeSecure("show_ime_with_hard_keyboard", original)}) {
            try { step.restore(); }
            catch (Exception | AssertionError problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw (AssertionError) failure;
    }

    @FunctionalInterface private interface KeyboardRestoreStep { void restore() throws Exception; }

    String keyboardDiagnostics() {
        StringBuilder diagnostic = new StringBuilder("Synthetic input-method diagnostics:");
        String selectedInputMethod = null;
        for (String key : new String[]{"default_input_method", "enabled_input_methods", "show_ime_with_hard_keyboard"}) {
            diagnostic.append("\n ").append(key).append('=');
            try {
                String value = readSecure(key);
                diagnostic.append(value);
                if ("default_input_method".equals(key)) selectedInputMethod = value;
            }
            catch (RuntimeException restricted) {
                // New releases restrict individual settings even for a test process.
                // Never replace the original keyboard assertion with a diagnostic error.
                diagnostic.append("[app read restricted: ").append(restricted.getClass().getSimpleName()).append(']');
                try {
                    String value = shell("settings get secure " + key).trim();
                    diagnostic.append(" shell=").append(value.substring(0, Math.min(value.length(), 1_024)));
                    if ("default_input_method".equals(key)) selectedInputMethod = value;
                } catch (Exception unavailable) {
                    diagnostic.append(" [shell unavailable: ").append(unavailable.getClass().getSimpleName()).append(']');
                }
            }
        }
        // The global dump has a long client/history prelude. Capture the selected
        // keyboard service first so its actual input-view state cannot be lost to it.
        if (selectedInputMethod != null && selectedInputMethod.length() <= 256
                && selectedInputMethod.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) {
            // UiAutomation uses Runtime.exec tokenization, not a shell: '$' stays
            // literal, and adding quotes would make them part of the component.
            appendShellDump(diagnostic, "selected input-method service",
                    "dumpsys activity service " + selectedInputMethod, 12_288);
        } else diagnostic.append("\nSelected input-method service is unavailable or has an invalid component identifier");
        appendShellDump(diagnostic, "global input-method state", "dumpsys input_method", 16_384);
        return diagnostic.toString();
    }

    /** Failure-only, synthetic-device metadata; never a new observation or an IME acceptance shortcut. */
    String keyboardWindowDiagnostics() {
        StringBuilder result = new StringBuilder("Synthetic keyboard window registration evidence (same-process shared cache; sequential reads, not simultaneous snapshots):");
        try {
            AccessibilityServiceInfo info = service.getServiceInfo();
            result.append("\nservice flags=").append(info == null ? "unavailable" : "0x" + Integer.toHexString(info.flags));
            if (info != null) service.setServiceInfo(info);
            result.append(info == null ? " refresh=unavailable" : " refresh=same-info-requested");
            appendConnectionWindows(result, service.getWindows());
        } catch (RuntimeException unavailable) {
            result.append("\nservice unavailable: ").append(unavailable.getClass().getSimpleName());
        }
        try {
            // Each connection reapplies its own unchanged configuration before
            // reading. Older releases share a cache: neither list is server proof.
            AccessibilityServiceInfo info = automation.getServiceInfo();
            result.append("\nUiAutomation flags=").append(info == null ? "unavailable" : "0x" + Integer.toHexString(info.flags));
            if (info != null) automation.setServiceInfo(info);
            result.append(info == null ? " refresh=unavailable" : " refresh=same-info-requested");
            appendConnectionWindows(result, automation.getWindows());
        } catch (RuntimeException unavailable) {
            result.append("\nUiAutomation unavailable: ").append(unavailable.getClass().getSimpleName());
        }
        String selected = null;
        int selectedUid = -1;
        try {
            String component;
            try { component = readSecure("default_input_method"); }
            catch (RuntimeException restricted) {
                result.append("\nselectedIme read=existing shell fallback");
                component = readSecureFromShell("default_input_method");
            }
            if (component != null && component.length() <= 256
                    && component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) {
                selected = component.substring(0, component.indexOf('/'));
                selectedUid = context.getPackageManager().getApplicationInfo(selected, 0).uid;
                result.append("\nselectedIme=").append(component).append(" uid=").append(selectedUid);
            } else result.append("\nselectedIme unavailable or invalid");
        } catch (Exception unavailable) {
            result.append("\nselectedIme identity unavailable: ").append(unavailable.getClass().getSimpleName());
        }
        appendKeyboardServerMetadata(result, true, selectedUid);
        appendKeyboardServerMetadata(result, false, selectedUid);
        return result.toString();
    }

    /** Does not fetch roots or recycle caller-owned windows. */
    static String compactWindowMetadata(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return "unavailable";
        StringBuilder result = new StringBuilder("count=" + windows.size());
        int count = 0;
        for (AccessibilityWindowInfo window : windows) {
            if (count++ == 10) { result.append(" [remaining windows omitted]"); break; }
            Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
            result.append(" {id=").append(window.getId()).append(",type=").append(window.getType())
                    .append(",display=").append(window.getDisplayId()).append(",layer=").append(window.getLayer())
                    .append(",focused=").append(window.isFocused()).append(",active=").append(window.isActive())
                    .append(",bounds=").append(bounds.toShortString()).append('}');
        }
        return result.toString();
    }

    private static void appendConnectionWindows(StringBuilder target, List<AccessibilityWindowInfo> windows) {
        try {
            target.append(" windows=").append(compactWindowMetadata(windows));
        } finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private void appendKeyboardServerMetadata(StringBuilder result, boolean windowManager, int selectedUid) {
        String label = windowManager ? "WM fixture/selected-IME window metadata" : "AMS accessibility fixture/input-method records";
        result.append('\n').append(label).append(':');
        String command = windowManager ? "dumpsys -t 2 window windows" : "dumpsys -t 2 accessibility";
        int byteLimit = 128 * 1024, outputLimit = windowManager ? 2_000 : 1_400;
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while (output.size() < byteLimit && (read = input.read(buffer, 0, Math.min(buffer.length, byteLimit - output.size()))) != -1)
                output.write(buffer, 0, read);
            String dump = output.toString(StandardCharsets.UTF_8.name());
            StringBuilder selected = new StringBuilder();
            boolean truncated = false;
            int imeRecords = 0, fixtureRecords = 0, associatedImeRecords = 0;
            if (windowManager) {
                Pattern owner = Pattern.compile("\\bmOwnerUid=" + selectedUid + "\\b");
                Matcher targets = Pattern.compile("(?m)^[\\t ]*(m?(?:ImeLayeringTarget|ImeInputTarget|ImeControlTarget|InputMethodWindow)|imeLayeringTarget|imeInputTarget|imeControlTarget)=(.*)$").matcher(dump);
                int targetCount = 0;
                while (targetCount < 4 && targets.find()) {
                    targetCount++;
                    Matcher token = Pattern.compile("Window\\{[0-9a-fA-F]+").matcher(targets.group(2));
                    result.append("\n  ").append(targets.group(1)).append('=')
                            .append(token.find() ? token.group() : targets.group(2).trim().equals("null") ? "null" : "unparsed")
                            .append(targets.group(2).contains(FIXTURE) ? " fixture" : "");
                }
                if (targetCount == 0) result.append(" [IME target references absent from bounded dump]");
                String[] blocks = dump.split("(?m)(?=^[\\t ]*Window #[0-9]+ )");
                // Put the selected IME first so fixture metadata cannot consume
                // the output budget before the missing-window question is shown.
                for (int pass = 0; pass < 2 && selected.length() < outputLimit; pass++) for (String block : blocks) {
                    String[] lines = block.trim().split("\\r?\\n");
                    String header = lines[0].trim();
                    if (!header.matches("Window #[0-9]+.*")) continue;
                    boolean fixture = header.contains(FIXTURE);
                    boolean inputMethod = Pattern.compile("\\bty=(?:INPUT_METHOD|2011)\\b").matcher(block).find();
                    boolean matchesIme = inputMethod && selectedUid >= 0 && owner.matcher(block).find();
                    if (pass == 0 ? !inputMethod : !fixture) continue;
                    if (fixture) fixtureRecords++; else imeRecords++;
                    if (matchesIme) associatedImeRecords++;
                    selected.append("\n  ").append(fixture ? "fixture" : matchesIme
                            ? "selected-IME UID matched" : "IME window; selected owner unassociated");
                    Matcher identity = Pattern.compile("Window #[0-9]+|Window\\{[0-9a-fA-F]+").matcher(header);
                    while (identity.find()) selected.append(' ').append(identity.group());
                    int recordLimit = selected.length() + 800;
                    for (String raw : lines) {
                        String line = raw.trim();
                        // Only window registration, geometry, type and visibility;
                        // never root/node text or unrelated Activity/service state.
                        if (!line.matches(".*(?:mDisplayId|mOwnerUid|mViewVisibility|mHasSurface|isOnScreen|isVisible|mDestroying|mRemoved|mRemoveOnExit|mAnimatingExit|mAppDied|mObscured|mDrawState|mHidden|mVisibleRequested|mSurfaceShown|mFrame|frame|mTouchableInsets|touchableRegion)=.*")
                                && !line.startsWith("mAttrs=") && !line.startsWith("Frames:") && !line.startsWith("fl=")) continue;
                        selected.append("\n   ").append(line, 0, Math.min(line.length(), 320));
                        if (line.length() > 320) truncated = true;
                        if (selected.length() >= recordLimit || selected.length() >= outputLimit) { truncated = true; break; }
                    }
                    if (selected.length() >= outputLimit) break;
                }
            } else {
                Pattern fields = Pattern.compile("\\b(?:id|type|layer|focused|active|displayId|parentId)=[^,\\]]+|bounds=Rect\\([^)]*\\)");
                for (String line : dump.split("\\r?\\n")) {
                    if (!line.contains("AccessibilityWindowInfo[")) continue;
                    boolean fixture = line.contains("Agent OS Test Fixture");
                    boolean inputMethod = line.contains("type=TYPE_INPUT_METHOD") || line.matches(".*\\btype=2[,\\]].*");
                    if (!fixture && !inputMethod) continue;
                    if (fixture) fixtureRecords++; else imeRecords++;
                    selected.append("\n  ").append(fixture ? "fixture" : "input-method type; selected UID not exposed");
                    Matcher metadata = fields.matcher(line);
                    while (metadata.find()) selected.append(' ').append(metadata.group());
                    if (selected.length() >= outputLimit) break;
                }
            }
            result.append(" fixtureRecords=").append(fixtureRecords)
                    .append(", inputMethodTypeRecords=").append(imeRecords);
            if (windowManager) result.append(", selectedImeUidMatches=").append(associatedImeRecords);
            if (selected.length() == 0) result.append(" [no matching records in bounded dump; not proof of absence]");
            else result.append(selected, 0, Math.min(selected.length(), outputLimit));
            if (truncated || selected.length() > outputLimit || output.size() == byteLimit) result.append(" [bounded/truncated]");
            if (windowManager && selectedUid < 0) result.append(" [selected IME UID unavailable]");
        } catch (Exception unavailable) {
            result.append(" [unavailable: ").append(unavailable.getClass().getSimpleName()).append(']');
        }
    }

    private void appendShellDump(StringBuilder diagnostic, String label, String command, int limit) {
        diagnostic.append("\n").append(label).append(':');
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int length;
            while (output.size() < limit && (length = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()))) != -1) {
                output.write(buffer, 0, length);
            }
            diagnostic.append("\n").append(output.toString(StandardCharsets.UTF_8.name()));
            if (output.size() == limit) diagnostic.append("\n[bounded diagnostic: ").append(label).append(']');
        } catch (Exception failure) {
            diagnostic.append("\nDump unavailable: ").append(failure.getClass().getSimpleName());
        }
    }

    AutoCloseable enableTouchExploration() {
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int previous = info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        assertNotNull(manager);
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        automation.setServiceInfo(info);
        Runnable restore = () -> {
            AccessibilityServiceInfo current = automation.getServiceInfo();
            current.flags = (current.flags & ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) | previous;
            automation.setServiceInfo(current);
        };
        try { await("Test accessibility focus requires touch exploration", manager::isTouchExplorationEnabled); }
        catch (Throwable failure) { restore.run(); throw failure; }
        return restore::run;
    }

    String shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand(command));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int length;
            while ((length = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, length);
                if (bytes.size() > 32_768) throw new IllegalStateException("Unexpected test command output size");
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String readSecure(String key) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try { return Settings.Secure.getString(context.getContentResolver(), key); }
        finally { automation.dropShellPermissionIdentity(); }
    }

    private void writeSecure(String key, String value) {
        // executeShellCommand is not a shell-language parser: quoted values can be stored
        // literally. Use the settings provider so component IDs and empty values stay exact.
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue("Could not write secure test setting " + key,
                    Settings.Secure.putString(context.getContentResolver(), key, value));
            if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(key)) {
                assertEquals("Secure test setting read-back mismatch: " + key,
                        value, Settings.Secure.getString(context.getContentResolver(), key));
            }
            // ACCESSIBILITY_ENABLED is also recomputed by the system while UiAutomation
            // is connected. The component list stays exact; service connection/disconnection
            // is the authoritative state, checked separately instead of racing the global flag.
        } finally { automation.dropShellPermissionIdentity(); }
    }

    static String windowDiagnostics() {
        AgentAccessibilityService active = AgentAccessibilityService.getInstance();
        if (active == null) return "Fixture window diagnostics: service disconnected";
        StringBuilder diagnostic = new StringBuilder("Fixture window diagnostics:");
        WindowManager manager = (WindowManager) active.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) {
            appendMetrics(diagnostic, "current", manager.getCurrentWindowMetrics());
            appendMetrics(diagnostic, "maximum", manager.getMaximumWindowMetrics());
        }
        List<AccessibilityWindowInfo> windows = active.getWindows();
        if (windows == null) return diagnostic.append(" unavailable").toString();
        try {
            for (AccessibilityWindowInfo window : windows) {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                diagnostic.append("\n type=").append(window.getType())
                        .append(" id=").append(window.getId())
                        .append(" title=").append(window.getTitle())
                        .append(" layer=").append(window.getLayer())
                        .append(" active=").append(window.isActive())
                        .append(" focused=").append(window.isFocused())
                        .append(" display=").append(window.getDisplayId())
                        .append(" bounds=").append(bounds.toShortString());
                if (Build.VERSION.SDK_INT >= 33) {
                    Region region = new Region();
                    window.getRegionInScreen(region);
                    diagnostic.append(" region=").append(region);
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) { diagnostic.append(" root=null"); continue; }
                try {
                    diagnostic.append(" rootId=").append(root.getWindowId())
                            .append(" rootPackage=").append(root.getPackageName())
                            .append(" rootClass=").append(root.getClassName())
                            .append(" visibleBeforeRefresh=").append(root.isVisibleToUser());
                    boolean refreshed = root.refresh();
                    diagnostic.append(" rootRefresh=").append(refreshed)
                            .append(" visibleAfterRefresh=").append(root.isVisibleToUser());
                } finally { root.recycle(); }
            }
            return diagnostic.toString();
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    /** Failure-only server evidence; it never refreshes a snapshot or converts a test to success. */
    private static String serverWindowDiagnostics() {
        StringBuilder result = new StringBuilder("Synthetic fixture server window evidence:");
        try {
            UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                    UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            appendFilteredServerDump(result, automation, "window-manager", "dumpsys -t 2 window windows", true, 4_000);
            appendFilteredServerDump(result, automation, "accessibility-manager", "dumpsys -t 2 accessibility", false, 3_000);
        } catch (RuntimeException unavailable) { result.append(" [unavailable: ").append(unavailable.getClass().getSimpleName()).append(']'); }
        return result.toString();
    }

    private static void appendFilteredServerDump(StringBuilder result, UiAutomation automation,
                                                 String label, String command, boolean windowManager, int outputLimit) {
        result.append("\n ").append(label).append(':');
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            int byteLimit = 256 * 1024;
            while (bytes.size() < byteLimit && (length = input.read(buffer, 0, Math.min(buffer.length, byteLimit - bytes.size()))) != -1) {
                bytes.write(buffer, 0, length);
            }
            boolean fixtureWindow = false;
            int emitted = 0;
            for (String raw : bytes.toString(StandardCharsets.UTF_8.name()).split("\\r?\\n")) {
                String line = raw.trim();
                boolean include;
                if (windowManager) {
                    if (line.matches("Window #[0-9]+.*")) fixtureWindow = line.contains(FIXTURE);
                    include = fixtureWindow && (line.matches("Window #[0-9]+.*")
                            || line.matches(".*(?:mViewVisibility|mHasSurface|isOnScreen|isVisible|mDestroying|mRemoved|mRemoveOnExit|mAnimatingExit|mAppDied|mObscured|mDrawState|mHidden|mVisibleRequested|mSurfaceShown)=.*"));
                    include |= (line.startsWith("mCurrentFocus=") || line.startsWith("mFocusedApp=")) && line.contains(FIXTURE);
                } else {
                    // Only the fixture's platform window metadata, never other apps'
                    // node text, connected service configuration, or account state.
                    include = line.contains("AccessibilityWindowInfo[") && line.contains("Agent OS Test Fixture");
                }
                if (!include) continue;
                line = line.substring(0, Math.min(line.length(), 1_200));
                int available = outputLimit - emitted;
                if (available <= 0) break;
                String bounded = line.substring(0, Math.min(line.length(), available));
                result.append("\n  ").append(bounded);
                emitted += bounded.length() + 3;
            }
            if (emitted == 0) result.append(" [no matching fixture entries in bounded dump]");
            if (emitted >= outputLimit || bytes.size() == byteLimit) result.append(" [bounded server evidence]");
        } catch (Exception unavailable) {
            result.append(" [unavailable: ").append(unavailable.getClass().getSimpleName()).append(']');
        }
    }

    private static void appendMetrics(StringBuilder diagnostic, String name, WindowMetrics metrics) {
        WindowInsets insets = metrics.getWindowInsets();
        diagnostic.append("\n ").append(name).append("Metrics=").append(metrics.getBounds().toShortString())
                .append(" status=").append(insets.getInsets(WindowInsets.Type.statusBars()))
                .append(" navigation=").append(insets.getInsets(WindowInsets.Type.navigationBars()))
                .append(" stableBars=").append(insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()))
                .append(" cutout=").append(insets.getInsets(WindowInsets.Type.displayCutout()))
                .append(" statusVisible=").append(insets.isVisible(WindowInsets.Type.statusBars()))
                .append(" navigationVisible=").append(insets.isVisible(WindowInsets.Type.navigationBars()));
    }

    static int focusedWindowId(AgentAccessibilityService service) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) return -1;
        int found = -1;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                if (found != -1) return -1;
                found = window.getId();
            }
            return found;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private String connectionDiagnostics() {
        try {
            String dump = shell("dumpsys accessibility");
            if (dump.length() > 8_000) dump = dump.substring(0, 8_000) + "\n[truncated]";
            return "enabledServices=" + readSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    + "; accessibilityEnabled=" + readSecure(Settings.Secure.ACCESSIBILITY_ENABLED)
                    + "\n" + dump;
        } catch (Exception failure) { return "Connection diagnostics unavailable: " + failure.getClass().getSimpleName(); }
    }

    private static void await(String failure, java.util.function.BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        fail(failure);
    }

    @Override public void close() throws Exception {
        control.cancel();
        try {
            if (service != null) instrumentation.runOnMainSync(() -> {
                service.hideOverlay();
                service.setOverlayCallbacks(null);
            });
            shell("input keyevent 224");
            shell("wm dismiss-keyguard");
        } finally {
            try { writeSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices); }
            finally { writeSecure(Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled); }
        }
    }
}
