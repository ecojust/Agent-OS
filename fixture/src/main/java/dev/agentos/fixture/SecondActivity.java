package dev.agentos.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class SecondActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, Math.round(220 * getResources().getDisplayMetrics().density), 32, 32);
        TextView marker = new TextView(this);
        marker.setId(R.id.second_marker);
        marker.setText("Second page reached");
        marker.setTextSize(20);
        content.addView(marker, new LinearLayout.LayoutParams(-1, -2));
        FixtureLayout.install(this, content);
    }
}
