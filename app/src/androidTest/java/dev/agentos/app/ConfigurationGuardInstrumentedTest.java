package dev.agentos.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.JsonObject;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Uses the production lease constructor and a real store's publication-before-notification window. */
@RunWith(AndroidJUnit4.class)
public final class ConfigurationGuardInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void publishedInvalidationRejectsReadsBeforeCancellationAndOldWorkCannotCancelANewRun() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
             ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
            SettingsStore store = new SettingsStore(device.context);
            SettingsStore.SavedSettings same = store.load();
            AndroidDeviceTools guarded = tools(device, settings.lease);
            JsonObject observed = awaitObservation(guarded, device.control, value -> FIXTURE.equals(string(value, "packageName")));
            assertEquals("Count: 0", string(node(observed, "counter_value"), "text"));
            AtomicInteger cancellationNotifications = new AtomicInteger();
            try (HeldConfigurationInvalidation held = new HeldConfigurationInvalidation(store, settings.lease);
                 SettingsStore.Registration cancellation = settings.lease.watch(() -> {
                     cancellationNotifications.incrementAndGet(); device.control.cancel();
                 });
                 PerformanceTrace.Session trace = PerformanceTrace.startDiagnostics()) {
                held.start(same);
                held.awaitPublished();
                assertEquals(0, cancellationNotifications.get());
                assertFalse("The test must exercise the gap before cancellation delivery", device.control.isCancelled());
                assertStoppedRead(guarded, device.control);
                assertTrue(device.control.isCancelled());
                assertEquals("The device guard, not a listener, must stop the request", 0, cancellationNotifications.get());
                held.assertHeld();
                assertEquals(0, device.control.getDispatchAttempts());
                assertEquals(0, trace.overwrittenCount());
                for (PerformanceTrace.Event event : trace.events()) {
                    assertNotEquals("An invalid configuration began a remote observation", PerformanceTrace.Stage.SNAPSHOT_OBSERVE, event.stage);
                    assertNotEquals(PerformanceTrace.Stage.DEVICE_DISPATCH_ATTEMPT, event.stage);
                }
                try { tools(device, settings.lease); fail("The production constructor accepted an invalid lease"); }
                catch (IOException expected) { }
                SettingsStore.ConfigurationLease replacement = held.finish();
                assertEquals(1, cancellationNotifications.get());
                RunControl next = new RunControl(SettingsStore.currentProfileId(device.context));
                try {
                    AndroidDeviceTools nextTools = tools(device, replacement);
                    JsonObject fresh = awaitObservation(nextTools, next, value -> FIXTURE.equals(string(value, "packageName")));
                    assertEquals("Count: 0", string(node(fresh, "counter_value"), "text"));
                    assertStoppedRead(guarded, device.control);
                    assertFalse("Late old requests must not cancel the replacement run", next.isCancelled());
                    assertTrue(nextTools.execute("observe", new JsonObject(), next).get("ok").getAsBoolean());
                    assertEquals(0, next.getDispatchAttempts());
                } finally { next.cancel(); }
                assertEquals(0, server.requestCount());
                screenshots.capture("configuration-publication-rejects-read-before-listener");
            } finally { device.control.cancel(); }
        }
    }

    private static AndroidDeviceTools tools(DeviceHarness device, SettingsStore.ConfigurationLease lease) throws IOException {
        return new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                dev.agentos.core.ActionConfirmationPolicy.Mode.CONFIRM_ALL, lease,
                (description, run, epoch) -> { fail("Read-only configuration checks must not request approval"); return false; });
    }

    private static void assertStoppedRead(AndroidDeviceTools tools, RunControl control) throws Exception {
        try { tools.execute("observe", new JsonObject(), control); fail("Invalidated configuration returned observation data"); }
        catch (TaskControlException stopped) { assertEquals("TASK_STOPPED", stopped.getCode()); }
    }
}
