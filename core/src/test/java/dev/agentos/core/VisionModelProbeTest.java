package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/** Real HTTP tests; the response oracle decodes the transmitted PNG, not production state. */
public class VisionModelProbeTest {
    private static final String KEY = "private-vision-key-never-disclose";
    private static final String PRIVATE_PROFILE = "private-profile-not-image-test-input";
    private static final String SERVER_DETAIL = "private-vision-server-diagnostic";
    private static final List<String> COLOR_NAMES = List.of(
            "red", "green", "blue", "yellow", "cyan", "magenta", "black", "white");
    private static final List<Integer> COLOR_RGB = List.of(
            0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0x00ffff, 0xff00ff, 0x000000, 0xffffff);

    private static final class Request {
        final String path, authorization, body;
        Request(String path, String authorization, String body) {
            this.path = path; this.authorization = authorization; this.body = body;
        }
        JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
    }

    private static final class Reply {
        final int status;
        final String body, location;
        Reply(String body) { this(200, body, null); }
        Reply(int status, String body, String location) {
            this.status = status; this.body = body; this.location = location;
        }
    }

    @FunctionalInterface private interface Responder { Reply respond(Request request) throws Exception; }

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
                    // A stopped or timed-out client may disconnect before a fixture response.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally { exchange.close(); }
            });
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        ModelSettings settings() { return new ModelSettings(endpoint(), KEY, "synthetic-vision-model"); }
        void assertHealthy() { assertNull("local server failure: " + failure.get(), failure.get()); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }

    private static JsonObject parameters(Request request) {
        return request.json().getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonObject("function").getAsJsonObject("parameters");
    }

    private static String nonce(Request request) {
        return parameters(request).getAsJsonObject("properties").getAsJsonObject("nonce")
                .getAsJsonArray("enum").get(0).getAsString();
    }

    private static JsonArray content(Request request) {
        JsonArray messages = request.json().getAsJsonArray("messages");
        assertEquals("only a synthetic system message and image challenge are sent", 2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(messages.get(0).getAsJsonObject().get("content").getAsJsonPrimitive().isString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        JsonArray content = messages.get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals("exactly one text and one image part", 2, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("image_url", content.get(1).getAsJsonObject().get("type").getAsString());
        return content;
    }

    /** The only source of answer order is the actual image received by the HTTP server. */
    private static JsonArray decodedColors(Request request) throws IOException {
        JsonArray content = content(request);
        String url = content.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString();
        String prefix = "data:image/png;base64,";
        assertTrue("image must be an inline PNG, not a remote resource", url.startsWith(prefix));
        byte[] png = Base64.getDecoder().decode(url.substring(prefix.length()));
        assertTrue(png.length > 33);
        assertTrue("synthetic PNG stays within its fixed encoding budget", png.length <= 64 * 1024);
        assertArrayEquals(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10},
                java.util.Arrays.copyOf(png, 8));
        assertEquals("IHDR", new String(png, 12, 4, StandardCharsets.US_ASCII));
        assertEquals("PNG sample depth", 8, png[24] & 255);
        assertEquals("PNG must contain RGB, without an alpha channel", 2, png[25] & 255);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull("ImageIO must independently decode the transmitted PNG", image);
        assertEquals(512, image.getWidth());
        assertEquals(256, image.getHeight());
        JsonArray answer = new JsonArray();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                int rgb = image.getRGB(64 + 128 * col, 64 + 128 * row) & 0xffffff;
                int index = COLOR_RGB.indexOf(rgb);
                assertTrue("center must be one of the eight specified colors", index >= 0);
                answer.add(COLOR_NAMES.get(index));
                for (int y : new int[] {0, 7, 8, 64, 119, 120, 127}) {
                    for (int x : new int[] {0, 7, 8, 64, 119, 120, 127}) {
                        int expected = x < 8 || x >= 120 || y < 8 || y >= 120 ? 0x808080 : rgb;
                        assertEquals("each cell has an 8px neutral margin", expected,
                                image.getRGB(col * 128 + x, row * 128 + y) & 0xffffff);
                    }
                }
            }
        }
        assertEquals("every color appears exactly once", new HashSet<>(COLOR_NAMES), strings(answer));
        return answer;
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> result = new HashSet<>();
        for (JsonElement element : array) result.add(element.getAsString());
        return result;
    }

    private static JsonObject validReply(Request request) throws IOException {
        JsonObject args = new JsonObject(); args.addProperty("nonce", nonce(request));
        args.add("colors", decodedColors(request));
        JsonObject function = new JsonObject(); function.addProperty("name", "agent_os_vision_probe");
        function.addProperty("arguments", args.toString());
        JsonObject call = new JsonObject(); call.addProperty("id", "vision_01.valid:call-id");
        call.addProperty("type", "function"); call.add("function", function);
        JsonArray calls = new JsonArray(); calls.add(call);
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.add("tool_calls", calls);
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject(); response.add("choices", choices); return response;
    }

    private static JsonObject message(JsonObject response) {
        return response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
    }
    private static JsonObject call(JsonObject response) {
        return message(response).getAsJsonArray("tool_calls").get(0).getAsJsonObject();
    }
    private static JsonObject function(JsonObject response) { return call(response).getAsJsonObject("function"); }
    private static JsonObject arguments(JsonObject response) {
        return JsonParser.parseString(function(response).get("arguments").getAsString()).getAsJsonObject();
    }
    private static void changeArguments(JsonObject response, Consumer<JsonObject> mutation) {
        JsonObject args = arguments(response); mutation.accept(args);
        function(response).addProperty("arguments", args.toString());
    }

    private static void assertRedacted(VisionModelProbe.Result result) {
        assertNotNull(result.message); assertFalse(result.message.isBlank());
        assertNotNull(result.code); assertFalse(result.code.isBlank());
        for (String secret : List.of(KEY, PRIVATE_PROFILE, SERVER_DETAIL, "data:image/png;base64,")) {
            assertFalse(result.message.contains(secret)); assertFalse(result.code.contains(secret));
        }
    }
    private static void assertOnlyModelRound(RunControl control) {
        assertEquals(1, control.budgetSnapshot().modelRounds);
        assertEquals("synthetic tools are never dispatched", 0, control.budgetSnapshot().toolCalls);
        assertEquals("the probe cannot submit a device write", 0, control.getDispatchAttempts());
        assertFalse(control.hasInFlightDispatch());
    }
    private static void assertUnverified(VisionModelProbe.Result result) {
        assertTrue(result.connected); assertFalse(result.syntheticImageVerified);
        assertEquals("SYNTHETIC_IMAGE_UNVERIFIED", result.code); assertRedacted(result);
    }

    @Test public void independentlyDecodesTheSingleImageAndKeepsPromptSchemaAndPrivateStateSeparate() throws Exception {
        try (Server server = new Server(request -> new Reply(validReply(request).toString()))) {
            VisionModelProbe probe = new VisionModelProbe();
            Set<String> nonces = new HashSet<>();
            JsonObject firstNormalized = null;
            for (int round = 0; round < 3; round++) {
                RunControl control = new RunControl(PRIVATE_PROFILE);
                VisionModelProbe.Result result = probe.test(server.settings(), control);
                assertTrue(result.connected); assertTrue(result.syntheticImageVerified);
                assertEquals("SYNTHETIC_IMAGE_VERIFIED", result.code);
                assertOnlyModelRound(control); assertRedacted(result);
                Request request = server.requests.get(round);
                assertEquals("/v1/chat/completions", request.path);
                assertEquals("Bearer " + KEY, request.authorization);
                for (String forbidden : List.of(KEY, PRIVATE_PROFILE, control.getTaskId(),
                        "expectedRevision", "packageName", "windowId", "nodeId", "executionEpoch")) {
                    assertFalse("synthetic request must not disclose " + forbidden, request.body.contains(forbidden));
                }
                JsonObject body = request.json();
                assertEquals("synthetic-vision-model", body.get("model").getAsString());
                JsonArray tools = body.getAsJsonArray("tools"); assertEquals(1, tools.size());
                JsonObject tool = tools.get(0).getAsJsonObject(); assertEquals("function", tool.get("type").getAsString());
                assertEquals("agent_os_vision_probe", tool.getAsJsonObject("function").get("name").getAsString());
                JsonObject params = parameters(request);
                assertEquals("object", params.get("type").getAsString());
                assertFalse(params.get("additionalProperties").getAsBoolean());
                assertEquals(Set.of("nonce", "colors"), params.getAsJsonObject("properties").keySet());
                assertEquals(Set.of("nonce", "colors"), strings(params.getAsJsonArray("required")));
                assertEquals(2, params.getAsJsonArray("required").size());
                JsonObject nonceSchema = params.getAsJsonObject("properties").getAsJsonObject("nonce");
                assertEquals("string", nonceSchema.get("type").getAsString());
                assertEquals(1, nonceSchema.getAsJsonArray("enum").size());
                String nonce = nonce(request);
                assertEquals(nonce, java.util.UUID.fromString(nonce).toString());
                assertTrue("each probe must use a fresh nonce", nonces.add(nonce));
                JsonObject colors = params.getAsJsonObject("properties").getAsJsonObject("colors");
                assertEquals("array", colors.get("type").getAsString());
                assertEquals(8, colors.get("minItems").getAsInt()); assertEquals(8, colors.get("maxItems").getAsInt());
                assertTrue(colors.get("uniqueItems").getAsBoolean());
                assertEquals("string", colors.getAsJsonObject("items").get("type").getAsString());
                JsonArray allowed = colors.getAsJsonObject("items").getAsJsonArray("enum");
                assertEquals(8, allowed.size());
                for (int i = 0; i < 8; i++) assertEquals(COLOR_NAMES.get(i), allowed.get(i).getAsString());
                JsonArray content = content(request);
                assertTrue(content.get(0).getAsJsonObject().get("text").getAsString().contains(nonce));
                decodedColors(request);

                // Normalize only nonce and image bytes. Any answer-dependent prompt/schema change fails.
                JsonObject normalized = JsonParser.parseString(request.body.replace(nonce, "NONCE")).getAsJsonObject();
                normalized.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content")
                        .get(1).getAsJsonObject().getAsJsonObject("image_url").addProperty("url", "IMAGE");
                if (firstNormalized == null) firstNormalized = normalized;
                else assertEquals("only nonce and image may vary between synthetic requests", firstNormalized, normalized);
            }
            assertEquals(3, server.requests.size()); server.assertHealthy();
        }
    }

    @Test public void correctArgumentsMayUseEitherFieldOrder() throws Exception {
        try (Server server = new Server(request -> {
            JsonObject response = validReply(request); JsonObject args = arguments(response);
            function(response).addProperty("arguments", " { \"colors\" : " + args.get("colors")
                    + ", \"nonce\" : " + args.get("nonce") + " } \n");
            return new Reply(response.toString());
        })) {
            RunControl control = new RunControl();
            VisionModelProbe.Result result = new VisionModelProbe().test(server.settings(), control);
            assertTrue(result.connected); assertTrue(result.syntheticImageVerified);
            assertOnlyModelRound(control); assertEquals(1, server.requests.size()); server.assertHealthy();
        }
    }

    @Test public void wrongOrderMissingColorsDuplicatesAndNonStringColorsCannotVerifyTheImage() throws Exception {
        assertUnverifiedMutations(List.of(
                response -> changeArguments(response, args -> {
                    JsonArray colors = args.getAsJsonArray("colors"); JsonElement first = colors.get(0);
                    colors.set(0, colors.get(1)); colors.set(1, first);
                }),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").remove(7)),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").add("red")),
                response -> changeArguments(response, args -> {
                    JsonArray colors = args.getAsJsonArray("colors"); colors.set(1, colors.get(0));
                }),
                response -> changeArguments(response, args -> {
                    JsonArray colors = new JsonArray(); for (int i = 0; i < 8; i++) colors.add("red");
                    args.add("colors", colors); // A fixed image-ignoring response cannot pass any shuffle.
                }),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").set(0, new JsonPrimitive(1))),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").set(0, new JsonPrimitive(true))),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").set(0, JsonNull.INSTANCE)),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").set(0, new JsonObject())),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").set(0, new JsonPrimitive("orange"))),
                response -> changeArguments(response, args -> args.getAsJsonArray("colors").set(0,
                        new JsonPrimitive(args.getAsJsonArray("colors").get(0).getAsString().toUpperCase(java.util.Locale.ROOT))))
        ));
    }

    @Test public void previousResponseAndPreviousNonceCannotVerifyANewChallenge() throws Exception {
        AtomicReference<JsonObject> previous = new AtomicReference<>();
        try (Server server = new Server(request -> {
            JsonObject current = validReply(request);
            JsonObject stale = previous.get();
            if (stale == null) { previous.set(current.deepCopy()); return new Reply(current.toString()); }
            return new Reply(stale.toString());
        })) {
            VisionModelProbe probe = new VisionModelProbe();
            RunControl first = new RunControl(), next = new RunControl();
            assertTrue(probe.test(server.settings(), first).syntheticImageVerified);
            assertUnverified(probe.test(server.settings(), next));
            assertNotEquals(nonce(server.requests.get(0)), nonce(server.requests.get(1)));
            assertOnlyModelRound(first); assertOnlyModelRound(next);
            assertEquals(2, server.requests.size()); server.assertHealthy();
        }
        AtomicReference<String> oldNonce = new AtomicReference<>();
        try (Server server = new Server(request -> {
            JsonObject response = validReply(request);
            String old = oldNonce.getAndSet(nonce(request));
            if (old != null) changeArguments(response, args -> args.addProperty("nonce", old));
            return new Reply(response.toString());
        })) {
            VisionModelProbe probe = new VisionModelProbe();
            assertTrue(probe.test(server.settings(), new RunControl()).syntheticImageVerified);
            assertUnverified(probe.test(server.settings(), new RunControl()));
            assertEquals(2, server.requests.size()); server.assertHealthy();
        }
    }

    @Test public void strictArgumentsRejectExtraDuplicateMissingCoercedAndTrailingValues() throws Exception {
        assertUnverifiedMutations(List.of(
                response -> changeArguments(response, args -> args.addProperty("extra", SERVER_DETAIL + KEY)),
                response -> changeArguments(response, args -> args.remove("nonce")),
                response -> changeArguments(response, args -> args.remove("colors")),
                response -> changeArguments(response, args -> args.addProperty("nonce", "stale-nonce")),
                response -> changeArguments(response, args -> args.addProperty("nonce", 123)),
                response -> changeArguments(response, args -> args.add("nonce", JsonNull.INSTANCE)),
                response -> changeArguments(response, args -> args.addProperty("colors", "red green blue")),
                response -> changeArguments(response, args -> args.add("colors", JsonNull.INSTANCE)),
                response -> {
                    JsonObject args = arguments(response);
                    function(response).addProperty("arguments", "{\"nonce\":" + args.get("nonce")
                            + ",\"nonce\":" + args.get("nonce") + ",\"colors\":" + args.get("colors") + "}");
                },
                response -> {
                    JsonObject args = arguments(response);
                    function(response).addProperty("arguments", "{\"nonce\":" + args.get("nonce")
                            + ",\"colors\":" + args.get("colors") + ",\"colors\":" + args.get("colors") + "}");
                },
                response -> function(response).addProperty("arguments", "[]"),
                response -> function(response).add("arguments", arguments(response)),
                response -> function(response).addProperty("arguments", "/* comment */" + function(response).get("arguments").getAsString()),
                response -> function(response).addProperty("arguments", function(response).get("arguments").getAsString() + "{}"),
                response -> function(response).addProperty("arguments", " ".repeat(4097))
        ));
    }

    @Test public void rejectsWrongRoleToolNameTypeCallIdAndAmbiguousResponseCounts() throws Exception {
        assertUnverifiedMutations(List.of(
                response -> message(response).addProperty("role", "user"),
                response -> message(response).remove("role"),
                response -> response.getAsJsonArray("choices").add(response.getAsJsonArray("choices").get(0).deepCopy()),
                response -> message(response).getAsJsonArray("tool_calls").add(call(response).deepCopy()),
                response -> message(response).getAsJsonArray("tool_calls").remove(0),
                response -> function(response).addProperty("name", "agent_os_probe"),
                response -> function(response).addProperty("name", "click"),
                response -> call(response).addProperty("type", "custom"),
                response -> call(response).addProperty("id", ""),
                response -> call(response).addProperty("id", "bad id"),
                response -> call(response).addProperty("id", "bad\nid"),
                response -> call(response).addProperty("id", "i".repeat(129)),
                response -> call(response).addProperty("id", 123),
                response -> call(response).remove("id")
        ));
    }

    private static void assertUnverifiedMutations(List<Consumer<JsonObject>> mutations) throws Exception {
        for (int variant = 0; variant < mutations.size(); variant++) {
            Consumer<JsonObject> mutation = mutations.get(variant);
            try (Server server = new Server(request -> {
                JsonObject response = validReply(request); mutation.accept(response); return new Reply(response.toString());
            })) {
                RunControl control = new RunControl();
                VisionModelProbe.Result result = new VisionModelProbe().test(server.settings(), control);
                assertTrue("variant " + variant + " has a valid JSON response", result.connected);
                assertFalse("variant " + variant + " must not verify the image", result.syntheticImageVerified);
                assertUnverified(result); assertOnlyModelRound(control);
                assertEquals("no correcting retry is sent by the probe", 1, server.requests.size());
                server.assertHealthy();
            }
        }
    }

    @Test public void textOnlyOrMissingToolRepliesRemainConnectedButNeverVerify() throws Exception {
        for (String reply : List.of("{}", "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"I can see the image " + SERVER_DETAIL + KEY + "\"}}]}",
                "{\"error\":{\"message\":\"" + SERVER_DETAIL + KEY + "\"}}")) {
            try (Server server = new Server(request -> { decodedColors(request); return new Reply(reply); })) {
                RunControl control = new RunControl();
                assertUnverified(new VisionModelProbe().test(server.settings(), control));
                assertOnlyModelRound(control); assertEquals(1, server.requests.size()); server.assertHealthy();
            }
        }
    }

    @Test public void httpErrorsAreRedactedSingleAttemptsIndependentOfRemoteDiagnosticText() throws Exception {
        for (int status : new int[] {401, 429, 500}) {
            List<String> messages = new ArrayList<>();
            for (String detail : List.of(SERVER_DETAIL + KEY, "different-private-diagnostic")) {
                try (Server server = new Server(request -> new Reply(status, "{\"error\":\"" + detail + "\"}", null))) {
                    RunControl control = new RunControl();
                    VisionModelProbe.Result result = new VisionModelProbe().test(server.settings(), control);
                    assertFalse(result.connected); assertFalse(result.syntheticImageVerified); assertRedacted(result);
                    assertFalse(result.message.contains(detail)); messages.add(result.message);
                    assertOnlyModelRound(control); assertEquals(1, server.requests.size()); server.assertHealthy();
                }
            }
            assertEquals("server diagnostics cannot influence user-facing text", messages.get(0), messages.get(1));
        }
    }

    @Test public void malformedResponseJsonCannotBeReportedAsAConnectionSuccess() throws Exception {
        for (String reply : List.of(SERVER_DETAIL + KEY, "[]", "{", "{}{}")) {
            try (Server server = new Server(request -> new Reply(reply))) {
                RunControl control = new RunControl();
                VisionModelProbe.Result result = new VisionModelProbe().test(server.settings(), control);
                assertFalse(result.connected); assertFalse(result.syntheticImageVerified); assertRedacted(result);
                assertOnlyModelRound(control); assertEquals(1, server.requests.size()); server.assertHealthy();
            }
        }
    }

    @Test public void redirectDoesNotSendImageOrCredentialsToAnotherEndpoint() throws Exception {
        try (Server destination = new Server(request -> new Reply(validReply(request).toString()));
             Server origin = new Server(request -> new Reply(302, SERVER_DETAIL + KEY,
                     destination.endpoint() + "/chat/completions"))) {
            RunControl control = new RunControl();
            VisionModelProbe.Result result = new VisionModelProbe().test(origin.settings(), control);
            assertFalse(result.connected); assertFalse(result.syntheticImageVerified); assertRedacted(result);
            assertOnlyModelRound(control); assertEquals(1, origin.requests.size()); assertTrue(destination.requests.isEmpty());
            origin.assertHealthy(); destination.assertHealthy();
        }
    }

    @Test(timeout = 5000) public void connectionFailureIsRedactedAndDoesNotDispatchTools() throws Exception {
        String endpoint;
        try (Server server = new Server(request -> new Reply("{}"))) { endpoint = server.endpoint(); }
        RunControl control = new RunControl();
        VisionModelProbe.Result result = new VisionModelProbe(new HttpModelClient(150)).test(
                new ModelSettings(endpoint, KEY, "synthetic-vision-model"), control);
        assertFalse(result.connected); assertFalse(result.syntheticImageVerified); assertRedacted(result);
        assertOnlyModelRound(control);
    }

    @Test(timeout = 8000) public void timeoutFinishesWithoutAResponseOrAutomaticRetry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Server server = new Server(request -> {
            entered.countDown(); release.await(5, TimeUnit.SECONDS); return new Reply(validReply(request).toString());
        })) {
            RunControl control = new RunControl();
            Future<VisionModelProbe.Result> future = worker.submit(() ->
                    new VisionModelProbe(new HttpModelClient(400)).test(server.settings(), control));
            assertTrue("timeout fixture must receive the actual image request", entered.await(3, TimeUnit.SECONDS));
            VisionModelProbe.Result result = future.get(3, TimeUnit.SECONDS);
            assertEquals("fixture response is still blocked", 1, release.getCount());
            assertFalse(result.connected); assertFalse(result.syntheticImageVerified); assertRedacted(result);
            assertOnlyModelRound(control); assertEquals(1, server.requests.size()); server.assertHealthy();
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void invalidConfigurationNeverSendsTheSyntheticImage() throws Exception {
        try (Server server = new Server(request -> new Reply(validReply(request).toString()))) {
            RunControl control = new RunControl();
            VisionModelProbe.Result result = new VisionModelProbe().test(
                    new ModelSettings(server.endpoint() + "?secret=" + KEY, KEY, "synthetic-vision-model"), control);
            assertFalse(result.connected); assertFalse(result.syntheticImageVerified); assertRedacted(result);
            assertTrue(server.requests.isEmpty()); assertEquals(0, control.budgetSnapshot().modelRounds);
            assertEquals(0, control.budgetSnapshot().toolCalls); assertEquals(0, control.getDispatchAttempts());
            server.assertHealthy();
        }
    }

    @Test public void preCancelledPausedAndExhaustedControlsThrowWithoutSendingARequest() throws Exception {
        try (Server server = new Server(request -> new Reply(validReply(request).toString()))) {
            for (String code : List.of("TASK_STOPPED", "TASK_PAUSED", "BUDGET_EXCEEDED")) {
                RunControl control = new RunControl(PRIVATE_PROFILE, new TaskBudget(1, 5, 60000));
                if (code.equals("TASK_STOPPED")) control.cancel();
                else if (code.equals("TASK_PAUSED")) control.pause();
                else control.consumeModelRound();
                TaskControlException error = assertThrows(TaskControlException.class,
                        () -> new VisionModelProbe().test(server.settings(), control));
                assertEquals(code, error.getCode()); assertTrue(server.requests.isEmpty());
                assertEquals(code.equals("BUDGET_EXCEEDED") ? 1 : 0, control.budgetSnapshot().modelRounds);
                assertEquals(0, control.budgetSnapshot().toolCalls); assertEquals(0, control.getDispatchAttempts());
            }
            server.assertHealthy();
        }
    }

    @Test(timeout = 8000) public void cancellingAnInFlightProbeRemainsAnInterruption() throws Exception {
        assertInterruptsInFlight(false, false);
    }

    @Test(timeout = 8000) public void pausingAnInFlightProbeRemainsAnInterruption() throws Exception {
        assertInterruptsInFlight(true, false);
    }

    @Test(timeout = 8000) public void resumingCannotMakeThePreviousImageResponseValidAgain() throws Exception {
        assertInterruptsInFlight(true, true);
    }

    private static void assertInterruptsInFlight(boolean pause, boolean resume) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RunControl control = new RunControl();
        try (Server server = new Server(request -> {
            JsonObject valid = validReply(request);
            entered.countDown(); release.await(5, TimeUnit.SECONDS); return new Reply(valid.toString());
        })) {
            Future<VisionModelProbe.Result> future = worker.submit(() -> new VisionModelProbe().test(server.settings(), control));
            assertTrue("the probe must send its image before interruption", entered.await(3, TimeUnit.SECONDS));
            if (pause) control.pause(); else control.cancel();
            if (resume) { control.resume(); release.countDown(); }
            ExecutionException wrapped = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertTrue("interruption must not be converted to a normal failed result", wrapped.getCause() instanceof TaskControlException);
            assertEquals(pause ? "TASK_PAUSED" : "TASK_STOPPED", ((TaskControlException) wrapped.getCause()).getCode());
            if (!resume) assertEquals("stopping does not wait for the remote response", 1, release.getCount());
            assertOnlyModelRound(control); assertEquals(1, server.requests.size()); server.assertHealthy();
        } finally { release.countDown(); control.cancel(); worker.shutdownNow(); }
    }
}
