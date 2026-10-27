package dev.agentos.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import com.google.gson.JsonObject;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.RunControl;
import dev.agentos.core.ToolCatalog;
import dev.agentos.core.ToolRequest;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Internal raw-buffer lifecycle only. No tool, image disclosure, renderer, or PNG entry point. */
final class WindowBufferCapturer {
    private static final long REQUEST_MS = 5_000;
    private static final AtomicReference<CaptureRequest> SLOT = new AtomicReference<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread worker = new Thread(task, "agent-window-capture"); worker.setDaemon(true); return worker;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }
    private final AndroidDeviceTools tools;
    private final Backend backend;
    private final boolean platformBackend;

    interface Callback {
        /** Transfers the actual Java handle reference; no callback may keep using it afterward. */
        void success(HardwareBuffer buffer, ColorSpace colorSpace, long uptimeTimestamp);
        void failure(int platformCode);
    }
    @FunctionalInterface interface Backend { void submit(int windowId, Callback callback) throws Exception; }
    enum Failure { BUSY, TIMEOUT, ABANDONED, ALREADY_CLAIMED, INVALID_REQUEST, PLATFORM_UNAVAILABLE, PLATFORM_FAILURE,
        SUBMISSION_UNCERTAIN, BUDGET_EXCEEDED, INVALID_BUFFER, PROTOCOL_ERROR }
    // Fixed progress labels only. They never authorize work or replace lifecycle checks.
    enum Phase { QUEUED, PREPARING, RESERVING, SUBMISSION_VALIDATION, SUBMITTING, WAITING_CALLBACK,
        INSPECTING_BUFFER, POST_VALIDATING, READY, CLAIMED, CONVERSION_ADMISSION, COPYING, SCALING,
        ENCODING, CONVERSION_CLEANUP, RECEIPT }

    /** Immutable state at the original exception, not a later reconstruction of the platform. */
    static final class Diagnostic {
        final Phase phase;
        final long elapsedMs, phaseElapsedMs, remainingMs, permitRemainingMs;
        final boolean permitIssued, submissionEntered, submissionReturned, callbackObserved,
                callbackReceiving, workerDone, postValidationEntered, conversionEntered;
        private Diagnostic(Phase phase, long elapsedMs, long phaseElapsedMs, long remainingMs,
                           long permitRemainingMs, boolean permitIssued, boolean submissionEntered,
                           boolean submissionReturned, boolean callbackObserved, boolean callbackReceiving,
                           boolean workerDone, boolean postValidationEntered, boolean conversionEntered) {
            this.phase = phase; this.elapsedMs = elapsedMs; this.phaseElapsedMs = phaseElapsedMs;
            this.remainingMs = remainingMs; this.permitRemainingMs = permitRemainingMs;
            this.permitIssued = permitIssued; this.submissionEntered = submissionEntered;
            this.submissionReturned = submissionReturned; this.callbackObserved = callbackObserved;
            this.callbackReceiving = callbackReceiving; this.workerDone = workerDone;
            this.postValidationEntered = postValidationEntered; this.conversionEntered = conversionEntered;
        }
        // A new fixed-shape object each time: no request/configuration IDs, geometry, text or pixels.
        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("phase", phase.name()); result.addProperty("elapsedMs", elapsedMs);
            result.addProperty("phaseElapsedMs", phaseElapsedMs); result.addProperty("requestLimitMs", REQUEST_MS);
            result.addProperty("remainingMs", remainingMs); result.addProperty("permitRemainingMs", permitRemainingMs);
            result.addProperty("permitIssued", permitIssued); result.addProperty("submissionEntered", submissionEntered);
            result.addProperty("submissionReturned", submissionReturned); result.addProperty("callbackObserved", callbackObserved);
            result.addProperty("callbackReceiving", callbackReceiving); result.addProperty("workerDone", workerDone);
            result.addProperty("postValidationEntered", postValidationEntered); result.addProperty("conversionEntered", conversionEntered);
            return result;
        }
    }
    static final class CaptureException extends Exception {
        final Failure code;
        final Diagnostic diagnostic;
        CaptureException(Failure code) { this(code, (Diagnostic) null); }
        CaptureException(Failure code, Throwable cause) { this(code, cause, null); }
        private CaptureException(Failure code, Diagnostic diagnostic) {
            super("Internal window capture failed: " + code); this.code = code; this.diagnostic = diagnostic;
        }
        private CaptureException(Failure code, Throwable cause, Diagnostic diagnostic) {
            super("Internal window capture failed: " + code, cause); this.code = code; this.diagnostic = diagnostic;
        }
    }
    WindowBufferCapturer(AndroidDeviceTools tools) {
        this.tools = Objects.requireNonNull(tools);
        platformBackend = true;
        AgentAccessibilityService service = tools.windowCaptureService();
        backend = (id, callback) -> {
            if (Build.VERSION.SDK_INT >= 34) Api34.submit(service, id, callback);
            else throw new IllegalStateException("Window capture entered on an unsupported API");
        };
    }
    private WindowBufferCapturer(AndroidDeviceTools tools, Backend backend) {
        this.tools = Objects.requireNonNull(tools); this.backend = Objects.requireNonNull(backend);
        platformBackend = false;
    }
    /** Synthetic protocol testing only; this does not replace or grant production capabilities. */
    static WindowBufferCapturer forTesting(AndroidDeviceTools tools, Backend backend) {
        return new WindowBufferCapturer(tools, backend);
    }
    static boolean hasActiveCapture() { return SLOT.get() != null; }

    private void requirePlatformCapability(CaptureRequest request) throws CaptureException {
        // Called under B1's device gate but before submissionEntered. This is
        // the fixed production backend's check; test backends cannot fake it.
        AgentAccessibilityService service = tools.windowCaptureService();
        try {
            AccessibilityServiceInfo info = service.getServiceInfo();
            if (Build.VERSION.SDK_INT < 34 || !service.isConnected() || info == null
                    || (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) == 0)
                throw request.captureFailure(Failure.PLATFORM_UNAVAILABLE);
        } catch (RuntimeException unavailable) { throw request.captureFailure(Failure.PLATFORM_UNAVAILABLE, unavailable); }
    }

    CaptureRequest start(RunControl owner, long fixedEpoch, String expectedRevision) throws Exception {
        // Legacy raw/conversion tests have no original call identity. They can
        // never be upgraded later into a typed captured-PNG receipt.
        return startInternal(owner, fixedEpoch, expectedRevision, null);
    }

    CaptureRequest start(ToolRequest original, RunControl owner) throws Exception {
        if (original == null || owner == null || original.protocolVersion != ToolCatalog.PROTOCOL_VERSION
                || !"take_screenshot".equals(original.tool) || original.callId == null
                || !original.callId.matches("[A-Za-z0-9_.:-]{1,128}")
                || !owner.getTaskId().equals(original.taskId) || !owner.getProfileId().equals(original.profileId))
            throw new CaptureException(Failure.INVALID_REQUEST);
        com.google.gson.JsonObject arguments = original.getArguments();
        try { ToolCatalog.validate(original.tool, arguments); }
        catch (IllegalArgumentException invalid) { throw new CaptureException(Failure.INVALID_REQUEST, invalid); }
        // ToolRequest is immutable and returns defensive argument copies. Retain
        // this original identity from entry; conversion cannot supply a call ID.
        return startInternal(owner, original.executionEpoch, arguments.get("expectedRevision").getAsString(), original);
    }

    private CaptureRequest startInternal(RunControl owner, long fixedEpoch, String expectedRevision,
                                         ToolRequest original) throws Exception {
        tools.checkWindowCaptureRequestCurrent(owner, fixedEpoch);
        CaptureRequest request = new CaptureRequest(owner, fixedEpoch, expectedRevision, original);
        if (!SLOT.compareAndSet(null, request)) throw request.captureFailure(Failure.BUSY);
        try { WORKER.execute(request::run); }
        catch (RuntimeException | Error rejected) {
            request.fail(rejected);
            synchronized (request.lock) { request.workerDone = true; request.submissionReturned = true; }
            request.cleanup();
            throw rejected;
        }
        return request;
    }

    @TargetApi(34) private static final class Api34 {
        static void submit(AgentAccessibilityService service, int windowId, Callback callback) {
            // Never replace this executor with a rejecting/shutdown worker pool:
            // the framework constructs the native buffer before invoking it.
            service.takeScreenshotOfWindow(windowId, Runnable::run, new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    HardwareBuffer buffer;
                    try { buffer = result.getHardwareBuffer(); }
                    catch (RuntimeException absent) { callback.failure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR); return; }
                    ColorSpace space;
                    long timestamp;
                    try { space = result.getColorSpace(); timestamp = result.getTimestamp(); }
                    catch (RuntimeException | Error metadataFailure) {
                        // Transfer even invalid metadata to the normal owner so a
                        // close failure retains its reservation/slot. Never close
                        // this handle again after Callback.success takes ownership.
                        callback.success(buffer, null, -1);
                        if (metadataFailure instanceof Error) throw (Error) metadataFailure;
                        return;
                    }
                    callback.success(buffer, space, timestamp);
                }
                @Override public void onFailure(int code) { callback.failure(code); }
            });
        }
    }

    final class CaptureRequest implements AutoCloseable {
        private final Object lock = new Object();
        private final RunControl control;
        private final long epoch, requestStarted = SystemClock.elapsedRealtime(), requestDeadline = requestStarted + REQUEST_MS;
        private Phase phase = Phase.QUEUED;
        private long phaseStarted = requestStarted;
        private boolean postValidationEntered, conversionEntered;
        private final String revision;
        private final ToolRequest originalRequest;
        private volatile AndroidDeviceTools.WindowCapturePermit permit;
        private boolean abandoned, workerDone, submissionEntered, submissionReturned;
        private boolean receiving, resourceSettled = true, claimed, conversionHeld;
        private ConversionResources conversionResources;
        private int terminal; // 0 = awaiting native, 1 = success, 2 = failure.
        private int platformCode, duplicates, extraClosers;
        private boolean unclosedExtra;
        // First failed duplicate retained for diagnosis; any such protocol/close
        // failure keeps this slot permanently draining. Further failed duplicates
        // are not kept in an unbounded list and never count as successful cleanup.
        private HardwareBuffer failedExtra;
        private Throwable failure;
        private HardwareBuffer primary;
        private ColorSpace colorSpace;
        private long timestamp;
        private long submissionUptime, callbackUptime;
        private ImageWorkset.Reservation capacity;
        private OwnedWindowBuffer owned;
        private CapturedWindow ready;

        private CaptureRequest(RunControl control, long epoch, String revision, ToolRequest originalRequest) {
            this.control = control; this.epoch = epoch; this.revision = revision;
            this.originalRequest = originalRequest;
        }

        private void recordPhase(Phase next) {
            synchronized (lock) { phase = next; phaseStarted = SystemClock.elapsedRealtime(); }
        }
        private CaptureException captureFailure(Failure code) { return new CaptureException(code, failureSnapshot()); }
        private CaptureException captureFailure(Failure code, Throwable cause) {
            return new CaptureException(code, cause, failureSnapshot());
        }
        private Diagnostic failureSnapshot() {
            synchronized (lock) {
                long now = SystemClock.elapsedRealtime();
                AndroidDeviceTools.WindowCapturePermit current = permit;
                return new Diagnostic(phase, Math.max(0, now - requestStarted),
                        Math.max(0, now - phaseStarted), Math.max(0, requestDeadline - now),
                        current == null ? -1 : Math.max(0, current.deadlineElapsedRealtimeMs - now),
                        current != null, submissionEntered, submissionReturned, terminal != 0, receiving,
                        workerDone, postValidationEntered, conversionEntered);
            }
        }

        private void checkCurrent() throws Exception {
            tools.checkWindowCaptureRequestCurrent(control, epoch);
            if (SystemClock.elapsedRealtime() >= requestDeadline) throw captureFailure(Failure.TIMEOUT);
            AndroidDeviceTools.WindowCapturePermit current = permit;
            if (current != null) tools.checkWindowCaptureCurrent(current, control, epoch);
        }

        private void run() {
            try {
                recordPhase(Phase.PREPARING);
                checkCurrent(); checkNotAbandoned();
                permit = tools.prepareWindowCapture(control, epoch, revision);
                checkCurrent(); checkNotAbandoned();
                recordPhase(Phase.RESERVING);
                Rect bounds = permit.copyWindowBounds();
                long bytes = Math.multiplyExact(Math.multiplyExact((long) bounds.width(), bounds.height()), 8L);
                if (bytes <= 0) throw captureFailure(Failure.INVALID_BUFFER);
                ImageWorkset.Reservation reserved;
                try { reserved = ImageWorkset.process().reserve(bytes); }
                catch (IllegalStateException exhausted) { throw captureFailure(Failure.BUDGET_EXCEEDED, exhausted); }
                synchronized (lock) { capacity = reserved; resourceSettled = false; }
                checkCurrent(); checkNotAbandoned();
                try {
                    recordPhase(Phase.SUBMISSION_VALIDATION);
                    tools.submitWindowCapture(permit, control, epoch, id -> {
                        if (platformBackend) requirePlatformCapability(this);
                        // Capability lookup above may use Binder. Recheck the
                        // original epoch/configuration after it actually returns.
                        checkCurrent(); checkNotAbandoned();
                        synchronized (lock) {
                            submissionEntered = true; submissionUptime = SystemClock.uptimeMillis();
                            recordPhase(Phase.SUBMITTING);
                        }
                        backend.submit(id, new Callback() {
                            @Override public void success(HardwareBuffer buffer, ColorSpace space, long time) { received(buffer, space, time); }
                            @Override public void failure(int code) { nativeFailure(code); }
                        });
                    });
                } catch (Exception failure) {
                    boolean entered;
                    synchronized (lock) { entered = submissionEntered; }
                    if (entered) throw captureFailure(Failure.SUBMISSION_UNCERTAIN, failure);
                    throw failure;
                } finally {
                    synchronized (lock) { submissionReturned = true; lock.notifyAll(); }
                }
                recordPhase(Phase.WAITING_CALLBACK);
                OwnedWindowBuffer source = awaitNative();
                recordPhase(Phase.INSPECTING_BUFFER);
                CapturedWindow result;
                try (OwnedWindowBuffer.ReadLease reader = source.openRead()) {
                    checkCurrent(); checkNotAbandoned();
                    HardwareBuffer buffer = reader.buffer();
                    Metadata metadata = inspect(buffer);
                    // This worker resumes after submission returned; it does not
                    // queue post-validation behind its own blocking await.
                    synchronized (lock) { postValidationEntered = true; recordPhase(Phase.POST_VALIDATING); }
                    tools.validateWindowCaptureResult(permit, control, epoch);
                    checkCurrent(); checkNotAbandoned();
                    result = new CapturedWindow(this, permit, control, epoch, source, metadata,
                            Math.min(requestDeadline, permit.deadlineElapsedRealtimeMs));
                }
                // A claimed result must not retain our validator's read lease:
                // an immediate converter may copy and release the raw owner.
                synchronized (lock) {
                    if (abandoned || failure != null) throw captureFailure(Failure.ABANDONED);
                    checkCurrent();
                    recordPhase(Phase.READY);
                    ready = result; lock.notifyAll();
                }
            } catch (Exception failed) { fail(failed); }
            catch (Error failed) { fail(failed); throw failed; }
            finally {
                synchronized (lock) { workerDone = true; if (!submissionEntered) submissionReturned = true; lock.notifyAll(); }
                cleanup();
            }
        }

        private OwnedWindowBuffer awaitNative() throws Exception {
            for (;;) {
                checkCurrent(); checkNotAbandoned();
                synchronized (lock) {
                    if (failure != null) throwFailure(failure);
                    if (terminal == 2) throw captureFailure(Failure.PLATFORM_FAILURE,
                            new IllegalStateException("Platform capture code " + platformCode));
                    if (terminal == 1 && !receiving && owned != null) return owned;
                    lock.wait(40);
                }
            }
        }

        private Metadata inspect(HardwareBuffer buffer) throws Exception {
            Rect expected = permit.copyWindowBounds();
            int width = buffer.getWidth(), height = buffer.getHeight(), format = buffer.getFormat(), layers = buffer.getLayers();
            long usage = buffer.getUsage();
            int pixelBytes = format == HardwareBuffer.RGBA_8888 ? 4 : format == HardwareBuffer.RGBA_FP16 ? 8 : 0;
            ColorSpace actualSpace;
            long actualTime;
            long before, receivedAt;
            synchronized (lock) { actualSpace = colorSpace; actualTime = timestamp; before = submissionUptime; receivedAt = callbackUptime; }
            if (buffer.isClosed() || width != expected.width() || height != expected.height() || layers != 1 || pixelBytes == 0
                    || (usage & HardwareBuffer.USAGE_PROTECTED_CONTENT) != 0 || actualSpace == null
                    || actualSpace.getModel() != ColorSpace.Model.RGB
                    || actualTime < before || actualTime > receivedAt
                    || Math.multiplyExact(Math.multiplyExact((long) width, height), pixelBytes) > (long) expected.width() * expected.height() * 8)
                throw captureFailure(Failure.INVALID_BUFFER);
            return new Metadata(width, height, format, layers, usage, actualSpace, actualTime);
        }

        private void received(HardwareBuffer buffer, ColorSpace space, long time) {
            long receivedAt = SystemClock.uptimeMillis();
            if (buffer == null) { nativeFailure(-1); return; }
            ImageWorkset.Reservation reservation;
            boolean extra;
            synchronized (lock) {
                if (buffer == primary) { duplicates = Math.min(1_000, duplicates + 1); return; }
                extra = terminal != 0;
                if (extra) { duplicates = Math.min(1_000, duplicates + 1); extraClosers++; reservation = null; }
                else {
                    primary = buffer; terminal = 1; receiving = true;
                    colorSpace = space; timestamp = time; callbackUptime = receivedAt; reservation = capacity;
                }
            }
            if (extra) { closeExtra(buffer); return; }
            OwnedWindowBuffer owner = null;
            try {
                owner = new OwnedWindowBuffer(buffer, reservation, this::actuallyClosed);
                synchronized (lock) { owned = owner; capacity = null; }
                // No target snapshot, View, Binder operation, or cancel hook here.
                checkCurrent();
            } catch (Exception failed) {
                fail(failed);
                if (owner == null) closeFailedConstruction(buffer, reservation);
            } catch (Error failed) {
                fail(failed);
                if (owner == null) closeFailedConstruction(buffer, reservation);
                throw failed;
            } finally {
                synchronized (lock) { receiving = false; lock.notifyAll(); }
                cleanup();
            }
        }

        private void nativeFailure(int code) {
            synchronized (lock) {
                if (terminal != 0) { duplicates = Math.min(1_000, duplicates + 1); return; }
                terminal = 2; platformCode = code; lock.notifyAll();
            }
            cleanup();
        }

        private void closeExtra(HardwareBuffer extra) {
            boolean confirmed = false;
            try { extra.close(); confirmed = extra.isClosed(); }
            catch (RuntimeException | Error closeFailure) {
                fail(closeFailure); confirmed = extra.isClosed();
            } finally {
                synchronized (lock) {
                    extraClosers--;
                    if (!confirmed) { unclosedExtra = true; if (failedExtra == null) failedExtra = extra; }
                }
                if (!confirmed) fail(captureFailure(Failure.PROTOCOL_ERROR));
                tryReleaseSlot();
            }
        }

        private void closeFailedConstruction(HardwareBuffer buffer, ImageWorkset.Reservation reservation) {
            boolean confirmed = false;
            try { buffer.close(); confirmed = buffer.isClosed(); }
            catch (RuntimeException | Error closeFailure) { fail(closeFailure); confirmed = buffer.isClosed(); }
            if (confirmed && reservation != null) {
                reservation.close();
                synchronized (lock) { capacity = null; resourceSettled = true; }
            }
        }

        private void actuallyClosed() {
            synchronized (lock) { resourceSettled = true; lock.notifyAll(); }
            tryReleaseSlot();
        }

        CapturedWindow awaitAndClaim() throws Exception {
            requireWorker();
            try {
                for (;;) {
                    checkCurrent();
                    synchronized (lock) {
                        if (claimed) throw captureFailure(Failure.ALREADY_CLAIMED);
                        if (failure != null) throwFailure(failure);
                        if (abandoned) throw captureFailure(Failure.ABANDONED);
                        if (ready != null) {
                            checkCurrent(); claimed = true; recordPhase(Phase.CLAIMED); return ready;
                        }
                        lock.wait(40);
                    }
                }
            } catch (Exception | Error failed) {
                synchronized (lock) { if (claimed) throw failed; }
                fail(failed); cleanup(); throw failed;
            }
        }

        private void checkNotAbandoned() throws CaptureException {
            synchronized (lock) { if (abandoned) throw captureFailure(Failure.ABANDONED); }
        }
        private void fail(Throwable failed) {
            synchronized (lock) { if (failure == null) failure = failed; abandoned = true; lock.notifyAll(); }
        }
        @Override public void close() {
            synchronized (lock) { abandoned = true; lock.notifyAll(); }
            cleanup();
        }

        private void cleanup() {
            OwnedWindowBuffer closeOwner;
            ImageWorkset.Reservation closeCapacity = null;
            synchronized (lock) {
                closeOwner = !claimed && (abandoned || failure != null) ? owned : null;
                if (workerDone && submissionReturned && (!submissionEntered || terminal == 2)
                        && !receiving && owned == null && primary == null && capacity != null) {
                    closeCapacity = capacity; capacity = null;
                }
            }
            if (closeOwner != null) {
                try { closeOwner.close(); }
                catch (RuntimeException | Error closeFailure) { fail(closeFailure); }
            }
            if (closeCapacity != null) {
                closeCapacity.close();
                synchronized (lock) { resourceSettled = true; }
            }
            tryReleaseSlot();
        }

        private void tryReleaseSlot() {
            synchronized (lock) {
                if (workerDone && submissionReturned && (!submissionEntered || terminal != 0)
                        && !receiving && resourceSettled && !conversionHeld && extraClosers == 0 && !unclosedExtra)
                    SLOT.compareAndSet(this, null);
            }
        }
        boolean isWorkerDone() { synchronized (lock) { return workerDone; } }
        boolean hasSubmissionReturned() { synchronized (lock) { return submissionReturned; } }
        int duplicateCallbacks() { synchronized (lock) { return duplicates; } }
    }

    static final class Metadata {
        final int width, height, format, layers;
        final long usage, uptimeTimestamp;
        final ColorSpace colorSpace;
        Metadata(int width, int height, int format, int layers, long usage, ColorSpace colorSpace, long timestamp) {
            this.width = width; this.height = height; this.format = format; this.layers = layers;
            this.usage = usage; this.colorSpace = colorSpace; uptimeTimestamp = timestamp;
        }
    }

    final class CapturedWindow implements AutoCloseable {
        final AndroidDeviceTools.WindowCapturePermit permit;
        final Metadata metadata;
        private final RunControl control;
        private final long epoch;
        private final long deadline;
        private final OwnedWindowBuffer owned;
        private final CaptureRequest request;
        private boolean conversionStarted;
        private CapturedWindow(CaptureRequest request, AndroidDeviceTools.WindowCapturePermit permit, RunControl control, long epoch,
                       OwnedWindowBuffer owned, Metadata metadata, long deadline) {
            this.permit = permit; this.control = control; this.epoch = epoch; this.owned = owned; this.metadata = metadata;
            this.deadline = deadline; this.request = request;
        }
        ConversionHold beginConversion() throws Exception {
            requireWorker(); checkReadable();
            synchronized (request.lock) {
                if (conversionStarted || owned.isClosed() || !request.claimed || request.owned != owned)
                    throw new IllegalStateException("Captured window cannot enter another conversion.");
                ConversionHold hold = new ConversionHold(request);
                conversionStarted = true; request.conversionHeld = true;
                request.conversionEntered = true; request.recordPhase(Phase.CONVERSION_ADMISSION);
                return hold;
            }
        }
        // Called only by the existing converter after it has acquired its conversion hold.
        // This records progress; it does not renew the deadline or change ownership.
        void recordConversionPhase(Phase next) { request.recordPhase(next); }

        /** Raw may already be closed during encoding; the original context/deadline still applies. */
        void checkConversionCurrent() throws Exception { requireWorker(); checkReadable(); }

        ToolRequest originalRequest() { return request.originalRequest; }
        boolean isPlatformCapture() { return platformBackend; }
        long captureDeadlineElapsedRealtimeMillis() { return deadline; }

        OwnedWindowBuffer.ReadLease openRead() throws Exception {
            requireWorker();
            OwnedWindowBuffer.ReadLease reader = null;
            try {
                checkReadable();
                reader = owned.openRead();
                checkReadable();
                return reader;
            } catch (Exception | Error failure) {
                try { if (reader != null) reader.close(); }
                finally { owned.close(); }
                throw failure;
            }
        }
        private void checkReadable() throws Exception {
            tools.checkWindowCaptureCurrent(permit, control, epoch);
            if (SystemClock.elapsedRealtime() >= deadline) throw request.captureFailure(Failure.TIMEOUT);
        }
        @Override public void close() { owned.close(); }
        boolean isActuallyClosed() { return owned.isActuallyClosed(); }
    }

    /** Finite cleanup state; retained by the original slot on an unconfirmed native/software close. */
    interface ConversionResources { boolean isCleanupComplete(); }

    /** One conversion continues to own the original slot after its native raw reference closes. */
    final class ConversionHold implements AutoCloseable {
        private final CaptureRequest request;
        private boolean closed;
        private ConversionHold(CaptureRequest request) { this.request = request; }
        void retainUntilClosed(ConversionResources resources) {
            if (resources == null) throw new NullPointerException("resources");
            synchronized (request.lock) {
                if (closed || request.conversionResources != null)
                    throw new IllegalStateException("Conversion resource record is already bound or closed.");
                request.conversionResources = resources;
            }
        }
        @Override public void close() {
            synchronized (request.lock) {
                if (closed) return;
                if (request.conversionResources != null && !request.conversionResources.isCleanupComplete())
                    throw new IllegalStateException("Conversion resources are still owned.");
                closed = true; request.conversionHeld = false; request.conversionResources = null;
            }
            request.tryReleaseSlot();
        }
    }

    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Raw window images must stay on a worker");
    }
    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        throw new IllegalStateException(failure);
    }
}
