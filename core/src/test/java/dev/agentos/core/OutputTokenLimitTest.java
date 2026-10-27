package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Checks the actual wire field; it does not simulate provider enforcement or billing. */
public class OutputTokenLimitTest {
    @Test(timeout = 5000) public void existingConstructorSendsDefaultOutputLimit() throws Exception {
        try (Server server = new Server(false)) {
            ModelSettings settings = new ModelSettings(server.endpoint(), "synthetic-key", "synthetic-model");
            assertEquals(2048, settings.maxOutputTokens);
            settings.validate();
            RunControl control = new RunControl();
            try {
                new HttpModelClient(2000).requestSynthetic(settings, new JsonArray(), new JsonArray(),
                        control, control.getExecutionEpoch());
                server.assertHealthy();
                assertEquals(List.of(2048), server.limits);
            } finally { control.cancel(); }
        }
    }

    @Test(timeout = 7000) public void actualRunnerKeepsSelectedBoundaryOnEveryRound() throws Exception {
        for (int limit : new int[]{128, 8192}) {
            try (Server server = new Server(true)) {
                RunControl control = new RunControl();
                AtomicReference<TaskResult> result = new AtomicReference<>();
                AtomicInteger observations = new AtomicInteger();
                ToolHandler tools = (name, args, owner) -> {
                    assertEquals("observe", name);
                    observations.incrementAndGet();
                    return JsonParser.parseString("{\"ok\":true,\"data\":{\"revision\":\"r1\",\"nodes\":[]}}").getAsJsonObject();
                };
                try {
                    new AgentRunner(new ModelSettings(server.endpoint(), "synthetic-key", "synthetic-model", limit),
                            tools, events(result), new HttpModelClient(2000))
                            .run("读取合成测试页面", control);
                    server.assertHealthy();
                    assertNotNull(result.get());
                    assertEquals(TaskResult.Kind.MODEL_REPORT, result.get().kind);
                    assertFalse(result.get().verified);
                    assertEquals(List.of(limit, limit), server.limits);
                    assertEquals(2, observations.get());
                } finally { control.cancel(); }
            }
        }
    }

    @Test(timeout = 5000) public void bothSyntheticProbesUseSameSelectedLimitWithoutClaimingVerification() throws Exception {
        try (Server server = new Server(false)) {
            ModelSettings settings = new ModelSettings(server.endpoint(), "synthetic-key", "synthetic-model", 3072);
            RunControl tools = new RunControl(), image = new RunControl();
            try {
                ModelProbe.Result toolResult = new ModelProbe(new HttpModelClient(2000)).test(settings, tools);
                VisionModelProbe.Result imageResult = new VisionModelProbe(new HttpModelClient(2000)).test(settings, image);
                assertTrue(toolResult.connected);
                assertFalse(toolResult.toolCallingVerified);
                assertTrue(imageResult.connected);
                assertFalse(imageResult.syntheticImageVerified);
                server.assertHealthy();
                assertEquals(List.of(3072, 3072), server.limits);
            } finally { tools.cancel(); image.cancel(); }
        }
    }

    @Test(timeout = 5000) public void invalidLimitsAreRejectedBeforeAnyTaskOrProbeNetworkRequest() throws Exception {
        try (Server server = new Server(false)) {
            for (int limit : new int[]{Integer.MIN_VALUE, -1, 0, 127, 8193, Integer.MAX_VALUE}) {
                ModelSettings settings = new ModelSettings(server.endpoint(), "synthetic-key", "synthetic-model", limit);
                assertThrows(IllegalArgumentException.class, settings::validate);
                RunControl task = new RunControl(), tools = new RunControl(), image = new RunControl();
                AtomicReference<TaskResult> result = new AtomicReference<>();
                try {
                    new AgentRunner(settings, (name, args, control) -> {
                        fail("Invalid settings must not read or act on a device");
                        return new JsonObject();
                    }, events(result), new HttpModelClient(2000)).run("合成任务", task);
                    assertNotNull(result.get());
                    assertEquals(TaskResult.Kind.FAILED, result.get().kind);
                    ModelProbe.Result toolResult = new ModelProbe(new HttpModelClient(2000)).test(settings, tools);
                    VisionModelProbe.Result imageResult = new VisionModelProbe(new HttpModelClient(2000)).test(settings, image);
                    assertEquals("INVALID_CONFIGURATION", toolResult.code);
                    assertEquals("INVALID_CONFIGURATION", imageResult.code);
                    assertFalse(toolResult.connected);
                    assertFalse(imageResult.connected);
                } finally { task.cancel(); tools.cancel(); image.cancel(); }
            }
            server.assertHealthy();
            assertTrue(server.limits.isEmpty());
        }
    }

    private static AgentRunner.Listener events(AtomicReference<TaskResult> result) {
        return new AgentRunner.Listener() {
            @Override public void onStatus(String status) { }
            @Override public void onStep(String step) { }
            @Override public void onFinished(String summary, boolean verified) { }
            @Override public void onResult(TaskResult value) { result.set(value); }
        };
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final List<Integer> limits = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Server(boolean twoRounds) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(worker);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonElement limit = request.get("max_tokens");
                    if (limit == null || !limit.isJsonPrimitive() || !limit.getAsJsonPrimitive().isNumber()
                            || !limit.toString().matches("[0-9]{1,4}")) throw new AssertionError("Expected integer max_tokens");
                    if (limits.size() >= 4) throw new AssertionError("Unexpected extra model request");
                    limits.add(limit.getAsInt());
                    String body = twoRounds && limits.size() == 1
                            ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"fresh-observation\",\"type\":\"function\",\"function\":{\"name\":\"observe\",\"arguments\":\"{}\"}}]}}]}"
                            : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"仅作未验证的合成报告。\"}}]}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { exchange.close(); }
            });
            server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        void assertHealthy() { assertNull("Unexpected fixture failure", failure.get()); }
        @Override public void close() throws InterruptedException {
            server.stop(0);
            worker.shutdownNow();
            assertTrue("HTTP fixture worker must terminate", worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
