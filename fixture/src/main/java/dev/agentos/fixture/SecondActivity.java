package dev.agentos.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class SecondActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView marker = new TextView(this);
        marker.setId(R.id.second_marker);
        marker.setText("Second page reached");
        marker.setPadding(32, Math.round(220 * getResources().getDisplayMetrics().density), 32, 32);
        marker.setTextSize(20);
        setContentView(marker);
    }
}
