package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DeviceCompatibilityUiInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void reportIsReadOnlyUntilTheUserCopiesAndCanBeClosed() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        try {
            await(instrumentation, () -> activity.findViewById(R.id.device_compatibility).isEnabled());
            assertFalse(AgentController.get(activity).getState().running);
            instrumentation.runOnMainSync(() -> {
                assertNotNull(clipboard);
                clipboard.setPrimaryClip(ClipData.newPlainText("diagnostic-test", "report-not-copied"));
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
                assertEquals("report-not-copied", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
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
                assertEquals(report.get(), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                root.findViewById(android.R.id.button2).performClick();
            });
            await(instrumentation, () -> reportWindow() == null);
            assertFalse(AgentController.get(activity).getState().running);
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (clipboard != null) clipboard.clearPrimaryClip();
                activity.finish();
            });
        }
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
