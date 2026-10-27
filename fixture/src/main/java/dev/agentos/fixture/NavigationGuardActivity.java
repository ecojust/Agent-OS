package dev.agentos.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Offline pages with real scroll affordances and separately observable navigation evidence. */
public final class NavigationGuardActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String scenario = getIntent().getStringExtra("scenario");
        if (scenario == null) scenario = "sensitive";
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(180), dp(24), dp(24));

        TextView marker = new TextView(this);
        marker.setId(R.id.navigation_case_marker);
        marker.setText("Navigation guard: " + scenario);
        page.addView(marker);
        TextView scrollCount = new TextView(this);
        scrollCount.setId(R.id.navigation_scroll_count);
        scrollCount.setText("Scroll changes: 0");
        page.addView(scrollCount);

        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.fixture_scroll);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if ("sensitive".equals(scenario)) {
            EditText password = new EditText(this);
            password.setId(R.id.password_field);
            password.setSingleLine(true);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setText("synthetic-navigation-secret");
            password.setContentDescription("synthetic-private-navigation-description");
            password.setMinHeight(dp(48));
            content.addView(password);
        } else if ("high-impact".equals(scenario)) {
            TextView risk = new TextView(this);
            risk.setText("Payment fixture — offline, no account or payment service");
            risk.setMinHeight(dp(48));
            content.addView(risk);
        } else if ("truncated".equals(scenario)) {
            TextView longField = new TextView(this);
            StringBuilder text = new StringBuilder("Long fixture field: ");
            while (text.length() <= 5000) text.append("synthetic detail ");
            longField.setText(text.toString());
            longField.setMaxLines(1);
            longField.setEllipsize(TextUtils.TruncateAt.END);
            longField.setMinHeight(dp(48));
            content.addView(longField);
        } else {
            throw new IllegalArgumentException("Unknown offline fixture scenario");
        }
        for (int index = 0; index < 24; index++) {
            TextView row = new TextView(this);
            row.setText("Navigation guard row " + index);
            row.setMinHeight(dp(48));
            content.addView(row);
        }
        int[] changes = {0};
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (x != oldX || y != oldY) scrollCount.setText("Scroll changes: " + ++changes[0]);
        });
        FixtureLayout.install(this, page);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
