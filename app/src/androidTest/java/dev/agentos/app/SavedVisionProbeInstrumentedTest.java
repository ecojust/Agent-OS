package dev.agentos.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real encrypted configuration and HTTP, with platform PNG decoding at the test server. */
@RunWith(AndroidJUnit4.class)
public final class SavedVisionProbeInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("saved_vision_probe_synthetic_http_no_device_capture");
    private static final long FRESHNESS_MILLIS = 600_000;

    @Test public void productionRegistryUsesTheActuallySavedConnectionAndARealDecodedColorChallenge() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             Environment env = new Environment(server, true)) {
            SavedVisionProbe registry = env.register(SavedVisionProbe.process());
            assertSame(registry, SavedVisionProbe.process()); assertNull(registry.current(env.lease));
            RunControl owner = env.control(); long epoch = owner.getExecutionEpoch();
            long before = SystemClock.elapsedRealtime();
            SavedVisionProbe.Outcome outcome = registry.test(env.context, env.lease, owner, epoch);
            long after = SystemClock.elapsedRealtime();
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", outcome);
            SavedVisionProbe.Verification record = registry.current(env.lease);
            assertNotNull(record); assertTrue(record.isCurrentFor(env.lease)); assertSame(record, registry.current(env.lease));
            assertTrue(record.verifiedAtElapsedRealtimeMillis >= before);
            assertTrue(record.verifiedAtElapsedRealtimeMillis <= after);
            assertEquals(record.verifiedAtElapsedRealtimeMillis + FRESHNESS_MILLIS, record.expiresAtElapsedRealtimeMillis);
            assertFalse(record.isCurrentFor(null)); assertSame(record, registry.current(env.lease));
            // A UI owner must not adopt another attempt's result, even when
            // both attempts name the same saved configuration and profile.
            assertTrue(record.isCurrentFor(env.lease, owner, epoch));
            assertFalse(record.isCurrentFor(env.lease, null, epoch));
            assertFalse(record.isCurrentFor(env.lease, env.control(), epoch));
            assertFalse(record.isCurrentFor(env.lease, owner, epoch + 1));
            assertSame(record, registry.current(env.lease));
            assertTrue(record.isCurrentFor(env.lease, owner, epoch));
            assertEquals(1, owner.budgetSnapshot().modelRounds); assertEquals(0, owner.budgetSnapshot().toolCalls);
            assertEquals(0, owner.getDispatchAttempts()); assertEquals(epoch, owner.getExecutionEpoch()); assertFalse(owner.isCancelled());
            assertSame(env.lease, env.store.loadWithLease().lease);
            assertEquals(1, server.requestCount()); assertEquals(1, server.imageRequestCount()); assertEquals(0, server.toolRequestCount());
            // The HTTP fixture independently decoded eight pixels and obtained
            // nonce/order from the real request; no probe result was injected.
            server.assertHealthy(); env.assertPayloadsDrained();
        }
    }

    @Test public void unsavedForeignStaleAndMainThreadInputsRejectBeforeAnyHttpRequest() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             Environment env = new Environment(server, false)) {
            SavedVisionProbe registry = env.registry(new AtomicLong(1_000)); RunControl owner = env.control();
            assertNull(env.lease); assertNull(env.store.loadWithLease().lease);
            assertThrows(IllegalArgumentException.class, () -> registry.test(env.context, null, owner, owner.getExecutionEpoch()));
            env.saveConnection();
            assertThrows(IllegalArgumentException.class, () -> registry.test(null, env.lease, owner, owner.getExecutionEpoch()));
            assertThrows(IllegalArgumentException.class, () -> registry.test(env.context, env.lease, null, 0));
            RunControl foreign = env.control("foreign-test-profile");
            assertThrows(IllegalArgumentException.class, () -> registry.test(env.context, env.lease, foreign, foreign.getExecutionEpoch()));
            assertFalse(foreign.isCancelled());
            AtomicReference<Throwable> mainFailure = new AtomicReference<>();
            env.instrumentation.runOnMainSync(() -> {
                try { registry.test(env.context, env.lease, owner, owner.getExecutionEpoch()); }
                catch (Throwable caught) { mainFailure.set(caught); }
            });
            assertTrue(String.valueOf(mainFailure.get()), mainFailure.get() instanceof IllegalStateException);
            long oldEpoch = owner.getExecutionEpoch(); owner.pause(); owner.resume();
            assertTask("TASK_PAUSED", () -> registry.test(env.context, env.lease, owner, oldEpoch));
            assertFalse(owner.isCancelled());
            SettingsStore.ConfigurationLease previous = env.lease; env.saveSame();
            RunControl oldConfigurationOwner = env.control();
            assertTask("TASK_STOPPED", () -> registry.test(env.context, previous, oldConfigurationOwner,
                    oldConfigurationOwner.getExecutionEpoch()));
            assertTrue(oldConfigurationOwner.isCancelled()); assertTrue(SettingsStore.isCurrent(env.lease));
            assertEquals(0, server.requestCount()); assertEquals(0, server.imageRequestCount()); assertEquals(0, server.toolRequestCount());
            assertEquals(0, owner.budgetSnapshot().modelRounds); assertEquals(0, foreign.budgetSnapshot().modelRounds);
            assertNull(registry.current(env.lease)); env.assertPayloadsDrained();
        }
    }

    @Test public void aRealUnverifiedRetestClearsThePreviousRecordAndHttpFailureCannotMintOne() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED_THEN_UNVERIFIED);
             Environment env = new Environment(server, true)) {
            SavedVisionProbe registry = env.registry(new AtomicLong(5_000)); RunControl owner = env.control();
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, owner));
            SavedVisionProbe.Verification original = registry.current(env.lease); assertNotNull(original);
            assertOutcome(SavedVisionProbe.Status.UNVERIFIED, true, "SYNTHETIC_IMAGE_UNVERIFIED", env.test(registry, owner));
            assertNull(registry.current(env.lease)); assertFalse(original.isCurrentFor(env.lease));
            assertEquals(2, server.requestCount()); assertEquals(2, server.imageRequestCount()); assertEquals(0, server.toolRequestCount());
            assertFalse(owner.isCancelled()); env.assertPayloadsDrained(); server.assertHealthy();
        }
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HTTP_UNAUTHORIZED);
             Environment env = new Environment(server, true)) {
            SavedVisionProbe registry = env.registry(new AtomicLong(6_000)); RunControl owner = env.control();
            assertOutcome(SavedVisionProbe.Status.FAILED, false, "PROBE_FAILED", env.test(registry, owner));
            assertNull(registry.current(env.lease)); assertFalse(owner.isCancelled());
            assertEquals(1, server.requestCount()); assertEquals(1, server.imageRequestCount());
            assertEquals(0, server.toolRequestCount()); env.assertPayloadsDrained(); server.assertHealthy();
        }
    }

    @Test public void publishedInvalidationRejectsACorrectHttpReplyBeforeTheHeldWatcherReturns() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HELD_VERIFIED);
             Environment env = new Environment(server, true)) {
            SavedVisionProbe registry = env.registry(new AtomicLong(8_000)); RunControl owner = env.control();
            SettingsStore.ConfigurationLease original = env.lease;
            SettingsStore.SavedSettings same = env.store.load();
            try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(env.store, original);
                 ProbeCall call = env.start(registry, owner)) {
                assertTrue(server.awaitRequests(1, 8_000)); assertEquals(1, server.imageRequestCount());
                held.start(same); held.awaitPublished(); assertFalse(SettingsStore.isCurrent(original));
                // One synthetic request has already arrived. Reject its response;
                // do not claim configuration publication retracts sent bytes.
                server.releaseResponse();
                assertTask("TASK_STOPPED", call::awaitOutcome);
                assertTrue(server.awaitResponseAttempt()); held.assertHeld();
                assertTrue(owner.isCancelled()); assertNull(registry.current(original));
                assertEquals(1, server.requestCount());
                env.lease = held.finish();
            }
            RunControl replacement = env.control();
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, replacement));
            SavedVisionProbe.Verification current = registry.current(env.lease); assertNotNull(current);
            assertNull(registry.current(original)); assertSame(current, registry.current(env.lease));
            assertFalse(replacement.isCancelled()); assertEquals(2, server.requestCount());
            env.assertPayloadsDrained(); server.assertHealthy();
        }
    }

    @Test public void cancelledOrPausedHttpWorkCannotPublishALateSuccessOrCancelAReplacementProbe() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HELD_VERIFIED);
                 Environment env = new Environment(server, true)) {
                SavedVisionProbe registry = env.registry(new AtomicLong(10_000)); RunControl owner = env.control();
                long epoch = owner.getExecutionEpoch();
                try (ProbeCall original = env.start(registry, owner)) {
                    assertTrue(server.awaitRequests(1, 8_000));
                    if (mode == 0) owner.cancel(); else { owner.pause(); owner.resume(); }
                    assertTask(mode == 0 ? "TASK_STOPPED" : "TASK_PAUSED", original::awaitOutcome);
                    if (mode == 1) assertFalse("Old epoch cancellation must not stop the resumed control", owner.isCancelled());
                    assertNotEquals(epoch, owner.getExecutionEpoch()); assertNull(registry.current(env.lease));
                    RunControl replacement = env.control();
                    // The old caller has exited but the fixture still holds its
                    // reply. A later response/drain may not remove a new attempt.
                    try (ProbeCall next = env.start(registry, replacement)) {
                        server.releaseResponse();
                        assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", next.awaitOutcome());
                        SavedVisionProbe.Verification current = registry.current(env.lease); assertNotNull(current);
                        assertTrue(current.isCurrentFor(env.lease)); assertFalse(replacement.isCancelled());
                        assertEquals(2, server.requestCount()); assertEquals(2, server.imageRequestCount());
                    }
                }
                env.assertPayloadsDrained(); server.assertHealthy();
            }
        }
    }

    @Test public void concurrentProbeReturnsBusyWithoutChangingTheOriginalTaskOrSendingASecondRequest() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.HELD_VERIFIED);
             Environment env = new Environment(server, true)) {
            SavedVisionProbe registry = env.registry(new AtomicLong(12_000));
            RunControl first = env.control(), second = env.control(); long firstEpoch = first.getExecutionEpoch(), secondEpoch = second.getExecutionEpoch();
            try (ProbeCall active = env.start(registry, first)) {
                assertTrue(server.awaitRequests(1, 8_000));
                try (ProbeCall busy = env.start(registry, second)) {
                    assertOutcome(SavedVisionProbe.Status.BUSY, false, "PROBE_BUSY", busy.awaitOutcome(2));
                }
                assertNull(registry.current(env.lease)); assertEquals(1, server.requestCount());
                assertEquals(0, second.budgetSnapshot().modelRounds); assertFalse(first.isCancelled()); assertFalse(second.isCancelled());
                assertEquals(firstEpoch, first.getExecutionEpoch()); assertEquals(secondEpoch, second.getExecutionEpoch());
                server.releaseResponse();
                assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", active.awaitOutcome());
            }
            SavedVisionProbe.Verification firstRecord = registry.current(env.lease); assertNotNull(firstRecord);
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, second));
            SavedVisionProbe.Verification secondRecord = registry.current(env.lease); assertNotNull(secondRecord); assertNotSame(firstRecord, secondRecord);
            first.cancel(); assertFalse(firstRecord.isCurrentFor(env.lease));
            assertSame(secondRecord, registry.current(env.lease)); assertFalse(second.isCancelled());
            assertEquals(2, server.requestCount()); env.assertPayloadsDrained(); server.assertHealthy();
        }
    }

    @Test public void fixedFreshnessExpiresAtTheBoundaryAndClockRollbackCannotReviveARecord() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             Environment env = new Environment(server, true)) {
            AtomicLong clock = new AtomicLong(20_000); SavedVisionProbe registry = env.registry(clock); RunControl owner = env.control();
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, owner));
            SavedVisionProbe.Verification first = registry.current(env.lease); assertNotNull(first);
            assertEquals(20_000, first.verifiedAtElapsedRealtimeMillis); assertEquals(620_000, first.expiresAtElapsedRealtimeMillis);
            clock.set(first.expiresAtElapsedRealtimeMillis - 1); assertSame(first, registry.current(env.lease));
            clock.incrementAndGet(); assertFalse(first.isCurrentFor(env.lease)); assertNull(registry.current(env.lease));
            clock.set(first.verifiedAtElapsedRealtimeMillis); assertFalse(first.isCurrentFor(env.lease)); assertNull(registry.current(env.lease));
            clock.set(700_000);
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, owner));
            SavedVisionProbe.Verification second = registry.current(env.lease); assertNotNull(second);
            clock.incrementAndGet(); assertTrue(second.isCurrentFor(env.lease));
            clock.decrementAndGet(); assertFalse(second.isCurrentFor(env.lease)); assertNull(registry.current(env.lease));
            clock.incrementAndGet(); assertFalse(second.isCurrentFor(env.lease));
            // The clock seam changes only record freshness, not HTTP or task
            // deadlines. Even successful HTTP cannot create an overflowing TTL.
            clock.set(Long.MAX_VALUE - 1);
            IOException invalidClock = assertThrows(IOException.class, () -> env.test(registry, owner));
            assertNull(invalidClock.getCause()); assertNull(registry.current(env.lease)); assertFalse(owner.isCancelled());
            clock.set(1_000_000);
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, owner));
            assertNotNull("A failed clock must release admission for a subsequent real probe", registry.current(env.lease));
            assertEquals(4, server.requestCount()); assertEquals(4, server.imageRequestCount());
            env.assertPayloadsDrained(); server.assertHealthy();
        }
    }

    @Test public void cancelResaveAndASeparateRegistryCannotReuseOrEraseAnotherCurrentVerification() throws Exception {
        try (LocalVisionProbeServer server = new LocalVisionProbeServer(LocalVisionProbeServer.Mode.VERIFIED);
             Environment env = new Environment(server, true)) {
            AtomicLong clock = new AtomicLong(30_000); SavedVisionProbe registry = env.registry(clock);
            RunControl first = env.control();
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, first));
            SavedVisionProbe.Verification original = registry.current(env.lease); assertNotNull(original);
            first.cancel(); assertFalse(original.isCurrentFor(env.lease)); assertNull(registry.current(env.lease));
            RunControl second = env.control();
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, second));
            SavedVisionProbe.Verification beforeResave = registry.current(env.lease); assertNotNull(beforeResave);
            assertNull(env.registry(clock).current(env.lease)); assertSame(beforeResave, registry.current(env.lease));
            SettingsStore.ConfigurationLease previous = env.lease; env.saveSame();
            assertNotSame(previous, env.lease); assertFalse(SettingsStore.isCurrent(previous)); assertTrue(SettingsStore.isCurrent(env.lease));
            assertFalse(beforeResave.isCurrentFor(previous)); assertNull(registry.current(env.lease));
            RunControl third = env.control();
            assertOutcome(SavedVisionProbe.Status.VERIFIED, true, "SYNTHETIC_IMAGE_VERIFIED", env.test(registry, third));
            SavedVisionProbe.Verification current = registry.current(env.lease); assertNotNull(current);
            assertFalse(original.isCurrentFor(env.lease)); assertFalse(beforeResave.isCurrentFor(previous));
            assertNull(registry.current(previous)); assertSame(current, registry.current(env.lease));
            assertFalse(third.isCancelled()); assertEquals(3, server.requestCount()); assertEquals(3, server.imageRequestCount());
            env.assertPayloadsDrained(); server.assertHealthy();
        }
    }

    private static final class ProbeCall implements AutoCloseable {
        final RunControl owner; final ExecutorService caller = Executors.newSingleThreadExecutor(); final Future<SavedVisionProbe.Outcome> result;
        ProbeCall(Environment env, SavedVisionProbe registry, RunControl owner) {
            this.owner = owner;
            SettingsStore.ConfigurationLease original = env.lease; long epoch = owner.getExecutionEpoch();
            try { result = caller.submit(() -> registry.test(env.context, original, owner, epoch)); }
            catch (RuntimeException | Error failed) { caller.shutdownNow(); throw failed; }
        }
        SavedVisionProbe.Outcome awaitOutcome() throws Exception { return awaitOutcome(8); }
        SavedVisionProbe.Outcome awaitOutcome(int seconds) throws Exception {
            try { return result.get(seconds, TimeUnit.SECONDS); }
            catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Error) throw (Error) cause;
                if (cause instanceof Exception) throw (Exception) cause;
                throw new AssertionError("Unexpected probe failure", cause);
            }
        }
        @Override public void close() throws Exception {
            if (!result.isDone()) { owner.cancel(); result.cancel(true); }
            caller.shutdownNow();
            assertTrue("Actual probe caller did not exit", caller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Preserves raw encrypted preferences and the existing Keystore key; opens no Activity/service. */
    private static final class Environment implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final LocalVisionProbeServer server;
        final SharedPreferences preferences; final Map<String, ?> originalPreferences;
        final SettingsStore store;
        SettingsStore.ConfigurationLease lease;
        final List<RunControl> controls = new ArrayList<>(); final List<ProbeCall> calls = new ArrayList<>();
        final List<SavedVisionProbe> registries = new ArrayList<>();
        Environment(LocalVisionProbeServer server, boolean save) throws Exception {
            this.server = server;
            await(() -> ImageWorkset.process().reservedBytes() == 0, "A previous HTTP payload owner has not drained");
            String profile = SettingsStore.currentProfileId(context);
            preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
            originalPreferences = preferences.getAll(); store = new SettingsStore(context);
            try {
                assertTrue(preferences.edit().clear().commit());
                assertNull(store.loadWithLease().lease);
                if (save) saveConnection();
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        void saveConnection() throws IOException {
            SettingsStore.SavedSettings saved = store.load(); saved.endpoint = server.endpoint();
            saved.apiKey = LocalVisionProbeServer.KEY; saved.model = LocalVisionProbeServer.MODEL;
            lease = store.save(saved); assertSame(lease, store.loadWithLease().lease);
        }
        void saveSame() throws IOException { lease = store.save(store.load()); assertSame(lease, store.loadWithLease().lease); }
        RunControl control() { return control(SettingsStore.currentProfileId(context)); }
        RunControl control(String profile) { RunControl owner = new RunControl(profile); controls.add(owner); return owner; }
        SavedVisionProbe register(SavedVisionProbe registry) { registries.add(registry); return registry; }
        SavedVisionProbe registry(AtomicLong clock) { return register(SavedVisionProbe.forTesting(clock::get)); }
        SavedVisionProbe.Outcome test(SavedVisionProbe registry, RunControl owner) throws Exception {
            return registry.test(context, lease, owner, owner.getExecutionEpoch());
        }
        ProbeCall start(SavedVisionProbe registry, RunControl owner) {
            ProbeCall call = new ProbeCall(this, registry, owner); calls.add(call); return call;
        }
        void assertPayloadsDrained() throws Exception {
            await(() -> ImageWorkset.process().reservedBytes() == 0, "Actual HTTP payload owner has not drained");
            server.assertHealthy();
            for (RunControl control : controls) { assertEquals(0, control.getDispatchAttempts()); assertEquals(0, control.budgetSnapshot().toolCalls); }
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try {
                for (RunControl control : controls) control.cancel();
                server.releaseResponse();
                for (ProbeCall call : calls) try { call.close(); } catch (Exception | Error failed) { failure = append(failure, failed); }
                await(() -> ImageWorkset.process().reservedBytes() == 0, "HTTP worker still owns payload during cleanup");
                for (SavedVisionProbe registry : registries) assertNull(registry.current(lease));
            } catch (Exception | Error failed) { failure = append(failure, failed); }
            finally {
                try { restorePreferences(); } catch (Exception | Error failed) { failure = append(failure, failed); }
            }
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof Exception) throw (Exception) failure;
        }
        private void restorePreferences() throws IOException {
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Set<?>) {
                    @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                    editor.putStringSet(entry.getKey(), values);
                }
            }
            if (!editor.commit()) throw new IOException("Could not restore configuration after synthetic saved probe test");
        }
    }
    private interface CheckedAction { void run() throws Exception; }
    private static void assertTask(String code, CheckedAction action) throws Exception {
        TaskControlException failure = assertThrows(TaskControlException.class, action::run);
        assertEquals(code, failure.getCode());
    }
    private static void assertOutcome(SavedVisionProbe.Status status, boolean connected, String code, SavedVisionProbe.Outcome actual) {
        assertNotNull(actual); assertEquals(status, actual.status); assertEquals(connected, actual.connected); assertEquals(code, actual.code);
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; if (first != next) first.addSuppressed(next); return first; }
}
