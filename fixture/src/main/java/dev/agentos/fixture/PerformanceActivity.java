package dev.agentos.fixture;

import android.app.Activity;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Display;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Separate synthetic target for passive process/frame measurements; no production hooks. */
public final class PerformanceActivity extends Activity {
    static final String SAMPLE = "Controller integration sample";
    private HandlerThread frameThread;
    private Handler frameHandler;
    private final Handler drawReadHandler = Handler.createAsync(Looper.getMainLooper());
    private DisplayManager displays;
    private EditText editor;
    private TextView saved, count;
    private int clicks;
    private long edits, saves, increments;
    private String instance;
    private ViewTreeObserver.OnDrawListener drawListener;
    private final Window.OnFrameMetricsAvailableListener frameListener =
            (window, metrics, dropped) -> PerformanceProbe.frame(instance, metrics, dropped);
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { refreshDisplay(id, true); }
        @Override public void onDisplayRemoved(int id) { refreshDisplay(id, true); }
        @Override public void onDisplayChanged(int id) { refreshDisplay(id, true); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        instance = PerformanceProbe.created();
        getWindow().setDecorFitsSystemWindows(false);
        frameThread = new HandlerThread("fixture-frame-metrics");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        PerformanceProbe.setFrameHandler(instance, frameHandler);
        getWindow().addOnFrameMetricsAvailableListener(frameListener, frameHandler);
        displays = getSystemService(DisplayManager.class);
        displays.registerDisplayListener(displayListener, frameHandler);
        refreshDisplay(getDisplay().getDisplayId(), false);

        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.fixture_scroll);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(180), dp(24), dp(32));
        scroll.addView(content);
        editor = new EditText(this);
        editor.setId(R.id.edit_value);
        editor.setSingleLine(true);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        editor.setHint("Test text");
        editor.setContentDescription("Fixture text editor");
        editor.setMinHeight(dp(48));
        content.addView(editor);
        saved = new TextView(this);
        saved.setId(R.id.saved_value);
        saved.setText("Saved: empty");
        Button save = button("Save test text", R.id.save_value);
        save.setOnClickListener(view -> {
            saved.setText("Saved: " + editor.getText());
            saves++;
            changed();
        });
        content.addView(save);
        content.addView(saved);
        count = new TextView(this);
        count.setId(R.id.counter_value);
        count.setText("Count: 0");
        Button increment = button("Increment test counter", R.id.increment);
        increment.setOnClickListener(view -> {
            count.setText("Count: " + ++clicks);
            increments++;
            changed();
        });
        content.addView(increment);
        content.addView(count);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { edits++; changed(); }
        });
        setContentView(scroll);
        drawListener = () -> {
            PerformanceProbe.DrawObservation observation = PerformanceProbe.observeDraw(instance,
                    getWindow().getDecorView().isHardwareAccelerated());
            // ViewRootImpl dispatches OnDraw before updating the public drawing time.
            // Read it after this traversal; the probe rejects a crossing into a later draw.
            // An async message avoids a later traversal's synchronization barrier;
            // it still cannot run until the current main-thread draw has returned.
            drawReadHandler.post(() -> PerformanceProbe.completeDraw(observation, scroll.getDrawingTime()));
        };
        scroll.getViewTreeObserver().addOnDrawListener(drawListener);
    }

    private void changed() {
        PerformanceProbe.changed(instance, editor.getText().toString(), saved.getText().toString(), clicks,
                edits, saves, increments);
    }

    private void refreshDisplay(int id, boolean changed) {
        Display display = displays == null ? null : displays.getDisplay(id);
        if (display != null && id == getDisplay().getDisplayId()) {
            PerformanceProbe.display(instance, id, display.getRefreshRate(), display.getMode().getRefreshRate(), changed);
        }
    }

    @Override protected void onStart() { super.onStart(); PerformanceProbe.lifecycle(instance, "start"); }
    @Override protected void onResume() { super.onResume(); PerformanceProbe.lifecycle(instance, "resume"); }
    @Override protected void onPause() { PerformanceProbe.lifecycle(instance, "pause"); super.onPause(); }
    @Override protected void onStop() { PerformanceProbe.lifecycle(instance, "stop"); super.onStop(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        PerformanceProbe.lifecycle(instance, "newIntent");
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        PerformanceProbe.focused(instance, focused);
    }
    @Override protected void onDestroy() {
        PerformanceProbe.lifecycle(instance, "destroy");
        getWindow().removeOnFrameMetricsAvailableListener(frameListener);
        if (drawListener != null) getWindow().getDecorView().getViewTreeObserver().removeOnDrawListener(drawListener);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        if (frameThread != null) frameThread.quitSafely();
        super.onDestroy();
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
