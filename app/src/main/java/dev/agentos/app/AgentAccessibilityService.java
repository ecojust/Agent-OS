package dev.agentos.app;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** User-enabled bridge. It never starts a task merely because accessibility is enabled. */
public final class AgentAccessibilityService extends AccessibilityService {
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final long EVENT_READY_CHECK_INTERVAL_MS = 100;
    public interface OverlayCallbacks {
        void onPauseResume();
        void onStop();
        void onApprove(boolean approved);
        default void onDevicePaused(String reason) { }
    }

    private static volatile AgentAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private OverlayCallbacks callbacks;
    private LinearLayout overlay;
    private TextView statusView;
    private TextView approvalView;
    private Button pauseButton;
    private LinearLayout approvalButtons;
    private ScrollView approvalScroll;
    private Button rejectButton;
    private Button approveButton;
    private String currentStatus = "";
    private String currentApproval = "";
    private boolean currentPaused;
    private boolean stopNotifying;
    private Set<String> watchedPackages = Collections.emptySet();
    private boolean receiverRegistered;
    private boolean packageReceiverRegistered;
    private boolean connected;
    private boolean eventReadyCheckPending;
    private final Runnable eventReadyCheck = () -> {
        eventReadyCheckPending = false;
        if (overlay != null && !isDeviceReady()) stopFromDevice();
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                    || USER_SWITCHED.equals(intent.getAction()) || !isDeviceReady()) stopFromDevice();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
            if (packageName != null && watchedPackages.contains(packageName)) stopFromDevice();
        }
    };

    public static AgentAccessibilityService getInstance() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        instance = this;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(USER_SWITCHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        receiverRegistered = true;
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageReceiver, packages);
        packageReceiverRegistered = true;
    }

    public boolean isConnected() { return connected && instance == this; }

    public boolean isDeviceReady() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        UserManager users = (UserManager) getSystemService(USER_SERVICE);
        return isConnected() && power != null && power.isInteractive()
                && keyguard != null && !keyguard.isKeyguardLocked()
                && users != null && users.isUserUnlocked()
                && (Build.VERSION.SDK_INT < 31 || users.isUserForeground());
    }

    public boolean isControlAvailable() { return overlay != null && overlay.isAttachedToWindow() && callbacks != null; }

    public void notifyDevicePaused(String reason) { onMain(() -> { if (callbacks != null) callbacks.onDevicePaused(reason); }); }

    void watchPackages(Set<String> packages) {
        Set<String> immutable = Collections.unmodifiableSet(new HashSet<>(packages));
        onMain(() -> watchedPackages = immutable);
    }

    public void setOverlayCallbacks(OverlayCallbacks value) {
        onMain(() -> callbacks = value);
    }

    public void showOverlay(String status, boolean paused, String approval) {
        onMain(() -> {
            if (!isDeviceReady()) { stopFromDevice(); return; }
            try {
                String previousApproval = currentApproval;
                currentStatus = status == null ? "正在操作" : status;
                currentPaused = paused;
                currentApproval = approval == null ? "" : approval;
                ensureOverlay();
                statusView.setText("Agent · " + currentStatus);
                pauseButton.setText(paused ? "继续" : "暂停");
                boolean waiting = approval != null && !approval.isEmpty();
                approvalView.setText(waiting ? approval : "");
                approvalScroll.setVisibility(waiting ? View.VISIBLE : View.GONE);
                approvalButtons.setVisibility(waiting ? View.VISIBLE : View.GONE);
                if (!currentApproval.equals(previousApproval)) approvalScroll.scrollTo(0, 0);
                overlay.post(() -> {
                    if (overlay == null) return;
                    WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (manager == null) { stopFromDevice(); return; }
                    Rect screen = manager.getCurrentWindowMetrics().getBounds();
                    Rect controls = controlBounds();
                    if (controls.width() > screen.width() || controls.bottom > screen.bottom - dp(12)) {
                        // A tiny or highly magnified window cannot safely show controls; stop instead of hiding them.
                        stopFromDevice();
                    }
                });
                // Bind the displayed request, not whichever callback exists at a later click.
                OverlayCallbacks shownCallbacks = callbacks;
                rejectButton.setOnClickListener(view -> { if (shownCallbacks != null) shownCallbacks.onApprove(false); });
                approveButton.setOnClickListener(view -> { if (shownCallbacks != null) shownCallbacks.onApprove(true); });
            } catch (RuntimeException failure) {
                // A task must not continue without its user-visible stop control.
                stopFromDevice();
            }
        });
    }

    public void hideOverlay() { onMain(() -> { watchedPackages = Collections.emptySet(); removeOverlay(); }); }

    /**
     * Acknowledges the actual compact layout, not just a request to hide approval views.
     * The caller waits on a worker with its own task deadline and cancels this future on
     * pause/stop/timeout. No UI thread is blocked and no target geometry is guessed.
     */
    CompletableFuture<Void> whenControlsReadyForAction() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        ControlLayoutWaiter waiter = new ControlLayoutWaiter(result);
        result.whenComplete((unused, failure) -> onMain(waiter::close));
        onMain(waiter::start);
        return result;
    }

    private final class ControlLayoutWaiter implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final CompletableFuture<Void> result;
        private View panel;
        private ViewTreeObserver observer;

        ControlLayoutWaiter(CompletableFuture<Void> result) { this.result = result; }

        void start() {
            if (result.isDone()) return;
            panel = overlay;
            if (panel == null || !panel.isAttachedToWindow()) {
                result.completeExceptionally(new IllegalStateException("任务控制条不可用"));
                return;
            }
            observer = panel.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            panel.addOnAttachStateChangeListener(this);
            checkLayout();
        }

        private void checkLayout() {
            if (result.isDone()) return;
            if (!connected || panel != overlay || !panel.isAttachedToWindow()) {
                result.completeExceptionally(new IllegalStateException("任务控制条已改变"));
                return;
            }
            if (currentApproval.isEmpty() && approvalScroll.getVisibility() == View.GONE
                    && approvalButtons.getVisibility() == View.GONE && panel.isLaidOut()
                    && !panel.isLayoutRequested() && panel.getWidth() > 0 && panel.getHeight() > 0) {
                result.complete(null);
            }
        }

        @Override public boolean onPreDraw() { checkLayout(); return true; }
        @Override public void onViewAttachedToWindow(View view) { checkLayout(); }
        @Override public void onViewDetachedFromWindow(View view) {
            result.completeExceptionally(new IllegalStateException("任务控制条已移除"));
        }

        void close() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            if (panel != null) panel.removeOnAttachStateChangeListener(this);
            observer = null;
            panel = null;
        }
    }

    /** Read on the main thread; gesture tools must not target the task controls. */
    Rect controlBounds() {
        if (overlay == null || !overlay.isAttachedToWindow()) return new Rect();
        int[] location = new int[2];
        overlay.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + overlay.getWidth(), location[1] + overlay.getHeight());
    }

    private void ensureOverlay() {
        if (overlay != null) return;
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager == null) throw new IllegalStateException("悬浮控制不可用");
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(24, 29, 43));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(126, 151, 255));
        panel.setBackground(background);
        panel.setElevation(dp(12));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(13);
        statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusView.setMaxLines(2);
        statusView.setPadding(dp(2), dp(4), dp(2), dp(4));
        statusView.setContentDescription("Agent 任务状态，可拖动控制条");
        panel.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        pauseButton = button("暂停", () -> { if (callbacks != null) callbacks.onPauseResume(); });
        header.addView(pauseButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(button("停止", () -> { if (callbacks != null) callbacks.onStop(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(header);

        approvalView = new TextView(this);
        approvalView.setTextSize(12);
        approvalView.setTextColor(Color.rgb(229, 235, 255));
        approvalView.setPadding(0, dp(6), 0, dp(2));
        approvalView.setTextIsSelectable(true);
        approvalScroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int available = manager.getCurrentWindowMetrics().getBounds().height();
                int maximum = Math.max(dp(32), Math.min(dp(240), available - dp(250)));
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST));
            }
        };
        approvalScroll.setFillViewport(false);
        approvalScroll.addView(approvalView, new ScrollView.LayoutParams(-1, -2));
        panel.addView(approvalScroll, new LinearLayout.LayoutParams(-1, -2));
        approvalButtons = new LinearLayout(this);
        approvalButtons.setGravity(Gravity.END);
        rejectButton = button("拒绝", () -> {});
        approveButton = button("允许此步", () -> {});
        approvalButtons.addView(rejectButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        approvalButtons.addView(approveButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(approvalButtons);

        int displayWidth = manager.getCurrentWindowMetrics().getBounds().width();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.min(dp(420), Math.max(1, displayWidth - dp(24))),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(12);
        params.setTitle("Agent OS task controls");
        manager.addView(panel, params);
        overlay = panel;
        statusView.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            int originalY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startY = event.getRawY(); originalY = params.y; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int height = manager.getCurrentWindowMetrics().getBounds().height();
                    params.y = Math.max(0, Math.min(Math.max(0, height - panel.getHeight() - dp(48)), originalY + Math.round(event.getRawY() - startY)));
                    try { manager.updateViewLayout(panel, params); } catch (RuntimeException failed) { stopFromDevice(); }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { view.performClick(); return true; }
                return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        });
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(view -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(48)));
        return button;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }

    private void removeOverlay() {
        main.removeCallbacks(eventReadyCheck);
        eventReadyCheckPending = false;
        if (overlay == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeViewImmediate(overlay); }
        catch (RuntimeException ignored) { }
        overlay = null;
        statusView = null;
        pauseButton = null;
        approvalView = null;
        approvalButtons = null;
        approvalScroll = null;
        approveButton = null;
        rejectButton = null;
        currentApproval = "";
        currentStatus = "";
        currentPaused = false;
    }

    private void stopFromDevice() {
        if (stopNotifying) return;
        stopNotifying = true;
        try { if (callbacks != null) callbacks.onStop(); }
        finally { removeOverlay(); watchedPackages = Collections.emptySet(); stopNotifying = false; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Let the framework process every window-removal/focus event for its cache.
        // Only coalesce our own readiness queries; retain no event or node contents.
        // This deadline is not extended by later events, so continuous scrolling
        // cannot postpone the check indefinitely. Broadcast and dispatch guards
        // remain immediate and do not use this queue.
        if (overlay == null || eventReadyCheckPending) return;
        eventReadyCheckPending = true;
        main.postDelayed(eventReadyCheck, EVENT_READY_CHECK_INTERVAL_MS);
    }

    @Override public void onInterrupt() { stopFromDevice(); }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (overlay == null) return;
        String status = currentStatus, approval = currentApproval;
        boolean paused = currentPaused;
        removeOverlay();
        showOverlay(status, paused, approval);
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        connected = false;
        if (instance == this) instance = null;
        stopFromDevice();
        callbacks = null;
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        if (packageReceiverRegistered) {
            try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) { }
            packageReceiverRegistered = false;
        }
    }
}
