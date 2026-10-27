package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ImageAttachment;
import dev.agentos.core.ImageWorkset;
import dev.agentos.core.PngImage;
import dev.agentos.core.TaskControlException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.agentos.app.DeviceHarness.*;
import static org.junit.Assert.*;

/** Synthetic hardware pixels, real configuration/window permits, real bitmap/PNG conversion; no native capture. */
@RunWith(AndroidJUnit4.class)
public final class WindowPngConverterInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();
    private static final int[] COLORS = {0xffe63946, 0xff2a9d8f, 0x80447a9c, 0xfff4a261};

    @Test public void actualRgbaPixelsAndAlphaSurvivePngWhileOnlyFinalEncodingRemainsOwned() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            SyntheticSource input = env.pattern(ColorSpace.get(ColorSpace.Named.SRGB), false);
            WindowBufferCapturer.CapturedWindow captured = env.capture(input);
            try (PngImage image = new WindowPngConverter().convert(captured)) {
                assertTrue(input.buffer.isClosed()); assertTrue(captured.isActuallyClosed());
                await(() -> !WindowBufferCapturer.hasActiveCapture(), "Conversion hold did not settle");
                assertEquals(image.encodedBytes, ImageWorkset.process().reservedBytes());
                assertPngColors(image, env.bounds.width(), env.bounds.height());
            }
            env.assertDrained();
            screenshots.capture("synthetic-rgba-alpha-real-png-conversion-only-final-encoding-owned");
        }
    }

    @Test public void actualWideGamutInputIsRejectedBeforeSoftwareCopyWithoutRelabeling() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            SyntheticSource input = env.pattern(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), false);
            assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), input.colorSpace);
            WindowBufferCapturer.CapturedWindow captured = env.capture(input);
            AtomicInteger copies = new AtomicInteger();
            WindowPngConverter converter = WindowPngConverter.forTesting(hardware -> {
                copies.incrementAndGet(); return hardware.copy(Bitmap.Config.ARGB_8888, false);
            }, (bitmap, output) -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            assertFailure(WindowPngConverter.Failure.UNSUPPORTED_FORMAT, () -> converter.convert(captured));
            assertEquals(0, copies.get()); assertTrue(input.buffer.isClosed()); env.assertDrained();
            screenshots.capture("actual-p3-synthetic-source-rejected-without-color-space-relabeling");
        }
    }

    @Test public void exhaustedKnownCapacityRejectsBeforeInvokingTheSoftwareAllocator() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            SyntheticSource input = env.pattern(ColorSpace.get(ColorSpace.Named.SRGB), false);
            WindowBufferCapturer.CapturedWindow captured = env.capture(input);
            long rawBytes = (long) env.bounds.width() * env.bounds.height() * 4;
            try (OwnedWindowBuffer.ReadLease reader = captured.openRead()) {
                assertEquals(rawBytes, reader.reduceToValidatedLogicalBytes());
            }
            // Fill after raw's truthful 8->4 logical reaccount, so that reducing it
            // in convert cannot itself create enough capacity for the full copy.
            AtomicInteger copies = new AtomicInteger();
            WindowPngConverter converter = WindowPngConverter.forTesting(hardware -> {
                copies.incrementAndGet(); return hardware.copy(Bitmap.Config.ARGB_8888, false);
            }, (bitmap, output) -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            long remaining = ImageWorkset.process().limitBytes() - ImageWorkset.process().reservedBytes();
            try (ImageWorkset.Reservation filler = ImageWorkset.process().reserve(remaining)) {
                assertFailure(WindowPngConverter.Failure.BUDGET_UNAVAILABLE, () -> converter.convert(captured));
                assertEquals(0, copies.get()); assertTrue(input.buffer.isClosed());
                await(() -> !WindowBufferCapturer.hasActiveCapture(), "Budget refusal retained conversion slot");
                assertEquals(remaining, ImageWorkset.process().reservedBytes());
            }
            env.assertDrained(); screenshots.capture("full-known-capacity-rejects-before-software-copy");
        }
    }

    @Test public void anActualLargerAllocationIsRejectedAndRecycledWithoutClaimingAPhysicalPeakGuarantee() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            SyntheticSource input = env.pattern(ColorSpace.get(ColorSpace.Named.SRGB), false);
            WindowBufferCapturer.CapturedWindow captured = env.capture(input);
            AtomicReference<Bitmap> allocated = new AtomicReference<>();
            WindowPngConverter converter = WindowPngConverter.forTesting(hardware -> {
                Bitmap result = hardware.copy(Bitmap.Config.RGBA_F16, false);
                allocated.set(result); return result;
            }, (bitmap, output) -> { throw new AssertionError("Over-reservation must reject before encoding"); });
            try (PngImage unexpected = converter.convert(captured)) { fail("Larger actual allocation was accepted"); }
            catch (WindowPngConverter.ConversionException failure) {
                assertEquals(WindowPngConverter.Failure.ALLOCATION_EXCEEDED, failure.code);
                assertEquals((long) env.bounds.width() * env.bounds.height() * 4, failure.reservedBytes);
                assertTrue(failure.actualAllocatedBytes > failure.reservedBytes);
            }
            assertNotNull(allocated.get()); assertTrue(allocated.get().isRecycled());
            assertTrue(input.buffer.isClosed()); env.assertDrained();
            screenshots.capture("actual-larger-bitmap-allocation-rejected-and-worker-recycled");
        }
    }

    @Test public void cancellationCannotRecyclePixelsOrRawWhileTheCopyCallIsStillExecuting() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            SyntheticSource input = env.pattern(ColorSpace.get(ColorSpace.Named.SRGB), false);
            WindowBufferCapturer.CapturedWindow captured = env.capture(input);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Bitmap> allocated = new AtomicReference<>();
            WindowPngConverter converter = WindowPngConverter.forTesting(hardware -> {
                Bitmap result = hardware.copy(Bitmap.Config.ARGB_8888, false); allocated.set(result); entered.countDown();
                boolean returned = false;
                try {
                    if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("Test copy barrier expired");
                    returned = true; return result;
                } finally { if (!returned && result != null) result.recycle(); }
            }, (bitmap, output) -> { throw new AssertionError("Stopped copy must not encode"); });
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<Throwable> result = worker.submit(() -> conversionFailure(converter, captured));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                env.device.control.pause(); captured.close();
                assertFalse("The converter reader still borrows the raw handle", input.buffer.isClosed());
                assertNotNull(allocated.get()); assertFalse(allocated.get().isRecycled());
                assertTrue(WindowBufferCapturer.hasActiveCapture());
                release.countDown();
                Throwable stopped = result.get(4, TimeUnit.SECONDS);
                assertTrue(String.valueOf(stopped), stopped instanceof TaskControlException);
                assertEquals("TASK_PAUSED", ((TaskControlException) stopped).getCode());
                assertTrue(allocated.get().isRecycled()); assertTrue(input.buffer.isClosed()); env.assertDrained();
            } finally {
                env.device.control.cancel(); release.countDown();
                worker.shutdown(); assertTrue("Actual converter worker failed to exit", worker.awaitTermination(6, TimeUnit.SECONDS));
            }
            screenshots.capture("cancelled-copy-keeps-raw-and-software-pixels-until-actual-worker-return");
        }
    }

    @Test public void encodingRetainsTheOriginalSlotAfterRawCloseAndPreservesCancellationFromStreamWrites() throws Exception {
        try (Environment env = new Environment()) {
            if (unsupported(env)) return;
            SyntheticSource input = env.pattern(ColorSpace.get(ColorSpace.Named.SRGB), false);
            WindowBufferCapturer.CapturedWindow captured = env.capture(input);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Bitmap> encoding = new AtomicReference<>();
            WindowPngConverter converter = WindowPngConverter.forTesting(hardware -> hardware.copy(Bitmap.Config.ARGB_8888, false),
                    (bitmap, output) -> {
                        encoding.set(bitmap); entered.countDown();
                        if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("Test PNG barrier expired");
                        // Bitmap.compress may swallow OutputStream IOException. The
                        // converter must restore the original task-control failure.
                        return bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    });
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<Throwable> result = worker.submit(() -> conversionFailure(converter, captured));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(input.buffer.isClosed()); assertTrue(captured.isActuallyClosed());
                assertTrue(WindowBufferCapturer.hasActiveCapture());
                long softwareBytes = (long) env.bounds.width() * env.bounds.height() * 4;
                assertEquals(softwareBytes + ImageAttachment.MAX_ENCODED_BYTES, ImageWorkset.process().reservedBytes());
                try { env.startEmpty(); fail("Raw close must not permit another capture while encoding"); }
                catch (WindowBufferCapturer.CaptureException busy) { assertEquals(WindowBufferCapturer.Failure.BUSY, busy.code); }
                env.device.control.cancel(); captured.close();
                assertFalse(encoding.get().isRecycled()); assertTrue(WindowBufferCapturer.hasActiveCapture());
                release.countDown();
                Throwable stopped = result.get(4, TimeUnit.SECONDS);
                assertTrue(String.valueOf(stopped), stopped instanceof TaskControlException);
                assertEquals("TASK_STOPPED", ((TaskControlException) stopped).getCode());
                assertTrue(encoding.get().isRecycled()); env.assertDrained();
            } finally {
                env.device.control.cancel(); release.countDown();
                worker.shutdown(); assertTrue("Actual encoder worker failed to exit", worker.awaitTermination(6, TimeUnit.SECONDS));
            }
            screenshots.capture("raw-closed-but-encoding-holds-slot-until-real-compress-and-cancel-cleanup");
        }
    }

    @Test public void realLargeWindowGeometryScalesWithinLimitsAndNoisyPngStopsAtFixedCapacity() throws Exception {
        if (Build.VERSION.SDK_INT < 34) {
            try (Environment env = new Environment()) { assertTrue(unsupported(env)); }
            return;
        }
        // Only this case changes a global test setting. Save the exact prior
        // override, set before service/control Views are created, restore after
        // those Views and every capture resource are really closed.
        // Keep the dedicated 320x640 emulator's aspect ratio and use an
        // integer enlargement. The previous 900x1600 override exposed a
        // 900-versus-899 window-metadata disagreement before capture began.
        // Actual bounds must still match exactly; no production guard is relaxed.
        try (DisplaySizeOverride display = new DisplaySizeOverride(960, 1920)) {
            try (Environment env = new Environment()) {
                assertEquals(1920, Math.max(env.bounds.width(), env.bounds.height()));
                assertEquals(960, Math.min(env.bounds.width(), env.bounds.height()));
                assertArrayEquals(new int[]{1024, 1024}, WindowPngConverter.targetSize(16384, 16384));
                assertArrayEquals(new int[]{1, 1280}, WindowPngConverter.targetSize(1, 100000));
                assertArrayEquals(new int[]{37, 53}, WindowPngConverter.targetSize(37, 53));
                SyntheticSource pattern = env.pattern(ColorSpace.get(ColorSpace.Named.SRGB), false);
                try (PngImage image = new WindowPngConverter().convert(env.capture(pattern))) {
                    int[] target = WindowPngConverter.targetSize(env.bounds.width(), env.bounds.height());
                    assertEquals(1280, Math.max(image.width, image.height));
                    assertEquals(640, Math.min(image.width, image.height));
                    assertTrue((long) image.width * image.height <= ImageAttachment.MAX_PIXELS);
                    assertPngColors(image, target[0], target[1]);
                }
                env.assertDrained();
                // Prepare the test's random source before the capture deadline.
                // This uses the real encoder, not a fabricated oversized output.
                SyntheticSource noise = env.pattern(ColorSpace.get(ColorSpace.Named.SRGB), true);
                assertFailure(WindowPngConverter.Failure.PNG_TOO_LARGE,
                        () -> new WindowPngConverter().convert(env.capture(noise)));
                assertTrue(noise.buffer.isClosed()); env.assertDrained();
                screenshots.capture("real-large-fixture-bounds-synthetic-pixels-resized-and-real-png-overflow-rejected");
            }
        }
    }

    private boolean unsupported(Environment env) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) return false;
        try { env.startEmpty(); fail("Low API must not sign a window capture permit"); }
        catch (AndroidDeviceTools.WindowCaptureException unavailable) {
            assertEquals(AndroidDeviceTools.WindowCaptureFailure.UNSUPPORTED_API, unavailable.code);
        }
        env.assertDrained(); env.assertNoCaptureCapability();
        screenshots.capture("png-window-conversion-unavailable-below-api34-no-native-call"); return true;
    }
    private static Throwable conversionFailure(WindowPngConverter converter, WindowBufferCapturer.CapturedWindow source) {
        try (PngImage unexpected = converter.convert(source)) { return new AssertionError("Expected conversion cancellation"); }
        catch (Throwable failed) { return failed; }
    }
    private static void assertPngColors(PngImage image, int width, int height) throws Exception {
        Bitmap decoded;
        try (PngImage.ReadLease reader = image.openRead()) { decoded = BitmapFactory.decodeStream(reader); }
        assertNotNull(decoded);
        try {
            assertEquals(width, image.width); assertEquals(height, image.height);
            assertEquals(width, decoded.getWidth()); assertEquals(height, decoded.getHeight());
            int[] actual = {decoded.getPixel(width / 4, height / 4), decoded.getPixel(width * 3 / 4, height / 4),
                    decoded.getPixel(width / 4, height * 3 / 4), decoded.getPixel(width * 3 / 4, height * 3 / 4)};
            for (int i = 0; i < COLORS.length; i++) {
                assertEquals(Color.alpha(COLORS[i]), Color.alpha(actual[i]));
                assertTrue(Math.abs(Color.red(COLORS[i]) - Color.red(actual[i])) <= 2);
                assertTrue(Math.abs(Color.green(COLORS[i]) - Color.green(actual[i])) <= 2);
                assertTrue(Math.abs(Color.blue(COLORS[i]) - Color.blue(actual[i])) <= 2);
            }
        } finally { decoded.recycle(); } // Never attached to a View/renderer.
    }

    private static final class SyntheticSource {
        final HardwareBuffer buffer;
        final ColorSpace colorSpace;
        volatile boolean transferred;
        SyntheticSource(HardwareBuffer buffer, ColorSpace colorSpace) { this.buffer = buffer; this.colorSpace = colorSpace; }
    }
    private static final class Environment implements AutoCloseable {
        DeviceHarness device;
        LocalControllerModelServer server;
        ControllerTestSettings settings;
        AndroidDeviceTools tools;
        Rect bounds;
        final List<SyntheticSource> sources = new ArrayList<>();
        final List<WindowBufferCapturer.CaptureRequest> requests = new ArrayList<>();
        final List<WindowBufferCapturer.CapturedWindow> captured = new ArrayList<>();
        Environment() throws Exception {
            try {
                await(() -> !WindowBufferCapturer.hasActiveCapture() && ImageWorkset.process().reservedBytes() == 0,
                        "Previous image resources have not drained");
                device = new DeviceHarness(); server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(device.context, server);
                tools = new AndroidDeviceTools(device.service, Collections.singleton(FIXTURE),
                        Collections.singletonMap(FIXTURE, SettingsStore.captureGrant(device.context, FIXTURE)),
                        ActionConfirmationPolicy.Mode.CONFIRM_ALL, settings.lease,
                        (message, owner, epoch) -> { throw new AssertionError("Conversion is not disclosure approval"); });
                device.launch("CapturePatternActivity"); observe(); bounds = focusedBounds(device);
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        JsonObject observe() throws Exception { return awaitObservation(tools, device.control, data -> FIXTURE.equals(string(data, "packageName"))); }
        SyntheticSource pattern(ColorSpace colorSpace, boolean noisy) {
            if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("Synthetic window sources require API 34 or newer");
            Bitmap software = null, hardware = null; HardwareBuffer buffer = null;
            boolean transferred = false;
            try {
                software = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888, true, colorSpace);
                if (noisy) {
                    Random random = new Random(0x42b3L);
                    int[] row = new int[bounds.width()];
                    for (int y = 0; y < bounds.height(); y++) {
                        for (int x = 0; x < row.length; x++) row[x] = 0xff000000 | random.nextInt(0x1000000);
                        software.setPixels(row, 0, row.length, 0, y, row.length, 1);
                    }
                } else {
                    Canvas canvas = new Canvas(software); Paint paint = new Paint();
                    try {
                        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                            paint.setColor(COLORS[y * 2 + x]);
                            canvas.drawRect(x * bounds.width() / 2f, y * bounds.height() / 2f,
                                    (x + 1) * bounds.width() / 2f, (y + 1) * bounds.height() / 2f, paint);
                        }
                    } finally { canvas.setBitmap(null); }
                }
                hardware = software.copy(Bitmap.Config.HARDWARE, false); assertNotNull(hardware);
                buffer = hardware.getHardwareBuffer(); assertNotNull(buffer);
                SyntheticSource source = new SyntheticSource(buffer, hardware.getColorSpace());
                sources.add(source); transferred = true; return source;
            } finally {
                if (hardware != null) hardware.recycle(); if (software != null) software.recycle();
                if (!transferred && buffer != null) buffer.close();
            }
        }
        WindowBufferCapturer.CapturedWindow capture(SyntheticSource source) throws Exception {
            // Source pixels are ready before B1's fixed request/permit deadlines.
            JsonObject current = observe();
            WindowBufferCapturer.CaptureRequest request = WindowBufferCapturer.forTesting(tools, (windowId, callback) -> {
                assertTrue(windowId >= 0); source.transferred = true;
                callback.success(source.buffer, source.colorSpace, SystemClock.uptimeMillis());
            }).start(device.control, device.control.getExecutionEpoch(), string(current, "revision"));
            requests.add(request);
            WindowBufferCapturer.CapturedWindow result = request.awaitAndClaim(); captured.add(result); return result;
        }
        void startEmpty() throws Exception {
            JsonObject current = observe();
            WindowBufferCapturer.CaptureRequest request = WindowBufferCapturer.forTesting(tools,
                    (windowId, callback) -> callback.failure(91))
                    .start(device.control, device.control.getExecutionEpoch(), string(current, "revision"));
            requests.add(request);
        }
        void assertDrained() throws Exception {
            await(() -> requests.stream().allMatch(WindowBufferCapturer.CaptureRequest::isWorkerDone)
                    && !WindowBufferCapturer.hasActiveCapture(), "Conversion/native ownership did not drain");
            assertEquals(0, ImageWorkset.process().reservedBytes()); assertNoCaptureCapability();
        }
        void assertNoCaptureCapability() {
            assertEquals(0, device.control.getDispatchAttempts()); assertEquals(0, server.requestCount());
            assertEquals(0, device.service.getServiceInfo().getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
            assertFalse(tools.availableTools().contains("take_screenshot"));
        }
        @Override public void close() throws Exception {
            Throwable error = null;
            try {
                if (device != null) device.control.cancel();
                for (WindowBufferCapturer.CapturedWindow result : captured) result.close();
                for (WindowBufferCapturer.CaptureRequest request : requests) request.close();
                await(() -> requests.stream().allMatch(WindowBufferCapturer.CaptureRequest::isWorkerDone), "Capture workers did not actually return");
                await(() -> !WindowBufferCapturer.hasActiveCapture(), "Conversion cleanup did not actually close resources");
                for (SyntheticSource source : sources) if (source.transferred)
                    assertTrue("Transferred raw handle was not closed by its actual owner", source.buffer.isClosed());
                assertEquals(0, ImageWorkset.process().reservedBytes());
            } catch (Exception | Error failed) { error = failed; }
            finally {
                for (SyntheticSource source : sources) if (!source.transferred) {
                    try { source.buffer.close(); } catch (RuntimeException | Error failed) { error = append(error, failed); }
                }
                try { if (settings != null) settings.close(); }
                catch (Exception | Error failed) { error = append(error, failed); }
                finally {
                    try { if (server != null) server.close(); }
                    catch (Exception | Error failed) { error = append(error, failed); }
                    finally { try { if (device != null) device.close(); } catch (Exception | Error failed) { error = append(error, failed); } }
                }
            }
            rethrow(error);
        }
    }

    /** Exactly restores the original wm override; no hidden unconditional reset. */
    private static final class DisplaySizeOverride implements AutoCloseable {
        final UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final String originalPhysical, originalOverride;
        boolean changed;
        DisplaySizeOverride(int width, int height) throws Exception {
            String before = shell(automation, "wm size");
            originalPhysical = dimension(before, "Physical"); originalOverride = dimension(before, "Override");
            assertNotNull("Physical size is required to restore the test display", originalPhysical);
            try {
                changed = true; shell(automation, "wm size " + width + "x" + height);
                await(() -> metricsMatch(width, height), "Requested real display dimensions did not settle");
                assertEquals(width + "x" + height, dimension(shell(automation, "wm size"), "Override"));
            } catch (Exception | Error failed) { try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); } throw failed; }
        }
        @Override public void close() throws Exception {
            if (!changed) return;
            shell(automation, originalOverride == null ? "wm size reset" : "wm size " + originalOverride);
            String expected = originalOverride == null ? originalPhysical : originalOverride;
            String[] parts = expected.split("x"); int width = Integer.parseInt(parts[0]), height = Integer.parseInt(parts[1]);
            await(() -> metricsMatch(width, height), "Original real display dimensions were not restored");
            String after = shell(automation, "wm size");
            assertEquals(originalPhysical, dimension(after, "Physical")); assertEquals(originalOverride, dimension(after, "Override"));
            automation.waitForIdle(500, 8_000); changed = false;
        }
        private static String dimension(String output, String prefix) {
            Matcher found = Pattern.compile("(?m)^" + prefix + " size: ([0-9]+x[0-9]+)\\s*$").matcher(output);
            return found.find() ? found.group(1) : null;
        }
        private static boolean metricsMatch(int width, int height) {
            Rect actual = InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(WindowManager.class)
                    .getMaximumWindowMetrics().getBounds();
            return Math.max(actual.width(), actual.height()) == Math.max(width, height)
                    && Math.min(actual.width(), actual.height()) == Math.min(width, height);
        }
    }
    private static String shell(UiAutomation automation, String command) throws Exception {
        ParcelFileDescriptor pipe = automation.executeShellCommand(command);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<String> output = reader.submit(() -> {
            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 16_384) throw new IOException("Unexpected bounded display command output");
                }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        });
        try { return output.get(5, TimeUnit.SECONDS); }
        finally {
            pipe.close(); output.cancel(true); reader.shutdownNow();
            assertTrue("Display command reader did not exit", reader.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
    private static Rect focusedBounds(DeviceHarness device) {
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            Rect found = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) {
                assertNull(found); found = new Rect(); window.getBoundsInScreen(found);
            }
            assertNotNull(found); return found;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }
    private static void assertFailure(WindowPngConverter.Failure expected, CheckedAction action) throws Exception {
        try (PngImage unexpected = action.run()) { fail("Expected conversion failure " + expected); }
        catch (WindowPngConverter.ConversionException failure) { assertEquals(failure.toString(), expected, failure.code); }
    }
    private interface CheckedAction { PngImage run() throws Exception; }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; if (first != next) first.addSuppressed(next); return first; }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw new IllegalStateException(failure);
    }
}
