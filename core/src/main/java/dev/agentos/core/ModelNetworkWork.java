package dev.agentos.core;

import com.google.gson.JsonObject;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/** Future completion/cancellation is not evidence that its network callable has exited. */
final class ModelNetworkWork implements Runnable {
    private static final int PENDING = 0, RUNNING = 1, RELEASED = 2;
    private final AtomicInteger ownership = new AtomicInteger(PENDING);
    private final OwnedRequestPayload payload;
    final FutureTask<JsonObject> future;

    ModelNetworkWork(OwnedRequestPayload payload, Callable<JsonObject> exchange) {
        this.payload = payload;
        this.future = new FutureTask<>(exchange);
    }

    @Override public void run() {
        if (!ownership.compareAndSet(PENDING, RUNNING)) return;
        try { future.run(); }
        finally {
            try { payload.close(); }
            finally { ownership.set(RELEASED); }
        }
    }

    /** Caller-worker cleanup only, never the synchronous UI cancellation hook. */
    void abandonIfPending() {
        if (!ownership.compareAndSet(PENDING, RELEASED)) return;
        future.cancel(false);
        payload.close();
    }
}
