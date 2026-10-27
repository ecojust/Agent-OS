package dev.agentos.app;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.RunControl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Dedicated recorded walkthrough, excluded from the ordinary API matrix.
 * The model is a local deterministic HTTP fixture, never an external language model.
 * Begins from an actually saved synthetic configuration. No secure window is relaxed.
 */
@RunWith(AndroidJUnit4.class)
public final class DemoWalkthroughInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private static final long DEMO_MILLIS = 90_000;
    private static final Set<String> STAGES = new HashSet<>(Arrays.asList(
            "ready", "confirmation_waiting", "text_entered", "text_saved", "counter_verified",
            "scrolled_down", "scrolled_up", "second_page", "returned_to_fixture", "controls_waiting",
            "paused", "resumed", "stopped", "stale_approval_rejected"));
    private final JsonObject report = new JsonObject();
    private final JsonArray stages = new JsonArray();
    private long startedElapsed, startedWall;
    private String currentStage = "ready";
    private int approvalOrdinal;
    private AgentController controller;
    private LocalControllerModelServer activeServer;
    private RunControl taskOwner;
    private PerformanceTrace.Session diagnostics;
    private long chapterStartedNanos;

    @Test public void observedActionsRequireConfirmationAndRespectUserControls() throws Exception {
        requireDedicatedRecording();
        initializeReport();
        Throwable failure = null;
        try (PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics();
             DeviceHarness device = new DeviceHarness()) {
            diagnostics = trace;
            controller = AgentController.get(device.context);
            assertFalse("A demo must not interrupt an existing task", controller.getState().running);
            settleWorker();

            try {
                runWritingChapter(device);
                runNavigationChapter(device);
                runUserControlsChapter(device);
                requireTime();
                assertFalse(controller.getState().running);
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
                assertEquals("Saved: " + LocalControllerModelServer.TEXT, fixtureText(device, "saved_value"));
                report.addProperty("status", "passed");
            } catch (Exception | Error error) {
                failure = error;
                try { stage("failed"); screenshots.capture("demo-failure-" + currentStage); }
                catch (Exception | Error evidenceError) { error.addSuppressed(evidenceError); }
                throw error;
            } finally {
                clearPreserving(device, failure);
            }
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            report.addProperty("finishedAt", System.currentTimeMillis());
            report.addProperty("finishedElapsedMs", SystemClock.elapsedRealtime());
            if (failure != null) {
                report.addProperty("status", "failed");
                JsonObject detail = new JsonObject();
                detail.addProperty("stage", currentStage);
                detail.addProperty("errorClass", failure.getClass().getName());
                report.add("failure", detail);
            }
            boolean expired = failure == null && SystemClock.elapsedRealtime() - startedElapsed >= DEMO_MILLIS;
            if (expired) {
                failure = new AssertionError("Recorded walkthrough exceeded its 90-second budget");
                report.addProperty("status", "failed");
                JsonObject detail = new JsonObject(); detail.addProperty("stage", currentStage);
                detail.addProperty("errorClass", failure.getClass().getName()); report.add("failure", detail);
            }
            try { persistReport(); }
            catch (Exception | Error evidenceError) {
                if (failure != null) failure.addSuppressed(evidenceError); else throw evidenceError;
            }
            if (expired) throw (AssertionError) failure;
        }
    }

    private void runWritingChapter(DeviceHarness device) throws Exception {
        try (LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.SAVE_AND_COUNT);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            activeServer = server;
            Throwable chapterFailure = null;
            try {
                assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, new SettingsStore(device.context).load().executionMode);
                recordVisible(device, "ready");
                start(device, settings, "Local synthetic demonstration: save the fixture sample and increment its counter once.");
                approveVerifiedSequence(device, new String[]{"text_entered", "text_saved", "counter_verified"}, 3);
                assertEquals("Saved: " + LocalControllerModelServer.TEXT, fixtureText(device, "saved_value"));
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
            } catch (Exception | Error error) {
                AssertionError diagnosed = diagnosedChapterFailure(device, error);
                chapterFailure = diagnosed; throw diagnosed;
            }
            finally { clearPreserving(device, chapterFailure); }
        }
    }

    private void runNavigationChapter(DeviceHarness device) throws Exception {
        try (LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.DEMO_NAVIGATION);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            activeServer = server;
            Throwable chapterFailure = null;
            try {
                start(device, settings, "Local synthetic demonstration: scroll down and up, open the second fixture page, then return.");
                approveVerifiedSequence(device,
                        new String[]{"scrolled_down", "scrolled_up", "second_page", "returned_to_fixture"}, 4);
                assertEquals("Saved: " + LocalControllerModelServer.TEXT, fixtureText(device, "saved_value"));
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
            } catch (Exception | Error error) {
                AssertionError diagnosed = diagnosedChapterFailure(device, error);
                chapterFailure = diagnosed; throw diagnosed;
            }
            finally { clearPreserving(device, chapterFailure); }
        }
    }

    private void runUserControlsChapter(DeviceHarness device) throws Exception {
        try (LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.DEMO_CONTROL_PENDING);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            activeServer = server;
            Throwable chapterFailure = null;
            try {
                start(device, settings, "Local synthetic demonstration: request a counter action and wait for user permission.");
                AgentController.State pending = awaitApproval("");
                RunControl owner = taskOwner;
                long originalEpoch = owner.getExecutionEpoch();
                assertEquals(0, owner.getDispatchAttempts());
                recordVisible(device, "controls_waiting");
                int beforePause = server.requestCount();
                clickOwnedButton(device, "暂停", pending.approvalId);
                assertTrue(controller.getState().running);
                assertTrue(controller.getState().paused);
                assertTrue(controller.getState().approvalId.isEmpty());
                assertSame(owner, readControl());
                long pausedEpoch = owner.getExecutionEpoch();
                assertTrue(pausedEpoch > originalEpoch);
                recordVisible(device, "paused");
                assertEquals("Paused task requested another plan", beforePause, server.requestCount());
                assertEquals(0, owner.getDispatchAttempts());
                assertEquals("Count: 1", fixtureText(device, "counter_value"));

                clickOwnedButton(device, "继续", "");
                AgentController.State resumed = awaitApproval(pending.approvalId);
                assertSame(owner, readControl());
                assertTrue(owner.getExecutionEpoch() > pausedEpoch);
                assertFalse(resumed.paused);
                assertNotEquals(pending.approvalId, resumed.approvalId);
                assertTrue(server.requestCount() > beforePause);
                recordVisible(device, "resumed");
                // Negative stale-ID assertion is not presented as a click of the new UI.
                onMain(device, () -> controller.approve(pending.approvalId, true));
                assertEquals(resumed.approvalId, controller.getState().approvalId);
                assertEquals(0, owner.getDispatchAttempts());

                int beforeStop = server.requestCount();
                clickOwnedButton(device, "停止", resumed.approvalId);
                settleWorker(); device.instrumentation.waitForIdleSync();
                assertFalse(controller.getState().running);
                assertEquals("已停止", controller.getState().status);
                assertTrue(controller.getState().approvalId.isEmpty());
                assertEquals(0, owner.getDispatchAttempts());
                recordVisible(device, "stopped");
                onMain(device, () -> {
                    controller.approve(pending.approvalId, true);
                    controller.approve(resumed.approvalId, true);
                });
                assertFalse(controller.getState().running);
                assertEquals(0, owner.getDispatchAttempts());
                assertEquals(beforeStop, server.requestCount());
                assertEquals(0, server.acceptedWriteCount());
                assertEquals("Count: 1", fixtureText(device, "counter_value"));
                server.assertHealthy();
                recordVisible(device, "stale_approval_rejected");
            } catch (Exception | Error error) {
                AssertionError diagnosed = diagnosedChapterFailure(device, error);
                chapterFailure = diagnosed; throw diagnosed;
            }
            finally { clearPreserving(device, chapterFailure); }
        }
    }

    private void approveVerifiedSequence(DeviceHarness device, String[] verifiedStages, int acceptedWrites) throws Exception {
        Set<String> approved = new HashSet<>();
        int verified = 0;
        while (true) {
            requireTime(); activeServer.assertHealthy();
            int observed = activeServer.verifiedDemoSteps();
            if (observed != verified) {
                assertEquals("A recorded result cannot skip an unobserved step", verified + 1, observed);
                recordVisible(device, verifiedStages[verified++]);
            }
            AgentController.State state = controller.getState();
            if (!state.running) break;
            assertFalse("The walkthrough unexpectedly paused", state.paused);
            if (!state.approvalId.isEmpty() && approved.add(state.approvalId)) {
                currentStage = "confirmation_waiting";
                approvalOrdinal++;
                long unapprovedDispatches = taskOwner.getDispatchAttempts();
                awaitDisplayedApproval(device, state.approvalId);
                stage("passed");
                // Keep normal user thinking time; capture only after the real approval has appeared.
                holdVisible(1_200);
                assertEquals("An unapproved pending action was dispatched", unapprovedDispatches, taskOwner.getDispatchAttempts());
                assertEquals(state.approvalId, controller.getState().approvalId);
                screenshots.capture("confirmation-" + approvalOrdinal);
                clickOwnedButton(device, "允许此步", state.approvalId);
            } else SystemClock.sleep(25);
        }
        settleWorker(); device.instrumentation.waitForIdleSync(); activeServer.assertHealthy();
        assertEquals(verifiedStages.length, verified);
        assertEquals(acceptedWrites, activeServer.acceptedWriteCount());
        assertEquals(acceptedWrites, taskOwner.getDispatchAttempts());
        assertTrue(activeServer.finalStateObserved());
        assertTrue(approved.size() >= acceptedWrites);
        assertTrue(approved.size() <= acceptedWrites + LocalControllerModelServer.MAX_WRITE_REPLANS);
        assertEquals("执行已结束 · 模型报告待核对", controller.getState().status);
    }

    private void start(DeviceHarness device, ControllerTestSettings settings, String task) throws Exception {
        requireTime(); chapterStartedNanos = SystemClock.elapsedRealtimeNanos();
        onMain(device, () -> controller.start(settings.model, task,
                Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, settings.lease));
        assertTrue(controller.getState().running);
        taskOwner = readControl();
        assertNotNull(taskOwner);
    }

    /** Purely observe the same pending approval's actual owned View; never replay a UI action. */
    private void awaitDisplayedApproval(DeviceHarness device, String expectedApproval) {
        long deadline = Math.min(startedElapsed + DEMO_MILLIS, SystemClock.elapsedRealtime() + 5_000);
        while (SystemClock.elapsedRealtime() < deadline) {
            requireTime(); activeServer.assertHealthy();
            boolean[] visible = {false};
            onMain(device, () -> {
                AgentController.State state = controller.getState();
                assertTrue("Task ended before its actual confirmation was displayed", state.running);
                assertEquals(expectedApproval, state.approvalId);
                View root = readField(device.service, AgentAccessibilityService.class, "overlay", View.class);
                AgentAccessibilityService.OwnedControlWindowIdentity identity = device.service.getOwnedControlWindowIdentity();
                if (root == null || !root.isAttachedToWindow() || !root.isShown() || !root.isLaidOut()
                        || root.isLayoutRequested() || !device.service.isOwnedControlWindowCurrent(identity)) return;
                Button approve = findButton(root, "允许此步");
                String displayed = readField(device.service, AgentAccessibilityService.class, "currentApproval", String.class);
                Long bound = readField(device.service, AgentAccessibilityService.class, "displayedCallbackGeneration", Long.class);
                Long current = readField(device.service, AgentAccessibilityService.class, "callbackGeneration", Long.class);
                visible[0] = !state.approval.isEmpty() && state.approval.equals(displayed) && bound.equals(current)
                        && approve != null && approve.isShown() && approve.isEnabled();
            });
            if (visible[0] && SystemClock.elapsedRealtime() < deadline) return;
            SystemClock.sleep(25);
        }
        throw new AssertionError("The actual owned confirmation did not appear within the fixed display wait");
    }

    /** Preserve the original assertion and safe actual state before chapter cleanup clears them. */
    private AssertionError diagnosedChapterFailure(DeviceHarness device, Throwable cause) {
        String detail = "diagnostics=unavailable";
        try {
            AgentController.State state = controller.getState();
            detail = "controller={running=" + state.running + ",paused=" + state.paused
                    + ",approvalPending=" + !state.approvalId.isEmpty()
                    + ",status=" + diagnosticStatus(state.status) + ",summaryClass=" + diagnosticSummary(state.summary)
                    + ",dispatchAttempts=" + (taskOwner == null ? -1 : taskOwner.getDispatchAttempts()) + "}\n"
                    + (activeServer == null ? "server=absent" : activeServer.safeDiagnostics())
                    + "\n" + diagnosticTrace();
        } catch (Throwable diagnosticFailure) { cause.addSuppressed(diagnosticFailure); }
        AssertionError result = new AssertionError("Recorded controller chapter failed; original cause retained.\n" + detail, cause);
        try { screenshots.capture("chapter-failure-" + currentStage); }
        catch (Throwable captureFailure) { result.addSuppressed(captureFailure); }
        return result;
    }

    private String diagnosticTrace() {
        if (diagnostics == null) return "trace=absent";
        java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
        int completed = 0, failed = 0, truncated = 0, local = 0, window = 0, revision = 0;
        for (PerformanceTrace.Event event : diagnostics.events()) {
            if (event.startNanos < chapterStartedNanos) continue;
            boolean snapshot = event.stage == PerformanceTrace.Stage.SNAPSHOT_PREPARE
                    || event.stage == PerformanceTrace.Stage.SNAPSHOT_FINAL_MUTATION;
            boolean rejection = event.stage == PerformanceTrace.Stage.LOCAL_CONTROL_STALE
                    || event.stage == PerformanceTrace.Stage.OWNED_WINDOW_MISMATCH
                    || event.stage == PerformanceTrace.Stage.OBSERVATION_REVISION_MISMATCH;
            if (!snapshot && !rejection) continue;
            if (snapshot) { if (event.nodeCount < 0) failed++; else completed++; if (event.truncated) truncated++; }
            if (event.stage == PerformanceTrace.Stage.LOCAL_CONTROL_STALE) local++;
            if (event.stage == PerformanceTrace.Stage.OWNED_WINDOW_MISMATCH) window++;
            if (event.stage == PerformanceTrace.Stage.OBSERVATION_REVISION_MISMATCH) revision++;
            recent.addLast(event.stage.name() + "{nodes=" + event.nodeCount + ",bytes=" + event.snapshotJsonBytes
                    + ",truncated=" + event.truncated + ",epoch=" + event.epoch
                    + ",dispatchAttempts=" + event.dispatchAttempts + "}");
            if (recent.size() > 12) recent.removeFirst();
        }
        return "trace={snapshotCompleted=" + completed + ",snapshotFailed=" + failed + ",truncated=" + truncated
                + ",localControlStale=" + local + ",ownedWindowMismatch=" + window + ",revisionMismatch=" + revision
                + ",overwritten=" + diagnostics.overwrittenCount() + ",recent=" + recent + "}";
    }

    private static String diagnosticStatus(String value) {
        if ("执行失败".equals(value)) return "FAILED";
        if ("已停止".equals(value)) return "STOPPED";
        if ("达到任务预算，已停止".equals(value)) return "BUDGET_EXCEEDED";
        if ("配置已改变，任务已停止".equals(value)) return "CONFIGURATION_CHANGED";
        if ("执行已结束 · 模型报告待核对".equals(value)) return "MODEL_REPORT";
        if ("准备就绪".equals(value)) return "READY";
        return value == null || value.isEmpty() ? "EMPTY" : "OTHER";
    }

    private static String diagnosticSummary(String value) {
        if ("模型连续三轮未提供可执行的有效操作，任务已停止。".equals(value)) return "THREE_INVALID_TURNS";
        if ("操作可能已提交但结果未知；任务已停止，请手动核对，未自动重发。".equals(value)) return "OUTCOME_UNKNOWN";
        if ("你已拒绝该动作，任务已结束，不会改用其他方式执行。".equals(value)) return "USER_DENIED";
        if ("配置或授权已改变，请重新保存配置并选择允许的应用。".equals(value)) return "CONFIGURATION_INVALID";
        if ("任务预算已改变，请重新保存配置后启动。".equals(value)) return "BUDGET_CHANGED";
        if ("任务执行中断；请检查当前界面后重新开始。".equals(value)) return "INTERNAL_ERROR";
        return value == null || value.isEmpty() ? "EMPTY" : "OTHER";
    }

    /** Invoke the real currently attached Button's production listener, never replace callbacks. */
    private void clickOwnedButton(DeviceHarness device, String text, String expectedApproval) throws Exception {
        requireTime(); device.instrumentation.waitForIdleSync();
        CompletableFuture<AgentAccessibilityService.OwnedControlWindowIdentity> acquisition =
                device.service.acquireOwnedControlWindowIdentity();
        AgentAccessibilityService.OwnedControlWindowIdentity identity;
        try { identity = acquisition.get(Math.min(6_000, remaining()), TimeUnit.MILLISECONDS); }
        finally { acquisition.cancel(false); }
        onMain(device, () -> {
            assertEquals(expectedApproval, controller.getState().approvalId);
            assertTrue(device.service.isOwnedControlWindowCurrent(identity));
            View root = readField(device.service, AgentAccessibilityService.class, "overlay", View.class);
            assertNotNull(root); assertTrue(root.isAttachedToWindow());
            Button button = findButton(root, text);
            assertNotNull("The actual control button was not visible", button);
            assertTrue(button.isShown()); assertTrue(button.isEnabled());
            assertTrue(button.performClick());
        });
        device.instrumentation.waitForIdleSync(); requireTime();
    }

    /** Surface UI-thread failures on the instrumentation thread so finally can preserve evidence. */
    private static void onMain(DeviceHarness device, Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable problem) { failure.set(problem); }
        });
        Throwable problem = failure.get();
        if (problem instanceof Error) throw (Error) problem;
        if (problem instanceof RuntimeException) throw (RuntimeException) problem;
        if (problem != null) throw new AssertionError("Demo UI action failed", problem);
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button child = findButton(group.getChildAt(i), text);
                if (child != null) return child;
            }
        }
        return null;
    }

    private AgentController.State awaitApproval(String old) {
        while (true) {
            requireTime(); activeServer.assertHealthy();
            AgentController.State state = controller.getState();
            assertTrue("Task ended before its real confirmation appeared", state.running);
            if (!state.approvalId.isEmpty() && !state.approvalId.equals(old)) return state;
            SystemClock.sleep(25);
        }
    }

    private void recordVisible(DeviceHarness device, String name) throws Exception {
        requireTime(); currentStage = name;
        device.instrumentation.waitForIdleSync();
        stage("passed"); screenshots.capture(name); holdVisible(1_200);
    }

    private void holdVisible(long millis) {
        long until = Math.min(startedElapsed + DEMO_MILLIS, SystemClock.elapsedRealtime() + millis);
        while (SystemClock.elapsedRealtime() < until) { requireTime(); SystemClock.sleep(25); }
        requireTime();
    }

    private long remaining() { requireTime(); return startedElapsed + DEMO_MILLIS - SystemClock.elapsedRealtime(); }
    private void requireTime() { assertTrue("Recorded walkthrough exceeded its 90-second budget", SystemClock.elapsedRealtime() - startedElapsed < DEMO_MILLIS); }

    private RunControl readControl() { return readField(controller, AgentController.class, "control", RunControl.class); }
    private static <T> T readField(Object owner, Class<?> type, String fieldName, Class<T> valueType) {
        try { Field field = type.getDeclaredField(fieldName); field.setAccessible(true); return valueType.cast(field.get(owner)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Unable to inspect the original demo owner", failure); }
    }

    private void settleWorker() throws Exception {
        ThreadPoolExecutor worker = readField(controller, AgentController.class, "worker", ThreadPoolExecutor.class);
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        java.util.concurrent.Future<?> barrier = null;
        try {
            while (barrier == null) {
                try { barrier = worker.submit(() -> { }); }
                catch (java.util.concurrent.RejectedExecutionException rejected) {
                    if (worker.isShutdown() || SystemClock.elapsedRealtime() >= deadline) throw rejected;
                    // Only an explicitly unqueued, side-effect-free no-op is retried.
                    SystemClock.sleep(25);
                }
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Demo worker barrier expired");
            barrier.get(remaining, TimeUnit.MILLISECONDS);
        } finally {
            if (barrier != null && !barrier.isDone()) barrier.cancel(false);
        }
    }

    private void clearPreserving(DeviceHarness device, Throwable original) throws Exception {
        try { clearAndSettle(device); }
        catch (Exception | Error cleanup) {
            if (original == null) throw cleanup;
            original.addSuppressed(cleanup);
        }
    }

    private void clearAndSettle(DeviceHarness device) throws Exception {
        onMain(device, controller::stop);
        settleWorker();
        onMain(device, controller::clearCompletedTask);
        device.instrumentation.waitForIdleSync();
    }

    /** Independent platform reads; never issue a parallel device tool while confirmation is held. */
    private String fixtureText(DeviceHarness device, String id) throws Exception {
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            android.view.accessibility.AccessibilityWindowInfo focused = null;
            for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                if (!window.isFocused()) continue;
                assertNull("Multiple focused windows", focused); focused = window;
            }
            assertNotNull(focused);
            android.view.accessibility.AccessibilityNodeInfo root = focused.getRoot();
            assertNotNull(root);
            try {
                assertEquals(DeviceHarness.FIXTURE, String.valueOf(root.getPackageName()));
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(DeviceHarness.FIXTURE + ":id/" + id);
                try { assertEquals(1, nodes.size()); return String.valueOf(nodes.get(0).getText()); }
                finally { for (android.view.accessibility.AccessibilityNodeInfo node : nodes) node.recycle(); }
            } finally { root.recycle(); }
        } finally { for (android.view.accessibility.AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static void requireDedicatedRecording() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assertEquals("agent-os-real-demo-v1", arguments.getString("demoMarker"));
        assertEquals("35", arguments.getString("expectedApi"));
        assertEquals(35, Build.VERSION.SDK_INT);
        assertTrue(Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64"));
        // Read-only preflight precedes DeviceHarness enabling the actual accessibility service.
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("getprop ro.kernel.qemu"))) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int value;
            while ((value = input.read()) >= 0) {
                assertTrue("Emulator preflight output exceeded its limit", bytes.size() < 16); bytes.write(value);
            }
            assertEquals("1", bytes.toString("US-ASCII").trim());
        }
    }

    private void initializeReport() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String sha = arguments.getString("sourceSha", "");
        String runId = arguments.getString("runId", ""), attempt = arguments.getString("runAttempt", "");
        assertTrue(sha.matches("[0-9a-f]{40}"));
        assertTrue(runId.matches("[1-9][0-9]{0,17}")); assertTrue(attempt.matches("[1-9][0-9]{0,5}"));
        startedElapsed = SystemClock.elapsedRealtime(); startedWall = System.currentTimeMillis();
        report.addProperty("schemaVersion", 1); report.addProperty("kind", "agent-os-synthetic-controller-demo");
        JsonObject source = new JsonObject(); source.addProperty("repository", "ecojust/Agent-OS");
        source.addProperty("commit", sha); source.addProperty("runId", Long.parseLong(runId)); source.addProperty("attempt", Integer.parseInt(attempt));
        report.add("source", source);
        JsonObject device = new JsonObject(); device.addProperty("api", Build.VERSION.SDK_INT); device.addProperty("emulator", true);
        report.add("device", device);
        JsonObject model = new JsonObject(); model.addProperty("kind", "local_synthetic_http"); model.addProperty("externalModelUsed", false);
        report.add("model", model); report.addProperty("startedAt", startedWall); report.addProperty("startedElapsedMs", startedElapsed);
        report.addProperty("status", "failed"); report.add("stages", stages); report.add("failure", JsonNull.INSTANCE);
    }

    private void stage(String status) {
        assertTrue(STAGES.contains(currentStage)); assertTrue(stages.size() < 40);
        AgentController.State state = controller == null ? null : controller.getState();
        JsonObject entry = new JsonObject(); entry.addProperty("name", currentStage); entry.addProperty("status", status);
        entry.addProperty("atElapsedMs", SystemClock.elapsedRealtime() - startedElapsed); entry.addProperty("wallTimeMs", System.currentTimeMillis());
        entry.addProperty("controllerRunning", state != null && state.running); entry.addProperty("controllerPaused", state != null && state.paused);
        entry.addProperty("pendingApproval", state != null && !state.approvalId.isEmpty());
        entry.addProperty("requestCount", activeServer == null ? 0 : activeServer.requestCount());
        entry.addProperty("dispatchAttempts", taskOwner == null ? 0 : taskOwner.getDispatchAttempts());
        entry.addProperty("approvalOrdinal", "confirmation_waiting".equals(currentStage) ? approvalOrdinal : 0);
        stages.add(entry);
    }

    private void persistReport() throws Exception {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "demo-video");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create demo evidence directory");
        byte[] bytes = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_536) throw new java.io.IOException("Demo evidence exceeded its bounded report size");
        try (FileOutputStream output = new FileOutputStream(new File(directory, "flow-result.json"))) {
            output.write(bytes); output.getFD().sync();
        }
    }
}
