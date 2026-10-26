package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.Build;
import android.view.View;
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
        Throwable primaryFailure = null;
        try {
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
                if (copied.get()) dismissClipboardPreview(instrumentation);
                instrumentation.runOnMainSync(() -> { if (clipboard != null) clipboard.clearPrimaryClip(); });
                screenshots.captureEmptyMainActivity(activity, "clipboard-preview-cleared");
            } catch (Exception | AssertionError cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            } finally {
                instrumentation.runOnMainSync(activity::finish);
            }
        }
    }

    /** Test-only cleanup of the UI created by the real copy action; no production bypass. */
    private static void dismissClipboardPreview(Instrumentation instrumentation) throws Exception {
        if (Build.VERSION.SDK_INT < 33) return;
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        Set<Integer> clipboardWindows = new HashSet<>();
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        try {
            // Let the copy's window/content events settle before considering the window absent.
            // This waits for UI events, not an assumed duration for the preview timeout.
            try { automation.waitForIdle(100, 1_000); } catch (TimeoutException busy) { }
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!clipboardPreviewPresent(automation, clipboardWindows, true)) return;
                try { automation.waitForIdle(100, Math.min(1_000, Math.max(100, deadline - SystemClock.elapsedRealtime()))); }
                catch (TimeoutException busy) { }
            }
            assertFalse("The copy test left a system clipboard preview window over later fixtures",
                    clipboardPreviewPresent(automation, clipboardWindows, false));
        } finally {
            AccessibilityServiceInfo restore = automation.getServiceInfo();
            restore.flags = originalFlags;
            automation.setServiceInfo(restore);
        }
    }

    private static boolean clipboardPreviewPresent(UiAutomation automation, Set<Integer> knownWindows, boolean dismiss) {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        assertNotNull("Cannot verify clipboard preview cleanup without system windows", windows);
        boolean present = false;
        try {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    boolean clipboard = knownWindows.contains(window.getId())
                            || "ClipboardOverlay".contentEquals(window.getTitle() == null ? "" : window.getTitle());
                    if (!clipboard && root != null && "com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                        clipboard = hasNode(root, "clipboard_ui") || hasNode(root, "clipboard_preview");
                    }
                    if (!clipboard) continue;
                    present = true;
                    knownWindows.add(window.getId());
                    if (dismiss && root != null) {
                        List<AccessibilityNodeInfo> controls = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/dismiss_button");
                        try {
                            for (AccessibilityNodeInfo close : controls) {
                                if (close.isVisibleToUser() && close.isEnabled()) {
                                    close.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    break;
                                }
                            }
                        } finally { for (AccessibilityNodeInfo close : controls) close.recycle(); }
                    }
                } finally { if (root != null) root.recycle(); }
            }
            return present;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
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
