package dev.agentos.fixture;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

/** Public synthetic pixels for the test APK's real, window-scoped platform capture. */
public final class CapturePatternActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);
        PatternView pattern = new PatternView(this);
        setContentView(pattern);
        pattern.requestFocus();
    }

    private static final class PatternView extends View {
        private final Paint paint = new Paint();

        PatternView(Context context) {
            super(context);
            setContentDescription("Synthetic window capture pattern");
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setWillNotDraw(false);
            paint.setAntiAlias(false);
            paint.setDither(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth(), height = getHeight();
            rectangle(canvas, 0, 0, width / 2, height / 2, 0xffe63946);
            rectangle(canvas, width / 2, 0, width, height / 2, 0xff2a9d8f);
            rectangle(canvas, 0, height / 2, width / 2, height, 0xff457b9d);
            rectangle(canvas, width / 2, height / 2, width, height, 0xfff4a261);
            float line = Math.max(1, getResources().getDisplayMetrics().density);
            rectangle(canvas, width / 2 - line, 0, width / 2 + line, height, Color.WHITE);
            rectangle(canvas, 0, height / 2 - line, width, height / 2 + line, Color.WHITE);
        }

        private void rectangle(Canvas canvas, float left, float top, float right, float bottom, int color) {
            paint.setColor(color);
            canvas.drawRect(left, top, right, bottom, paint);
        }
    }
}
