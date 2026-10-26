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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Checks the epoch at the device boundary, after the outer dispatch checks have passed. */
public class ExecutionEpochPropagationTest {
    private static final String GOAL = "打开已授权的测试应用";

    private static JsonObject launchArguments() {
        JsonObject arguments = new JsonObject(); arguments.addProperty("packageName", "demo.app");
        return arguments;
    }

    private static JsonObject accepted() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true); result.addProperty("dispatchAccepted", true);
        return result;
    }

    private static JsonObject observation(long epoch, boolean launched) {
        JsonObject data = new JsonObject();
        data.addProperty("revision", "observed-epoch-" + epoch);
        data.addProperty("packageName", launched ? "demo.app" : "dev.agentos.app");
        data.addProperty("scoped", launched);
        data.add("nodes", new JsonArray());
        JsonObject result = new JsonObject(); result.addProperty("ok", true); result.add("data", data);
        return result;
    }

    /** The legacy entry deliberately demonstrates the unsafe epoch recapture regression. */
    private static final class DeviceBoundary implements ToolHandler {
        final AtomicBoolean injectPauseAtLaunch;
        final AtomicInteger legacyEntries = new AtomicInteger();
        final AtomicInteger approvals = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final List<String> entries = new CopyOnWriteArrayList<>();
        final List<Long> suppliedEpochs = new CopyOnWriteArrayList<>();
        final List<Long> observedEpochs = new CopyOnWriteArrayList<>();

        DeviceBoundary(boolean injectPauseAtLaunch) {
            this.injectPauseAtLaunch = new AtomicBoolean(injectPauseAtLaunch);
        }

        @Override public JsonObject execute(String name, JsonObject arguments, RunControl control) throws Exception {
            legacyEntries.incrementAndGet();
            pauseAndResumeAtEntry(name, control);
            // If a caller drops the request epoch, this recreates the old vulnerability:
            // the already-validated outer request is rebound to the resumed execution.
            return checkedExecute(name, control, control.getExecutionEpoch());
        }

        @Override public JsonObject execute(String name, JsonObject arguments, RunControl control, long requestEpoch) throws Exception {
            entries.add(name); suppliedEpochs.add(requestEpoch);
            pauseAndResumeAtEntry(name, control);
            return checkedExecute(name, control, requestEpoch);
        }

        private void pauseAndResumeAtEntry(String name, RunControl control) {
            if ("launch_app".equals(name) && injectPauseAtLaunch.compareAndSet(true, false)) {
                control.pause(); control.resume();
            }
        }

        private JsonObject checkedExecute(String name, RunControl control, long requestEpoch) throws Exception {
            control.checkExecutionEpoch(requestEpoch);
            if ("observe".equals(name)) {
                observedEpochs.add(requestEpoch);
                return observation(requestEpoch, writes.get() > 0);
            }
            assertEquals("launch_app", name);
            approvals.incrementAndGet();
            control.markDispatchAttempt(requestEpoch);
            writes.incrementAndGet();
            return accepted();
        }
    }

    private static final class Events implements AgentRunner.Listener {
        final AtomicInteger completions = new AtomicInteger();
        volatile TaskResult result;
        public void onStatus(String status) { }
        public void onStep(String step) { }
        public void onFinished(String summary, boolean verified) { completions.incrementAndGet(); }
        public void onResult(TaskResult result) { this.result = result; AgentRunner.Listener.super.onResult(result); }
    }

    /** First response requests one launch, the next response finishes without another write. */
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int round = requests.size(); requests.add(request);
                    byte[] response = response(round).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (IOException ignored) {
                    // Control changes can close an in-flight model connection.
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally { exchange.close(); }
            });
            server.start();
        }

        private static JsonObject response(int round) {
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant");
            if (round == 0) {
                JsonObject function = new JsonObject();
                function.addProperty("name", "launch_app"); function.addProperty("arguments", launchArguments().toString());
                JsonObject call = new JsonObject();
                call.addProperty("id", "original-launch-plan"); call.addProperty("type", "function"); call.add("function", function);
                JsonArray calls = new JsonArray(); calls.add(call); message.add("tool_calls", calls);
            } else {
                message.addProperty("content", "已读取新的界面状态；请核对当前结果。");
            }
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject result = new JsonObject(); result.add("choices", choices); return result;
        }

        AgentRunner runner(ToolHandler handler, Events events) {
            ModelSettings settings = new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic-key", "test-model");
            return new AgentRunner(settings, handler, events);
        }
        void assertHealthy() { assertNull("local protocol server failed: " + failure.get(), failure.get()); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }

    @Test public void dispatcherPreservesOriginalEpochUntilTheDeviceChecksIt() throws Exception {
        RunControl control = new RunControl();
        long originalEpoch = control.getExecutionEpoch();
        DeviceBoundary device = new DeviceBoundary(true);
        ToolRequest request = new ToolRequest(ToolCatalog.PROTOCOL_VERSION, control.getTaskId(), "old-launch",
                originalEpoch, control.getProfileId(), "launch_app", launchArguments());
        try (ToolDispatcher dispatcher = new ToolDispatcher(device)) {
            TaskControlException stopped = assertThrows(TaskControlException.class, () -> dispatcher.dispatch(request, control));
            assertEquals("TASK_PAUSED", stopped.getCode());
            assertTrue(control.getExecutionEpoch() > originalEpoch);
            assertFalse("the user already resumed; only the old request is invalid", control.isPaused());
            assertEquals(0, device.legacyEntries.get());
            assertEquals(List.of("launch_app"), device.entries);
            assertEquals(List.of(originalEpoch), device.suppliedEpochs);
            assertEquals(0, device.approvals.get());
            assertEquals(0, device.writes.get());
            assertEquals(0, control.getDispatchAttempts());
            assertFalse("the device boundary rejected before submitting anything", dispatcher.hasUnknownOutcome());
            assertEquals(1, control.budgetSnapshot().toolCalls);
        }
    }

    @Test(timeout = 5000) public void runnerForwardsOldEpochAndReobservesAfterTheDeviceEntryRace() throws Exception {
        RunControl control = new RunControl();
        long originalEpoch = control.getExecutionEpoch(); String taskId = control.getTaskId();
        DeviceBoundary device = new DeviceBoundary(true); Events events = new Events();
        try (Server server = new Server()) {
            server.runner(device, events).run(GOAL, control);
            long resumedEpoch = control.getExecutionEpoch();
            assertTrue(resumedEpoch > originalEpoch);
            assertEquals(taskId, control.getTaskId());
            assertEquals(0, device.legacyEntries.get());
            assertEquals(Arrays.asList("observe", "launch_app", "observe"), device.entries);
            assertEquals(Arrays.asList(originalEpoch, originalEpoch, resumedEpoch), device.suppliedEpochs);
            assertEquals(Arrays.asList(originalEpoch, resumedEpoch), device.observedEpochs);
            assertEquals(0, device.approvals.get());
            assertEquals(0, device.writes.get());
            assertEquals(0, control.getDispatchAttempts());
            assertEquals(3, control.budgetSnapshot().toolCalls);
            assertEquals(2, control.budgetSnapshot().modelRounds);
            assertEquals(1, events.completions.get());
            assertNotNull(events.result); assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertFalse(events.result.verified);
            assertEquals(2, server.requests.size());
            String resumedRequest = server.requests.get(1);
            assertTrue(resumedRequest.contains(GOAL));
            assertTrue(resumedRequest.contains("observed-epoch-" + resumedEpoch));
            assertFalse("old plan must be discarded after continuation", resumedRequest.contains("original-launch-plan"));
            assertFalse("old observation must not be rebound", resumedRequest.contains("observed-epoch-" + originalEpoch));
            server.assertHealthy();
        }
    }

    @Test(timeout = 5000) public void freshTaskStillApprovesAndDispatchesExactlyOnceThroughTheEpochAwareEntry() throws Exception {
        RunControl control = new RunControl(); long freshEpoch = control.getExecutionEpoch();
        DeviceBoundary device = new DeviceBoundary(false); Events events = new Events();
        try (Server server = new Server()) {
            server.runner(device, events).run(GOAL, control);
            assertEquals(freshEpoch, control.getExecutionEpoch());
            assertEquals(0, device.legacyEntries.get());
            assertEquals(Arrays.asList("observe", "launch_app", "observe"), device.entries);
            assertEquals(Arrays.asList(freshEpoch, freshEpoch, freshEpoch), device.suppliedEpochs);
            assertEquals(Arrays.asList(freshEpoch, freshEpoch), device.observedEpochs);
            assertEquals(1, device.approvals.get());
            assertEquals(1, device.writes.get());
            assertEquals(1, control.getDispatchAttempts());
            assertEquals(3, control.budgetSnapshot().toolCalls);
            assertEquals(2, control.budgetSnapshot().modelRounds);
            assertEquals(1, events.completions.get());
            assertNotNull(events.result); assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertEquals(2, server.requests.size());
            assertTrue(server.requests.get(1).contains("demo.app"));
            server.assertHealthy();
        }
    }
}
