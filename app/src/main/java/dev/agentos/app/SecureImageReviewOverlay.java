package dev.agentos.app;

import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;

import dev.agentos.core.CoordinateNodeResolver;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.RunControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Internal, non-focusing presentation component. No screenshot, tool, network or
 * production Controller entry point exists here. Window metadata checks below
 * establish host attachment and retained target focus, not a complete disclosure
 * guard for target revision or hidden sensitive content.
 *
 * Ordinary AndroidDeviceTools still treats this separate window as an obstruction.
 */
final class SecureImageReviewOverlay implements AutoCloseable {
    /** Fixed lifecycle labels only; never store exception messages or window content. */
    enum CloseSource {
        EXPLICIT, SHOW_FAILED, CONFIGURATION_WATCH, WINDOW_DETACHED, WINDOW_VISIBILITY,
        PREVIEW_NULL, PREVIEW_CONTEXT, PREVIEW_ATTACHMENT, PREDRAW_CONTEXT, PREDRAW_LAYOUT,
        VERIFY_REJECTED, VERIFY_CONTEXT, VERIFY_ERROR, VERIFY_POST_FAILED, VERIFY_QUEUE_REJECTED,
        APPROVE_CONTEXT, APPROVE_LISTENER, USER_REJECTED, APPROVED_CONTEXT_CHANGED,
        REFRESH_CONTEXT, REFRESH_LAYOUT, WINDOW_REMOVED, TARGET_CONTENT_CHANGED,
        SERVICE_CONTROL_INVALIDATED, SERVICE_CONTROL_LAYOUT_REQUEST, SERVICE_CONTROL_LAYOUT_CHANGED,
        SERVICE_CONTROL_ATTACHED, SERVICE_CONTROL_DETACHED, SERVICE_CONTROL_VISIBILITY,
        SERVICE_CONTROL_CALLBACKS, SERVICE_CONTROL_PUBLICATION, SERVICE_CONTROL_REMOVED, SERVICE_DEVICE_STOP,
        SERVICE_LOW_MEMORY, SERVICE_UI_HIDDEN, SERVICE_TRIM_MEMORY,
        SERVICE_CONFIGURATION, SERVICE_CONNECTED, SERVICE_DISCONNECTED
    }
    private enum VerifyStage {
        NOT_STARTED, START_CONTEXT, CONFIGURATION_IDENTITY, CONFIGURATION_CONTEXT, DEVICE_GATE,
        GATED_CONTEXT, DEVICE_READY, APP_GRANT, FRESH_WINDOWS, WINDOW_COUNT,
        WINDOW_CONTEXT, REVIEW_WINDOW, CONTROL_WINDOW, FOCUSED_WINDOW, REQUIRED_WINDOW_COUNTS,
        TARGET_ROOT, TARGET_ROOT_IDENTITY, WINDOW_METRICS, UNKNOWN_OCCLUSION, FINAL_CONTEXT,
        FINAL_DEVICE_READY, FINAL_READY_CONTEXT, VERIFIED
    }
    /** Internal removal seam; actual attachment always decides whether admission can be released. */
    interface WindowRemover { void remove(WindowManager manager, View root); }

    interface Listener {
        void onApproved(ImageReviewSession.ApprovalToken approval);
        void onClosed(ImageReviewSession.State state, ImageReviewSession.Reason reason);
    }

    private static final long CHECK_MS = 250, IDENTITY_WAIT_MS = 5_000;
    private static final int MAX_IDENTITY_WAITERS = 8;
    private static final ThreadPoolExecutor VERIFIER = new ThreadPoolExecutor(0, 1, 10,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
        Thread thread = new Thread(task, "agent-os-review-window"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private final AgentAccessibilityService service;
    private final ImageReviewSession session;
    private final RunControl control;
    private final long epoch;
    private final SettingsStore.ConfigurationLease configuration;
    private final ImagePreviewLoader.Decoder decoder;
    private final WindowRemover windowRemover;
    private final CompletableFuture<Void> removed = new CompletableFuture<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final ImageReviewView reviewView;
    private final List<IdentityWaiter> identityWaiters = new ArrayList<>();
    private Listener listener;
    private SettingsStore.Registration configurationWatch;
    private AgentAccessibilityService.OwnedControlWindowIdentity controls;
    // No SavedSettings or API key survives the initial worker-only validation.
    private SettingsStore.AppGrant verifiedGrant;
    private volatile AgentAccessibilityService.OwnedReviewWindowIdentity verifiedWindow;
    private AgentAccessibilityService.OwnedReviewWindowIdentity approvedWindow;
    private volatile ImageReviewSession.HostToken hostToken;
    private volatile boolean ended, closeRequested;
    private volatile long eventGeneration;
    private final Object diagnosticLock = new Object();
    private volatile VerifyStage verifyStage = VerifyStage.NOT_STARTED;
    private volatile String windowDiagnostic = "not_read";
    private volatile String windowCounts = "not_counted";
    private volatile long shownAt = -1;
    private CloseSource firstCloseSource;
    private VerifyStage firstCloseVerifyStage;
    private String firstCloseWindows, firstCloseCounts;
    private long firstCloseElapsed = -1;
    private boolean shown, attached, verificationPending, cleanupComplete, finishing;
    private final Runnable deadline = this::onDeadline;
    private final ViewTreeObserver.OnPreDrawListener presented = this::onPreDraw;
    private final Runnable contextCheck = new Runnable() {
        @Override public void run() {
            if (ended) return;
            refresh();
            if (!ended) main.postDelayed(this, Math.min(CHECK_MS, Math.max(1, session.remainingMillis())));
        }
    };

    SecureImageReviewOverlay(AgentAccessibilityService service, ImageReviewSession session,
                             RunControl control, long epoch, SettingsStore.ConfigurationLease configuration,
                             Listener listener, ImagePreviewLoader.Decoder decoder) {
        this(service, session, control, epoch, configuration, listener, decoder, WindowManager::removeViewImmediate);
    }

    SecureImageReviewOverlay(AgentAccessibilityService service, ImageReviewSession session,
                             RunControl control, long epoch, SettingsStore.ConfigurationLease configuration,
                             Listener listener, ImagePreviewLoader.Decoder decoder, WindowRemover windowRemover) {
        requireMain();
        this.service = Objects.requireNonNull(service); this.session = Objects.requireNonNull(session);
        this.control = Objects.requireNonNull(control); this.epoch = epoch;
        this.configuration = Objects.requireNonNull(configuration);
        this.listener = Objects.requireNonNull(listener); this.decoder = Objects.requireNonNull(decoder);
        this.windowRemover = Objects.requireNonNull(windowRemover);
        root = new FrameLayout(service) {
            @Override public void requestLayout() {
                invalidateGeometry(false);
                super.requestLayout();
            }
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                if (changed) invalidateGeometry(false);
                super.onLayout(changed, left, top, right, bottom);
            }
            @Override protected void onAttachedToWindow() {
                invalidateGeometry(true); super.onAttachedToWindow();
            }
            @Override protected void onDetachedFromWindow() {
                invalidateGeometry(true); super.onDetachedFromWindow();
                if (shown && !cleanupComplete) main.post(() -> close(CloseSource.WINDOW_DETACHED));
            }
            @Override protected void onWindowVisibilityChanged(int visibility) {
                invalidateGeometry(false); super.onWindowVisibilityChanged(visibility);
                if (shown && visibility != VISIBLE && !ended) close(CloseSource.WINDOW_VISIBILITY);
            }
            @Override public boolean onFilterTouchEventForSecurity(MotionEvent event) {
                if ((event.getFlags() & (MotionEvent.FLAG_WINDOW_IS_OBSCURED
                        | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) != 0) return false;
                return super.onFilterTouchEventForSecurity(event);
            }
        };
        root.setSaveEnabled(false); root.setClickable(true); root.setFilterTouchesWhenObscured(true);
        reviewView = new ImageReviewView(service, session.binding, this::approve, this::reject);
        root.addView(reviewView, new FrameLayout.LayoutParams(-1, -1));
        root.getViewTreeObserver().addOnPreDrawListener(presented);
    }

    View rootView() { return root; }
    boolean ownsSession(ImageReviewSession value) { return session == value; }
    boolean isEnded() { return ended || closeRequested; }

    void show() {
        requireMain();
        if (shown || isEnded()) throw new IllegalStateException("图片审阅窗口不可复用。");
        if (ImageReviewPresentationCoordinator.isSessionOwnedByAnother(this, session)) {
            // An unclaimed duplicate shell cannot revoke the real host's session.
            ended = true; reviewView.clearPreview(); listener = null; service.finishImageReview(this);
            throw new IllegalStateException("此图片已有审阅窗口。");
        }
        if (!contextCurrent()) throw new IllegalStateException("图片审阅绑定已失效。");
        controls = service.getOwnedControlWindowIdentity();
        if (controls == null) throw new IllegalStateException("任务控制条尚未就绪。");
        hostToken = ImageReviewPresentationCoordinator.claim(this, session);
        if (hostToken == null) throw new IllegalStateException("已有图片审阅正在进行。");
        shown = true;
        shownAt = SystemClock.elapsedRealtime();
        configurationWatch = configuration.watch(() -> close(CloseSource.CONFIGURATION_WATCH));
        if (!contextCurrent()) throw new IllegalStateException("图片审阅配置已改变。");

        WindowManager manager = service.getSystemService(WindowManager.class);
        if (manager == null) throw new IllegalStateException("显示窗口不可用。");
        WindowMetrics metrics = manager.getCurrentWindowMetrics();
        Rect screen = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect safe = new Rect(screen.left + insets.left, screen.top + insets.top,
                screen.right - insets.right, screen.bottom - insets.bottom);
        Rect controlBounds = controls.copyBounds();
        int left = safe.left + dp(8), top = Math.max(safe.top + dp(8), controlBounds.bottom + dp(8));
        int right = safe.right - dp(8), bottom = safe.bottom - dp(8);
        if (right - left < dp(240) || bottom - top < dp(220)) {
            throw new IllegalStateException("控制条下方空间不足，请先将控制条移到顶部再审阅。");
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(right - left, bottom - top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.setFitInsetsTypes(0);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.x = left; params.y = top;
        params.setTitle("Agent OS image review");
        manager.addView(root, params); attached = true;
        main.postDelayed(deadline, Math.max(1, session.remainingMillis()));
        main.postDelayed(contextCheck, CHECK_MS);
        ImagePreviewLoader.load(session, hostToken, decoder, this::installPreview);
    }

    private void installPreview(DecodedPreview decoded) {
        requireMain();
        boolean transferred = false;
        try {
            if (isEnded() || !contextCurrent() || session.state() != ImageReviewSession.State.PREPARING || decoded == null) {
                if (!ended) close(decoded == null ? CloseSource.PREVIEW_NULL : CloseSource.PREVIEW_CONTEXT);
                return;
            }
            reviewView.installPreview(decoded); transferred = true;
            root.requestLayout(); root.invalidate();
        } catch (RuntimeException | OutOfMemoryError failedAttachment) { close(CloseSource.PREVIEW_ATTACHMENT); }
        finally { if (!transferred && decoded != null) decoded.close(); }
    }

    private void invalidateGeometry(boolean registrationLost) {
        if (!shown || ended) return;
        verifiedWindow = null;
        service.invalidateReviewWindow(this, registrationLost);
        if (registrationLost && approvedWindow != null) invalidateApprovedContext();
    }

    private boolean onPreDraw() {
        if (ended) return true;
        boolean currentContext = contextCurrent();
        if (!currentContext || !secureLayout()) {
            close(currentContext ? CloseSource.PREDRAW_LAYOUT : CloseSource.PREDRAW_CONTEXT); return true;
        }
        AgentAccessibilityService.OwnedReviewWindowIdentity identity = refreshIdentity();
        if (ended) return true;
        completeIdentityWaiters(identity);
        if (identity == null) { reviewView.setPresented(false); return true; }
        if (verifiedWindow != identity) {
            reviewView.setPresented(false);
            verifyWindow(identity);
        } else if (reviewView.isReadyToPresent()) {
            ImageReviewSession.State current = session.state();
            if (current == ImageReviewSession.State.PREPARING || current == ImageReviewSession.State.PRESENTED)
                reviewView.setPresented(session.markPresented(hostToken));
        }
        return true;
    }

    private void verifyWindow(AgentAccessibilityService.OwnedReviewWindowIdentity identity) {
        if (verificationPending || ended) return;
        verificationPending = true;
        long expectedEvents = eventGeneration;
        try { VERIFIER.execute(() -> {
            boolean valid = false;
            try { valid = verifyMetadata(identity, expectedEvents); }
            catch (Exception unavailable) { /* Only bounded lifecycle metadata is exposed to the host. */ }
            catch (Error failure) { close(CloseSource.VERIFY_ERROR); throw failure; }
            boolean accepted = valid;
            if (!main.post(() -> {
                verificationPending = false;
                if (ended || closeRequested) return;
                // A layout/event can supersede this read without granting it authority.
                if (!service.isOwnedReviewWindowCurrent(identity) || expectedEvents != eventGeneration) {
                    root.invalidate(); return;
                }
                if (!accepted || !contextCurrent()) {
                    close(accepted ? CloseSource.VERIFY_CONTEXT : CloseSource.VERIFY_REJECTED); return;
                }
                verifiedWindow = identity; root.invalidate();
            })) close(CloseSource.VERIFY_POST_FAILED);
        }); } catch (RuntimeException | Error rejected) {
            verificationPending = false; close(CloseSource.VERIFY_QUEUE_REJECTED); throw rejected;
        }
    }

    /** Metadata only. The ordinary tool whitelist and final disclosure policy are intentionally untouched. */
    private boolean verifyMetadata(AgentAccessibilityService.OwnedReviewWindowIdentity identity, long expectedEvents) throws Exception {
        verifyStage = VerifyStage.START_CONTEXT;
        windowDiagnostic = "not_read"; windowCounts = "not_counted";
        checkWorkerContext(identity, expectedEvents);
        verifyStage = VerifyStage.CONFIGURATION_IDENTITY;
        if (!validateConfigurationIdentity()) return false;
        verifyStage = VerifyStage.CONFIGURATION_CONTEXT;
        checkWorkerContext(identity, expectedEvents);
        verifyStage = VerifyStage.DEVICE_GATE;
        if (!service.tryBeginDeviceCall()) return false;
        List<AccessibilityWindowInfo> windows = null;
        try {
            verifyStage = VerifyStage.GATED_CONTEXT;
            checkWorkerContext(identity, expectedEvents);
            verifyStage = VerifyStage.DEVICE_READY;
            if (!service.isDeviceReady()) return false;
            verifyStage = VerifyStage.APP_GRANT;
            if (!SettingsStore.isGrantValid(service, verifiedGrant)) return false;
            verifyStage = VerifyStage.FRESH_WINDOWS;
            windows = AndroidDeviceTools.freshWindows(service, () -> checkWorkerContext(identity, expectedEvents));
            try { windowDiagnostic = windowMetadata(windows, identity); }
            catch (RuntimeException | OutOfMemoryError diagnosticUnavailable) { windowDiagnostic = "unavailable"; }
            verifyStage = VerifyStage.WINDOW_COUNT;
            if (windows == null || windows.isEmpty() || windows.size() > 40) return false;
            int reviews = 0, focused = 0, controlCount = 0;
            AccessibilityWindowInfo target = null;
            CoordinateNodeResolver.Binding source = session.binding.observation;
            Rect targetBounds = new Rect(source.windowBounds.left, source.windowBounds.top,
                    source.windowBounds.right, source.windowBounds.bottom);
            for (AccessibilityWindowInfo window : windows) {
                verifyStage = VerifyStage.WINDOW_CONTEXT;
                checkWorkerContext(identity, expectedEvents);
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getId() == identity.windowId) {
                    verifyStage = VerifyStage.REVIEW_WINDOW;
                    reviews++;
                    if (window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                            || window.getDisplayId() != Display.DEFAULT_DISPLAY || window.isFocused()
                            || !bounds.equals(identity.copyBounds())) return false;
                }
                if (window.getId() == controls.windowId) {
                    verifyStage = VerifyStage.CONTROL_WINDOW;
                    controlCount++;
                    if (window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                            || window.getDisplayId() != controls.displayId || window.isFocused()
                            || !bounds.equals(controls.copyBounds()) || Rect.intersects(bounds, identity.copyBounds())) return false;
                }
                if (window.isFocused()) {
                    verifyStage = VerifyStage.FOCUSED_WINDOW;
                    focused++;
                    if (window.getId() != source.windowId || window.getDisplayId() != source.displayId
                            || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !bounds.equals(targetBounds)) return false;
                    target = window;
                }
            }
            try { windowCounts = "review=" + reviews + ",control=" + controlCount + ",focused=" + focused; }
            catch (OutOfMemoryError diagnosticUnavailable) { windowCounts = "unavailable"; }
            verifyStage = VerifyStage.REQUIRED_WINDOW_COUNTS;
            if (reviews != 1 || controlCount != 1 || focused != 1 || target == null) return false;
            verifyStage = VerifyStage.TARGET_ROOT;
            AccessibilityNodeInfo targetRoot = target.getRoot();
            if (targetRoot == null) return false;
            try {
                verifyStage = VerifyStage.TARGET_ROOT_IDENTITY;
                if (targetRoot.getWindowId() != source.windowId || !source.packageName.contentEquals(
                        targetRoot.getPackageName() == null ? "" : targetRoot.getPackageName())) return false;
            } finally { targetRoot.recycle(); }
            verifyStage = VerifyStage.WINDOW_METRICS;
            WindowManager manager = service.getSystemService(WindowManager.class);
            if (manager == null) return false;
            WindowMetrics metrics = manager.getCurrentWindowMetrics();
            Insets bars = metrics.getWindowInsets().getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            Rect screen = metrics.getBounds();
            verifyStage = VerifyStage.UNKNOWN_OCCLUSION;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() == source.windowId || window.getId() == identity.windowId
                        || window.getId() == controls.windowId || window.getLayer() <= target.getLayer()) continue;
                Rect other = new Rect(); window.getBoundsInScreen(other);
                if (!Rect.intersects(other, targetBounds) && !Rect.intersects(other, identity.copyBounds())
                        && !Rect.intersects(other, controls.copyBounds())) continue;
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && inVisibleBar(other, screen, bars)) continue;
                return false;
            }
            verifyStage = VerifyStage.FINAL_CONTEXT;
            checkWorkerContext(identity, expectedEvents);
            verifyStage = VerifyStage.FINAL_DEVICE_READY;
            boolean ready = service.isDeviceReady();
            verifyStage = VerifyStage.FINAL_READY_CONTEXT;
            checkWorkerContext(identity, expectedEvents);
            verifyStage = ready ? VerifyStage.VERIFIED : VerifyStage.FINAL_DEVICE_READY;
            return ready;
        } finally {
            if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle();
            service.endDeviceCall();
        }
    }

    /** Bounded numeric metadata only, from the exact list already read by the verifier. */
    private String windowMetadata(List<AccessibilityWindowInfo> windows,
                                  AgentAccessibilityService.OwnedReviewWindowIdentity identity) {
        CoordinateNodeResolver.Binding target = session.binding.observation;
        StringBuilder text = new StringBuilder(1024);
        text.append("count=").append(windows == null ? -1 : windows.size())
                .append("; expectedReview=").append(identity.windowId).append('@').append(identity.copyBounds().toShortString())
                .append("; expectedControl=").append(controls.windowId).append('@').append(controls.copyBounds().toShortString())
                .append("; expectedTarget=").append(target.windowId).append('@')
                .append('[').append(target.windowBounds.left).append(',').append(target.windowBounds.top)
                .append(',').append(target.windowBounds.right).append(',').append(target.windowBounds.bottom).append(']');
        if (windows != null) {
            int count = Math.min(8, windows.size());
            for (int i = 0; i < count; i++) {
                AccessibilityWindowInfo window = windows.get(i);
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                text.append("; w=").append(window.getId()).append('/').append(window.getType())
                        .append('/').append(window.getDisplayId()).append('/').append(window.isFocused() ? 1 : 0)
                        .append('@').append(bounds.toShortString());
            }
            if (windows.size() > count) text.append("; omitted=").append(windows.size() - count);
        }
        return text.toString();
    }

    /** Validate displayable recipient and the saved grant once, without retaining credentials. */
    private boolean validateConfigurationIdentity() throws Exception {
        if (verifiedGrant != null) return true;
        SettingsStore.LoadedSettings loaded = new SettingsStore(service).loadWithLease();
        if (loaded.lease != configuration || !SettingsStore.isCurrent(configuration)) return false;
        SettingsStore.SavedSettings saved = loaded.settings;
        ImageReviewSession.Recipient recipient = new ImageReviewSession.Recipient(saved.endpoint, saved.model);
        String packageName = session.binding.observation.packageName;
        SettingsStore.AppGrant grant = saved.grants.get(packageName);
        ImageReviewSession.AppIdentity identity = session.binding.applicationIdentity;
        if (!recipient.equals(session.binding.recipient) || !saved.allowedPackages.contains(packageName) || grant == null
                || !grant.profileId.equals(configuration.profileId) || grant.uid != identity.uid
                || grant.firstInstallTime != identity.firstInstallTime || !grant.signers.equals(identity.signerDigests)) return false;
        verifiedGrant = grant;
        return true;
    }

    private static boolean inVisibleBar(Rect value, Rect screen, Insets bars) {
        if (value.isEmpty()) return false;
        return bars.left > 0 && new Rect(screen.left, screen.top, screen.left + bars.left, screen.bottom).contains(value)
                || bars.top > 0 && new Rect(screen.left, screen.top, screen.right, screen.top + bars.top).contains(value)
                || bars.right > 0 && new Rect(screen.right - bars.right, screen.top, screen.right, screen.bottom).contains(value)
                || bars.bottom > 0 && new Rect(screen.left, screen.bottom - bars.bottom, screen.right, screen.bottom).contains(value);
    }

    private void checkWorkerContext(AgentAccessibilityService.OwnedReviewWindowIdentity identity, long expectedEvents) throws Exception {
        control.checkExecutionEpoch(epoch);
        if (isEnded() || !SettingsStore.isCurrent(configuration) || !service.isConnected()
                || !service.isOwnedReviewWindowCurrent(identity) || !service.isOwnedControlWindowCurrent(controls)
                || eventGeneration != expectedEvents) throw new IllegalStateException("Review context changed");
    }

    private boolean contextCurrent() {
        if (isEnded() || !service.isConnected() || !SettingsStore.isCurrent(configuration)) return false;
        try { control.checkExecutionEpoch(epoch); }
        catch (InterruptedException stopped) { return false; }
        CoordinateNodeResolver.Binding binding = session.binding.observation;
        if (!binding.taskId.equals(control.getTaskId()) || binding.executionEpoch != epoch
                || !binding.profileId.equals(control.getProfileId()) || !binding.profileId.equals(configuration.profileId)
                || !session.binding.configurationToken.equals(configuration.configurationToken)
                || binding.displayId != Display.DEFAULT_DISPLAY) return false;
        if (controls != null && !service.isOwnedControlWindowCurrent(controls)) return false;
        ImageReviewSession.State current = session.state();
        return current == ImageReviewSession.State.WAITING_HOST || current == ImageReviewSession.State.PREPARING
                || current == ImageReviewSession.State.PRESENTED || current == ImageReviewSession.State.APPROVED;
    }

    private boolean secureLayout() {
        if (!root.isAttachedToWindow() || !root.isShown() || !(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return false;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
        int required = WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        return params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && (params.flags & required) == required;
    }

    private void approve() {
        requireMain();
        AgentAccessibilityService.OwnedReviewWindowIdentity identity = verifiedWindow;
        if (!contextCurrent() || !secureLayout() || root.isLayoutRequested()
                || !service.isOwnedReviewWindowCurrent(identity) || !reviewView.isReadyToPresent()) {
            close(CloseSource.APPROVE_CONTEXT); return;
        }
        ImageReviewSession.ApprovalToken approved = session.approve(hostToken);
        if (approved == null) { refresh(); return; }
        approvedWindow = identity;
        reviewView.setApproved();
        try { if (listener != null) listener.onApproved(approved); }
        catch (RuntimeException failedConsumer) { close(CloseSource.APPROVE_LISTENER); return; }
        refresh(); // An unconsumed token remains revocable until consumed or expired.
    }

    private void reject() { session.reject(hostToken); close(CloseSource.USER_REJECTED); }

    private void invalidateApprovedContext() {
        session.invalidate(ImageReviewSession.Reason.CONTEXT_CHANGED);
        close(CloseSource.APPROVED_CONTEXT_CHANGED);
    }

    /** Check actual geometry before publishing it to any waiter, including synchronous future continuations. */
    private AgentAccessibilityService.OwnedReviewWindowIdentity refreshIdentity() {
        AgentAccessibilityService.OwnedReviewWindowIdentity identity = service.refreshReviewWindowIdentity(this);
        if (approvedWindow != null && identity != null && !approvedWindow.sameRegistrationAndBounds(identity)) {
            invalidateApprovedContext(); return null;
        }
        return identity;
    }

    private void onDeadline() { session.expire(); refresh(); }

    void refresh() {
        requireMain();
        if (ended) return;
        boolean current = contextCurrent();
        if (!current || shown && !secureLayout()) {
            close(current ? CloseSource.REFRESH_LAYOUT : CloseSource.REFRESH_CONTEXT); return;
        }
        completeIdentityWaiters(refreshIdentity());
        if (verifiedWindow == null) root.invalidate();
    }

    void onAccessibilityEvent(int type, int windowId, int changes) {
        requireMain();
        if (!shown || ended) return;
        // requestLayout can temporarily clear the published geometry. Classification
        // still comes from this exact View's registered token/attachment, never a title.
        if (service.isRegisteredReviewWindow(this, windowId)) {
            if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    && (changes & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) close(CloseSource.WINDOW_REMOVED);
            else if (approvedWindow != null && type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    && (changes & (AccessibilityEvent.WINDOWS_CHANGE_BOUNDS | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
                    | AccessibilityEvent.WINDOWS_CHANGE_LAYER)) != 0) invalidateApprovedContext();
            return;
        }
        if (controls != null && windowId == controls.windowId) return;
        if (windowId == session.binding.observation.windowId && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            session.invalidate(ImageReviewSession.Reason.CONTEXT_CHANGED); close(CloseSource.TARGET_CONTENT_CHANGED); return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // A previously issued token must not remain consumable while an
            // asynchronous window read is pending. No fresh approval is fabricated.
            if (approvedWindow != null) { invalidateApprovedContext(); return; }
            eventGeneration++; verifiedWindow = null; reviewView.setPresented(false); root.invalidate();
        }
    }

    CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> acquireIdentity() {
        CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> future = new CompletableFuture<>();
        boolean queued = main.post(() -> {
            if (future.isDone()) return;
            if (ended || closeRequested || identityWaiters.size() >= MAX_IDENTITY_WAITERS) {
                future.completeExceptionally(new IllegalStateException("Review identity unavailable")); return;
            }
            IdentityWaiter waiter = new IdentityWaiter(future, SystemClock.elapsedRealtime()
                    + Math.min(IDENTITY_WAIT_MS, Math.max(1, session.remainingMillis())));
            identityWaiters.add(waiter);
            future.whenComplete((unused, failure) -> main.post(() -> identityWaiters.remove(waiter)));
            completeIdentityWaiters(refreshIdentity()); root.invalidate();
        });
        if (!queued) future.completeExceptionally(new IllegalStateException("Review UI unavailable"));
        return future;
    }

    private void completeIdentityWaiters(AgentAccessibilityService.OwnedReviewWindowIdentity identity) {
        long now = SystemClock.elapsedRealtime();
        for (IdentityWaiter waiter : new ArrayList<>(identityWaiters)) {
            if (waiter.future.isDone()) identityWaiters.remove(waiter);
            else if (isEnded() || now >= waiter.deadline) {
                waiter.future.completeExceptionally(new IllegalStateException("Review identity expired")); identityWaiters.remove(waiter);
            } else if (identity != null) { waiter.future.complete(identity); identityWaiters.remove(waiter); }
        }
    }

    /**
     * A separate waiter for actual host cleanup. Cancelling or completing this
     * dependent cannot alter cleanup or another waiter. This acknowledges our
     * Drawable/preview owner and window, not RenderThread/GPU or pending decoder memory.
     */
    CompletableFuture<Void> whenRemoved() { return removed.thenApply(unused -> null); }

    @Override public void close() { close(CloseSource.EXPLICIT); }

    void close(CloseSource source) {
        synchronized (diagnosticLock) {
            if (firstCloseSource == null) {
                firstCloseSource = source;
                firstCloseVerifyStage = verifyStage;
                firstCloseWindows = windowDiagnostic;
                firstCloseCounts = windowCounts;
                firstCloseElapsed = shownAt < 0 ? -1 : SystemClock.elapsedRealtime() - shownAt;
            }
        }
        closeRequested = true;
        ImageReviewSession.HostToken claimed = hostToken;
        if (claimed != null) session.invalidateHost(claimed);
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::finishHost); return; }
        finishHost();
    }

    /** Failure-only package diagnostic; never reads Views, storage, window text or credentials. */
    String diagnosticSummary() {
        synchronized (diagnosticLock) {
            return "reviewClose=" + firstCloseSource + "; afterShowMs=" + firstCloseElapsed
                    + "; verifyStage=" + (firstCloseVerifyStage == null ? verifyStage : firstCloseVerifyStage)
                    + "; " + (firstCloseCounts == null ? windowCounts : firstCloseCounts)
                    + "; " + (firstCloseWindows == null ? windowDiagnostic : firstCloseWindows);
        }
    }

    private void finishHost() {
        requireMain();
        if (cleanupComplete || finishing) return;
        finishing = true;
        try {
        ended = true;
        main.removeCallbacks(deadline); main.removeCallbacks(contextCheck);
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(presented);
        if (hostToken != null) session.invalidateHost(hostToken);
        else if (!ImageReviewPresentationCoordinator.isSessionOwnedByAnother(this, session))
            session.invalidate(ImageReviewSession.Reason.HOST_LOST);
        if (configurationWatch != null) { configurationWatch.close(); configurationWatch = null; }
        completeIdentityWaiters(null);
        reviewView.clearPreview();
        service.invalidateReviewWindow(this, true);
        if (attached || root.isAttachedToWindow()) {
            try {
                WindowManager manager = service.getSystemService(WindowManager.class);
                if (manager != null) windowRemover.remove(manager, root);
            } catch (RuntimeException ignored) { }
        }
        // Do not admit a replacement while a failed remove still leaves our window
        // attached. A later detach/close can finish cleanup; the old image is cleared.
        if (root.isAttachedToWindow()) return;
        attached = false;
        service.finishImageReview(this);
        ImageReviewPresentationCoordinator.release(this);
        cleanupComplete = true;
        Listener closed = listener; listener = null;
        try {
            if (closed != null) {
                try { closed.onClosed(session.state(), session.reason()); }
                catch (RuntimeException ignored) { }
            }
        } finally {
            // Exact service/shared owners are already released. Even a listener
            // failure cannot undo that fact or strand acknowledgement of cleanup.
            removed.complete(null);
        }
        } finally { finishing = false; }
    }

    private static final class IdentityWaiter {
        final CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> future;
        final long deadline;
        IdentityWaiter(CompletableFuture<AgentAccessibilityService.OwnedReviewWindowIdentity> future, long deadline) {
            this.future = future; this.deadline = deadline;
        }
    }
    private int dp(int value) { return Math.round(service.getResources().getDisplayMetrics().density * value); }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Review UI requires main");
    }
}
