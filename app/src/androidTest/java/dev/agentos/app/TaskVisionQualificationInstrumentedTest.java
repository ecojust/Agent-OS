package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonObject;

import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.DisclosureGuard;
import dev.agentos.core.LocalToolResult;
import dev.agentos.core.ToolCatalog;
import dev.agentos.core.ToolRequest;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Real saved settings, Controller and HTTP PNG challenge; no screenshot or real-model acceptance claim. */
@RunWith(AndroidJUnit4.class)
public final class TaskVisionQualificationInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void defaultStartMakesOnlyStructuredRequestsAndNeverQualifiesVision() throws Exception {
        try (Environment env = new Environment(LocalVisionProbeServer.Mode.VERIFIED, TaskBudget.DEFAULT);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            env.startDefault();
            AgentController.State pending = env.awaitApproval(AgentController.VisualQualification.NOT_REQUESTED);
            assertFalse(pending.visualRequested);
            assertEquals(0, env.server.imageRequestCount());
            assertEquals(0, env.server.toolRequestCount());
            assertEquals(env.server.taskRequestCount(), env.server.requestCount());
            assertEquals(env.server.requestCount(), pending.usage.consumed.modelRounds);
            assertNull(SavedVisionProbe.process().current(env.lease));
            env.assertStructuredOnlyAndNoDispatch(trace);
            screenshots.capture("default-task-uses-structured-tools-without-synthetic-vision-request");
        }
    }

    @Test public void optedInTaskUsesItsOwnRealProbeAndResumeNeverRenewsTheOldQualification() throws Exception {
        try (Environment env = new Environment(LocalVisionProbeServer.Mode.VERIFIED, TaskBudget.DEFAULT);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            env.startRequested();
            long originalEpoch = env.owner.getExecutionEpoch();
            AgentController.State first = env.awaitApproval(AgentController.VisualQualification.SYNTHETIC_VERIFIED);
            assertTrue(first.visualRequested);
            assertEquals(1, env.server.imageRequestCount());
            assertEquals(env.server.requestCount(), first.usage.consumed.modelRounds);
            SavedVisionProbe.Verification record = SavedVisionProbe.process().current(env.lease);
            assertNotNull(record);
            assertTrue(record.isCurrentFor(env.lease, env.owner, originalEpoch));
            RunControl unrelated = new RunControl(env.lease.profileId);
            long unrelatedEpoch = unrelated.getExecutionEpoch();
            try {
                assertFalse(record.isCurrentFor(env.lease, unrelated, unrelatedEpoch));
                // A second actual probe of the same saved lease supersedes the
                // task's record; no boolean/factory can manufacture this transition.
                assertEquals(SavedVisionProbe.Status.VERIFIED,
                        SavedVisionProbe.process().test(env.context, env.lease, unrelated, unrelatedEpoch).status);
                SavedVisionProbe.Verification replacement = SavedVisionProbe.process().current(env.lease);
                assertNotNull(replacement); assertNotSame(record, replacement);
                assertTrue(replacement.isCurrentFor(env.lease, unrelated, unrelatedEpoch));
                assertFalse(record.isCurrentFor(env.lease, env.owner, originalEpoch));
                assertEquals(AgentController.VisualQualification.INVALIDATED, env.controller.getState().visualQualification);
                assertEquals(2, env.server.imageRequestCount());
                assertEquals(env.server.taskRequestCount() + 1, env.owner.budgetSnapshot().modelRounds);
                String priorApproval = first.approvalId;
                env.main(env.controller::pauseResume);
                assertTrue(env.controller.getState().paused);
                assertEquals(AgentController.VisualQualification.INVALIDATED, env.controller.getState().visualQualification);
                env.main(env.controller::pauseResume);
                AgentController.State resumed = env.awaitApproval(AgentController.VisualQualification.INVALIDATED);
                assertNotEquals(priorApproval, resumed.approvalId);
                assertTrue(env.owner.getExecutionEpoch() > originalEpoch);
                assertEquals("Only the two explicit probes may send images; resume must not send a third", 2,
                        env.server.imageRequestCount());
                assertEquals(env.server.taskRequestCount() + 1, resumed.usage.consumed.modelRounds);
                assertEquals(1, unrelated.budgetSnapshot().modelRounds);
                assertTrue(replacement.isCurrentFor(env.lease, unrelated, unrelatedEpoch));
                assertTrue(env.server.receivedMaxTokens().stream().allMatch(value -> value == env.settings.model.maxOutputTokens));
                env.assertStructuredOnlyAndNoDispatch(trace);
                screenshots.capture("task-owned-synthetic-record-superseded-and-not-renewed-on-resume");
                env.main(env.controller::stop); env.awaitStopped();
                env.main(env.controller::clearCompletedTask);
                assertFalse(env.controller.getState().visualRequested);
                assertEquals(AgentController.VisualQualification.NOT_REQUESTED, env.controller.getState().visualQualification);
                assertNull(env.controller.getState().usage);
                assertTrue("Clearing this task must not cancel the unrelated current diagnostic", replacement.isCurrentFor(env.lease, unrelated, unrelatedEpoch));
            } finally { unrelated.cancel(); }
        }
    }

    @Test public void unverifiedAndUnauthorizedImageResponsesStillAllowStructuredPlanning() throws Exception {
        LocalVisionProbeServer.Mode[] modes = {LocalVisionProbeServer.Mode.UNVERIFIED, LocalVisionProbeServer.Mode.HTTP_UNAUTHORIZED};
        AgentController.VisualQualification[] expected = {AgentController.VisualQualification.UNVERIFIED, AgentController.VisualQualification.FAILED};
        for (int index = 0; index < modes.length; index++) {
            try (Environment env = new Environment(modes[index], TaskBudget.DEFAULT);
                 PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                env.startRequested();
                AgentController.State pending = env.awaitApproval(expected[index]);
                assertTrue(pending.visualRequested);
                assertTrue(pending.running);
                assertEquals(1, env.server.imageRequestCount());
                assertTrue(env.server.taskRequestCount() >= 1);
                assertEquals(env.server.requestCount(), pending.usage.consumed.modelRounds);
                assertNull(SavedVisionProbe.process().current(env.lease));
                env.assertStructuredOnlyAndNoDispatch(trace);
                screenshots.capture("synthetic-image-" + modes[index].name().toLowerCase(java.util.Locale.ROOT) + "-structured-fallback");
            }
        }
    }

    @Test public void stopConfigurationChangeAndPauseDuringTheRealProbeRejectItsLateSuccess() throws Exception {
        for (String intervention : new String[] {"stop", "configuration", "pause", "queued_pause_resume"}) {
            try (Environment env = new Environment(LocalVisionProbeServer.Mode.HELD_VERIFIED, TaskBudget.DEFAULT);
                 PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                if ("queued_pause_resume".equals(intervention)) {
                    env.start(true, true);
                    AgentController.State resumed = env.awaitApproval(AgentController.VisualQualification.INVALIDATED);
                    assertTrue(env.owner.getExecutionEpoch() > env.originalStartEpoch);
                    assertEquals("The queued old request must not acquire the resumed epoch", 0, env.server.imageRequestCount());
                    assertEquals(env.server.taskRequestCount(), resumed.usage.consumed.modelRounds);
                    assertNull(SavedVisionProbe.process().current(env.lease));
                    env.assertStructuredOnlyAndNoDispatch(trace);
                    screenshots.capture("queued-original-visual-epoch-invalidated-before-any-probe-http");
                    continue;
                }
                env.startRequested();
                assertTrue(env.server.awaitRequests(1, 10_000));
                long originalEpoch = env.originalStartEpoch;
                assertEquals(AgentController.VisualQualification.CHECKING, env.controller.getState().visualQualification);
                assertEquals(1, env.server.imageRequestCount());
                assertEquals(0, env.server.taskRequestCount());
                assertEquals(1, env.owner.budgetSnapshot().modelRounds);
                if ("configuration".equals(intervention)) {
                    SettingsStore store = new SettingsStore(env.context);
                    SettingsStore.ConfigurationLease replacement = store.save(store.load());
                    assertFalse(SettingsStore.isCurrent(env.lease));
                    assertTrue(SettingsStore.isCurrent(replacement));
                } else if ("pause".equals(intervention)) {
                    env.main(env.controller::pauseResume);
                    assertTrue(env.controller.getState().paused);
                } else {
                    env.main(env.controller::stop);
                }
                assertEquals(AgentController.VisualQualification.INVALIDATED, env.controller.getState().visualQualification);
                env.server.releaseResponse();
                assertTrue(env.server.awaitResponseAttempt());
                if ("pause".equals(intervention)) {
                    env.main(env.controller::pauseResume);
                    AgentController.State resumed = env.awaitApproval(AgentController.VisualQualification.INVALIDATED);
                    assertTrue(resumed.running);
                    assertTrue(env.owner.getExecutionEpoch() > originalEpoch);
                    assertEquals(1, env.server.imageRequestCount());
                } else {
                    AgentController.State ended = env.awaitStopped();
                    assertTrue(env.owner.isCancelled());
                    assertEquals(AgentController.VisualQualification.INVALIDATED, ended.visualQualification);
                    assertEquals(0, env.server.taskRequestCount());
                    assertEquals(1, ended.usage.consumed.modelRounds);
                }
                assertNull(SavedVisionProbe.process().current(env.lease));
                env.assertStructuredOnlyAndNoDispatch(trace);
                screenshots.capture("held-task-image-probe-" + intervention + "-rejects-late-verification");
            }
        }
    }

    @Test public void imageProbeConsumesTheOriginalOneRoundBudgetBeforeTaskPlanningCanSend() throws Exception {
        TaskBudget budget = new TaskBudget(1, 100, 600_000);
        try (Environment env = new Environment(LocalVisionProbeServer.Mode.VERIFIED, budget);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            env.startRequested();
            AgentController.State ended = env.awaitStopped();
            assertEquals("达到任务预算，已停止", ended.status);
            assertTrue(ended.visualRequested);
            assertEquals(AgentController.VisualQualification.INVALIDATED, ended.visualQualification);
            assertEquals(1, env.server.requestCount());
            assertEquals(1, env.server.imageRequestCount());
            assertEquals(0, env.server.taskRequestCount());
            assertEquals(1, ended.usage.consumed.modelRounds);
            assertEquals(budget, ended.usage.budget);
            assertTrue(ended.usage.finished);
            assertTrue(env.owner.isCancelled());
            assertNull(SavedVisionProbe.process().current(env.lease));
            env.assertStructuredOnlyAndNoDispatch(trace);
            screenshots.capture("one-real-probe-exhausts-original-model-round-budget");
        }
    }

    @Test public void anotherRealSavedProbeMakesQualificationBusyWithoutStealingItsOwnerOrSendingASecondImage() throws Exception {
        try (Environment env = new Environment(LocalVisionProbeServer.Mode.HELD_VERIFIED, TaskBudget.DEFAULT);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            RunControl other = new RunControl(env.lease.profileId);
            long otherEpoch = other.getExecutionEpoch();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<SavedVisionProbe.Outcome> held = worker.submit(() ->
                    SavedVisionProbe.process().test(env.context, env.lease, other, otherEpoch));
            try {
                assertTrue(env.server.awaitRequests(1, 10_000));
                env.startRequested();
                await("The task did not preserve the actual competing probe's admission", () -> {
                    env.server.assertHealthy();
                    return env.controller.getState().visualQualification == AgentController.VisualQualification.BUSY;
                });
                assertFalse(other.isCancelled());
                // The structured round may already be waiting in HTTP's socket
                // backlog; it is not a second probe and must count normally.
                env.server.releaseResponse();
                assertEquals(SavedVisionProbe.Status.VERIFIED, held.get(10, TimeUnit.SECONDS).status);
                AgentController.State pending = env.awaitApproval(AgentController.VisualQualification.BUSY);
                SavedVisionProbe.Verification record = SavedVisionProbe.process().current(env.lease);
                assertNotNull(record);
                assertTrue(record.isCurrentFor(env.lease, other, otherEpoch));
                assertFalse(record.isCurrentFor(env.lease, env.owner, env.owner.getExecutionEpoch()));
                assertEquals(1, env.server.imageRequestCount());
                assertEquals(env.server.taskRequestCount(), pending.usage.consumed.modelRounds);
                assertEquals(1, other.budgetSnapshot().modelRounds);
                assertTrue(pending.visualRequested);
                env.assertStructuredOnlyAndNoDispatch(trace);
                screenshots.capture("competing-real-probe-is-not-adopted-as-this-task-qualification");
            } finally {
                other.cancel(); env.server.releaseResponse();
                worker.shutdown();
                assertTrue("Competing real probe worker did not finish", worker.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void legacyTypedAndStringScreenshotRequestsCannotForgeCaptureIdentityOrStartAnyWork() throws Exception {
        try (Environment env = new Environment(LocalVisionProbeServer.Mode.VERIFIED, TaskBudget.DEFAULT)) {
            SettingsStore.SavedSettings saved = new SettingsStore(env.context).load();
            AndroidDeviceTools legacy = new AndroidDeviceTools(env.device.service,
                    Collections.singleton(DeviceHarness.FIXTURE), saved.grants, saved.executionMode, env.lease,
                    (description, active, epoch) -> { throw new AssertionError("A rejected image request asked for approval"); });
            RunControl owner = env.device.control;
            String revision = DeviceHarness.string(env.device.observe(), "revision");
            assertActualScreenshotPermissionClosed(env);
            CaptureQueueBaseline baseline = new CaptureQueueBaseline();
            long bytes = ImageWorkset.process().reservedBytes();
            assertFalse(legacy.availableTools().contains("take_screenshot"));
            try (LocalToolResult rejected = legacy.execute(imageRequest(owner, "legacy-typed", revision), owner)) {
                assertRejectedImage(rejected, "CAPABILITY_UNAVAILABLE");
            }
            JsonObject forgedArguments = imageArguments(revision);
            forgedArguments.addProperty("taskId", owner.getTaskId());
            forgedArguments.addProperty("callId", "not-a-local-request");
            forgedArguments.addProperty("profileId", owner.getProfileId());
            forgedArguments.addProperty("executionEpoch", owner.getExecutionEpoch());
            // Supplying trusted request metadata as model arguments cannot turn
            // either legacy overload into the request-aware image route.
            assertRejectedMetadata(legacy.execute("take_screenshot", forgedArguments, owner), "CAPABILITY_UNAVAILABLE");
            assertRejectedMetadata(legacy.execute("take_screenshot", forgedArguments, owner,
                    owner.getExecutionEpoch()), "CAPABILITY_UNAVAILABLE");
            try (LocalToolResult unknown = legacy.execute(new ToolRequest(ToolCatalog.PROTOCOL_VERSION,
                    owner.getTaskId(), "unknown-image-tool", owner.getExecutionEpoch(), owner.getProfileId(),
                    "capture_unregistered_page", imageArguments(revision)), owner)) {
                assertRejectedImage(unknown, "INVALID_ARGUMENT");
            }
            if (Build.VERSION.SDK_INT < 34) assertUnsupportedWindowCapture(legacy, owner);
            baseline.assertUnchanged();
            assertEquals(bytes, ImageWorkset.process().reservedBytes());
            assertEquals("No model request, including a probe, belongs to this legacy check", 0, env.server.requestCount());
            assertEquals(0, owner.getDispatchAttempts());
            assertFalse(owner.isCancelled()); assertFalse(owner.isPaused());
            assertFalse(ImageReviewPresentationCoordinator.hasHost());
            env.server.assertHealthy();
            screenshots.capture(Build.VERSION.SDK_INT < 34
                    ? "legacy-image-route-api-below-34-unsupported-zero-capture-work-and-http"
                    : "legacy-image-route-permission-closed-zero-capture-work-and-http");
        }
    }

    @Test public void actualTaskQualificationCannotOverrideClosedPermissionOrAcceptAnotherRequestOwner() throws Exception {
        try (Environment env = new Environment(LocalVisionProbeServer.Mode.VERIFIED, TaskBudget.DEFAULT);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            env.startRequested();
            AgentController.State pending = env.awaitApproval(AgentController.VisualQualification.SYNTHETIC_VERIFIED);
            long epoch = env.originalStartEpoch;
            SavedVisionProbe.Verification verification = SavedVisionProbe.process().current(env.lease);
            assertNotNull(verification);
            assertTrue(verification.isCurrentFor(env.lease, env.owner, epoch));
            assertActualScreenshotPermissionClosed(env);
            // Read the original Controller holder/generation and ask its actual
            // factory for the same closure; never write a field, fake a record,
            // supply a constant-true qualification, or change a capability bit.
            DisclosureGuard qualification = actualTaskQualification(env);
            assertTrue(qualification.isCurrent(env.owner, epoch));
            SettingsStore.SavedSettings saved = new SettingsStore(env.context).load();
            AndroidDeviceTools qualified = new AndroidDeviceTools(env.device.service,
                    Collections.singleton(DeviceHarness.FIXTURE), saved.grants, saved.executionMode, env.lease,
                    (description, active, expectedEpoch) -> { throw new AssertionError("Unavailable capture requested approval"); },
                    env.owner, epoch, qualification);
            qualified.prepareVisualSupport();
            assertTrue("The real diagnostic must remain current despite unavailable capture", qualification.isCurrent(env.owner, epoch));
            assertFalse(qualified.availableTools().contains("take_screenshot"));
            assertFalse(qualified.availableTools().contains("tap"));
            assertFalse(qualified.availableTools().contains("swipe"));
            // This case tests refusal before capture/page validation, not support
            // for the synthetic fixture as a production visual page.
            String revision = "not-consumed-by-unavailable-route";
            CaptureQueueBaseline baseline = new CaptureQueueBaseline();
            await("Completed synthetic HTTP payload still owned", () -> ImageWorkset.process().reservedBytes() == 0);
            int requests = env.server.requestCount();
            assertEquals(1, env.server.imageRequestCount());
            try (LocalToolResult denied = qualified.execute(imageRequest(env.owner, "qualified-capability-closed", revision), env.owner)) {
                assertRejectedImage(denied, "CAPABILITY_UNAVAILABLE");
            }
            RunControl foreign = new RunControl(env.lease.profileId);
            try {
                assertEquals("Identity check uses equal epochs, not an incidental stale-epoch rejection", epoch, foreign.getExecutionEpoch());
                assertFalse(qualification.isCurrent(foreign, foreign.getExecutionEpoch()));
                try (LocalToolResult wrongOwner = qualified.execute(imageRequest(env.owner, "foreign-owner", revision), foreign)) {
                    assertRejectedImage(wrongOwner, "INVALID_ARGUMENT");
                }
                ToolRequest[] malformed = {
                        new ToolRequest(ToolCatalog.PROTOCOL_VERSION + 1, env.owner.getTaskId(), "wrong-version", epoch,
                                env.owner.getProfileId(), "take_screenshot", imageArguments(revision)),
                        new ToolRequest(ToolCatalog.PROTOCOL_VERSION, foreign.getTaskId(), "wrong-task", epoch,
                                env.owner.getProfileId(), "take_screenshot", imageArguments(revision)),
                        new ToolRequest(ToolCatalog.PROTOCOL_VERSION, env.owner.getTaskId(), "wrong-profile", epoch,
                                "unrelated-profile", "take_screenshot", imageArguments(revision)),
                        new ToolRequest(ToolCatalog.PROTOCOL_VERSION, env.owner.getTaskId(), "not a valid local call id", epoch,
                                env.owner.getProfileId(), "take_screenshot", imageArguments(revision))
                };
                for (ToolRequest request : malformed) try (LocalToolResult rejected = qualified.execute(request, env.owner)) {
                    assertRejectedImage(rejected, "INVALID_ARGUMENT");
                }
                assertFalse(foreign.isCancelled()); assertEquals(0, foreign.getDispatchAttempts());
            } finally { foreign.cancel(); }
            if (Build.VERSION.SDK_INT < 34) assertUnsupportedWindowCapture(qualified, env.owner);
            baseline.assertUnchanged();
            assertEquals(0, ImageWorkset.process().reservedBytes());
            assertEquals("Rejected captures must not send another probe or task request", requests, env.server.requestCount());
            assertEquals(1, env.server.imageRequestCount());
            assertEquals(pending.approvalId, env.controller.getState().approvalId);
            assertTrue(qualification.isCurrent(env.owner, epoch));
            assertFalse(ImageReviewPresentationCoordinator.hasHost());
            env.assertStructuredOnlyAndNoDispatch(trace);
            screenshots.capture(Build.VERSION.SDK_INT < 34
                    ? "real-task-qualification-api-below-34-unsupported-zero-capture-work"
                    : "real-task-qualification-does-not-open-permission-or-accept-foreign-identity");
        }
    }

    private static ToolRequest imageRequest(RunControl owner, String callId, String revision) {
        return new ToolRequest(ToolCatalog.PROTOCOL_VERSION, owner.getTaskId(), callId, owner.getExecutionEpoch(),
                owner.getProfileId(), "take_screenshot", imageArguments(revision));
    }

    private static JsonObject imageArguments(String revision) {
        JsonObject arguments = new JsonObject(); arguments.addProperty("expectedRevision", revision); return arguments;
    }

    private static void assertRejectedImage(LocalToolResult result, String code) throws Exception {
        assertNotNull(result);
        assertRejectedMetadata(result.getResult(), code);
        // Read-only ownership check, with close in every caller even on assertion failure.
        assertNull("Rejected result retained a native image owner", read(result, "image"));
        assertFalse(result.getResult().has("imageDataUrl"));
    }

    private static void assertRejectedMetadata(JsonObject result, String code) {
        assertNotNull(result);
        assertEquals(code, result.get("code").getAsString());
        for (String flag : new String[]{"ok", "submitted", "dispatchSubmitted", "dispatchAccepted"}) {
            assertTrue(flag, result.has(flag) && result.get(flag).isJsonPrimitive()
                    && result.getAsJsonPrimitive(flag).isBoolean());
            assertFalse(flag, result.get(flag).getAsBoolean());
        }
    }

    private static void assertActualScreenshotPermissionClosed(Environment env) {
        env.main(() -> {
            assertNotNull(env.device.service.getServiceInfo());
            assertEquals(0, env.device.service.getServiceInfo().getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
            assertFalse(env.device.service.hasWindowCaptureCapability());
        });
    }

    private static void assertUnsupportedWindowCapture(AndroidDeviceTools tools, RunControl owner) throws Exception {
        try {
            tools.checkWindowCaptureRequestCurrent(owner, owner.getExecutionEpoch());
            fail("API below 34 must report unsupported before acquiring a native capture slot");
        } catch (AndroidDeviceTools.WindowCaptureException unavailable) {
            assertEquals(AndroidDeviceTools.WindowCaptureFailure.UNSUPPORTED_API, unavailable.code);
        }
    }

    private static DisclosureGuard actualTaskQualification(Environment env) throws Exception {
        Object lock = read(env.controller, "lock");
        synchronized (lock) {
            Object holder = read(env.controller, "taskVisual");
            assertNotNull(holder);
            assertSame(env.owner, read(holder, "owner"));
            assertSame(env.lease, read(holder, "configuration"));
            Method factory = AgentController.class.getDeclaredMethod("visualQualificationGuardFor", long.class, holder.getClass());
            factory.setAccessible(true);
            return (DisclosureGuard) factory.invoke(env.controller, (long) read(env.controller, "generation"), holder);
        }
    }

    /** Counter-only reflection; no backend/worker replacement or native capability bypass. */
    private static final class CaptureQueueBaseline {
        final ThreadPoolExecutor worker;
        final long admitted, completed;
        CaptureQueueBaseline() throws Exception {
            Field field = WindowBufferCapturer.class.getDeclaredField("WORKER"); field.setAccessible(true);
            worker = (ThreadPoolExecutor) field.get(null);
            assertFalse(WindowBufferCapturer.hasActiveCapture());
            await("Prior native capture worker still active", () -> worker.getActiveCount() == 0 && worker.getQueue().isEmpty());
            admitted = worker.getTaskCount(); completed = worker.getCompletedTaskCount();
        }
        void assertUnchanged() {
            // Every production native submission runs on this fixed executor.
            // Unchanged admission counts are stronger than sampling an empty slot
            // after a short accidental capture could already have completed.
            assertEquals("Rejected route admitted a native capture task", admitted, worker.getTaskCount());
            assertEquals(completed, worker.getCompletedTaskCount());
            assertEquals(0, worker.getActiveCount()); assertTrue(worker.getQueue().isEmpty());
            assertFalse(WindowBufferCapturer.hasActiveCapture());
        }
    }

    private static final class Environment implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final AgentController controller = AgentController.get(context);
        final LocalControllerModelServer planner;
        final LocalVisionProbeServer server;
        final ControllerTestSettings settings;
        final SettingsStore.ConfigurationLease lease;
        final TaskBudget budget;
        final DeviceHarness device;
        RunControl owner;
        long originalStartEpoch;

        Environment(LocalVisionProbeServer.Mode mode, TaskBudget budget) throws Exception {
            this.budget = budget;
            assertFalse("Do not replace an existing task", controller.getState().running);
            LocalControllerModelServer createdPlanner = null;
            LocalVisionProbeServer createdServer = null;
            ControllerTestSettings createdSettings = null;
            DeviceHarness createdDevice = null;
            try {
                settle(); main(controller::clearCompletedTask); instrumentation.waitForIdleSync();
                await("Previous HTTP image payload did not drain", () -> ImageWorkset.process().reservedBytes() == 0);
                createdPlanner = LocalControllerModelServer.forPlanningOnly(LocalControllerModelServer.Scenario.QUALIFICATION_PENDING);
                createdServer = new LocalVisionProbeServer(mode, createdPlanner);
                ModelSettings model = new ModelSettings(createdServer.endpoint(), LocalVisionProbeServer.KEY,
                        LocalVisionProbeServer.MODEL, 1024);
                createdSettings = new ControllerTestSettings(context, model, ActionConfirmationPolicy.Mode.CONFIRM_ALL);
                SettingsStore store = new SettingsStore(context);
                SettingsStore.SavedSettings saved = store.load(); saved.taskBudget = budget;
                lease = store.save(saved);
                settle(); instrumentation.waitForIdleSync();
                assertNull(controller.getTaskUsage());
                createdDevice = new DeviceHarness();
                planner = createdPlanner; server = createdServer; settings = createdSettings; device = createdDevice;
                device.awaitUiIdle();
            } catch (Exception | AssertionError failure) {
                if (createdDevice != null) try { createdDevice.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                if (createdSettings != null) try { createdSettings.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                if (createdServer != null) try { createdServer.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                if (createdPlanner != null) try { createdPlanner.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void startDefault() throws Exception { start(false, false); }
        void startRequested() throws Exception { start(true, false); }
        private void start(boolean requested, boolean pauseResumeWhileQueued) throws Exception {
            // Keep the original owner observable even if the one-round task finishes
            // quickly. This harmless queue hold changes no control, guard or clock.
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<?> barrier = executor().submit(() -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Task setup queue hold expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
            });
            try {
                assertTrue("The real controller worker did not enter setup hold", entered.await(5, TimeUnit.SECONDS));
                main(() -> {
                    String task = LocalVisionProbeServer.PRIVATE_TASK + ": inspect the synthetic fixture and request its increment; never approve it.";
                    if (requested) controller.start(settings.model, task, Collections.singleton(DeviceHarness.FIXTURE),
                            settings.executionMode, budget, lease, true);
                    else controller.start(settings.model, task, Collections.singleton(DeviceHarness.FIXTURE),
                            settings.executionMode, budget, lease);
                    try { owner = (RunControl) read(controller, "control"); }
                    catch (Exception error) { throw new AssertionError(error); }
                    assertNotNull("Capture the actual original task owner, never create a substitute", owner);
                    originalStartEpoch = owner.getExecutionEpoch();
                    if (pauseResumeWhileQueued) {
                        controller.pauseResume();
                        assertTrue(controller.getState().paused);
                        controller.pauseResume();
                        assertFalse(controller.getState().paused);
                    }
                });
            } catch (Exception | AssertionError failed) {
                // Do not release an uncancelled queued task after setup itself fails.
                main(controller::stop);
                throw failed;
            } finally { release.countDown(); barrier.get(10, TimeUnit.SECONDS); }
        }

        AgentController.State awaitApproval(AgentController.VisualQualification expected) {
            await("The real structured task did not reach its protected confirmation", () -> {
                server.assertHealthy(); planner.assertHealthy();
                AgentController.State state = controller.getState();
                return state.running && !state.approvalId.isEmpty() && state.visualQualification == expected;
            });
            return controller.getState();
        }

        AgentController.State awaitStopped() throws Exception {
            await("The original task did not stop", () -> !controller.getState().running);
            settle(); instrumentation.waitForIdleSync();
            AgentController.State state = controller.getState();
            assertFalse(state.running); assertNotNull(state.usage); assertTrue(state.usage.finished);
            server.assertHealthy(); planner.assertHealthy();
            return state;
        }

        void assertStructuredOnlyAndNoDispatch(PerformanceTrace.Session trace) {
            assertEquals(0, trace.overwrittenCount());
            for (PerformanceTrace.Event event : trace.events())
                assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
            assertEquals(0, planner.acceptedWriteCount());
            assertEquals(0, owner.getDispatchAttempts());
            assertEquals(0, server.toolRequestCount());
            main(() -> assertEquals("Synthetic qualification must not enable production screenshot permission", 0,
                    device.service.getServiceInfo().getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT));
            server.assertHealthy(); planner.assertHealthy();
        }

        void main(Runnable operation) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { operation.run(); }
                catch (RuntimeException | AssertionError error) { failure.set(error); }
            });
            Throwable error = failure.get();
            if (error instanceof AssertionError) throw (AssertionError) error;
            if (error instanceof RuntimeException) throw (RuntimeException) error;
        }

        private ThreadPoolExecutor executor() throws Exception { return (ThreadPoolExecutor) read(controller, "worker"); }
        void settle() throws Exception { TaskUsageTestHarness.drainWorker(executor()); }

        @Override public void close() throws Exception {
            try {
                main(controller::stop); server.releaseResponse(); settle();
                await("Actual HTTP payload owner did not drain", () -> ImageWorkset.process().reservedBytes() == 0);
                main(controller::clearCompletedTask); instrumentation.waitForIdleSync();
            } finally {
                try { settings.close(); }
                finally { try { server.close(); }
                    finally { try { planner.close(); } finally { device.close(); } } }
            }
        }
    }

    private static Object read(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }
}
