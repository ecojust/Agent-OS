package dev.agentos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.agentos.core.ImageReviewSession;

/** Native presentation only. The session and secure host own authorization and lifetime. */
final class ImageReviewView extends LinearLayout {
    static final String PREVIEW = "image-review-preview";
    static final String RECIPIENT = "image-review-recipient";
    static final String APPROVE = "image-review-approve";
    static final String REJECT = "image-review-reject";
    static final String CONTENT = "image-review-content";

    private final PreviewImageView image;
    private final TextView status;
    private final Button approve;
    private final Button reject;
    private Bitmap preview;
    private DecodedPreview previewOwner;

    ImageReviewView(Context context, ImageReviewSession.Binding binding,
                    Runnable onApprove, Runnable onReject) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(12), dp(16), dp(12));
        setBackgroundColor(context.getColor(R.color.agent_surface));
        setSaveEnabled(false);

        TextView title = text("审阅本次图片", 20, R.color.agent_text);
        title.setAccessibilityHeading(true);
        addView(title, new LayoutParams(-1, -2));
        addView(text("请查看图片和模型接收方。确认后，仅此图片可发送到所示模型服务。", 14,
                R.color.agent_muted), new LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(context);
        scroll.setTag(CONTENT);
        scroll.setFillViewport(false);
        scroll.setSaveEnabled(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(0, dp(8), 0, dp(8));
        content.addView(text("目标应用\n" + binding.observation.packageName, 14,
                R.color.agent_text), new LayoutParams(-1, -2));
        TextView recipient = text("模型接收方\n" + binding.recipient.endpoint
                + "\n模型：" + binding.recipient.model, 14, R.color.agent_text);
        recipient.setTag(RECIPIENT);
        recipient.setPadding(0, dp(8), 0, dp(8));
        // No ellipsis, URL autolinking or copy action: the entire receiver remains scrollable.
        content.addView(recipient, new LayoutParams(-1, -2));

        image = new PreviewImageView(context);
        image.setTag(PREVIEW);
        image.setContentDescription("本次将发送的图片，可放大查看");
        image.setSaveEnabled(false);
        image.setBackgroundColor(context.getColor(R.color.agent_input));
        content.addView(image, new LayoutParams(-1, dp(260)));
        LinearLayout zoom = new LinearLayout(context);
        zoom.setGravity(Gravity.CENTER_VERTICAL);
        Button enlarge = button("放大图片", () -> image.enlarge());
        Button overview = button("显示全图", () -> image.fit());
        zoom.addView(enlarge, new LayoutParams(0, -2, 1));
        zoom.addView(overview, new LayoutParams(0, -2, 1));
        content.addView(zoom, new LayoutParams(-1, -2));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1));

        status = text("正在准备图片…", 14, R.color.agent_muted);
        status.setPadding(0, dp(8), 0, dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(status, new LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(context);
        reject = button("拒绝", onReject);
        reject.setTag(REJECT);
        approve = button("仅发送此图", onApprove);
        approve.setTag(APPROVE);
        approve.setEnabled(false);
        actions.addView(reject, new LayoutParams(0, -2, 1));
        actions.addView(approve, new LayoutParams(0, -2, 1));
        addView(actions, new LayoutParams(-1, -2));
    }

    void installPreview(DecodedPreview owner) {
        requireMain();
        if (previewOwner != null || preview != null || owner == null) {
            throw new IllegalStateException("图片预览不可用。");
        }
        // Exposure is recorded before calling View code: even a failed attachment
        // must not recycle pixels that a renderer might already reference.
        preview = owner.exposeToRenderer();
        previewOwner = owner;
        boolean installed = false;
        try {
            image.setImageBitmap(preview);
            image.fit();
            // Finish text-driven layout before marking the actual preview presented.
            status.setText("本次确认 60 秒内有效；拒绝或离开后失效。");
            installed = true;
        } finally {
            if (!installed) clearPreview();
        }
    }

    boolean isReadyToPresent() {
        requireMain();
        Rect visible = new Rect();
        return preview != null && !preview.isRecycled() && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && image.isLaidOut() && !image.isLayoutRequested()
                && image.getWidth() > 0 && image.getHeight() > 0 && image.getGlobalVisibleRect(visible)
                && visible.width() > 0 && visible.height() > 0
                && image.getDrawable() instanceof BitmapDrawable
                && ((BitmapDrawable) image.getDrawable()).getBitmap() == preview;
    }

    void setPresented(boolean presented) {
        requireMain();
        approve.setEnabled(presented && preview != null);
    }

    void setApproved() {
        requireMain();
        approve.setEnabled(false);
        reject.setText("撤销");
        status.setText("已确认此图。离开此页会取消本次发送。");
    }

    /**
     * A displayed bitmap may still be referenced by RenderThread. Release our
     * known owner only after detaching; never claim this frees GPU/native memory.
     */
    void clearPreview() {
        requireMain();
        approve.setEnabled(false);
        reject.setEnabled(false);
        image.setImageDrawable(null);
        preview = null;
        DecodedPreview owned = previewOwner;
        previewOwner = null;
        if (owned != null) owned.close();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getContext().getColor(color));
        view.setSaveEnabled(false);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(getContext());
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setSaveEnabled(false);
        view.setOnClickListener(unused -> action.run());
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("请在主线程更新图片界面。");
    }

    /** Matrix-only zoom and pan share the one decoded bitmap; no second image is allocated. */
    private static final class PreviewImageView extends ImageView {
        private final Matrix transform = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private float scale = 1, fitScale = 1, offsetX, offsetY, lastX, lastY;

        PreviewImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom(scale * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    return true;
                }
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            fit();
        }

        void fit() {
            if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) return;
            fitScale = Math.min((float) getWidth() / getDrawable().getIntrinsicWidth(),
                    (float) getHeight() / getDrawable().getIntrinsicHeight());
            scale = fitScale;
            offsetX = offsetY = 0;
            applyTransform();
        }

        void enlarge() { zoom(scale * 1.5f, getWidth() / 2f, getHeight() / 2f); }

        private void zoom(float next, float focusX, float focusY) {
            if (getDrawable() == null) return;
            next = Math.max(fitScale, Math.min(Math.max(4, fitScale), next));
            float factor = next / scale;
            offsetX = focusX - (focusX - offsetX) * factor;
            offsetY = focusY - (focusY - offsetY) * factor;
            scale = next;
            applyTransform();
        }

        private void applyTransform() {
            if (getDrawable() == null) return;
            float width = getDrawable().getIntrinsicWidth() * scale;
            float height = getDrawable().getIntrinsicHeight() * scale;
            offsetX = width <= getWidth() ? (getWidth() - width) / 2 : Math.max(getWidth() - width, Math.min(0, offsetX));
            offsetY = height <= getHeight() ? (getHeight() - height) / 2 : Math.max(getHeight() - height, Math.min(0, offsetY));
            transform.setScale(scale, scale);
            transform.postTranslate(offsetX, offsetY);
            setImageMatrix(transform);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (getDrawable() == null) return false;
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX(); lastY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && scale > fitScale) {
                        offsetX += event.getX() - lastX; offsetY += event.getY() - lastY;
                        applyTransform();
                    }
                    lastX = event.getX(); lastY = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    performClick();
                    break;
                default: break;
            }
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(scaleDetector.isInProgress() || scale > fitScale);
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
