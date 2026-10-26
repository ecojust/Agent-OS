package dev.agentos.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentos.core.TaskBudget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic protocol fixture. It neither contacts nor evaluates a real language model. */
final class LocalControllerModelServer implements AutoCloseable {
    enum Scenario { SAVE_AND_COUNT, REJECT_CLICK, EXHAUST_ROUND_BUDGET }
    static final String SYNTHETIC_KEY = "controller-test-only-key";
    static final String MODEL = "synthetic-controller-model";
    static final String TEXT = "Controller integration sample";
    private final Scenario scenario;
    private final ServerSocket server;
    private final Thread worker;
    private final AtomicInteger requests = new AtomicInteger();
    private final Object changed = new Object();
    private volatile boolean closed;
    private volatile Socket active;
    private volatile Throwable failure;
    private volatile String protocolStage = "listening";
    private volatile String safeObservation = "not observed";
    private volatile String safeToolResults = "none";
    private int phase;
    private int refreshes;
    private volatile boolean finalStateObserved;

    LocalControllerModelServer(Scenario scenario) throws IOException {
        this.scenario = scenario;
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(this::serve, "controller-protocol-fixture");
        worker.setDaemon(true);
        worker.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getLocalPort() + "/v1"; }
    int requestCount() { return requests.get(); }
    boolean finalStateObserved() { return finalStateObserved; }

    void assertHealthy() {
        Throwable problem = failure;
        if (problem != null) throw new AssertionError("Local protocol fixture failed: " + problem.getClass().getSimpleName()
                + "; stage=" + protocolStage + "; phase=" + phaseName() + "; requests=" + requests.get()
                + "; refreshes=" + refreshes + (problem instanceof IOException ? "; reason=" + safeMessage(problem.getMessage()) : "")
                + "\nobservation=" + safeObservation + "\ntoolResults=" + safeToolResults, problem);
    }

    boolean receivedAnotherRequest(int baseline, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (changed) {
            while (requests.get() <= baseline && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                changed.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            }
            return requests.get() > baseline;
        }
    }

    private void serve() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                active = socket;
                socket.setSoTimeout(5_000);
                protocolStage = "read-http-request";
                JsonObject request = readRequest(socket.getInputStream());
                int round = requests.incrementAndGet();
                synchronized (changed) { changed.notifyAll(); }
                protocolStage = "plan-from-device-observation";
                byte[] response = respond(request, round).toString().getBytes(StandardCharsets.UTF_8);
                protocolStage = "write-http-response";
                String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Connection: close\r\nContent-Length: " + response.length + "\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(response);
                socket.getOutputStream().flush();
                protocolStage = "await-next-request";
            } catch (SocketException stopped) {
                if (!closed) failure = stopped;
            } catch (Throwable error) {
                if (!closed) failure = error;
            } finally { active = null; }
        }
    }

    private JsonObject readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int terminator = 0;
        while (terminator != 0x0d0a0d0a) {
            int value = input.read();
            if (value < 0) throw new IOException("Incomplete local HTTP header");
            header.write(value);
            if (header.size() > 16_384) throw new IOException("Local HTTP header exceeded fixture limit");
            terminator = (terminator << 8) | value;
        }
        String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        if (!"POST /v1/chat/completions HTTP/1.1".equals(lines[0])) throw new IOException("Unexpected local protocol route");
        int length = -1;
        boolean authenticated = false;
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator < 0) continue;
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if ("content-length".equals(name)) length = Integer.parseInt(value);
            if ("authorization".equals(name)) authenticated = ("Bearer " + SYNTHETIC_KEY).equals(value);
        }
        if (!authenticated) throw new IOException("Synthetic authentication header missing");
        if (length <= 0 || length > 4 * 1024 * 1024) throw new IOException("Invalid local request length");
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < body.length) {
            int read = input.read(body, offset, body.length - offset);
            if (read < 0) throw new IOException("Incomplete local request body");
            offset += read;
        }
        JsonObject request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!MODEL.equals(request.get("model").getAsString())) throw new IOException("Unexpected synthetic model identity");
        if (request.get("stream").getAsBoolean()) throw new IOException("This fixture expects bounded non-streaming requests");
        if (request.getAsJsonArray("tools").isEmpty()) throw new IOException("Real tool definitions were not supplied");
        return request;
    }

    private JsonObject respond(JsonObject request, int round) throws IOException {
        JsonObject observed = latestObservation(request);
        safeObservation = summarizeObservation(observed);
        safeToolResults = summarizeToolResults(request);
        require(DeviceHarness.FIXTURE.equals(observed.get("packageName").getAsString()), "Unexpected observed application");
        require(observed.get("scoped").getAsBoolean(), "Fixture was not actually authorized");
        if (scenario == Scenario.EXHAUST_ROUND_BUDGET) {
            // The next reply would contain a write, but the controller must never ask for it.
            if (round > TaskBudget.DEFAULT.maxModelRounds) return tool("click", nodeArguments(observed, "increment"), round);
            return tool("observe", new JsonObject(), round);
        }
        if (scenario == Scenario.REJECT_CLICK) {
            require(round == 1, "Task requested another plan after its action was rejected");
            return tool("click", nodeArguments(observed, "increment"), round);
        }
        if (phase == 0) {
            require("Count: 0".equals(nodeText(observed, "counter_value")), "Fixture did not start from a clean counter");
            JsonObject args = nodeArguments(observed, "edit_value");
            args.addProperty("text", TEXT);
            phase++;
            return tool("type_text", args, round);
        }
        if (phase == 1) {
            if (!TEXT.equals(nodeText(observed, "edit_value"))) return refresh(round);
            phase++; refreshes = 0;
            return tool("click", nodeArguments(observed, "save_value"), round);
        }
        if (phase == 2) {
            if (!("Saved: " + TEXT).equals(nodeText(observed, "saved_value"))) return refresh(round);
            phase++; refreshes = 0;
            return tool("click", nodeArguments(observed, "increment"), round);
        }
        require(phase == 3, "Unexpected extra planning round");
        if (!"Count: 1".equals(nodeText(observed, "counter_value"))) return refresh(round);
        finalStateObserved = true;
        phase++;
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", "Synthetic fixture observed: saved text and counter 1.");
        return envelope(message);
    }

    private JsonObject refresh(int round) throws IOException {
        require(++refreshes <= 3, "Submitted plan did not produce the expected observable fixture result");
        // A dispatch acknowledgement need not mean the remote view's update is visible yet.
        // Use bounded read-only tools; never repeat an already submitted write.
        JsonObject wait = new JsonObject(); wait.addProperty("milliseconds", 125);
        JsonObject response = tool("wait", wait, round);
        JsonArray calls = response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls");
        calls.get(0).getAsJsonObject().addProperty("id", "script-round-" + round + "-wait");
        JsonObject observe = tool("observe", new JsonObject(), round).getAsJsonArray("choices").get(0)
                .getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        observe.addProperty("id", "script-round-" + round + "-observe");
        calls.add(observe);
        return response;
    }

    private static JsonObject latestObservation(JsonObject request) throws IOException {
        JsonArray messages = request.getAsJsonArray("messages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonObject message = messages.get(index).getAsJsonObject();
            JsonElement content = message.get("content");
            if (!"user".equals(message.get("role").getAsString()) || content == null || !content.isJsonPrimitive()) continue;
            String value = content.getAsString();
            if (!value.startsWith("最新本机观察")) continue;
            int json = value.indexOf('{');
            if (json >= 0) return JsonParser.parseString(value.substring(json)).getAsJsonObject().getAsJsonObject("data");
        }
        throw new IOException("Planner received no current device observation");
    }

    private static JsonObject node(JsonObject observed, String id) throws IOException {
        for (JsonElement item : observed.getAsJsonArray("nodes")) {
            JsonObject node = item.getAsJsonObject();
            if ((DeviceHarness.FIXTURE + ":id/" + id).equals(node.get("viewId").getAsString())) return node;
        }
        throw new IOException("Required synthetic fixture node is not visible: " + id);
    }

    private static String nodeText(JsonObject observed, String id) throws IOException { return node(observed, id).get("text").getAsString(); }
    private static JsonObject nodeArguments(JsonObject observed, String id) throws IOException {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("nodeId", node(observed, id).get("id").getAsString());
        arguments.addProperty("expectedRevision", observed.get("revision").getAsString());
        return arguments;
    }

    private static JsonObject tool(String name, JsonObject args, int round) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", args.toString());
        JsonObject call = new JsonObject();
        call.addProperty("id", "script-round-" + round);
        call.addProperty("type", "function");
        call.add("function", function);
        JsonArray calls = new JsonArray(); calls.add(call);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.add("tool_calls", calls);
        return envelope(message);
    }

    private static JsonObject envelope(JsonObject message) {
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject(); response.add("choices", choices);
        return response;
    }

    private String phaseName() {
        if (scenario != Scenario.SAVE_AND_COUNT) return scenario.name();
        switch (phase) {
            case 0: return "before-type";
            case 1: return "await-typed-text";
            case 2: return "await-saved-text";
            case 3: return "await-counter-increment";
            default: return "final-report";
        }
    }

    private static String summarizeObservation(JsonObject observed) {
        // Only fixed fixture fields and text classifications are recorded. Never dump
        // model requests, Authorization headers, arbitrary UI text, or key material.
        StringBuilder result = new StringBuilder();
        for (String key : new String[]{"windowId", "focused", "scoped", "hasOcclusion", "truncated"}) {
            result.append(key).append('=').append(observed.has(key) ? observed.get(key) : "missing").append(' ');
        }
        for (String id : new String[]{"edit_value", "saved_value", "counter_value"}) {
            result.append(id).append('{');
            try {
                JsonObject item = node(observed, id);
                String value = DeviceHarness.string(item, "text");
                String classification = value.isEmpty() ? "empty"
                        : TEXT.equals(value) ? "expected-input"
                        : ("Saved: " + TEXT).equals(value) ? "expected-saved"
                        : "Saved: empty".equals(value) ? "initial-saved"
                        : "Count: 0".equals(value) ? "counter-zero"
                        : "Count: 1".equals(value) ? "counter-one"
                        : "[obscured]".equals(value) ? "obscured" : "other-length-" + value.length();
                result.append("text=").append(classification);
                for (String key : new String[]{"obscured", "enabled", "focused", "editable"}) {
                    result.append(',').append(key).append('=').append(item.has(key) ? item.get(key) : "missing");
                }
            } catch (IOException missing) { result.append("missing"); }
            result.append("} ");
        }
        return result.toString();
    }

    private static String summarizeToolResults(JsonObject request) {
        StringBuilder result = new StringBuilder();
        JsonArray messages = request.getAsJsonArray("messages");
        for (int index = messages.size() - 1, count = 0; index >= 0 && count < 10; index--) {
            JsonObject message = messages.get(index).getAsJsonObject();
            if (!"tool".equals(DeviceHarness.string(message, "role"))) continue;
            count++;
            try {
                JsonObject tool = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
                String callId = DeviceHarness.string(message, "tool_call_id");
                result.append("{call=").append(callId.matches("script-round-[0-9]+(?:-wait|-observe)?") ? callId : "other");
                result.append(",ok=").append(tool.has("ok") ? tool.get("ok") : "missing");
                String code = DeviceHarness.string(tool, "code");
                // Codes are protocol identifiers, never free-form error descriptions.
                result.append(",code=").append(code.matches("[A-Z_]{0,80}") ? code : "nonstandard");
                for (String flag : new String[]{"submitted", "accepted"}) {
                    if (tool.has(flag)) result.append(',').append(flag).append('=').append(tool.get(flag).getAsBoolean());
                }
                result.append("} ");
            } catch (RuntimeException malformed) { result.append("{unreadable-result} "); }
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    private static String safeMessage(String message) {
        if (message == null) return "none";
        String bounded = message.replace(SYNTHETIC_KEY, "[redacted]").replace('\r', ' ').replace('\n', ' ');
        return bounded.substring(0, Math.min(bounded.length(), 512));
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }

    @Override public void close() throws Exception {
        closed = true;
        server.close();
        Socket current = active;
        if (current != null) current.close();
        synchronized (changed) { changed.notifyAll(); }
        worker.join(2_000);
        if (worker.isAlive()) throw new IOException("Local model fixture did not terminate");
    }
}
