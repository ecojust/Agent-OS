package dev.agentos.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only exists in the separate test fixture APK. */
public final class SlowObservationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ("dev.agentos.fixture.ARM_SLOW_OBSERVATION".equals(intent.getAction())) SlowActivity.arm();
        if ("dev.agentos.fixture.ARM_SLOW_WRITE_PREPARATION".equals(intent.getAction())) SlowActivity.armRootWithoutChangingContent();
    }
}
