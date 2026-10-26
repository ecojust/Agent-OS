package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real emulator evidence only. This helper is compiled into the test APK, never the product. */
public final class ScreenshotEvidence implements TestRule {
    private static final String RUN_ID = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    private static final JsonArray RUN_TESTS = new JsonArray();
    private final String nonVisualReason;
    private final JsonArray captures = new JsonArray();
    private Description test;
    private String prefix;
    private long startedAt;
    private Throwable evidenceFailure;

    public ScreenshotEvidence() { this(null); }
    public ScreenshotEvidence(String nonVisualReason) { this.nonVisualReason = nonVisualReason; }

    @Override public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                test = description;
                startedAt = System.currentTimeMillis();
                prefix = "api-" + Build.VERSION.SDK_INT + "-" + safe(description.getMethodName())
                        + "-" + UUID.randomUUID().toString().substring(0, 8);
                Throwable testFailure = null;
                try { base.evaluate(); }
                catch (Throwable failure) { testFailure = failure; }
                capture(testFailure == null ? "finished" : "failed");
                try { persist(testFailure); }
                catch (IOException failure) {
                    System.err.println("Screenshot evidence manifest write failed: " + failure.getClass().getSimpleName());
                    if (evidenceFailure == null) evidenceFailure = failure;
                }
                if (testFailure != null) {
                    if (evidenceFailure != null) testFailure.addSuppressed(evidenceFailure);
                    throw testFailure;
                }
                if (evidenceFailure != null) throw new AssertionError("Screenshot evidence could not be written", evidenceFailure);
            }
        };
    }

    public void capture(String stage) { capture(stage, false); }

    /** The only permitted secure-window exception is this verified empty, synthetic UI test. */
    public void captureEmptyMainActivity(Activity activity, String stage) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        SettingsStore.SavedSettings stored = new SettingsStore(activity).load();
        if (!stored.apiKey.isEmpty() || !stored.endpoint.isEmpty() || !stored.model.isEmpty()
                || !stored.allowedPackages.isEmpty()) {
            blocked(stage, "application_configuration_is_not_empty");
            return;
        }
        boolean[] safeUi = {false};
        instrumentation.runOnMainSync(() -> {
            AgentController.State state = AgentController.get(activity).getState();
            safeUi[0] = !state.running && state.log.isEmpty() && state.summary.isEmpty() && state.approval.isEmpty()
                    && empty(activity, R.id.settings_api_key) && empty(activity, R.id.settings_endpoint)
                    && empty(activity, R.id.settings_model) && empty(activity, R.id.task_prompt)
                    && (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        });
        if (!safeUi[0]) {
            blocked(stage, "application_window_is_not_a_verified_empty_test_screen");
            return;
        }
        try {
            instrumentation.runOnMainSync(() -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
            CountDownLatch drawn = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> activity.getWindow().getDecorView().postOnAnimation(() ->
                    activity.getWindow().getDecorView().postOnAnimation(drawn::countDown)));
            if (!drawn.await(3, TimeUnit.SECONDS)) {
                blocked(stage, "empty_test_window_did_not_draw");
                return;
            }
            capture(stage, true);
        } finally {
            instrumentation.runOnMainSync(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        }
    }

    private static boolean empty(Activity activity, int id) {
        TextView field = activity.findViewById(id);
        return field != null && field.getText().length() == 0;
    }

    private void capture(String stage, boolean verifiedEmptyApplication) {
        if (nonVisualReason != null) { blocked(stage, nonVisualReason); return; }
        JsonObject entry = entry(stage);
        Bitmap screenshot = null;
        try {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            if ((info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                automation.setServiceInfo(info);
            }
            String foreground = focusedPackage(automation);
            long deadline = SystemClock.elapsedRealtime() + 2_000;
            while (foreground.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(40);
                foreground = focusedPackage(automation);
            }
            entry.addProperty("foregroundPackage", foreground);
            if (!DeviceHarness.FIXTURE.equals(foreground)
                    && !(verifiedEmptyApplication && instrumentation.getTargetContext().getPackageName().equals(foreground))) {
                entry.addProperty("status", "blocked");
                entry.addProperty("captureError", "foreground_is_not_an_approved_synthetic_test_screen");
                return;
            }
            screenshot = automation.takeScreenshot();
            if (screenshot != null && screenshot.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap readable = screenshot.copy(Bitmap.Config.ARGB_8888, false);
                screenshot.recycle();
                screenshot = readable;
            }
            if (screenshot == null || !hasVisibleContent(screenshot)) {
                entry.addProperty("status", "blocked");
                entry.addProperty("captureError", "capture_unavailable_or_blank_secure_surface_not_evidence");
                return;
            }
            String name = prefix + "-" + safe(stage) + "-" + captures.size() + ".png";
            File image = new File(directory(), name);
            try (FileOutputStream output = new FileOutputStream(image)) {
                if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encoding failed");
                output.getFD().sync();
            }
            entry.addProperty("status", "pass");
            entry.addProperty("png", name);
            entry.addProperty("width", screenshot.getWidth());
            entry.addProperty("height", screenshot.getHeight());
            entry.addProperty("secureWindowTemporarilyCleared", verifiedEmptyApplication);
        } catch (Exception failure) {
            entry.addProperty("status", "fail");
            entry.addProperty("captureError", failure.getClass().getSimpleName());
            if (evidenceFailure == null) evidenceFailure = failure;
        } finally {
            if (screenshot != null) screenshot.recycle();
            captures.add(entry);
        }
    }

    private static String focusedPackage(UiAutomation automation) {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null) return "";
        String packageName = "";
        int count = 0;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                count++;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try { packageName = root.getPackageName() == null ? "" : root.getPackageName().toString(); }
                finally { root.recycle(); }
            }
            return count == 1 ? packageName : "";
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private static boolean hasVisibleContent(Bitmap bitmap) {
        if (bitmap.getWidth() < 64 || bitmap.getHeight() < 64) return false;
        Set<Integer> colors = new HashSet<>();
        for (int row = 2; row < 18; row++) {
            for (int column = 1; column < 19; column++) {
                colors.add(bitmap.getPixel(bitmap.getWidth() * column / 20, bitmap.getHeight() * row / 20));
            }
        }
        return colors.size() > 3;
    }

    private void blocked(String stage, String reason) {
        JsonObject entry = entry(stage);
        entry.addProperty("status", "blocked");
        entry.addProperty("captureError", reason);
        captures.add(entry);
    }

    private static JsonObject entry(String stage) {
        JsonObject entry = new JsonObject();
        entry.addProperty("stage", stage);
        entry.addProperty("capturedAt", System.currentTimeMillis());
        return entry;
    }

    private synchronized void persist(Throwable failure) throws IOException {
        JsonObject record = new JsonObject();
        record.addProperty("schemaVersion", 1);
        record.addProperty("api", Build.VERSION.SDK_INT);
        record.addProperty("testClass", test.getClassName());
        record.addProperty("testName", test.getMethodName());
        record.addProperty("assertionResult", failure == null ? "pass" : "fail");
        record.addProperty("testResult", failure != null || evidenceFailure != null ? "fail" : "pass");
        record.addProperty("status", failure != null || evidenceFailure != null ? "fail" : "pass");
        record.addProperty("startedAt", startedAt);
        record.addProperty("finishedAt", System.currentTimeMillis());
        if (failure != null) record.addProperty("failureClass", failure.getClass().getSimpleName());
        record.add("captures", captures.deepCopy());
        writeJson(new File(directory(), prefix + ".json"), record);
        synchronized (RUN_TESTS) {
            RUN_TESTS.add(record);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("schemaVersion", 1);
            manifest.addProperty("api", Build.VERSION.SDK_INT);
            manifest.addProperty("runId", RUN_ID);
            manifest.addProperty("scope", "Dedicated synthetic Android fixture and verified empty application UI only");
            manifest.addProperty("jvmTests", "No UI; no screenshot is claimed for JVM tests");
            manifest.add("tests", RUN_TESTS.deepCopy());
            writeJson(new File(directory(), "manifest.json"), manifest);
        }
    }

    private static File directory() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "screenshots");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Screenshot directory unavailable");
        return directory;
    }

    private static void writeJson(File destination, JsonObject value) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(new GsonBuilder().setPrettyPrinting().create().toJson(value).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(destination)) throw new IOException("Screenshot manifest rename failed");
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_-]", "_"); }
}
