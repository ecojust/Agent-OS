package dev.agentos.core;

import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * One admitted UTF-8 wire buffer, without an intermediate complete JSON String.
 * This accounts only for the owned buffer capacity, not JSON/source strings, encoder
 * internals, HTTP buffers, native memory or the time at which GC reclaims objects.
 */
final class OwnedRequestPayload implements AutoCloseable {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private final int length;
    private ImageWorkset.Reservation reservation;
    private byte[] bytes;

    private OwnedRequestPayload(byte[] bytes, ImageWorkset.Reservation reservation) {
        this.bytes = bytes; this.length = bytes.length; this.reservation = reservation;
    }

    /** The supplied body is already a private snapshot belonging to this request. */
    static OwnedRequestPayload encode(JsonObject body, ImageWorkset workset, ModelRequestContext context)
            throws ModelException, InterruptedException {
        return encode(body, null, workset, context);
    }

    /** Borrows the typed PNG for two passes; the request retains responsibility for closing it. */
    static OwnedRequestPayload encode(JsonObject body, PngImage image, ImageWorkset workset, ModelRequestContext context)
            throws ModelException, InterruptedException {
        context.check();
        Sink counted = new Sink(null, context);
        byte[] firstHash = null, secondHash = null, encoded = null;
        ImageWorkset.Reservation reservation = null;
        boolean transferred = false;
        try (ImageRequestSerializer picture = image == null ? null : imageSerializer(image, workset, context)) {
            serialize(body, counted, picture);
            context.check();
            firstHash = counted.hash.digest();
            try { reservation = workset.reserve(counted.length); }
            catch (IllegalStateException full) {
                context.check();
                throw new ModelException("REQUEST_MEMORY_LIMIT", "模型请求缓冲预算正在使用，请等待上次请求释放资源后重试。");
            }
            encoded = new byte[counted.length];
            Sink written = new Sink(encoded, context);
            serialize(body, written, picture);
            context.check();
            secondHash = written.hash.digest();
            if (written.length != counted.length || !MessageDigest.isEqual(firstHash, secondHash)) throw changed();
            OwnedRequestPayload payload = new OwnedRequestPayload(encoded, reservation);
            transferred = true;
            return payload;
        } finally {
            if (firstHash != null) Arrays.fill(firstHash, (byte) 0);
            if (secondHash != null) Arrays.fill(secondHash, (byte) 0);
            if (!transferred) {
                if (encoded != null) Arrays.fill(encoded, (byte) 0);
                if (reservation != null) reservation.close();
            }
        }
    }

    private static ImageRequestSerializer imageSerializer(PngImage image, ImageWorkset workset, ModelRequestContext context)
            throws ModelException, TaskControlException {
        try { return new ImageRequestSerializer(image, workset); }
        catch (IllegalStateException unavailable) {
            context.check();
            throw new ModelException("REQUEST_MEMORY_LIMIT", "模型图片缓冲暂不可用，请等待上次请求释放资源后重试。");
        }
    }

    private static void serialize(JsonObject body, Sink output, ImageRequestSerializer picture) throws ModelException, InterruptedException {
        // These are the same writer settings used by JsonElement.toString(). In
        // particular HTML characters, nulls and surrogate handling stay unchanged.
        try (OutputStreamWriter utf8 = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            if (picture == null) {
                JsonWriter json = new JsonWriter(utf8);
                json.setStrictness(Strictness.LENIENT);
                Streams.write(body, json);
            } else picture.write(body, utf8, output.context);
        } catch (Stopped stopped) { throw stopped.failure; }
        catch (Rejected rejected) { throw rejected.failure; }
        catch (IOException failed) { throw new ModelException("INVALID_REQUEST", "无法编码模型请求，尚未发送网络请求。"); }
    }

    int length() { return length; }

    /** Called only by the network worker that owns the payload until its finally exits. */
    void writeTo(OutputStream output, ModelRequestContext context) throws IOException, TaskControlException {
        byte[] source = bytes;
        if (source == null) throw new IllegalStateException("Model request payload has been released.");
        for (int offset = 0; offset < source.length; offset += 8192) {
            context.check();
            output.write(source, offset, Math.min(8192, source.length - offset));
            context.check();
        }
    }

    @Override public synchronized void close() {
        if (bytes == null) return;
        Arrays.fill(bytes, (byte) 0); bytes = null;
        reservation.close(); reservation = null;
    }

    private static ModelException changed() {
        return new ModelException("REQUEST_CHANGED", "模型请求在编码期间发生变化，尚未发送网络请求。");
    }

    private static final class Sink extends OutputStream {
        final MessageDigest hash;
        final byte[] destination;
        final ModelRequestContext context;
        int length;

        Sink(byte[] destination, ModelRequestContext context) {
            this.destination = destination; this.context = context;
            try { hash = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
        }

        private void check(int count) throws IOException {
            try { context.check(); } catch (TaskControlException stopped) { throw new Stopped(stopped); }
            if (count > MAX_BYTES - length) throw new Rejected(new ModelException("CONTEXT_LIMIT",
                    "任务上下文过大，已停止发送；请缩小任务范围。"));
            if (destination != null && count > destination.length - length) throw new Rejected(changed());
        }

        @Override public void write(int value) throws IOException {
            check(1);
            if (destination != null) destination[length] = (byte) value;
            hash.update((byte) value); length++;
        }

        @Override public void write(byte[] source, int offset, int count) throws IOException {
            java.util.Objects.requireNonNull(source);
            if (offset < 0 || count < 0 || offset > source.length - count) throw new IndexOutOfBoundsException();
            check(count);
            if (destination != null) System.arraycopy(source, offset, destination, length, count);
            hash.update(source, offset, count); length += count;
        }
    }

    private static final class Stopped extends IOException {
        final TaskControlException failure;
        Stopped(TaskControlException failure) { this.failure = failure; }
    }
    private static final class Rejected extends IOException {
        final ModelException failure;
        Rejected(ModelException failure) { this.failure = failure; }
    }
}
