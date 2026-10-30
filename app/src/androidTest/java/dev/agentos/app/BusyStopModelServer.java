package dev.agentos.app;

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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded real loopback HTTP. MODEL holds an actual parsed request before replying. */
final class BusyStopModelServer implements AutoCloseable {
    final boolean hold;
    final AtomicInteger requests = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), written = new CountDownLatch(1);
    private final LocalControllerModelServer planner;
    private final ServerSocket listener;
    private final Thread worker;
    private volatile Socket socket;
    private volatile Throwable failure;
    private volatile boolean closed, stopped;
    volatile long enteredNanoTime = -1, releaseNanoTime = -1, writeAttemptNanoTime = -1, writeEndNanoTime = -1;
    volatile boolean peerClosed, writeSucceeded, heldExpired;

    BusyStopModelServer(boolean hold) throws Exception {
        this.hold = hold;
        planner = LocalControllerModelServer.forPlanningOnly(LocalControllerModelServer.Scenario.REJECT_CLICK);
        listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(this::serve, "busy-stop-loopback-model");
        worker.setDaemon(true);
        worker.start();
    }
    String endpoint() { return "http://127.0.0.1:" + listener.getLocalPort() + "/v1"; }
    void assertHealthy() { if (failure != null) throw new AssertionError("Busy-stop HTTP fixture failed", failure); }
    boolean isHeld() { return hold && entered.getCount() == 0 && release.getCount() != 0 && !heldExpired; }
    synchronized void releaseAfterStop() {
        if (release.getCount() == 0) return;
        stopped = true;
        releaseNanoTime = System.nanoTime();
        release.countDown();
    }
    void awaitWritten() throws Exception {
        if (!written.await(5, TimeUnit.SECONDS)) throw new AssertionError("Held HTTP write did not finish");
        assertHealthy();
        if (writeAttemptNanoTime < 0) throw new AssertionError("Late HTTP response never reached its socket write attempt");
    }
    JsonObject snapshot() {
        JsonObject value = new JsonObject();
        value.addProperty("requests", requests.get());
        value.addProperty("enteredNanoTime", enteredNanoTime);
        value.addProperty("releaseNanoTime", releaseNanoTime);
        value.addProperty("writeAttemptNanoTime", writeAttemptNanoTime);
        value.addProperty("writeEndNanoTime", writeEndNanoTime);
        value.addProperty("peerClosed", peerClosed);
        value.addProperty("writeSucceeded", writeSucceeded);
        value.addProperty("heldExpired", heldExpired);
        return value;
    }
    private void serve() {
        while (!closed) {
            int round = 0;
            try (Socket current = listener.accept()) {
                socket = current;
                current.setSoTimeout(5_000);
                JsonObject request = read(current.getInputStream());
                round = requests.incrementAndGet();
                if (round > (hold ? 1 : 4)) throw new IOException("Unexpected extra request");
                byte[] response = planner.respond(request, round).toString().getBytes(StandardCharsets.UTF_8);
                if (hold) {
                    enteredNanoTime = System.nanoTime();
                    entered.countDown();
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        heldExpired = true;
                        throw new IOException("Held model response expired");
                    }
                }
                try {
                    if (hold) writeAttemptNanoTime = System.nanoTime();
                    String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                            + "Connection: close\r\nContent-Length: " + response.length + "\r\n\r\n";
                    current.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                    current.getOutputStream().write(response);
                    current.getOutputStream().flush();
                    if (hold) writeSucceeded = true;
                } catch (SocketException disconnected) {
                    if (!hold || !stopped) throw disconnected;
                    peerClosed = true;
                }
            } catch (Throwable error) {
                if (!closed) failure = error;
                return;
            } finally {
                socket = null;
                if (hold && round > 0) {
                    writeEndNanoTime = System.nanoTime();
                    written.countDown();
                }
            }
        }
    }
    private static JsonObject read(InputStream input) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int terminator = 0;
        while (terminator != 0x0d0a0d0a) {
            int next = input.read();
            if (next < 0) throw new IOException("Incomplete HTTP headers");
            header.write(next);
            if (header.size() > 16_384) throw new IOException("HTTP header limit");
            terminator = (terminator << 8) | next;
        }
        String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        if (!"POST /v1/chat/completions HTTP/1.1".equals(lines[0])) throw new IOException("Unexpected HTTP route");
        int length = -1;
        boolean authenticated = false;
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator < 0) continue;
            String key = line.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if ("content-length".equals(key)) length = Integer.parseInt(value);
            if ("authorization".equals(key)) authenticated = ("Bearer " + LocalControllerModelServer.SYNTHETIC_KEY).equals(value);
        }
        if (!authenticated || length <= 0 || length > 1_048_576) throw new IOException("Invalid synthetic HTTP request");
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new IOException("Incomplete HTTP body");
            offset += count;
        }
        JsonObject value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!LocalControllerModelServer.MODEL.equals(value.get("model").getAsString())
                || value.get("stream").getAsBoolean() || value.getAsJsonArray("tools").isEmpty())
            throw new IOException("Unexpected synthetic model request");
        return value;
    }
    @Override public void close() throws Exception {
        closed = true;
        release.countDown();
        listener.close();
        Socket current = socket;
        if (current != null) current.close();
        worker.join(2_000);
        planner.close();
        if (worker.isAlive()) throw new IOException("Busy-stop HTTP fixture worker did not stop");
    }
}
