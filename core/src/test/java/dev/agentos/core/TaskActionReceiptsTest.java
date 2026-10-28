package dev.agentos.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static dev.agentos.core.TaskActionReceipts.*;

public class TaskActionReceiptsTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject accepted() {
        return json("{\"ok\":true,\"dispatchSubmitted\":true,\"dispatchAccepted\":true,\"verification\":\"UNVERIFIED\"}");
    }
    private static ToolRequest click(RunControl control, String id) {
        return new ToolRequest(1, control.getTaskId(), id, control.getExecutionEpoch(), control.getProfileId(), "click",
                json("{\"nodeId\":\"synthetic-private-node\",\"expectedRevision\":\"synthetic-private-revision\"}"));
    }
    private static TaskActionReceipts latest(List<TaskActionReceipts> snapshots) { return snapshots.get(snapshots.size() - 1); }
    private static Entry last(TaskActionReceipts snapshot) { return snapshot.entries.get(snapshot.entries.size() - 1); }
    private static void unknown(Entry entry) {
        assertEquals(Fact.UNKNOWN, entry.submitted); assertEquals(Fact.UNKNOWN, entry.accepted);
        assertEquals(Verification.UNKNOWN, entry.verification);
    }

    @Test public void actualDispatchPublishesImmutableWhitelistedEvidenceWithoutRequestOrResultContents() throws Exception {
        RunControl control = new RunControl("synthetic-private-profile");
        List<TaskActionReceipts> snapshots = new ArrayList<>();
        JsonObject metadata = accepted();
        metadata.addProperty("code", "SYNTHETIC_PRIVATE_ERROR");
        metadata.addProperty("note", "synthetic-private-device-text");
        metadata.add("data", json("{\"text\":\"synthetic-private-page\",\"package\":\"private.package\"}"));
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
            try (RunControl.DispatchTicket ignored = active.beginDispatch(active.getExecutionEpoch())) { return metadata; }
        }, snapshots::add)) {
            JsonObject result = dispatcher.dispatch(click(control, "synthetic-private-call"), control);
            assertTrue(result.get("dispatchAccepted").getAsBoolean());
            assertEquals(2, snapshots.size());
            assertEquals(Stage.WAITING_RESULT, last(snapshots.get(0)).stage);
            unknown(last(snapshots.get(0)));
            Entry receipt = last(latest(snapshots));
            assertEquals(Stage.RETURNED, receipt.stage); assertEquals(Action.CLICK, receipt.action);
            assertEquals(1, receipt.ordinal); assertEquals(control.getExecutionEpoch(), receipt.executionEpoch);
            assertTrue(receipt.elapsedMillis >= 0); assertEquals(Fact.YES, receipt.submitted);
            assertEquals(Fact.YES, receipt.accepted); assertEquals(Verification.UNVERIFIED, receipt.verification);
            assertEquals("OTHER", receipt.code);
            assertThrows(UnsupportedOperationException.class, () -> latest(snapshots).entries.clear());
            result.addProperty("dispatchAccepted", false); metadata.addProperty("dispatchSubmitted", false);
            assertEquals(Fact.YES, receipt.submitted); assertEquals(Fact.YES, receipt.accepted);
            String serializedForInspection = new Gson().toJson(latest(snapshots));
            assertFalse(serializedForInspection.contains("synthetic-private"));
            assertFalse(serializedForInspection.contains("private.package"));
            assertFalse(serializedForInspection.contains("SYNTHETIC_PRIVATE_ERROR"));
            assertEquals(Stage.WAITING_RESULT, last(snapshots.get(0)).stage);
        }
    }

    @Test public void validationAndExplicitRejectionAreVisibleWithoutBeingReportedAsPlatformActions() throws Exception {
        RunControl control = new RunControl(); AtomicInteger calls = new AtomicInteger();
        List<TaskActionReceipts> snapshots = new ArrayList<>();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
            calls.incrementAndGet(); return ToolDispatcher.error("APPROVAL_REJECTED", "synthetic-private-reason");
        }, snapshots::add)) {
            ToolRequest malformed = new ToolRequest(1, control.getTaskId(), "invalid", control.getExecutionEpoch(),
                    control.getProfileId(), "click", new JsonObject());
            assertEquals("INVALID_ARGUMENT", dispatcher.dispatch(malformed, control).get("code").getAsString());
            assertEquals(0, calls.get());
            Entry invalid = last(latest(snapshots));
            assertEquals("INVALID_ARGUMENT", invalid.code); assertEquals(Fact.NO, invalid.submitted);
            assertEquals(Fact.NO, invalid.accepted);
            dispatcher.dispatch(click(control, "denied"), control);
            Entry denied = last(latest(snapshots));
            assertEquals("APPROVAL_REJECTED", denied.code); assertEquals(Fact.NO, denied.submitted);
            assertEquals(Fact.NO, denied.accepted); assertEquals(1, calls.get());
            assertEquals(0, control.getDispatchAttempts()); assertEquals(2, latest(snapshots).entries.size());
        }
    }

    @Test public void malformedOrContradictoryFactsNeverBecomeAcceptedOrRejectedByCoercion() throws Exception {
        String[] reports = {
                "{\"ok\":true}",
                "{\"ok\":true,\"dispatchSubmitted\":null,\"dispatchAccepted\":true}",
                "{\"ok\":true,\"dispatchSubmitted\":true,\"dispatchAccepted\":null}",
                "{\"ok\":true,\"dispatchSubmitted\":\"true\",\"dispatchAccepted\":true}",
                "{\"ok\":true,\"dispatchSubmitted\":true,\"dispatchAccepted\":1}",
                "{\"ok\":true,\"dispatchSubmitted\":{},\"dispatchAccepted\":false}",
                "{\"ok\":true,\"dispatchSubmitted\":false,\"dispatchAccepted\":true}",
                "{\"ok\":true,\"dispatchSubmitted\":false,\"dispatchAccepted\":false,\"code\":\"OUTCOME_UNKNOWN\"}"
        };
        for (String report : reports) {
            RunControl control = new RunControl(); List<TaskActionReceipts> snapshots = new ArrayList<>();
            try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> json(report), snapshots::add)) {
                dispatcher.dispatch(click(control, "attempt"), control);
                Entry receipt = last(latest(snapshots)); assertEquals(Stage.RETURNED, receipt.stage); unknown(receipt);
            }
        }
    }

    @Test public void acceptedMetadataCannotPromoteMissingOrClaimedVerificationIntoGoalSuccess() throws Exception {
        for (String verification : new String[]{null, "verified", "pending", "private-verification-text", "unverified"}) {
            RunControl control = new RunControl(); List<TaskActionReceipts> snapshots = new ArrayList<>();
            JsonObject metadata = accepted();
            if (verification == null) metadata.remove("verification"); else metadata.addProperty("verification", verification);
            try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> metadata, snapshots::add)) {
                dispatcher.dispatch(click(control, "attempt"), control);
                Entry receipt = last(latest(snapshots));
                assertEquals(Fact.YES, receipt.accepted);
                assertEquals(verification == null || "unverified".equals(verification)
                        ? Verification.UNVERIFIED : Verification.UNKNOWN, receipt.verification);
            }
        }
    }

    @Test public void cachedAndConflictingDuplicatesNeverCreateAnotherReceiptOrSubmission() throws Exception {
        RunControl control = new RunControl(); AtomicInteger calls = new AtomicInteger();
        List<TaskActionReceipts> snapshots = new ArrayList<>();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
            calls.incrementAndGet(); return accepted();
        }, snapshots::add)) {
            ToolRequest request = click(control, "one");
            dispatcher.dispatch(request, control); TaskActionReceipts fixed = latest(snapshots);
            assertTrue(dispatcher.dispatch(request, control).get("ok").getAsBoolean());
            JsonObject altered = request.getArguments(); altered.addProperty("nodeId", "different");
            ToolRequest conflict = new ToolRequest(1, control.getTaskId(), "one", control.getExecutionEpoch(),
                    control.getProfileId(), "click", altered);
            assertEquals("INVALID_ARGUMENT", dispatcher.dispatch(conflict, control).get("code").getAsString());
            assertEquals(1, calls.get()); assertEquals(2, snapshots.size());
            assertSame(fixed, latest(snapshots)); assertEquals(1, fixed.entries.size());
        }
    }

    @Test public void inProgressDuplicateAndFreezeRetainUnknownWhileTheOriginalWorkerOwnsItsCall() throws Exception {
        RunControl control = new RunControl(); AtomicInteger calls = new AtomicInteger();
        List<TaskActionReceipts> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
            calls.incrementAndGet();
            try (RunControl.DispatchTicket ignored = active.beginDispatch(active.getExecutionEpoch())) {
                entered.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new InterruptedException();
                return accepted();
            }
        }, snapshots::add)) {
            ToolRequest request = click(control, "one");
            Future<JsonObject> pending = worker.submit(() -> dispatcher.dispatch(request, control));
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(control.hasInFlightDispatch());
            assertEquals("IN_PROGRESS", dispatcher.dispatch(request, control).get("code").getAsString());
            assertEquals(1, snapshots.size());
            TaskActionReceipts frozen = latest(snapshots).freeze();
            assertTrue(frozen.frozen); assertSame(frozen, frozen.freeze());
            assertEquals(Stage.NO_FINAL_RESULT, last(frozen).stage); unknown(last(frozen));
            // A terminating consumer keeps this frozen copy and ignores the old worker.
            dispatcher.close(); release.countDown(); pending.get(1, TimeUnit.SECONDS);
            assertEquals(1, snapshots.size()); assertEquals(1, calls.get());
            assertEquals(Stage.NO_FINAL_RESULT, last(frozen).stage); unknown(last(frozen));
        } finally { release.countDown(); control.cancel(); worker.shutdownNow(); }
    }

    @Test public void epochInterruptionKeepsUnknownHistoryAndNewEpochUsesANewReceipt() throws Exception {
        RunControl control = new RunControl(); AtomicInteger calls = new AtomicInteger();
        List<TaskActionReceipts> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
            if (calls.incrementAndGet() == 1) {
                try (RunControl.DispatchTicket ignored = active.beginDispatch(active.getExecutionEpoch())) {
                    entered.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new InterruptedException();
                    return accepted();
                }
            }
            return accepted();
        }, snapshots::add)) {
            ToolRequest original = click(control, "old");
            Future<JsonObject> pending = worker.submit(() -> dispatcher.dispatch(original, control));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            control.pause(); control.resume(); release.countDown();
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pending.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TaskControlException);
            Entry old = last(latest(snapshots)); assertEquals(Stage.NO_FINAL_RESULT, old.stage); unknown(old);
            assertThrows(TaskControlException.class, () -> dispatcher.dispatch(original, control));
            dispatcher.dispatch(click(control, "new"), control);
            TaskActionReceipts current = latest(snapshots);
            assertEquals(control.getTaskId(), current.taskId); assertEquals(2, current.entries.size());
            assertSame(old, current.entries.get(0)); assertEquals(2, last(current).ordinal);
            assertTrue(last(current).executionEpoch > old.executionEpoch);
            assertEquals(Stage.RETURNED, last(current).stage); assertEquals(2, calls.get());
        } finally { release.countDown(); control.cancel(); worker.shutdownNow(); }
    }

    @Test public void workerInterruptionAndUncertainDeviceExceptionNeverReportNonSubmission() throws Exception {
        for (boolean interruption : new boolean[]{true, false}) {
            RunControl control = new RunControl(); List<TaskActionReceipts> snapshots = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
                calls.incrementAndGet();
                if (interruption) throw new InterruptedException("synthetic-private-failure");
                throw new IllegalStateException("synthetic-private-failure");
            }, snapshots::add)) {
                ToolRequest request = click(control, "one");
                if (interruption) assertThrows(InterruptedException.class, () -> dispatcher.dispatch(request, control));
                else assertEquals("OUTCOME_UNKNOWN", dispatcher.dispatch(request, control).get("code").getAsString());
                Entry receipt = last(latest(snapshots)); unknown(receipt);
                assertEquals(interruption ? Stage.NO_FINAL_RESULT : Stage.RETURNED, receipt.stage);
                dispatcher.dispatch(request, control);
                assertEquals(1, calls.get()); assertEquals(2, snapshots.size());
            }
        }
    }

    @Test public void observerFailureCannotChangeTheAcceptedResultOrReplayTheDeviceCall() throws Exception {
        RunControl control = new RunControl(); AtomicInteger calls = new AtomicInteger(), callbacks = new AtomicInteger();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
            calls.incrementAndGet(); return accepted();
        }, ignored -> { callbacks.incrementAndGet(); throw new IllegalStateException("observer failed"); })) {
            ToolRequest request = click(control, "one");
            JsonObject first = dispatcher.dispatch(request, control), second = dispatcher.dispatch(request, control);
            assertTrue(first.get("dispatchAccepted").getAsBoolean()); assertEquals(first, second);
            assertEquals(1, calls.get()); assertEquals(2, callbacks.get());
        }
    }

    @Test public void receiptsStayBoundedAndDoNotIncludeReadsOrUnavailableCoordinateTools() throws Exception {
        RunControl control = new RunControl("personal-test", new TaskBudget(30, 300, 600_000));
        List<TaskActionReceipts> snapshots = new ArrayList<>();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> accepted(), snapshots::add)) {
            for (String tool : new String[]{"observe", "list_apps", "tap", "take_screenshot"}) {
                dispatcher.dispatch(new ToolRequest(1, control.getTaskId(), tool, control.getExecutionEpoch(),
                        control.getProfileId(), tool, new JsonObject()), control);
            }
            assertTrue(snapshots.isEmpty());
            for (int i = 1; i <= 205; i++) dispatcher.dispatch(click(control, "write-" + i), control);
            TaskActionReceipts last = latest(snapshots);
            assertEquals(MAX_ENTRIES, last.entries.size()); assertEquals(5, last.omittedCount);
            assertEquals(6, last.entries.get(0).ordinal); assertEquals(205, last(last).ordinal);
            assertEquals(1, snapshots.get(1).entries.size()); assertEquals(0, snapshots.get(1).omittedCount);
            assertTrue(snapshots.stream().allMatch(value -> value.entries.size() <= MAX_ENTRIES));
            TaskActionReceipts empty = TaskActionReceipts.empty(new RunControl().getTaskId());
            assertTrue(empty.entries.isEmpty()); assertEquals(0, empty.version); assertFalse(empty.frozen);
        }
    }

    @Test public void objectCodeKeepsTheExistingFailureAndCannotExposeItsContentThroughAReceipt() throws Exception {
        RunControl control = new RunControl(); List<TaskActionReceipts> snapshots = new ArrayList<>();
        JsonObject metadata = accepted(); metadata.add("code", json("{\"secret\":\"synthetic-private-code\"}"));
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> metadata, snapshots::add)) {
            assertThrows(UnsupportedOperationException.class, () -> dispatcher.dispatch(click(control, "one"), control));
            TaskActionReceipts frozen = latest(snapshots).freeze(); unknown(last(frozen));
            assertEquals(Stage.NO_FINAL_RESULT, last(frozen).stage);
            assertFalse(new Gson().toJson(frozen).contains("synthetic-private-code"));
        }
    }
}
