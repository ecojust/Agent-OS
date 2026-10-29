package dev.agentos.app;

import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real system settings on the dedicated fixture device; no synthetic configuration callbacks. */
final class RotationTestSettings {
    private final DeviceHarness device;
    private final String accelerometer, userRotation;
    private final int actualRotation;

    RotationTestSettings(DeviceHarness device) {
        this.device = device;
        accelerometer = read(Settings.System.ACCELEROMETER_ROTATION);
        userRotation = read(Settings.System.USER_ROTATION);
        assertTrue("Unexpected original accelerometer setting", accelerometer == null || accelerometer.matches("[01]"));
        assertTrue("Unexpected original user rotation setting", userRotation == null || userRotation.matches("[0-3]"));
        actualRotation = snapshot().rotation;
        assertTrue(actualRotation >= 0 && actualRotation <= 3);
    }

    void change(int rotation) throws Exception {
        assertTrue("Only the declared portrait and landscape samples may be installed", rotation == 0 || rotation == 1);
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        write(Settings.System.ACCELEROMETER_ROTATION, "0");
        write(Settings.System.USER_ROTATION, Integer.toString(rotation));
        await("0", Integer.toString(rotation), rotation, deadline, "change");
    }

    void restore() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        Throwable failure = null;
        try {
            write(Settings.System.ACCELEROMETER_ROTATION, "0");
            write(Settings.System.USER_ROTATION, Integer.toString(actualRotation));
            await("0", Integer.toString(actualRotation), actualRotation, deadline, "restore_actual");
        } catch (Exception | AssertionError error) { failure = error; }
        finally {
            try { write(Settings.System.USER_ROTATION, userRotation); }
            catch (Exception | AssertionError error) { failure = append(failure, error); }
            try { write(Settings.System.ACCELEROMETER_ROTATION, accelerometer); }
            catch (Exception | AssertionError error) { failure = append(failure, error); }
            try { await(accelerometer, userRotation, actualRotation, deadline, "restore_original_rows"); }
            catch (Exception | AssertionError error) { failure = append(failure, error); }
        }
        if (failure instanceof AssertionError) throw (AssertionError) failure;
        if (failure != null) throw (Exception) failure;
    }

    private static Throwable append(Throwable first, Throwable later) {
        if (first == null) return later;
        first.addSuppressed(later); return first;
    }

    private String read(String key) { return Settings.System.getString(device.context.getContentResolver(), key); }

    private void write(String key, String value) throws Exception {
        assertTrue(Settings.System.ACCELEROMETER_ROTATION.equals(key) || Settings.System.USER_ROTATION.equals(key));
        if (value != null) assertTrue(value.matches("[0-3]"));
        device.shell("settings --user current " + (value == null ? "delete system " + key : "put system " + key + " " + value));
    }

    private void await(String expectedAccelerometer, String expectedUser, int expectedRotation,
                       long deadline, String phase) throws Exception {
        Snapshot last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            last = snapshot();
            if (Objects.equals(expectedAccelerometer, read(Settings.System.ACCELEROMETER_ROTATION))
                    && Objects.equals(expectedUser, read(Settings.System.USER_ROTATION))
                    && last.matches(expectedRotation) && SystemClock.elapsedRealtime() < deadline) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Actual rotation and original setting rows did not converge; phase=" + phase
                + ",expected=" + expectedRotation + ",last=" + last);
    }

    Snapshot snapshot() {
        AtomicReference<Snapshot> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> {
            try {
                DisplayManager displays = device.context.getSystemService(DisplayManager.class);
                assertNotNull(displays);
                Display display = displays.getDisplay(Display.DEFAULT_DISPLAY); assertNotNull(display);
                WindowManager manager = device.service.getSystemService(WindowManager.class); assertNotNull(manager);
                result.set(new Snapshot(display.getRotation(), new Rect(manager.getCurrentWindowMetrics().getBounds()),
                        device.service.getResources().getConfiguration().orientation,
                        device.context.getResources().getConfiguration().orientation));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Could not read actual rotation", failure.get());
        return result.get();
    }

    static final class Snapshot {
        final int rotation, serviceOrientation, applicationOrientation;
        final Rect bounds;
        Snapshot(int rotation, Rect bounds, int serviceOrientation, int applicationOrientation) {
            this.rotation = rotation; this.bounds = bounds;
            this.serviceOrientation = serviceOrientation; this.applicationOrientation = applicationOrientation;
        }
        boolean matches(int expected) {
            boolean landscape = expected == 1 || expected == 3;
            int orientation = landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
            return rotation == expected && serviceOrientation == orientation && applicationOrientation == orientation
                    && !bounds.isEmpty() && (landscape ? bounds.width() > bounds.height() : bounds.height() > bounds.width());
        }
        @Override public String toString() {
            return "{rotation=" + rotation + ",bounds=" + bounds + ",service=" + serviceOrientation
                    + ",application=" + applicationOrientation + "}";
        }
    }
}
