package dev.agentos.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.TaskResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real task completion and Activity.recreate races; no rotation or process-death claim. */
@RunWith(AndroidJUnit4.class)
public final class TaskInputDraftInstrumentedTest {
    private static final String GOAL = "Synthetic task input that must not return after its task ends";
    private static final String NEXT_GOAL = "Synthetic new unsent task input";
    private static final String DRAFT_MODEL = "synthetic-unsaved-task-input-model";
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_task_input_draft_capture_not_attempted");

    @Test public void actualTaskEndBeforeReplacementReadCannotRestoreItsOldGoal() throws Exception {
        try (DraftUi ui = new DraftUi()) {
            PendingTask pending = ui.startThroughUi();
            Map<String, ?> persisted = ui.preferences.getAll();
            Host replacement = ui.recreate(true);
            ui.main(() -> {
                assertEquals(GOAL, field(replacement.retained, "task"));
                assertSame(replacement.retained, field(replacement.activity, "retainedDraft"));
                AgentController.TaskInputState retained = (AgentController.TaskInputState) field(replacement.retained, "taskInput");
                assertTrue(retained.matches(pending.input));
                assertTrue(ui.controller.getState().running);
                assertTrue(bool(replacement.activity, "busy"));
                assertFalse(bool(replacement.activity, "ready"));
            });
            ui.rejectAndAwaitEnd(pending);
            ui.main(() -> {
                assertTrue(bool(replacement.activity, "busy"));
                assertEquals("", ui.text(R.id.task_prompt));
                assertEquals("The stale retained object must still be pending at the tested boundary",
                        GOAL, field(replacement.retained, "task"));
            });
            AgentController.TaskInputState ended = ui.controller.getTaskInputState();
            replacement.gate.releaseAndJoin();
            ui.awaitReady(replacement);
            ui.main(() -> {
                assertEquals("A late real configuration read must not resurrect the finished task", "", ui.text(R.id.task_prompt));
                assertNull(field(replacement.activity, "retainedDraft"));
                assertEquals(LocalControllerModelServer.MODEL, ui.text(R.id.settings_model));
                ui.assertBudgetTexts("030", "0100", "0600");
                assertTrue(ended.matches(ui.controller.getTaskInputState()));
                ui.assertSecure();
            });
            ui.assertEnded(pending);
            assertEquals(persisted, ui.preferences.getAll());
            screenshots.capture("protected-ended-task-not-restored-by-late-read");
        }
    }

    @Test public void emptyHistoryClearInvalidatesBothBackgroundCaptureAndDelayedRestore() throws Exception {
        try (DraftUi ui = new DraftUi()) {
            NoWork baseline = new NoWork(ui);
            ui.seedUnsentDraft();
            Host old = ui.current;
            ui.main(() -> assertTrue(old.activity.moveTaskToBack(true)));
            ui.await(() -> ui.read(() -> old.stopped && !old.resumed), 5_000,
                    "The old host must actually stop and remove its Controller listener");
            ui.main(() -> {
                assertEquals(NEXT_GOAL, ui.text(R.id.task_prompt));
                assertNull(ui.controller.getState().usage);
                AgentController.TaskInputState before = ui.controller.getTaskInputState();
                assertTrue(((AgentController.TaskInputState) field(old.activity, "taskInputState")).matches(before));
                ui.controller.clearCompletedTask();
                assertFalse(before.matches(ui.controller.getTaskInputState()));
                assertNull(ui.controller.getState().usage);
                assertEquals("The background host has not received the clear publication", NEXT_GOAL, ui.text(R.id.task_prompt));
                // Directly exercise the real lifecycle capture method while this host
                // is stopped. This segment is not a rotation or recreation claim.
                Object captured = old.activity.onRetainNonConfigurationInstance();
                assertNotNull(captured);
                assertEquals("A stale host must not give old text a new input identity", "", field(captured, "task"));
                assertEquals(NEXT_GOAL, ui.text(R.id.task_prompt));
            });
            ui.returnToSameHost(old);
            ui.main(() -> assertEquals("", ui.text(R.id.task_prompt)));
            ui.seedUnsentDraft();
            Host replacement = ui.recreate(true);
            ui.main(() -> {
                assertEquals(NEXT_GOAL, field(replacement.retained, "task"));
                for (int clear = 0; clear < 2; clear++) {
                    AgentController.TaskInputState before = ui.controller.getTaskInputState();
                    assertNull(ui.controller.getState().usage);
                    ui.click(R.id.clear_task);
                    assertFalse("Even empty-to-empty clear must revoke the previous input lifetime",
                            before.matches(ui.controller.getTaskInputState()));
                    assertNull(ui.controller.getState().usage);
                    assertEquals("", ui.text(R.id.task_prompt));
                }
                assertTrue(bool(replacement.activity, "busy"));
            });
            replacement.gate.releaseAndJoin();
            ui.awaitReady(replacement);
            ui.main(() -> {
                assertEquals("", ui.text(R.id.task_prompt));
                ui.assertModelAndBudgetDraft();
                ui.assertSecure();
            });
            baseline.assertUnchanged(ui);
            screenshots.capture("protected-empty-history-clear-rejects-old-input-only");
        }
    }

    @Test public void newUnsentGoalAfterAnEndedTaskSurvivesRecreationWithoutSavingOrRequests() throws Exception {
        try (DraftUi ui = new DraftUi()) {
            PendingTask pending = ui.startThroughUi();
            ui.rejectAndAwaitEnd(pending);
            NoWork baseline = new NoWork(ui);
            AgentController.TaskInputState ended = ui.controller.getTaskInputState();
            ui.seedUnsentDraft();
            Host replacement = ui.recreate(false);
            ui.main(() -> {
                assertEquals(NEXT_GOAL, field(replacement.retained, "task"));
                assertEquals(NEXT_GOAL, ui.text(R.id.task_prompt));
                ui.assertModelAndBudgetDraft();
                assertTrue("Historical completion alone must not erase a newer unsent draft",
                        ended.matches(ui.controller.getTaskInputState()));
                assertEquals(pending.owner.getTaskId(), ui.controller.getState().usage.taskId);
                ui.assertSecure();
            });
            baseline.assertUnchanged(ui);
            ui.assertEnded(pending);
            screenshots.capture("protected-new-unsent-draft-retained-after-historical-end");
        }
    }

    private static final class PendingTask {
        final RunControl owner;
        final AgentController.TaskInputState input;
        final String approval;
        final int requests;
        PendingTask(RunControl owner, AgentController.State state, int requests) {
            this.owner = owner; input = state.taskInput; approval = state.approvalId; this.requests = requests;
        }
    }

    private static final class NoWork {
        final Map<String, ?> persisted;
        final int requests;
        final long generation, tasks;
        NoWork(DraftUi ui) throws Exception {
            persisted = ui.preferences.getAll(); requests = ui.task.server.requestCount();
            synchronized (field(ui.controller, "lock")) {
                assertNull(field(ui.controller, "control"));
                generation = (Long) field(ui.controller, "generation");
            }
            tasks = ui.task.worker().getTaskCount();
        }
        void assertUnchanged(DraftUi ui) throws Exception {
            ui.task.server.assertHealthy();
            assertEquals(persisted, ui.preferences.getAll());
            assertEquals(requests, ui.task.server.requestCount());
            synchronized (field(ui.controller, "lock")) {
                assertNull(field(ui.controller, "control"));
                assertEquals(generation, ((Long) field(ui.controller, "generation")).longValue());
            }
            assertEquals(tasks, ui.task.worker().getTaskCount());
            assertEquals(0, ui.task.worker().getActiveCount());
            assertTrue(ui.task.worker().getQueue().isEmpty());
            ui.main(() -> assertNull(field(ui.current.activity, "modelProbe")));
        }
    }

    private static final class Host {
        final MainActivity activity;
        ExecutorService storage, diagnostics;
        Object retained;
        ReadGate gate;
        boolean resumed, stopped, destroyed, configurationDestroy, finishRequested;
        Host(MainActivity activity) { this.activity = activity; }
    }

    /** Queued before onCreate's real read; never blocks the main thread or replaces the executor. */
    private static final class ReadGate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final Future<?> future;
        ReadGate(ExecutorService storage) {
            future = storage.submit(() -> {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("Replacement storage gate exceeded its bound");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Replacement storage gate was interrupted", interrupted);
                }
            });
        }
        void releaseAndJoin() throws Exception { release.countDown(); future.get(5, TimeUnit.SECONDS); }
    }

    private static final class DraftUi implements AutoCloseable, Application.ActivityLifecycleCallbacks {
        final TaskUsageTestHarness task = new TaskUsageTestHarness(LocalControllerModelServer.Scenario.REJECT_CLICK, TaskBudget.DEFAULT);
        final AgentController controller = task.controller;
        final Application application = (Application) task.device.context.getApplicationContext();
        final SharedPreferences preferences;
        final List<Host> hosts = new ArrayList<>();
        Host current;
        boolean registered, gateNext;
        int expectedHosts;
        String lifecycleFailure;

        DraftUi() throws Exception {
            String profile = SettingsStore.currentProfileId(task.device.context);
            preferences = task.device.context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            try {
                main(() -> { application.registerActivityLifecycleCallbacks(this); registered = true; });
                expectedHosts = 1;
                MainActivity opened = (MainActivity) task.device.instrumentation.startActivitySync(
                        new Intent(task.device.context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                current = read(() -> host(opened));
                assertNotNull(current);
                awaitReady(current);
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (Exception | AssertionError cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void main(Runnable action) { task.onMain(action); }
        <T> T read(Supplier<T> action) {
            AtomicReference<T> result = new AtomicReference<>(); main(() -> result.set(action.get())); return result.get();
        }
        EditText editor(int id) { return current.activity.findViewById(id); }
        String text(int id) { return ((TextView) current.activity.findViewById(id)).getText().toString(); }
        void click(int id) {
            View button = current.activity.findViewById(id);
            assertTrue("The real action must be attached, shown and enabled", button.isAttachedToWindow() && button.isShown() && button.isEnabled());
            assertTrue("The actual click listener must handle the action", button.performClick());
        }
        void assertSecure() {
            assertTrue((current.activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertFalse(editor(R.id.task_prompt).isSaveEnabled());
        }
        void await(BooleanSupplier condition, long millis, String message) {
            long deadline = SystemClock.elapsedRealtime() + millis;
            do {
                task.server.assertHealthy();
                main(() -> assertNull("Bounded Activity lifecycle failure", lifecycleFailure));
                if (condition.getAsBoolean()) return;
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            fail(message);
        }
        void awaitReady(Host expected) {
            await(() -> read(() -> expected.resumed && !expected.destroyed && expected.activity.hasWindowFocus()
                    && bool(expected.activity, "ready") && !bool(expected.activity, "busy")
                    && !bool(expected.activity, "storageDamaged")), 10_000, "Actual host and saved configuration did not become ready");
            main(this::assertSecure);
        }
        void seedUnsentDraft() {
            main(() -> {
                assertTrue(editor(R.id.task_prompt).isEnabled());
                if (!bool(current.activity, "modelExpanded")) click(R.id.configuration_toggle);
                if (!bool(current.activity, "budgetExpanded")) click(R.id.task_budget_toggle);
                editor(R.id.task_prompt).setText(NEXT_GOAL);
                editor(R.id.settings_model).setText(DRAFT_MODEL);
                editor(R.id.task_budget_rounds).setText("017");
                editor(R.id.task_budget_tools).setText("063");
                editor(R.id.task_budget_seconds).setText("0420");
                assertModelAndBudgetDraft();
            });
        }
        void assertModelAndBudgetDraft() {
            assertEquals(DRAFT_MODEL, text(R.id.settings_model));
            assertBudgetTexts("017", "063", "0420");
            assertTrue(bool(current.activity, "modelExpanded"));
            assertTrue(bool(current.activity, "budgetExpanded"));
        }
        void assertBudgetTexts(String rounds, String tools, String seconds) {
            assertEquals(rounds, text(R.id.task_budget_rounds));
            assertEquals(tools, text(R.id.task_budget_tools));
            assertEquals(seconds, text(R.id.task_budget_seconds));
        }

        PendingTask startThroughUi() throws Exception {
            Host original = current;
            try (TaskUsageTestHarness.WorkerHold hold = new TaskUsageTestHarness.WorkerHold(task)) {
                main(() -> {
                    editor(R.id.task_prompt).setText(GOAL);
                    if (!bool(current.activity, "budgetExpanded")) click(R.id.task_budget_toggle);
                    editor(R.id.task_budget_rounds).setText("030");
                    editor(R.id.task_budget_tools).setText("0100");
                    editor(R.id.task_budget_seconds).setText("0600");
                    click(R.id.start_task);
                });
                AtomicReference<Button> trust = new AtomicReference<>();
                await(() -> read(() -> {
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        Button candidate = root.findViewById(android.R.id.button1);
                        if (candidate != null && "信任此连接".contentEquals(candidate.getText())
                                && root.isAttachedToWindow() && root.hasWindowFocus() && candidate.isShown()) {
                            trust.set(candidate); return true;
                        }
                    }
                    return false;
                }), 5_000, "The real local-HTTP trust dialog did not obtain focus");
                main(() -> { assertTrue(trust.get().isEnabled()); assertTrue(trust.get().performClick()); });
                await(() -> controller.getState().running && read(() -> !bool(original.activity, "busy")),
                        10_000, "The actual Start action did not admit a task after its encrypted save");
                main(() -> {
                    AgentController.State running = controller.getState();
                    assertTrue(running.running);
                    assertEquals("The real starting host must bind and retain its admitted input", GOAL, text(R.id.task_prompt));
                    assertTrue(((AgentController.TaskInputState) field(original.activity, "taskInputState")).matches(running.taskInput));
                    assertFalse(editor(R.id.task_prompt).isEnabled());
                });
                assertEquals("The held real worker has not contacted HTTP yet", 0, task.server.requestCount());
                task.device.launch("FixtureActivity");
                hold.release.countDown();
            }
            task.awaitApproval();
            AgentController.State pending = controller.getState();
            PendingTask result = new PendingTask(task.owner(), pending, task.server.requestCount());
            assertEquals(0, result.owner.getDispatchAttempts());
            returnToSameHost(original);
            main(() -> {
                assertEquals(GOAL, text(R.id.task_prompt));
                assertTrue(result.input.matches(controller.getTaskInputState()));
            });
            return result;
        }

        void returnToSameHost(Host original) {
            main(() -> task.device.context.startActivity(new Intent(task.device.context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            await(() -> read(() -> original.resumed && !original.stopped && original.activity.hasWindowFocus()),
                    5_000, "The original Activity did not return to the foreground");
            main(() -> assertSame("Returning must reuse the original host", original, hosts.get(hosts.size() - 1)));
            current = original;
        }

        Host recreate(boolean gated) throws Exception {
            Host old = current;
            main(() -> { expectedHosts++; gateNext = gated; old.activity.recreate(); });
            await(() -> read(() -> hosts.size() > 1 && hosts.get(hosts.size() - 1) != old
                    && hosts.get(hosts.size() - 1).resumed && old.destroyed), 10_000,
                    "Activity.recreate did not produce a distinct resumed host and destroy its predecessor");
            current = read(() -> hosts.get(hosts.size() - 1));
            main(() -> { assertTrue(old.configurationDestroy); assertNotNull(current.retained); assertSecure(); });
            if (gated) {
                assertNotNull(current.gate);
                assertTrue("The actual replacement storage worker must enter its gate", current.gate.entered.await(5, TimeUnit.SECONDS));
                main(() -> { assertTrue(bool(current.activity, "busy")); assertFalse(bool(current.activity, "ready")); });
            } else awaitReady(current);
            return current;
        }

        void rejectAndAwaitEnd(PendingTask pending) throws Exception {
            main(() -> {
                assertEquals(pending.approval, controller.getState().approvalId);
                click(R.id.approval_deny);
            });
            task.awaitStopped();
            await(() -> read(() -> "".equals(text(R.id.task_prompt))), 5_000,
                    "Actual completion publication did not clear the task editor");
            assertEnded(pending);
        }
        void assertEnded(PendingTask pending) {
            task.server.assertHealthy();
            AgentController.State ended = controller.getState();
            assertFalse(ended.running);
            assertNotNull(ended.usage);
            assertTrue(ended.usage.finished);
            assertEquals(pending.owner.getTaskId(), ended.usage.taskId);
            assertEquals(TaskResult.Kind.FAILED, ended.resultKind);
            assertEquals("PERMISSION_DENIED", ended.resultCode);
            assertTrue(ended.approvalId.isEmpty());
            assertFalse(pending.input.matches(ended.taskInput));
            assertEquals(0, pending.owner.getDispatchAttempts());
            assertEquals(0, task.server.acceptedWriteCount());
            assertEquals(pending.requests, task.server.requestCount());
        }

        Host host(Activity activity) { for (Host host : hosts) if (host.activity == activity) return host; return null; }
        void reject(String reason) { if (lifecycleFailure == null) lifecycleFailure = reason; }
        @Override public void onActivityPreCreated(Activity activity, Bundle saved) {
            if (!(activity instanceof MainActivity)) return;
            // Record ownership before reflection so even an unexpected setup
            // failure cannot lose the Activity that must later be finished.
            Host host = new Host((MainActivity) activity); hosts.add(host);
            try {
                host.storage = (ExecutorService) field(activity, "storageWorker");
                host.diagnostics = (ExecutorService) field(activity, "diagnosticsWorker");
                if (hosts.size() > 6) reject("unexpected_extra_hosts");
                if (gateNext) { gateNext = false; host.gate = new ReadGate(host.storage); }
            } catch (RuntimeException | AssertionError failure) { reject("precreate_storage_gate_failed"); }
        }
        @Override public void onActivityPostCreated(Activity activity, Bundle saved) {
            Host host = host(activity); if (host == null) return;
            try {
                host.retained = activity.getLastNonConfigurationInstance();
                if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0)
                    reject("created_host_not_secure");
            } catch (RuntimeException | AssertionError failure) { reject("postcreate_snapshot_failed"); }
        }
        @Override public void onActivityCreated(Activity activity, Bundle saved) { }
        @Override public void onActivityStarted(Activity activity) { Host host = host(activity); if (host != null) host.stopped = false; }
        @Override public void onActivityResumed(Activity activity) { Host host = host(activity); if (host != null) host.resumed = true; }
        @Override public void onActivityPaused(Activity activity) { Host host = host(activity); if (host != null) host.resumed = false; }
        @Override public void onActivityStopped(Activity activity) { Host host = host(activity); if (host != null) host.stopped = true; }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) { }
        @Override public void onActivityDestroyed(Activity activity) {
            Host host = host(activity); if (host != null) {
                host.destroyed = true; host.resumed = false; host.configurationDestroy = activity.isChangingConfigurations();
            }
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            AtomicReference<Throwable> finishFailure = new AtomicReference<>();
            List<Host> owned = new ArrayList<>();
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            try {
                boolean allDestroyed;
                do {
                    allDestroyed = read(() -> {
                        boolean done = hosts.size() >= expectedHosts;
                        for (Host host : hosts) {
                            if (!owned.contains(host)) owned.add(host);
                            if (host.gate != null) host.gate.release.countDown();
                            if (!host.destroyed && !host.finishRequested) {
                                host.finishRequested = true;
                                try { host.activity.finish(); }
                                catch (RuntimeException | AssertionError error) {
                                    finishFailure.set(append(finishFailure.get(), error));
                                }
                            }
                            done &= host.destroyed;
                        }
                        return done;
                    });
                    if (!allDestroyed) SystemClock.sleep(20);
                } while (!allDestroyed && SystemClock.elapsedRealtime() < deadline);
                if (!allDestroyed) throw new AssertionError("Owned Activity cleanup exceeded its shared bound");
            } catch (Exception | AssertionError cleanup) { failure = append(failure, cleanup); }
            if (finishFailure.get() != null) failure = append(failure, finishFailure.get());
            for (Host host : owned) {
                try { if (host.gate != null) host.gate.releaseAndJoin(); }
                catch (Exception | AssertionError cleanup) { failure = append(failure, cleanup); }
                for (ExecutorService worker : new ExecutorService[]{host.storage, host.diagnostics}) {
                    try {
                        if (worker == null) throw new AssertionError("Owned Activity worker was not captured");
                        long remaining = Math.max(0, deadline - SystemClock.elapsedRealtime());
                        if (!worker.awaitTermination(remaining, TimeUnit.MILLISECONDS))
                            throw new AssertionError("Owned Activity worker did not terminate before settings restoration");
                    } catch (Exception | AssertionError cleanup) { failure = append(failure, cleanup); }
                }
            }
            try { main(() -> { if (registered) application.unregisterActivityLifecycleCallbacks(this); registered = false; }); }
            catch (Exception | AssertionError cleanup) { failure = append(failure, cleanup); }
            try { task.close(); } catch (Exception | AssertionError cleanup) { failure = append(failure, cleanup); }
            try { task.server.assertHealthy(); } catch (AssertionError cleanup) { failure = append(failure, cleanup); }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof AssertionError) throw (AssertionError) failure;
        }
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next); return first;
    }
    private static Object field(Object owner, String name) {
        try { return TaskUsageTestHarness.readField(owner, name); }
        catch (Exception unavailable) { throw new AssertionError("Cannot read bounded test state: " + name, unavailable); }
    }
    private static boolean bool(Object owner, String name) { return (Boolean) field(owner, name); }
}
