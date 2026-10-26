package dev.agentos.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DeviceCapabilitiesInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("non_visual_device_capabilities_test");

    private static DeviceCapabilities.DisplaySnapshot display() {
        return new DeviceCapabilities.DisplaySnapshot(320, 640, 0, 0, 160, 1f, 1.5f, false);
    }

    @Test public void reportUsesSystemConditionsWithoutCertifyingTheDevice() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeviceCapabilities.Report report = DeviceCapabilities.collect(context, display(), new CancellationSignal());
        assertTrue(report.text.contains("API " + Build.VERSION.SDK_INT));
        for (String abi : Build.SUPPORTED_ABIS) assertTrue(report.text.contains(abi));
        assertTrue(report.text.contains("应用资源报告的像素：320 × 640 px"));
        assertTrue(report.text.contains("系统报告的最大窗口：未能读取"));
        assertTrue(report.text.contains("系统字体缩放：1.50 倍"));
        assertTrue(report.text.contains("可安装 APK，保留手机现有系统"));
        assertTrue(report.text.contains("普通应用：安装、启动和日常使用由原系统处理，不经过 Agent"));
        assertTrue(report.text.contains("本机真机验收：未验证"));
        assertTrue(report.text.contains("性能验收：未验证"));
        assertTrue(report.text.contains("没有创建 WebView 或发起网络请求"));
        assertTrue(report.text.contains("截图和坐标手势未开放"));
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager != null) {
            assertTrue(report.text.contains("系统 lowRam 标记：" + (manager.isLowRamDevice() ? "是" : "否")));
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            boolean expectedHint = manager.isLowRamDevice() || memory.totalMem > 0 && memory.totalMem <= 4L * 1024 * 1024 * 1024;
            assertEquals(expectedHint, report.lowResourceHint);
        }
        assertEquals(7, report.sections.size());
        assertThrows(UnsupportedOperationException.class, () -> report.sections.clear());
        assertThrows(UnsupportedOperationException.class, () -> report.sections.get(0).facts.clear());
    }

    @Test public void cancelledReportDoesNotContinueCollecting() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CancellationSignal cancelled = new CancellationSignal();
        cancelled.cancel();
        assertThrows(OperationCanceledException.class, () -> DeviceCapabilities.collect(context, display(), cancelled));
    }

    @Test public void slowerCollectionCannotRunOnMainThread() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertThrows(IllegalStateException.class,
                () -> DeviceCapabilities.collect(context, display(), new CancellationSignal())));
    }
}
