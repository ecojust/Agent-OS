package dev.agentos.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Uses a synthetic secret, never a real account or authentication service. */
public final class PasswordActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, Math.round(220 * getResources().getDisplayMetrics().density), 32, 32);
        TextView label = new TextView(this);
        label.setText("Password protection fixture");
        content.addView(label);
        EditText password = new EditText(this);
        password.setId(R.id.password_field);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("fixture-private-credential");
        password.setContentDescription("fixture-private-description");
        password.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        content.addView(password);
        setContentView(content);
    }
}
