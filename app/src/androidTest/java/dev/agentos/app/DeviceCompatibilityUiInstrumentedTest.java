package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.Build;
import android.view.View;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.inspector.WindowInspector;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DeviceCompatibilityUiInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void reportIsReadOnlyUntilTheUserCopiesAndCanBeClosed() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        AtomicBoolean copied = new AtomicBoolean();
        UiAutomation clipboardAutomation = null;
        int originalAutomationFlags = 0;
        Throwable primaryFailure = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                clipboardAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                AccessibilityServiceInfo info = clipboardAutomation.getServiceInfo();
                originalAutomationFlags = info.flags;
                // Configure discovery before copying. Clipboard containers can be omitted from
                // the important-only tree, and their window title is null on some system images.
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                clipboardAutomation.setServiceInfo(info);
            }
            await(instrumentation, () -> activity.findViewById(R.id.device_compatibility).isEnabled());
            assertFalse(AgentController.get(activity).getState().running);
            instrumentation.runOnMainSync(() -> {
                assertNotNull(clipboard);
                // An empty clipboard tests that opening diagnostics does not copy anything,
                // without creating a separate system preview for a test sentinel.
                clipboard.clearPrimaryClip();
                activity.findViewById(R.id.device_compatibility).performClick();
            });
            await(instrumentation, () -> {
                View root = reportWindow();
                return root != null && root.findViewById(R.id.copy_device_report).isEnabled();
            });
            AtomicReference<String> report = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                View root = reportWindow();
                assertNotNull(root);
                TextView contents = root.findViewById(R.id.device_report);
                report.set(contents.getText().toString());
                assertTrue(report.get().contains("本机真机验收：未验证"));
                assertTrue(report.get().contains("内存与运行模式"));
                assertFalse("Opening diagnostics must not copy automatically", clipboard.hasPrimaryClip());
                Button copy = root.findViewById(R.id.copy_device_report);
                Button close = root.findViewById(android.R.id.button2);
                assertFullyVisible(copy);
                assertFullyVisible(close);
            });
            // The helper only relaxes the secure flag if every configuration/task field is empty.
            // Otherwise it records a blocked capture and leaves all protection in place.
            screenshots.captureEmptyMainActivity(activity, "device-compatibility-report");
            instrumentation.runOnMainSync(() -> {
                View root = reportWindow();
                root.findViewById(R.id.copy_device_report).performClick();
                copied.set(true);
                assertEquals(report.get(), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                root.findViewById(android.R.id.button2).performClick();
            });
            await(instrumentation, () -> reportWindow() == null);
            assertFalse(AgentController.get(activity).getState().running);
        } catch (Exception | AssertionError failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                if (copied.get() && clipboardAutomation != null) dismissClipboardPreview(clipboardAutomation);
                instrumentation.runOnMainSync(() -> { if (clipboard != null) clipboard.clearPrimaryClip(); });
                screenshots.captureEmptyMainActivity(activity, "clipboard-preview-cleared");
            } catch (Exception | AssertionError cleanupFailure) {
                try { screenshots.captureEmptyMainActivity(activity, "clipboard-cleanup-failed"); }
                catch (Exception | AssertionError evidenceFailure) { cleanupFailure.addSuppressed(evidenceFailure); }
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            } finally {
                try {
                    if (clipboardAutomation != null) {
                        AccessibilityServiceInfo restore = clipboardAutomation.getServiceInfo();
                        restore.flags = originalAutomationFlags;
                        clipboardAutomation.setServiceInfo(restore);
                    }
                } finally { instrumentation.runOnMainSync(activity::finish); }
            }
        }
    }

    /** Test-only cleanup of the UI created by the real copy action; no production bypass. */
    private static void dismissClipboardPreview(UiAutomation automation) throws Exception {
        Set<Integer> clipboardWindows = new HashSet<>();
        AtomicBoolean dismissalRequested = new AtomicBoolean();
        boolean observed = false;
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean present = clipboardPreviewPresent(automation, clipboardWindows, dismissalRequested);
            if (present && !observed) {
                observed = true;
                deadline = SystemClock.elapsedRealtime() + 5_000;
            }
            // Absence before seeing the actual preview is inconclusive, not successful cleanup.
            // The platform may also time out the preview; its verified removal is what matters.
            if (observed && !present) return;
            try { automation.waitForIdle(100, Math.min(1_000, Math.max(100, deadline - SystemClock.elapsedRealtime()))); }
            catch (TimeoutException busy) { }
        }
        fail("Clipboard preview cleanup was not verified: observed=" + observed
                + ", dismissalRequested=" + dismissalRequested.get() + ", knownWindows=" + clipboardWindows
                + "; " + systemWindowSummary(automation));
    }

    private static boolean clipboardPreviewPresent(UiAutomation automation, Set<Integer> knownWindows, AtomicBoolean dismissalRequested) {
        // UiAutomation's cached tree can predate the clipboard overlay and its dismiss button.
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache();
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        assertNotNull("Cannot verify clipboard preview cleanup without system windows", windows);
        boolean present = false;
        try {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    if (root != null) root.refresh();
                    boolean clipboard = knownWindows.contains(window.getId())
                            || "ClipboardOverlay".contentEquals(window.getTitle() == null ? "" : window.getTitle());
                    if (!clipboard && root != null && "com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                        clipboard = hasNode(root, "clipboard_ui") || hasNode(root, "clipboard_preview")
                                || hasNode(root, "text_preview") || hasNode(root, "minimized_preview");
                    }
                    if (!clipboard) continue;
                    present = true;
                    knownWindows.add(window.getId());
                    if (!dismissalRequested.get() && root != null) {
                        List<AccessibilityNodeInfo> controls = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/dismiss_button");
                        try {
                            for (AccessibilityNodeInfo close : controls) {
                                if (close.isVisibleToUser() && close.isEnabled()) {
                                    dismissalRequested.set(close.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                                    break;
                                }
                            }
                        } finally { for (AccessibilityNodeInfo close : controls) close.recycle(); }
                        if (!dismissalRequested.get()) {
                            dismissalRequested.set(dismissPreviewNode(automation, root));
                        }
                    }
                } finally { if (root != null) root.recycle(); }
            }
            return present;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static boolean dismissPreviewNode(UiAutomation automation, AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> previews = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/clipboard_preview");
        try {
            for (AccessibilityNodeInfo preview : previews) {
                if (!preview.isVisibleToUser() || !preview.isEnabled()) continue;
                if (preview.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
                        && preview.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) return true;
                // Newer system versions omit the close button. Swipe away the actual, identified
                // preview as a user would; never send a gesture before its node is observable.
                Rect bounds = new Rect();
                preview.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                float startX = bounds.exactCenterX();
                float y = bounds.exactCenterY();
                int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
                float endX = startX < screenWidth / 2f ? screenWidth - 2f : 2f;
                if (Math.abs(endX - startX) < bounds.width()) continue;
                return swipe(automation, startX, endX, y);
            }
        } finally { for (AccessibilityNodeInfo preview : previews) preview.recycle(); }
        return false;
    }

    private static boolean swipe(UiAutomation automation, float startX, float endX, float y) {
        long downTime = SystemClock.uptimeMillis();
        boolean complete = false;
        try {
            if (!touch(automation, downTime, MotionEvent.ACTION_DOWN, startX, y)) return false;
            for (int step = 1; step <= 12; step++) {
                // This is the gesture's input cadence, not a blind wait for window dismissal.
                SystemClock.sleep(12);
                if (!touch(automation, downTime, MotionEvent.ACTION_MOVE,
                        startX + (endX - startX) * step / 12f, y)) return false;
            }
            complete = touch(automation, downTime, MotionEvent.ACTION_UP, endX, y);
            return complete;
        } finally {
            if (!complete) touch(automation, downTime, MotionEvent.ACTION_CANCEL, startX, y);
        }
    }

    private static boolean touch(UiAutomation automation, long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { return automation.injectInputEvent(event, true); }
        finally { event.recycle(); }
    }

    private static String systemWindowSummary(UiAutomation automation) {
        StringBuilder summary = new StringBuilder();
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    if (root == null || !"com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                    summary.append("systemWindow(id=").append(window.getId()).append(", title=")
                            .append(window.getTitle()).append(", ids=");
                    appendNodeIds(root, summary, new int[] {64});
                    summary.append(") ");
                } finally { if (root != null) root.recycle(); }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        return summary.toString();
    }

    private static void appendNodeIds(AccessibilityNodeInfo node, StringBuilder summary, int[] remaining) {
        if (remaining[0]-- <= 0) return;
        if (node.getViewIdResourceName() != null) summary.append(node.getViewIdResourceName()).append(' ');
        for (int index = 0; index < node.getChildCount() && remaining[0] > 0; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { appendNodeIds(child, summary, remaining); }
            finally { child.recycle(); }
        }
    }

    private static boolean hasNode(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/" + id);
        try { return !nodes.isEmpty(); }
        finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
    }

    private static void assertFullyVisible(View button) {
        assertNotNull(button);
        assertTrue(button.isShown());
        Rect visible = new Rect();
        assertTrue(button.getGlobalVisibleRect(visible));
        assertEquals(button.getHeight(), visible.height());
        assertEquals(button.getWidth(), visible.width());
        assertTrue(button.getHeight() >= Math.round(48 * button.getResources().getDisplayMetrics().density));
    }

    private static View reportWindow() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (root.findViewById(R.id.device_report) != null) return root;
        }
        return null;
    }

    private interface Condition { boolean ready(); }

    private static void await(Instrumentation instrumentation, Condition condition) {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        AtomicBoolean matched = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> matched.set(condition.ready()));
            if (matched.get()) return;
            SystemClock.sleep(30);
        }
        fail("Timed out waiting for device diagnostic UI");
    }
}
