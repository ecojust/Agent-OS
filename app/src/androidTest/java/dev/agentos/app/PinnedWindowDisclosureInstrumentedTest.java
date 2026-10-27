package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Dedicated permission variant only: actual production service, Controller, secure review and HTTP. */
@RunWith(AndroidJUnit4.class)
public final class PinnedWindowDisclosureInstrumentedTest {
    @Rule public final TestName name = new TestName();
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void approvedActualWindowIsReviewedAndSentOnce() throws Exception { run(false); }
    @Test public void declinedActualWindowNeverReachesModel() throws Exception { run(true); }

    private void run(boolean decline) throws Exception {
        Environment env = new Environment(name.getMethodName(), decline);
        Throwable failure = null;
        try { env.prepare(); env.exercise(); }
        catch (Exception | Error error) { failure = error; }
        finally {
            try { env.close(); } catch (Exception | Error cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            try { env.finish(failure); } catch (Exception | Error evidence) { if (failure == null) failure = evidence; else failure.addSuppressed(evidence); }
        }
        rethrow(failure);
    }

    private final class Environment implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final AgentController controller = AgentController.get(context);
        final Bundle args = InstrumentationRegistry.getArguments();
        final String caseName;
        final boolean decline;
        final File directory, payloadFile;
        final JsonObject report = new JsonObject();
        DeviceHarness device;
        ControllerTestSettings backup;
        LocalVisionProbeServer server;
        NativeWindowTaskScript script;
        SettingsStore.ConfigurationLease lease;
        ModelSettings model;
        RunControl owner;
        long epoch;
        SecureImageReviewOverlay host;
        ImageReviewSession session;
        AgentAccessibilityService.OwnedReviewWindowIdentity reviewIdentity;
        CompletableFuture<Void> removed;
        boolean closed;

        Environment(String name, boolean decline) throws Exception {
            caseName = name; this.decline = decline;
            assertEquals("Dedicated native disclosure workflow required", "agent-os-native-disclosure-v1", args.getString("nativeDisclosureMarker"));
            assertEquals("Verified fresh app installation required", "agent-os-native-disclosure-fresh-v1", args.getString("freshInstallMarker"));
            assertTrue(Build.VERSION.SDK_INT >= 34 && Build.VERSION.SDK_INT <= 37);
            assertEquals(Integer.toString(Build.VERSION.SDK_INT), args.getString("expectedApi"));
            assertTrue(args.getString("sourceSha", "").matches("[0-9a-f]{40}"));
            assertTrue(args.getString("runId", "").matches("[1-9][0-9]{0,18}"));
            assertTrue(args.getString("runAttempt", "").matches("[1-9][0-9]{0,8}"));
            assertTrue(args.getString("deviceSerial", "").matches("emulator-[0-9]+"));
            directory = new File(context.getFilesDir(), "pinned-window-disclosure");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Evidence directory unavailable");
            payloadFile = new File(directory, caseName + "-received.png");
            assertFalse("Never overwrite an earlier native payload", payloadFile.exists());
            report.addProperty("schemaVersion", 1); report.addProperty("kind", "pinned-window-disclosure");
            report.addProperty("testClass", PinnedWindowDisclosureInstrumentedTest.class.getName()); report.addProperty("testName", name);
            report.addProperty("api", Build.VERSION.SDK_INT); report.addProperty("sourceSha", args.getString("sourceSha"));
            report.addProperty("runId", args.getString("runId")); report.addProperty("runAttempt", args.getString("runAttempt"));
            report.addProperty("deviceSerial", args.getString("deviceSerial")); report.addProperty("startedAt", System.currentTimeMillis());
            report.addProperty("status", "unverified");
            report.addProperty("scope", "Dedicated visualAcceptance APK; actual pinned Calculator native window, protected review and loopback HTTP only");
            persist();
        }

        void prepare() throws Exception {
            assertFalse("Do not replace another task", controller.getState().running);
            await("Earlier image owners did not drain", () -> ImageWorkset.process().reservedBytes() == 0
                    && !WindowBufferCapturer.hasActiveCapture() && !ImagePreviewLoader.isWorkPending()
                    && !ImageReviewPresentationCoordinator.hasHost(), 10_000);
            main(controller::clearCompletedTask);
            settleWorker();
            device = new DeviceHarness();
            main(() -> {
                AccessibilityServiceInfo info = device.service.getServiceInfo(); assertNotNull(info);
                assertTrue("Acceptance APK lacks actual platform capability", (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0);
                assertTrue(device.service.hasWindowCaptureCapability());
                assertEquals(0, info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES);
            });
            report.addProperty("captureCapabilityPresent", true); report.addProperty("gestureCapabilityAbsent", true);
            script = new NativeWindowTaskScript(decline, payloadFile);
            server = LocalVisionProbeServer.forNativeWindowDisclosure(script);
            model = new ModelSettings(server.endpoint(), LocalVisionProbeServer.KEY, LocalVisionProbeServer.MODEL, 1024);
            backup = new ControllerTestSettings(context, model, ActionConfirmationPolicy.Mode.CONFIRM_ALL);
            SettingsStore store = new SettingsStore(context); SettingsStore.SavedSettings saved = store.load();
            saved.allowedPackages.clear(); saved.grants.clear();
            SettingsStore.AppGrant grant = SettingsStore.captureGrant(context, NativeWindowTaskScript.PACKAGE);
            saved.allowedPackages.add(NativeWindowTaskScript.PACKAGE); saved.grants.put(NativeWindowTaskScript.PACKAGE, grant);
            lease = store.save(saved);
            // Independent test preflight hashes the actual installed APK. The
            // Controller subsequently creates its own original per-task install.
            assertNotNull(SupportedVisualPagePolicy.verifyInstalled(context, grant, device.control,
                    device.control.getExecutionEpoch(), lease));
            report.addProperty("installedArtifactVerified", true);
            Intent launcher = context.getPackageManager().getLaunchIntentForPackage(NativeWindowTaskScript.PACKAGE);
            assertNotNull(launcher); assertNotNull(launcher.getComponent());
            assertEquals(NativeWindowTaskScript.PACKAGE, launcher.getComponent().getPackageName());
            context.startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            device.awaitUiIdle();
            await("Actual Calculator did not reach its ordinary full window", this::calculatorVisible, 8_000);
            // Original exported launcher and installed page only: no synthetic
            // screenshot backend or fixture page is substituted for this target.
            main(() -> {
                controller.start(model, LocalVisionProbeServer.PRIVATE_TASK + ": request one reviewed image of this Calculator, then observe once.",
                        Collections.singleton(NativeWindowTaskScript.PACKAGE), ActionConfirmationPolicy.Mode.CONFIRM_ALL,
                        TaskBudget.DEFAULT, lease, true);
                try { owner = (RunControl) field(controller, "control"); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertNotNull(owner); epoch = owner.getExecutionEpoch();
            });
            persist();
        }

        boolean calculatorVisible() throws Exception {
            List<AccessibilityWindowInfo> windows = AndroidDeviceTools.freshWindows(device.service, () -> { });
            try {
                AccessibilityWindowInfo target = null;
                for (AccessibilityWindowInfo window : windows) if (window.isFocused()) {
                    if (target != null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || window.getDisplayId() != 0) return false;
                    target = window;
                }
                if (target == null) return false;
                AccessibilityNodeInfo root = target.getRoot(); if (root == null) return false;
                try {
                    if (!NativeWindowTaskScript.PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
                    Rect bounds = new Rect(); target.getBoundsInScreen(bounds);
                    // Fixed small emulator source, never a forged permit or a
                    // transient display resize to bypass raw-buffer admission.
                    if (bounds.width() != 320 || bounds.height() != 640) return false;
                    List<AccessibilityNodeInfo> equals = root.findAccessibilityNodeInfosByViewId(NativeWindowTaskScript.PACKAGE + ":id/btn_equals");
                    try { return equals != null && equals.size() == 1 && equals.get(0).isVisibleToUser(); }
                    finally { if (equals != null) for (AccessibilityNodeInfo node : equals) node.recycle(); }
                } finally { root.recycle(); }
            } finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
        }

        void exercise() throws Exception {
            await("Actual platform capture did not produce a presented review", () -> {
                server.assertHealthy(); AtomicBoolean ready = new AtomicBoolean();
                main(() -> {
                    try {
                        SecureImageReviewOverlay current = (SecureImageReviewOverlay) field(device.service, "reviewHost");
                        if (current != null) {
                            if (host != null) assertSame("A replacement review is not the original image", host, current);
                            host = current; session = (ImageReviewSession) field(host, "session");
                            ImageReviewView content = (ImageReviewView) ((ViewGroup) host.rootView()).getChildAt(0);
                            ready.set(session.state() == ImageReviewSession.State.PRESENTED && host.rootView().isAttachedToWindow()
                                    && !host.rootView().hasWindowFocus() && content.isReadyToPresent() && button(ImageReviewView.APPROVE).isEnabled());
                        }
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                if (!ready.get() && !controller.getState().running) throw new AssertionError("Controller ended before review: " + controller.getState().summary);
                if (host != null && host.isEnded() && !ready.get()) throw new AssertionError("Original review ended before presentation: " + host.diagnosticSummary());
                return ready.get();
            }, 20_000);
            assertEquals(AgentController.VisualQualification.SYNTHETIC_VERIFIED, controller.getState().visualQualification);
            assertEquals(1, server.imageRequestCount()); assertEquals(0, server.toolRequestCount());
            assertEquals(1, server.taskRequestCount()); assertEquals(0, script.deviceImages.get());
            assertTrue(SettingsStore.isCurrent(lease)); owner.checkExecutionEpoch(epoch);
            assertEquals(NativeWindowTaskScript.CAPTURE_CALL, session.binding.callId);
            assertEquals(owner.getTaskId(), session.binding.observation.taskId); assertEquals(epoch, session.binding.observation.executionEpoch);
            assertEquals(lease.configurationToken, session.binding.configurationToken);
            assertEquals(NativeWindowTaskScript.PACKAGE, session.binding.observation.packageName);
            assertEquals(model.completionUrl().toExternalForm(), session.binding.recipient.endpoint); assertEquals(model.model, session.binding.recipient.model);
            assertEquals(script.originalRevision, session.binding.observation.revision);
            assertEquals(script.originalWindowId, session.binding.observation.windowId);
            CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> pending = device.service.acquireOwnedReviewWindowIdentity(host);
            try { reviewIdentity = pending.get(5, TimeUnit.SECONDS); } finally { pending.cancel(false); }
            assertTrue(device.service.isOwnedReviewWindowCurrent(reviewIdentity));
            assertReviewWindows(true);
            removed = host.whenRemoved(); assertFalse(removed.isDone());
            main(() -> {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) host.rootView().getLayoutParams();
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type);
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
                TextView recipient = host.rootView().findViewWithTag(ImageReviewView.RECIPIENT);
                assertTrue(recipient.getText().toString().contains(model.endpoint)); assertTrue(recipient.getText().toString().contains(model.model));
                ImageView image = host.rootView().findViewWithTag(ImageReviewView.PREVIEW);
                assertTrue(image.getDrawable() instanceof BitmapDrawable);
                Bitmap preview = ((BitmapDrawable) image.getDrawable()).getBitmap();
                assertFalse(preview.isRecycled()); assertEquals(session.width, preview.getWidth()); assertEquals(session.height, preview.getHeight());
                script.reviewed.set(new NativeWindowTaskScript.Reviewed(session, NativeWindowTaskScript.samples(preview), removed));
            });
            report.addProperty("reviewDisplayed", true); report.addProperty("nativeOrigin", "platform_window");
            report.addProperty("reviewedSha256", session.imageSha256); report.addProperty("width", session.width); report.addProperty("height", session.height);
            screenshots.capture("actual-native-window-review-protected-not-bypassed");
            persist();
            main(() -> {
                String action = decline ? ImageReviewView.REJECT : ImageReviewView.APPROVE;
                assertTrue(button(action).isEnabled()); assertTrue(button(action).performClick());
                if (decline) { assertEquals(ImageReviewSession.State.REJECTED, session.state()); assertEquals(ImageReviewSession.Reason.REJECTED, session.reason()); }
            });
            report.addProperty("reviewRejected", decline);
            await("Original Controller did not finish", () -> { server.assertHealthy(); return !controller.getState().running; }, 15_000);
            removed.get(5, TimeUnit.SECONDS);
            main(() -> assertFalse(host.rootView().isAttachedToWindow()));
            await("The original review remained in the actual window table", () -> !reviewWindowPublished(), 5_000);
            report.addProperty("actualReviewRemoved", true);
            if (decline) {
                assertTrue(controller.getState().summary, controller.getState().summary.contains("你已拒绝"));
                assertEquals(0, script.deviceImages.get()); assertEquals(1, server.taskRequestCount()); assertFalse(payloadFile.exists());
            } else {
                assertTrue(controller.getState().summary, controller.getState().summary.contains(NativeWindowTaskScript.FINAL_REPORT));
                assertEquals(1, script.deviceImages.get()); assertEquals(1, script.imageFreeFollowingRequests.get()); assertEquals(3, server.taskRequestCount());
                assertEquals(session.imageSha256, script.receivedHash); assertTrue(payloadFile.isFile());
                report.addProperty("receivedSha256", script.receivedHash); report.addProperty("image", payloadFile.getName());
                report.addProperty("imageSha256", script.receivedHash); report.addProperty("imageBytes", script.receivedBytes);
            }
            assertEquals(1, server.imageRequestCount());
            for (int limit : server.receivedMaxTokens()) assertEquals(1024, limit);
            assertEquals(1 + server.taskRequestCount(), server.requestCount());
            report.addProperty("syntheticProbeImageCount", server.imageRequestCount()); report.addProperty("taskImageCount", script.deviceImages.get());
            report.addProperty("taskHttpRequests", server.taskRequestCount()); report.addProperty("afterImageRequestWithoutImageCount", script.imageFreeFollowingRequests.get());
            persist();
        }

        Button button(String tag) { return host.rootView().findViewWithTag(tag); }
        void assertReviewWindows(boolean requirePresent) throws Exception {
            List<AccessibilityWindowInfo> windows = AndroidDeviceTools.freshWindows(device.service, () -> owner.checkExecutionEpoch(epoch));
            int actualReview = 0, focused = 0;
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.isFocused()) { focused++; assertEquals(script.originalWindowId, window.getId()); assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, window.getType()); }
                    if (window.getId() == reviewIdentity.windowId) {
                        actualReview++; assertEquals(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, window.getType()); assertFalse(window.isFocused());
                        assertEquals(reviewIdentity.displayId, window.getDisplayId()); Rect bounds = new Rect(); window.getBoundsInScreen(bounds); assertEquals(reviewIdentity.copyBounds(), bounds);
                    }
                }
            } finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
            assertEquals(1, focused); assertEquals(requirePresent ? 1 : 0, actualReview);
        }
        boolean reviewWindowPublished() throws Exception {
            List<AccessibilityWindowInfo> windows = AndroidDeviceTools.freshWindows(device.service, () -> { });
            try { for (AccessibilityWindowInfo window : windows) if (window.getId() == reviewIdentity.windowId) return true; return false; }
            finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
        }
        void main(Runnable action) throws Exception {
            Throwable[] failure = {null};
            instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Exception | Error error) { failure[0] = error; } });
            rethrow(failure[0]);
        }
        private void settleWorker() throws Exception {
            ThreadPoolExecutor worker = (ThreadPoolExecutor) field(controller, "worker");
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            Future<?> barrier = null;
            try {
                while (barrier == null) {
                    try { barrier = worker.submit(() -> { }); }
                    catch (RejectedExecutionException rejected) {
                        if (worker.isShutdown() || SystemClock.elapsedRealtime() >= deadline) throw rejected;
                        // Retry only an explicitly unaccepted, side-effect-free no-op.
                        // Once accepted, wait on that same Future without requeueing.
                        SystemClock.sleep(25);
                    }
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new TimeoutException("Actual Controller worker did not drain");
                barrier.get(remaining, TimeUnit.MILLISECONDS);
            } finally { if (barrier != null && !barrier.isDone()) barrier.cancel(false); }
        }
        @Override public void close() throws Exception {
            if (closed) return; closed = true; Throwable failure = null;
            try {
                main(() -> {
                    try {
                        RunControl current = (RunControl) field(controller, "control");
                        if (owner != null && current == owner) controller.stop();
                        else if (current != null) throw new AssertionError("Do not stop another task during cleanup");
                        if (host != null && !host.isEnded()) host.close();
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                if (server != null) server.releaseResponse();
                settleWorker();
                if (host != null) host.whenRemoved().get(5, TimeUnit.SECONDS);
                await("Native/preview/HTTP owners did not actually drain", () -> ImageWorkset.process().reservedBytes() == 0
                        && !WindowBufferCapturer.hasActiveCapture() && !ImagePreviewLoader.isWorkPending()
                    && !ImageReviewPresentationCoordinator.hasHost(), 10_000);
                main(() -> {
                    try { if (device != null) assertNull("Actual service retained a review host", field(device.service, "reviewHost")); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                report.addProperty("cleanupDrained", true);
            } catch (Exception | Error error) { failure = error; }
            try { if (server != null) server.close(); } catch (Exception | Error error) { failure = add(failure, error); }
            try { retainObservedHttpEvidence(); } catch (Exception | Error error) { failure = add(failure, error); }
            try { if (backup != null) backup.close(); } catch (Exception | Error error) { failure = add(failure, error); }
            try { if (device != null) device.close(); } catch (Exception | Error error) { failure = add(failure, error); }
            try { main(controller::clearCompletedTask); } catch (Exception | Error error) { failure = add(failure, error); }
            rethrow(failure);
        }
        private void retainObservedHttpEvidence() throws Exception {
            // Preserve a real approved payload even when a later assertion failed.
            // This does not change the case result or infer approval from a file.
            if (server != null) {
                report.addProperty("syntheticProbeImageCount", server.imageRequestCount());
                report.addProperty("taskHttpRequests", server.taskRequestCount());
            }
            if (script == null) return;
            report.addProperty("taskImageCount", script.deviceImages.get());
            report.addProperty("afterImageRequestWithoutImageCount", script.imageFreeFollowingRequests.get());
            if (script.receivedHash == null) return;
            assertFalse("A rejected payload must never be retained as approved", decline);
            assertNotNull(session); assertNotNull(reviewIdentity); assertNotNull(removed);
            assertTrue(report.get("reviewDisplayed").getAsBoolean());
            assertTrue(report.has("reviewRejected") && !report.get("reviewRejected").getAsBoolean());
            assertEquals(1, script.deviceImages.get());
            assertEquals(session.imageSha256, script.receivedHash);
            assertTrue(payloadFile.isFile()); assertEquals((long) script.receivedBytes, payloadFile.length());
            // The server already required the exact host acknowledgement before
            // accepting this PNG. Independently retain actual detach/window-table
            // evidence here if the normal post-request assertions ended early.
            removed.get(5, TimeUnit.SECONDS);
            main(() -> assertFalse(host.rootView().isAttachedToWindow()));
            await("Received image's original review remains published", () -> !reviewWindowPublished(), 5_000);
            report.addProperty("actualReviewRemoved", true);
            report.addProperty("receivedSha256", script.receivedHash);
            report.addProperty("image", payloadFile.getName());
            report.addProperty("imageSha256", script.receivedHash);
            report.addProperty("imageBytes", script.receivedBytes);
        }
        void finish(Throwable failure) throws Exception {
            report.addProperty("status", failure == null ? "passed" : "failed"); report.addProperty("finishedAt", System.currentTimeMillis());
            if (failure != null) report.addProperty("failureClass", failure.getClass().getSimpleName());
            persist();
        }
        void persist() throws Exception {
            byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
            assertTrue(bytes.length <= 96 * 1024);
            try (FileOutputStream output = new FileOutputStream(new File(directory, caseName + ".case.json"))) { output.write(bytes); }
        }
    }

    @FunctionalInterface private interface Condition { boolean test() throws Exception; }
    private static void await(String reason, Condition condition, long millis) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + millis;
        do { if (condition.test()) return; SystemClock.sleep(40); } while (SystemClock.elapsedRealtime() < deadline);
        fail(reason);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Throwable add(Throwable first, Throwable next) { if (first == null) return next; first.addSuppressed(next); return first; }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError(failure);
    }
}
