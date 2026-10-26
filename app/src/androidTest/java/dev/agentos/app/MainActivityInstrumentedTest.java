package dev.agentos.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class MainActivityInstrumentedTest {
    @Test public void nativeLauncherOpensWithoutStartingAnAgentTask() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            instrumentation.waitForIdleSync();
            assertNotNull(activity);
            long deadline = SystemClock.elapsedRealtime() + 3_000;
            while (!activity.hasWindowFocus() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            instrumentation.runOnMainSync(() -> {
                View content = activity.findViewById(android.R.id.content);
                assertNotNull(content);
                assertTrue(content.isShown());
                assertNotNull(activity.findViewById(R.id.start_task));
                assertNotNull(activity.findViewById(R.id.open_accessibility));
                assertNotNull(activity.findViewById(R.id.settings_api_key));
                assertFalse(activity.findViewById(R.id.settings_api_key).isSaveEnabled());
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertFalse(AgentController.get(activity).getState().running);
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
