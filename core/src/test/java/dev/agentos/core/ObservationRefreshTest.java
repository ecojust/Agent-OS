package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.Assert.*;

/** Transient observation failures never authorize replaying a device write. */
public class ObservationRefreshTest {
    private static final String GOAL = "观察允许的应用并说明当前界面";

    private static JsonObject observation(String revision) {
        JsonObject data = new JsonObject();
        data.addProperty("revision", revision);
        data.addProperty("packageName", "demo.app");
        data.add("nodes", new JsonArray());
        JsonObject result = new JsonObject();
        result.addProperty("ok", true); result.add("data", data);
        return result;
    }

    private static JsonObject error(String code) {
        return ToolDispatcher.error(code, "测试执行器拒绝观察");
    }

    private static String finalReply() {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"当前界面报告\"}}]}";
    }

    private static String clickReply() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("nodeId", "n1"); arguments.addProperty("expectedRevision", "r1");
        JsonObject function = new JsonObject();
        function.addProperty("name", "click"); function.addProperty("arguments", arguments.toString());
        JsonObject call = new JsonObject();
        call.addProperty("id", "one-write"); call.addProperty("type", "function"); call.add("function", function);
        JsonArray calls = new JsonArray(); calls.add(call);
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.add("tool_calls", calls);
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject(); response.add("choices", choices);
        return response.toString();
    }

    private static final class Events implements AgentRunner.Listener {
        final CountDownLatch retryWaiting = new CountDownLatch(1);
        final CountDownLatch fourthRetryWaiting = new CountDownLatch(1);
        final CountDownLatch paused = new CountDownLatch(1);
        final AtomicInteger retryWaits = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        volatile TaskResult result;

        public void onStatus(String status) {
            if ("等待界面焦点稳定".equals(status)) {
                retryWaiting.countDown();
                if (retryWaits.incrementAndGet() == 4) fourthRetryWaiting.countDown();
            }
            if (status.startsWith("已暂停")) paused.countDown();
        }
        public void onStep(String step) { }
        public void onFinished(String summary, boolean verified) { completions.incrementAndGet(); }
        public void onResult(TaskResult result) { this.result = result; AgentRunner.Listener.super.onResult(result); }

        void assertCompleted(TaskResult.Kind kind, String code) {
            assertEquals(1, completions.get());
            assertNotNull(result);
            assertEquals(kind, result.kind); assertEquals(code, result.code);
        }
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Server() throws IOException { this(index -> finalReply()); }
        Server(IntFunction<String> response) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int index = requests.size(); requests.add(body);
                    byte[] bytes = response.apply(index).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // A control change may disconnect a request before its response is read.
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally { exchange.close(); }
            });
            server.start();
        }

        AgentRunner runner(ToolHandler handler, Events events) {
            ModelSettings settings = new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test-model");
            return new AgentRunner(settings, handler, events);
        }
        void assertHealthy() { assertNull("local server callback failed: " + failure.get(), failure.get()); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }

    @Test public void initialStaleObservationIsRetriedAndEveryAttemptUsesToolBudget() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        Events events = new Events(); RunControl control = new RunControl();
        try (Server server = new Server()) {
            server.runner((name, args, active) -> {
                assertEquals("observe", name);
                return observations.incrementAndGet() == 1 ? error("STALE_OBSERVATION") : observation("fresh-r2");
            }, events).run(GOAL, control);
            events.assertCompleted(TaskResult.Kind.MODEL_REPORT, "MODEL_REPORT");
            assertEquals(2, observations.get());
            assertEquals(2, control.budgetSnapshot().toolCalls);
            assertEquals(1, control.budgetSnapshot().modelRounds);
            assertEquals(1, server.requests.size());
            assertTrue(server.requests.get(0).contains("fresh-r2"));
            assertFalse(server.requests.get(0).contains("STALE_OBSERVATION"));
            server.assertHealthy();
        }
    }

    @Test(timeout = 4000) public void thirdObservationCanSucceedAfterBothBoundedBackoffs() throws Exception {
        List<Long> attemptTimes = new CopyOnWriteArrayList<>();
        Events events = new Events(); RunControl control = new RunControl();
        try (Server server = new Server()) {
            server.runner((name, args, active) -> {
                assertEquals("observe", name); attemptTimes.add(System.nanoTime());
                return attemptTimes.size() < 3 ? error("STALE_OBSERVATION") : observation("third-r3");
            }, events).run(GOAL, control);
            events.assertCompleted(TaskResult.Kind.MODEL_REPORT, "MODEL_REPORT");
            assertEquals(3, attemptTimes.size());
            // Leave a little clock granularity tolerance while rejecting a tight retry loop.
            assertTrue(TimeUnit.NANOSECONDS.toMillis(attemptTimes.get(1) - attemptTimes.get(0)) >= 100);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(attemptTimes.get(2) - attemptTimes.get(1)) >= 200);
            assertEquals(3, control.budgetSnapshot().toolCalls);
            assertEquals(1, server.requests.size());
            server.assertHealthy();
        }
    }

    @Test(timeout = 5000) public void persistentStaleObservationsStopWithinSixAttemptsBeforeCallingModel() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        Events events = new Events(); RunControl control = new RunControl();
        try (Server server = new Server()) {
            long started = System.nanoTime();
            server.runner((name, args, active) -> {
                assertEquals("observe", name); observations.incrementAndGet();
                return error("STALE_OBSERVATION");
            }, events).run(GOAL, control);
            events.assertCompleted(TaskResult.Kind.FAILED, "STALE_OBSERVATION");
            assertTrue("The attempt count must remain bounded even when the window has time left", observations.get() <= 6);
            assertTrue("A transient focus change needs more than one immediate observation", observations.get() >= 2);
            assertEquals(observations.get(), control.budgetSnapshot().toolCalls);
            assertTrue("Persistent stale reads must end within the stability window plus scheduling tolerance",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
            assertEquals(0, control.budgetSnapshot().modelRounds);
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        }
    }

    @Test(timeout = 5000) public void elapsedRetryWindowPreventsAnotherReadEvenBeforeAttemptLimit() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        Events events = new Events(); RunControl control = new RunControl();
        try (Server server = new Server()) {
            server.runner((name, args, active) -> {
                assertEquals("observe", name); observations.incrementAndGet();
                // An in-flight platform query can finish after the two-second retry
                // window, but its failure cannot admit another observation.
                new CountDownLatch(1).await(2050, TimeUnit.MILLISECONDS);
                return error("STALE_OBSERVATION");
            }, events).run(GOAL, control);
            events.assertCompleted(TaskResult.Kind.FAILED, "STALE_OBSERVATION");
            assertEquals(1, observations.get());
            assertEquals(1, control.budgetSnapshot().toolCalls);
            assertEquals(0, control.budgetSnapshot().modelRounds);
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        }
    }

    @Test public void permissionFailureAndUnknownOutcomeAreNeverRetried() throws Exception {
        for (String code : List.of("PERMISSION_DENIED", "OUTCOME_UNKNOWN")) {
            AtomicInteger observations = new AtomicInteger();
            Events events = new Events(); RunControl control = new RunControl();
            try (Server server = new Server()) {
                server.runner((name, args, active) -> {
                    assertEquals("observe", name); observations.incrementAndGet(); return error(code);
                }, events).run(GOAL, control);
                events.assertCompleted(TaskResult.Kind.FAILED, code);
                assertEquals(1, observations.get());
                assertEquals(1, control.budgetSnapshot().toolCalls);
                assertEquals(0, control.budgetSnapshot().modelRounds);
                assertTrue(server.requests.isEmpty());
                server.assertHealthy();
            }
        }
    }

    @Test(timeout = 4000) public void cancellationDuringBackoffPreventsSecondObservationAndModelCall() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        Events events = new Events(); RunControl control = new RunControl();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Server server = new Server()) {
            Future<?> running = worker.submit(() -> server.runner((name, args, active) -> {
                assertEquals("observe", name); observations.incrementAndGet(); return error("STALE_OBSERVATION");
            }, events).run(GOAL, control));
            assertTrue("runner must reach retry backoff", events.retryWaiting.await(1, TimeUnit.SECONDS));
            control.cancel();
            running.get(1, TimeUnit.SECONDS);
            events.assertCompleted(TaskResult.Kind.STOPPED, "TASK_STOPPED");
            assertEquals(1, observations.get());
            assertEquals(1, control.budgetSnapshot().toolCalls);
            assertEquals(0, control.budgetSnapshot().modelRounds);
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        } finally { control.cancel(); worker.shutdownNow(); }
    }

    @Test(timeout = 5000) public void cancellationAlsoInterruptsTheLongerLaterBackoffPromptly() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        Events events = new Events(); RunControl control = new RunControl();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Server server = new Server()) {
            Future<?> running = worker.submit(() -> server.runner((name, args, active) -> {
                assertEquals("observe", name); observations.incrementAndGet(); return error("STALE_OBSERVATION");
            }, events).run(GOAL, control));
            assertTrue("The runner must reach the later, longer stability wait", events.fourthRetryWaiting.await(2, TimeUnit.SECONDS));
            assertEquals(4, observations.get());
            long stopped = System.nanoTime();
            control.cancel();
            running.get(350, TimeUnit.MILLISECONDS);
            assertTrue("Stop must wake the wait instead of sleeping through its 500 ms delay",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopped) < 400);
            events.assertCompleted(TaskResult.Kind.STOPPED, "TASK_STOPPED");
            assertEquals(4, observations.get());
            assertEquals(4, control.budgetSnapshot().toolCalls);
            assertEquals(0, control.budgetSnapshot().modelRounds);
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        } finally { control.cancel(); worker.shutdownNow(); }
    }

    @Test(timeout = 5000) public void pauseDiscardsOldRetryAndResumeObservesNewEpochWithoutResettingBudget() throws Exception {
        List<Long> observedEpochs = new CopyOnWriteArrayList<>();
        Events events = new Events();
        RunControl control = new RunControl("personal-test", new TaskBudget(2, 3, 10000));
        control.consumeModelRound(); control.consumeToolCall();
        String taskId = control.getTaskId(); long initialEpoch = control.getExecutionEpoch();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Server server = new Server()) {
            Future<?> running = worker.submit(() -> server.runner((name, args, active) -> {
                assertEquals("observe", name); observedEpochs.add(active.getExecutionEpoch());
                return observedEpochs.size() == 1 ? error("STALE_OBSERVATION") : observation("resumed-fresh");
            }, events).run(GOAL, control));
            assertTrue(events.retryWaiting.await(1, TimeUnit.SECONDS));
            control.pause();
            assertTrue("runner must acknowledge pause instead of retrying", events.paused.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> running.get(180, TimeUnit.MILLISECONDS));
            assertEquals(List.of(initialEpoch), observedEpochs);
            assertTrue(server.requests.isEmpty());
            assertEquals(2, control.budgetSnapshot().toolCalls);
            assertEquals(1, control.budgetSnapshot().modelRounds);
            control.resume();
            long resumedEpoch = control.getExecutionEpoch();
            running.get(2, TimeUnit.SECONDS);
            assertEquals(taskId, control.getTaskId());
            assertTrue(resumedEpoch > initialEpoch);
            assertEquals(Arrays.asList(initialEpoch, resumedEpoch), observedEpochs);
            assertEquals(3, control.budgetSnapshot().toolCalls);
            assertEquals(2, control.budgetSnapshot().modelRounds);
            events.assertCompleted(TaskResult.Kind.MODEL_REPORT, "MODEL_REPORT");
            assertEquals(1, server.requests.size());
            assertTrue(server.requests.get(0).contains(GOAL));
            assertTrue(server.requests.get(0).contains("resumed-fresh"));
            server.assertHealthy();
        } finally { control.cancel(); worker.shutdownNow(); }
    }

    @Test public void exhaustedActivityBudgetPreventsAnyObservationRetry() throws Exception {
        AtomicLong clock = new AtomicLong();
        RunControl control = new RunControl("personal-test", new TaskBudget(30, 100, 50), clock::get);
        AtomicInteger observations = new AtomicInteger(); Events events = new Events();
        try (Server server = new Server()) {
            server.runner((name, args, active) -> {
                assertEquals("observe", name); observations.incrementAndGet();
                clock.set(TimeUnit.MILLISECONDS.toNanos(50));
                return error("STALE_OBSERVATION");
            }, events).run(GOAL, control);
            events.assertCompleted(TaskResult.Kind.BUDGET_EXCEEDED, "BUDGET_EXCEEDED");
            assertEquals(1, observations.get());
            assertEquals(1, control.budgetSnapshot().toolCalls);
            assertEquals(0, control.budgetSnapshot().modelRounds);
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        }
    }

    @Test(timeout = 3000) public void retryCannotOutliveAShortRemainingActivityBudget() throws Exception {
        AtomicInteger observations = new AtomicInteger(); Events events = new Events();
        try (Server server = new Server()) {
            RunControl control = new RunControl("personal-test", new TaskBudget(30, 100, 75));
            server.runner((name, args, active) -> {
                assertEquals("observe", name); observations.incrementAndGet(); return error("STALE_OBSERVATION");
            }, events).run(GOAL, control);
            assertEquals(1, events.completions.get());
            assertNotNull(events.result);
            assertTrue(List.of("BUDGET_EXCEEDED", "STALE_OBSERVATION").contains(events.result.code));
            assertEquals(1, observations.get());
            assertEquals(1, control.budgetSnapshot().toolCalls);
            assertEquals(0, control.budgetSnapshot().modelRounds);
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        }
    }

    @Test(timeout = 5000) public void observationCanSettleAfterHalfASecondWithoutReplayingTheAcceptedMutation() throws Exception {
        AtomicInteger observations = new AtomicInteger(); List<String> actions = new CopyOnWriteArrayList<>();
        AtomicLong writeAcceptedAt = new AtomicLong();
        Events events = new Events(); RunControl control = new RunControl();
        try (Server server = new Server(index -> index == 0 ? clickReply() : finalReply())) {
            server.runner((name, args, active) -> {
                actions.add(name);
                if (name.equals("observe")) {
                    int count = observations.incrementAndGet();
                    if (count == 1) return observation("r1");
                    // A real focus transition can exceed the former 500 ms window.
                    return System.nanoTime() - writeAcceptedAt.get() < TimeUnit.MILLISECONDS.toNanos(650)
                            ? error("STALE_OBSERVATION") : observation("after-click-settled");
                }
                assertEquals("click", name);
                active.markDispatchAttempt(active.getExecutionEpoch());
                assertTrue("An accepted mutation must never be replayed", writeAcceptedAt.compareAndSet(0, System.nanoTime()));
                JsonObject result = new JsonObject(); result.addProperty("ok", true); return result;
            }, events).run(GOAL, control);
            events.assertCompleted(TaskResult.Kind.MODEL_REPORT, "MODEL_REPORT");
            assertEquals("observe", actions.get(0));
            assertEquals("click", actions.get(1));
            for (int index = 2; index < actions.size(); index++) assertEquals("observe", actions.get(index));
            assertTrue(observations.get() >= 3 && observations.get() <= 7);
            assertEquals(1, control.getDispatchAttempts());
            assertEquals(observations.get() + 1, control.budgetSnapshot().toolCalls);
            assertTrue("Focus waits must count toward the original activity budget", control.budgetSnapshot().activeMillis >= 650);
            assertEquals(2, control.budgetSnapshot().modelRounds);
            assertEquals(2, server.requests.size());
            assertTrue(server.requests.get(1).contains("after-click-settled"));
            server.assertHealthy();
        }
    }
}
