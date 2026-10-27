package dev.agentos.compat;

import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures only the verified frozen external application on the dedicated synthetic device. */
final class SmokeCapture {
    private SmokeCapture() { }

    static void capture(SmokeHarness harness, File directory, String state, String stage, JSONArray captures) throws Exception {
        JSONObject item = new JSONObject().put("stage", stage).put("capturedAt", System.currentTimeMillis())
                .put("secureFlagsCleared", false);
        Bitmap bitmap = null;
        try {
            harness.verifyTargetIdentity();
            String hiddenIme = awaitHiddenNotesInputMethod(harness);
            try (SmokeHarness.WindowSnapshot before = harness.appWindow(false)) {
                SmokeHarness.require(before.bounds.width() <= 4096 && before.bounds.height() <= 4096,
                        "Unexpected screenshot geometry");
                // visible-apps includes both DisplayContent focus and full visible
                // Window attributes; the windows/all subcommands omit focus.
                WindowFlags flags = inspectedWindow(harness.shell("dumpsys -t 2 window visible-apps"), harness.target);
                if (flags.secure) {
                    item.put("status", "blocked").put("reason", "application_window_is_secure");
                    return;
                }
                bitmap = harness.automation.takeScreenshot();
                if (bitmap != null && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                    Bitmap readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    bitmap.recycle();
                    bitmap = readable;
                }
                if (bitmap == null) {
                    item.put("status", "blocked").put("reason", "platform_returned_no_screenshot");
                    return;
                }
                if (bitmap.getWidth() > 4096 || bitmap.getHeight() > 4096
                        || (long) bitmap.getWidth() * bitmap.getHeight() > 16_777_216L) {
                    item.put("status", "blocked").put("reason", "unexpected_screenshot_dimensions");
                    return;
                }
                try (SmokeHarness.WindowSnapshot after = harness.appWindow(false)) {
                    WindowFlags current = inspectedWindow(harness.shell("dumpsys -t 2 window visible-apps"), harness.target);
                    if (before.id != after.id || !before.bounds.equals(after.bounds)
                            || current.secure || !flags.identity.equals(current.identity)) {
                        item.put("status", "blocked").put("reason", "window_changed_during_capture");
                        return;
                    }
                    harness.verifyTargetIdentity();
                    if (hiddenIme != null && !hiddenIme.equals(hiddenNotesInputMethod(harness))) {
                        item.put("status", "blocked").put("reason", "input_method_changed_during_capture");
                        return;
                    }
                    if (!hasVisibleCalculatorContent(bitmap, before.bounds)) {
                        item.put("status", "blocked").put("reason", "application_surface_is_blank_or_protected");
                        return;
                    }
                }
                String name = state + "-" + stage + "-" + captures.length() + ".png";
                File file = new File(directory, name);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG encoding failed");
                    output.getFD().sync();
                }
                if (file.length() > 8 * 1024 * 1024 || file.length() == 0) {
                    if (!file.delete()) throw new IllegalStateException("Could not remove oversized test capture");
                    throw new IllegalStateException("PNG size is outside evidence limits");
                }
                String hash;
                try (FileInputStream input = new FileInputStream(file)) { hash = SmokeHarness.digest(input, 8 * 1024 * 1024); }
                item.put("status", "captured").put("path", name).put("sha256", hash).put("bytes", file.length())
                        .put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                        .put("windowId", before.id).put("windowBounds", before.bounds.toShortString())
                        .put("windowFlags", flags.flags);
            }
        } catch (AssertionError refusal) {
            item.put("status", "blocked").put("reason", "unverified_identity_window_or_obscuration")
                    .put("detail", safeMessage(refusal));
        } catch (CaptureRefused refusal) {
            item.put("status", "blocked").put("reason", refusal.getMessage());
        } catch (Exception unavailable) {
            item.put("status", "unavailable").put("reason", unavailable.getClass().getSimpleName());
        } finally {
            if (bitmap != null) bitmap.recycle();
            captures.put(item);
        }
    }

    private static String awaitHiddenNotesInputMethod(SmokeHarness harness) throws Exception {
        if (harness.target != SmokeHarness.NOTES_TARGET) return null;
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        String stable = null, last = "input_method_hidden_state_unverified";
        // Read-only, at most four bounded dumps; never press BACK from capture.
        // WMS state is not a SurfaceFlinger presentation fence. Two separated
        // observations plus a post-capture check reduce, but cannot atomically
        // eliminate, a new surface appearing between observations.
        for (int attempt = 0; attempt < 4 && SystemClock.elapsedRealtime() < deadline; attempt++) {
            try {
                String current = hiddenNotesInputMethod(harness);
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new CaptureRefused("input_method_hidden_state_timed_out");
                if (current.equals(stable)) return current;
                stable = current;
            } catch (CaptureRefused refusal) {
                stable = null;
                last = refusal.getMessage();
            }
            if (attempt < 3 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(150);
        }
        throw new CaptureRefused(last);
    }

    private static String hiddenNotesInputMethod(SmokeHarness harness) throws Exception {
        ServiceInfo ime = harness.configuredSystemInputMethod();
        if (ime == null) throw new CaptureRefused("configured_system_input_method_unverified");
        String identity = inspectedHiddenInputMethod(harness.shell("dumpsys -t 2 window -a windows"),
                ime.applicationInfo.uid, ime.packageName);
        return identity + "/" + ime.name;
    }

    /** Narrow Notes evidence check, not a general window parser or an action permission. */
    private static String inspectedHiddenInputMethod(String dump, int ownerUid, String packageName) throws CaptureRefused {
        if (!dump.startsWith("WINDOW MANAGER WINDOWS")) throw new CaptureRefused("input_method_window_dump_unavailable");
        Matcher selected = Pattern.compile("(?m)^[\\t ]*mInputMethodWindow=Window\\{([0-9a-fA-F]+)\\s+u0\\s+[^}]+\\}").matcher(dump);
        if (!selected.find()) throw new CaptureRefused("input_method_window_reference_unavailable");
        String token = selected.group(1);
        if (selected.find()) throw new CaptureRefused("input_method_window_reference_ambiguous");
        String[] records = dump.split("(?m)(?=^[\\t ]*Window #[0-9]+ )");
        String matched = null;
        int inputMethods = 0;
        for (String record : records) {
            if (!record.trim().startsWith("Window #")) continue;
            if (!Pattern.compile("\\bty=(?:INPUT_METHOD|2011)\\b").matcher(record).find()) continue;
            inputMethods++;
            if (Pattern.compile("^[\\t ]*Window #[0-9]+ Window\\{" + Pattern.quote(token)
                    + "\\s+u0\\s+[^}]+\\}:").matcher(record).find()) matched = record;
        }
        if (inputMethods != 1 || matched == null) throw new CaptureRefused("input_method_window_record_missing_or_ambiguous");
        if (!oneField(matched, "mOwnerUid", Integer.toString(ownerUid))
                || !oneField(matched, "mDisplayId", "0")
                || !Pattern.compile("(?m)^[\\t ]*mOwnerUid=" + ownerUid
                        + "[\\t ]+[^\\r\\n]*\\bpackage=" + Pattern.quote(packageName) + "(?:[\\t ]|$)").matcher(matched).find())
            throw new CaptureRefused("input_method_window_identity_mismatch");
        if (!oneField(matched, "isOnScreen", "false") || !oneField(matched, "isVisible", "false")
                || !oneField(matched, "isReadyForDisplay()", "false")
                || !oneField(matched, "mLastHidden", "true")
                || !oneField(matched, "mEnterAnimationPending", "false")
                || Pattern.compile("\\bmAnimatingExit=true\\b").matcher(matched).find())
            throw new CaptureRefused("input_method_is_visible_animating_or_unverified");
        if (!oneField(matched, "mHasSurface", "false")
                && !(oneField(matched, "mHasSurface", "true") && oneField(matched, "Surface: shown", "false")))
            throw new CaptureRefused("input_method_surface_is_visible_or_unverified");
        return token + "/" + ownerUid + "/" + packageName;
    }

    private static boolean oneField(String block, String field, String expected) {
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(field) + "=([^\\s]+)").matcher(block);
        return matcher.find() && expected.equals(matcher.group(1)) && !matcher.find();
    }

    private static WindowFlags inspectedWindow(String dump) throws CaptureRefused {
        return inspectedWindow(dump, SmokeHarness.CALCULATOR_TARGET);
    }

    private static WindowFlags inspectedWindow(String dump, SmokeHarness.Target app) throws CaptureRefused {
        SmokeHarness.require(app == SmokeHarness.CALCULATOR_TARGET || app == SmokeHarness.NOTES_TARGET, "Unknown capture target");
        Matcher currentFocusFields = Pattern.compile("\\bmCurrentFocus=").matcher(dump);
        int focusFields = 0;
        while (currentFocusFields.find()) focusFields++;
        if (focusFields != 1) throw new CaptureRefused("window_manager_focus_is_missing_or_ambiguous");
        Pattern focusPattern = Pattern.compile("mCurrentFocus=Window\\{([0-9a-fA-F]+)\\s+u0\\s+(" + Pattern.quote(app.packageName) + "/[^}\\s]+)\\}");
        Matcher focus = focusPattern.matcher(dump);
        if (!focus.find()) throw new CaptureRefused("window_manager_did_not_confirm_frozen_application_focus");
        String token = focus.group(1);
        String component = focus.group(2);
        if (focus.find()) throw new CaptureRefused("window_manager_focus_is_ambiguous");
        if (!component.equals(app.packageName + "/" + app.packageName + ".activities.MainActivity")
                && !component.equals(app.packageName + "/.activities.MainActivity")) {
            throw new CaptureRefused("focused_activity_is_not_the_frozen_application_page");
        }
        Pattern header = Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{" + Pattern.quote(token)
                + "\\s+u0\\s+" + Pattern.quote(component) + "\\}:");
        Matcher target = header.matcher(dump);
        if (!target.find()) throw new CaptureRefused("focused_window_attributes_are_unavailable");
        int start = target.end();
        Matcher next = Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{").matcher(dump);
        int end = next.find(start) ? next.start() : dump.length();
        String block = dump.substring(start, end);
        int attrsStart = block.indexOf("mAttrs={");
        if (attrsStart < 0) throw new CaptureRefused("focused_window_attributes_are_unavailable");
        int brace = attrsStart + "mAttrs=".length();
        int depth = 0, attrsEnd = -1;
        for (int i = brace; i < block.length(); i++) {
            if (block.charAt(i) == '{') depth++;
            else if (block.charAt(i) == '}' && --depth == 0) { attrsEnd = i; break; }
        }
        if (attrsEnd < 0 || attrsEnd - brace > 8192) throw new CaptureRefused("focused_window_attributes_are_incomplete");
        String attributes = block.substring(brace + 1, attrsEnd);
        Matcher flagsMatcher = Pattern.compile("(?:^|\\s)fl=([^\\r\\n}]*)").matcher(attributes);
        if (!flagsMatcher.find()) throw new CaptureRefused("window_secure_flag_could_not_be_read");
        String flags = flagsMatcher.group(1).trim();
        if (flags.isEmpty()) throw new CaptureRefused("window_secure_flag_could_not_be_read");
        boolean secure;
        if (flags.matches("(?:0x)?[0-9a-fA-F]+")) {
            try { secure = (Long.parseLong(flags.replaceFirst("^0x", ""), 16) & 0x2000L) != 0; }
            catch (NumberFormatException invalid) { throw new CaptureRefused("window_flags_have_unknown_format"); }
        } else if (flags.matches("[A-Z0-9_| ]+")) {
            secure = Pattern.compile("(?:^|[ |])(?:FLAG_)?SECURE(?:$|[ |])").matcher(flags).find();
        } else {
            throw new CaptureRefused("window_flags_have_unknown_format");
        }
        return new WindowFlags(token + "/" + component + "/" + flags, flags, secure);
    }

    static void verifyWindowParser() throws Exception {
        // Parser-only strings, never image evidence. Exercise this on Android ICU.
        String component = "org.fossify.math/org.fossify.math.activities.MainActivity";
        String focus = "  mCurrentFocus=Window{abc u0 " + component + "}\n";
        String windows = "  Window #0 Window{abc u0 " + component + "}:\n    mAttrs={ fl=0x100}\n";
        String complete = "Display: mDisplayId=0\n" + focus + windows;
        SmokeHarness.require(!inspectedWindow(complete).secure, "Visible app focus and attributes were not matched");
        SmokeHarness.require(inspectedWindow(complete.replace("fl=0x100", "fl=0x2100")).secure,
                "Numeric secure window flag was missed");
        SmokeHarness.require(inspectedWindow(complete.replace("fl=0x100", "fl=SECURE")).secure,
                "Named secure window flag was missed");
        verifyInputMethodWindowParser();
        String notes = complete.replace("org.fossify.math", "org.fossify.notes");
        SmokeHarness.require(!inspectedWindow(notes, SmokeHarness.NOTES_TARGET).secure, "Pinned notes page was not matched");
        boolean crossPackageRefused = false;
        try { inspectedWindow(notes, SmokeHarness.CALCULATOR_TARGET); } catch (CaptureRefused expected) { crossPackageRefused = true; }
        SmokeHarness.require(crossPackageRefused, "Calculator capture accepted the notes package");
        for (String rejected : new String[] {windows, complete + "mCurrentFocus=null\n",
                complete.replace("Window #0 Window{abc", "Window #0 Window{def"),
                complete.replace("org.fossify.math.activities.MainActivity", "org.fossify.math.activities.OtherActivity"),
                complete.replace("fl=0x100", "unknown=0x100")}) {
            boolean refused = false;
            try { inspectedWindow(rejected); } catch (CaptureRefused expected) { refused = true; }
            SmokeHarness.require(refused, "Missing, ambiguous, foreign or uninspectable window was accepted");
        }
    }

    private static void verifyInputMethodWindowParser() throws Exception {
        // Parser-only fixtures. These are never written as UI/PNG evidence.
        String ime = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
                + "  Window #0 Window{abc u0 InputMethod}:\n"
                + "    mDisplayId=0\n    mOwnerUid=10100 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n"
                + "    mAttrs={ ty=INPUT_METHOD }\n"
                + "    mHasSurface=true isReadyForDisplay()=false\n"
                + "    mLastHidden=true mEnterAnimationPending=false\n"
                + "    Surface: shown=false layer=0\n"
                + "    isOnScreen=false\n    isVisible=false\n"
                + "  mInputMethodWindow=Window{abc u0 InputMethod}\n";
        inspectedHiddenInputMethod(ime, 10100, "com.android.inputmethod.latin");
        inspectedHiddenInputMethod(ime.replace("mHasSurface=true", "mHasSurface=false")
                .replace("    Surface: shown=false layer=0\n", ""), 10100, "com.android.inputmethod.latin");
        for (String rejected : new String[] {
                ime.replace("isOnScreen=false", "isOnScreen=true"),
                ime.replace("isVisible=false", "isVisible=true"),
                ime.replace("Surface: shown=false", "Surface: shown=true"),
                ime.replace("mEnterAnimationPending=false", "mEnterAnimationPending=true"),
                ime.replace("mLastHidden=true", "mLastHidden=false"),
                ime.replace("isReadyForDisplay()=false", "isReadyForDisplay()=true"),
                ime.replace("isVisible=false", "isVisible=false isVisible=false"),
                ime.replace("isOnScreen=false\n", ""),
                ime.replace("mOwnerUid=10100", "mOwnerUid=10101"),
                ime.replace("mDisplayId=0", "mDisplayId=1"),
                ime.replace("ty=INPUT_METHOD", "ty=APPLICATION"),
                ime.replace("mInputMethodWindow=Window{abc", "mInputMethodWindow=Window{def"),
                ime.replace("mInputMethodWindow=", "unknownWindow="),
                ime + "mInputMethodWindow=Window{abc u0 InputMethod}\n",
                ime.replace("isOnScreen=false", "mAnimatingExit=true isOnScreen=false")}) {
            boolean refused = false;
            try { inspectedHiddenInputMethod(rejected, 10100, "com.android.inputmethod.latin"); }
            catch (CaptureRefused expected) { refused = true; }
            SmokeHarness.require(refused, "Visible, animating or unverified IME surface was accepted");
        }
    }

    private static boolean hasVisibleCalculatorContent(Bitmap image, Rect bounds) {
        Rect area = new Rect(bounds);
        if (!area.intersect(0, 0, image.getWidth(), image.getHeight()) || area.width() < 64 || area.height() < 64) return false;
        // Sample the application interior, not the status/navigation bars, so a secure
        // black application surface cannot pass because a system icon remains visible.
        area.inset(Math.max(8, area.width() / 12), Math.max(16, area.height() / 10));
        Set<Integer> colors = new HashSet<>();
        int nonBlack = 0;
        for (int row = 1; row < 20; row++) {
            for (int column = 1; column < 20; column++) {
                int pixel = image.getPixel(area.left + area.width() * column / 20, area.top + area.height() * row / 20);
                colors.add(pixel);
                if ((pixel & 0x00ffffff) > 0x00080808) nonBlack++;
            }
        }
        return colors.size() > 3 && nonBlack > 36;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 240));
    }

    private static final class WindowFlags {
        final String identity;
        final String flags;
        final boolean secure;
        WindowFlags(String identity, String flags, boolean secure) { this.identity = identity; this.flags = flags; this.secure = secure; }
    }

    private static final class CaptureRefused extends Exception {
        CaptureRefused(String reason) { super(reason); }
    }
}
