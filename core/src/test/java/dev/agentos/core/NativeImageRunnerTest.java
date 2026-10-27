package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;

import static org.junit.Assert.*;

/** Actual HTTP verifies that request-aware native results still use the one-use image transport. */
public final class NativeImageRunnerTest {
    private static final String FINISH = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"合成测试结束，请核对。\"}}]}";

    @Test public void nativePngIsSentOnceAndDuplicateCallHasOnlyMetadataInLaterHistory() throws Exception {
        long baseline = ImageWorkset.process().reservedBytes(); Events events = new Events();
        RunControl control = new RunControl("native-profile"); NativeDevice device = new NativeDevice(control);
        try (Server server = new Server(index -> switch (index) {
            case 0, 1 -> plans(call("native-image", "take_screenshot"));
            case 2 -> plans(call("wait-next", "wait"));
            default -> FINISH;
        })) {
            server.runner(device, events).run("检查合成界面", control);
            server.assertHealthy();
            assertNotNull(events.result); assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertFalse(events.result.verified); assertEquals(4, server.requests.size());
            assertEquals(1, device.captures.get()); assertEquals(1, device.clones.get());
            assertEquals(List.of("observe", "take_screenshot", "wait"), device.names());
            ToolRequest captured = device.requests.stream().filter(request -> "take_screenshot".equals(request.tool)).findFirst().orElseThrow();
            assertEquals("native-image", captured.callId); assertEquals(control.getTaskId(), captured.taskId);
            assertEquals(control.getProfileId(), captured.profileId);
            assertEquals(control.getExecutionEpoch(), captured.executionEpoch);
            assertEquals(LocalToolResultTest.REVISION, captured.getArguments().get("expectedRevision").getAsString());
            for (int i = 0; i < server.requests.size(); i++) {
                List<String> urls = imageUrls(server.requests.get(i));
                assertEquals(i == 1 ? 1 : 0, urls.size());
                if (i == 1) {
                    assertTrue(urls.get(0).startsWith("data:image/png;base64,"));
                    byte[] decoded = Base64.getDecoder().decode(urls.get(0).substring("data:image/png;base64,".length()));
                    assertArrayEquals(device.bytes, decoded);
                    java.awt.image.BufferedImage pixels = ImageIO.read(new ByteArrayInputStream(decoded));
                    assertNotNull(pixels); assertEquals(2, pixels.getWidth()); assertEquals(1, pixels.getHeight());
                    assertEquals(0xff0a141e, pixels.getRGB(0, 0)); assertEquals(0xff28323c, pixels.getRGB(1, 0));
                }
                for (JsonElement entry : server.requests.get(i).getAsJsonArray("messages")) {
                    JsonObject message = entry.getAsJsonObject();
                    if ("tool".equals(message.get("role").getAsString())) {
                        assertFalse(message.get("content").getAsString().contains("imageDataUrl"));
                        assertFalse(message.get("content").getAsString().contains(Base64.getEncoder().encodeToString(device.bytes)));
                    }
                }
            }
            assertTrue(server.requests.get(2).toString().contains("not_replayed"));
            assertFalse(String.join("\n", events.log).contains(Base64.getEncoder().encodeToString(device.bytes)));
            assertTrue(device.image.get().isClosed());
        } finally { device.close(); control.cancel(); awaitBudget(baseline); }
    }

    @Test public void legacyFourArgumentAdaptersStillReceiveTheOriginalEpochThroughTheRunner() throws Exception {
        long baseline = ImageWorkset.process().reservedBytes(); Events events = new Events();
        RunControl control = new RunControl(); long epoch = control.getExecutionEpoch(); byte[] png = PngImageTest.png();
        List<String> entries = new CopyOnWriteArrayList<>();
        ToolHandler legacy = new ToolHandler() {
            @Override public JsonObject execute(String name, JsonObject arguments, RunControl active) {
                throw new AssertionError("Existing four-argument adapters must not fall back to fresh calls");
            }
            @Override public JsonObject execute(String name, JsonObject arguments, RunControl active, long requestEpoch) throws Exception {
                assertSame(control, active); assertEquals(epoch, requestEpoch); active.checkExecutionEpoch(requestEpoch);
                entries.add(name); JsonObject result = LocalToolResultTest.metadata();
                if ("take_screenshot".equals(name)) result.addProperty("imageDataUrl",
                        "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
                else {
                    assertEquals("observe", name); result.getAsJsonObject("data").addProperty("packageName", "synthetic.fixture");
                    result.getAsJsonObject("data").add("nodes", new JsonArray());
                }
                return result;
            }
            @Override public java.util.Set<String> availableTools() {
                java.util.Set<String> names = new java.util.HashSet<>(ToolHandler.super.availableTools());
                names.add("take_screenshot"); return names;
            }
        };
        try (Server server = new Server(index -> index == 0 ? plans(call("legacy-image", "take_screenshot")) : FINISH)) {
            server.runner(legacy, events).run("检查合成界面", control);
            server.assertHealthy(); assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertEquals(List.of("observe", "take_screenshot"), entries); assertEquals(2, server.requests.size());
            assertTrue(imageUrls(server.requests.get(0)).isEmpty()); assertEquals(1, imageUrls(server.requests.get(1)).size());
            assertArrayEquals(png, Base64.getDecoder().decode(imageUrls(server.requests.get(1)).get(0)
                    .substring("data:image/png;base64,".length())));
        } finally { control.cancel(); awaitBudget(baseline); }
    }

    @Test public void nativeRequestOverloadCannotBypassSameBatchOrRevisionGuards() throws Exception {
        long baseline = ImageWorkset.process().reservedBytes(); Events events = new Events();
        RunControl control = new RunControl(); NativeDevice device = new NativeDevice(control);
        try (Server server = new Server(index -> index == 0
                ? plans(call("wrong-revision", "take_screenshot", "old"), call("current", "take_screenshot"),
                        call("second-image", "take_screenshot"), call("old-write", "click")) : FINISH)) {
            server.runner(device, events).run("检查当前合成界面", control);
            server.assertHealthy(); assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertEquals(List.of("observe", "take_screenshot"), device.names()); assertEquals(1, device.captures.get());
            assertEquals("current", device.requests.get(1).callId); assertEquals(0, control.getDispatchAttempts());
            assertEquals(2, server.requests.size()); assertEquals(1, imageUrls(server.requests.get(1)).size());
            int stale = 0;
            for (JsonElement value : server.requests.get(1).getAsJsonArray("messages")) {
                JsonObject message = value.getAsJsonObject();
                if ("tool".equals(message.get("role").getAsString())) {
                    JsonObject result = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
                    if (result.has("code") && "STALE_OBSERVATION".equals(result.get("code").getAsString())) stale++;
                }
            }
            assertEquals(3, stale); assertTrue(device.image.get().isClosed());
        } finally { device.close(); control.cancel(); awaitBudget(baseline); }
    }

    @Test public void aNewObservationOrWaitDropsTheNativeImageBeforeTheNextRequest() throws Exception {
        for (String tool : List.of("observe", "wait")) {
            long baseline = ImageWorkset.process().reservedBytes(); Events events = new Events();
            RunControl control = new RunControl(); NativeDevice device = new NativeDevice(control);
            try (Server server = new Server(index -> index == 0
                    ? plans(call("native-image", "take_screenshot"), call("discard-image", tool)) : FINISH)) {
                server.runner(device, events).run("重新观察合成界面", control);
                server.assertHealthy(); assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
                assertEquals(List.of("observe", "take_screenshot", tool), device.names());
                assertEquals(2, server.requests.size());
                for (JsonObject request : server.requests) assertTrue(imageUrls(request).isEmpty());
                assertTrue(device.image.get().isClosed());
            } finally { device.close(); control.cancel(); awaitBudget(baseline); }
        }
    }

    @Test public void revokedDisclosureAfterNativeCaptureClosesTheOwnerWithoutAnotherHttpRequest() throws Exception {
        long baseline = ImageWorkset.process().reservedBytes(); Events events = new Events();
        RunControl control = new RunControl(); NativeDevice device = new NativeDevice(control);
        AtomicBoolean permitted = new AtomicBoolean(true); device.afterCapture = () -> permitted.set(false);
        try (Server server = new Server(index -> plans(call("native-image", "take_screenshot")))) {
            new AgentRunner(server.settings(), device, events, control,
                    (original, epoch) -> original == control && permitted.get()).run("检查合成界面", control);
            server.assertHealthy(); assertNotNull(events.result);
            assertEquals(TaskResult.Kind.STOPPED, events.result.kind); assertEquals(1, device.captures.get());
            assertEquals(1, server.requests.size()); assertTrue(imageUrls(server.requests.get(0)).isEmpty());
            assertTrue(device.image.get().isClosed());
        } finally { device.close(); control.cancel(); awaitBudget(baseline); }
    }

    private static final class NativeDevice extends LocalToolResultTest.NativeHandler implements AutoCloseable {
        final RunControl expectedControl;
        final byte[] bytes = PngImageTest.png();
        final AtomicInteger captures = new AtomicInteger(), clones = new AtomicInteger();
        final List<ToolRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<PngImage> image = new AtomicReference<>();
        Runnable afterCapture = () -> { };
        NativeDevice(RunControl control) throws IOException { expectedControl = control; }
        @Override public LocalToolResult execute(ToolRequest request, RunControl control) throws Exception {
            assertSame(expectedControl, control); assertEquals(control.getTaskId(), request.taskId);
            assertEquals(control.getProfileId(), request.profileId); control.checkExecutionEpoch(request.executionEpoch);
            requests.add(request);
            if ("take_screenshot".equals(request.tool)) {
                captures.incrementAndGet();
                PngImage png = PngImage.fromBytes(bytes, ImageWorkset.process(), source -> { clones.incrementAndGet(); return source.clone(); });
                image.set(png); LocalToolResult result = LocalToolResult.withPng(LocalToolResultTest.metadata(), png);
                boolean returned = false;
                try { afterCapture.run(); returned = true; return result; }
                finally { if (!returned) result.close(); }
            }
            assertTrue("Unexpected native device entry: " + request.tool,
                    "observe".equals(request.tool) || "wait".equals(request.tool));
            JsonObject result = LocalToolResultTest.metadata();
            result.getAsJsonObject("data").addProperty("packageName", "synthetic.fixture");
            result.getAsJsonObject("data").add("nodes", new JsonArray());
            return LocalToolResult.metadata(result);
        }
        List<String> names() { List<String> result = new ArrayList<>(); for (ToolRequest request : requests) result.add(request.tool); return result; }
        @Override public void close() { if (image.get() != null) image.get().close(); }
    }

    private static String call(String id, String tool) { return call(id, tool, LocalToolResultTest.REVISION); }
    private static String call(String id, String tool, String revision) {
        JsonObject arguments = new JsonObject();
        if (ToolCatalog.requiresRevision(tool)) arguments.addProperty("expectedRevision", revision);
        if ("click".equals(tool)) arguments.addProperty("nodeId", "n1");
        if ("wait".equals(tool)) arguments.addProperty("milliseconds", 0);
        JsonObject function = new JsonObject(); function.addProperty("name", tool); function.addProperty("arguments", arguments.toString());
        JsonObject result = new JsonObject(); result.addProperty("id", id); result.addProperty("type", "function"); result.add("function", function);
        return result.toString();
    }
    private static String plans(String... calls) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + String.join(",", calls) + "]}}]}";
    }
    private static List<String> imageUrls(JsonObject request) {
        List<String> urls = new ArrayList<>();
        for (JsonElement value : request.getAsJsonArray("messages")) {
            JsonElement content = value.getAsJsonObject().get("content");
            if (content == null || !content.isJsonArray()) continue;
            for (JsonElement item : content.getAsJsonArray()) {
                JsonObject part = item.getAsJsonObject();
                if ("image_url".equals(part.get("type").getAsString())) urls.add(part.getAsJsonObject("image_url").get("url").getAsString());
            }
        }
        return urls;
    }
    private static void awaitBudget(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ImageWorkset.process().reservedBytes() != expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("Actual network workers must release their known wire owners", expected, ImageWorkset.process().reservedBytes());
    }
    private static final class Events implements AgentRunner.Listener {
        final List<String> log = new CopyOnWriteArrayList<>();
        TaskResult result;
        @Override public void onStatus(String status) { log.add(status); }
        @Override public void onStep(String step) { log.add(step); }
        @Override public void onFinished(String summary, boolean verified) { log.add(summary); }
        @Override public void onResult(TaskResult value) { result = value; AgentRunner.Listener.super.onResult(value); }
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final List<JsonObject> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Server(IntFunction<String> responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(worker);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    int index = requests.size(); requests.add(request);
                    byte[] reply = responder.apply(index).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply);
                } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                finally { exchange.close(); }
            });
            server.start();
        }
        ModelSettings settings() { return new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic-key", "native-image-model"); }
        AgentRunner runner(ToolHandler handler, Events events) { return new AgentRunner(settings(), handler, events, new HttpModelClient(3000)); }
        void assertHealthy() { assertNull("Synthetic HTTP server failed: " + failure.get(), failure.get()); }
        @Override public void close() throws InterruptedException { server.stop(0); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }
}
