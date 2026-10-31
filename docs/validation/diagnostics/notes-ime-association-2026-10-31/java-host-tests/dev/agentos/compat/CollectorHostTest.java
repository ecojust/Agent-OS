package dev.agentos.compat;
import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Controlled host substitutes exercise caller bounds; this does not emulate Android binder. */
public final class CollectorHostTest {
    private static final String COMPONENT = "com.android.inputmethod.latin/.LatinIME";
    private static int checks;
    private static void check(boolean condition, String detail) {
        checks++; if (!condition) throw new AssertionError(detail);
    }
    private static JSONObject collect(UiAutomation automation) throws Exception {
        Method method = NotesImeAssociationDiagnostic.class.getDeclaredMethod("collect", UiAutomation.class, int.class, String.class);
        method.setAccessible(true);
        return (JSONObject) method.invoke(null, automation, 10012, COMPONENT);
    }
    private static ParcelFileDescriptor text(String text) {
        return new ParcelFileDescriptor(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static String hash(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format("%02x", value & 255));
        return out.toString();
    }
    private static void timeoutAndBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        UiAutomation blocked = new UiAutomation(command -> {
            calls.incrementAndGet();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* simulate uncancellable synchronous binder */ }
            }
            return new ParcelFileDescriptor(new ByteArrayInputStream(new byte[0]) {
                @Override public void close() throws IOException { super.close(); closed.countDown(); }
            });
        });
        AssertionError original = new AssertionError("original hidden timeout");
        Throwable prior = new AssertionError("prior confirmation diagnostic");
        Throwable cause = new IllegalArgumentException("original cause");
        original.initCause(cause); original.addSuppressed(prior);
        long started = System.nanoTime();
        NotesImeAssociationDiagnostic.attach(original, blocked, 10012, COMPONENT);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check(millis >= 2900 && millis < 6000, "caller did not return around its declared 3000ms bound: " + millis);
        check(original.getMessage().equals("original hidden timeout") && original.getCause() == cause
                && original.getSuppressed().length == 2 && original.getSuppressed()[0] == prior,
                "supplement replaced original error/cause/prior suppressed evidence");
        String marker = original.getSuppressed()[1].getMessage();
        check(marker.startsWith(NotesImeAssociationDiagnostic.MARKER) && marker.length() <= NotesImeAssociationDiagnostic.MAX_CHARS,
                "bounded supplemental marker missing");
        JSONObject first = new JSONObject(marker.substring(NotesImeAssociationDiagnostic.MARKER.length()));
        JSONArray commands = first.getJSONArray("commands");
        check(commands.getJSONObject(0).getString("status").equals("TIMEOUT"), "blocked shell did not time out");
        check(commands.getJSONObject(1).getString("status").equals("SKIPPED_AFTER_TIMEOUT")
                && commands.getJSONObject(2).getString("status").equals("SKIPPED_AFTER_TIMEOUT") && calls.get() == 1,
                "timeout launched subsequent commands");
        JSONObject busy = collect(blocked);
        for (int i = 0; i < 3; i++) check(busy.getJSONArray("commands").getJSONObject(i).getString("status").equals("SKIPPED_COLLECTOR_BUSY"),
                "second collection queued work while a worker remained blocked");
        check(calls.get() == 1, "busy collection launched an extra worker/command");
        release.countDown();
        check(closed.await(1000, TimeUnit.MILLISECONDS), "FD returned after cancellation was not closed");
        Field field = NotesImeAssociationDiagnostic.class.getDeclaredField("WORKER_ACTIVE"); field.setAccessible(true);
        AtomicBoolean active = (AtomicBoolean) field.get(null);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (active.get() && System.nanoTime() < until) Thread.yield();
        check(!active.get(), "finished cancelled worker did not release its slot");
        JSONObject recovered = collect(new UiAutomation(command -> text("")));
        check(recovered.getJSONArray("commands").getJSONObject(0).getString("status").equals("COMPLETE"), "released slot remained busy");
        System.out.println("timeout_caller_elapsed_ms=" + millis);
    }
    private static void streamsAndStatuses() throws Exception {
        AtomicInteger calls = new AtomicInteger(), readBytes = new AtomicInteger();
        byte[] oversized = new byte[NotesImeAssociationDiagnostic.MAX_BYTES + 1]; Arrays.fill(oversized, (byte) 'x');
        JSONObject limited = collect(new UiAutomation(command -> {
            if (calls.getAndIncrement() != 0) return text("");
            return new ParcelFileDescriptor(new ByteArrayInputStream(oversized) {
                @Override public synchronized int read(byte[] data, int offset, int length) {
                    int count = super.read(data, offset, length); if (count > 0) readBytes.addAndGet(count); return count;
                }
            });
        }));
        JSONObject bytes = limited.getJSONArray("commands").getJSONObject(0);
        check(bytes.getString("status").equals("BYTE_LIMIT") && bytes.getInt("outputBytes") == NotesImeAssociationDiagnostic.MAX_BYTES
                && !bytes.getBoolean("outputComplete") && bytes.getString("hashScope").equals("RETAINED_PREFIX"), "byte limit mislabeled as complete source");
        check(readBytes.get() == NotesImeAssociationDiagnostic.MAX_BYTES + 1, "byte-limit detection read beyond its one-byte sentinel");
        check(bytes.getString("outputSha256").equals(hash(Arrays.copyOf(oversized, NotesImeAssociationDiagnostic.MAX_BYTES))), "prefix checksum differs");
        check(calls.get() == 3, "byte limit unexpectedly added/retried commands");
        JSONObject eof = collect(new UiAutomation(command -> text(""))).getJSONArray("commands").getJSONObject(0);
        check(eof.getString("status").equals("COMPLETE") && eof.getString("exitStatus").equals("NOT_EXPOSED")
                && eof.getBoolean("outputComplete") && eof.getString("outputSha256").equals(hash(new byte[0]))
                && eof.getJSONObject("parsed").getString("status").equals("UNAVAILABLE"), "EOF incorrectly became service success or a missing IME");
        JSONObject denied = collect(new UiAutomation(command -> text("Permission Denial: requires android.permission.DUMP")));
        check(denied.getJSONArray("commands").getJSONObject(0).getString("status").equals("PERMISSION_DENIED"), "permission denial not retained");
        AtomicInteger timedCalls = new AtomicInteger();
        JSONObject timeout = collect(new UiAutomation(command -> { timedCalls.incrementAndGet(); return text("*** DUMP TIMEOUT ***"); }));
        check(timeout.getJSONArray("commands").getJSONObject(0).getString("status").equals("SERVICE_TIMEOUT")
                && timeout.getJSONArray("commands").getJSONObject(1).getString("status").equals("SKIPPED_AFTER_TIMEOUT")
                && timedCalls.get() == 1, "service timeout did not stop subsequent reads");
        JSONObject failed = collect(new UiAutomation(command -> { throw new IllegalStateException("private exception text"); }));
        check(failed.getJSONArray("commands").getJSONObject(0).getString("status").equals("READ_FAILED")
                && !failed.toString().contains("private exception text"), "read failure exposed remote exception details");
        JSONObject unknown = collect(new UiAutomation(command -> text("Unknown command: a11y")));
        check(unknown.getJSONArray("commands").getJSONObject(0).getString("status").equals("COMMAND_UNAVAILABLE"), "unknown command not retained");
    }
    private static void diagnosticErrorsStaySupplemental() throws Exception {
        AssertionError original = new AssertionError("original remains primary");
        android.os.SystemClock.injected = new UnsatisfiedLinkError("private diagnostic failure");
        try { NotesImeAssociationDiagnostic.attach(original, new UiAutomation(command -> text("")), 10012, COMPONENT); }
        finally { android.os.SystemClock.injected = null; }
        check(original.getMessage().equals("original remains primary") && original.getSuppressed().length == 1,
                "supplemental Error escaped or replaced the original failure");
        check(original.getSuppressed()[0].getMessage().equals(NotesImeAssociationDiagnostic.MARKER
                + "{\"diagnosticSerializationFailed\":true}"), "supplemental Error fallback exposed arbitrary details");
        JSONObject failed = collect(new UiAutomation(command -> { throw new LinkageError("private worker failure"); }));
        check(failed.getJSONArray("commands").getJSONObject(0).getString("status").equals("READ_FAILED")
                && !failed.toString().contains("private worker failure"), "worker Error escaped or exposed details");
        check(collect(new UiAutomation(command -> text(""))).getJSONArray("commands").getJSONObject(0).getString("status").equals("COMPLETE"),
                "worker Error left the collector occupied");
    }
    public static void main(String[] args) throws Exception {
        NotesImeAssociationDiagnostic.verifyParsers();
        timeoutAndBusy(); streamsAndStatuses(); diagnosticErrorsStaySupplemental();
        System.out.println("collector_host_checks=" + checks);
        System.out.println("parser_selftests=passed");
        System.out.println("host_stub_collector_tests=passed");
    }
}
