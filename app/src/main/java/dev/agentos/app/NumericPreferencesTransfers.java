package dev.agentos.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import dev.agentos.core.NumericPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-local ownership and a separate, nonqueued numeric-preference file worker. */
final class NumericPreferencesTransfers {
    static final int REQUEST_CODE = 4101;
    static final String EXTRA_NONCE = "dev.agentos.app.extra.NUMERIC_PREFERENCES_NONCE";

    enum Mode { IMPORT, EXPORT }
    enum Outcome { IMPORTED, EXPORTED, CANCELLED, FAILED }
    enum Failure { NONE, INVALID_URI, BUSY, INVALID_FILE, IO_FAILED, CONTEXT_CHANGED, UNAVAILABLE }
    private enum Phase { CREATED, CLAIMED, WORKING, READY, RETURNING, REVOKED, CONSUMED }

    static final class Owner {
        final String nonce = UUID.randomUUID().toString();
        final Mode mode;
        final String profileId;
        final long configurationGeneration;
        final AgentController.TaskInputState taskInput;
        final NumericPreferences exportPreferences;
        private Phase phase = Phase.CREATED;
        private Result result;

        private Owner(Mode mode, String profileId, long configurationGeneration,
                      AgentController.TaskInputState taskInput, NumericPreferences exportPreferences) {
            this.mode = mode; this.profileId = profileId; this.configurationGeneration = configurationGeneration;
            this.taskInput = taskInput; this.exportPreferences = exportPreferences;
        }
    }

    static final class Result {
        final Outcome outcome;
        final NumericPreferences preferences;
        final Failure failure;
        private Result(Outcome outcome, NumericPreferences preferences, Failure failure) {
            this.outcome = outcome; this.preferences = preferences; this.failure = failure;
        }
        private static Result failed(Failure failure) { return new Result(Outcome.FAILED, null, failure); }
    }

    interface Callback { void onReady(Owner owner); }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Owner current;
    private static Object ioSlot;
    private static final ThreadPoolExecutor IO_WORKER = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "agent-os-numeric-preferences");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private NumericPreferencesTransfers() { }

    static Owner create(Mode mode, String profileId, long configurationGeneration,
                        AgentController.TaskInputState taskInput, NumericPreferences exportPreferences) {
        if (mode == null || profileId == null || profileId.isEmpty() || profileId.length() > 128
                || taskInput == null || taskInput.running
                || (mode == Mode.EXPORT) != (exportPreferences != null))
            throw new IllegalArgumentException("数值偏好操作状态无效。");
        synchronized (LOCK) {
            if (current != null || ioSlot != null) return null;
            current = new Owner(mode, profileId, configurationGeneration, taskInput, exportPreferences);
            return current;
        }
    }

    /** The broker may claim a nonce once; process recreation cannot reconstruct this registry. */
    static Owner claimBroker(String nonce) {
        synchronized (LOCK) {
            if (current == null || current.phase != Phase.CREATED || !current.nonce.equals(nonce)) return null;
            current.phase = Phase.CLAIMED; return current;
        }
    }

    static boolean isCurrent(Owner owner) { synchronized (LOCK) { return owner != null && current == owner; } }
    static boolean isReturning(Owner owner) {
        synchronized (LOCK) { return owner != null && current == owner && owner.phase == Phase.RETURNING; }
    }
    static boolean isWorkerBusy() { synchronized (LOCK) { return ioSlot != null; } }

    /** Revocation never closes a provider, interrupts a worker, or releases its actual I/O slot. */
    static void revoke(Owner owner) {
        synchronized (LOCK) {
            if (owner == null || current != owner) return;
            owner.phase = Phase.REVOKED; owner.result = null; current = null;
        }
    }

    static Result peekResult(Owner owner) {
        synchronized (LOCK) {
            return owner != null && current == owner && (owner.phase == Phase.READY || owner.phase == Phase.RETURNING)
                    ? owner.result : null;
        }
    }

    /** For IMPORT, only the broker's explicit preview confirmation calls this transition. */
    static boolean markReturning(Owner owner) {
        synchronized (LOCK) {
            if (owner == null || current != owner || owner.phase != Phase.READY || owner.result == null) return false;
            owner.phase = Phase.RETURNING; return true;
        }
    }

    static boolean cancelFromBroker(Owner owner) {
        synchronized (LOCK) {
            if (owner == null || current != owner || owner.phase == Phase.CREATED || owner.phase == Phase.RETURNING) return false;
            owner.result = new Result(Outcome.CANCELLED, null, Failure.NONE);
            owner.phase = Phase.RETURNING; return true;
        }
    }

    static boolean failFromBroker(Owner owner, Failure failure) {
        if (failure == null || failure == Failure.NONE) throw new IllegalArgumentException("数值偏好结果无效。");
        synchronized (LOCK) {
            if (owner == null || current != owner || owner.phase != Phase.CLAIMED) return false;
            owner.result = Result.failed(failure); owner.phase = Phase.READY; return true;
        }
    }

    /** Intent extras cannot supply the values or grant authority to consume an unfinished owner. */
    static Result consume(Owner owner) {
        synchronized (LOCK) {
            if (owner == null || current != owner || owner.phase != Phase.RETURNING) return null;
            Result result = owner.result;
            owner.result = null; owner.phase = Phase.CONSUMED; current = null;
            return result;
        }
    }

    /**
     * A false return has no queued work: a current owner's fixed error is available via peekResult.
     * The caller must retain its Callback; neither this worker nor its main-thread post retains an Activity.
     */
    static boolean startIo(Context context, Owner owner, Uri uri, Callback callback) {
        Context application = context == null ? null : context.getApplicationContext();
        final Object slot;
        synchronized (LOCK) {
            if (owner == null || current != owner || owner.phase != Phase.CLAIMED) return false;
            Failure invalid = application == null || callback == null ? Failure.UNAVAILABLE
                    : uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null || uri.getAuthority().isEmpty()
                    ? Failure.INVALID_URI : ioSlot != null ? Failure.BUSY : Failure.NONE;
            if (invalid != Failure.NONE) {
                owner.result = Result.failed(invalid); owner.phase = Phase.READY; return false;
            }
            slot = new Object(); ioSlot = slot; owner.phase = Phase.WORKING;
        }
        WeakReference<Callback> completion = new WeakReference<>(callback);
        try {
            IO_WORKER.execute(() -> transfer(application, owner, uri, completion, slot));
            return true;
        } catch (RejectedExecutionException rejected) {
            synchronized (LOCK) {
                // No Runnable was accepted; only this exact reservation can be released.
                if (ioSlot == slot) ioSlot = null;
                if (current == owner && owner.phase == Phase.WORKING) {
                    owner.result = Result.failed(Failure.BUSY); owner.phase = Phase.READY;
                }
            }
            return false;
        }
    }

    private static void transfer(Context application, Owner owner, Uri uri, WeakReference<Callback> completion, Object slot) {
        Result result = Result.failed(Failure.IO_FAILED);
        try {
            requireWorkerOwner(application, owner);
            if (owner.mode == Mode.IMPORT) {
                byte[] input = new byte[NumericPreferences.MAX_ENCODED_BYTES + 1];
                int count = 0;
                try (InputStream stream = application.getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IOException();
                    while (count < input.length) {
                        requireWorkerOwner(application, owner);
                        int read = stream.read(input, count, input.length - count);
                        if (read < 0) break;
                        if (read == 0) {
                            requireWorkerOwner(application, owner);
                            int one = stream.read();
                            if (one < 0) break;
                            input[count++] = (byte) one;
                        } else count += read;
                    }
                    requireWorkerOwner(application, owner);
                    if (count > NumericPreferences.MAX_ENCODED_BYTES) throw new InvalidFile();
                }
                requireWorkerOwner(application, owner);
                NumericPreferences preferences;
                try { preferences = NumericPreferences.decode(Arrays.copyOf(input, count)); }
                catch (IllegalArgumentException invalid) { throw new InvalidFile(); }
                requireWorkerOwner(application, owner);
                result = new Result(Outcome.IMPORTED, preferences, Failure.NONE);
            } else {
                byte[] output = owner.exportPreferences.encode();
                requireWorkerOwner(application, owner);
                try (OutputStream stream = application.getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) throw new IOException();
                    requireWorkerOwner(application, owner);
                    stream.write(output);
                    requireWorkerOwner(application, owner);
                }
                // Close is part of completion. Cancellation cannot undo bytes a
                // provider already accepted and does not guarantee its I/O stops.
                requireWorkerOwner(application, owner);
                result = new Result(Outcome.EXPORTED, null, Failure.NONE);
            }
        } catch (ChangedOwner changed) { result = Result.failed(Failure.CONTEXT_CHANGED); }
        catch (InvalidFile invalid) { result = Result.failed(Failure.INVALID_FILE); }
        catch (IOException | RuntimeException unavailable) { result = Result.failed(Failure.IO_FAILED); }
        finally {
            synchronized (LOCK) {
                // A cancelled/consumed A can neither publish into B nor release B's slot.
                if (current == owner && owner.phase == Phase.WORKING) {
                    owner.result = result; owner.phase = Phase.READY;
                }
                if (ioSlot == slot) ioSlot = null;
            }
        }
        MAIN.post(() -> {
            if (!isCurrent(owner) || peekResult(owner) == null || isReturning(owner)) return;
            Callback callback = completion.get();
            if (callback != null) callback.onReady(owner);
        });
    }

    private static void requireWorkerOwner(Context application, Owner owner) throws ChangedOwner {
        synchronized (LOCK) {
            if (current != owner || owner.phase != Phase.WORKING) throw new ChangedOwner();
        }
        // Platform/profile queries belong to this file worker, never inside LOCK
        // or a main-thread cancel. The external I/O boundary itself is not atomic.
        try {
            SettingsStore.requirePersonalProfile(application);
            if (!owner.profileId.equals(SettingsStore.currentProfileId(application))
                    || !owner.taskInput.matches(AgentController.get(application).getTaskInputState())) throw new ChangedOwner();
        } catch (RuntimeException unavailable) { throw new ChangedOwner(); }
        synchronized (LOCK) {
            if (current != owner || owner.phase != Phase.WORKING) throw new ChangedOwner();
        }
    }

    private static final class ChangedOwner extends Exception { }
    private static final class InvalidFile extends Exception { }
}
