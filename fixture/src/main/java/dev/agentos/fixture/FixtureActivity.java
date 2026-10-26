package dev.agentos.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A separate, offline app with deterministic state for device-tool integration tests. */
public final class FixtureActivity extends Activity {
    private int clicks;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Intentionally retain an edge-to-edge scroll container: normal system bars
        // must not be mistaken for an unrelated window obstructing every scroll.
        getWindow().setDecorFitsSystemWindows(false);
        clicks = state == null ? 0 : state.getInt("clicks");
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.fixture_scroll);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Leave enough space for the user-visible accessibility control overlay.
        content.setPadding(dp(24), dp(180), dp(24), dp(32));
        scroll.addView(content);

        EditText editor = new EditText(this);
        editor.setId(R.id.edit_value);
        editor.setSingleLine(true);
        editor.setHint("Test text");
        editor.setContentDescription("Fixture text editor");
        editor.setMinHeight(dp(48));
        content.addView(editor);
        TextView saved = new TextView(this);
        saved.setId(R.id.saved_value);
        saved.setText("Saved: empty");
        Button save = button("Save test text", R.id.save_value);
        save.setOnClickListener(view -> saved.setText("Saved: " + editor.getText()));
        content.addView(save);
        content.addView(saved);

        TextView count = new TextView(this);
        count.setId(R.id.counter_value);
        count.setText("Count: " + clicks);
        Button increment = button("Increment test counter", R.id.increment);
        increment.setOnClickListener(view -> count.setText("Count: " + ++clicks));
        content.addView(increment);
        content.addView(count);
        Button second = button("Open second test page", R.id.open_second);
        second.setOnClickListener(view -> startActivity(new Intent(this, SecondActivity.class)));
        content.addView(second);
        Button password = button("Open password test page", R.id.open_password);
        password.setOnClickListener(view -> startActivity(new Intent(this, PasswordActivity.class)));
        content.addView(password);
        for (int index = 0; index < 30; index++) {
            TextView row = new TextView(this);
            row.setText("Scrollable test row " + index);
            row.setMinHeight(dp(48));
            content.addView(row);
        }
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("clicks", clicks);
        super.onSaveInstanceState(outState);
    }

    private Button button(String title, int id) {
        Button button = new Button(this);
        button.setId(id);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
