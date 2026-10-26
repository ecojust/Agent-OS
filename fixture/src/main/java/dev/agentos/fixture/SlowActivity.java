package dev.agentos.fixture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Simulates a slow remote accessibility provider without changing production execution code. */
public final class SlowActivity extends Activity {
    private static WeakReference<SlowLayout> active = new WeakReference<>(null);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SlowLayout content = new SlowLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, Math.round(220 * getResources().getDisplayMetrics().density), 32, 32);
        TextView count = new TextView(this);
        count.setId(R.id.counter_value);
        count.setText("Count: 0");
        Button increment = new Button(this);
        increment.setId(R.id.increment);
        increment.setText("Increment slow test counter");
        increment.setOnClickListener(view -> count.setText("Count: 1"));
        content.addView(increment);
        content.addView(count);
        setContentView(content);
        active = new WeakReference<>(content);
    }

    static void arm() {
        SlowLayout view = active.get();
        if (view == null || !view.isAttachedToWindow()) return;
        view.armed = true;
        view.generation++;
        AccessibilityEvent changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        changed.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        // Invalidate the cache rather than requesting an eager node refresh before the test read.
        view.sendAccessibilityEventUnchecked(changed);
    }

    private static final class SlowLayout extends LinearLayout {
        boolean armed;
        int generation;
        SlowLayout(Context context) { super(context); }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setContentDescription("Slow observation generation " + generation);
            if (!armed) return;
            armed = false;
            getContext().sendBroadcast(new Intent("dev.agentos.fixture.OBSERVATION_BLOCKED")
                    .setPackage("dev.agentos.app")
                    .putExtra("startedAt", SystemClock.elapsedRealtime()));
            // This is a one-shot, bounded test delay in the fixture's separate process.
            SystemClock.sleep(650);
        }
    }
}
