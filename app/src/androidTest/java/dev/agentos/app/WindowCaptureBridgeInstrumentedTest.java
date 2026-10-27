package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;

import dev.agentos.app.capturetest.CaptureBridgeClient;
import dev.agentos.app.capturetest.CaptureBridgeProvider;

import static org.junit.Assert.*;

/** A test-APK-only real platform smoke, not a production image-disclosure authorization. */
@RunWith(AndroidJUnit4.class)
public final class WindowCaptureBridgeInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void syntheticFixtureWindowCrossesTheRealTestProcessBridgeAndClosesBothHandles() throws Exception {
        try (DeviceHarness device = new DeviceHarness()) {
            device.launch("CapturePatternActivity");
            assertProductionVisualToolsRemainDisabled(device);
            ProviderInfo provider = device.context.getPackageManager().resolveContentProvider(CaptureBridgeProvider.AUTHORITY, 0);
            assertNotNull("The target UID must resolve the actual test APK provider", provider);
            assertEquals(device.instrumentation.getContext().getPackageName(), provider.packageName);
            assertEquals("dev.agentos.app.test", provider.packageName);
            assertNotEquals(Process.myUid(), provider.applicationInfo.uid);

            try (CaptureBridgeClient bridge = new CaptureBridgeClient(device.context, device.automation)) {
                device.awaitUiIdle();
                WindowMetadata window = focusedFixtureWindow(device);
                Bundle initial = bridge.status();
                assertEquals(Process.myUid(), initial.getInt("callingUid"));
                assertEquals(provider.applicationInfo.uid, initial.getInt("bridgeUid"));
                assertNotEquals(Process.myPid(), initial.getInt("bridgePid"));
                assertEquals(0, initial.getInt("platformCalls"));
                long requestedAt = SystemClock.uptimeMillis();
                try (CaptureBridgeClient.Result result = bridge.capture(window.id, window.bounds)) {
                    assertEquals("The separate test process must decode the framework proxy, not our callback subclass",
                            ResultReceiver.class.getName(), bridge.receiverClassFromBridge());
                    assertEquals(initial.getInt("bridgeUid"), result.metadata.getInt("senderUid"));
                    assertEquals(initial.getInt("bridgePid"), result.metadata.getInt("senderPid"));
                    if (Build.VERSION.SDK_INT < 34) {
                        assertEquals(CaptureBridgeClient.UNAVAILABLE, result.code);
                        assertEquals("API_BELOW_34", result.metadata.getString("reason"));
                        assertNull(result.buffer);
                        Bundle unsupported = bridge.awaitDrained();
                        assertEquals(0, unsupported.getInt("platformCalls"));
                        assertEquals(0, unsupported.getInt("received"));
                        assertEquals(0, bridge.ownedHandles());
                        screenshots.capture("window-capture-unavailable-no-platform-submission");
                    } else {
                        assertEquals(result.metadata.toString(), CaptureBridgeClient.SUCCESS, result.code);
                        assertCapturedPattern(result, window, requestedAt);
                    }
                }
                Bundle drained = bridge.awaitDrained();
                assertFalse(drained.getBoolean("pending"));
                assertEquals(0, drained.getInt("ownedHandles"));
                assertEquals(0, drained.getInt("deliveryFailures"));
                assertEquals(0, drained.getInt("closeFailures"));
                assertEquals(0, drained.getInt("lateClosed"));
                assertEquals(0, bridge.ownedHandles());
                assertEquals(0, bridge.lateClosedHandles());
                int captures = Build.VERSION.SDK_INT >= 34 ? 1 : 0;
                assertEquals(captures, drained.getInt("platformCalls"));
                assertEquals(captures, drained.getInt("received"));
                assertEquals(captures, drained.getInt("sent"));
                assertEquals(captures, drained.getInt("closedHandles"));
                assertEquals(captures, bridge.closedHandles());
                if (Build.VERSION.SDK_INT >= 34)
                    screenshots.capture("real-fixture-window-captured-received-and-both-handles-closed");
            }
            assertProductionVisualToolsRemainDisabled(device);
        }
    }

    @TargetApi(34) private static void assertCapturedPattern(CaptureBridgeClient.Result result,
                                                            WindowMetadata window, long requestedAt) {
        Bundle metadata = result.metadata;
        HardwareBuffer handle = result.buffer;
        assertNotNull(handle); assertFalse(handle.isClosed());
        assertEquals(window.id, metadata.getInt("windowId"));
        assertEquals(Display.DEFAULT_DISPLAY, metadata.getInt("displayId"));
        assertEquals(window.bounds, metadata.getParcelable("bounds"));
        assertEquals(window.bounds.width(), handle.getWidth());
        assertEquals(window.bounds.height(), handle.getHeight());
        assertEquals(handle.getWidth(), metadata.getInt("width"));
        assertEquals(handle.getHeight(), metadata.getInt("height"));
        assertEquals(handle.getId(), metadata.getLong("bufferId"));
        assertEquals(HardwareBuffer.RGBA_8888, handle.getFormat());
        assertEquals(1, handle.getLayers());
        assertEquals(handle.getFormat(), metadata.getInt("format"));
        assertEquals(handle.getLayers(), metadata.getInt("layers"));
        assertEquals(handle.getUsage(), metadata.getLong("usage"));
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB).getId(), metadata.getInt("colorSpaceId"));
        assertTrue(metadata.getLong("timestamp") >= requestedAt);
        assertTrue(metadata.getLong("timestamp") <= SystemClock.uptimeMillis());
        // This bounds only the synthetic test's explicit software copy. It is not
        // a measurement of gralloc, codec, renderer, or the production 24 MiB budget.
        assertTrue("Unexpected synthetic emulator size", (long) handle.getWidth() * handle.getHeight() * 4 <= 8L * 1024 * 1024);
        Bitmap wrapped = null, software = null;
        try {
            wrapped = Bitmap.wrapHardwareBuffer(handle, ColorSpace.get(ColorSpace.Named.SRGB));
            assertNotNull(wrapped);
            software = wrapped.copy(Bitmap.Config.ARGB_8888, false);
            assertNotNull(software);
            int width = software.getWidth(), height = software.getHeight();
            assertColor(0xffe63946, software.getPixel(width / 4, height / 4));
            assertColor(0xff2a9d8f, software.getPixel(width * 3 / 4, height / 4));
            assertColor(0xff457b9d, software.getPixel(width / 4, height * 3 / 4));
            assertColor(0xfff4a261, software.getPixel(width * 3 / 4, height * 3 / 4));
        } finally {
            // Neither test bitmap was handed to a View/RenderThread. Release these
            // independent references before the receiver's HardwareBuffer handle.
            if (software != null) software.recycle();
            if (wrapped != null) wrapped.recycle();
        }
    }

    private static void assertColor(int expected, int actual) {
        assertEquals("Opaque fixture alpha", 255, actual >>> 24);
        for (int shift : new int[]{16, 8, 0})
            assertTrue("Actual captured quadrant differs: expected " + Integer.toHexString(expected)
                    + " actual " + Integer.toHexString(actual), Math.abs((expected >> shift & 255) - (actual >> shift & 255)) <= 2);
    }

    private static void assertProductionVisualToolsRemainDisabled(DeviceHarness device) {
        assertEquals(0, device.service.getServiceInfo().getCapabilities()
                & (AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT | AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES));
        assertFalse(device.tools.availableTools().contains("take_screenshot"));
        assertFalse(device.tools.availableTools().contains("tap"));
        assertFalse(device.tools.availableTools().contains("swipe"));
    }

    private static WindowMetadata focusedFixtureWindow(DeviceHarness device) {
        AccessibilityServiceInfo info = device.service.getServiceInfo();
        assertNotNull(info);
        device.service.setServiceInfo(info);
        List<AccessibilityWindowInfo> windows = device.service.getWindows();
        try {
            AccessibilityWindowInfo focused = null;
            for (AccessibilityWindowInfo window : windows) if (window.isFocused()) {
                assertNull("Only one real focused window is accepted", focused); focused = window;
            }
            assertNotNull(focused);
            assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, focused.getType());
            assertEquals(Display.DEFAULT_DISPLAY, focused.getDisplayId());
            Rect bounds = new Rect(); focused.getBoundsInScreen(bounds); assertFalse(bounds.isEmpty());
            AccessibilityNodeInfo root = focused.getRoot(); assertNotNull(root);
            try {
                assertEquals(DeviceHarness.FIXTURE, String.valueOf(root.getPackageName()));
                assertEquals(focused.getId(), root.getWindowId());
            } finally { root.recycle(); }
            return new WindowMetadata(focused.getId(), bounds);
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static final class WindowMetadata {
        final int id; final Rect bounds;
        WindowMetadata(int id, Rect bounds) { this.id = id; this.bounds = bounds; }
    }
}
