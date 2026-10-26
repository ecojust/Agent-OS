package dev.agentos.fixture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Simulates a slow remote accessibility provider without changing production execution code. */
public final class SlowActivity extends Activity {
    private static WeakReference<SlowLayout> active = new WeakReference<>(null);
    private static WeakReference<View> activeRoot = new WeakReference<>(null);
    private static boolean rootArmed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SlowLayout content = new SlowLayout(this);
        // A structural layout is normally omitted from the remote accessibility tree.
        // This node is deliberately observable so its one-shot delay can be exercised.
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, Math.round(220 * getResources().getDisplayMetrics().density), 32, 32);
        TextView count = new TextView(this);
        count.setId(R.id.counter_value);
        count.setText("Count: 0");
        Button increment = new Button(this);
        increment.setId(R.id.increment);
        increment.setText("Increment slow test counter");
        int[] clicks = {0};
        increment.setOnClickListener(view -> count.setText("Count: " + ++clicks[0]));
        content.addView(increment);
        content.addView(count);
        FixtureLayout.install(this, content);
        active = new WeakReference<>(content);
        rootArmed = false;
        View root = getWindow().getDecorView();
        activeRoot = new WeakReference<>(root);
        root.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (!rootArmed) return;
                rootArmed = false;
                host.getContext().sendBroadcast(new Intent("dev.agentos.fixture.OBSERVATION_BLOCKED")
                        .setPackage("dev.agentos.app").putExtra("readKind", "write-preparation-root")
                        .putExtra("startedAt", SystemClock.elapsedRealtime()));
                // Delay the root reply without changing any node semantics or revision.
                SystemClock.sleep(650);
            }
        });
    }

    static void armRootWithoutChangingContent() {
        View root = activeRoot.get();
        if (root == null || !root.isAttachedToWindow()) return;
        rootArmed = true;
        AccessibilityEvent changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        changed.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        root.sendAccessibilityEventUnchecked(changed);
        root.getContext().sendBroadcast(new Intent("dev.agentos.fixture.OBSERVATION_ARMED")
                .setPackage("dev.agentos.app").putExtra("readKind", "write-preparation-root"));
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
        view.getContext().sendBroadcast(new Intent("dev.agentos.fixture.OBSERVATION_ARMED")
                .setPackage("dev.agentos.app").putExtra("generation", view.generation));
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
