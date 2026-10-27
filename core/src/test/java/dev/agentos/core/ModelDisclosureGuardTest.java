package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real loopback transport with deterministic barriers around otherwise blocking I/O. */
public class ModelDisclosureGuardTest {
    private static final String REPLY = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"synthetic report\"}}]}";

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void hold() throws IOException {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test barrier timed out"); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("test barrier interrupted"); }
        }
        void await() throws InterruptedException { assertTrue("transport must reach the requested boundary", entered.await(2, TimeUnit.SECONDS)); }
    }

    private enum Boundary { OUTPUT_RETURN, READ_CHUNK, READ_EOF, DISCONNECT }

    private static final class GatedConnection extends HttpURLConnection {
        final HttpURLConnection delegate;
        final Boundary boundary;
        final Gate gate;
        final AtomicBoolean gated = new AtomicBoolean();
        final AtomicInteger written = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        GatedConnection(URL url, Boundary boundary, Gate gate) throws IOException {
            super(url); this.delegate = (HttpURLConnection) url.openConnection(); this.boundary = boundary; this.gate = gate;
        }
        void at(Boundary point) throws IOException { if (boundary == point && gated.compareAndSet(false, true)) gate.hold(); }
        @Override public OutputStream getOutputStream() throws IOException {
            OutputStream stream = delegate.getOutputStream();
            at(Boundary.OUTPUT_RETURN);
            return new FilterOutputStream(stream) {
                @Override public void write(byte[] bytes, int start, int length) throws IOException {
                    written.addAndGet(length); out.write(bytes, start, length);
                }
                @Override public void write(int value) throws IOException { written.incrementAndGet(); out.write(value); }
            };
        }
        @Override public InputStream getInputStream() throws IOException {
            return new FilterInputStream(delegate.getInputStream()) {
                @Override public int read(byte[] bytes, int start, int length) throws IOException {
                    int read = in.read(bytes, start, length);
                    at(read == -1 ? Boundary.READ_EOF : Boundary.READ_CHUNK);
                    return read;
                }
            };
        }
        @Override public int getResponseCode() throws IOException { return delegate.getResponseCode(); }
        @Override public long getContentLengthLong() { return delegate.getContentLengthLong(); }
        @Override public void setInstanceFollowRedirects(boolean follow) { delegate.setInstanceFollowRedirects(follow); }
        @Override public void setConnectTimeout(int timeout) { delegate.setConnectTimeout(timeout); }
        @Override public void setReadTimeout(int timeout) { delegate.setReadTimeout(timeout); }
        @Override public void setRequestMethod(String method) throws ProtocolException { delegate.setRequestMethod(method); }
        @Override public void setDoOutput(boolean output) { delegate.setDoOutput(output); }
        @Override public void setRequestProperty(String name, String value) { delegate.setRequestProperty(name, value); }
        @Override public void setFixedLengthStreamingMode(int length) { delegate.setFixedLengthStreamingMode(length); }
        @Override public void connect() throws IOException { delegate.connect(); }
        @Override public boolean usingProxy() { return delegate.usingProxy(); }
        @Override public void disconnect() {
            try { at(Boundary.DISCONNECT); }
            catch (IOException ignored) { }
            finally { delegate.disconnect(); closed.countDown(); }
        }
    }

    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final AtomicInteger requests = new AtomicInteger(), bodyBytes = new AtomicInteger();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); http.setExecutor(workers);
            http.createContext("/v1/chat/completions", exchange -> {
                requests.incrementAndGet();
                try (ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int read;
                    while ((read = exchange.getRequestBody().read(buffer)) != -1) { bodyBytes.addAndGet(read); body.write(buffer, 0, read); }
                    bodies.add(body.toString(StandardCharsets.UTF_8));
                    byte[] reply = REPLY.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply);
                } catch (IOException cancelledRequest) {
                    // Revocation can leave received headers or a partial body; neither
                    // represents a completed model response and no request is retried.
                } finally { exchange.close(); }
            });
            http.start();
        }
        ModelSettings settings() { return new ModelSettings("http://127.0.0.1:" + http.getAddress().getPort() + "/v1", "synthetic-key", "synthetic-model"); }
        @Override public void close() { http.stop(0); workers.shutdownNow(); }
    }

    private static JsonArray messages() {
        JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.addProperty("content", "synthetic-device-marker");
        JsonArray result = new JsonArray(); result.add(message); return result;
    }
    private static ModelRequestContext context(RunControl control, AtomicBoolean current) {
        return new ModelRequestContext(control, control.getExecutionEpoch(), (original, epoch) -> original == control && current.get());
    }
    private static void assertStopped(Future<?> result) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertTrue("revocation must remain a control failure: " + failure.getCause(), failure.getCause() instanceof TaskControlException);
        assertEquals("TASK_STOPPED", ((TaskControlException) failure.getCause()).getCode());
    }
    private static final class Events implements AgentRunner.Listener {
        final AtomicReference<TaskResult> result = new AtomicReference<>();
        public void onStatus(String status) { }
        public void onStep(String step) { }
        public void onFinished(String text, boolean verified) { }
        public void onResult(TaskResult completed) { assertTrue(result.compareAndSet(null, completed)); }
    }
    private static ToolHandler observed(AtomicInteger reads) {
        return new ToolHandler() {
            public Set<String> availableTools() { return Set.of("observe"); }
            public JsonObject execute(String name, JsonObject arguments, RunControl control) {
                assertEquals("observe", name); reads.incrementAndGet();
                JsonObject data = new JsonObject(); data.addProperty("revision", "synthetic-r1"); data.addProperty("text", "synthetic-device-marker");
                JsonObject result = new JsonObject(); result.addProperty("ok", true); result.add("data", data); return result;
            }
        };
    }

    @Test public void publicRunnerRequiresOwnerAndGuardAndRejectsReuseWithoutTouchingAnotherTask() throws Exception {
        Constructor<?>[] publicConstructors = AgentRunner.class.getConstructors();
        assertEquals(1, publicConstructors.length);
        assertArrayEquals(new Class<?>[]{ModelSettings.class, ToolHandler.class, AgentRunner.Listener.class,
                RunControl.class, DisclosureGuard.class}, publicConstructors[0].getParameterTypes());
        RunControl original = new RunControl(), replacement = new RunControl();
        AtomicInteger guardCalls = new AtomicInteger(), reads = new AtomicInteger(); Events events = new Events();
        try (Server server = new Server()) {
            AgentRunner runner = new AgentRunner(server.settings(), observed(reads), events, original,
                    (owner, epoch) -> { guardCalls.incrementAndGet(); return false; });
            assertThrows(IllegalArgumentException.class, () -> runner.run("synthetic task", replacement));
            assertEquals(0, guardCalls.get()); assertEquals(0, reads.get());
            assertFalse(original.isCancelled()); assertFalse(replacement.isCancelled());
            runner.run("synthetic task", original);
            assertEquals(TaskResult.Kind.STOPPED, events.result.get().kind);
            assertEquals(0, server.requests.get()); assertTrue(original.isCancelled());
            assertThrows(IllegalStateException.class, () -> runner.run("synthetic task", original));
            assertFalse(replacement.isCancelled());
        }
    }

    @Test public void invalidatedObservationCannotBecomeAModelMessageOrOpenAConnection() throws Exception {
        RunControl control = new RunControl(); AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger(), opens = new AtomicInteger(); Events events = new Events();
        ToolHandler observed = observed(reads);
        ToolHandler invalidating = new ToolHandler() {
            public Set<String> availableTools() { return observed.availableTools(); }
            public JsonObject execute(String name, JsonObject arguments, RunControl active) throws Exception {
                JsonObject result = observed.execute(name, arguments, active); current.set(false); return result;
            }
        };
        try (Server server = new Server()) {
            HttpModelClient client = new HttpModelClient(3000, url -> { opens.incrementAndGet(); return (HttpURLConnection) url.openConnection(); });
            new AgentRunner(server.settings(), invalidating, events, client, control, (owner, epoch) -> current.get()).run("synthetic task", control);
            assertEquals(1, reads.get()); assertEquals(0, opens.get()); assertEquals(0, server.requests.get());
            assertEquals(TaskResult.Kind.STOPPED, events.result.get().kind); assertTrue(control.isCancelled());
        }
    }

    @Test public void invalidationDuringSerializationIsRejectedBeforeNetworkSubmission() throws Exception {
        RunControl control = new RunControl(); AtomicBoolean current = new AtomicBoolean(true); AtomicInteger opens = new AtomicInteger();
        JsonArray outgoing = messages();
        // Gson preserves this Number through deepCopy and calls toString only
        // while serializing. This invalidates the lease in the serialization gap.
        outgoing.add(new Number() {
            public int intValue() { return 1; } public long longValue() { return 1; }
            public float floatValue() { return 1; } public double doubleValue() { return 1; }
            @Override public String toString() { current.set(false); return "1"; }
        });
        try (Server server = new Server()) {
            HttpModelClient client = new HttpModelClient(3000, url -> { opens.incrementAndGet(); return (HttpURLConnection) url.openConnection(); });
            TaskControlException stopped = assertThrows(TaskControlException.class,
                    () -> client.request(server.settings(), outgoing, new JsonArray(), context(control, current)));
            assertEquals("TASK_STOPPED", stopped.getCode()); assertTrue(control.isCancelled());
            assertEquals(0, opens.get()); assertEquals(0, server.requests.get());
        }
    }

    @Test(timeout = 6000) public void invalidationWhileAcquiringOutputStreamPreventsEveryBodyWrite() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor(); Gate gate = new Gate();
        RunControl original = new RunControl(), replacement = new RunControl(); AtomicBoolean current = new AtomicBoolean(true);
        AtomicReference<GatedConnection> connection = new AtomicReference<>();
        try (Server server = new Server()) {
            HttpModelClient client = new HttpModelClient(4000, url -> {
                GatedConnection wrapped = new GatedConnection(url, Boundary.OUTPUT_RETURN, gate); connection.set(wrapped); return wrapped;
            });
            Future<?> request = caller.submit(() -> client.request(server.settings(), messages(), new JsonArray(), context(original, current)));
            gate.await(); current.set(false); gate.release.countDown(); assertStopped(request);
            assertTrue(connection.get().closed.await(2, TimeUnit.SECONDS));
            assertEquals(0, connection.get().written.get()); assertEquals(0, server.bodyBytes.get());
            assertTrue(original.isCancelled()); assertFalse(replacement.isCancelled());
            JsonObject fresh = new HttpModelClient().request(server.settings(), messages(), new JsonArray(), context(replacement, new AtomicBoolean(true)));
            assertTrue(fresh.has("choices")); assertFalse(replacement.isCancelled());
            assertEquals(1, server.bodies.size()); // The old request may have sent headers only.
        } finally { gate.release.countDown(); original.cancel(); replacement.cancel(); caller.shutdownNow(); }
    }

    @Test(timeout = 10000) public void invalidationAfterReadOrEofCannotPublishAReport() throws Exception {
        for (Boundary boundary : new Boundary[]{Boundary.READ_CHUNK, Boundary.READ_EOF}) {
            ExecutorService caller = Executors.newSingleThreadExecutor(); Gate gate = new Gate();
            RunControl control = new RunControl(); AtomicBoolean current = new AtomicBoolean(true);
            AtomicReference<GatedConnection> connection = new AtomicReference<>();
            AtomicInteger reads = new AtomicInteger(); Events events = new Events();
            try (Server server = new Server()) {
                HttpModelClient client = new HttpModelClient(4000, url -> {
                    GatedConnection wrapped = new GatedConnection(url, boundary, gate); connection.set(wrapped); return wrapped;
                });
                AgentRunner runner = new AgentRunner(server.settings(), observed(reads), events, client, control, (owner, epoch) -> current.get());
                Future<?> task = caller.submit(() -> runner.run("synthetic task", control));
                gate.await(); current.set(false); gate.release.countDown(); task.get(2, TimeUnit.SECONDS);
                assertTrue(connection.get().closed.await(2, TimeUnit.SECONDS));
                assertEquals(TaskResult.Kind.STOPPED, events.result.get().kind);
                assertEquals("TASK_STOPPED", events.result.get().code);
                assertEquals(1, reads.get()); assertEquals(1, server.requests.get()); assertEquals(1, server.bodies.size());
                assertEquals(0, control.getDispatchAttempts()); assertTrue(control.isCancelled());
            } finally { gate.release.countDown(); control.cancel(); caller.shutdownNow(); }
        }
    }

    @Test(timeout = 6000) public void invalidationAfterParsingBeforeCallerReceivesResultStillStops() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor(); Gate gate = new Gate();
        RunControl control = new RunControl(); AtomicBoolean current = new AtomicBoolean(true);
        try (Server server = new Server()) {
            HttpModelClient client = new HttpModelClient(4000, url -> new GatedConnection(url, Boundary.DISCONNECT, gate));
            Future<?> request = caller.submit(() -> client.request(server.settings(), messages(), new JsonArray(), context(control, current)));
            gate.await(); current.set(false); gate.release.countDown(); assertStopped(request);
            assertTrue(control.isCancelled()); assertEquals(1, server.requests.get()); assertEquals(1, server.bodies.size());
        } finally { gate.release.countDown(); control.cancel(); caller.shutdownNow(); }
    }

    @Test(timeout = 6000) public void queuedRequestRechecksItsLeaseOnWorkerWithoutCancellingAnotherTask() throws Exception {
        ExecutorService firstCaller = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> queuedCallerThread = new AtomicReference<>();
        ExecutorService queuedCaller = Executors.newSingleThreadExecutor(runnable -> {
            Thread worker = new Thread(runnable, "synthetic-queued-model-caller"); queuedCallerThread.set(worker); return worker;
        });
        Gate gate = new Gate();
        RunControl first = new RunControl(), queued = new RunControl(); AtomicBoolean current = new AtomicBoolean(true);
        CountDownLatch submitted = new CountDownLatch(1); AtomicInteger queuedOpens = new AtomicInteger();
        try (Server server = new Server()) {
            HttpModelClient firstClient = new HttpModelClient(4000, url -> new GatedConnection(url, Boundary.OUTPUT_RETURN, gate));
            Future<?> firstRequest = firstCaller.submit(() -> firstClient.request(server.settings(), messages(), new JsonArray(), context(first, new AtomicBoolean(true))));
            gate.await();
            HttpModelClient secondClient = new HttpModelClient(4000, url -> { queuedOpens.incrementAndGet(); return (HttpURLConnection) url.openConnection(); });
            ModelRequestContext queuedContext = new ModelRequestContext(queued, queued.getExecutionEpoch(), (owner, epoch) -> {
                if (!Thread.currentThread().getName().equals("agent-model-network")) submitted.countDown();
                return current.get();
            });
            Future<?> secondRequest = queuedCaller.submit(() -> secondClient.request(server.settings(), messages(), new JsonArray(), queuedContext));
            assertTrue(submitted.await(1, TimeUnit.SECONDS));
            // Wait until the caller is actually awaiting its queued future, rather
            // than invalidating during request construction on the calling thread.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!secondRequest.isDone() && !waitingInFutureGet(queuedCallerThread.get()) && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            assertTrue("second caller must be waiting for its queued network worker", waitingInFutureGet(queuedCallerThread.get()));
            current.set(false); gate.release.countDown();
            firstRequest.get(2, TimeUnit.SECONDS); assertStopped(secondRequest);
            assertEquals(0, queuedOpens.get()); assertEquals(1, server.requests.get());
            assertTrue(queued.isCancelled()); assertFalse(first.isCancelled());
        } finally { gate.release.countDown(); first.cancel(); queued.cancel(); firstCaller.shutdownNow(); queuedCaller.shutdownNow(); }
    }

    private static boolean waitingInFutureGet(Thread caller) {
        if (caller == null || caller.getState() != Thread.State.TIMED_WAITING) return false;
        boolean request = false, future = false;
        for (StackTraceElement frame : caller.getStackTrace()) {
            if (frame.getClassName().equals(HttpModelClient.class.getName()) && frame.getMethodName().equals("request")) request = true;
            if (frame.getClassName().equals("java.util.concurrent.FutureTask") && frame.getMethodName().equals("get")) future = true;
        }
        return request && future;
    }

    @Test(timeout = 5000) public void aLateOldEpochGuardCannotCancelTheResumedTask() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor(); Gate gate = new Gate(); RunControl control = new RunControl();
        ModelRequestContext old = new ModelRequestContext(control, control.getExecutionEpoch(), (owner, epoch) -> {
            try { gate.hold(); } catch (IOException failed) { throw new IllegalStateException(failed); }
            return false;
        });
        try {
            Future<?> checking = caller.submit(() -> { old.check(); return null; });
            gate.await(); control.pause(); control.resume(); gate.release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> checking.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TaskControlException);
            assertEquals("TASK_PAUSED", ((TaskControlException) failure.getCause()).getCode());
            assertFalse(control.isCancelled()); assertFalse(control.isPaused());
            new ModelRequestContext(control, control.getExecutionEpoch(), (owner, epoch) -> true).check();
        } finally { gate.release.countDown(); control.cancel(); caller.shutdownNow(); }
    }

    @Test public void throwingGuardFailsClosedWithoutOpeningTransport() throws Exception {
        RunControl control = new RunControl(); AtomicInteger opens = new AtomicInteger();
        try (Server server = new Server()) {
            HttpModelClient client = new HttpModelClient(3000, url -> { opens.incrementAndGet(); return (HttpURLConnection) url.openConnection(); });
            TaskControlException stopped = assertThrows(TaskControlException.class, () -> client.request(server.settings(), messages(), new JsonArray(),
                    new ModelRequestContext(control, control.getExecutionEpoch(), (owner, epoch) -> { throw new IllegalStateException("synthetic failure"); })));
            assertEquals("TASK_STOPPED", stopped.getCode()); assertTrue(control.isCancelled());
            assertEquals(0, opens.get()); assertEquals(0, server.requests.get());
        }
    }

    @Test public void protocolProbesUseOnlyTheirOwnSyntheticInputs() throws Exception {
        try (Server server = new Server()) {
            new ModelProbe().test(server.settings(), new RunControl());
            new VisionModelProbe().test(server.settings(), new RunControl());
            assertEquals(2, server.bodies.size());
            for (String body : server.bodies) {
                assertFalse(body.contains("synthetic-device-marker"));
                assertFalse(body.contains("expectedRevision"));
                assertFalse(body.contains("launch_app"));
            }
            assertFalse(server.bodies.get(0).contains("data:image/png;base64,"));
            assertTrue(server.bodies.get(0).contains("agent_os_probe"));
            assertTrue(server.bodies.get(1).contains("agent_os_vision_probe"));
            assertTrue(server.bodies.get(1).contains("data:image/png;base64,"));
        }
    }
}
