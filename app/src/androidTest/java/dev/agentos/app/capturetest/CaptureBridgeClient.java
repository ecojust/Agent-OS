package dev.agentos.app.capturetest;

import android.Manifest;
import android.app.UiAutomation;
import android.content.ContentProviderClient;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** SDK/JDK-only client. Binder callbacks only take or close handles; they never decode pixels. */
public final class CaptureBridgeClient implements AutoCloseable {
    public static final int SUCCESS = 0, UNAVAILABLE = 1;
    private static final Uri URI = Uri.parse("content://" + CaptureBridgeProvider.AUTHORITY);
    private static final String COMPONENT = "dev.agentos.app.test/dev.agentos.app.capturetest.CaptureBridgeService";
    private final Context context;
    private final UiAutomation automation;
    private final ContentProviderClient provider;
    private final String nonce = UUID.randomUUID().toString();
    private final String originalServices, originalEnabled;
    private final Object lock = new Object();
    private final AtomicInteger owned = new AtomicInteger(), closed = new AtomicInteger(), lateClosed = new AtomicInteger();
    private boolean closing, requested, opened;
    private Result accepted;
    private CompletableFuture<Result> pending;
    private ResultReceiver localCallback;
    private String receiverClassFromBridge;

    public CaptureBridgeClient(Context context, UiAutomation automation) throws Exception {
        this.context = context; this.automation = automation;
        originalServices = readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        originalEnabled = readSetting(Settings.Secure.ACCESSIBILITY_ENABLED);
        if (originalServices != null && Arrays.asList(originalServices.split(":")).contains(COMPONENT))
            throw new IllegalStateException("The test-only capture service must not already be enabled");
        // Keep the bridge process/session alive through service unbinding and its
        // final drain checks; a restarted provider must not masquerade as cleanup.
        provider = context.getContentResolver().acquireContentProviderClient(URI);
        if (provider == null) throw new IllegalStateException("Could not acquire the test bridge provider");
        try {
            call("open", extras()); opened = true;
            String enabled = originalServices == null || originalServices.isEmpty()
                    ? COMPONENT : originalServices + ":" + COMPONENT;
            writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            writeSetting(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            awaitStatus(value -> value.getBoolean("connected"), 8_000, "Test capture service did not connect");
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Bundle status() { return call("status", extras()); }

    public Result capture(int windowId, Rect bounds) throws Exception {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Result> completion = new CompletableFuture<>();
        synchronized (lock) {
            if (closing || requested) throw new IllegalStateException("One capture per test session");
            requested = true; pending = completion;
        }
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                HardwareBuffer buffer = null;
                Result received = null;
                try {
                    if (data == null) throw new IllegalArgumentException("Missing capture result");
                    data.setClassLoader(HardwareBuffer.class.getClassLoader());
                    buffer = data.getParcelable("buffer");
                    received = new Result(code, data, buffer);
                    buffer = null; // The Result now owns this actual receiver-side handle.
                    synchronized (lock) {
                        if (closing || !nonce.equals(data.getString("nonce"))
                                || !requestId.equals(data.getString("requestId")) || completion.isDone()) {
                            received.closeLate(); return;
                        }
                        accepted = received;
                        completion.complete(received);
                    }
                } catch (RuntimeException | Error failure) {
                    if (received != null) received.closeLate();
                    else if (buffer != null) buffer.close();
                    completion.completeExceptionally(failure);
                    if (failure instanceof Error) throw (Error) failure;
                }
            }
        };
        synchronized (lock) { localCallback = receiver; }
        try {
            // Bundle.writeParcelable otherwise writes the anonymous application
            // subclass name. The remote SDK-only classloader must receive only a
            // framework ResultReceiver backed by this retained local callback.
            ResultReceiver proxy = frameworkProxy(receiver);
            if (proxy.getClass() != ResultReceiver.class)
                throw new IllegalStateException("Capture callback proxy is not the framework class");
            Bundle request = extras(); request.putString("requestId", requestId);
            request.putInt("windowId", windowId); request.putParcelable("bounds", new Rect(bounds));
            request.putParcelable("receiver", proxy);
            Bundle acknowledged = call("capture", request);
            receiverClassFromBridge = acknowledged.getString("receiverClass");
            if (!ResultReceiver.class.getName().equals(receiverClassFromBridge))
                throw new IllegalStateException("Remote bridge did not receive the framework callback proxy");
            return completion.get(10, TimeUnit.SECONDS);
        } catch (Exception | Error failure) {
            // Closing marks the receiver stale before asking the remote service to
            // drain. A late Binder delivery still owns and closes its own handle.
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Bundle awaitDrained() throws Exception {
        return awaitStatus(value -> !value.getBoolean("pending") && value.getInt("ownedHandles") == 0,
                8_000, "Capture request/handles did not drain");
    }
    public int ownedHandles() { return owned.get(); }
    public int closedHandles() { return closed.get(); }
    public int lateClosedHandles() { return lateClosed.get(); }
    public String receiverClassFromBridge() { return receiverClassFromBridge; }

    private static ResultReceiver frameworkProxy(ResultReceiver local) {
        Parcel parcel = Parcel.obtain();
        try {
            local.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    private Bundle awaitStatus(Predicate<Bundle> condition, long timeout, String reason) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        Bundle last;
        do {
            last = status();
            if (condition.test(last)) return last;
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(reason + ": " + last);
    }

    private Bundle extras() { Bundle value = new Bundle(); value.putString("nonce", nonce); return value; }
    private Bundle call(String method, Bundle extras) {
        // No adopted shell permission identity may survive into this IPC call.
        try {
            Bundle response = provider.call(method, null, extras);
            if (response == null) throw new IllegalStateException("Test bridge returned no status");
            return response;
        } catch (RemoteException failure) { throw new IllegalStateException("Test bridge process/connection failed", failure); }
    }
    private String readSetting(String key) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try { return Settings.Secure.getString(context.getContentResolver(), key); }
        finally { automation.dropShellPermissionIdentity(); }
    }
    private void writeSetting(String key, String value) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            if (!Settings.Secure.putString(context.getContentResolver(), key, value))
                throw new IllegalStateException("Could not restore/write test accessibility setting " + key);
            if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(key)
                    && !java.util.Objects.equals(value, Settings.Secure.getString(context.getContentResolver(), key)))
                throw new IllegalStateException("Accessibility service setting read-back differs");
        } finally { automation.dropShellPermissionIdentity(); }
    }

    @Override public void close() throws Exception {
        Throwable failure = null;
        synchronized (lock) {
            if (closing) return;
            closing = true;
            try { if (accepted != null) accepted.close(); }
            catch (Exception | Error error) { failure = error; }
            finally { if (pending != null) pending.cancel(false); }
        }
        try {
            if (opened) { call("end", extras()); awaitDrained(); }
        } catch (Exception | Error error) { failure = append(failure, error); }
        finally {
            try { writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices); }
            catch (Exception | Error error) { failure = append(failure, error); }
            finally {
                try { writeSetting(Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled); }
                catch (Exception | Error error) { failure = append(failure, error); }
            }
        }
        try {
            if (opened) awaitStatus(value -> !value.getBoolean("connected"), 8_000, "Test capture service did not disconnect");
            if (owned.get() != 0) throw new AssertionError("Receiver handles remain owned: " + owned.get());
            // Keep the local callback reachable through drain and disconnection;
            // failed cleanup retains it so a late result still closes its handle.
            if (failure == null) synchronized (lock) { localCallback = null; }
        } catch (Exception | Error error) { failure = append(failure, error); }
        finally {
            try { provider.close(); }
            catch (Exception | Error error) { failure = append(failure, error); }
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (Exception) failure;
    }
    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next); return first;
    }

    public final class Result implements AutoCloseable {
        public final int code;
        public final Bundle metadata;
        public final HardwareBuffer buffer;
        private final AtomicBoolean released = new AtomicBoolean();
        private Result(int code, Bundle metadata, HardwareBuffer buffer) {
            this.code = code; this.metadata = metadata; this.buffer = buffer;
            if (buffer != null) owned.incrementAndGet();
        }
        private void closeLate() { release(true); }
        @Override public void close() { release(false); }
        private void release(boolean late) {
            if (!released.compareAndSet(false, true) || buffer == null) return;
            try { buffer.close(); }
            finally {
                if (buffer.isClosed()) {
                    owned.decrementAndGet(); closed.incrementAndGet(); if (late) lateClosed.incrementAndGet();
                }
            }
        }
    }
}
