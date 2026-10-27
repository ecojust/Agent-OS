package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, non-streaming model gateway. No transport error is silently retried. */
final class HttpModelClient {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final ThreadPoolExecutor NETWORK = executor("agent-model-network");
    private static final ThreadPoolExecutor CLOSER = executor("agent-model-close");
    private final long requestTimeoutMillis;
    private final ConnectionFactory connections;
    private final ThreadPoolExecutor network;
    private final Executor closer;
    private final ImageWorkset workset;

    @FunctionalInterface interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }

    HttpModelClient() { this(90_000); }
    HttpModelClient(long requestTimeoutMillis) { this(requestTimeoutMillis, url -> (HttpURLConnection) url.openConnection()); }
    HttpModelClient(long requestTimeoutMillis, ConnectionFactory connections) {
        this(requestTimeoutMillis, connections, NETWORK, CLOSER, ImageWorkset.process());
    }

    /** Package-only seams for deterministic queue, blocking-I/O and ownership tests. */
    HttpModelClient(long requestTimeoutMillis, ConnectionFactory connections, ThreadPoolExecutor network,
                    Executor closer, ImageWorkset workset) {
        if (requestTimeoutMillis < 1 || requestTimeoutMillis > 90_000) throw new IllegalArgumentException();
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.connections = java.util.Objects.requireNonNull(connections);
        this.network = java.util.Objects.requireNonNull(network);
        this.closer = java.util.Objects.requireNonNull(closer);
        this.workset = java.util.Objects.requireNonNull(workset);
    }

    /** Package-only synthetic test helper; production requests always supply their bound context. */
    JsonObject requestSynthetic(ModelSettings settings, JsonArray messages, JsonArray tools, RunControl control, long epoch)
            throws ModelException, InterruptedException {
        return request(settings, messages, tools, ModelRequestContext.syntheticRequest(control, epoch));
    }

    JsonObject request(ModelSettings settings, JsonArray messages, JsonArray tools, ModelRequestContext context)
            throws ModelException, InterruptedException {
        context.check();
        RunControl control = context.control;
        JsonObject body = new JsonObject(); body.addProperty("model", settings.model); body.add("messages", messages.deepCopy());
        body.add("tools", tools.deepCopy()); body.addProperty("tool_choice", "auto"); body.addProperty("stream", false);
        body.addProperty("max_tokens", settings.maxOutputTokens);
        context.check();
        OwnedRequestPayload payload = OwnedRequestPayload.encode(body, workset, context);
        ModelNetworkWork ownedWork = null;
        try {
            context.check();
            long remainingBudget = control.remainingActiveMillis();
            long timeout = Math.max(1, Math.min(requestTimeoutMillis, remainingBudget));
            AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
            ModelNetworkWork work = new ModelNetworkWork(payload, () -> exchange(settings, payload, context, connection));
            ownedWork = work;
            Runnable abort = () -> {
                // This hook may run on the UI thread. It never wipes a large buffer.
                work.future.cancel(true);
                network.remove(work);
                HttpURLConnection active = connection.get();
                if (active != null) try { closer.execute(active::disconnect); } catch (RejectedExecutionException ignored) { }
            };
            control.setCancelHook(abort);
            try {
                context.check();
                try { network.execute(work); }
                catch (RejectedExecutionException busy) { throw new ModelException("NETWORK_BUSY", "上次网络请求尚在释放资源，请稍后重新开始任务。"); }
                JsonObject result = work.future.get(timeout, TimeUnit.MILLISECONDS);
                context.check();
                return result;
            } catch (CancellationException cancelled) {
                context.check();
                throw new ModelException("REQUEST_CANCELLED", "模型请求已取消。");
            } catch (TimeoutException timedOut) {
                abort.run();
                context.check();
                if (remainingBudget <= requestTimeoutMillis) throw new TaskControlException("BUDGET_EXCEEDED", "任务活动时间已达到上限。");
                throw new ModelException("TIMEOUT", "模型请求已达到总时限，未自动重发；请检查服务后重新开始。");
            } catch (ExecutionException failed) {
                context.check();
                Throwable cause = failed.getCause();
                if (cause instanceof ModelException) throw (ModelException) cause;
                if (cause instanceof TaskControlException) throw (TaskControlException) cause;
                if (cause instanceof SocketTimeoutException) throw new ModelException("TIMEOUT", "模型连接或数据等待超时，未自动重发。");
                throw new ModelException("NETWORK_ERROR", "无法完成模型请求，请检查地址、网络和证书；请求未自动重发。");
            } catch (InterruptedException interrupted) {
                abort.run(); throw interrupted;
            } finally {
                control.setCancelHook(null);
            }
        } finally {
            if (ownedWork == null) payload.close();
            else ownedWork.abandonIfPending();
        }
    }

    private JsonObject exchange(ModelSettings settings, OwnedRequestPayload payload, ModelRequestContext context,
                                AtomicReference<HttpURLConnection> active) throws Exception {
        // Recheck on the network worker, even if this request spent time queued.
        context.check();
        URL endpoint = settings.completionUrl();
        context.check();
        HttpURLConnection connection = connections.open(endpoint);
        active.set(connection);
        try {
            context.check();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000); connection.setReadTimeout(30_000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (!settings.apiKey.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey);
            connection.setFixedLengthStreamingMode(payload.length());
            context.check();
            // getOutputStream may already connect and send headers. A check after
            // it returns prevents a stale first body write; no local check can
            // retract headers or make the following transport operation atomic.
            try (OutputStream output = connection.getOutputStream()) {
                context.check();
                payload.writeTo(output, context);
            }
            context.check();
            int status = connection.getResponseCode();
            context.check();
            if (status < 200 || status >= 300) throw statusError(status);
            long declared = connection.getContentLengthLong();
            if (declared > MAX_RESPONSE_BYTES) throw new ModelException("RESPONSE_LIMIT", "模型响应超过大小上限。");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    context.check();
                    int read = input.read(buffer);
                    context.check();
                    if (read == -1) break;
                    if (output.size() + read > MAX_RESPONSE_BYTES) throw new ModelException("RESPONSE_LIMIT", "模型响应超过大小上限。");
                    output.write(buffer, 0, read);
                }
                try {
                    context.check();
                    JsonObject result = StrictJson.object(new String(output.toByteArray(), StandardCharsets.UTF_8));
                    context.check();
                    return result;
                }
                catch (IllegalArgumentException malformed) { throw new ModelException("INVALID_RESPONSE", "模型未返回有效的完整 JSON 响应。"); }
            }
        } finally { active.compareAndSet(connection, null); connection.disconnect(); }
    }

    private static ModelException statusError(int status) {
        if (status >= 300 && status < 400) return new ModelException("REDIRECT_REJECTED", "模型服务返回重定向；为避免泄露密钥，请填写最终服务地址。");
        if (status == 401 || status == 403) return new ModelException("MODEL_AUTH", "模型服务拒绝认证，请检查密钥和访问权限（HTTP " + status + "）。");
        if (status == 429) return new ModelException("RATE_LIMIT", "模型服务请求受限，未自动重试；请稍后再开始（HTTP 429）。");
        if (status == 404) return new ModelException("MODEL_NOT_FOUND", "模型接口或模型不存在，请检查配置（HTTP 404）。");
        return new ModelException("MODEL_HTTP_ERROR", "模型服务返回错误（HTTP " + status + "），请检查接口、模型及工具调用支持。");
    }

    private static ThreadPoolExecutor executor(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true); return executor;
    }
}
