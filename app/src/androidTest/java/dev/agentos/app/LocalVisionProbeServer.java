package dev.agentos.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real loopback HTTP fixture; the original probe route reads only generated challenge pixels. */
final class LocalVisionProbeServer implements AutoCloseable {
    enum Mode { VERIFIED, UNVERIFIED, HELD_VERIFIED, VERIFIED_THEN_UNVERIFIED, HTTP_UNAUTHORIZED }
    static final String KEY = "vision-ui-synthetic-key";
    static final String MODEL = "vision-ui-synthetic-model";
    static final String PRIVATE_TASK = "PRIVATE_TASK_MUST_NOT_BE_SENT";
    private final Mode mode;
    // Optional explicit test route only. Its planner consumes the actual task observation;
    // it never supplies or replaces the synthetic PNG/nonce verification algorithm.
    private final LocalControllerModelServer taskPlanner;
    /** Dedicated native-disclosure test route; never substitutes the real synthetic qualification. */
    interface WindowTaskRoute { JsonObject respond(JsonObject request, int round) throws IOException; }
    private final WindowTaskRoute windowTaskRoute;
    private final AtomicInteger taskRequests = new AtomicInteger();
    private final ServerSocket server;
    private final Thread worker;
    private final Object changed = new Object();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Integer> outputLimits = new ArrayList<>();
    private final AtomicInteger images = new AtomicInteger();
    private final AtomicInteger tools = new AtomicInteger();
    private final CountDownLatch responseGate = new CountDownLatch(1);
    private final CountDownLatch responseAttempted = new CountDownLatch(1);
    private volatile Socket active;
    private volatile boolean closed;
    private volatile Throwable failure;

    LocalVisionProbeServer(Mode mode) throws IOException { this(mode, null); }

    LocalVisionProbeServer(Mode mode, LocalControllerModelServer taskPlanner) throws IOException {
        this(mode, taskPlanner, null);
    }

    static LocalVisionProbeServer forNativeWindowDisclosure(WindowTaskRoute route) throws IOException {
        return new LocalVisionProbeServer(Mode.VERIFIED, null, java.util.Objects.requireNonNull(route));
    }

    private LocalVisionProbeServer(Mode mode, LocalControllerModelServer taskPlanner, WindowTaskRoute windowTaskRoute) throws IOException {
        this.mode = mode;
        this.taskPlanner = taskPlanner;
        this.windowTaskRoute = windowTaskRoute;
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(this::serve, "synthetic-vision-http");
        worker.setDaemon(true); worker.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getLocalPort() + "/v1"; }
    int requestCount() { return requests.get(); }
    /** Only bounded integer transport metadata; never retain extra prompts or credentials. */
    List<Integer> receivedMaxTokens() { synchronized (outputLimits) { return new ArrayList<>(outputLimits); } }
    private void recordMaxTokens(JsonObject request) throws IOException {
        JsonElement value = request.get("max_tokens");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]{1,9}")) throw new IOException("Expected an integer output-token limit");
        int actual = Integer.parseInt(value.getAsString());
        synchronized (outputLimits) {
            if (outputLimits.size() >= 32) throw new IOException("Output-token fixture metadata limit reached");
            outputLimits.add(actual);
        }
    }
    int imageRequestCount() { return images.get(); }
    int toolRequestCount() { return tools.get(); }
    int taskRequestCount() { return taskRequests.get(); }
    void releaseResponse() { responseGate.countDown(); }
    boolean awaitResponseAttempt() throws InterruptedException { return responseAttempted.await(5, TimeUnit.SECONDS); }

    boolean awaitRequests(int count, long timeoutMillis) throws InterruptedException {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (changed) {
            while (requests.get() < count && failure == null && !closed) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) break;
                changed.wait(Math.max(1, remaining / 1_000_000L));
            }
            assertHealthy(); return requests.get() >= count;
        }
    }

    void assertHealthy() {
        if (failure != null) throw new AssertionError("Synthetic vision HTTP fixture failed: "
                + failure.getClass().getSimpleName(), failure);
    }

    private void serve() {
        while (!closed) {
            boolean writing = false;
            boolean heldSynthetic = false;
            try (Socket socket = server.accept()) {
                active = socket;
                if (closed) continue; // A close racing accept must not leave this new socket alive.
                socket.setSoTimeout(5000);
                JsonObject request = readRequest(socket.getInputStream());
                recordMaxTokens(request);
                boolean synthetic = isSyntheticProbe(request);
                heldSynthetic = mode == Mode.HELD_VERIFIED && (taskPlanner == null || synthetic);
                JsonObject reply = response(request);
                requests.incrementAndGet();
                synchronized (changed) { changed.notifyAll(); }
                if (heldSynthetic && !responseGate.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("Synthetic response was never released");
                }
                byte[] body = reply.toString().getBytes(StandardCharsets.UTF_8);
                writing = true;
                try {
                    String status = mode == Mode.HTTP_UNAUTHORIZED && synthetic ? "401 Unauthorized" : "200 OK";
                    String header = "HTTP/1.1 " + status + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                            + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                    socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body); socket.getOutputStream().flush();
                } finally { Arrays.fill(body, (byte) 0); responseAttempted.countDown(); }
            } catch (IOException error) {
                // Explicitly cancelled held probes may close their client before this write.
                if (!closed && !(writing && heldSynthetic)) failure = error;
            } catch (Throwable error) {
                if (!closed) failure = error;
            } finally {
                active = null;
                synchronized (changed) { changed.notifyAll(); }
            }
        }
    }

    private JsonObject readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int suffix = 0;
        while (suffix != 0x0d0a0d0a) {
            int next = input.read();
            require(next >= 0, "Incomplete HTTP header"); header.write(next);
            require(header.size() <= 16_384, "HTTP header exceeded fixture budget");
            suffix = suffix << 8 | next;
        }
        String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        require("POST /v1/chat/completions HTTP/1.1".equals(lines[0]), "Unexpected probe endpoint");
        int length = -1; boolean authorized = false;
        for (String line : lines) {
            int colon = line.indexOf(':'); if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length")) length = Integer.parseInt(value);
            if (name.equals("authorization")) authorized = value.equals("Bearer " + KEY);
        }
        require(authorized, "Synthetic credential missing");
        require(length > 0 && length <= (windowTaskRoute == null ? 256 * 1024 : 2 * 1024 * 1024), "Unexpected probe body length");
        byte[] bytes = new byte[length];
        try {
            int offset = 0;
            while (offset < length) {
                int count = input.read(bytes, offset, length - offset);
                require(count > 0, "Incomplete probe body"); offset += count;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            JsonObject request = JsonParser.parseString(text).getAsJsonObject();
            require(MODEL.equals(request.get("model").getAsString()), "Unexpected model identity");
            require(!request.get("stream").getAsBoolean(), "Probe unexpectedly streamed");
            return request;
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static boolean isSyntheticProbe(JsonObject request) {
        JsonArray definitions = request.getAsJsonArray("tools");
        if (definitions == null || definitions.size() != 1) return false;
        String name = definitions.get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
        return "agent_os_vision_probe".equals(name) || "agent_os_probe".equals(name);
    }

    private JsonObject response(JsonObject request) throws IOException {
        if (!isSyntheticProbe(request)) {
            if (windowTaskRoute != null) return windowTaskRoute.respond(request, taskRequests.incrementAndGet());
            require(taskPlanner != null, "No structured task route was explicitly installed");
            require(request.toString().contains(PRIVATE_TASK), "The explicit structured route did not receive the real synthetic task");
            require(!request.toString().contains("image_url"), "The structured task unexpectedly received an image");
            JsonArray definitions = request.getAsJsonArray("tools");
            require(definitions != null && !definitions.isEmpty(), "Missing real structured tools");
            boolean observe = false, click = false;
            for (JsonElement definition : definitions) {
                String name = definition.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
                require(!"take_screenshot".equals(name) && !"tap".equals(name) && !"swipe".equals(name),
                        "Qualification must not advertise disabled device capabilities");
                require(!name.startsWith("agent_os_"), "Synthetic diagnostic tool leaked into the real task");
                observe |= "observe".equals(name); click |= "click".equals(name);
            }
            require(observe && click, "The real structured task tool set was not supplied");
            return taskPlanner.respond(request, taskRequests.incrementAndGet());
        }
        require(!request.toString().contains(PRIVATE_TASK), "Task content entered a capability probe");
        JsonArray definitions = request.getAsJsonArray("tools");
        require(definitions != null && definitions.size() == 1, "Probe must define one synthetic tool");
        JsonObject function = definitions.get(0).getAsJsonObject().getAsJsonObject("function");
        String name = function.get("name").getAsString();
        String nonce = function.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("nonce").getAsJsonArray("enum").get(0).getAsString();
        JsonObject arguments = new JsonObject(); arguments.addProperty("nonce", nonce);
        if ("agent_os_vision_probe".equals(name)) {
            arguments.add("colors", imageColors(request)); images.incrementAndGet();
        } else {
            require("agent_os_probe".equals(name), "Unexpected executable tool");
            require(!request.toString().contains("image_url"), "Tool probe unexpectedly included an image");
            tools.incrementAndGet();
        }
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant");
        if (mode == Mode.UNVERIFIED || mode == Mode.VERIFIED_THEN_UNVERIFIED && requests.get() > 0) {
            message.addProperty("content", "Synthetic service response without a verified image answer.");
        } else {
            JsonObject invocation = new JsonObject(); invocation.addProperty("name", name);
            invocation.addProperty("arguments", arguments.toString());
            JsonObject call = new JsonObject(); call.addProperty("id", "synthetic-probe-" + (requests.get() + 1));
            call.addProperty("type", "function"); call.add("function", invocation);
            JsonArray calls = new JsonArray(); calls.add(call); message.add("tool_calls", calls);
        }
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject reply = new JsonObject(); reply.add("choices", choices); return reply;
    }

    private JsonArray imageColors(JsonObject request) throws IOException {
        String dataUrl = null;
        for (JsonElement entry : request.getAsJsonArray("messages")) {
            JsonElement content = entry.getAsJsonObject().get("content");
            if (content == null || !content.isJsonArray()) continue;
            for (JsonElement part : content.getAsJsonArray()) {
                JsonObject item = part.getAsJsonObject();
                if (!"image_url".equals(item.get("type").getAsString())) continue;
                require(dataUrl == null, "More than one synthetic image was sent");
                dataUrl = item.getAsJsonObject("image_url").get("url").getAsString();
            }
        }
        String prefix = "data:image/png;base64,";
        require(dataUrl != null && dataUrl.startsWith(prefix), "Synthetic PNG was not attached");
        byte[] bytes = Base64.decode(dataUrl.substring(prefix.length()), Base64.NO_WRAP);
        Bitmap image = null;
        try {
            require(bytes.length <= 64 * 1024, "Synthetic PNG exceeded its budget");
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false;
            image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            require(image != null && image.getWidth() == 512 && image.getHeight() == 256,
                    "Synthetic challenge geometry changed");
            JsonArray colors = new JsonArray();
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 4; column++) {
                    colors.add(colorName(image.getPixel(column * 128 + 64, row * 128 + 64) & 0xffffff));
                }
            }
            return colors;
        } finally { Arrays.fill(bytes, (byte) 0); if (image != null) image.recycle(); }
    }

    private static String colorName(int rgb) throws IOException {
        switch (rgb) {
            case 0xff0000: return "red"; case 0x00ff00: return "green";
            case 0x0000ff: return "blue"; case 0xffff00: return "yellow";
            case 0x00ffff: return "cyan"; case 0xff00ff: return "magenta";
            case 0x000000: return "black"; case 0xffffff: return "white";
            default: throw new IOException("Unknown synthetic color");
        }
    }

    private static void require(boolean condition, String reason) throws IOException {
        if (!condition) throw new IOException(reason);
    }

    @Override public void close() throws Exception {
        closed = true; responseGate.countDown();
        try { server.close(); } catch (SocketException ignored) { }
        Socket connection = active; if (connection != null) connection.close();
        worker.interrupt(); worker.join(2000);
        if (worker.isAlive()) throw new AssertionError("Local HTTP worker did not actually stop");
        assertHealthy();
    }
}
