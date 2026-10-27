package dev.agentos.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Holds a real store's first observer after invalidation publication, without production hooks. */
final class HeldConfigurationInvalidation implements AutoCloseable {
    private final SettingsStore store;
    private final SettingsStore.ConfigurationLease original;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final CountDownLatch published = new CountDownLatch(1), release = new CountDownLatch(1);
    private final AtomicBoolean expired = new AtomicBoolean(), returned = new AtomicBoolean();
    private final SettingsStore.Registration registration;
    private volatile Future<SettingsStore.ConfigurationLease> saving;

    HeldConfigurationInvalidation(SettingsStore store, SettingsStore.ConfigurationLease lease) {
        this.store = store;
        original = lease;
        assertTrue("The observer must be registered before invalidation", SettingsStore.isCurrent(lease));
        registration = lease.watch(() -> {
            published.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) expired.set(true); }
            catch (InterruptedException interrupted) { expired.set(true); Thread.currentThread().interrupt(); }
            finally { returned.set(true); }
        });
    }

    void start(SettingsStore.SavedSettings sameConfiguration) {
        assertNull("A held invalidation is single-use", saving);
        saving = writer.submit(() -> store.save(sameConfiguration));
    }

    void awaitPublished() throws Exception {
        assertTrue("The real store did not publish invalidation", published.await(2, TimeUnit.SECONDS));
        assertHeld();
    }

    void assertHeld() {
        assertFalse("The old configuration remains current", SettingsStore.isCurrent(original));
        assertFalse("An emergency timeout cannot stand in for observer release", expired.get());
        assertFalse("Cancellation listeners must still be waiting behind this observer", returned.get());
        assertNotNull(saving);
        assertFalse("Save completed before the observer was explicitly released", saving.isDone());
    }

    SettingsStore.ConfigurationLease finish() throws Exception {
        release.countDown();
        SettingsStore.ConfigurationLease replacement = saving.get(5, TimeUnit.SECONDS);
        assertFalse("The held observer timed out or was interrupted", expired.get());
        assertTrue(returned.get());
        assertFalse(SettingsStore.isCurrent(original));
        assertTrue(SettingsStore.isCurrent(replacement));
        return replacement;
    }

    @Override public void close() throws Exception {
        release.countDown();
        try { if (saving != null) saving.get(5, TimeUnit.SECONDS); }
        finally { registration.close(); writer.shutdownNow(); }
        assertFalse("Cleanup must release the observer before its emergency timeout", expired.get());
    }
}
