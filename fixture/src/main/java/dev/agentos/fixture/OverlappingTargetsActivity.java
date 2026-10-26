package dev.agentos.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Two real actionable ancestors at the same point, with independently observable click counts. */
public final class OverlappingTargetsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(180), dp(24), dp(24));
        TextView parentCount = new TextView(this);
        parentCount.setId(R.id.overlap_parent_count);
        parentCount.setText("Parent clicks: 0");
        TextView childCount = new TextView(this);
        childCount.setId(R.id.overlap_child_count);
        childCount.setText("Child clicks: 0");
        FrameLayout parent = new FrameLayout(this);
        parent.setId(R.id.overlap_parent);
        parent.setContentDescription("Overlapping clickable parent");
        parent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        parent.setBackgroundColor(Color.rgb(220, 230, 240));
        parent.setFocusable(true);
        int[] clicks = {0, 0};
        parent.setOnClickListener(view -> parentCount.setText("Parent clicks: " + ++clicks[0]));
        Button child = new Button(this);
        child.setId(R.id.overlap_child);
        child.setText("Nested clickable child");
        child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        child.setOnClickListener(view -> childCount.setText("Child clicks: " + ++clicks[1]));
        FrameLayout.LayoutParams childPosition = new FrameLayout.LayoutParams(-1, dp(64));
        childPosition.setMargins(dp(20), dp(48), dp(20), 0);
        parent.addView(child, childPosition);
        content.addView(parent, new LinearLayout.LayoutParams(-1, dp(180)));
        content.addView(parentCount);
        content.addView(childCount);
        FixtureLayout.install(this, content);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
