package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real system font settings and service Views; callbacks use a synthetic RunControl, not AgentController. */
@RunWith(AndroidJUnit4.class)
public final class FontScaleControlWindowInstrumentedTest {
    private static final String PAUSED = "已暂停", WAITING = "等待模型";
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void realSystemFontScaleRebuildsControlsAndRevokesOldWindow() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            OriginalFont original = new OriginalFont(device);
            Callbacks callbacks = new Callbacks(device);
            String phase = "baseline_configuration";
            Throwable primary = null;
            try {
                prepareApi30Cache(device);
                changeFont(device, "1.0", 1.0f);
                onMain(device, () -> {
                    device.control.pause();
                    device.service.setOverlayCallbacks(callbacks);
                    device.service.showOverlay(PAUSED, true, "");
                });
                Controls before = currentControls(device, callbacks, 1.0f);
                long originalEpoch = device.control.getExecutionEpoch();
                assertEquals(0, callbacks.pauseResume.get());
                assertEquals(0, callbacks.stop.get());
                assertEquals(0, device.control.getDispatchAttempts());
                screenshots.capture("real-system-font-scale-1_0-paused-controls");

                phase = "font_scale_change";
                // Preparation only: Android 11 shares its node cache with this
                // process's UiAutomation. Reapply unchanged info before the timer.
                prepareApi30Cache(device);
                changeFont(device, "1.3", 1.3f);
                Controls enlarged = currentControls(device, callbacks, 1.3f, before);
                onMain(device, () -> {
                    assertFalse(before.panel.isAttachedToWindow());
                    assertFalse(before.title.isAttachedToWindow());
                    assertFalse(before.pause.isAttachedToWindow());
                    assertFalse(before.stop.isAttachedToWindow());
                    assertNotSame(before.panel, enlarged.panel);
                    assertNotSame(before.title, enlarged.title);
                    assertNotSame(before.identity, enlarged.identity);
                    assertFalse(device.service.isOwnedControlWindowCurrent(before.identity));
                    assertFalse(device.service.isControlLeaseCurrent(before.lease));
                    assertFalse(before.lease.matchesOriginalAttachment(enlarged.identity));
                    // Integer window IDs may be reused; the real attachment must change.
                    assertSame(callbacks, field(device.service, "callbacks"));
                    assertTrue(device.control.isPaused());
                    assertFalse(device.control.isCancelled());
                    assertEquals(originalEpoch, device.control.getExecutionEpoch());
                    assertTrue(enlarged.titlePixels > before.titlePixels);
                    assertTrue(enlarged.pausePixels > before.pausePixels);
                    assertTrue(enlarged.stopPixels > before.stopPixels);
                    assertEquals(before.density, enlarged.density, 0.0001f);
                    assertEquals(before.rotation, enlarged.rotation);
                    assertEquals(before.displayBounds, enlarged.displayBounds);
                    // Old View listeners still exist. Their panel guard must reject
                    // these test-injected calls without reaching the callbacks.
                    assertTrue(before.pause.performClick());
                    assertTrue(before.stop.performClick());
                    assertEquals(0, callbacks.pauseResume.get());
                    assertEquals(0, callbacks.stop.get());
                    assertTrue(device.control.isPaused());
                    assertFalse(device.control.isCancelled());
                    assertEquals(originalEpoch, device.control.getExecutionEpoch());
                    assertEquals(0, device.control.getDispatchAttempts());
                });
                screenshots.capture("real-system-font-scale-1_3-new-window-old-controls-inert");

                phase = "current_button_listeners";
                // ScreenshotEvidence may populate the same-process accessibility
                // cache. Prepare API30 before the next actual status publication.
                prepareApi30Cache(device);
                // performClick exercises the actual installed listeners, not physical hit testing.
                onMain(device, () -> assertTrue(enlarged.pause.performClick()));
                assertEquals(1, callbacks.pauseResume.get());
                assertFalse(device.control.isPaused());
                assertFalse(device.control.isCancelled());
                Controls resumed = currentControls(device, callbacks, 1.3f);
                onMain(device, () -> assertTrue(resumed.pause.performClick()));
                assertEquals(2, callbacks.pauseResume.get());
                assertTrue(device.control.isPaused());
                Controls pausedAgain = currentControls(device, callbacks, 1.3f);
                onMain(device, () -> assertTrue(pausedAgain.stop.performClick()));
                assertEquals(1, callbacks.stop.get());
                assertTrue(device.control.isCancelled());
                assertEquals(0, device.control.getDispatchAttempts());
                // The synthetic stop callback cancels only its RunControl; hiding
                // the panel remains ordinary test cleanup, not Controller coverage.
                screenshots.capture("enlarged-current-buttons-reach-synthetic-pause-resume-stop");
                phase = "verified";
            } catch (Exception | AssertionError failure) {
                String diagnostic = "System font-scale assertion failed; diagnostic=unavailable";
                Throwable diagnosticFailure = null;
                try {
                    diagnostic = "System font-scale assertion failed before restoration; phase=" + phase
                            + ",actualScale=" + device.service.getResources().getConfiguration().fontScale
                            + ",paused=" + device.control.isPaused() + ",cancelled=" + device.control.isCancelled()
                            + ",pauseResumeCallbacks=" + callbacks.pauseResume.get()
                            + ",stopCallbacks=" + callbacks.stop.get()
                            + ",dispatchAttempts=" + device.control.getDispatchAttempts();
                } catch (RuntimeException | AssertionError unavailable) { diagnosticFailure = unavailable; }
                AssertionError reported = new AssertionError(diagnostic, failure);
                if (diagnosticFailure != null) reported.addSuppressed(diagnosticFailure);
                try { screenshots.capture("font-scale-failed-before-setting-restoration"); }
                catch (Throwable captureFailure) { reported.addSuppressed(captureFailure); }
                primary = reported;
                throw reported;
            } finally {
                Throwable cleanup = null;
                try { onMain(device, device.service::hideOverlay); }
                catch (Exception | AssertionError failure) { cleanup = failure; }
                try { original.restore(device); }
                catch (Exception | AssertionError failure) {
                    if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure);
                }
                if (cleanup != null) {
                    if (primary != null) primary.addSuppressed(cleanup);
                    else throw new AssertionError("Could not restore the original synthetic-device font configuration", cleanup);
                }
            }
        }
    }

    private static final class OriginalFont {
        final String raw;
        final float actual;
        OriginalFont(DeviceHarness device) {
            raw = readFont(device);
            float[] effective = onMain(device, () -> new float[]{device.service.getResources().getConfiguration().fontScale,
                    device.context.getResources().getConfiguration().fontScale});
            actual = effective[0];
            assertTrue("Original effective font scale must be finite and positive", Float.isFinite(actual) && actual > 0);
            assertEquals("Original application and service resources must agree before changing settings",
                    actual, effective[1], 0.0001f);
            // executeShellCommand tokenizes arguments directly. Preserve the exact
            // raw numeric token, including exponent spelling, or refuse before changing it.
            if (raw != null) assertTrue("Original font setting is not a safe numeric token",
                    raw.length() <= 64 && raw.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
                            && Float.isFinite(Float.parseFloat(raw)));
        }
        void restore(DeviceHarness device) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            device.shell(raw == null ? "settings --user current delete system font_scale"
                    : "settings --user current put system font_scale " + raw);
            awaitFont(device, raw, actual, deadline);
        }
    }

    private static String readFont(DeviceHarness device) {
        return Settings.System.getString(device.context.getContentResolver(), Settings.System.FONT_SCALE);
    }

    private static void changeFont(DeviceHarness device, String raw, float expected) throws Exception {
        assertTrue("Only the two declared synthetic font samples may be installed", "1.0".equals(raw) || "1.3".equals(raw));
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        device.shell("settings --user current put system font_scale " + raw);
        awaitFont(device, raw, expected, deadline);
    }

    private static void awaitFont(DeviceHarness device, String raw, float expected, long deadline) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean applied = onMain(device, () ->
                    Objects.equals(raw, readFont(device))
                            && Math.abs(device.service.getResources().getConfiguration().fontScale - expected) < 0.0001f
                            && Math.abs(device.context.getResources().getConfiguration().fontScale - expected) < 0.0001f);
            if (applied) { requireTime(deadline); return; }
            Thread.sleep(25);
        }
        throw new AssertionError("System setting and actual service/application font configuration did not agree within ten seconds");
    }

    private static void prepareApi30Cache(DeviceHarness device) {
        if (Build.VERSION.SDK_INT != 30) return;
        AccessibilityServiceInfo current = device.automation.getServiceInfo();
        assertNotNull("API30 test cache preparation requires the existing UiAutomation configuration", current);
        device.automation.setServiceInfo(current);
    }

    private static Controls currentControls(DeviceHarness device, Callbacks callbacks, float scale) throws Exception {
        return currentControls(device, callbacks, scale, null);
    }

    private static Controls currentControls(DeviceHarness device, Callbacks callbacks, float scale, Controls previous) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        // Resource updates alone do not establish that the service callback or
        // first layout has completed. The identity waiter requires an attachment.
        // Include this real lifecycle preparation in the same five-second phase.
        boolean ready = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            ready = onMain(device, () -> {
                View current = (View) field(device.service, "overlay");
                return current != null && current.isAttachedToWindow() && current.isLaidOut()
                        && !current.isLayoutRequested()
                        && (previous == null || current != previous.panel && !previous.panel.isAttachedToWindow());
            });
            if (ready) break;
            Thread.sleep(25);
        }
        assertTrue("Actual controls must finish attachment/layout; configuration changes must detach and replace the old panel", ready);
        requireTime(deadline);
        AgentAccessibilityService.OwnedControlWindowIdentity identity = awaitFuture(device.service.acquireOwnedControlWindowIdentity(), deadline);
        AgentAccessibilityService.ControlLease lease = awaitFuture(device.service.acquireControlLease(), deadline);
        Controls controls = onMain(device, () -> {
            requireTime(deadline);
            assertTrue(device.service.isOwnedControlWindowCurrent(identity));
            assertTrue(device.service.isControlLeaseCurrent(lease));
            assertTrue(lease.matchesOriginalAttachment(identity));
            assertEquals(identity.windowId, lease.windowId);
            assertEquals(identity.displayId, lease.displayId);
            assertEquals(identity.copyBounds(), lease.copyBounds());
            assertSame(callbacks, field(device.service, "callbacks"));
            assertEquals(scale, device.service.getResources().getConfiguration().fontScale, 0.0001f);
            Controls actual = new Controls(device.service, identity, lease);
            assertEquals(identity.copyBounds(), screenBounds(actual.panel));
            assertEquals(identity.copyBounds(), device.service.controlBounds());
            boolean paused = device.control.isPaused();
            assertEquals(paused, field(device.service, "currentPaused"));
            assertEquals(paused ? PAUSED : WAITING, field(device.service, "currentStatus"));
            assertEquals("", field(device.service, "currentApproval"));
            assertEquals("Agent · " + (paused ? PAUSED : WAITING), actual.title.getText().toString());
            assertEquals(paused ? "继续" : "暂停", actual.pause.getText().toString());
            assertEquals("停止", actual.stop.getText().toString());
            assertSafeGeometry(device.service, actual);
            return actual;
        });
        assertRegistered(device, controls, deadline);
        requireTime(deadline);
        return controls;
    }

    private static <T> T awaitFuture(CompletableFuture<T> pending, long deadline) throws Exception {
        try {
            requireTime(deadline);
            T result = pending.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            requireTime(deadline);
            return result;
        } finally { pending.cancel(false); }
    }

    private static void assertRegistered(DeviceHarness device, Controls controls, long deadline) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            int matches = 0;
            boolean exact = false;
            List<AccessibilityWindowInfo> windows = device.service.getWindows();
            try {
                if (windows != null) for (AccessibilityWindowInfo window : windows) {
                    if (window.getId() != controls.identity.windowId) continue;
                    matches++;
                    Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                    exact = window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                            && window.getDisplayId() == controls.identity.displayId
                            && !window.isFocused() && controls.identity.copyBounds().equals(bounds);
                }
            } finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
            assertTrue("A current control ID must identify at most one platform window", matches <= 1);
            if (matches == 1 && exact) {
                onMain(device, () -> {
                    requireTime(deadline);
                    assertTrue(device.service.isOwnedControlWindowCurrent(controls.identity));
                    assertTrue(device.service.isControlLeaseCurrent(controls.lease));
                    assertSame(controls.panel, field(device.service, "overlay"));
                    assertEquals(controls.identity.copyBounds(), screenBounds(controls.panel));
                });
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The new View and its actual platform window did not register with identical geometry");
    }

    private static final class Controls {
        final ViewGroup panel;
        final TextView title;
        final Button pause, stop;
        final AgentAccessibilityService.OwnedControlWindowIdentity identity;
        final AgentAccessibilityService.ControlLease lease;
        final float titlePixels, pausePixels, stopPixels, density;
        final int rotation;
        final Rect displayBounds;
        Controls(AgentAccessibilityService service, AgentAccessibilityService.OwnedControlWindowIdentity identity,
                 AgentAccessibilityService.ControlLease lease) {
            this.identity = identity; this.lease = lease;
            panel = (ViewGroup) field(service, "overlay");
            title = (TextView) field(service, "statusView");
            pause = (Button) field(service, "pauseButton");
            assertNotNull(panel); assertNotNull(title); assertNotNull(pause);
            stop = (Button) ((ViewGroup) panel.getChildAt(1)).getChildAt(1);
            titlePixels = title.getTextSize(); pausePixels = pause.getTextSize(); stopPixels = stop.getTextSize();
            density = service.getResources().getDisplayMetrics().density;
            Display display = panel.getDisplay(); assertNotNull(display);
            rotation = display.getRotation();
            WindowManager manager = service.getSystemService(WindowManager.class); assertNotNull(manager);
            displayBounds = new Rect(manager.getCurrentWindowMetrics().getBounds());
        }
    }

    private static void assertSafeGeometry(AgentAccessibilityService service, Controls controls) {
        assertEquals(Display.DEFAULT_DISPLAY, controls.identity.displayId);
        WindowManager manager = service.getSystemService(WindowManager.class); assertNotNull(manager);
        WindowMetrics metrics = manager.getCurrentWindowMetrics();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect safe = new Rect(metrics.getBounds());
        safe.set(safe.left + insets.left, safe.top + insets.top, safe.right - insets.right, safe.bottom - insets.bottom);
        assertFalse(safe.isEmpty());
        assertTrue("The entire compact control window must fit within system-bar/cutout safe bounds", safe.contains(screenBounds(controls.panel)));
        int minimum = Math.round(48 * controls.density);
        for (TextView text : new TextView[]{controls.title, controls.pause, controls.stop}) {
            assertTrue(text.isAttachedToWindow() && text.isLaidOut() && text.isShown());
            Rect local = new Rect();
            assertTrue(text.getLocalVisibleRect(local));
            assertEquals("The real label View must not be clipped by its parent", new Rect(0, 0, text.getWidth(), text.getHeight()), local);
            assertTrue(controls.identity.copyBounds().contains(screenBounds(text)));
            assertTrue(text.getHeight() >= minimum);
            if (text instanceof Button) {
                assertTrue(text.isEnabled() && text.isClickable());
                assertTrue(text.getWidth() >= minimum);
            }
            Layout layout = text.getLayout(); assertNotNull(layout);
            assertTrue(layout.getLineCount() > 0);
            assertEquals(text.getText().length(), layout.getLineEnd(layout.getLineCount() - 1));
            int availableWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            int availableHeight = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
            assertTrue("Actual text layout must fit vertically", layout.getHeight() <= availableHeight);
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals("Labels must not be ellipsized", 0, layout.getEllipsisCount(line));
                assertTrue("Actual text must fit horizontally", layout.getLineLeft(line) >= -0.5f
                        && layout.getLineRight(line) <= availableWidth + 0.5f);
            }
        }
    }

    private static Rect screenBounds(View view) {
        int[] location = new int[2]; view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static final class Callbacks implements AgentAccessibilityService.OverlayCallbacks {
        final DeviceHarness device;
        final AtomicInteger pauseResume = new AtomicInteger(), stop = new AtomicInteger();
        Callbacks(DeviceHarness device) { this.device = device; }
        @Override public void onPauseResume() {
            pauseResume.incrementAndGet();
            if (device.control.isPaused()) device.control.resume(); else device.control.pause();
            boolean paused = device.control.isPaused();
            device.service.showOverlay(paused ? PAUSED : WAITING, paused, "");
        }
        @Override public void onStop() { stop.incrementAndGet(); device.control.cancel(); }
        @Override public void onApprove(boolean approved) { fail("Compact font test must not invoke approval"); }
    }

    private static Object field(AgentAccessibilityService service, String name) {
        try {
            Field field = AgentAccessibilityService.class.getDeclaredField(name); field.setAccessible(true); return field.get(service);
        } catch (ReflectiveOperationException unavailable) { throw new AssertionError("Cannot read fixed service test field: " + name, unavailable); }
    }

    private static void requireTime(long deadline) {
        assertTrue("The original test phase deadline must not be renewed", SystemClock.elapsedRealtime() < deadline);
    }

    private static void onMain(DeviceHarness device, Runnable action) { onMain(device, () -> { action.run(); return null; }); }

    private static <T> T onMain(DeviceHarness device, Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); } catch (Throwable error) { failure.set(error); }
        });
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error != null) throw new AssertionError("Main-thread test action failed", error);
        return result.get();
    }
}
