package dev.agentos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.AgentRunner;
import dev.agentos.core.DisclosureGuard;
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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real configuration publication, production controller wiring and loopback HTTP only. */
@RunWith(AndroidJUnit4.class)
public final class ConfigurationDisclosureInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void controllerRejectsAResponseAfterPublicationBeforeItsCancellationListenerRuns() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             HeldReplyServer server = new HeldReplyServer();
             SavedConfiguration settings = new SavedConfiguration(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            SettingsStore store = new SettingsStore(device.context);
            SettingsStore.SavedSettings same = store.load();
            try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, settings.lease)) {
                // This observer is registered before the actual Controller's observer.
                // The server has already received one valid request before invalidation;
                // the test concerns rejecting its late response, not retracting sent data.
                start(device, controller, settings.model, settings.lease);
                server.awaitRequest();
                assertTrue(controller.getState().running);
                held.start(same);
                held.awaitPublished();
                assertTrue("Controller cancellation must still be behind the first observer", controller.getState().running);
                server.releaseResponse();
                AgentController.State stopped = awaitFinished(controller, server);
                held.assertHeld();
                assertEquals("The production transport guard must stop the original task", "已停止", stopped.status);
                assertFalse("An invalidated response entered the UI", stopped.summary.contains(HeldReplyServer.REPORT));
                assertTrue(stopped.approvalId.isEmpty());
                assertEquals(1, server.requests.get());
                held.finish();
                screenshots.capture("configuration-disclosure-stopped-before-listener");
            } catch (Exception | AssertionError failure) {
                captureFailure(failure);
                throw failure;
            } finally { clear(device, controller); }
        }
    }

    @Test public void productionGuardReadsThePublishedLeaseAndOldRunnerCannotStopAReplacementTask() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             HeldReplyServer server = new HeldReplyServer();
             SavedConfiguration settings = new SavedConfiguration(device.context, server)) {
            AgentController controller = AgentController.get(device.context);
            SettingsStore store = new SettingsStore(device.context);
            SettingsStore.SavedSettings same = store.load();
            RunControl original = new RunControl(SettingsStore.currentProfileId(device.context));
            DisclosureGuard guard = AgentController.disclosureGuardFor(original, settings.lease);
            AtomicReference<TaskResult> result = new AtomicReference<>();
            AtomicInteger listenerCalls = new AtomicInteger();
            AgentRunner oldRunner = new AgentRunner(settings.model, syntheticReadOnlyTools(), new AgentRunner.Listener() {
                @Override public void onStatus(String value) { }
                @Override public void onStep(String value) { }
                @Override public void onFinished(String summary, boolean verified) { fail("Typed result expected"); }
                @Override public void onResult(TaskResult value) { result.set(value); listenerCalls.incrementAndGet(); }
            }, original, guard);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, settings.lease)) {
                long epoch = original.getExecutionEpoch();
                assertTrue(guard.isCurrent(original, epoch));
                RunControl unrelated = new RunControl(original.getProfileId());
                try {
                    assertFalse("Another task must not borrow a saved configuration guard", guard.isCurrent(unrelated, unrelated.getExecutionEpoch()));
                    assertFalse(guard.isCurrent(original, epoch + 1));
                    assertFalse(unrelated.isCancelled());
                } finally { unrelated.cancel(); }
                held.start(same);
                held.awaitPublished();
                assertFalse("The direct guard must not wait for the held cancellation callback",
                        caller.submit(() -> guard.isCurrent(original, epoch)).get(2, TimeUnit.SECONDS));
                assertFalse("The predicate does not itself mutate a task", original.isCancelled());
                held.assertHeld();
                SettingsStore.ConfigurationLease replacement = held.finish();

                // A new production Controller task is waiting in the real HTTP client.
                // Executing the old bound runner may cancel only its own RunControl.
                start(device, controller, settings.model, replacement);
                server.awaitRequest();
                caller.submit(() -> oldRunner.run("Synthetic old request must never be uploaded.", original))
                        .get(2, TimeUnit.SECONDS);
                assertTrue(original.isCancelled());
                assertNotNull(result.get());
                assertEquals(TaskResult.Kind.STOPPED, result.get().kind);
                assertEquals(1, listenerCalls.get());
                assertTrue("Old request cancellation stopped the replacement Controller task", controller.getState().running);
                assertEquals("The stale runner reached HTTP", 1, server.requests.get());
                server.releaseResponse();
                AgentController.State finished = awaitFinished(controller, server);
                assertEquals("执行已结束 · 模型报告待核对", finished.status);
                assertTrue("The new configuration's valid response was lost", finished.summary.contains(HeldReplyServer.REPORT));
                assertEquals(1, server.requests.get());
                screenshots.capture("configuration-disclosure-old-run-isolated-from-replacement");
            } catch (Exception | AssertionError failure) {
                captureFailure(failure);
                throw failure;
            } finally {
                original.cancel();
                caller.shutdownNow();
                clear(device, controller);
            }
        }
    }

    private static ToolHandler syntheticReadOnlyTools() {
        return new ToolHandler() {
            @Override public Set<String> availableTools() { return Collections.singleton("observe"); }
            @Override public JsonObject execute(String name, JsonObject arguments, RunControl control) {
                assertEquals("observe", name);
                JsonObject data = new JsonObject();
                data.addProperty("revision", "synthetic-disclosure-only");
                data.addProperty("packageName", DeviceHarness.FIXTURE);
                data.addProperty("scoped", true);
                data.add("nodes", new JsonArray());
                JsonObject reply = new JsonObject(); reply.addProperty("ok", true); reply.add("data", data);
                return reply;
            }
        };
    }

    private static void start(DeviceHarness device, AgentController controller, ModelSettings model,
                              SettingsStore.ConfigurationLease lease) {
        device.instrumentation.runOnMainSync(() -> controller.start(model,
                "Read the synthetic fixture and report without modifying it.",
                Collections.singleton(DeviceHarness.FIXTURE), ActionConfirmationPolicy.Mode.CONFIRM_ALL, lease));
    }

    private static AgentController.State awaitFinished(AgentController controller, HeldReplyServer server) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            server.assertHealthy();
            AgentController.State state = controller.getState();
            // RunControl cancellation precedes the Controller's final UI publication.
            if (!state.running && !state.summary.isEmpty()) return state;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The released HTTP response did not settle the production task");
    }

    private void captureFailure(Throwable failure) {
        try { screenshots.capture("configuration-disclosure-failure-before-cleanup"); }
        catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
    }

    private static void clear(DeviceHarness device, AgentController controller) {
        device.instrumentation.runOnMainSync(() -> { controller.stop(); controller.clearCompletedTask(); });
        device.instrumentation.waitForIdleSync();
    }

    /** Preserves the pre-test encrypted document and never deletes the default Keystore key. */
    private static final class SavedConfiguration implements AutoCloseable {
        private final SharedPreferences preferences;
        private final Map<String, ?> original;
        final ModelSettings model;
        final SettingsStore.ConfigurationLease lease;

        SavedConfiguration(Context context, HeldReplyServer server) throws Exception {
            String profile = SettingsStore.currentProfileId(context);
            preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            original = preferences.getAll();
            if (!preferences.edit().clear().commit()) throw new IOException("Could not isolate synthetic settings");
            model = new ModelSettings(server.endpoint(), HeldReplyServer.KEY, HeldReplyServer.MODEL);
            try {
                SettingsStore store = new SettingsStore(context);
                SettingsStore.SavedSettings saved = store.load();
                saved.endpoint = model.endpoint; saved.apiKey = model.apiKey; saved.model = model.model;
                saved.executionMode = ActionConfirmationPolicy.Mode.CONFIRM_ALL;
                saved.allowedPackages.add(DeviceHarness.FIXTURE);
                saved.grants.put(DeviceHarness.FIXTURE, SettingsStore.captureGrant(context, DeviceHarness.FIXTURE));
                lease = store.save(saved);
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override public void close() throws IOException {
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Set<?>) {
                    @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                    editor.putStringSet(entry.getKey(), values);
                }
            }
            if (!editor.commit()) throw new IOException("Could not restore pre-test settings");
        }
    }

    /** A final model reply is held after receiving the actual Controller's HTTP request. */
    private static final class HeldReplyServer implements AutoCloseable {
        static final String KEY = "synthetic-disclosure-key", MODEL = "synthetic-disclosure-model";
        static final String REPORT = "SYNTHETIC_DISCLOSURE_RESPONSE";
        final AtomicInteger requests = new AtomicInteger();
        private final ServerSocket server;
        private final Thread worker;
        private final CountDownLatch requestReceived = new CountDownLatch(1), responseReleased = new CountDownLatch(1);
        private volatile Socket active;
        private volatile boolean closed;
        private volatile Throwable failure;

        HeldReplyServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "synthetic-disclosure-http");
            worker.setDaemon(true); worker.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getLocalPort() + "/v1"; }
        void releaseResponse() { responseReleased.countDown(); }
        void awaitRequest() throws Exception {
            boolean received = requestReceived.await(15, TimeUnit.SECONDS);
            assertHealthy(); assertTrue("The real Controller did not send its first request", received);
        }
        void assertHealthy() {
            if (failure != null) throw new AssertionError("Synthetic disclosure HTTP failed: " + failure.getClass().getSimpleName(), failure);
        }

        private void serve() {
            while (!closed) {
                boolean writingResponse = false;
                try (Socket socket = server.accept()) {
                    active = socket; socket.setSoTimeout(5_000);
                    readRequest(socket.getInputStream());
                    requests.incrementAndGet(); requestReceived.countDown();
                    if (!responseReleased.await(20, TimeUnit.SECONDS)) throw new IOException("Synthetic response barrier expired");
                    JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", REPORT);
                    JsonObject choice = new JsonObject(); choice.add("message", message);
                    JsonArray choices = new JsonArray(); choices.add(choice);
                    JsonObject response = new JsonObject(); response.add("choices", choices);
                    byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                    try {
                        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                                + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                        writingResponse = true;
                        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body); socket.getOutputStream().flush();
                    } finally { Arrays.fill(body, (byte) 0); }
                } catch (IOException cancelled) {
                    // The invalidated client may close while this already released reply is written.
                    if (!closed && !writingResponse) failure = cancelled;
                } catch (Throwable problem) {
                    if (!closed) failure = problem;
                } finally { active = null; }
            }
        }

        private void readRequest(InputStream input) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int suffix = 0;
            while (suffix != 0x0d0a0d0a) {
                int value = input.read();
                require(value >= 0, "Incomplete synthetic HTTP header");
                header.write(value); require(header.size() <= 16_384, "Synthetic header exceeded its limit");
                suffix = suffix << 8 | value;
            }
            String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
            require("POST /v1/chat/completions HTTP/1.1".equals(lines[0]), "Unexpected synthetic route");
            int length = -1; boolean authenticated = false;
            for (String line : lines) {
                int colon = line.indexOf(':'); if (colon < 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-length")) length = Integer.parseInt(value);
                if (name.equals("authorization")) authenticated = value.equals("Bearer " + KEY);
            }
            require(authenticated && length > 0 && length <= 4 * 1024 * 1024, "Unexpected synthetic request metadata");
            byte[] bytes = new byte[length];
            try {
                int offset = 0;
                while (offset < length) {
                    int count = input.read(bytes, offset, length - offset);
                    require(count > 0, "Incomplete synthetic body"); offset += count;
                }
                JsonObject request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                require(MODEL.equals(request.get("model").getAsString()), "Wrong synthetic model");
                require(!request.get("stream").getAsBoolean(), "Unexpected streaming request");
                require(!request.getAsJsonArray("tools").isEmpty(), "Production tools missing");
                require(request.getAsJsonArray("messages").toString().contains(DeviceHarness.FIXTURE), "Actual fixture observation missing");
            } finally { Arrays.fill(bytes, (byte) 0); }
        }

        private static void require(boolean condition, String message) throws IOException {
            if (!condition) throw new IOException(message);
        }

        @Override public void close() throws Exception {
            closed = true; releaseResponse(); server.close();
            Socket socket = active; if (socket != null) socket.close();
            worker.interrupt(); worker.join(2_000); assertHealthy();
        }
    }
}
