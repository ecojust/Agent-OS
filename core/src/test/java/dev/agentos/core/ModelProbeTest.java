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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Protocol probes use a real local HTTP server, without a device executor. */
public class ModelProbeTest {
    private static final String KEY = "private-probe-key-never-disclose";
    private static final String PRIVATE_PROFILE = "personal-profile-must-stay-local";
    private static final String SERVER_DETAIL = "private-server-detail-must-not-be-shown";

    private static final class Request {
        final String path;
        final String authorization;
        final String body;

        Request(String path, String authorization, String body) {
            this.path = path;
            this.authorization = authorization;
            this.body = body;
        }

        JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
    }

    private static final class Reply {
        final int status;
        final String body;
        final String location;

        Reply(String body) { this(200, body, null); }

        Reply(int status, String body, String location) {
            this.status = status;
            this.body = body;
            this.location = location;
        }
    }

    @FunctionalInterface
    private interface Responder { Reply respond(Request request) throws Exception; }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Server(Responder responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    Request request = new Request(exchange.getRequestURI().toString(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    requests.add(request);
                    Reply reply = responder.respond(request);
                    byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    if (reply.location != null) exchange.getResponseHeaders().add("Location", reply.location);
                    exchange.sendResponseHeaders(reply.status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // Cancellation and total deadlines may close the client socket first.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        ModelSettings settings() { return new ModelSettings(endpoint(), KEY, "synthetic-test-model"); }
        void assertHealthy() { assertNull("local server failed: " + failure.get(), failure.get()); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }

    private static String nonce(Request request) {
        return request.json().getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonObject("function").getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("nonce")
                .getAsJsonArray("enum").get(0).getAsString();
    }

    private static JsonObject validReply(Request request) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("nonce", nonce(request));
        JsonObject function = new JsonObject();
        function.addProperty("name", "agent_os_probe");
        function.addProperty("arguments", arguments.toString());
        JsonObject call = new JsonObject();
        call.addProperty("id", "probe_01.valid:call-id");
        call.addProperty("type", "function");
        call.add("function", function);
        JsonArray calls = new JsonArray(); calls.add(call);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.add("tool_calls", calls);
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject(); response.add("choices", choices);
        return response;
    }

    private static JsonObject message(JsonObject response) {
        return response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
    }

    private static JsonObject call(JsonObject response) {
        return message(response).getAsJsonArray("tool_calls").get(0).getAsJsonObject();
    }

    private static void assertRedacted(ModelProbe.Result result) {
        assertNotNull(result.message);
        assertFalse(result.message.isBlank());
        assertNotNull(result.code);
        assertFalse(result.code.isBlank());
        assertFalse(result.message.contains(KEY));
        assertFalse(result.message.contains(SERVER_DETAIL));
        assertFalse(result.code.contains(KEY));
        assertFalse(result.code.contains(SERVER_DETAIL));
    }

    @Test public void verifiesOnlySyntheticToolAndSendsNoDeviceOrTaskContext() throws Exception {
        try (Server server = new Server(request -> new Reply(validReply(request).toString()))) {
            ModelProbe probe = new ModelProbe();
            List<String> nonces = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                RunControl control = new RunControl(PRIVATE_PROFILE);
                ModelProbe.Result result = probe.test(server.settings(), control);
                assertTrue(result.connected);
                assertTrue(result.toolCallingVerified);
                assertRedacted(result);
                assertEquals(1, control.budgetSnapshot().modelRounds);
                assertEquals("a protocol probe cannot dispatch device tools", 0, control.budgetSnapshot().toolCalls);

                Request request = server.requests.get(i);
                assertEquals("/v1/chat/completions", request.path);
                assertEquals("Bearer " + KEY, request.authorization);
                assertFalse(request.body.contains(KEY));
                assertFalse(request.body.contains(PRIVATE_PROFILE));
                assertFalse(request.body.contains(control.getTaskId()));
                assertFalse(request.body.contains("image_url"));
                assertFalse(request.body.contains("expectedRevision"));
                JsonObject body = request.json();
                assertEquals("synthetic-test-model", body.get("model").getAsString());
                JsonArray messages = body.getAsJsonArray("messages");
                assertEquals(2, messages.size());
                assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
                assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
                for (JsonElement element : messages) {
                    JsonObject entry = element.getAsJsonObject();
                    assertEquals(2, entry.size());
                    assertTrue(entry.get("content").getAsJsonPrimitive().isString());
                }
                JsonArray tools = body.getAsJsonArray("tools");
                assertEquals(1, tools.size());
                JsonObject tool = tools.get(0).getAsJsonObject();
                assertEquals("function", tool.get("type").getAsString());
                JsonObject function = tool.getAsJsonObject("function");
                assertEquals("agent_os_probe", function.get("name").getAsString());
                JsonObject parameters = function.getAsJsonObject("parameters");
                assertFalse(parameters.get("additionalProperties").getAsBoolean());
                assertEquals(1, parameters.getAsJsonObject("properties").size());
                assertEquals(1, parameters.getAsJsonArray("required").size());
                assertEquals("nonce", parameters.getAsJsonArray("required").get(0).getAsString());
                JsonObject nonceSchema = parameters.getAsJsonObject("properties").getAsJsonObject("nonce");
                assertEquals("string", nonceSchema.get("type").getAsString());
                assertEquals(1, nonceSchema.getAsJsonArray("enum").size());
                String nonce = nonce(request);
                assertFalse(nonce.isBlank());
                assertTrue(messages.get(1).getAsJsonObject().get("content").getAsString().contains(nonce));
                nonces.add(nonce);
            }
            assertNotEquals("each probe must use a fresh challenge", nonces.get(0), nonces.get(1));
            assertEquals(2, server.requests.size());
            server.assertHealthy();
        }
    }

    @Test public void validJsonWithoutVerifiedToolStaysConnectedButUnverified() throws Exception {
        String[] replies = {
                "{}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Everything worked\"}}]}",
                "{\"error\":{\"message\":\"" + SERVER_DETAIL + KEY + "\"}}"
        };
        for (String reply : replies) {
            try (Server server = new Server(request -> new Reply(reply))) {
                ModelProbe.Result result = new ModelProbe().test(server.settings(), new RunControl());
                assertTrue("valid response must remain distinguishable from connection failure", result.connected);
                assertFalse(result.toolCallingVerified);
                assertRedacted(result);
                assertEquals(1, server.requests.size());
                server.assertHealthy();
            }
        }
    }

    @Test public void rejectsWrongNonceExtraArgumentsAndNonStrictArgumentJson() throws Exception {
        List<Consumer<JsonObject>> mutations = List.of(
                response -> call(response).getAsJsonObject("function").addProperty("arguments", "{\"nonce\":\"wrong\"}"),
                response -> {
                    JsonObject function = call(response).getAsJsonObject("function");
                    JsonObject args = JsonParser.parseString(function.get("arguments").getAsString()).getAsJsonObject();
                    args.addProperty("extra", true); function.addProperty("arguments", args.toString());
                },
                response -> call(response).getAsJsonObject("function").addProperty("arguments", "{}"),
                response -> call(response).getAsJsonObject("function").addProperty("arguments", "{\"nonce\":1}"),
                response -> call(response).getAsJsonObject("function").addProperty("arguments", "{\"nonce\":null}"),
                response -> call(response).getAsJsonObject("function").addProperty("arguments", "[]"),
                response -> call(response).getAsJsonObject("function").add("arguments", new JsonObject()),
                response -> {
                    JsonObject function = call(response).getAsJsonObject("function");
                    function.addProperty("arguments", "/* comment */" + function.get("arguments").getAsString());
                },
                response -> {
                    JsonObject function = call(response).getAsJsonObject("function");
                    function.addProperty("arguments", function.get("arguments").getAsString() + "{}");
                },
                response -> call(response).getAsJsonObject("function").addProperty("arguments", " ".repeat(4097))
        );
        assertUnverifiedMutations(mutations);
    }

    @Test public void rejectsWrongRolesCallTypesNamesIdsAndAmbiguousCallCounts() throws Exception {
        List<Consumer<JsonObject>> mutations = List.of(
                response -> message(response).addProperty("role", "user"),
                response -> message(response).remove("role"),
                response -> response.getAsJsonArray("choices").add(response.getAsJsonArray("choices").get(0).deepCopy()),
                response -> message(response).getAsJsonArray("tool_calls").add(call(response).deepCopy()),
                response -> call(response).addProperty("type", "custom"),
                response -> call(response).getAsJsonObject("function").addProperty("name", "click"),
                response -> call(response).addProperty("id", ""),
                response -> call(response).addProperty("id", "id with spaces"),
                response -> call(response).addProperty("id", "id\nnewline"),
                response -> call(response).addProperty("id", "i".repeat(129)),
                response -> call(response).addProperty("id", 5),
                response -> call(response).remove("id")
        );
        assertUnverifiedMutations(mutations);
    }

    private static void assertUnverifiedMutations(List<Consumer<JsonObject>> mutations) throws Exception {
        for (int i = 0; i < mutations.size(); i++) {
            Consumer<JsonObject> mutation = mutations.get(i);
            try (Server server = new Server(request -> {
                JsonObject response = validReply(request); mutation.accept(response);
                return new Reply(response.toString());
            })) {
                RunControl control = new RunControl();
                ModelProbe.Result result = new ModelProbe().test(server.settings(), control);
                assertTrue("variant " + i + " returned valid JSON", result.connected);
                assertFalse("variant " + i + " must not validate an invalid tool", result.toolCallingVerified);
                assertEquals(0, control.budgetSnapshot().toolCalls);
                assertEquals(1, server.requests.size());
                assertRedacted(result);
                server.assertHealthy();
            }
        }
    }

    @Test public void authenticationAndServerErrorsNeverEchoResponseBodiesOrKeys() throws Exception {
        for (int status : new int[] {401, 500}) {
            List<String> messages = new ArrayList<>();
            for (String detail : new String[] {SERVER_DETAIL + KEY, "a different remote diagnostic"}) {
                try (Server server = new Server(request -> new Reply(status, "{\"error\":\"" + detail + "\"}", null))) {
                    ModelProbe.Result result = new ModelProbe().test(server.settings(), new RunControl());
                    assertFalse(result.connected);
                    assertFalse(result.toolCallingVerified);
                    assertRedacted(result);
                    assertFalse(result.message.contains(detail));
                    messages.add(result.message);
                    assertEquals("failed request must not be silently retried", 1, server.requests.size());
                    server.assertHealthy();
                }
            }
            assertEquals("remote diagnostics cannot affect user-facing error text", messages.get(0), messages.get(1));
        }
    }

    @Test public void invalidResponseJsonDoesNotCountAsSuccessfulConnection() throws Exception {
        for (String reply : new String[] {SERVER_DETAIL + KEY, "[]", "{", "{}{}"}) {
            try (Server server = new Server(request -> new Reply(reply))) {
                ModelProbe.Result result = new ModelProbe().test(server.settings(), new RunControl());
                assertFalse(result.connected);
                assertFalse(result.toolCallingVerified);
                assertRedacted(result);
                assertEquals(1, server.requests.size());
                server.assertHealthy();
            }
        }
    }

    @Test public void redirectsNeverForwardTheProbeOrAuthorizationToAnotherEndpoint() throws Exception {
        try (Server destination = new Server(request -> new Reply(validReply(request).toString()));
             Server origin = new Server(request -> new Reply(302, SERVER_DETAIL + KEY,
                     destination.endpoint() + "/chat/completions"))) {
            ModelProbe.Result result = new ModelProbe().test(origin.settings(), new RunControl());
            assertFalse(result.connected);
            assertFalse(result.toolCallingVerified);
            assertRedacted(result);
            assertEquals(1, origin.requests.size());
            assertTrue(destination.requests.isEmpty());
            origin.assertHealthy(); destination.assertHealthy();
        }
    }

    @Test(timeout = 5000) public void refusedConnectionReturnsARedactedFailure() throws Exception {
        String endpoint;
        try (Server server = new Server(request -> new Reply("{}"))) { endpoint = server.endpoint(); }
        ModelProbe.Result result = new ModelProbe(new HttpModelClient(150)).test(
                new ModelSettings(endpoint, KEY, "synthetic-test-model"), new RunControl());
        assertFalse(result.connected);
        assertFalse(result.toolCallingVerified);
        assertRedacted(result);
    }

    @Test(timeout = 5000) public void deadlineReturnsFailureWithoutWaitingForTheDefaultTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server(request -> {
            release.await(3, TimeUnit.SECONDS);
            return new Reply(validReply(request).toString());
        })) {
            long start = System.nanoTime();
            ModelProbe.Result result = new ModelProbe(new HttpModelClient(150)).test(server.settings(), new RunControl());
            assertFalse(result.connected);
            assertFalse(result.toolCallingVerified);
            assertRedacted(result);
            assertTrue("short transport deadline must remain bounded", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
            assertEquals(1, server.requests.size());
            server.assertHealthy();
        } finally { release.countDown(); }
    }

    @Test public void invalidConfigurationDoesNotMakeANetworkRequest() throws Exception {
        try (Server server = new Server(request -> new Reply(validReply(request).toString()))) {
            ModelSettings invalid = new ModelSettings(server.endpoint() + "?secret=" + KEY, KEY, "synthetic-test-model");
            ModelProbe.Result result = new ModelProbe().test(invalid, new RunControl());
            assertFalse(result.connected);
            assertFalse(result.toolCallingVerified);
            assertRedacted(result);
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        }
    }

    @Test public void cancelledProbeThrowsBeforeSendingAnyRequest() throws Exception {
        try (Server server = new Server(request -> new Reply(validReply(request).toString()))) {
            RunControl control = new RunControl(); control.cancel();
            TaskControlException failure = assertThrows(TaskControlException.class,
                    () -> new ModelProbe().test(server.settings(), control));
            assertEquals("TASK_STOPPED", failure.getCode());
            assertTrue(server.requests.isEmpty());
            server.assertHealthy();
        }
    }

    @Test(timeout = 8000) public void cancellingInFlightProbeRemainsAnInterruptionInsteadOfAResult() throws Exception {
        assertInterruptsInFlight(false);
    }

    @Test(timeout = 8000) public void pausingInFlightProbeInvalidatesItsChallengeInsteadOfReportingSuccess() throws Exception {
        assertInterruptsInFlight(true);
    }

    private static void assertInterruptsInFlight(boolean pause) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RunControl control = new RunControl();
        try (Server server = new Server(request -> {
            entered.countDown(); release.await(5, TimeUnit.SECONDS);
            return new Reply(validReply(request).toString());
        })) {
            Future<ModelProbe.Result> future = worker.submit(() -> new ModelProbe().test(server.settings(), control));
            assertTrue("request must reach the server before interruption", entered.await(2, TimeUnit.SECONDS));
            if (pause) control.pause(); else control.cancel();
            ExecutionException wrapped = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(wrapped.getCause() instanceof TaskControlException);
            assertEquals(pause ? "TASK_PAUSED" : "TASK_STOPPED", ((TaskControlException) wrapped.getCause()).getCode());
            assertEquals(1, server.requests.size());
            assertEquals(0, control.budgetSnapshot().toolCalls);
            server.assertHealthy();
        } finally {
            release.countDown(); control.cancel(); worker.shutdownNow();
        }
    }
}
