package dev.agentos.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A synthetic action deliberately located underneath the compact stop controls. */
public final class CoveredActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout content = new FrameLayout(this);
        TextView count = new TextView(this);
        count.setId(R.id.counter_value);
        count.setText("Count: 0");
        FrameLayout.LayoutParams counterPosition = new FrameLayout.LayoutParams(-1, -2);
        counterPosition.leftMargin = dp(24);
        counterPosition.topMargin = dp(220);
        content.addView(count, counterPosition);
        Button button = new Button(this);
        button.setId(R.id.increment);
        button.setText("Covered test counter");
        button.setOnClickListener(view -> count.setText("Count: 1"));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, dp(48));
        position.leftMargin = dp(24);
        position.rightMargin = dp(24);
        position.topMargin = dp(48);
        content.addView(button, position);
        FixtureLayout.install(this, content);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
