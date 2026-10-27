package dev.agentos.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One local disclosure review, with no capture, network, View or persistence capability.
 * The caller transfers PNG ownership at construction, including failed construction.
 * The trusted current-binding supplier is fixed at construction and must be local and
 * nonblocking. It is not a model-provided assertion or a substitute for platform capture
 * eligibility and final device guards, which a future production adapter must supply.
 * The host schedules expire() independently and calls invalidateHost() on loss. Task and
 * configuration owners should also invalidate promptly; every entry point rechecks them.
 * No RunControl cancel hook is installed or replaced.
 */
public final class ImageReviewSession implements AutoCloseable {
    public static final long TIMEOUT_MILLIS = 60_000L;
    public enum State {
        WAITING_HOST, PREPARING, PRESENTED, APPROVED, CONSUMED, REJECTED, EXPIRED, INVALIDATED, CLOSED
    }
    public enum Reason { NONE, HOST_LOST, CONTEXT_CHANGED, CLOCK_CHANGED, EXPIRED, REJECTED, CLOSED }
    public static final class HostToken { private HostToken() { } }
    public static final class ApprovalToken { private ApprovalToken() { } }

    /** The identity must come from the trusted installed-app grant, not a page or model. */
    public static final class AppIdentity {
        public final int uid;
        public final long firstInstallTime;
        public final Set<String> signerDigests;
        public AppIdentity(int uid, long firstInstallTime, Set<String> signerDigests) {
            if (uid < 0 || firstInstallTime <= 0 || signerDigests == null
                    || signerDigests.isEmpty() || signerDigests.size() > 32) throw invalid();
            LinkedHashSet<String> copy = new LinkedHashSet<>();
            for (String signer : signerDigests) {
                if (signer == null || !signer.matches("[0-9a-f]{64}")) throw invalid();
                copy.add(signer);
            }
            this.uid = uid; this.firstInstallTime = firstInstallTime;
            this.signerDigests = Collections.unmodifiableSet(copy);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof AppIdentity)) return false;
            AppIdentity value = (AppIdentity) other;
            return uid == value.uid && firstInstallTime == value.firstInstallTime
                    && signerDigests.equals(value.signerDigests);
        }
        @Override public int hashCode() { return Objects.hash(uid, firstInstallTime, signerDigests); }
    }

    /** Displayable connection identity only. API credentials never enter review metadata. */
    public static final class Recipient {
        public final String endpoint, model;
        public Recipient(String endpoint, String model) {
            ModelSettings settings = new ModelSettings(endpoint, "", model);
            settings.validate();
            this.endpoint = settings.completionUrl().toExternalForm(); this.model = settings.model;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Recipient)) return false;
            Recipient value = (Recipient) other;
            return endpoint.equals(value.endpoint) && model.equals(value.model);
        }
        @Override public int hashCode() { return Objects.hash(endpoint, model); }
    }

    /** Full immutable provenance; geometry and display are in observation, pixels in the PNG. */
    public static final class Binding {
        public final CoordinateNodeResolver.Binding observation;
        public final String callId, captureId, configurationToken;
        public final AppIdentity applicationIdentity;
        public final Recipient recipient;

        public Binding(CoordinateNodeResolver.Binding observation, String callId, String captureId,
                       AppIdentity applicationIdentity, Recipient recipient, String configurationToken) {
            if (observation == null || applicationIdentity == null || recipient == null) throw invalid();
            this.observation = observation;
            this.callId = identifier(callId, 128); this.captureId = identifier(captureId, 128);
            this.applicationIdentity = applicationIdentity; this.recipient = recipient;
            this.configurationToken = identifier(configurationToken, 128);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Binding)) return false;
            Binding value = (Binding) other;
            return observation.equals(value.observation) && callId.equals(value.callId)
                    && captureId.equals(value.captureId) && applicationIdentity.equals(value.applicationIdentity)
                    && recipient.equals(value.recipient) && configurationToken.equals(value.configurationToken);
        }
        @Override public int hashCode() {
            return Objects.hash(observation, callId, captureId, applicationIdentity, recipient, configurationToken);
        }
    }

    public final Binding binding;
    public final int width, height, encodedBytes;
    public final String imageSha256;
    private final RunControl control;
    private final Supplier<Binding> currentBinding;
    private final LongSupplier monotonicMillis;
    private final long startedAtMillis;
    private long lastClockMillis;
    private PngImage image;
    private HostToken host;
    private ApprovalToken approval;
    private State state = State.WAITING_HOST;
    private Reason reason = Reason.NONE;
    private boolean accountingWait;
    private boolean previewIssued;

    public ImageReviewSession(Binding binding, PngImage image, RunControl control,
                              Supplier<Binding> trustedCurrentBinding) {
        this(binding, image, control, trustedCurrentBinding, () -> System.nanoTime() / 1_000_000L);
    }

    /** The monotonic clock can be injected for deterministic deadline tests. */
    public ImageReviewSession(Binding binding, PngImage image, RunControl control,
                              Supplier<Binding> trustedCurrentBinding, LongSupplier monotonicMillis) {
        Objects.requireNonNull(image, "image");
        try {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.control = Objects.requireNonNull(control, "control");
            this.currentBinding = Objects.requireNonNull(trustedCurrentBinding, "currentBinding");
            this.monotonicMillis = Objects.requireNonNull(monotonicMillis, "monotonicMillis");
            if (image.isClosed()) throw invalid();
            width = image.width; height = image.height; encodedBytes = image.encodedBytes;
            imageSha256 = image.sha256; this.image = image;
            startedAtMillis = monotonicMillis.getAsLong(); lastClockMillis = startedAtMillis;
            if (checkCurrent()) {
                control.beginApprovalWait(); accountingWait = true;
                // A pause racing the first check cannot establish a fresh approval wait.
                checkCurrent();
            }
        } catch (RuntimeException | Error failure) {
            image.close();
            if (accountingWait) { accountingWait = false; control.endApprovalWait(); }
            throw failure;
        }
    }

    /** A session has one host for its entire lifetime; replacement requires a new review. */
    public synchronized HostToken claimHost() {
        if (!checkCurrent() || state != State.WAITING_HOST) return null;
        host = new HostToken(); state = State.PREPARING; return host;
    }

    /** Worker-only decode lease. The caller must close it even if review is invalidated meanwhile. */
    public synchronized PngImage.ReadLease openPreviewRead(HostToken token) {
        if (!checkCurrent() || token == null || token != host
                || state != State.PREPARING || previewIssued) return null;
        previewIssued = true;
        return image.openRead();
    }

    /** The secure host calls this only after successful decode, attach and actual layout. */
    public synchronized boolean markPresented(HostToken token) {
        if (!checkCurrent() || token == null || token != host || !previewIssued) return false;
        if (state == State.PRESENTED) return true;
        if (state != State.PREPARING) return false;
        state = State.PRESENTED; return true;
    }

    /** UI approval does not consume or transmit the PNG. Tokens cannot be fabricated by text approvals. */
    public synchronized ApprovalToken approve(HostToken token) {
        if (!checkCurrent() || token == null || token != host || state != State.PRESENTED) return null;
        approval = new ApprovalToken(); state = State.APPROVED; return approval;
    }

    public synchronized void reject(HostToken token) {
        if (!checkCurrent() || token == null || token != host || state == State.APPROVED) return;
        finish(State.REJECTED, Reason.REJECTED);
    }

    /**
     * Transfers the exact reviewed PNG once after context/epoch/deadline checks. The recipient
     * owns close() from here and must retain its ordinary final network/cancellation guards.
     * This permission alone neither authorizes capture nor proves a platform page is safe.
     */
    public synchronized PngImage consume(ApprovalToken token) {
        if (!checkCurrent() || token == null || token != approval || state != State.APPROVED) return null;
        PngImage transferred = image; image = null;
        finish(State.CONSUMED, Reason.NONE); return transferred;
    }

    public synchronized void invalidateHost(HostToken token) {
        if (token != null && token == host && active()) finish(State.INVALIDATED, Reason.HOST_LOST);
    }

    public synchronized void invalidate(Reason reason) {
        if (reason == null || reason == Reason.NONE) throw invalid();
        if (active()) finish(State.INVALIDATED, reason);
    }

    /** Called by the independently scheduled host deadline, also rechecks task/configuration. */
    public synchronized void expire() { checkCurrent(); }
    public synchronized State state() { checkCurrent(); return state; }
    public synchronized Reason reason() { checkCurrent(); return reason; }
    public synchronized long remainingMillis() {
        if (!checkCurrent()) return 0;
        return TIMEOUT_MILLIS - (lastClockMillis - startedAtMillis);
    }
    @Override public synchronized void close() { if (active()) finish(State.CLOSED, Reason.CLOSED); }

    private boolean checkCurrent() {
        if (!active()) return false;
        try {
            long now = monotonicMillis.getAsLong();
            if (now < lastClockMillis) { finish(State.INVALIDATED, Reason.CLOCK_CHANGED); return false; }
            lastClockMillis = now;
            long elapsed = now - startedAtMillis;
            if (elapsed < 0 || elapsed >= TIMEOUT_MILLIS) {
                finish(State.EXPIRED, Reason.EXPIRED); return false;
            }
            CoordinateNodeResolver.Binding observation = binding.observation;
            control.checkExecutionEpoch(observation.executionEpoch);
            if (image == null || image.isClosed() || !control.getTaskId().equals(observation.taskId)
                    || !control.getProfileId().equals(observation.profileId)
                    || !binding.equals(currentBinding.get())) {
                finish(State.INVALIDATED, Reason.CONTEXT_CHANGED); return false;
            }
            return true;
        } catch (TaskControlException | RuntimeException failedContext) {
            finish(State.INVALIDATED, Reason.CONTEXT_CHANGED); return false;
        }
    }

    private boolean active() {
        return state == State.WAITING_HOST || state == State.PREPARING
                || state == State.PRESENTED || state == State.APPROVED;
    }

    private void finish(State next, Reason why) {
        state = next; reason = why; host = null; approval = null;
        if (image != null) { image.close(); image = null; }
        if (accountingWait) { accountingWait = false; control.endApprovalWait(); }
    }

    private static String identifier(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum) throw invalid();
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw invalid();
        return value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("图片审阅绑定无效。"); }
}
