package dev.agentos.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.agentos.core.AgentRunner;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskResult;
import dev.agentos.core.ToolHandler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Synthetic pixels and real loopback HTTP only; no device observation, capture, or permissions. */
@RunWith(AndroidJUnit4.class)
public final class ImageTransportInstrumentedTest {
    private static final String REVISION = "synthetic-image-r1";
    private static final String TASK = "合成图像传输 <tag attr='x'>&= \"quoted\" \\ path\ncafé 😀 \u2028\u2029";
    private static final String COMPLETE = "synthetic-image-transport-complete";
    private static final String PREFIX = "data:image/png;base64,";
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence(
            "synthetic_http_image_transport_nonvisual_no_device_capture");

    @Test public void androidDecodesTheSingleUsePngFromRealHttpAndLaterHistoryHasNoPixels() throws Exception {
        awaitEmptyWorkset();
        RunControl control = new RunControl();
        long epoch = control.getExecutionEpoch();
        AtomicInteger observations = new AtomicInteger(), captures = new AtomicInteger();
        AtomicReference<TaskResult> completion = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> running = null;
        try (SyntheticImage image = new SyntheticImage(); ImageServer server = new ImageServer(image)) {
            try {
                ToolHandler synthetic = new ToolHandler() {
                    @Override public Set<String> availableTools() {
                        return new HashSet<>(Arrays.asList("observe", "take_screenshot"));
                    }
                    @Override public JsonObject execute(String name, JsonObject arguments, RunControl active) throws Exception {
                        assertSame(control, active);
                        active.checkExecutionEpoch(epoch);
                        JsonObject result = new JsonObject(); result.addProperty("ok", true);
                        JsonObject data = new JsonObject(); data.addProperty("revision", REVISION);
                        if ("observe".equals(name)) {
                            observations.incrementAndGet();
                            data.addProperty("packageName", "synthetic.image.fixture");
                            data.add("nodes", new JsonArray());
                        } else {
                            assertEquals("take_screenshot", name);
                            assertEquals(REVISION, arguments.get("expectedRevision").getAsString());
                            assertEquals("The synthetic attachment must be created once", 1, captures.incrementAndGet());
                            data.addProperty("width", SyntheticImage.WIDTH);
                            data.addProperty("height", SyntheticImage.HEIGHT);
                            // The public legacy ToolHandler boundary still accepts a local
                            // data URL. The production dispatcher/runner own the typed PNG
                            // and the real HTTP serializer after this synthetic result.
                            result.addProperty("imageDataUrl", PREFIX + Base64.encodeToString(image.png, Base64.NO_WRAP));
                        }
                        result.add("data", data);
                        return result;
                    }
                };
                AgentRunner runner = new AgentRunner(new ModelSettings(server.endpoint(), "synthetic-image-key", "synthetic-image-model"),
                        synthetic, new AgentRunner.Listener() {
                            @Override public void onStatus(String status) { }
                            @Override public void onStep(String step) { }
                            @Override public void onFinished(String summary, boolean verified) { }
                            @Override public void onResult(TaskResult result) { completion.set(result); }
                        }, control, (original, originalEpoch) -> original == control && originalEpoch == epoch);
                running = worker.submit(() -> runner.run(TASK, control));
                try { running.get(20, TimeUnit.SECONDS); }
                finally { server.assertHealthy(); }
                assertNotNull(completion.get());
                assertEquals(TaskResult.Kind.MODEL_REPORT, completion.get().kind);
                assertEquals("MODEL_REPORT", completion.get().code);
                assertFalse(completion.get().verified);
                assertTrue(completion.get().summary.contains(COMPLETE));
                assertEquals(4, server.requests.get());
                assertEquals(1, server.images.get());
                assertTrue(server.historyWasPixelFree);
                assertEquals(1, captures.get());
                assertEquals(3, observations.get());
                assertEquals(0, control.getDispatchAttempts());
                awaitEmptyWorkset();
            } finally {
                // Stop the caller before either test server or source pixels are
                // released, including when the bounded get or an assertion fails.
                control.cancel();
                if (running != null) running.cancel(true);
                worker.shutdownNow();
                assertTrue("The synthetic runner worker did not stop", worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        } finally {
            control.cancel();
            if (running != null) running.cancel(true);
            worker.shutdownNow();
            assertTrue("The synthetic runner worker did not stop", worker.awaitTermination(5, TimeUnit.SECONDS));
            awaitEmptyWorkset();
        }
    }

    private static void awaitEmptyWorkset() {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        int emptyReads = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ImageWorkset.process().reservedBytes() == 0) {
                if (++emptyReads == 2) return;
            } else emptyReads = 0;
            SystemClock.sleep(25);
        }
        fail("Known payload owners from this or prior work remain: " + ImageWorkset.process().reservedBytes());
    }

    private static final class SyntheticImage implements AutoCloseable {
        static final int WIDTH = 97, HEIGHT = 53;
        final byte[] png;
        final int[] pixels = new int[WIDTH * HEIGHT];

        SyntheticImage() {
            for (int index = 0; index < pixels.length; index++) {
                int value = index + 1;
                value ^= value >>> 16; value *= 0x7feb352d;
                value ^= value >>> 15; value *= 0x846ca68b;
                value ^= value >>> 16;
                pixels[index] = 0xff000000 | value & 0x00ffffff;
            }
            Bitmap bitmap = Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                png = output.toByteArray();
                assertTrue("Exercise more than one small transport block", png.length > 8192);
            } finally { bitmap.recycle(); }
        }

        @Override public void close() { Arrays.fill(png, (byte) 0); Arrays.fill(pixels, 0); }
    }

    private static final class ImageServer implements AutoCloseable {
        private final SyntheticImage expected;
        private final ServerSocket listener;
        private final Thread thread;
        private volatile boolean closed;
        private volatile Socket active;
        private volatile Throwable failure;
        final AtomicInteger requests = new AtomicInteger(), images = new AtomicInteger();
        volatile boolean historyWasPixelFree;

        ImageServer(SyntheticImage expected) throws IOException {
            this.expected = expected;
            listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            try {
                listener.setSoTimeout(10_000);
                thread = new Thread(this::serve, "synthetic-android-image-http");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException | RuntimeException | Error failure) {
                try { listener.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        String endpoint() { return "http://127.0.0.1:" + listener.getLocalPort() + "/v1"; }

        private void serve() {
            while (!closed && failure == null) {
                try (Socket socket = listener.accept()) {
                    active = socket;
                    if (closed) continue;
                    socket.setSoTimeout(5_000);
                    JsonObject request = readRequest(socket.getInputStream());
                    int round = requests.incrementAndGet();
                    assertTrue("Unexpected extra model request", round <= 4);
                    verifyRequest(request, round);
                    byte[] response = response(round).toString().getBytes(StandardCharsets.UTF_8);
                    try {
                        String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nConnection: close\r\nContent-Length: "
                                + response.length + "\r\n\r\n";
                        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(response);
                        socket.getOutputStream().flush();
                    } finally { Arrays.fill(response, (byte) 0); }
                } catch (Throwable problem) {
                    if (!closed) failure = problem;
                } finally { active = null; }
            }
        }

        private JsonObject readRequest(InputStream input) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int end = 0;
            while (end != 0x0d0a0d0a) {
                int value = input.read();
                if (value < 0) throw new IOException("Incomplete synthetic HTTP header");
                header.write(value);
                if (header.size() > 16_384) throw new IOException("Synthetic header exceeded its bound");
                end = end << 8 | value;
            }
            String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
            assertEquals("POST /v1/chat/completions HTTP/1.1", lines[0]);
            int length = -1;
            boolean authenticated = false;
            for (String line : lines) {
                int separator = line.indexOf(':');
                if (separator < 0) continue;
                String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                if ("content-length".equals(name)) length = Integer.parseInt(value);
                if ("authorization".equals(name)) authenticated = "Bearer synthetic-image-key".equals(value);
            }
            assertTrue("Only synthetic authentication belongs in this fixture", authenticated);
            assertTrue("Bound the independently read HTTP body", length > 0 && length <= 4 * 1024 * 1024);
            byte[] bytes = new byte[length];
            try {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) throw new IOException("Incomplete synthetic HTTP body");
                    offset += read;
                }
                try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
                    reader.setStrictness(Strictness.STRICT);
                    JsonObject request = JsonParser.parseReader(reader).getAsJsonObject();
                    assertEquals(JsonToken.END_DOCUMENT, reader.peek());
                    return request;
                }
            } finally { Arrays.fill(bytes, (byte) 0); }
        }

        private void verifyRequest(JsonObject request, int round) {
            assertEquals("synthetic-image-model", request.get("model").getAsString());
            assertFalse(request.get("stream").getAsBoolean());
            assertFalse(request.getAsJsonArray("tools").isEmpty());
            int pictureParts = 0, imageMetadata = 0;
            boolean taskFound = false;
            for (JsonElement item : request.getAsJsonArray("messages")) {
                JsonObject message = item.getAsJsonObject();
                JsonElement content = message.get("content");
                if (content != null && content.isJsonPrimitive()) {
                    String text = content.getAsString();
                    taskFound |= ("本次用户目标：" + TASK).equals(text);
                    assertFalse("Pixels must never be retained in ordinary history text", text.contains(PREFIX));
                    if ("tool".equals(message.get("role").getAsString())) {
                        JsonObject metadata = JsonParser.parseString(text).getAsJsonObject();
                        assertFalse(metadata.has("imageDataUrl"));
                        if (metadata.has("imageAttachment")) {
                            imageMetadata++;
                            JsonObject attachment = metadata.getAsJsonObject("imageAttachment");
                            assertEquals("image/png", attachment.get("mimeType").getAsString());
                            assertEquals(expected.png.length, attachment.get("encodedBytes").getAsInt());
                            assertEquals(REVISION, attachment.get("revision").getAsString());
                        }
                    }
                } else if (content != null && content.isJsonArray()) {
                    for (JsonElement part : content.getAsJsonArray()) {
                        JsonObject value = part.getAsJsonObject();
                        if (!"image_url".equals(value.get("type").getAsString())) continue;
                        pictureParts++;
                        String url = value.getAsJsonObject("image_url").get("url").getAsString();
                        assertTrue(url.startsWith(PREFIX));
                        // Android's own decoder and image codec independently inspect
                        // the actual wire result, not the core encoder's output helper.
                        byte[] decoded = Base64.decode(url.substring(PREFIX.length()), Base64.DEFAULT);
                        Bitmap bitmap = null;
                        try {
                            assertArrayEquals(expected.png, decoded);
                            bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                            assertNotNull(bitmap);
                            assertEquals(SyntheticImage.WIDTH, bitmap.getWidth());
                            assertEquals(SyntheticImage.HEIGHT, bitmap.getHeight());
                            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
                                assertEquals(expected.pixels[y * bitmap.getWidth() + x], bitmap.getPixel(x, y));
                            images.incrementAndGet();
                        } finally { if (bitmap != null) bitmap.recycle(); Arrays.fill(decoded, (byte) 0); }
                    }
                }
            }
            assertTrue("UTF-8 task text changed on the Android wire path", taskFound);
            assertEquals(round == 3 ? 1 : 0, pictureParts);
            assertEquals(round >= 3 ? 1 : 0, imageMetadata);
            if (round == 4) {
                assertFalse(request.toString().contains(PREFIX));
                historyWasPixelFree = true;
            }
        }

        private static JsonObject response(int round) {
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant");
            if (round == 4) message.addProperty("content", COMPLETE);
            else {
                JsonObject args = new JsonObject();
                if (round == 2) args.addProperty("expectedRevision", REVISION);
                JsonObject function = new JsonObject(); function.addProperty("name", round == 2 ? "take_screenshot" : "observe");
                function.addProperty("arguments", args.toString());
                JsonObject call = new JsonObject(); call.addProperty("id", "synthetic-image-" + round);
                call.addProperty("type", "function"); call.add("function", function);
                JsonArray calls = new JsonArray(); calls.add(call); message.add("tool_calls", calls);
            }
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject response = new JsonObject(); response.add("choices", choices); return response;
        }

        void assertHealthy() {
            if (failure != null) throw new AssertionError("Synthetic HTTP fixture failed at request " + requests.get(), failure);
        }

        @Override public void close() throws Exception {
            closed = true;
            try { listener.close(); }
            finally {
                Socket socket = active;
                try { if (socket != null) socket.close(); }
                finally { thread.join(3_000); }
            }
            assertFalse("The synthetic HTTP worker did not stop", thread.isAlive());
            assertHealthy();
        }
    }
}
