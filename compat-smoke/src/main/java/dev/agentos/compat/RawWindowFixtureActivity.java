package dev.agentos.compat;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** A synthetic editor for the explicitly selected API37 shell diagnostic, never a product UI. */
public final class RawWindowFixtureActivity extends Activity {
    private EditText editor;
    private String sourceSha;
    private long runId;
    private long attempt;
    private long startedElapsedMs;
    private long showRequestedElapsedMs;
    private long firstImeVisibleElapsedMs;
    private boolean showScheduled;
    private boolean showAttempted;
    private boolean showAccepted;
    private boolean imeVisible;
    private int writes;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        sourceSha = intent.getStringExtra("sourceSha");
        String run = intent.getStringExtra("runId");
        String runAttempt = intent.getStringExtra("runAttempt");
        if (state != null || Build.VERSION.SDK_INT != 37
                || !intent.getBooleanExtra("rawWindowProbe", false)
                || sourceSha == null || !sourceSha.matches("[0-9a-f]{40}")
                || run == null || !run.matches("[1-9][0-9]{0,17}")
                || runAttempt == null || !runAttempt.matches("[1-9][0-9]{0,8}")) {
            finish();
            return;
        }
        runId = Long.parseLong(run);
        attempt = Long.parseLong(runAttempt);
        startedElapsedMs = SystemClock.elapsedRealtime();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 32, 24, 24);
        TextView title = new TextView(this);
        title.setText("Agent OS window diagnostic\nSynthetic editor — one keyboard request");
        root.addView(title);
        editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setText("Synthetic input window sample");
        // Focus is established explicitly; automatic focus-driven IME display is disabled.
        editor.setShowSoftInputOnFocus(false);
        root.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (showAttempted && !imeVisible && insets.isVisible(WindowInsets.Type.ime())) {
                imeVisible = true;
                firstImeVisibleElapsedMs = SystemClock.elapsedRealtime();
                writeReceipt("ime_visible");
            }
            return view.onApplyWindowInsets(insets);
        });
        setContentView(root);
        editor.requestFocus();
        writeReceipt("created");
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || editor == null || showScheduled) return;
        showScheduled = true;
        // ViewRootImpl completes IME focus bookkeeping after dispatching this callback.
        // One posted request lets that bookkeeping finish; loss of focus is not retried.
        boolean posted = editor.post(() -> {
            if (isFinishing() || isDestroyed() || !editor.isFocused() || !editor.hasWindowFocus()) {
                writeReceipt("focus_lost");
                return;
            }
            showAttempted = true;
            showRequestedElapsedMs = SystemClock.elapsedRealtime();
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            showAccepted = manager != null
                    && manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            writeReceipt("show_requested");
        });
        if (!posted) writeReceipt("post_rejected");
    }

    private void writeReceipt(String phase) {
        // Three fixed state transitions, no polling, dump, screenshot or arbitrary content.
        if (writes >= 3) return;
        writes++;
        try {
            JSONObject source = new JSONObject().put("repository", "ecojust/Agent-OS")
                    .put("commit", sourceSha).put("runId", runId).put("attempt", attempt);
            JSONObject receipt = new JSONObject().put("schemaVersion", 1)
                    .put("kind", "agent-os-raw-window-fixture").put("source", source)
                    .put("phase", phase).put("sdkInt", Build.VERSION.SDK_INT)
                    .put("uid", Process.myUid()).put("displayId", getDisplay() == null
                            ? -1 : getDisplay().getDisplayId())
                    .put("startedElapsedMs", startedElapsedMs)
                    .put("showImeAttempts", showAttempted ? 1 : 0)
                    .put("showImeAccepted", showAttempted ? showAccepted : JSONObject.NULL)
                    .put("showRequestedElapsedMs", showAttempted
                            ? showRequestedElapsedMs : JSONObject.NULL)
                    .put("imeVisibleObserved", imeVisible)
                    .put("firstImeVisibleElapsedMs", imeVisible
                            ? firstImeVisibleElapsedMs : JSONObject.NULL)
                    .put("receiptWrites", writes)
                    .put("receiptWrittenElapsedMs", SystemClock.elapsedRealtime());
            byte[] bytes = (receipt.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 4096) return;
            try (FileOutputStream stream = openFileOutput("raw-window-fixture.json", MODE_PRIVATE)) {
                stream.write(bytes);
            }
        } catch (Exception ignored) {
            // Missing or partial receipts are rejected by the host; no fallback or second request.
        }
    }
}
