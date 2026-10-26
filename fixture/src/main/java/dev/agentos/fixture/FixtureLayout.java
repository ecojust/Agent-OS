package dev.agentos.fixture;

import android.app.Activity;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Gives ordinary fixture controls the same usable bounds on API 30 and API 35. */
final class FixtureLayout {
    private FixtureLayout() { }

    static void install(Activity activity, View content) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        FrameLayout safeArea = new FrameLayout(activity);
        safeArea.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        safeArea.addView(content, new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(safeArea);
        safeArea.requestApplyInsets();
    }
}
