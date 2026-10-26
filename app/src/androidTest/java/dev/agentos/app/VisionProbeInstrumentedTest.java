package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Real native configuration UI and loopback HTTP, with generated pixels and synthetic credentials. */
@RunWith(AndroidJUnit4.class)
public final class VisionProbeInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_model_configuration_capture_not_attempted");

    @Test public void syntheticImageAndToolResultsRemainIndependentAndEachClickSendsOneRequest() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             ProbeUi ui = new ProbeUi(server)) {
            ui.beginVision();
            assertFalse("HTTP must wait for explicit trust", server.awaitRequests(1, 250));
            ui.acceptHttpTrust();
            String imageResult = ui.awaitResult(text -> text.contains("图像能力：本次合成图片已验证"));
            assertTrue(imageResult.contains("网络连接：已连接"));
            assertFalse(imageResult.contains("工具调用：已验证"));
            assertEquals(1, server.imageRequestCount()); assertEquals(0, server.toolRequestCount());
            assertFalse("A vision click must not automatically run the tool probe", server.awaitRequests(2, 250));
            ui.click(R.id.test_model);
            String combined = ui.awaitResult(text -> text.contains("工具调用：已验证"));
            assertTrue(combined.contains("图像能力：本次合成图片已验证"));
            assertEquals(2, server.requestCount()); assertEquals(1, server.toolRequestCount());
            ui.beginVision();
            ui.awaitResult(text -> text.contains("图像能力：本次合成图片已验证")
                    && text.contains("工具调用：已验证") && !ui.isProbing());
            assertEquals(3, server.requestCount()); assertEquals(2, server.imageRequestCount());
            assertFalse(server.awaitRequests(4, 250));
            ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void validResponseWithoutVerifiedImageAnswerDoesNotAdvertiseVisualOrToolSuccess() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.UNVERIFIED);
             ProbeUi ui = new ProbeUi(server)) {
            ui.beginVision(); ui.acceptHttpTrust();
            String result = ui.awaitResult(text -> text.contains("图像能力：本次合成图片未验证") && !ui.isProbing());
            assertTrue(result.contains("网络连接：已连接"));
            assertFalse(result.contains("工具调用：已验证"));
            assertFalse(result.contains("图像能力：本次合成图片已验证"));
            assertEquals(1, server.requestCount()); assertEquals(1, server.imageRequestCount());
            ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void cancelKeepsControlsResponsiveAndDiscardsTheDelayedImageResult() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HELD_VERIFIED);
             ProbeUi ui = new ProbeUi(server)) {
            ui.beginVision(); ui.acceptHttpTrust(); assertTrue(server.awaitRequests(1, 10_000));
            assertTrue(ui.isProbing());
            ui.instrumentation.runOnMainSync(() -> {
                assertFalse(ui.button(R.id.test_model).isEnabled());
                assertFalse(ui.button(R.id.test_model_vision).isEnabled());
                // Even a stale queued click must not start a second kind of probe.
                ui.button(R.id.test_model).performClick();
                ui.button(R.id.cancel_model_test).performClick();
            });
            String cancelled = ui.awaitResult(text -> text.contains("取消") && !ui.isProbing());
            server.releaseResponse(); assertTrue(server.awaitResponseAttempt());
            ui.assertResultRemains(cancelled);
            assertEquals(1, server.requestCount()); assertEquals(0, server.toolRequestCount());
            ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void editingConfigurationClearsAllValidationAndRejectsTheOldPendingResult() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HELD_VERIFIED);
             ProbeUi ui = new ProbeUi(server)) {
            ui.beginVision(); ui.acceptHttpTrust(); assertTrue(server.awaitRequests(1, 10_000));
            ui.setText(R.id.settings_model, LocalVisionProbeServer.MODEL + "-changed");
            String changed = ui.awaitResult(text -> text.contains("配置已更改") && !ui.isProbing());
            assertCleared(changed);
            server.releaseResponse(); assertTrue(server.awaitResponseAttempt());
            ui.assertResultRemains(changed);
            assertEquals(1, server.requestCount()); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void anyConnectionFieldEditClearsPreviouslyVerifiedToolAndImageResults() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             ProbeUi ui = new ProbeUi(server)) {
            int[] fields = {R.id.settings_endpoint, R.id.settings_model, R.id.settings_api_key};
            String[] values = {server.endpoint(), LocalVisionProbeServer.MODEL, LocalVisionProbeServer.KEY};
            for (int index = 0; index < fields.length; index++) {
                ui.beginVision();
                if (index == 0) ui.acceptHttpTrust();
                ui.awaitResult(text -> text.contains("图像能力：本次合成图片已验证") && !ui.isProbing());
                ui.click(R.id.test_model);
                ui.awaitResult(text -> text.contains("工具调用：已验证") && !ui.isProbing());
                ui.setText(fields[index], values[index] + "-edited");
                String reset = ui.result(); assertTrue(reset.contains("配置已更改")); assertCleared(reset);
                ui.setText(fields[index], values[index]);
            }
            assertEquals(6, server.requestCount()); ui.assertSecureAndIdle(); server.assertHealthy();
        }
    }

    @Test public void changedEndpointCannotReuseAnOldHttpTrustPrompt() throws Exception {
        try (LocalVisionProbeServer original = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             LocalVisionProbeServer replacement = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             ProbeUi ui = new ProbeUi(original)) {
            ui.beginVision();
            AccessibilityNodeInfo oldConfirm = ui.findTrustButton();
            try {
                assertFalse(original.awaitRequests(1, 150));
                ui.setText(R.id.settings_endpoint, replacement.endpoint());
                oldConfirm.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                assertFalse(original.awaitRequests(1, 150));
                assertFalse(replacement.awaitRequests(1, 150));
                ui.beginVision();
                assertFalse(replacement.awaitRequests(1, 150));
                ui.acceptHttpTrust();
                ui.awaitResult(text -> text.contains("图像能力：本次合成图片已验证") && !ui.isProbing());
                assertEquals(0, original.requestCount()); assertEquals(1, replacement.requestCount());
            } finally { oldConfirm.recycle(); }
            ui.assertSecureAndIdle(); original.assertHealthy(); replacement.assertHealthy();
        }
    }

    @Test public void leavingTheActivityCancelsItsProbeAndNeverAppliesALateSuccess() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HELD_VERIFIED);
             ProbeUi ui = new ProbeUi(server)) {
            ui.beginVision(); ui.acceptHttpTrust(); assertTrue(server.awaitRequests(1, 10_000));
            ui.instrumentation.runOnMainSync(ui.activity::finish);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!ui.activity.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            assertTrue(ui.activity.isDestroyed());
            String stopped = ui.result();
            server.releaseResponse(); assertTrue(server.awaitResponseAttempt());
            ui.assertResultRemains(stopped);
            assertFalse(stopped.contains("图像能力：本次合成图片已验证"));
            assertEquals(1, server.requestCount()); server.assertHealthy();
        }
    }

    private static void assertCleared(String text) {
        assertFalse(text.contains("网络连接：已连接"));
        assertFalse(text.contains("工具调用：已验证"));
        assertFalse(text.contains("图像能力：本次合成图片已验证"));
    }

    private static final class ProbeUi implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final SharedPreferences preferences;
        final Map<String, ?> original;
        final Activity activity;

        ProbeUi(LocalVisionProbeServer server) throws Exception {
            assertFalse("Capability UI test must not interrupt a task", AgentController.get(context).getState().running);
            String profile = SettingsStore.currentProfileId(context);
            preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            original = preferences.getAll();
            assertTrue(preferences.edit().clear().commit());
            Activity opened = null;
            try {
                SettingsStore store = new SettingsStore(context);
                SettingsStore.SavedSettings saved = store.load();
                saved.endpoint = server.endpoint(); saved.model = LocalVisionProbeServer.MODEL;
                saved.apiKey = LocalVisionProbeServer.KEY; store.save(saved);
                Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                opened = instrumentation.startActivitySync(intent);
                activity = opened;
                awaitReady(server.endpoint());
                instrumentation.runOnMainSync(() -> {
                    View vision = activity.findViewById(R.id.test_model_vision);
                    if (!vision.isShown()) button(R.id.configuration_toggle).performClick();
                    ((EditText) activity.findViewById(R.id.task_prompt)).setText(LocalVisionProbeServer.PRIVATE_TASK);
                    assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                });
            } catch (Throwable failure) {
                Activity failed = opened;
                if (failed != null) instrumentation.runOnMainSync(failed::finish);
                restore(); throw failure;
            }
        }

        private void awaitReady(String endpoint) {
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            while (!ready.get() && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync(() -> ready.set(button(R.id.test_model_vision).isEnabled()
                        && endpoint.equals(((EditText) activity.findViewById(R.id.settings_endpoint)).getText().toString())));
                if (!ready.get()) SystemClock.sleep(25);
            }
            assertTrue("Synthetic model configuration did not become ready", ready.get());
        }

        Button button(int id) { return activity.findViewById(id); }
        void beginVision() { click(R.id.test_model_vision); }
        void click(int id) {
            instrumentation.runOnMainSync(() -> {
                Button button = button(id); assertTrue("Button must be usable", button.isEnabled());
                button.requestRectangleOnScreen(new Rect(0, 0, Math.max(1, button.getWidth()), Math.max(1, button.getHeight())), true);
                button.performClick();
            });
        }
        void setText(int id, String value) {
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(id)).setText(value));
        }
        String result() {
            AtomicReference<String> result = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> result.set(((TextView) activity.findViewById(R.id.model_test_result)).getText().toString()));
            return result.get();
        }
        boolean isProbing() {
            AtomicReference<Boolean> probing = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> probing.set(button(R.id.cancel_model_test).getVisibility() == View.VISIBLE));
            return probing.get();
        }
        String awaitResult(Predicate<String> expected) {
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            String value = result();
            while (!expected.test(value) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25); value = result();
            }
            assertTrue("Unexpected synthetic probe status: " + value, expected.test(value));
            return value;
        }
        void assertResultRemains(String expected) {
            long deadline = SystemClock.elapsedRealtime() + 500;
            do {
                instrumentation.waitForIdleSync(); assertEquals(expected, result()); SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtime() < deadline);
        }
        void acceptHttpTrust() {
            AccessibilityNodeInfo button = findTrustButton();
            try { assertTrue("Could not confirm the actual HTTP dialog", button.performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
            finally { button.recycle(); }
        }
        AccessibilityNodeInfo findTrustButton() {
            UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root != null) {
                    try {
                        if (context.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                            List<AccessibilityNodeInfo> candidates = root.findAccessibilityNodeInfosByText("信任此连接");
                            AccessibilityNodeInfo selected = null;
                            for (AccessibilityNodeInfo node : candidates) {
                                if (selected == null && "信任此连接".contentEquals(node.getText() == null ? "" : node.getText())
                                        && node.isEnabled() && node.isClickable()) selected = AccessibilityNodeInfo.obtain(node);
                                node.recycle();
                            }
                            if (selected != null) return selected;
                        }
                    } finally { root.recycle(); }
                }
                SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("Expected the explicit local HTTP trust dialog");
        }
        void assertSecureAndIdle() {
            instrumentation.runOnMainSync(() -> {
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertFalse(AgentController.get(context).getState().running);
                assertTrue(button(R.id.test_model).isEnabled()); assertTrue(button(R.id.test_model_vision).isEnabled());
            });
        }
        @Override public void close() throws Exception {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync(); restore();
        }
        private void restore() throws IOException {
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Set<?>) {
                    @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                    editor.putStringSet(entry.getKey(), values);
                }
            }
            if (!editor.commit()) throw new IOException("Could not restore configuration after synthetic probe UI test");
        }
    }
}
