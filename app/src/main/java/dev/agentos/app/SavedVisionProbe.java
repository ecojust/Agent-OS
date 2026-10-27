package dev.agentos.app;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import dev.agentos.core.DisclosureGuard;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import dev.agentos.core.VisionModelProbe;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Internal diagnostic of one real saved configuration against a synthetic image.
 * It grants no screenshot, page, disclosure, device-action or general vision
 * capability. Only the explicit saved-configuration UI diagnostic calls it;
 * no Controller/tool route consumes this record, and nothing is persisted.
 */
final class SavedVisionProbe {
    private static final long FRESHNESS_MILLIS = 600_000;
    private static final State EMPTY = new State(null, null);
    private static final SavedVisionProbe PROCESS = new SavedVisionProbe(SystemClock::elapsedRealtime);

    enum Status { VERIFIED, UNVERIFIED, FAILED, BUSY }

    /** Describes this attempt only; it is never a reusable authorization/proof. */
    static final class Outcome {
        final Status status;
        final boolean connected;
        final String code;
        private Outcome(Status status, boolean connected, String code) {
            this.status = status; this.connected = connected; this.code = code;
        }
    }

    /** Minted only after the real algorithm and transport complete successfully. */
    static final class Verification {
        final long verifiedAtElapsedRealtimeMillis;
        final long expiresAtElapsedRealtimeMillis;
        private final SavedVisionProbe registry;
        private final Attempt original;
        private long lastChecked;

        private Verification(SavedVisionProbe registry, Attempt original, long now) {
            if (now < 0) throw new IllegalArgumentException("Invalid diagnostic clock");
            verifiedAtElapsedRealtimeMillis = now;
            expiresAtElapsedRealtimeMillis = Math.addExact(now, FRESHNESS_MILLIS);
            this.registry = registry; this.original = original;
            lastChecked = now;
        }

        /** The UI may retain only the record minted by its own original attempt. */
        boolean isCurrentFor(SettingsStore.ConfigurationLease expectedLease, RunControl originalControl,
                             long originalEpoch) {
            return original.control == originalControl && original.epoch == originalEpoch
                    && isCurrentFor(expectedLease);
        }

        /** Bounded memory/clock checks only: no decryption, View, Binder or network work. */
        boolean isCurrentFor(SettingsStore.ConfigurationLease expectedLease) {
            // A foreign lookup must not clear a newer/other saved configuration's record.
            if (expectedLease == null || expectedLease != original.configuration) return false;
            State publication = registry.state.get();
            if (publication.attempt != null || publication.verification != this) return false;
            boolean current;
            try {
                original.control.checkExecutionEpoch(original.epoch);
                current = SettingsStore.isCurrent(original.configuration) && timeIsCurrent();
            } catch (TaskControlException | RuntimeException stale) { current = false; }
            if (!current) {
                registry.state.compareAndSet(publication, EMPTY);
                return false;
            }
            return registry.state.get() == publication;
        }

        private synchronized boolean timeIsCurrent() {
            // Read the clock inside the same short monitor as its previous value:
            // concurrent callers must not mistake normal query reordering for a
            // clock rollback. Only the fixed production monotonic clock is read;
            // the independent test clock must likewise be nonblocking.
            long now = registry.elapsedRealtimeMillis.getAsLong();
            if (now < lastChecked || now < verifiedAtElapsedRealtimeMillis || now >= expiresAtElapsedRealtimeMillis)
                return false;
            lastChecked = now;
            return true;
        }
    }

    /** This small identity contains no Context, settings, key, image, response, or task text. */
    private static final class Attempt {
        final SettingsStore.ConfigurationLease configuration;
        final RunControl control;
        final long epoch;
        Attempt(SettingsStore.ConfigurationLease configuration, RunControl control, long epoch) {
            this.configuration = configuration; this.control = control; this.epoch = epoch;
        }
    }

    /** Admission and old-record invalidation are one indivisible publication. */
    private static final class State {
        final Attempt attempt;
        final Verification verification;
        State(Attempt attempt, Verification verification) {
            this.attempt = attempt; this.verification = verification;
        }
    }

    private final LongSupplier elapsedRealtimeMillis;
    private final AtomicReference<State> state = new AtomicReference<>(EMPTY);

    private SavedVisionProbe(LongSupplier clock) { elapsedRealtimeMillis = Objects.requireNonNull(clock); }
    static SavedVisionProbe process() { return PROCESS; }

    /** Independent registry and clock only. The actual saved store/probe/HTTP are not replaceable. */
    static SavedVisionProbe forTesting(LongSupplier elapsedRealtimeMillis) { return new SavedVisionProbe(elapsedRealtimeMillis); }

    Verification current(SettingsStore.ConfigurationLease expectedLease) {
        Verification record = state.get().verification;
        return record != null && record.isCurrentFor(expectedLease) ? record : null;
    }

    /**
     * Worker-only. Wrong thread/parameters fail before admission or storage. An
     * invalidated original epoch/configuration remains an interruption, not a
     * failed color test. Read failures use a fixed message without a cause/value.
     */
    Outcome test(Context context, SettingsStore.ConfigurationLease expectedLease,
                 RunControl control, long originalEpoch) throws IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Saved vision tests require a worker");
        if (context == null || expectedLease == null || control == null)
            throw new IllegalArgumentException("Saved configuration and original control are required");
        if (!expectedLease.profileId.equals(control.getProfileId()))
            throw new IllegalArgumentException("Saved configuration belongs to another profile");
        Attempt attempt = new Attempt(expectedLease, control, originalEpoch);
        checkOriginal(attempt);
        Context application = context.getApplicationContext();
        if (application == null) throw new IllegalArgumentException("Application context is required");
        State admitted = new State(attempt, null);
        for (;;) {
            State before = state.get();
            if (before.attempt != null) return new Outcome(Status.BUSY, false, "PROBE_BUSY");
            if (state.compareAndSet(before, admitted)) break;
            checkOriginal(attempt);
        }

        State publication = null;
        boolean returned = false;
        try {
            checkAttempt(admitted);
            SettingsStore.LoadedSettings loaded;
            try { loaded = new SettingsStore(application).loadWithLease(); }
            catch (IOException unreadable) {
                checkOriginal(attempt);
                throw new IOException("Unable to read the saved model configuration");
            }
            checkAttempt(admitted);
            if (loaded.lease != expectedLease || !loaded.settings.profileId.equals(expectedLease.profileId)) {
                stopOriginal(attempt);
                throw new IOException("Saved model configuration no longer matches");
            }
            // These credential-bearing values remain local to this real attempt;
            // the registry/guard/record never retain them or the probe's messages.
            ModelSettings model = new ModelSettings(loaded.settings.endpoint,
                    loaded.settings.apiKey, loaded.settings.model, loaded.settings.maxOutputTokens);
            DisclosureGuard guard = (owner, epoch) -> owner == attempt.control && epoch == attempt.epoch
                    && owner.getProfileId().equals(attempt.configuration.profileId)
                    && state.get() == admitted && SettingsStore.isCurrent(attempt.configuration);
            checkAttempt(admitted);
            VisionModelProbe.Result result = new VisionModelProbe().test(model, control, originalEpoch, guard);
            checkAttempt(admitted);
            boolean verified = result.connected && result.syntheticImageVerified;
            Outcome outcome = verified ? new Outcome(Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED")
                    : result.connected ? new Outcome(Status.UNVERIFIED, true, "SYNTHETIC_IMAGE_UNVERIFIED")
                    : new Outcome(Status.FAILED, false, "PROBE_FAILED");
            Verification record = null;
            if (verified) {
                try { record = new Verification(this, attempt, elapsedRealtimeMillis.getAsLong()); }
                catch (RuntimeException invalidClock) { throw new IOException("Unable to issue a current probe record"); }
            }
            checkAttempt(admitted);
            publication = new State(null, record);
            if (!state.compareAndSet(admitted, publication))
                throw new IOException("Probe attempt no longer owns its publication");
            // Late failure only clears this exact publication. A subsequent
            // attempt may already have atomically superseded it and is untouched.
            checkOriginal(attempt);
            try {
                if (record != null && !record.timeIsCurrent())
                    throw new IOException("Probe record expired before publication completed");
            } catch (RuntimeException invalidClock) {
                throw new IOException("Unable to check the probe record clock");
            }
            returned = true;
            return outcome;
        } finally {
            if (!returned && publication != null) state.compareAndSet(publication, EMPTY);
            state.compareAndSet(admitted, EMPTY);
        }
    }

    private void checkAttempt(State admitted) throws InterruptedException, IOException {
        checkOriginal(admitted.attempt);
        if (state.get() != admitted) throw new IOException("Probe attempt is no longer current");
    }

    private static void checkOriginal(Attempt original) throws InterruptedException {
        original.control.checkExecutionEpoch(original.epoch);
        if (!SettingsStore.isCurrent(original.configuration)) stopOriginal(original);
        original.control.checkExecutionEpoch(original.epoch);
    }

    private static void stopOriginal(Attempt original) throws InterruptedException {
        original.control.cancelIfExecutionEpoch(original.epoch);
        original.control.checkExecutionEpoch(original.epoch);
        throw new TaskControlException("TASK_STOPPED", "Saved model configuration has changed");
    }
}
