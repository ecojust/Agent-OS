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
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Synthetic native captures and actual HTTP verify a guard belongs to one image request only. */
public final class GuardedImageRunnerTest {
    private static final String REVISION = "guarded-image-r1";
    private static final String REPORT = "synthetic-image-report-not-a-verified-result";
    private static final String PRIVATE_GUARD_MARKER = "private-image-condition-do-not-serialize";
    private static final String FINISH = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + REPORT + "\"}}]}";

    @Test public void imageAndItsOriginalGuardAreUsedOnceWithoutPersistingInHistory() throws Exception {
        long baseline = ImageWorkset.process().reservedBytes();
        RunControl control = new RunControl("guarded-image-profile");
        // Use a noninitial epoch so passing a default epoch cannot accidentally satisfy the test.
        control.pause(); control.resume();
        NativeDevice device = new NativeDevice(control); Events events = new Events();
        AtomicInteger guardChecksAtDiscard = new AtomicInteger();
        device.afterRead = () -> {
            if (device.captures.get() > 0) {
                guardChecksAtDiscard.set(device.guard.calls.size());
                device.guard.permitted.set(false);
            }
        };
        try (Server server = new Server((index, request) -> switch (index) {
            case 0 -> plans(call("image-once", "take_screenshot"));
            case 1 -> plans(call("read-after-image", "wait"));
            default -> FINISH;
        })) {
            server.runner(device, events, control).run("检查合成图像", control);
            server.assertHealthy(); assertReported(events);
            assertEquals(3, server.requests.size()); assertEquals(1, device.captures.get());
            assertEquals(1, device.clones.get());
            assertEquals(List.of("observe", "take_screenshot", "wait"), device.names());
            assertImagesOnlyAt(server, device, 1);
            assertTrue("The image guard must actually run", guardChecksAtDiscard.get() > 0);
            assertEquals("Later image-free requests must not retain the old condition",
                    guardChecksAtDiscard.get(), device.guard.calls.size());
            assertFalse(device.guard.permitted.get());
            assertBoundToOriginalRequest(device, "image-once");
            assertNotSerialized(server, device, events);
            assertTrue(device.image.get().isClosed()); assertEquals(0, control.getDispatchAttempts());
        } finally { device.close(); control.cancel(); awaitBudget(baseline); }
    }

    @Test public void falseOrThrowingImageConditionAfterCapturePreventsTheSecondHttpRequest() throws Exception {
        for (boolean throwing : List.of(false, true)) {
            long baseline = ImageWorkset.process().reservedBytes();
            RunControl control = new RunControl("guarded-image-profile");
            NativeDevice device = new NativeDevice(control); Events events = new Events();
            device.afterCapture = () -> {
                device.guard.throwing.set(throwing);
                device.guard.permitted.set(false);
            };
            try (Server server = new Server((index, request) -> plans(call("revoked-image", "take_screenshot")))) {
                server.runner(device, events, control).run("检查合成图像", control);
                server.assertHealthy(); assertStopped(events);
                assertEquals(1, server.requests.size()); assertEquals(1, device.captures.get());
                assertImagesOnlyAt(server, device, -1);
                assertEquals(List.of("observe", "take_screenshot"), device.names());
                assertTrue("The invalid local condition must be evaluated", device.guard.calls.size() > 0);
                assertBoundToOriginalRequest(device, "revoked-image");
                assertNotSerialized(server, device, events);
                assertTrue(device.image.get().isClosed()); assertEquals(0, control.getDispatchAttempts());
            } finally { device.close(); control.cancel(); awaitBudget(baseline); }
        }
    }

    @Test public void conditionRevokedAfterTheServerReceivesTheImageRejectsItsHeldReplyWithoutRetry() throws Exception {
        long baseline = ImageWorkset.process().reservedBytes();
        RunControl control = new RunControl("guarded-image-profile");
        NativeDevice device = new NativeDevice(control); Events events = new Events();
        CountDownLatch receivedImage = new CountDownLatch(1), releaseReply = new CountDownLatch(1);
        ExecutorService runnerThread = Executors.newSingleThreadExecutor();
        try (Server server = new Server((index, request) -> {
            if (index == 0) return plans(call("held-image", "take_screenshot"));
            assertEquals("No retry or extra request is permitted", 1, index);
            assertOneImage(request, device.bytes);
            receivedImage.countDown();
            assertTrue("The test must release the real server reply", releaseReply.await(5, TimeUnit.SECONDS));
            return FINISH;
        })) {
            Future<?> execution = runnerThread.submit(() -> server.runner(device, events, control).run("检查合成图像", control));
            try {
                assertTrue("The real HTTP request must reach the server", receivedImage.await(5, TimeUnit.SECONDS));
                assertEquals(2, server.requests.size()); assertFalse(execution.isDone());
                assertTrue(device.guard.calls.size() > 0);
                // A body already sent cannot be retracted. The delayed response must not be accepted.
                server.expectedClientDisconnect.set(true);
                device.guard.permitted.set(false);
                releaseReply.countDown();
                execution.get(10, TimeUnit.SECONDS);
                server.assertHealthy(); assertStopped(events);
                assertEquals(2, server.requests.size()); assertImagesOnlyAt(server, device, 1);
                assertEquals(List.of("observe", "take_screenshot"), device.names());
                assertEquals(1, device.captures.get()); assertEquals(0, control.getDispatchAttempts());
                assertBoundToOriginalRequest(device, "held-image");
                assertNotSerialized(server, device, events); assertTrue(device.image.get().isClosed());
            } finally { releaseReply.countDown(); control.cancel(); execution.cancel(true); }
        } finally {
            releaseReply.countDown(); control.cancel(); runnerThread.shutdownNow();
            assertTrue(runnerThread.awaitTermination(5, TimeUnit.SECONDS));
            device.close(); awaitBudget(baseline);
        }
    }

    @Test public void sameBatchObserveOrWaitDiscardsBothTheImageAndItsInvalidCondition() throws Exception {
        for (String readTool : List.of("observe", "wait")) {
            long baseline = ImageWorkset.process().reservedBytes();
            RunControl control = new RunControl("guarded-image-profile");
            NativeDevice device = new NativeDevice(control); Events events = new Events();
            device.afterCapture = () -> device.guard.permitted.set(false);
            AtomicBoolean releasedBeforeRead = new AtomicBoolean();
            device.afterRead = () -> {
                if (device.captures.get() > 0) releasedBeforeRead.set(device.image.get().isClosed());
            };
            try (Server server = new Server((index, request) -> index == 0
                    ? plans(call("discarded-image", "take_screenshot"), call("fresh-read", readTool)) : FINISH)) {
                server.runner(device, events, control).run("只使用最新结构化观察", control);
                server.assertHealthy(); assertReported(events);
                assertEquals(2, server.requests.size()); assertImagesOnlyAt(server, device, -1);
                assertEquals(List.of("observe", "take_screenshot", readTool), device.names());
                assertEquals(1, device.captures.get()); assertTrue(releasedBeforeRead.get());
                assertTrue("An image never submitted for disclosure must not retain its guard", device.guard.calls.isEmpty());
                assertFalse(device.guard.permitted.get()); assertTrue(device.image.get().isClosed());
                assertBoundToOriginalRequest(device, "discarded-image");
                assertNotSerialized(server, device, events); assertEquals(0, control.getDispatchAttempts());
            } finally { device.close(); control.cancel(); awaitBudget(baseline); }
        }
    }

    @Test public void duplicateCallIdReplaysOnlyMetadataWithoutTheConsumedImageOrGuard() throws Exception {
        long baseline = ImageWorkset.process().reservedBytes();
        RunControl control = new RunControl("guarded-image-profile");
        NativeDevice device = new NativeDevice(control); Events events = new Events();
        AtomicInteger screenshotSteps = new AtomicInteger(), guardChecksAtReplay = new AtomicInteger();
        events.step = value -> {
            if (ToolCatalog.label("take_screenshot").equals(value) && screenshotSteps.incrementAndGet() == 2) {
                guardChecksAtReplay.set(device.guard.calls.size()); device.guard.permitted.set(false);
            }
        };
        try (Server server = new Server((index, request) -> index < 2
                ? plans(call("one-image-call", "take_screenshot")) : FINISH)) {
            server.runner(device, events, control).run("检查合成图像", control);
            server.assertHealthy(); assertReported(events);
            assertEquals(3, server.requests.size()); assertEquals(2, screenshotSteps.get());
            assertEquals(1, device.captures.get()); assertEquals(1, device.clones.get());
            assertEquals(List.of("observe", "take_screenshot"), device.names());
            assertImagesOnlyAt(server, device, 1);
            assertTrue(server.requests.get(2).toString().contains("not_replayed"));
            assertTrue(guardChecksAtReplay.get() > 0);
            assertEquals(guardChecksAtReplay.get(), device.guard.calls.size());
            assertFalse(device.guard.permitted.get()); assertTrue(device.image.get().isClosed());
            assertBoundToOriginalRequest(device, "one-image-call");
            assertNotSerialized(server, device, events); assertEquals(0, control.getDispatchAttempts());
        } finally { device.close(); control.cancel(); awaitBudget(baseline); }
    }

    private static final class BoundGuard implements DisclosureGuard {
        final RunControl owner;
        final long epoch;
        final AtomicBoolean permitted = new AtomicBoolean(true), throwing = new AtomicBoolean();
        final List<GuardCall> calls = new CopyOnWriteArrayList<>();
        BoundGuard(RunControl owner) { this.owner = owner; epoch = owner.getExecutionEpoch(); }
        @Override public boolean isCurrent(RunControl original, long originalEpoch) {
            calls.add(new GuardCall(original, originalEpoch));
            if (throwing.get()) throw new IllegalStateException(PRIVATE_GUARD_MARKER);
            return original == owner && originalEpoch == epoch && permitted.get();
        }
        @Override public String toString() { return PRIVATE_GUARD_MARKER; }
    }
    private record GuardCall(RunControl control, long epoch) { }

    private static final class NativeDevice implements ToolHandler, AutoCloseable {
        final RunControl owner;
        final long epoch;
        final BoundGuard guard;
        final byte[] bytes = PngImageTest.png();
        final AtomicInteger captures = new AtomicInteger(), clones = new AtomicInteger();
        final List<ToolRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<PngImage> image = new AtomicReference<>();
        Runnable afterCapture = () -> { }, afterRead = () -> { };
        NativeDevice(RunControl owner) throws IOException {
            this.owner = owner; epoch = owner.getExecutionEpoch(); guard = new BoundGuard(owner);
        }
        @Override public JsonObject execute(String name, JsonObject arguments, RunControl control) {
            throw new AssertionError("The guarded native result must receive its complete original request");
        }
        @Override public Set<String> availableTools() {
            Set<String> result = new HashSet<>(ToolHandler.super.availableTools());
            result.add("take_screenshot"); return result;
        }
        @Override public LocalToolResult execute(ToolRequest request, RunControl active) throws Exception {
            assertSame(owner, active); assertEquals(epoch, request.executionEpoch);
            assertEquals(owner.getTaskId(), request.taskId); assertEquals(owner.getProfileId(), request.profileId);
            active.checkExecutionEpoch(request.executionEpoch); requests.add(request);
            if ("take_screenshot".equals(request.tool)) {
                captures.incrementAndGet();
                PngImage png = PngImage.fromBytes(bytes, ImageWorkset.process(), source -> { clones.incrementAndGet(); return source.clone(); });
                image.set(png);
                LocalToolResult result = LocalToolResult.withPng(metadata(), png, guard);
                boolean transferred = false;
                try { afterCapture.run(); transferred = true; return result; }
                finally { if (!transferred) result.close(); }
            }
            assertTrue("Unexpected synthetic device operation: " + request.tool,
                    "observe".equals(request.tool) || "wait".equals(request.tool));
            JsonObject result = metadata(); JsonObject data = result.getAsJsonObject("data");
            data.addProperty("packageName", "synthetic.guarded.fixture"); data.add("nodes", new JsonArray());
            afterRead.run(); return LocalToolResult.metadata(result);
        }
        List<String> names() {
            List<String> result = new ArrayList<>();
            for (ToolRequest request : requests) result.add(request.tool);
            return result;
        }
        @Override public void close() { if (image.get() != null) image.get().close(); }
    }

    private static JsonObject metadata() {
        JsonObject result = new JsonObject(); result.addProperty("ok", true);
        JsonObject data = new JsonObject(); data.addProperty("revision", REVISION);
        data.addProperty("width", 2); data.addProperty("height", 1);
        data.addProperty("captureId", "synthetic-guarded-capture"); result.add("data", data); return result;
    }
    private static String call(String id, String name) {
        JsonObject args = new JsonObject();
        if (ToolCatalog.requiresRevision(name)) args.addProperty("expectedRevision", REVISION);
        if ("wait".equals(name)) args.addProperty("milliseconds", 0);
        JsonObject function = new JsonObject(); function.addProperty("name", name); function.addProperty("arguments", args.toString());
        JsonObject call = new JsonObject(); call.addProperty("id", id); call.addProperty("type", "function"); call.add("function", function);
        return call.toString();
    }
    private static String plans(String... calls) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + String.join(",", calls) + "]}}]}";
    }
    private static void assertReported(Events events) {
        assertNotNull(events.result); assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
        assertFalse(events.result.verified); assertTrue(events.result.summary.contains(REPORT));
        assertEquals(1, events.completions.get());
    }
    private static void assertStopped(Events events) {
        assertNotNull(events.result); assertEquals(TaskResult.Kind.STOPPED, events.result.kind);
        assertEquals("TASK_STOPPED", events.result.code); assertFalse(events.result.verified);
        assertFalse(events.result.summary.contains(REPORT)); assertEquals(1, events.completions.get());
    }
    private static void assertBoundToOriginalRequest(NativeDevice device, String callId) {
        ToolRequest captured = device.requests.stream().filter(value -> "take_screenshot".equals(value.tool)).findFirst().orElseThrow();
        assertEquals(callId, captured.callId); assertEquals(device.owner.getTaskId(), captured.taskId);
        assertEquals(device.owner.getProfileId(), captured.profileId); assertEquals(device.epoch, captured.executionEpoch);
        assertEquals(REVISION, captured.getArguments().get("expectedRevision").getAsString());
        for (GuardCall check : device.guard.calls) {
            assertSame(device.owner, check.control()); assertEquals(captured.executionEpoch, check.epoch());
        }
    }
    private static void assertImagesOnlyAt(Server server, NativeDevice device, int index) {
        for (int i = 0; i < server.requests.size(); i++) {
            if (i == index) assertOneImage(server.requests.get(i), device.bytes);
            else assertTrue("Historical or unrelated requests must be image-free", imageUrls(server.requests.get(i)).isEmpty());
        }
    }
    private static void assertOneImage(JsonObject request, byte[] expected) {
        List<String> urls = imageUrls(request); assertEquals(1, urls.size());
        assertTrue(urls.get(0).startsWith("data:image/png;base64,"));
        assertArrayEquals(expected, Base64.getDecoder().decode(urls.get(0).substring("data:image/png;base64,".length())));
    }
    private static List<String> imageUrls(JsonObject request) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : request.getAsJsonArray("messages")) {
            JsonElement content = value.getAsJsonObject().get("content");
            if (content == null || !content.isJsonArray()) continue;
            for (JsonElement item : content.getAsJsonArray()) {
                JsonObject part = item.getAsJsonObject();
                if ("image_url".equals(part.get("type").getAsString())) result.add(part.getAsJsonObject("image_url").get("url").getAsString());
            }
        }
        return result;
    }
    private static void assertNotSerialized(Server server, NativeDevice device, Events events) {
        String encoded = Base64.getEncoder().encodeToString(device.bytes);
        for (JsonObject request : server.requests) {
            assertFalse(request.toString().contains(PRIVATE_GUARD_MARKER));
            assertFalse(request.toString().contains("imageGuard")); assertFalse(request.toString().contains("disclosureGuard"));
            for (JsonElement value : request.getAsJsonArray("messages")) {
                JsonObject message = value.getAsJsonObject();
                if ("tool".equals(message.get("role").getAsString())) {
                    assertFalse(message.get("content").getAsString().contains("imageDataUrl"));
                    assertFalse(message.get("content").getAsString().contains(encoded));
                }
            }
        }
        String logs = String.join("\n", events.log);
        assertFalse(logs.contains(encoded)); assertFalse(logs.contains(PRIVATE_GUARD_MARKER));
    }
    private static void awaitBudget(long baseline) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ImageWorkset.process().reservedBytes() != baseline && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("Known image and wire owners must be released after their workers exit", baseline, ImageWorkset.process().reservedBytes());
    }
    private static final class Events implements AgentRunner.Listener {
        final List<String> log = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();
        volatile TaskResult result;
        Consumer<String> step = value -> { };
        @Override public void onStatus(String value) { log.add(value); }
        @Override public void onStep(String value) { log.add(value); step.accept(value); }
        @Override public void onFinished(String value, boolean verified) { log.add(value); }
        @Override public void onResult(TaskResult value) {
            result = value; completions.incrementAndGet(); AgentRunner.Listener.super.onResult(value);
        }
    }
    @FunctionalInterface private interface Reply { String answer(int index, JsonObject request) throws Exception; }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final List<JsonObject> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean expectedClientDisconnect = new AtomicBoolean();
        Server(Reply responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(worker);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    int index = requests.size(); requests.add(request);
                    byte[] response = responder.answer(index, request).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } catch (Throwable error) {
                    // Revocation may disconnect the already-received request before its held reply is written.
                    if (!(error instanceof IOException && expectedClientDisconnect.get())) failure.compareAndSet(null, error);
                } finally { exchange.close(); }
            });
            server.start();
        }
        AgentRunner runner(ToolHandler tools, Events events, RunControl control) {
            ModelSettings settings = new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic-key", "guarded-image-model");
            long epoch = control.getExecutionEpoch();
            return new AgentRunner(settings, tools, events, new HttpModelClient(8000), control,
                    (original, originalEpoch) -> original == control && originalEpoch == epoch);
        }
        void assertHealthy() { assertNull("Synthetic HTTP fixture failed: " + failure.get(), failure.get()); }
        @Override public void close() throws InterruptedException {
            server.stop(0); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); assertHealthy();
        }
    }
}
