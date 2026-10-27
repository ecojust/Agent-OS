package dev.agentos.compat;

import android.graphics.Bitmap;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures only the verified external calculator on the dedicated synthetic device. */
final class SmokeCapture {
    private SmokeCapture() { }

    static void capture(SmokeHarness harness, File directory, String state, String stage, JSONArray captures) throws Exception {
        JSONObject item = new JSONObject().put("stage", stage).put("capturedAt", System.currentTimeMillis())
                .put("secureFlagsCleared", false);
        Bitmap bitmap = null;
        try {
            harness.verifyCalculatorIdentity();
            try (SmokeHarness.WindowSnapshot before = harness.calculatorWindow()) {
                SmokeHarness.require(before.bounds.width() <= 4096 && before.bounds.height() <= 4096,
                        "Unexpected screenshot geometry");
                // visible-apps includes both DisplayContent focus and full visible
                // Window attributes; the windows/all subcommands omit focus.
                WindowFlags flags = inspectedWindow(harness.shell("dumpsys -t 2 window visible-apps"));
                if (flags.secure) {
                    item.put("status", "blocked").put("reason", "calculator_window_is_secure");
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
                try (SmokeHarness.WindowSnapshot after = harness.calculatorWindow()) {
                    WindowFlags current = inspectedWindow(harness.shell("dumpsys -t 2 window visible-apps"));
                    if (before.id != after.id || !before.bounds.equals(after.bounds)
                            || current.secure || !flags.identity.equals(current.identity)) {
                        item.put("status", "blocked").put("reason", "window_changed_during_capture");
                        return;
                    }
                    harness.verifyCalculatorIdentity();
                    if (!hasVisibleCalculatorContent(bitmap, before.bounds)) {
                        item.put("status", "blocked").put("reason", "calculator_surface_is_blank_or_protected");
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

    private static WindowFlags inspectedWindow(String dump) throws CaptureRefused {
        Matcher currentFocusFields = Pattern.compile("\\bmCurrentFocus=").matcher(dump);
        int focusFields = 0;
        while (currentFocusFields.find()) focusFields++;
        if (focusFields != 1) throw new CaptureRefused("window_manager_focus_is_missing_or_ambiguous");
        Pattern focusPattern = Pattern.compile("mCurrentFocus=Window\\{([0-9a-fA-F]+)\\s+u0\\s+(org\\.fossify\\.math/[^}\\s]+)\\}");
        Matcher focus = focusPattern.matcher(dump);
        if (!focus.find()) throw new CaptureRefused("window_manager_did_not_confirm_calculator_focus");
        String token = focus.group(1);
        String component = focus.group(2);
        if (focus.find()) throw new CaptureRefused("window_manager_focus_is_ambiguous");
        if (!component.equals("org.fossify.math/org.fossify.math.activities.MainActivity")
                && !component.equals("org.fossify.math/.activities.MainActivity")) {
            throw new CaptureRefused("focused_activity_is_not_the_frozen_calculator_page");
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
        for (String rejected : new String[] {windows, complete + "mCurrentFocus=null\n",
                complete.replace("Window #0 Window{abc", "Window #0 Window{def"),
                complete.replace("org.fossify.math.activities.MainActivity", "org.fossify.math.activities.OtherActivity"),
                complete.replace("fl=0x100", "unknown=0x100")}) {
            boolean refused = false;
            try { inspectedWindow(rejected); } catch (CaptureRefused expected) { refused = true; }
            SmokeHarness.require(refused, "Missing, ambiguous, foreign or uninspectable window was accepted");
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
