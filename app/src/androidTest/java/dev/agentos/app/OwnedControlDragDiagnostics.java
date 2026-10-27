package dev.agentos.app;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Failure-only synthetic-device measurements; never a source of ownership or authorization. */
final class OwnedControlDragDiagnostics {
    private static final String NUMBER = "-?[0-9]{1,10}";
    private static final String RECT = "\\[" + NUMBER + "," + NUMBER + "\\]\\[" + NUMBER + "," + NUMBER + "\\]";

    private OwnedControlDragDiagnostics() { }

    /**
     * Caller holds the existing device gate until reads and recycling finish. Deadlines prevent
     * starting further reads and reject late results; they cannot interrupt a synchronous Binder
     * call already in flight. This never converts the original registration failure to success.
     */
    static String sample(DeviceHarness device, int windowId, int displayId, Rect expected) {
        long started = SystemClock.elapsedRealtime();
        StringBuilder out = new StringBuilder("Drag registration failure-only measurements: expectedId=")
                .append(windowId).append(" expectedDisplay=").append(displayId)
                .append(" expectedBounds=").append(expected.toShortString())
                .append(" samples_are_sequential_not_atomic=true");
        out.append('\n').append(localView(device, started));
        out.append('\n').append(platformWindow(device, windowId, started));
        out.append('\n').append(windowManager(device, started));
        return out.toString();
    }

    private static String localView(DeviceHarness device, long started) {
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 1_000;
        Runnable read = () -> {
            try {
                if (SystemClock.elapsedRealtime() >= deadline) { result.set("local=deadline"); return; }
                Field field = AgentAccessibilityService.class.getDeclaredField("overlay");
                field.setAccessible(true);
                View panel = (View) field.get(device.service);
                if (panel == null) { result.set("local=absent"); return; }
                int[] screen = new int[2], inWindow = new int[2];
                panel.getLocationOnScreen(screen); panel.getLocationInWindow(inWindow);
                Rect visible = new Rect(); boolean hasVisible = panel.getGlobalVisibleRect(visible);
                StringBuilder value = new StringBuilder("local atMs=").append(SystemClock.elapsedRealtime() - started)
                        .append(" attached=").append(panel.isAttachedToWindow())
                        .append(" shown=").append(panel.isShown()).append(" laidOut=").append(panel.isLaidOut())
                        .append(" layoutRequested=").append(panel.isLayoutRequested())
                        .append(" hasWindowToken=").append(panel.getWindowToken() != null)
                        .append(" viewBounds=").append(new Rect(screen[0], screen[1], screen[0] + panel.getWidth(), screen[1] + panel.getHeight()).toShortString())
                        .append(" inWindow=").append(inWindow[0]).append(',').append(inWindow[1])
                        .append(" hasGlobalVisibleRect=").append(hasVisible).append(" globalVisibleRect=").append(visible.toShortString())
                        .append(" translation=").append(panel.getTranslationX()).append(',').append(panel.getTranslationY())
                        .append(" scale=").append(panel.getScaleX()).append(',').append(panel.getScaleY());
                if (panel.getLayoutParams() instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams p = (WindowManager.LayoutParams) panel.getLayoutParams();
                    value.append(" paramsX=").append(p.x).append(" paramsY=").append(p.y)
                            .append(" paramsWidth=").append(p.width).append(" paramsHeight=").append(p.height)
                            .append(" paramsGravity=").append(p.gravity).append(" paramsType=").append(p.type)
                            .append(" paramsFlags=").append(p.flags);
                } else value.append(" params=unexpected_type");
                AgentAccessibilityService.OwnedControlWindowIdentity published = device.service.getOwnedControlWindowIdentity();
                value.append(" published=").append(published != null);
                if (published != null) value.append(" publishedId=").append(published.windowId)
                        .append(" publishedDisplay=").append(published.displayId)
                        .append(" publishedBounds=").append(published.copyBounds().toShortString())
                        .append(" publishedCurrent=").append(device.service.isOwnedControlWindowCurrent(published));
                result.set(SystemClock.elapsedRealtime() < deadline ? value.toString() : "local=late_read_not_used");
            } catch (Throwable failed) { result.set("local=unavailable errorClass=" + failed.getClass().getSimpleName()); }
            finally { done.countDown(); }
        };
        if (!main.post(read)) return "local=main_post_rejected";
        try {
            if (!done.await(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)) {
                main.removeCallbacks(read); return "local=timeout";
            }
        } catch (InterruptedException interrupted) {
            main.removeCallbacks(read); Thread.currentThread().interrupt(); return "local=interrupted";
        }
        return result.get() == null ? "local=unavailable" : result.get();
    }

    private static String platformWindow(DeviceHarness device, int id, long started) {
        StringBuilder out = new StringBuilder("platform atMs=").append(SystemClock.elapsedRealtime() - started);
        long deadline = SystemClock.elapsedRealtime() + 1_500;
        List<AccessibilityWindowInfo> windows = null;
        try {
            requireTime(deadline);
            windows = device.service.getWindows();
            requireTime(deadline);
            int matches = 0; AccessibilityWindowInfo selected = null;
            if (windows != null) for (AccessibilityWindowInfo window : windows) if (window.getId() == id) { matches++; selected = window; }
            out.append(" matchingIdCount=").append(matches);
            if (matches != 1) return out.toString();
            Rect bounds = new Rect(); selected.getBoundsInScreen(bounds);
            out.append(" id=").append(selected.getId()).append(" type=").append(selected.getType())
                    .append(" display=").append(selected.getDisplayId()).append(" focused=").append(selected.isFocused())
                    .append(" layer=").append(selected.getLayer()).append(" bounds=").append(bounds.toShortString());
            requireTime(deadline);
            AccessibilityNodeInfo root = selected.getRoot();
            if (root == null) return out.append(" root=null").toString();
            try {
                requireTime(deadline);
                root.getBoundsInScreen(bounds);
                out.append(" rootIdBefore=").append(root.getWindowId()).append(" rootBoundsBefore=").append(bounds.toShortString())
                        .append(" rootPackageMatchesBefore=").append(device.service.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName()));
                requireTime(deadline);
                boolean refreshed = root.refresh();
                requireTime(deadline);
                root.getBoundsInScreen(bounds);
                out.append(" rootRefresh=").append(refreshed).append(" rootIdAfter=").append(root.getWindowId())
                        .append(" rootBoundsAfter=").append(bounds.toShortString())
                        .append(" rootPackageMatchesAfter=").append(device.service.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName()));
            } finally { root.recycle(); }
            requireTime(deadline);
        } catch (Throwable failed) { out.append(" readStatus=unavailable errorClass=").append(failed.getClass().getSimpleName()); }
        finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
        return out.append(" endMs=").append(SystemClock.elapsedRealtime() - started).toString();
    }

    private static String windowManager(DeviceHarness device, long started) {
        StringBuilder out = new StringBuilder("wm atMs=").append(SystemClock.elapsedRealtime() - started);
        final int limit = 256 * 1024;
        long deadline = SystemClock.elapsedRealtime() + 1_500;
        try (ParcelFileDescriptor descriptor = device.automation.executeShellCommand("dumpsys -t 1 window -a windows");
             ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; boolean eof = false;
            while (bytes.size() < limit) {
                requireTime(deadline);
                StructPollfd poll = new StructPollfd(); poll.fd = descriptor.getFileDescriptor();
                poll.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
                if (Os.poll(new StructPollfd[]{poll}, (int) Math.max(1, deadline - SystemClock.elapsedRealtime())) == 0) break;
                requireTime(deadline);
                int read = input.read(chunk, 0, Math.min(chunk.length, limit - bytes.size()));
                if (read < 0) { eof = true; break; }
                bytes.write(chunk, 0, read);
            }
            requireTime(deadline);
            out.append(" shellStreamComplete=").append(eof).append(" bytesRead=").append(bytes.size());
            String dump = bytes.toString(StandardCharsets.UTF_8.name());
            boolean dumpFailure = Pattern.compile("(?i)DUMP TIMEOUT|DUMP FAILED|Error dumping service|Can't find service").matcher(dump).find();
            if (eof && !dumpFailure) out.append(" candidateCountScope=complete_returned_stdout ")
                    .append(filteredWindowManager(dump, Process.myUid()));
            else out.append(" windowFields=not_parsed_incomplete_or_failed_dump");
        } catch (Throwable failed) { out.append(" readStatus=unavailable errorClass=").append(failed.getClass().getSimpleName()); }
        return out.append(" endMs=").append(SystemClock.elapsedRealtime() - started).toString();
    }

    /** Fixed synthetic title + UID + type correlation, NOT cross-process Binder-token equivalence. */
    static String filteredWindowManager(String dump, int uid) {
        Matcher headers = Pattern.compile("(?m)^[\\t ]*Window #[0-9]+ Window\\{[^\\r\\n]*\\}:[\\t ]*$").matcher(dump);
        int count = 0, start = -1; String header = null, selected = null;
        while (headers.find()) {
            if (start >= 0) {
                String block = dump.substring(start, headers.start());
                if (matchesSynthetic(header, block, uid)) { count++; selected = block; }
            }
            header = headers.group(); start = headers.end();
        }
        if (start >= 0) {
            String block = dump.substring(start);
            // Global dump trailers must not be mistaken for fields of the final WindowState.
            Matcher trailer = Pattern.compile("(?m)^(?=\\S)").matcher(block);
            if (trailer.find()) block = block.substring(0, trailer.start());
            if (matchesSynthetic(header, block, uid)) { count++; selected = block; }
        }
        StringBuilder out = new StringBuilder("association=synthetic_title_uid_type_only candidateCount=").append(count);
        if (count != 1) return out.toString();
        appendField(out, selected, "display", "\\bmDisplayId=(" + NUMBER + ")\\b");
        appendField(out, selected, "ownerUid", "\\bmOwnerUid=(" + NUMBER + ")\\b");
        appendField(out, selected, "frame", "\\bmFrame=(" + RECT + ")");
        appendField(out, selected, "lastFrame", "\\bmFrame=" + RECT + "[\\t ]+last=(" + RECT + ")");
        appendField(out, selected, "touchableInsets", "\\bmTouchableInsets=(" + NUMBER + ")\\b");
        appendField(out, selected, "touchableRegion", "(?m)^[\\t ]*touchable region=(SkRegion\\([0-9,() \\-]{0,512}\\))[\\t ]*$");
        for (String name : new String[]{"mHasSurface", "isOnScreen", "isVisible", "mRelayoutCalled", "mLayoutNeeded", "mHaveFrame"})
            appendField(out, selected, name, "\\b" + name + "=(true|false)\\b");
        return out.toString();
    }

    private static boolean matchesSynthetic(String header, String block, int uid) {
        return header != null && header.matches("[\\t ]*Window #[0-9]+ Window\\{[0-9a-fA-F]+ u[0-9]+ Agent OS task controls\\}:[\\t ]*")
                && Pattern.compile("\\bmOwnerUid=" + uid + "\\b").matcher(block).find()
                && Pattern.compile("\\bty=(?:ACCESSIBILITY_OVERLAY|2032)\\b").matcher(block).find();
    }

    private static void appendField(StringBuilder out, String block, String name, String pattern) {
        Matcher values = Pattern.compile(pattern).matcher(block); int count = 0; String value = null;
        while (values.find()) { count++; if (count == 1) value = values.group(1); }
        out.append(' ').append(name).append("Count=").append(count).append(' ').append(name).append('=')
                .append(count == 0 ? "not_reported" : count == 1 ? value : "ambiguous");
    }

    private static void requireTime(long deadline) {
        if (SystemClock.elapsedRealtime() >= deadline) throw new DiagnosticDeadlineExpired();
    }

    private static final class DiagnosticDeadlineExpired extends RuntimeException { }
}
