package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

/** A screenshot is transient transport data, not reusable tool history. */
public class ImageAttachmentLifecycleTest {
    private static final String REVISION = "screen-r1";
    private static final byte[] RGB_ROW = {0, 10, 20, 30, 40, 50, 60};

    private static byte[] png(int width, int height, int color, int depth, int interlace,
                              byte[] rows, String ancillary) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(header);
        fields.writeInt(width); fields.writeInt(height); fields.writeByte(depth);
        fields.writeByte(color); fields.writeByte(0); fields.writeByte(0); fields.writeByte(interlace);
        chunk(output, "IHDR", header.toByteArray());
        if (ancillary != null) chunk(output, ancillary, new byte[] {0});
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(encoded)) { deflater.write(rows); }
        chunk(output, "IDAT", encoded.toByteArray()); chunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static byte[] validPng() throws IOException { return png(2, 1, 2, 8, 0, RGB_ROW, null); }

    private static byte[] withMetadata(boolean afterPixels, String[] names, byte[][] values) throws IOException {
        byte[] image = validPng();
        int split = afterPixels ? image.length - 12 : 8 + 12 + 13;
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(image, 0, split);
        for (int i = 0; i < names.length; i++) chunk(output, names[i], values[i]);
        output.write(image, split, image.length - split); return output.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream target, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        DataOutputStream output = new DataOutputStream(target);
        output.writeInt(data.length); output.write(name); output.write(data);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(data); output.writeInt((int) crc.getValue());
    }

    private static String url(byte[] data) { return "data:image/png;base64," + Base64.getEncoder().encodeToString(data); }
    private static JsonObject ok() { JsonObject value = new JsonObject(); value.addProperty("ok", true); return value; }
    private static JsonObject observation() {
        JsonObject value = ok(); JsonObject data = new JsonObject();
        data.addProperty("revision", REVISION); data.addProperty("packageName", "demo.app");
        data.add("nodes", new JsonArray()); value.add("data", data); return value;
    }

    private static JsonObject screenshot(String dataUrl) {
        JsonObject value = observation();
        value.getAsJsonObject("data").addProperty("width", 2);
        value.getAsJsonObject("data").addProperty("height", 1);
        value.addProperty("imageDataUrl", dataUrl); return value;
    }

    private static ToolRequest request(RunControl control, String id, String tool) {
        JsonObject args = new JsonObject();
        if (ToolCatalog.requiresRevision(tool)) args.addProperty("expectedRevision", REVISION);
        if ("click".equals(tool)) args.addProperty("nodeId", "n1");
        if ("wait".equals(tool)) args.addProperty("milliseconds", 0);
        return new ToolRequest(ToolCatalog.PROTOCOL_VERSION, control.getTaskId(), id,
                control.getExecutionEpoch(), control.getProfileId(), tool, args);
    }

    private static ToolHandler source(JsonObject result, AtomicInteger calls) {
        return new ToolHandler() {
            public JsonObject execute(String name, JsonObject args, RunControl control) {
                calls.incrementAndGet(); return result.deepCopy();
            }
            public Set<String> availableTools() {
                Set<String> names = new HashSet<>(ToolHandler.super.availableTools()); names.add("take_screenshot"); return names;
            }
        };
    }

    private static void assertNoBinary(JsonObject metadata, String dataUrl) {
        assertFalse(metadata.toString().contains("imageDataUrl"));
        assertFalse(metadata.toString().contains(dataUrl));
        assertFalse(metadata.toString().contains(dataUrl.substring(dataUrl.indexOf(',') + 1)));
    }

    @Test public void dispatcherTransfersOneBoundImageAndCachesOnlyDefensiveMetadata() throws Exception {
        String dataUrl = url(validPng()); AtomicInteger calls = new AtomicInteger(); RunControl control = new RunControl();
        ToolRequest request = request(control, "one-capture", "take_screenshot");
        try (ToolDispatcher dispatcher = new ToolDispatcher(source(screenshot(dataUrl), calls));
             ToolDispatchResult first = dispatcher.dispatchWithAttachment(request, control)) {
            JsonObject metadata = first.getResult(); assertTrue(metadata.get("ok").getAsBoolean());
            assertNoBinary(metadata, dataUrl);
            assertEquals("single_use", metadata.getAsJsonObject("imageAttachment").get("delivery").getAsString());
            metadata.addProperty("ok", false);
            assertTrue(first.getResult().get("ok").getAsBoolean());
            ImageAttachment image = first.takeImage(); assertNotNull(image); assertNull(first.takeImage());
            assertEquals(2, image.width); assertEquals(1, image.height); assertEquals(validPng().length, image.encodedBytes);
            assertEquals(control.getTaskId(), image.taskId); assertEquals(control.getProfileId(), image.profileId);
            assertEquals(request.executionEpoch, image.executionEpoch); assertEquals("one-capture", image.callId);
            assertEquals(REVISION, image.revision);
            first.close(); assertFalse("ownership transferred to the caller", image.isClosed());
            try (PngImage transferred = image.consumePng(control, request.executionEpoch, REVISION)) {
                assertTrue(image.isConsumed()); assertTrue(image.isClosed());
                assertThrows(IllegalArgumentException.class, () -> image.consumePng(control, request.executionEpoch, REVISION));
                assertFalse("a duplicate consumption cannot close the transferred owner", transferred.isClosed());
                assertArrayEquals(validPng(), PngImageTest.read(transferred));
            }
            try (ToolDispatchResult duplicate = dispatcher.dispatchWithAttachment(request, control)) {
                assertNull(duplicate.takeImage()); assertTrue(duplicate.getResult().get("ok").getAsBoolean());
                assertNoBinary(duplicate.getResult(), dataUrl);
                assertEquals("not_replayed", duplicate.getResult().getAsJsonObject("imageAttachment").get("delivery").getAsString());
            }
            assertEquals(1, calls.get());
        }
    }

    @Test public void metadataOnlyEntryAndUnclaimedOwnerNeverLeaveAReusableAttachment() throws Exception {
        RunControl control = new RunControl(); ToolRequest request = request(control, "discard", "take_screenshot");
        String dataUrl = url(validPng()); JsonObject local = screenshot(dataUrl); AtomicInteger calls = new AtomicInteger();
        ImageAttachment owned = ImageAttachment.fromLocalResult(request, local, local.get("imageDataUrl"));
        try (ToolDispatchResult owner = new ToolDispatchResult(ok(), owned)) { assertFalse(owned.isClosed()); }
        assertTrue(owned.isClosed()); assertFalse(owned.isConsumed());
        assertThrows(IllegalArgumentException.class, () -> owned.consumePng(control, request.executionEpoch, REVISION));
        try (ToolDispatcher dispatcher = new ToolDispatcher(source(local, calls))) {
            assertNoBinary(dispatcher.dispatch(request, control), dataUrl);
            try (ToolDispatchResult replay = dispatcher.dispatchWithAttachment(request, control)) { assertNull(replay.takeImage()); }
            assertEquals(1, calls.get());
        }
    }

    @Test public void wrongTaskRevisionOrExecutionEpochClosesWithoutConsuming() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            RunControl control = new RunControl(); ToolRequest request = request(control, "bound", "take_screenshot");
            JsonObject local = screenshot(url(validPng()));
            ImageAttachment image = ImageAttachment.fromLocalResult(request, local, local.get("imageDataUrl"));
            RunControl receiver = mode == 0 ? new RunControl() : control;
            String revision = mode == 1 ? "a-new-screen" : REVISION;
            if (mode == 2) { control.pause(); control.resume(); }
            if (mode == 2) assertThrows(TaskControlException.class, () -> image.consumePng(receiver, request.executionEpoch, revision));
            else assertThrows(IllegalArgumentException.class, () -> image.consumePng(receiver, request.executionEpoch, revision));
            assertTrue(image.isClosed()); assertFalse(image.isConsumed());
        }
    }

    @Test public void completeRgbRgbaAndWhitelistedMetadataPngsAreAccepted() throws Exception {
        byte[][] variants = {validPng(), png(2, 1, 6, 8, 0, new byte[] {0, 10, 20, 30, -1, 40, 50, 60, -1}, null),
                png(2, 1, 2, 8, 0, RGB_ROW, "sRGB"),
                withMetadata(false, new String[] {"gAMA", "pHYs", "sBIT"}, new byte[][] {
                        {0, 0, -79, -113}, {0, 0, 14, -60, 0, 0, 14, -60, 1}, {8, 8, 8}})};
        for (byte[] bytes : variants) {
            RunControl control = new RunControl(); JsonObject local = screenshot(url(bytes));
            try (ImageAttachment image = ImageAttachment.fromLocalResult(request(control, "valid", "take_screenshot"), local, local.get("imageDataUrl"))) {
                try (PngImage png = image.consumePng(control, control.getExecutionEpoch(), REVISION)) {
                    assertArrayEquals(bytes, PngImageTest.read(png));
                }
            }
        }
    }

    @Test public void base64InputRetainsStrictPaddingAndTrailingCharacterChecks() throws Exception {
        byte[] bytes = png(2, 1, 2, 8, 0, RGB_ROW, "sRGB");
        String padded = url(bytes);
        assertTrue("fixture must exercise padding", padded.endsWith("="));
        RunControl control = new RunControl();
        ToolRequest request = request(control, "base64", "take_screenshot");
        ImageWorkset workset = new ImageWorkset(20_000);
        for (String value : List.of(padded, padded.replace("=", ""))) {
            JsonObject local = screenshot(value);
            try (ImageAttachment image = ImageAttachment.fromLocalResult(request, local, local.get("imageDataUrl"), workset)) {
                assertEquals(bytes.length, workset.reservedBytes());
                try (PngImage png = image.consumePng(control, request.executionEpoch, REVISION)) {
                    image.close();
                    assertArrayEquals(bytes, PngImageTest.read(png));
                    assertEquals(bytes.length, workset.reservedBytes());
                }
            }
            assertEquals(0, workset.reservedBytes());
        }
        for (String value : List.of(padded + "A", padded + "AAAA", padded + "=", padded + "\n", padded + "é",
                padded.replace("=", "A="), padded.substring(0, 30) + " " + padded.substring(30))) {
            JsonObject local = screenshot(value);
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAttachment.fromLocalResult(request, local, local.get("imageDataUrl"), workset));
            assertEquals(0, workset.reservedBytes());
        }
    }

    @Test public void decodingAndPngAdmissionFailuresReleaseOnlyTheirOwnCapacity() throws Exception {
        byte[] bytes = validPng(); RunControl control = new RunControl();
        ToolRequest request = request(control, "budget", "take_screenshot");
        for (long limit : new long[]{bytes.length - 1, bytes.length, bytes.length * 2L + PngValidator.SCRATCH_BYTES - 1}) {
            ImageWorkset workset = new ImageWorkset(limit + 7);
            try (ImageWorkset.Reservation other = workset.reserve(7)) {
                JsonObject local = screenshot(url(bytes));
                assertThrows(IllegalArgumentException.class,
                        () -> ImageAttachment.fromLocalResult(request, local, local.get("imageDataUrl"), workset));
                assertEquals(7, workset.reservedBytes());
            }
            assertEquals(0, workset.reservedBytes());
        }
        ImageWorkset workset = new ImageWorkset(20_000);
        JsonObject wrongMetadata = screenshot(url(bytes));
        wrongMetadata.getAsJsonObject("data").addProperty("width", 9);
        assertThrows(IllegalArgumentException.class,
                () -> ImageAttachment.fromLocalResult(request, wrongMetadata, wrongMetadata.get("imageDataUrl"), workset));
        assertEquals(0, workset.reservedBytes());
        AssertionError failure = new AssertionError("synthetic metadata failure");
        JsonObject errorMetadata = screenshot(url(bytes));
        errorMetadata.getAsJsonObject("data").addProperty("width", OwnedRequestPayloadTest.number(() -> {
            assertEquals("both the decode temporary and PNG owner remain admitted", bytes.length * 2L, workset.reservedBytes());
            throw failure;
        }));
        assertSame(failure, assertThrows(AssertionError.class,
                () -> ImageAttachment.fromLocalResult(request, errorMetadata, errorMetadata.get("imageDataUrl"), workset)));
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void malformedUnsupportedAndOverBudgetPngsNeverProduceAttachmentsOrCachedBytes() throws Exception {
        byte[] badCrc = validPng(); badCrc[16] ^= 1;
        byte[] valid = validPng();
        List<String> invalid = new ArrayList<>(List.of(
                "data:image/jpeg;base64,/9j/2Q==", "data:image/png;base64,%%%", url(Arrays.copyOf(valid, 8)),
                url(badCrc), url(Arrays.copyOf(valid, valid.length - 12)), url(new byte[ImageAttachment.MAX_ENCODED_BYTES + 1]),
                url(png(1281, 1, 2, 8, 0, RGB_ROW, null)), url(png(1024, 1025, 6, 8, 0, RGB_ROW, null)),
                url(png(2, 1, 2, 16, 0, RGB_ROW, null)), url(png(2, 1, 3, 8, 0, RGB_ROW, null)),
                url(png(2, 1, 2, 8, 1, RGB_ROW, null)), url(png(2, 1, 2, 8, 0, new byte[] {0, 1}, null)),
                url(png(2, 1, 2, 8, 0, new byte[] {5, 10, 20, 30, 40, 50, 60}, null)),
                url(png(2, 1, 2, 8, 0, new byte[8192], null))));
        for (String chunk : List.of("acTL", "zTXt", "iCCP", "tEXt")) invalid.add(url(png(2, 1, 2, 8, 0, RGB_ROW, chunk)));
        String[] metadataNames = {"sRGB", "gAMA", "cHRM", "pHYs", "sBIT"};
        byte[][] metadataValues = {{0}, {0, 0, -79, -113}, new byte[32],
                {0, 0, 14, -60, 0, 0, 14, -60, 1}, {8, 8, 8}};
        for (int i = 0; i < metadataNames.length; i++) {
            invalid.add(url(withMetadata(false, new String[] {metadataNames[i], metadataNames[i]},
                    new byte[][] {metadataValues[i], metadataValues[i]})));
            invalid.add(url(withMetadata(true, new String[] {metadataNames[i]}, new byte[][] {metadataValues[i]})));
        }
        String[] invalidNames = {"gAMA", "sBIT", "sBIT", "pHYs", "sRGB"};
        byte[][] invalidValues = {{0, 0, 0, 0}, {0, 8, 8}, {9, 8, 8},
                {0, 0, 14, -60, 0, 0, 14, -60, 2}, {4}};
        for (int i = 0; i < invalidNames.length; i++) {
            invalid.add(url(withMetadata(false, new String[] {invalidNames[i]}, new byte[][] {invalidValues[i]})));
        }
        for (String dataUrl : invalid) {
            RunControl control = new RunControl(); AtomicInteger calls = new AtomicInteger();
            ToolRequest request = request(control, "invalid", "take_screenshot");
            try (ToolDispatcher dispatcher = new ToolDispatcher(source(screenshot(dataUrl), calls));
                 ToolDispatchResult first = dispatcher.dispatchWithAttachment(request, control);
                 ToolDispatchResult cached = dispatcher.dispatchWithAttachment(request, control)) {
                assertNull(first.takeImage()); assertNull(cached.takeImage());
                assertEquals("INVALID_RESULT", first.getResult().get("code").getAsString());
                assertFalse(first.getResult().get("ok").getAsBoolean());
                assertFalse(cached.getResult().toString().contains("imageDataUrl"));
                assertFalse(cached.getResult().toString().contains("base64"));
                assertEquals(1, calls.get());
            }
        }
    }

    @Test public void forgedToolImagesFailedCapturesAndMismatchedMetadataAreRejected() throws Exception {
        String dataUrl = url(validPng());
        for (String tool : List.of("observe", "wait", "take_screenshot")) {
            JsonObject local = screenshot(dataUrl);
            if ("take_screenshot".equals(tool)) local.addProperty("ok", false);
            assertInvalidSource(tool, local);
        }
        for (Consumer<JsonObject> change : List.<Consumer<JsonObject>>of(
                value -> value.getAsJsonObject("data").addProperty("revision", "another-screen"),
                value -> value.getAsJsonObject("data").addProperty("width", 1),
                value -> value.getAsJsonObject("data").addProperty("height", "1"),
                value -> value.remove("data"), value -> value.remove("imageDataUrl"))) {
            JsonObject local = screenshot(dataUrl); change.accept(local); assertInvalidSource("take_screenshot", local);
        }
    }

    private static void assertInvalidSource(String tool, JsonObject local) throws Exception {
        RunControl control = new RunControl();
        try (ToolDispatcher dispatcher = new ToolDispatcher(source(local, new AtomicInteger()));
             ToolDispatchResult result = dispatcher.dispatchWithAttachment(request(control, "forged", tool), control)) {
            assertNull(result.takeImage()); assertEquals("INVALID_RESULT", result.getResult().get("code").getAsString());
            assertFalse(result.getResult().toString().contains("base64"));
        }
    }

    private static String tool(String id, String name) {
        JsonObject function = new JsonObject(); function.addProperty("name", name);
        JsonObject args = new JsonObject();
        if (ToolCatalog.requiresRevision(name)) args.addProperty("expectedRevision", REVISION);
        if ("click".equals(name)) args.addProperty("nodeId", "n1");
        if ("wait".equals(name)) args.addProperty("milliseconds", 0);
        function.addProperty("arguments", args.toString());
        JsonObject call = new JsonObject(); call.addProperty("id", id); call.addProperty("type", "function"); call.add("function", function);
        return call.toString();
    }

    private static String plans(String... calls) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + String.join(",", calls) + "]}}]}";
    }
    private static String finish() { return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"已查看，请核对。\"}}]}"; }

    private static final class Events implements AgentRunner.Listener {
        final List<String> log = new CopyOnWriteArrayList<>();
        final AtomicInteger modelWaits = new AtomicInteger();
        Consumer<Integer> beforeModel = ignored -> { };
        volatile TaskResult result;
        public void onStatus(String status) {
            log.add(status);
            if ("等待模型".equals(status)) beforeModel.accept(modelWaits.incrementAndGet());
        }
        public void onStep(String step) { log.add(step); }
        public void onFinished(String summary, boolean verified) { log.add(summary); }
        public void onResult(TaskResult result) { this.result = result; AgentRunner.Listener.super.onResult(result); }
    }

    private static final class Device implements ToolHandler {
        final String dataUrl;
        final AtomicInteger captures = new AtomicInteger();
        final List<String> calls = new CopyOnWriteArrayList<>();
        Device() throws IOException { dataUrl = url(validPng()); }
        public JsonObject execute(String name, JsonObject args, RunControl control) {
            calls.add(name);
            if ("take_screenshot".equals(name)) { captures.incrementAndGet(); return screenshot(dataUrl); }
            return "observe".equals(name) ? observation() : ok();
        }
        public Set<String> availableTools() {
            Set<String> names = new HashSet<>(ToolHandler.super.availableTools()); names.add("take_screenshot"); return names;
        }
    }

    private static final class Reply {
        final int status; final String body;
        Reply(String body) { this(200, body); }
        Reply(int status, String body) { this.status = status; this.body = body; }
    }
    @FunctionalInterface private interface Responder { Reply respond(int index) throws Exception; }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<JsonObject> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Server(Responder responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    int index = requests.size(); requests.add(request);
                    Reply reply = responder.respond(index); byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(reply.status, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) { // Cancellation can close the socket before the test response.
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
                } catch (Throwable problem) { failure.compareAndSet(null, problem);
                } finally { exchange.close(); }
            }); server.start();
        }
        AgentRunner runner(ToolHandler device, Events events, long timeout) {
            ModelSettings settings = new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test-model");
            return new AgentRunner(settings, device, events, new HttpModelClient(timeout));
        }
        void assertHealthy() { assertNull("local HTTP server failed: " + failure.get(), failure.get()); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }

    private static int imageCount(JsonObject request) {
        int count = 0;
        for (JsonElement entry : request.getAsJsonArray("messages")) {
            JsonObject message = entry.getAsJsonObject(); JsonElement content = message.get("content");
            if (content == null || !content.isJsonArray()) continue;
            for (JsonElement item : content.getAsJsonArray()) if ("image_url".equals(item.getAsJsonObject().get("type").getAsString())) count++;
        }
        return count;
    }

    @Test public void screenshotIsSentOnlyInNextHttpRequestAndDuplicateCallCannotReplayIt() throws Exception {
        Device device = new Device(); Events events = new Events();
        try (Server server = new Server(index -> new Reply(switch (index) {
            case 0, 2 -> plans(tool("same-image", "take_screenshot"));
            case 1 -> plans(tool("refresh", "observe"));
            default -> finish();
        }))) {
            server.runner(device, events, 2000).run("查看当前画面", new RunControl());
            assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertEquals(4, server.requests.size()); assertEquals(1, device.captures.get());
            for (int i = 0; i < 4; i++) {
                JsonObject request = server.requests.get(i);
                assertEquals(i == 1 ? 1 : 0, imageCount(request));
                assertEquals(i == 1, request.toString().contains(device.dataUrl));
                for (JsonElement entry : request.getAsJsonArray("messages")) {
                    JsonObject message = entry.getAsJsonObject();
                    if ("tool".equals(message.get("role").getAsString())) assertFalse(message.get("content").getAsString().contains(device.dataUrl));
                }
            }
            assertTrue(server.requests.get(3).toString().contains("not_replayed"));
            assertFalse(String.join("\n", events.log).contains(device.dataUrl)); server.assertHealthy();
        }
    }

    @Test public void secondScreenshotAndWriteInSameBatchAreRejectedBeforeDeviceEntry() throws Exception {
        Device device = new Device(); Events events = new Events();
        try (Server server = new Server(index -> new Reply(index == 0
                ? plans(tool("one", "take_screenshot"), tool("two", "take_screenshot"), tool("write", "click")) : finish()))) {
            server.runner(device, events, 2000).run("查看当前画面", new RunControl());
            assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertEquals(List.of("observe", "take_screenshot"), device.calls);
            assertEquals(1, imageCount(server.requests.get(1))); assertEquals(1, device.captures.get());
            int stale = 0;
            for (JsonElement entry : server.requests.get(1).getAsJsonArray("messages")) {
                JsonObject message = entry.getAsJsonObject();
                if ("tool".equals(message.get("role").getAsString()) && message.get("content").getAsString().contains("STALE_OBSERVATION")) stale++;
            }
            assertEquals(2, stale); server.assertHealthy();
        }
    }

    @Test public void observeOrWaitAfterCaptureDiscardsThePendingImage() throws Exception {
        for (String next : List.of("observe", "wait")) {
            Device device = new Device(); Events events = new Events();
            try (Server server = new Server(index -> new Reply(index == 0
                    ? plans(tool("image", "take_screenshot"), tool("next", next)) : finish()))) {
                server.runner(device, events, 2000).run("查看当前画面", new RunControl());
                assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind); assertEquals(2, server.requests.size());
                assertEquals(1, device.captures.get()); assertEquals(List.of("observe", "take_screenshot", next), device.calls);
                for (JsonObject request : server.requests) { assertEquals(0, imageCount(request)); assertFalse(request.toString().contains(device.dataUrl)); }
                server.assertHealthy();
            }
        }
    }

    @Test public void pauseResumeBeforeImageRequestCannotMoveTheOldImageToTheNewEpoch() throws Exception {
        RunControl control = new RunControl(); Device device = new Device(); Events events = new Events();
        long oldEpoch = control.getExecutionEpoch();
        events.beforeModel = round -> { if (round == 2) { control.pause(); control.resume(); } };
        try (Server server = new Server(index -> new Reply(index == 0 ? plans(tool("image", "take_screenshot")) : finish()))) {
            server.runner(device, events, 2000).run("查看当前画面", control);
            assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind); assertTrue(control.getExecutionEpoch() > oldEpoch);
            assertEquals(List.of("observe", "take_screenshot", "observe"), device.calls); assertEquals(2, server.requests.size());
            for (JsonObject request : server.requests) { assertEquals(0, imageCount(request)); assertFalse(request.toString().contains(device.dataUrl)); }
            server.assertHealthy();
        }
    }

    @Test public void cancellationBeforeImageRequestPreventsAnyImageUpload() throws Exception {
        RunControl control = new RunControl(); Device device = new Device(); Events events = new Events();
        events.beforeModel = round -> { if (round == 2) control.cancel(); };
        try (Server server = new Server(index -> new Reply(plans(tool("image", "take_screenshot"))))) {
            server.runner(device, events, 2000).run("查看当前画面", control);
            assertEquals(TaskResult.Kind.STOPPED, events.result.kind); assertEquals(1, server.requests.size());
            assertEquals(1, device.captures.get()); assertEquals(0, imageCount(server.requests.get(0)));
            assertFalse(String.join("\n", events.log).contains(device.dataUrl)); server.assertHealthy();
        }
    }

    @Test(timeout = 5000) public void failedOrTimedOutImageUploadIsNeverAutomaticallyRetried() throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            Device device = new Device(); Events events = new Events(); CountDownLatch release = new CountDownLatch(1);
            try (Server server = new Server(index -> {
                if (index == 0) return new Reply(plans(tool("image", "take_screenshot")));
                if (timeout) release.await(2, TimeUnit.SECONDS);
                return new Reply(500, "private-server-body");
            })) {
                server.runner(device, events, timeout ? 250 : 2000).run("查看当前画面", new RunControl());
                assertEquals(TaskResult.Kind.FAILED, events.result.kind); assertEquals(2, server.requests.size());
                assertEquals(1, device.captures.get()); assertEquals(1, imageCount(server.requests.get(1)));
                assertFalse(String.join("\n", events.log).contains(device.dataUrl)); server.assertHealthy();
            } finally { release.countDown(); }
        }
    }

    @Test(timeout = 5000) public void cancellationDuringImageUploadStopsWithoutAnotherRequest() throws Exception {
        Device device = new Device(); Events events = new Events(); RunControl control = new RunControl();
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Server server = new Server(index -> {
            if (index == 0) return new Reply(plans(tool("image", "take_screenshot")));
            received.countDown(); release.await(3, TimeUnit.SECONDS); return new Reply(finish());
        })) {
            Future<?> running = worker.submit(() -> server.runner(device, events, 2000).run("查看当前画面", control));
            assertTrue(received.await(2, TimeUnit.SECONDS)); control.cancel(); running.get(1, TimeUnit.SECONDS);
            assertEquals(TaskResult.Kind.STOPPED, events.result.kind); assertEquals(2, server.requests.size());
            assertEquals(1, device.captures.get()); assertEquals(1, imageCount(server.requests.get(1)));
            assertFalse(String.join("\n", events.log).contains(device.dataUrl)); server.assertHealthy();
        } finally { release.countDown(); control.cancel(); worker.shutdownNow(); }
    }

    @Test public void submittedWriteWithInvalidImageStopsAsUnknownAndCannotBeReplayed() throws Exception {
        String invalidImage = "data:image/png;base64,private-invalid-image";
        JsonObject written = screenshot(invalidImage);
        written.addProperty("submitted", true); written.addProperty("accepted", true);
        written.addProperty("dispatchAccepted", true);
        AtomicInteger writes = new AtomicInteger(); List<String> calls = new CopyOnWriteArrayList<>();
        ToolHandler device = (name, args, active) -> {
            calls.add(name);
            if ("observe".equals(name)) return observation();
            assertEquals("click", name);
            active.markDispatchAttempt(active.getExecutionEpoch()); writes.incrementAndGet();
            return written.deepCopy();
        };
        RunControl control = new RunControl(); Events events = new Events();
        try (Server server = new Server(index -> new Reply(plans(tool("submitted-click", "click"))))) {
            server.runner(device, events, 2000).run("执行一次已授权点击", control);
            assertNotNull(events.result); assertEquals(TaskResult.Kind.FAILED, events.result.kind);
            assertEquals("OUTCOME_UNKNOWN", events.result.code);
            assertEquals(1, writes.get()); assertEquals(1, control.getDispatchAttempts());
            assertEquals(List.of("observe", "click"), calls);
            assertEquals("unknown write must terminate before another model request", 1, server.requests.size());
            assertFalse(String.join("\n", events.log).contains(invalidImage));
            assertFalse(server.requests.get(0).toString().contains(invalidImage)); server.assertHealthy();
        }

        AtomicInteger directWrites = new AtomicInteger(); RunControl directControl = new RunControl();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, args, active) -> {
            active.markDispatchAttempt(active.getExecutionEpoch()); directWrites.incrementAndGet();
            return written.deepCopy();
        })) {
            ToolRequest same = request(directControl, "same-submitted-click", "click");
            JsonObject result = dispatcher.dispatch(same, directControl);
            assertEquals("OUTCOME_UNKNOWN", result.get("code").getAsString());
            assertTrue(dispatcher.hasUnknownOutcome()); assertFalse(result.get("ok").getAsBoolean());
            assertFalse(result.toString().contains("imageDataUrl")); assertFalse(result.toString().contains(invalidImage));
            JsonObject cached = dispatcher.dispatch(same, directControl);
            assertEquals(result, cached); assertEquals(1, directWrites.get());
            assertEquals(1, directControl.getDispatchAttempts());
        }
    }
}
