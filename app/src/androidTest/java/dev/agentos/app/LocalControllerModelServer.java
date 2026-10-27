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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic protocol fixture. It neither contacts nor evaluates a real language model. */
final class LocalControllerModelServer implements AutoCloseable {
    enum Scenario { SAVE_AND_COUNT, REJECT_CLICK, EXHAUST_ROUND_BUDGET, CONTINUOUS_NAVIGATION, QUALIFICATION_PENDING }
    static final String SYNTHETIC_KEY = "controller-test-only-key";
    static final String MODEL = "synthetic-controller-model";
    static final String TEXT = "Controller integration sample";
    static final int MAX_WRITE_REPLANS = 3;
    static final int MAX_WRITE_PLANS = 3 + MAX_WRITE_REPLANS;
    static final int MAX_SCENARIO_REQUESTS = 4 + MAX_WRITE_REPLANS + 3 * 3;
    private final Scenario scenario;
    private final String expectedHomePackage;
    private final ServerSocket server;
    private final Thread worker;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Integer> outputLimits = new ArrayList<>();
    private final Object changed = new Object();
    private volatile boolean closed;
    private volatile Socket active;
    private volatile Throwable failure;
    private volatile String protocolStage = "listening";
    private volatile String safeObservation = "not observed";
    private volatile String safeToolResults = "none";
    private volatile int lastReceivedRequestRound;
    private volatile int phase;
    private volatile int refreshes;
    private volatile String pendingWriteCallId;
    private volatile boolean pendingWriteAccepted;
    private volatile int writePlans;
    private volatile int acceptedWrites;
    private volatile int staleReplans;
    private volatile boolean finalStateObserved;
    private volatile String observedHomePackage = "";

    LocalControllerModelServer(Scenario scenario) throws IOException {
        this(scenario, null);
    }

    LocalControllerModelServer(Scenario scenario, String expectedHomePackage) throws IOException {
        this(scenario, expectedHomePackage, true);
    }

    /** Share planning with the explicit mixed fixture without binding a second HTTP endpoint. */
    static LocalControllerModelServer forPlanningOnly(Scenario scenario) throws IOException {
        return new LocalControllerModelServer(scenario, null, false);
    }

    private LocalControllerModelServer(Scenario scenario, String expectedHomePackage, boolean bindHttp) throws IOException {
        if (scenario == Scenario.CONTINUOUS_NAVIGATION) {
            if (expectedHomePackage == null || expectedHomePackage.isEmpty()
                    || DeviceHarness.FIXTURE.equals(expectedHomePackage) || "dev.agentos.app".equals(expectedHomePackage)) {
                throw new IllegalArgumentException("Navigation fixture requires the device's resolved default HOME package");
            }
        } else if (expectedHomePackage != null) {
            throw new IllegalArgumentException("Only the navigation scenario accepts a default HOME package");
        }
        this.scenario = scenario;
        this.expectedHomePackage = expectedHomePackage;
        if (bindHttp) {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "controller-protocol-fixture");
            worker.setDaemon(true);
            worker.start();
        } else { server = null; worker = null; }
    }

    String endpoint() {
        if (server == null) throw new IllegalStateException("The planning-only fixture has no HTTP endpoint");
        return "http://127.0.0.1:" + server.getLocalPort() + "/v1";
    }
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
    boolean finalStateObserved() { return finalStateObserved; }
    int writePlanCount() { return writePlans; }
    int acceptedWriteCount() { return acceptedWrites; }
    String expectedHomePackage() { return expectedHomePackage; }
    String observedHomePackage() { return observedHomePackage; }

    /** Read-only, bounded synthetic diagnostics; no request body, UI text, or credentials. */
    String safeDiagnostics() {
        String value = "server={snapshot=best-effort,scope=received-requests-only,"
                + "finalExecutionResultMayBeAbsent=true,stage=" + protocolStage
                + ",phase=" + phaseName() + ",requests=" + requests.get()
                + ",lastReceivedRequestRound=" + lastReceivedRequestRound
                + ",writePlans=" + writePlans + ",acceptedWrites=" + acceptedWrites
                + ",staleReplans=" + staleReplans + ",refreshes=" + refreshes
                + ",pendingWrite=" + (pendingWriteCallId != null)
                + ",pendingWriteAccepted=" + pendingWriteAccepted
                + ",finalStateObserved=" + finalStateObserved
                + ",protocolFailed=" + (failure != null) + "}\n"
                + "receivedObservation=" + safeObservation + "\nreceivedToolResults=" + safeToolResults;
        return value.length() <= 4000 ? value : value.substring(0, 4000) + " [capped]";
    }

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
                recordMaxTokens(request);
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

    // Package visible so the fixture's planning rules can be checked without a device.
    JsonObject respond(JsonObject request, int round) throws IOException {
        JsonObject observed = latestObservation(request);
        safeObservation = summarizeObservation(observed);
        safeToolResults = summarizeToolResults(request);
        lastReceivedRequestRound = round;
        if (scenario == Scenario.CONTINUOUS_NAVIGATION) return navigationResponse(request, observed, round);
        require(DeviceHarness.FIXTURE.equals(observed.get("packageName").getAsString()), "Unexpected observed application");
        require(observed.get("scoped").getAsBoolean(), "Fixture was not actually authorized");
        if (scenario == Scenario.EXHAUST_ROUND_BUDGET) {
            // The next reply would contain a write, but the controller must never ask for it.
            if (round > TaskBudget.DEFAULT.maxModelRounds) return tool("click", nodeArguments(observed, "increment"), round);
            return tool("observe", new JsonObject(), round);
        }
        if (scenario == Scenario.QUALIFICATION_PENDING) {
            // Qualification tests never approve these plans and separately require
            // zero platform dispatches. A resumed epoch drops old chat history, so
            // this explicit scenario plans from each new real observation without
            // weakening the accepted/unknown-write replay checks in other scenarios.
            require("Count: 0".equals(nodeText(observed, "counter_value")), "Unapproved qualification task changed the counter");
            require(++writePlans <= 8, "Qualification fixture exceeded its bounded planning attempts");
            return tool("click", nodeArguments(observed, "increment"), round);
        }
        if (scenario == Scenario.REJECT_CLICK) {
            if (pendingWriteCallId != null) {
                JsonObject result = matchingResult(request, pendingWriteCallId);
                require(definitelyUnsubmittedStale(result),
                        "Rejection scenario requested another plan without an explicitly unsubmitted stale result");
                require(++staleReplans <= MAX_WRITE_REPLANS, "Unsubmitted stale plans exceeded the fixture replan limit");
            }
            require("Count: 0".equals(nodeText(observed, "counter_value")), "Rejected fixture action changed the counter");
            require(++writePlans <= 1 + MAX_WRITE_REPLANS, "Rejection scenario exceeded the fixture plan limit");
            pendingWriteCallId = "script-round-" + round;
            return tool("click", nodeArguments(observed, "increment"), round);
        }
        if (pendingWriteCallId != null && !pendingWriteAccepted) {
            JsonObject result = matchingResult(request, pendingWriteCallId);
            if (definitelyUnsubmittedStale(result)) {
                // The executor explicitly rejected this request before submission. A new
                // plan uses the newest observation and gets its own call ID and approval.
                // Never infer this from a missing flag or from the unchanged target alone.
                require(++staleReplans <= MAX_WRITE_REPLANS, "Unsubmitted stale plans exceeded the fixture replan limit");
                pendingWriteCallId = null;
            } else {
                require(flag(result, "ok", true) && flag(result, "submitted", true) && flag(result, "accepted", true)
                                && optionalFlag(result, "dispatchSubmitted", true) && optionalFlag(result, "dispatchAccepted", true),
                        "Write result is not definitely accepted or definitely unsubmitted stale; refusing to replay");
                pendingWriteAccepted = true;
                acceptedWrites++;
            }
        }
        if (pendingWriteAccepted) {
            boolean actualResult = phase == 0 ? TEXT.equals(nodeText(observed, "edit_value"))
                    : phase == 1 ? ("Saved: " + TEXT).equals(nodeText(observed, "saved_value"))
                    : "Count: 1".equals(nodeText(observed, "counter_value"));
            if (!actualResult) return refresh(round);
            phase++;
            pendingWriteCallId = null;
            pendingWriteAccepted = false;
            refreshes = 0;
        }
        if (phase == 3) {
            require(("Saved: " + TEXT).equals(nodeText(observed, "saved_value"))
                    && "Count: 1".equals(nodeText(observed, "counter_value")), "Final fixture state changed unexpectedly");
            require(acceptedWrites == 3, "Final state must follow exactly three accepted writes");
            finalStateObserved = true;
            phase++;
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", "Synthetic fixture observed: saved text and counter 1.");
            return envelope(message);
        }
        require(phase >= 0 && phase < 3, "Unexpected extra planning round");
        require("Count: 0".equals(nodeText(observed, "counter_value")), "Counter changed before its sole accepted increment");
        if (phase >= 1) require(TEXT.equals(nodeText(observed, "edit_value")), "Typed fixture text changed before planning");
        if (phase >= 2) require(("Saved: " + TEXT).equals(nodeText(observed, "saved_value")), "Saved fixture text changed before planning");
        String target = phase == 0 ? "edit_value" : phase == 1 ? "save_value" : "increment";
        JsonObject args = nodeArguments(observed, target);
        if (phase == 0) args.addProperty("text", TEXT);
        require(++writePlans <= MAX_WRITE_PLANS, "Fixture write plans exceeded the task limit");
        pendingWriteCallId = "script-round-" + round;
        return tool(phase == 0 ? "type_text" : "click", args, round);
    }

    /** Requires real HOME and relaunch observations; never replays an accepted or ambiguous write. */
    private JsonObject navigationResponse(JsonObject request, JsonObject observed, int round) throws IOException {
        if (pendingWriteCallId != null && !pendingWriteAccepted) {
            requireFreshObservationAfterResult(request, pendingWriteCallId);
            JsonObject result = matchingResult(request, pendingWriteCallId);
            if (definitelyUnsubmittedStale(result)) {
                require(++staleReplans <= MAX_WRITE_REPLANS, "Navigation stale plans exceeded the replan limit");
                pendingWriteCallId = null;
            } else {
                require(flag(result, "ok", true) && flag(result, "submitted", true) && flag(result, "accepted", true)
                                && optionalFlag(result, "dispatchSubmitted", true) && optionalFlag(result, "dispatchAccepted", true),
                        "Navigation result is neither definitely accepted nor definitely unsubmitted stale");
                pendingWriteAccepted = true;
                acceptedWrites++;
            }
        }
        if (pendingWriteAccepted) {
            boolean actual = phase == 0 ? isUnscopedLauncher(observed)
                    : DeviceHarness.FIXTURE.equals(DeviceHarness.string(observed, "packageName"))
                            && observed.get("scoped").getAsBoolean()
                            && "Count: 0".equals(nodeText(observed, "counter_value"));
            if (!actual) return refresh(round);
            if (phase == 0) observedHomePackage = DeviceHarness.string(observed, "packageName");
            phase++;
            pendingWriteCallId = null;
            pendingWriteAccepted = false;
            refreshes = 0;
        }
        if (phase == 2) {
            require(acceptedWrites == 2, "Navigation must observe exactly two accepted writes");
            require(DeviceHarness.FIXTURE.equals(DeviceHarness.string(observed, "packageName"))
                    && "Count: 0".equals(nodeText(observed, "counter_value")), "Navigation final state changed unexpectedly");
            finalStateObserved = true;
            phase++;
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant");
            message.addProperty("content", "Synthetic navigation observed: launcher then authorized fixture, counter unchanged.");
            return envelope(message);
        }
        require(phase == 0 || phase == 1, "Unexpected extra navigation planning round");
        JsonObject args = new JsonObject();
        if (phase == 0) {
            require(DeviceHarness.FIXTURE.equals(DeviceHarness.string(observed, "packageName"))
                    && observed.get("scoped").getAsBoolean(), "HOME must begin at the authorized fixture");
            require("Count: 0".equals(nodeText(observed, "counter_value")), "Navigation must not change fixture data");
            args.addProperty("expectedRevision", observed.get("revision").getAsString());
        } else {
            require(isUnscopedLauncher(observed), "Relaunch must use a fresh unscoped launcher observation");
            args.addProperty("packageName", DeviceHarness.FIXTURE);
        }
        require(++writePlans <= 2 + MAX_WRITE_REPLANS, "Navigation plans exceeded the task limit");
        pendingWriteCallId = "script-round-" + round;
        return tool(phase == 0 ? "home" : "launch_app", args, round);
    }

    private boolean isUnscopedLauncher(JsonObject observed) {
        return expectedHomePackage.equals(DeviceHarness.string(observed, "packageName"))
                && !observed.get("scoped").getAsBoolean() && observed.getAsJsonArray("nodes").isEmpty();
    }

    private static void requireFreshObservationAfterResult(JsonObject request, String callId) throws IOException {
        int resultIndex = -1, observationIndex = -1;
        JsonArray messages = request.getAsJsonArray("messages");
        for (int index = 0; index < messages.size(); index++) {
            JsonObject message = messages.get(index).getAsJsonObject();
            if ("tool".equals(DeviceHarness.string(message, "role"))
                    && callId.equals(DeviceHarness.string(message, "tool_call_id"))) resultIndex = index;
            if ("user".equals(DeviceHarness.string(message, "role"))
                    && DeviceHarness.string(message, "content").startsWith("最新本机观察")) observationIndex = index;
        }
        require(resultIndex >= 0 && observationIndex > resultIndex,
                "Navigation planning requires a new observation after the previous write result");
    }

    private static JsonObject matchingResult(JsonObject request, String callId) throws IOException {
        JsonArray messages = request.getAsJsonArray("messages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonObject message = messages.get(index).getAsJsonObject();
            if ("tool".equals(DeviceHarness.string(message, "role"))
                    && callId.equals(DeviceHarness.string(message, "tool_call_id"))) {
                try { return JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject(); }
                catch (RuntimeException malformed) { throw new IOException("Pending write result is malformed"); }
            }
        }
        throw new IOException("Pending write result is missing; refusing to guess whether it was submitted");
    }

    private static boolean definitelyUnsubmittedStale(JsonObject result) {
        return flag(result, "ok", false) && "STALE_OBSERVATION".equals(DeviceHarness.string(result, "code"))
                && flag(result, "submitted", false) && optionalFlag(result, "accepted", false)
                && optionalFlag(result, "dispatchSubmitted", false) && optionalFlag(result, "dispatchAccepted", false);
    }

    private static boolean flag(JsonObject value, String name, boolean expected) {
        JsonElement field = value.get(name);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isBoolean() && field.getAsBoolean() == expected;
    }

    private static boolean optionalFlag(JsonObject value, String name, boolean expected) {
        return !value.has(name) || flag(value, name, expected);
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
            case 0: return pendingWriteCallId == null ? "before-type" : "await-typed-text";
            case 1: return "await-saved-text";
            case 2: return "await-counter-increment";
            default: return "final-report";
        }
    }

    private static String summarizeObservation(JsonObject observed) {
        // Only fixed fixture fields and text classifications are recorded. Never dump
        // model requests, Authorization headers, arbitrary UI text, or key material.
        StringBuilder result = new StringBuilder();
        for (String key : new String[]{"windowId", "focused", "scoped", "hasOcclusion", "truncated"}) {
            result.append(key).append('=').append("windowId".equals(key)
                    ? diagnosticInteger(observed, key) : diagnosticBoolean(observed, key)).append(' ');
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
                    result.append(',').append(key).append('=').append(diagnosticBoolean(item, key));
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
                result.append(",ok=").append(diagnosticBoolean(tool, "ok"));
                String code = DeviceHarness.string(tool, "code");
                result.append(",code=").append(diagnosticCode(code));
                for (String flag : new String[]{"submitted", "accepted", "dispatchSubmitted", "dispatchAccepted"}) {
                    result.append(',').append(flag).append('=').append(diagnosticBoolean(tool, flag));
                }
                result.append("} ");
            } catch (RuntimeException malformed) { result.append("{unreadable-result} "); }
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    private static String diagnosticBoolean(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) return "missing";
        if (value.isJsonNull()) return "null";
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? Boolean.toString(value.getAsBoolean()) : "invalid";
    }

    private static String diagnosticInteger(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) return "missing";
        if (value.isJsonNull()) return "null";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return "invalid";
        String number = value.getAsString();
        if (!number.matches("-?[0-9]{1,10}")) return "invalid";
        long integer = Long.parseLong(number);
        return integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE
                ? Long.toString(integer) : "invalid";
    }

    private static String diagnosticCode(String code) {
        switch (code) {
            case "": return "none";
            case "STALE_OBSERVATION": case "OUTCOME_UNKNOWN": case "PERMISSION_DENIED":
            case "USER_DENIED": case "APPROVAL_REJECTED": case "APPROVAL_EXPIRED":
            case "TASK_STOPPED": case "TASK_PAUSED": case "BUDGET_EXCEEDED":
            case "INVALID_ARGUMENT": case "INVALID_RESULT": case "CAPABILITY_UNAVAILABLE":
            case "DEVICE_ERROR": case "TIMEOUT": case "IN_PROGRESS": case "UNSUPPORTED_VERSION":
            case "BLOCKED_TARGET": case "SENSITIVE_CONTENT": case "HIGH_IMPACT_CONTENT":
            case "INCOMPLETE_OBSERVATION": case "CONFIGURATION_INVALID":
                return code;
            default: return "other";
        }
    }

    private static String safeMessage(String message) {
        if (message == null) return "none";
        String bounded = message.replace(SYNTHETIC_KEY, "[redacted]").replace('\r', ' ').replace('\n', ' ');
        return bounded.substring(0, Math.min(bounded.length(), 512));
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }

    @Override public void close() throws Exception {
        closed = true;
        if (server != null) server.close();
        Socket current = active;
        if (current != null) current.close();
        synchronized (changed) { changed.notifyAll(); }
        if (worker != null) {
            worker.join(2_000);
            if (worker.isAlive()) throw new IOException("Local model fixture did not terminate");
        }
    }
}
