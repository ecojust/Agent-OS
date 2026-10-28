package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real loopback responses and caller/worker barriers; no provider billing claim. */
public class ModelTokenUsageHttpTest {
    private static final String CHOICES = "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"synthetic report\"}}]";
    private static final String COMPLETE = "{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}";

    @Test(timeout = 7000) public void runnerPreservesPartialFieldCoverageAcrossActualModelRounds() throws Exception {
        try (Server server = new Server((index, request) -> index == 1
                ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"observation\",\"type\":\"function\",\"function\":{\"name\":\"observe\",\"arguments\":\"{}\"}}]}}],\"usage\":{\"prompt_tokens\":10,\"total_tokens\":12}}"
                : reply("\"usage\":{\"prompt_tokens\":\"private-invalid-count\",\"completion_tokens\":3,\"total_tokens\":5}"))) {
            RunControl owner = new RunControl();
            AtomicReference<TaskResult> result = new AtomicReference<>();
            AtomicInteger observations = new AtomicInteger();
            try {
                new AgentRunner(server.settings(), observations(observations), events(result), owner,
                        (control, epoch) -> control == owner).run("读取合成测试页面", owner);
                assertEquals(TaskResult.Kind.MODEL_REPORT, result.get().kind);
                assertFalse(result.get().verified);
                ModelTokenUsage usage = owner.modelTokenUsageSnapshot();
                assertEquals(2, usage.calls);
                assertField(usage.prompt, 10L, 1);
                assertField(usage.completion, 3L, 1);
                assertField(usage.total, 17L, 2);
                assertEquals(ModelTokenUsage.Completeness.PARTIAL, usage.completeness);
                assertEquals(2, owner.budgetSnapshot().modelRounds);
                assertEquals(2, observations.get());
                assertEquals(0, owner.getDispatchAttempts());
                assertEquals(2, server.requests.get());
                server.assertHealthy();
            } finally { owner.cancel(); }
        }
    }

    @Test(timeout = 10000) public void boundTaskVisionProbeAndRunnerShareUsageWhileIndependentProbeDoesNot() throws Exception {
        try (Server server = new Server((index, request) -> reply("\"usage\":" + COMPLETE))) {
            RunControl task = new RunControl(), independent = new RunControl();
            AtomicReference<TaskResult> result = new AtomicReference<>();
            AtomicInteger guardChecks = new AtomicInteger();
            try {
                long epoch = task.getExecutionEpoch();
                VisionModelProbe.Result vision = new VisionModelProbe().test(server.settings(), task, epoch,
                        (owner, originalEpoch) -> {
                            guardChecks.incrementAndGet();
                            return owner == task && originalEpoch == epoch;
                        });
                assertTrue(vision.connected);
                assertFalse("A generic reply does not verify the synthetic image", vision.syntheticImageVerified);
                assertTrue(guardChecks.get() > 0);
                new AgentRunner(server.settings(), observations(new AtomicInteger()), events(result), task,
                        (owner, originalEpoch) -> owner == task).run("读取合成测试页面", task);
                assertEquals(TaskResult.Kind.MODEL_REPORT, result.get().kind);
                ModelProbe.Result protocol = new ModelProbe().test(server.settings(), independent);
                assertTrue(protocol.connected);
                assertFalse(protocol.toolCallingVerified);
                ModelTokenUsage combined = task.modelTokenUsageSnapshot();
                assertEquals(2, combined.calls);
                assertField(combined.prompt, 4L, 2);
                assertField(combined.completion, 2L, 2);
                assertField(combined.total, 6L, 2);
                assertEquals(ModelTokenUsage.Completeness.COMPLETE, combined.completeness);
                assertEquals(2, task.budgetSnapshot().modelRounds);
                assertEquals(1, independent.modelTokenUsageSnapshot().calls);
                assertField(independent.modelTokenUsageSnapshot().total, 3L, 1);
                assertEquals(3, server.requests.get());
                server.assertHealthy();
            } finally { task.cancel(); independent.cancel(); }
        }
    }

    @Test(timeout = 7000) public void duplicateMalformedAndMissingUsageDoNotRejectOrReplayValidResponses() throws Exception {
        String[] optional = {"", "\"usage\":false", "\"usage\":" + COMPLETE + ",\"usage\":" + COMPLETE,
                "\"usage\":{\"prompt_tokens\":2,\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}",
                "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":2}"};
        try (Server server = new Server((index, request) -> reply(optional[index - 1]))) {
            RunControl owner = new RunControl();
            try {
                HttpModelClient client = new HttpModelClient(3000);
                for (int i = 0; i < optional.length; i++) {
                    JsonObject response = request(client, server.settings(), owner, (control, epoch) -> true);
                    assertEquals("synthetic report", response.getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString());
                    assertEquals(i + 1, owner.modelTokenUsageSnapshot().calls);
                }
                ModelTokenUsage usage = owner.modelTokenUsageSnapshot();
                assertEquals(ModelTokenUsage.Completeness.UNKNOWN, usage.completeness);
                assertNull(usage.prompt.subtotal); assertNull(usage.completion.subtotal); assertNull(usage.total.subtotal);
                assertEquals(0, usage.total.reportedCalls);
                assertEquals(optional.length, server.requests.get());
                server.assertHealthy();
            } finally { owner.cancel(); }
        }
    }

    @Test(timeout = 20000) public void parsedWorkerReportsCannotSurviveStopPauseOrLateConfigurationInvalidation() throws Exception {
        for (int intervention = 0; intervention < 3; intervention++) {
            try (Server server = new Server((index, request) -> reply("\"usage\":" + COMPLETE));
                 Network fixture = new Network()) {
                RunControl owner = new RunControl(), replacement = new RunControl();
                AtomicBoolean configurationCurrent = new AtomicBoolean(true);
                AtomicBoolean first = new AtomicBoolean(true);
                HttpModelClient client = fixture.client(url -> first.getAndSet(false)
                        ? new FinalizingConnection(url, fixture.gate) : (HttpURLConnection) url.openConnection());
                try {
                    Future<TaskControlException> result = fixture.caller.submit(() -> assertThrows(TaskControlException.class,
                            () -> request(client, server.settings(), owner, (control, epoch) -> configurationCurrent.get())));
                    assertTrue("Response parsing must finish before the finalization barrier", fixture.gate.entered.await(3, TimeUnit.SECONDS));
                    assertEquals(1, owner.modelTokenUsageSnapshot().calls);
                    assertNull("The network worker must not publish its parsed usage", owner.modelTokenUsageSnapshot().total.subtotal);
                    if (intervention == 0) owner.cancel();
                    else if (intervention == 1) { owner.pause(); owner.resume(); }
                    else configurationCurrent.set(false);
                    ModelTokenUsage beforeRelease = owner.modelTokenUsageSnapshot();
                    fixture.gate.release.countDown();
                    assertEquals(intervention == 1 ? "TASK_PAUSED" : "TASK_STOPPED", result.get(3, TimeUnit.SECONDS).getCode());
                    fixture.drain();
                    assertNull(beforeRelease.total.subtotal);
                    assertNull(owner.modelTokenUsageSnapshot().total.subtotal);
                    assertEquals(0, owner.modelTokenUsageSnapshot().total.reportedCalls);
                    RunControl next = intervention == 1 ? owner : replacement;
                    request(client, server.settings(), next, (control, epoch) -> true);
                    assertField(next.modelTokenUsageSnapshot().total, 3L, 1);
                    assertEquals(intervention == 1 ? 2 : 1, next.modelTokenUsageSnapshot().calls);
                    assertEquals(intervention == 1 ? ModelTokenUsage.Completeness.PARTIAL : ModelTokenUsage.Completeness.COMPLETE,
                            next.modelTokenUsageSnapshot().completeness);
                    assertEquals(2, server.requests.get());
                    server.assertHealthy();
                } finally { fixture.gate.release.countDown(); owner.cancel(); replacement.cancel(); }
            }
        }
    }

    @Test(timeout = 10000) public void timedOutCallerDoesNotAdoptALateWorkerReportWhileOwnerRemainsActive() throws Exception {
        try (Server server = new Server((index, request) -> reply("\"usage\":" + COMPLETE));
             Network fixture = new Network()) {
            RunControl owner = new RunControl();
            AtomicBoolean first = new AtomicBoolean(true);
            HttpModelClient client = fixture.client(url -> first.getAndSet(false)
                    ? new FinalizingConnection(url, fixture.gate) : (HttpURLConnection) url.openConnection(), 1500);
            try {
                Future<ModelException> result = fixture.caller.submit(() -> assertThrows(ModelException.class,
                        () -> request(client, server.settings(), owner, (control, epoch) -> true)));
                assertTrue(fixture.gate.entered.await(3, TimeUnit.SECONDS));
                assertEquals("TIMEOUT", result.get(3, TimeUnit.SECONDS).code);
                assertFalse("Gateway timeout alone does not cancel this direct caller's owner", owner.isCancelled());
                ModelTokenUsage timedOut = owner.modelTokenUsageSnapshot();
                assertEquals(1, timedOut.calls);
                assertNull(timedOut.total.subtotal);
                fixture.gate.release.countDown();
                fixture.drain();
                assertNull(owner.modelTokenUsageSnapshot().total.subtotal);
                request(client, server.settings(), owner, (control, epoch) -> true);
                assertEquals(2, owner.modelTokenUsageSnapshot().calls);
                assertField(owner.modelTokenUsageSnapshot().total, 3L, 1);
                assertEquals(ModelTokenUsage.Completeness.PARTIAL, owner.modelTokenUsageSnapshot().completeness);
                assertNull(timedOut.total.subtotal);
                assertEquals(2, server.requests.get());
                server.assertHealthy();
            } finally { fixture.gate.release.countDown(); owner.cancel(); }
        }
    }

    @Test(timeout = 7000) public void queueRejectionCountsALocalCallButInvalidInitialContextDoesNot() throws Exception {
        try (Server server = new Server((index, request) -> reply("\"usage\":" + COMPLETE));
             Network fixture = new Network()) {
            RunControl queued = new RunControl(), invalid = new RunControl();
            try {
                fixture.network.shutdown();
                HttpModelClient client = fixture.client(url -> (HttpURLConnection) url.openConnection());
                ModelException rejected = assertThrows(ModelException.class,
                        () -> request(client, server.settings(), queued, (control, epoch) -> true));
                assertEquals("NETWORK_BUSY", rejected.code);
                assertEquals(1, queued.modelTokenUsageSnapshot().calls);
                assertNull(queued.modelTokenUsageSnapshot().total.subtotal);
                assertThrows(TaskControlException.class,
                        () -> request(client, server.settings(), invalid, (control, epoch) -> false));
                assertEquals(0, invalid.modelTokenUsageSnapshot().calls);
                assertEquals(0, server.requests.get());
                assertEquals(0, fixture.workset.reservedBytes());
                server.assertHealthy();
            } finally { queued.cancel(); invalid.cancel(); }
        }
    }

    private static JsonObject request(HttpModelClient client, ModelSettings settings, RunControl owner, DisclosureGuard guard)
            throws Exception {
        return client.request(settings, new JsonArray(), new JsonArray(),
                new ModelRequestContext(owner, owner.getExecutionEpoch(), guard));
    }
    private static String reply(String members) { return "{" + CHOICES + (members.isEmpty() ? "" : "," + members) + "}"; }
    private static void assertField(ModelTokenUsage.Field field, long value, long calls) {
        assertEquals(Long.valueOf(value), field.subtotal); assertEquals(calls, field.reportedCalls); assertFalse(field.overflowed);
    }
    private static ToolHandler observations(AtomicInteger calls) {
        return (name, arguments, owner) -> {
            assertEquals("observe", name); calls.incrementAndGet();
            return JsonParser.parseString("{\"ok\":true,\"data\":{\"revision\":\"r1\",\"nodes\":[]}}").getAsJsonObject();
        };
    }
    private static AgentRunner.Listener events(AtomicReference<TaskResult> result) {
        return new AgentRunner.Listener() {
            @Override public void onStatus(String value) { }
            @Override public void onStep(String value) { }
            @Override public void onFinished(String value, boolean verified) { }
            @Override public void onResult(TaskResult value) { result.set(value); }
        };
    }

    @FunctionalInterface private interface Reply { String body(int request, JsonObject input) throws Exception; }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Server(Reply reply) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(worker);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JsonObject input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    byte[] body = reply.body(requests.incrementAndGet(), input).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException cancelled) { /* The client may close an already submitted call. */ }
                catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { exchange.close(); }
            });
            server.start();
        }
        ModelSettings settings() { return new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic-key", "synthetic-model"); }
        void assertHealthy() { assertNull("Unexpected HTTP fixture failure", failure.get()); }
        @Override public void close() throws InterruptedException {
            server.stop(0); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void awaitRelease() {
            entered.countDown();
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (release.getCount() != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new AssertionError("HTTP finalization barrier expired");
                    try { assertTrue(release.await(remaining, TimeUnit.NANOSECONDS)); }
                    catch (InterruptedException stopped) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private static final class Network implements AutoCloseable {
        final Gate gate = new Gate();
        final ImageWorkset workset = new ImageWorkset(4 * 1024 * 1024);
        final ThreadPoolExecutor network = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> new Thread(task, "token-usage-network"));
        final ExecutorService closer = Executors.newSingleThreadExecutor(), caller = Executors.newSingleThreadExecutor();
        HttpModelClient client(HttpModelClient.ConnectionFactory factory) { return client(factory, 5000); }
        HttpModelClient client(HttpModelClient.ConnectionFactory factory, long timeout) {
            return new HttpModelClient(timeout, factory, network, closer, workset);
        }
        void drain() throws Exception { network.submit(() -> {}).get(3, TimeUnit.SECONDS); }
        @Override public void close() throws InterruptedException {
            gate.release.countDown(); caller.shutdownNow(); network.shutdownNow(); closer.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(network.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(closer.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, workset.reservedBytes());
        }
    }
    /** Holds after parsing but before Future completion; the transport itself is real HTTP. */
    private static final class FinalizingConnection extends HttpURLConnection {
        private final HttpURLConnection delegate;
        private final Gate gate;
        private final AtomicBoolean held = new AtomicBoolean();
        FinalizingConnection(URL url, Gate gate) throws IOException { super(url); delegate = (HttpURLConnection) url.openConnection(); this.gate = gate; }
        @Override public void disconnect() {
            try {
                if (Thread.currentThread().getName().equals("token-usage-network") && held.compareAndSet(false, true)) gate.awaitRelease();
            } finally { delegate.disconnect(); }
        }
        @Override public OutputStream getOutputStream() throws IOException { return delegate.getOutputStream(); }
        @Override public InputStream getInputStream() throws IOException { return delegate.getInputStream(); }
        @Override public int getResponseCode() throws IOException { return delegate.getResponseCode(); }
        @Override public long getContentLengthLong() { return delegate.getContentLengthLong(); }
        @Override public void setInstanceFollowRedirects(boolean value) { delegate.setInstanceFollowRedirects(value); }
        @Override public void setConnectTimeout(int value) { delegate.setConnectTimeout(value); }
        @Override public void setReadTimeout(int value) { delegate.setReadTimeout(value); }
        @Override public void setRequestMethod(String value) throws ProtocolException { delegate.setRequestMethod(value); }
        @Override public void setDoOutput(boolean value) { delegate.setDoOutput(value); }
        @Override public void setRequestProperty(String key, String value) { delegate.setRequestProperty(key, value); }
        @Override public void setFixedLengthStreamingMode(int value) { delegate.setFixedLengthStreamingMode(value); }
        @Override public void connect() throws IOException { delegate.connect(); }
        @Override public boolean usingProxy() { return delegate.usingProxy(); }
    }
}
