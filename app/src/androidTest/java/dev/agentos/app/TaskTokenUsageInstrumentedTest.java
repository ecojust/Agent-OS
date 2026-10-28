package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.ModelTokenUsage;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.TaskResult;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.agentos.app.TaskUsageTestHarness.*;
import static org.junit.Assert.*;

/** Real Controller/HTTP accounting and protected UI; no injected counters or device writes. */
@RunWith(AndroidJUnit4.class)
public final class TaskTokenUsageInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_task_token_usage_capture_not_attempted");

    @Test public void completeReportedTokensAccumulateAcrossRealHttpRepliesAndRenderWithoutChangingLocalLimits() throws Exception {
        try (TokenTask task = new TokenTask(0, usage(2L, 0L, 2L), usage(3L, 0L, 3L));
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            task.start();
            AgentController.TaskUsage ended = task.awaitEnded();
            assertEquals(TaskResult.Kind.MODEL_REPORT, task.controller.getState().resultKind);
            assertEquals(TaskBudget.DEFAULT, ended.budget);
            assertEquals(2, task.server.requests.get());
            assertEquals(2, ended.modelTokens.calls);
            assertEquals(ModelTokenUsage.Completeness.COMPLETE, ended.modelTokens.completeness);
            assertField(ended.modelTokens.prompt, 5L, 2);
            assertField(ended.modelTokens.completion, 0L, 2);
            assertField(ended.modelTokens.total, 5L, 2);
            assertEquals(java.util.Arrays.asList(128, 128), task.server.outputLimits());
            Activity activity = task.openMain();
            task.onMain(() -> {
                assertEquals("服务端 Token · 已登记调用全部有统计", text(activity, R.id.task_token_usage_heading));
                assertEquals("输入：5（有效报告 2/2 次调用）", text(activity, R.id.task_token_usage_input));
                assertEquals("输出：0（有效报告 2/2 次调用）", text(activity, R.id.task_token_usage_output));
                assertEquals("总计：5（有效报告 2/2 次调用）", text(activity, R.id.task_token_usage_total));
                assertProtectedTokenView(activity);
                assertFalse(task.handler().hasCallbacks(task.refresh()));
            });
            waitRealMillis(1_100);
            assertSame("Ended accounting must stay frozen", ended, task.controller.getTaskUsage());
            task.onMain(() -> assertEquals("输出：0（有效报告 2/2 次调用）",
                    text(activity, R.id.task_token_usage_output)));
            assertNoDispatch(trace);
            task.server.assertHealthy();
            screenshots.capture("protected-complete-server-tokens-and-real-zero");
        }
    }

    @Test public void partialAndMissingMetadataKeepIndependentCoverageWithoutInventingTotals() throws Exception {
        try (TokenTask task = new TokenTask(0, usage(11L, 5L, null), usage(13L, null, null), null);
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            task.start();
            AgentController.TaskUsage ended = task.awaitEnded();
            assertEquals(TaskResult.Kind.MODEL_REPORT, task.controller.getState().resultKind);
            assertEquals(3, task.server.requests.get());
            assertEquals(3, ended.modelTokens.calls);
            assertEquals(ModelTokenUsage.Completeness.PARTIAL, ended.modelTokens.completeness);
            assertField(ended.modelTokens.prompt, 24L, 2);
            assertField(ended.modelTokens.completion, 5L, 1);
            assertField(ended.modelTokens.total, null, 0);
            Activity activity = task.openMain();
            task.onMain(() -> {
                assertEquals("服务端 Token · 部分统计已报告", text(activity, R.id.task_token_usage_heading));
                assertEquals("输入已报告小计：24（有效报告 2/3 次调用）", text(activity, R.id.task_token_usage_input));
                assertEquals("输出已报告小计：5（有效报告 1/3 次调用）", text(activity, R.id.task_token_usage_output));
                assertEquals("总计：未知（有效报告 0/3 次调用）", text(activity, R.id.task_token_usage_total));
                assertProtectedTokenView(activity);
            });
            assertNoDispatch(trace);
            task.server.assertHealthy();
            screenshots.capture("protected-partial-field-coverage-and-unknown-total");
        }
    }

    @Test public void stoppedInFlightReplyCannotChangeFrozenTokensOrLeakIntoClearAndNewTask() throws Exception {
        try (TokenTask task = new TokenTask(2, null, usage(700L, 200L, 900L));
             PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
            task.start();
            task.server.awaitHeldReply();
            RunControl originalOwner = task.owner();
            AgentController.TaskUsage pending = task.controller.getTaskUsage();
            assertEquals(2, task.server.requests.get());
            assertEquals(2, pending.modelTokens.calls);
            assertField(pending.modelTokens.prompt, null, 0);
            assertField(pending.modelTokens.completion, null, 0);
            assertField(pending.modelTokens.total, null, 0);
            task.stop();
            AgentController.TaskUsage stopped = task.controller.getTaskUsage();
            assertTrue(originalOwner.isCancelled());
            assertEquals(0, originalOwner.getDispatchAttempts());
            assertTrue(stopped.finished);
            assertEquals(TaskResult.Kind.STOPPED, task.controller.getState().resultKind);
            assertEquals(ModelTokenUsage.Completeness.UNKNOWN, stopped.modelTokens.completeness);
            assertEquals(2, stopped.modelTokens.calls);
            assertField(stopped.modelTokens.prompt, null, 0);
            assertField(stopped.modelTokens.completion, null, 0);
            assertField(stopped.modelTokens.total, null, 0);

            // The server really parsed request two and prepared its reply. Stop may
            // already have closed the client socket; a write is not proof of consumption.
            task.server.releaseAfterStop();
            task.server.awaitHeldWrite();
            task.settle();
            task.instrumentation.waitForIdleSync();
            assertSame("The late HTTP attempt must not replace stop's frozen snapshot", stopped, task.controller.getTaskUsage());
            ModelTokenUsage original = originalOwner.modelTokenUsageSnapshot();
            assertEquals(2, original.calls);
            assertField(original.prompt, null, 0);
            assertField(original.completion, null, 0);
            assertField(original.total, null, 0);
            assertEquals(0, originalOwner.getDispatchAttempts());

            Activity activity = task.openMain();
            task.onMain(() -> {
                assertEquals("服务端 Token · 用量未知", text(activity, R.id.task_token_usage_heading));
                assertEquals("输入：未知（有效报告 0/2 次调用）", text(activity, R.id.task_token_usage_input));
                assertEquals("输出：未知（有效报告 0/2 次调用）", text(activity, R.id.task_token_usage_output));
                assertEquals("总计：未知（有效报告 0/2 次调用）", text(activity, R.id.task_token_usage_total));
                assertTrue(activity.findViewById(R.id.clear_task).performClick());
                assertNull(task.controller.getTaskUsage());
                assertEquals(View.GONE, activity.findViewById(R.id.task_usage).getVisibility());
                for (int id : tokenValueIds()) assertEquals("", text(activity, id));
            });
            try (WorkerHold hold = new WorkerHold(task.worker())) {
                try {
                    task.start();
                    AgentController.TaskUsage fresh = task.controller.getTaskUsage();
                    assertNotEquals(stopped.taskId, fresh.taskId);
                    assertFalse(fresh.finished);
                    assertEquals(0, fresh.modelTokens.calls);
                    assertField(fresh.modelTokens.prompt, null, 0);
                    assertField(fresh.modelTokens.completion, null, 0);
                    assertField(fresh.modelTokens.total, null, 0);
                    task.instrumentation.waitForIdleSync();
                    task.onMain(() -> {
                        assertEquals("服务端 Token · 尚未请求", text(activity, R.id.task_token_usage_heading));
                        assertEquals("输入：尚未请求", text(activity, R.id.task_token_usage_input));
                        assertEquals("输出：尚未请求", text(activity, R.id.task_token_usage_output));
                        assertEquals("总计：尚未请求", text(activity, R.id.task_token_usage_total));
                        assertProtectedTokenView(activity);
                    });
                    screenshots.capture("protected-stopped-tokens-cleared-and-new-task-unrequested");
                } finally { task.stop(); }
            }
            task.settle();
            assertEquals("The replacement stopped in its queue, before another HTTP call", 2, task.server.requests.get());
            assertEquals(0, task.controller.getTaskUsage().modelTokens.calls);
            assertField(stopped.modelTokens.total, null, 0);
            assertNoDispatch(trace);
            task.server.assertHealthy();
        }
    }

    private static JsonObject usage(Long prompt, Long completion, Long total) {
        JsonObject value = new JsonObject();
        if (prompt != null) value.addProperty("prompt_tokens", prompt);
        if (completion != null) value.addProperty("completion_tokens", completion);
        if (total != null) value.addProperty("total_tokens", total);
        return value;
    }

    private static void assertField(ModelTokenUsage.Field field, Long subtotal, long reported) {
        assertEquals(subtotal, field.subtotal);
        assertEquals(reported, field.reportedCalls);
        assertFalse(field.overflowed);
    }

    private static String text(Activity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static int[] tokenValueIds() {
        return new int[]{R.id.task_token_usage_heading, R.id.task_token_usage_input,
                R.id.task_token_usage_output, R.id.task_token_usage_total};
    }

    private static void assertProtectedTokenView(Activity activity) {
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                activity.findViewById(R.id.task_usage).getAccessibilityLiveRegion());
        for (int id : tokenValueIds()) {
            assertFalse(activity.findViewById(id).isSaveEnabled());
            assertFalse(text(activity, id).contains(TokenServer.KEY));
            assertFalse(text(activity, id).contains(TokenServer.MODEL));
        }
        String explanation = text(activity, R.id.task_token_usage_explanation);
        assertTrue(explanation.contains("缺失值不补算"));
        assertTrue(explanation.contains("不是费用或累计预算"));
        assertTrue(explanation.contains("调用不保证送达或计费"));
    }

    private static final class TokenTask implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final AgentController controller = AgentController.get(context);
        TokenServer server;
        ControllerTestSettings settings;
        DeviceHarness device;
        Activity activity;

        TokenTask(int heldRound, JsonObject... replies) throws Exception {
            assertFalse("Token tests must not replace a running task", controller.getState().running);
            try {
                settle();
                onMain(controller::clearCompletedTask);
                instrumentation.waitForIdleSync();
                server = new TokenServer(heldRound, replies);
                settings = new ControllerTestSettings(context,
                        new ModelSettings(server.endpoint(), TokenServer.KEY, TokenServer.MODEL, 128),
                        ActionConfirmationPolicy.Mode.CONFIRM_ALL);
                settle();
                instrumentation.waitForIdleSync();
                device = new DeviceHarness();
                onMain(() -> device.service.showOverlay("正在连接模型", false, ""));
                device.service.whenControlsReadyForAction().get(5, TimeUnit.SECONDS);
                device.awaitUiIdle();
                DeviceHarness.awaitObservation(device.tools, device.control,
                        value -> DeviceHarness.FIXTURE.equals(DeviceHarness.string(value, "packageName"))
                                && value.get("focused").getAsBoolean() && !value.get("hasOcclusion").getAsBoolean());
                instrumentation.waitForIdleSync();
                assertNull(controller.getTaskUsage());
            } catch (Throwable failure) {
                try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void start() {
            onMain(() -> controller.start(settings.model, "Measure only this synthetic HTTP task's reported tokens.",
                    Collections.singleton(DeviceHarness.FIXTURE), settings.executionMode, TaskBudget.DEFAULT, settings.lease));
        }

        void stop() { onMain(controller::stop); }
        ThreadPoolExecutor worker() throws Exception { return (ThreadPoolExecutor) readField(controller, "worker"); }
        RunControl owner() throws Exception { return (RunControl) readField(controller, "control"); }
        void settle() throws Exception { drainWorker(worker()); }

        AgentController.TaskUsage awaitEnded() throws Exception {
            await("The real token task did not finish", () -> {
                server.assertHealthy();
                AgentController.TaskUsage usage = controller.getTaskUsage();
                return !controller.getState().running && usage != null && usage.finished;
            });
            settle();
            instrumentation.waitForIdleSync();
            server.assertHealthy();
            return controller.getTaskUsage();
        }

        Activity openMain() throws Exception {
            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await("The real task Activity did not load", () -> {
                AtomicBoolean ready = new AtomicBoolean();
                onMain(() -> ready.set(readBoolean(activity, "ready") && readBoolean(activity, "resumed")
                        && !readBoolean(activity, "busy") && !activity.isFinishing()));
                return ready.get();
            });
            instrumentation.waitForIdleSync();
            return activity;
        }

        Handler handler() {
            try { return mainHandler(activity); }
            catch (Exception error) { throw new AssertionError(error); }
        }

        Runnable refresh() {
            try { return usageRefresh(activity); }
            catch (Exception error) { throw new AssertionError(error); }
        }

        void onMain(Runnable action) {
            AtomicReference<Throwable> caught = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { action.run(); } catch (Throwable failure) { caught.set(failure); }
            });
            Throwable failure = caught.get();
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure != null) throw new AssertionError(failure);
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            try { stop(); } catch (Throwable error) { failure = error; }
            if (server != null) server.releaseAfterStop();
            try { settle(); } catch (Throwable error) { failure = add(failure, error); }
            try {
                if (activity != null) {
                    onMain(activity::finish);
                    await("Token Activity did not destroy", activity::isDestroyed);
                    ExecutorService storage = (ExecutorService) readField(activity, "storageWorker");
                    assertTrue("Actual settings reads must finish before restoring preferences", storage.awaitTermination(5, TimeUnit.SECONDS));
                }
            } catch (Throwable error) { failure = add(failure, error); }
            try { onMain(controller::clearCompletedTask); instrumentation.waitForIdleSync(); }
            catch (Throwable error) { failure = add(failure, error); }
            try { if (settings != null) settings.close(); }
            catch (Throwable error) { failure = add(failure, error); }
            try { if (server != null) server.close(); }
            catch (Throwable error) { failure = add(failure, error); }
            try { if (device != null) device.close(); }
            catch (Throwable error) { failure = add(failure, error); }
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof Exception) throw (Exception) failure;
        }
    }

    private static Throwable add(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    /** The replacement is held on the actual Controller queue, before any request. */
    private static final class WorkerHold implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        final Future<?> future;
        WorkerHold(ThreadPoolExecutor worker) throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            future = worker.submit(() -> {
                entered.countDown();
                try { assertTrue("Synthetic queue gate exceeded its bound", release.await(20, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            });
            try { assertTrue("Actual Controller worker did not enter the gate", entered.await(5, TimeUnit.SECONDS)); }
            catch (Throwable failure) { release.countDown(); throw failure; }
        }
        @Override public void close() throws Exception { release.countDown(); future.get(5, TimeUnit.SECONDS); }
    }

    /** Bounded loopback protocol fixture. Only the explicitly stopped held write may disconnect. */
    private static final class TokenServer implements AutoCloseable {
        static final String KEY = "synthetic-token-fixture-key";
        static final String MODEL = "synthetic-token-fixture-model";
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), written = new CountDownLatch(1);
        final List<JsonObject> reports = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        final int heldRound;
        final ServerSocket listener;
        final Thread thread;
        volatile Socket active;
        volatile Throwable failure;
        volatile boolean closed, stoppedHeldWrite, heldWriteAttempted, heldPeerClosed;

        TokenServer(int heldRound, JsonObject[] replies) throws IOException {
            if (replies.length < 1 || replies.length > 3 || heldRound < 0 || heldRound > replies.length)
                throw new IllegalArgumentException("Invalid bounded token fixture");
            this.heldRound = heldRound;
            for (JsonObject report : replies) reports.add(report == null ? null : report.deepCopy());
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "token-usage-http-fixture");
            thread.setDaemon(true);
            thread.start();
        }

        String endpoint() { return "http://127.0.0.1:" + listener.getLocalPort() + "/v1"; }
        List<Integer> outputLimits() { synchronized (limits) { return new ArrayList<>(limits); } }
        void assertHealthy() { if (failure != null) throw new AssertionError("Synthetic token HTTP fixture failed", failure); }

        void awaitHeldReply() throws Exception {
            assertTrue("The server did not parse and hold its actual HTTP reply", entered.await(10, TimeUnit.SECONDS));
            assertHealthy();
        }

        void releaseAfterStop() { stoppedHeldWrite = true; release.countDown(); }

        void awaitHeldWrite() throws Exception {
            assertTrue("The server did not finish its held write attempt", written.await(5, TimeUnit.SECONDS));
            assertHealthy();
            assertTrue("A released reply must really reach the socket-write attempt", heldWriteAttempted);
            // heldPeerClosed records an actual SocketException, not successful delivery.
        }

        private void serve() {
            while (!closed) {
                int round = 0;
                try (Socket socket = listener.accept()) {
                    active = socket;
                    socket.setSoTimeout(5_000);
                    readRequest(socket.getInputStream());
                    round = requests.incrementAndGet();
                    if (round > reports.size()) throw new IOException("Unexpected extra synthetic request");
                    byte[] body = response(round).toString().getBytes(StandardCharsets.UTF_8);
                    if (round == heldRound) {
                        entered.countDown();
                        if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Synthetic reply gate exceeded its bound");
                    }
                    try {
                        if (round == heldRound) heldWriteAttempted = true;
                        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                                + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                    } catch (SocketException disconnected) {
                        if (round != heldRound || !stoppedHeldWrite) throw disconnected;
                        heldPeerClosed = true;
                    }
                } catch (Throwable error) {
                    if (!closed) failure = error;
                    return;
                } finally {
                    active = null;
                    // Publish completion only after an unexpected write/close error
                    // has reached failure, so awaitHeldWrite cannot race its report.
                    if (round == heldRound) written.countDown();
                }
            }
        }

        private void readRequest(InputStream input) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int terminator = 0;
            while (terminator != 0x0d0a0d0a) {
                int value = input.read();
                if (value < 0) throw new IOException("Incomplete synthetic HTTP header");
                header.write(value);
                if (header.size() > 16_384) throw new IOException("Synthetic HTTP header exceeded its limit");
                terminator = (terminator << 8) | value;
            }
            String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
            if (!"POST /v1/chat/completions HTTP/1.1".equals(lines[0])) throw new IOException("Unexpected synthetic route");
            int length = -1;
            boolean authenticated = false;
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if ("content-length".equals(name)) {
                    if (length != -1) throw new IOException("Duplicate synthetic content length");
                    length = Integer.parseInt(value);
                }
                if ("authorization".equals(name)) authenticated = ("Bearer " + KEY).equals(value);
            }
            if (!authenticated || length < 1 || length > 4 * 1024 * 1024) throw new IOException("Invalid bounded synthetic request");
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < body.length) {
                int count = input.read(body, offset, body.length - offset);
                if (count < 0) throw new IOException("Incomplete synthetic HTTP body");
                offset += count;
            }
            JsonObject request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!MODEL.equals(request.get("model").getAsString()) || request.get("stream").getAsBoolean()
                    || request.getAsJsonArray("tools").isEmpty()) throw new IOException("Unexpected synthetic model protocol");
            if (!request.get("max_tokens").isJsonPrimitive() || !request.getAsJsonPrimitive("max_tokens").isNumber()
                    || !"128".equals(request.get("max_tokens").getAsString())) throw new IOException("The independent output limit changed");
            synchronized (limits) {
                if (limits.size() >= reports.size()) throw new IOException("Synthetic request metadata exceeded its limit");
                limits.add(128);
            }
        }

        private JsonObject response(int round) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            if (round == reports.size()) {
                message.addProperty("content", "Synthetic token fixture report; no business outcome was verified.");
            } else {
                JsonObject function = new JsonObject();
                function.addProperty("name", "wait");
                function.addProperty("arguments", "{\"milliseconds\":0}");
                JsonObject call = new JsonObject();
                call.addProperty("id", "token-round-" + round);
                call.addProperty("type", "function");
                call.add("function", function);
                JsonArray calls = new JsonArray(); calls.add(call);
                message.add("tool_calls", calls);
            }
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject result = new JsonObject(); result.add("choices", choices);
            JsonObject report = reports.get(round - 1);
            if (report != null) result.add("usage", report.deepCopy());
            return result;
        }

        @Override public void close() throws Exception {
            closed = true;
            release.countDown();
            listener.close();
            Socket socket = active;
            if (socket != null) socket.close();
            thread.join(3_000);
            assertFalse("The bounded token HTTP fixture did not terminate", thread.isAlive());
            assertHealthy();
        }
    }
}
