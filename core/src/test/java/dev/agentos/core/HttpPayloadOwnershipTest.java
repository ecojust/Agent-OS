package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.*;

/** Deterministic transport ownership tests; a finished Future is not a released I/O buffer. */
public final class HttpPayloadOwnershipTest {
    private static final String REPLY = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"synthetic\"}}]}";
    private static final ModelSettings SETTINGS = new ModelSettings("http://127.0.0.1:9/v1", "synthetic-key", "test-model");
    private enum Mode { NORMAL, WRITE_BLOCK, DISCONNECT_BLOCK, WRITE_FAIL, READ_FAIL, INVALID_JSON }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void holdDespiteInterrupt() {
            entered.countDown(); boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (release.getCount() != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new AssertionError("synthetic I/O barrier expired");
                    try { if (release.await(remaining, TimeUnit.NANOSECONDS)) return; }
                    catch (InterruptedException stop) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        void await() throws InterruptedException { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
    }

    private static final class Network extends ThreadPoolExecutor {
        final Gate beforeRun;
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicReference<Runnable> first = new AtomicReference<>();
        final CountDownLatch secondQueued = new CountDownLatch(1), firstExited = new CountDownLatch(1);
        volatile Gate submitReturn;
        Network(Gate beforeRun) {
            super(1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread worker = new Thread(runnable, "payload-network-test"); worker.setDaemon(true); return worker;
            }, new AbortPolicy());
            this.beforeRun = beforeRun;
        }
        @Override public void execute(Runnable work) {
            int index = submitted.incrementAndGet(); first.compareAndSet(null, work);
            super.execute(work);
            if (index == 2) secondQueued.countDown();
            Gate gate = submitReturn;
            if (gate != null) gate.holdDespiteInterrupt();
        }
        @Override protected void beforeExecute(Thread worker, Runnable work) {
            if (beforeRun != null && work == first.get()) beforeRun.holdDespiteInterrupt();
        }
        @Override protected void afterExecute(Runnable work, Throwable failure) {
            if (work == first.get()) firstExited.countDown();
        }
    }

    private static final class Connection extends HttpURLConnection {
        final Mode mode;
        final Gate gate;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final AtomicReference<byte[]> buffer = new AtomicReference<>();
        Connection(URL url, Mode mode, Gate gate) { super(url); this.mode = mode; this.gate = gate; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { sent.write(value); }
                @Override public void write(byte[] source, int offset, int length) throws IOException {
                    buffer.compareAndSet(null, source);
                    if (mode == Mode.WRITE_FAIL) throw new IOException("synthetic write failure");
                    sent.write(source, offset, length);
                    if (mode == Mode.WRITE_BLOCK) gate.holdDespiteInterrupt();
                }
            };
        }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return -1; }
        @Override public InputStream getInputStream() throws IOException {
            if (mode == Mode.READ_FAIL) throw new IOException("synthetic read failure");
            return new ByteArrayInputStream((mode == Mode.INVALID_JSON ? "broken" : REPLY).getBytes(StandardCharsets.UTF_8));
        }
        @Override public void disconnect() {
            if (mode == Mode.DISCONNECT_BLOCK && Thread.currentThread().getName().equals("payload-network-test")) {
                gate.holdDespiteInterrupt();
            }
        }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
    }

    private static final class Fixture implements AutoCloseable {
        final ImageWorkset workset;
        final Network network;
        final ExecutorService closer = Executors.newSingleThreadExecutor();
        final ExecutorService callers = Executors.newFixedThreadPool(3);
        final List<Connection> connections = new CopyOnWriteArrayList<>();
        final List<Gate> gates = new CopyOnWriteArrayList<>();
        Fixture() { this(null); }
        Fixture(Gate beforeRun) { this(beforeRun, 4096); }
        Fixture(Gate beforeRun, long capacity) {
            workset = new ImageWorkset(capacity);
            network = new Network(beforeRun); if (beforeRun != null) gates.add(beforeRun);
        }
        HttpModelClient client(Mode mode, Gate gate, long timeout) {
            if (gate != null) gates.add(gate);
            return new HttpModelClient(timeout, url -> {
                Connection connection = new Connection(url, mode, gate); connections.add(connection); return connection;
            }, network, closer, workset);
        }
        Future<JsonObject> start(HttpModelClient client, RunControl run) {
            return callers.submit(() -> request(client, run, messages()));
        }
        void firstExited() throws InterruptedException { assertTrue(network.firstExited.await(2, TimeUnit.SECONDS)); }
        @Override public void close() throws InterruptedException {
            for (Gate gate : gates) gate.release.countDown();
            callers.shutdownNow(); network.shutdownNow(); closer.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(network.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(closer.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals("every owned wire buffer must be released at worker exit", 0, workset.reservedBytes());
        }
    }

    private static JsonArray messages() {
        JsonObject message = new JsonObject(); message.addProperty("role", "user");
        message.addProperty("content", "synthetic picture payload 中文😀");
        JsonArray messages = new JsonArray(); messages.add(message); return messages;
    }
    private static JsonObject request(HttpModelClient client, RunControl run, JsonArray messages) throws Exception {
        return client.request(SETTINGS, messages, new JsonArray(), ModelRequestContext.syntheticRequest(run, run.getExecutionEpoch()));
    }
    private static Throwable failure(Future<?> task) {
        return assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS)).getCause();
    }

    @Test(timeout = 12000) public void cancelAndTimeoutDoNotWipeOrReleaseStillExecutingOutput() throws Exception {
        for (boolean cancel : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture()) {
                Gate io = new Gate(); RunControl run = new RunControl();
                Future<JsonObject> request = fixture.start(fixture.client(Mode.WRITE_BLOCK, io, cancel ? 4000 : 500), run);
                io.await(); Connection connection = fixture.connections.get(0);
                byte[] actual = connection.buffer.get(), before = actual.clone();
                assertEquals(actual.length, fixture.workset.reservedBytes());
                if (cancel) run.cancel();
                Throwable failure = failure(request);
                if (cancel) assertTrue(failure instanceof TaskControlException);
                else { assertTrue(failure instanceof ModelException); assertEquals("TIMEOUT", ((ModelException) failure).code); }
                assertEquals("caller exit and Future.cancel cannot settle active I/O", actual.length, fixture.workset.reservedBytes());
                assertArrayEquals("the blocked writer must retain the unchanged array", before, actual);
                try (ImageWorkset.Reservation remainder = fixture.workset.reserve(fixture.workset.limitBytes() - actual.length)) {
                    assertThrows(IllegalStateException.class, () -> fixture.workset.reserve(1));
                }
                io.release.countDown(); fixture.firstExited();
                assertArrayEquals(new byte[actual.length], actual); assertEquals(0, fixture.workset.reservedBytes());
                assertEquals(1, fixture.connections.size());
            }
        }
    }

    @Test(timeout = 8000) public void cancellingQueuedWorkReleasesOnlyThatPayloadWithoutOpeningTransport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Gate io = new Gate(); RunControl first = new RunControl(), queued = new RunControl();
            Future<JsonObject> running = fixture.start(fixture.client(Mode.WRITE_BLOCK, io, 4000), first);
            io.await(); long runningBytes = fixture.workset.reservedBytes();
            Future<JsonObject> pending = fixture.start(fixture.client(Mode.NORMAL, null, 4000), queued);
            assertTrue(fixture.network.secondQueued.await(2, TimeUnit.SECONDS));
            assertEquals(runningBytes * 2, fixture.workset.reservedBytes());
            queued.cancel(); assertTrue(failure(pending) instanceof TaskControlException);
            assertEquals(runningBytes, fixture.workset.reservedBytes());
            assertTrue(fixture.network.getQueue().isEmpty()); assertEquals(1, fixture.connections.size());
            io.release.countDown(); assertTrue(running.get(2, TimeUnit.SECONDS).has("choices")); fixture.firstExited();
        }
    }

    @Test(timeout = 8000) public void typedPngIsReleasedBeforeNetworkAndCancelRetainsOnlyInFlightWireBytes() throws Exception {
        try (Fixture fixture = new Fixture(null, 30_000)) {
            Gate io = new Gate(); RunControl run = new RunControl();
            PngImage image = PngImage.fromBytes(PngImageTest.png(), fixture.workset);
            ModelRequestContext context = ModelRequestContext.syntheticRequest(run, run.getExecutionEpoch());
            HttpModelClient client = fixture.client(Mode.WRITE_BLOCK, io, 4000);
            Future<JsonObject> pending = fixture.callers.submit(() -> client.request(SETTINGS, messages(), new JsonArray(), context, image));
            io.await(); Connection connection = fixture.connections.get(0);
            byte[] bytes = connection.buffer.get(), unchanged = bytes.clone();
            assertTrue("PNG ownership ends before queue/network ownership begins", image.isClosed());
            assertEquals(bytes.length, fixture.workset.reservedBytes());
            run.cancel(); assertTrue(failure(pending) instanceof TaskControlException);
            assertEquals(bytes.length, fixture.workset.reservedBytes());
            assertArrayEquals(unchanged, bytes);
            io.release.countDown(); fixture.firstExited();
            assertArrayEquals(new byte[bytes.length], bytes); assertEquals(0, fixture.workset.reservedBytes());
            assertEquals(1, fixture.connections.size());
        }
    }

    @Test(timeout = 8000) public void revokedQueuedImageOpensNoConnectionAndSettlesOnlyAtItsOwner() throws Exception {
        Gate dequeued = new Gate();
        try (Fixture fixture = new Fixture(dequeued, 30_000)) {
            RunControl run = new RunControl(); AtomicBoolean permitted = new AtomicBoolean(true);
            PngImage image = PngImage.fromBytes(PngImageTest.png(), fixture.workset);
            ModelRequestContext context = new ModelRequestContext(run, run.getExecutionEpoch(),
                    (owner, epoch) -> true, (owner, epoch) -> permitted.get());
            HttpModelClient client = fixture.client(Mode.NORMAL, null, 4000);
            try {
                Future<JsonObject> pending = fixture.callers.submit(() -> client.request(SETTINGS,
                        messages(), new JsonArray(), context, image));
                dequeued.await(); assertTrue(image.isClosed()); assertTrue(fixture.workset.reservedBytes() > 0);
                permitted.set(false);
                // Invalidating a condition is not a cleanup owner or a polling thread.
                assertEquals(0, fixture.connections.size()); assertFalse(pending.isDone());
                dequeued.release.countDown();
                assertTrue(failure(pending) instanceof TaskControlException); fixture.firstExited();
                assertEquals(0, fixture.connections.size()); assertEquals(0, fixture.workset.reservedBytes());
            } finally { run.cancel(); image.close(); }
        }
    }

    @Test(timeout = 8000) public void imageGuardCancellationCannotEraseAnInFlightWireBuffer() throws Exception {
        try (Fixture fixture = new Fixture(null, 30_000)) {
            Gate io = new Gate(); RunControl run = new RunControl(); AtomicBoolean permitted = new AtomicBoolean(true);
            PngImage image = PngImage.fromBytes(PngImageTest.png(), fixture.workset);
            ModelRequestContext context = new ModelRequestContext(run, run.getExecutionEpoch(),
                    (owner, epoch) -> true, (owner, epoch) -> permitted.get());
            HttpModelClient client = fixture.client(Mode.WRITE_BLOCK, io, 4000);
            try {
                Future<JsonObject> pending = fixture.callers.submit(() -> client.request(SETTINGS,
                        messages(), new JsonArray(), context, image));
                io.await(); byte[] bytes = fixture.connections.get(0).buffer.get(), unchanged = bytes.clone();
                assertTrue(image.isClosed()); assertEquals(bytes.length, fixture.workset.reservedBytes());
                permitted.set(false);
                // Explicitly exercise an existing context checkpoint. A bare flag
                // change is not claimed to interrupt arbitrary blocked platform I/O.
                assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class, context::check).getCode());
                assertTrue(failure(pending) instanceof TaskControlException);
                assertEquals(bytes.length, fixture.workset.reservedBytes()); assertArrayEquals(unchanged, bytes);
                io.release.countDown(); fixture.firstExited();
                assertArrayEquals(new byte[bytes.length], bytes); assertEquals(0, fixture.workset.reservedBytes());
                assertEquals(1, fixture.connections.size());
            } finally { run.cancel(); image.close(); }
        }
    }

    @Test(timeout = 8000) public void cancellationAfterDequeuingBeforeRunStillHasOneCleanupOwner() throws Exception {
        Gate dequeued = new Gate();
        try (Fixture fixture = new Fixture(dequeued)) {
            RunControl run = new RunControl();
            Future<JsonObject> pending = fixture.start(fixture.client(Mode.NORMAL, null, 4000), run);
            dequeued.await(); assertTrue(fixture.network.getQueue().isEmpty());
            assertTrue(fixture.workset.reservedBytes() > 0);
            run.cancel(); assertTrue(failure(pending) instanceof TaskControlException);
            assertEquals(0, fixture.workset.reservedBytes()); assertEquals(0, fixture.connections.size());
            dequeued.release.countDown(); fixture.firstExited();
            assertEquals(0, fixture.connections.size());
        }
    }

    @Test(timeout = 8000) public void synchronousCancelHookLeavesPendingCleanupForTheCallerWorker() throws Exception {
        Gate dequeued = new Gate();
        try (Fixture fixture = new Fixture(dequeued)) {
            Gate callerStillSubmitting = new Gate(); fixture.gates.add(callerStillSubmitting);
            fixture.network.submitReturn = callerStillSubmitting;
            RunControl run = new RunControl();
            Future<JsonObject> task = fixture.start(fixture.client(Mode.NORMAL, null, 4000), run);
            dequeued.await(); callerStillSubmitting.await();
            long pendingBytes = fixture.workset.reservedBytes(); assertTrue(pendingBytes > 0);
            run.cancel();
            assertEquals("the synchronous hook must not close/wipe a pending payload", pendingBytes,
                    fixture.workset.reservedBytes());
            assertFalse(task.isDone()); assertEquals(0, fixture.connections.size());
            callerStillSubmitting.release.countDown(); assertTrue(failure(task) instanceof TaskControlException);
            assertEquals(0, fixture.workset.reservedBytes());
            dequeued.release.countDown(); fixture.firstExited(); assertEquals(0, fixture.connections.size());
        }
    }

    @Test(timeout = 8000) public void rejectedSubmissionDoesNotReleaseRunningOrQueuedRequests() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Gate io = new Gate(); RunControl first = new RunControl(), queued = new RunControl();
            Future<JsonObject> running = fixture.start(fixture.client(Mode.WRITE_BLOCK, io, 4000), first);
            io.await(); long runningBytes = fixture.workset.reservedBytes();
            Future<JsonObject> pending = fixture.start(fixture.client(Mode.NORMAL, null, 4000), queued);
            assertTrue(fixture.network.secondQueued.await(2, TimeUnit.SECONDS));
            ModelException busy = assertThrows(ModelException.class,
                    () -> request(fixture.client(Mode.NORMAL, null, 4000), new RunControl(), messages()));
            assertEquals("NETWORK_BUSY", busy.code);
            assertEquals(runningBytes * 2, fixture.workset.reservedBytes()); assertEquals(1, fixture.connections.size());
            queued.cancel(); assertTrue(failure(pending) instanceof TaskControlException);
            assertEquals(runningBytes, fixture.workset.reservedBytes());
            io.release.countDown(); running.get(2, TimeUnit.SECONDS); fixture.firstExited();
        }
    }

    @Test(timeout = 12000) public void normalWriteReadParseAndOpenFailureAllReleaseAtWorkerExit() throws Exception {
        for (Mode mode : new Mode[]{Mode.NORMAL, Mode.WRITE_FAIL, Mode.READ_FAIL, Mode.INVALID_JSON}) {
            try (Fixture fixture = new Fixture()) {
                Future<JsonObject> task = fixture.start(fixture.client(mode, null, 4000), new RunControl());
                if (mode == Mode.NORMAL) assertTrue(task.get(2, TimeUnit.SECONDS).has("choices"));
                else assertTrue(failure(task) instanceof ModelException);
                fixture.firstExited(); assertEquals(0, fixture.workset.reservedBytes());
                byte[] actual = fixture.connections.get(0).buffer.get(); assertNotNull(actual);
                assertArrayEquals(new byte[actual.length], actual);
            }
        }
        try (Fixture fixture = new Fixture()) {
            HttpModelClient failedOpen = new HttpModelClient(4000, url -> { throw new IOException("synthetic open failure"); },
                    fixture.network, fixture.closer, fixture.workset);
            assertTrue(failure(fixture.start(failedOpen, new RunControl())) instanceof ModelException);
            fixture.firstExited(); assertEquals(0, fixture.workset.reservedBytes());
        }
    }

    @Test(timeout = 8000) public void parsedResponseDoesNotReleaseBufferWhileWorkerIsStillDisconnecting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Gate disconnect = new Gate(); RunControl run = new RunControl();
            Future<JsonObject> task = fixture.start(fixture.client(Mode.DISCONNECT_BLOCK, disconnect, 4000), run);
            disconnect.await(); byte[] actual = fixture.connections.get(0).buffer.get(); byte[] before = actual.clone();
            run.cancel(); assertTrue(failure(task) instanceof TaskControlException);
            assertEquals(actual.length, fixture.workset.reservedBytes()); assertArrayEquals(before, actual);
            disconnect.release.countDown(); fixture.firstExited(); assertEquals(0, fixture.workset.reservedBytes());
            assertArrayEquals(new byte[actual.length], actual);
        }
    }

    @Test(timeout = 8000) public void requestSnapshotIsIndependentOfCallerMutationDuringEncoding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Gate encoding = new Gate(); fixture.gates.add(encoding);
            JsonArray input = messages(); AtomicInteger writes = new AtomicInteger();
            input.add(OwnedRequestPayloadTest.number(() -> {
                if (writes.incrementAndGet() == 1) encoding.holdDespiteInterrupt(); return "1";
            }));
            HttpModelClient client = fixture.client(Mode.NORMAL, null, 4000);
            Future<JsonObject> task = fixture.callers.submit(() -> request(client, new RunControl(), input));
            encoding.await(); input.get(0).getAsJsonObject().addProperty("content", "mutated-after-snapshot");
            input.add("later-message"); encoding.release.countDown();
            assertTrue(task.get(2, TimeUnit.SECONDS).has("choices")); fixture.firstExited();
            JsonObject sent = JsonParser.parseString(fixture.connections.get(0).sent.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(2, sent.getAsJsonArray("messages").size());
            assertEquals("synthetic picture payload 中文😀", sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
            assertFalse(sent.toString().contains("synthetic-key")); assertEquals(2, writes.get());
        }
    }
}
