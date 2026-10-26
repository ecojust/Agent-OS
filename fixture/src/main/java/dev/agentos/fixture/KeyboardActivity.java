package dev.agentos.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit foreground IME scenario, separate from fixtures that always hide it on launch. */
public final class KeyboardActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, Math.round(120 * getResources().getDisplayMetrics().density), 24, 24);
        content.setFocusableInTouchMode(true);
        TextView visible = new TextView(this);
        visible.setId(R.id.keyboard_visible);
        visible.setText("Keyboard visible: false");
        content.addView(visible);
        EditText editor = new EditText(this);
        editor.setId(R.id.edit_value);
        editor.setSingleLine(true);
        editor.setHint("Synthetic keyboard input");
        editor.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        content.addView(editor);
        Button open = new Button(this);
        open.setId(R.id.show_keyboard);
        open.setText("Show test keyboard");
        open.setAllCaps(false);
        open.setOnClickListener(view -> {
            editor.requestFocus();
            // This is the public foreground window API. No service or test injection
            // pretends that a keyboard appeared; the test also checks the IME window.
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.ime());
        });
        content.addView(open);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            String value = "Keyboard visible: " + insets.isVisible(WindowInsets.Type.ime());
            if (!value.contentEquals(visible.getText())) visible.setText(value);
            return insets;
        });
        FixtureLayout.install(this, content);
        content.requestFocus();
    }
}
