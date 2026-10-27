package dev.agentos.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.agentos.core.ImageReviewSession;
import dev.agentos.core.RunControl;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** User-enabled bridge. It never starts a task merely because accessibility is enabled. */
public final class AgentAccessibilityService extends AccessibilityService {
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final long EVENT_READY_CHECK_INTERVAL_MS = 100;
    private static final long CONTROL_IDENTITY_TIMEOUT_MS = 5_000;
    public interface OverlayCallbacks {
        void onPauseResume();
        void onStop();
        void onApprove(boolean approved);
        default void onDevicePaused(String reason) { }
    }

    /**
     * Identifies this service's actual attached control View, never a discovered title
     * or package name. Workers must compare it with a fresh platform window list too.
     */
    public static final class OwnedControlWindowIdentity {
        public final int windowId, displayId;
        private final WindowRegistration registration;
        private final int left, top, right, bottom;
        private final long generation, callbackGeneration;

        private OwnedControlWindowIdentity(WindowRegistration registration, Rect bounds,
                                           long generation, long callbackGeneration) {
            this.registration = registration;
            windowId = registration.windowId; displayId = registration.displayId;
            left = bounds.left; top = bounds.top; right = bounds.right; bottom = bounds.bottom;
            this.generation = generation; this.callbackGeneration = callbackGeneration;
        }

        public Rect copyBounds() { return new Rect(left, top, right, bottom); }

        private boolean sameRegistrationAndBounds(OwnedControlWindowIdentity other) {
            return other != null && registration == other.registration
                    && left == other.left && top == other.top && right == other.right && bottom == other.bottom;
        }

        private boolean matches(WindowRegistration candidate, Rect bounds, long generation, long callbacks) {
            return registration == candidate && this.generation == generation && callbackGeneration == callbacks
                    && left == bounds.left && top == bounds.top && right == bounds.right && bottom == bounds.bottom;
        }
    }

    /** Actual attached review window only. It never grants a normal action or screenshot permit. */
    static final class OwnedReviewWindowIdentity {
        public final int windowId, displayId;
        private final SecureImageReviewOverlay owner;
        private final WindowRegistration registration;
        private final int left, top, right, bottom;
        private final long generation;
        private OwnedReviewWindowIdentity(SecureImageReviewOverlay owner, WindowRegistration registration,
                                          Rect bounds, long generation) {
            this.owner = owner; this.registration = registration; this.generation = generation;
            windowId = registration.windowId; displayId = registration.displayId;
            left = bounds.left; top = bounds.top; right = bounds.right; bottom = bounds.bottom;
        }
        public Rect copyBounds() { return new Rect(left, top, right, bottom); }
        /** Cosmetic layout may change publication generation, never the actual attachment or geometry. */
        boolean sameRegistrationAndBounds(OwnedReviewWindowIdentity other) {
            return other != null && owner == other.owner && registration == other.registration
                    && windowId == other.windowId && displayId == other.displayId
                    && left == other.left && top == other.top && right == other.right && bottom == other.bottom;
        }
        private boolean matches(SecureImageReviewOverlay owner, WindowRegistration registration, Rect bounds, long generation) {
            return this.owner == owner && this.registration == registration && this.generation == generation
                    && left == bounds.left && top == bounds.top && right == bounds.right && bottom == bounds.bottom;
        }
    }

    /** Main-thread registration from the owned View only; never supplied by a caller. */
    private static final class WindowRegistration {
        final IBinder token;
        final int windowId, displayId;
        final long connectionGeneration, attachmentGeneration;

        WindowRegistration(IBinder token, int windowId, int displayId, long connection, long attachment) {
            this.token = token; this.windowId = windowId; this.displayId = displayId;
            connectionGeneration = connection; attachmentGeneration = attachment;
        }
    }

    /** Immutable main-thread geometry. A worker must also validate the current lease and epoch. */
    public static final class ControlLease {
        public final int windowId, displayId;
        private final OwnedControlWindowIdentity windowIdentity;
        private final int left, top, right, bottom;
        private final long generation, connectionGeneration, callbackGeneration;

        private ControlLease(OwnedControlWindowIdentity windowIdentity, Rect bounds, long generation, long connectionGeneration,
                             long callbackGeneration) {
            this.windowIdentity = windowIdentity;
            windowId = windowIdentity.windowId; displayId = windowIdentity.displayId;
            left = bounds.left; top = bounds.top; right = bounds.right; bottom = bounds.bottom;
            this.generation = generation;
            this.connectionGeneration = connectionGeneration;
            this.callbackGeneration = callbackGeneration;
        }

        public boolean intersects(Rect target) {
            return target != null && left < target.right && target.left < right
                    && top < target.bottom && target.top < bottom;
        }

        /** Pure value copy of this lease; does not query a View or current geometry. */
        public Rect copyBounds() { return new Rect(left, top, right, bottom); }

        private boolean matches(OwnedControlWindowIdentity identity, Rect bounds, long generation, long connection, long callbacks) {
            return windowIdentity == identity && this.generation == generation && connectionGeneration == connection
                    && callbackGeneration == callbacks && left == bounds.left && top == bounds.top
                    && right == bounds.right && bottom == bounds.bottom;
        }
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
    private long displayedCallbackGeneration = -1;
    private boolean stopNotifying;
    private Set<String> watchedPackages = Collections.emptySet();
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener visualDisplayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { invalidateVisualEvents(); }
        @Override public void onDisplayRemoved(int displayId) { invalidateVisualEvents(); }
        @Override public void onDisplayChanged(int displayId) { invalidateVisualEvents(); }
    };
    private boolean receiverRegistered;
    private boolean packageReceiverRegistered;
    private volatile boolean connected;
    // Written on main before handling a change; never retain an event or a source node.
    // Saturation permanently rejects visual guards for this service instance.
    private volatile long visualGeneration;
    private volatile boolean windowCaptureCapability;
    // These main-only geometry baselines survive cosmetic requestLayout, but never
    // survive their actual attachment. They are not an event-ID exemption.
    private OwnedControlWindowIdentity visualControlGeometry;
    private OwnedReviewWindowIdentity visualReviewGeometry;
    private volatile ControlLease currentControlLease;
    private volatile OwnedControlWindowIdentity currentControlWindowIdentity;
    private volatile SecureImageReviewOverlay reviewHost;
    private volatile OwnedReviewWindowIdentity currentReviewWindowIdentity;
    private volatile WindowRegistration reviewWindowRegistration;
    private long reviewGeneration, reviewAttachmentGeneration;
    private volatile WindowRegistration controlWindowRegistration;
    // Only the main thread writes generations. Workers read the published immutable lease.
    private long controlGeneration, connectionGeneration, callbackGeneration;
    private long controlAttachmentGeneration;
    // A disconnected service can still have a platform call in flight. A replacement
    // service must share this gate; only the original worker releases it on return.
    private static final AtomicReference<Thread> DEVICE_CALL_OWNER = new AtomicReference<>();
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
        invalidateVisualEvents();
        windowCaptureCapability = false;
        super.onServiceConnected();
        invalidateControlWindowRegistration(SecureImageReviewOverlay.CloseSource.SERVICE_CONNECTED);
        connectionGeneration++;
        connected = true;
        instance = this;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                AccessibilityServiceInfo actual = getServiceInfo();
                windowCaptureCapability = actual != null && (actual.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
            } catch (RuntimeException unavailable) { windowCaptureCapability = false; }
        }
        try {
            displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (displayManager == null) windowCaptureCapability = false;
            else displayManager.registerDisplayListener(visualDisplayListener, main);
        } catch (RuntimeException unavailable) {
            // Structured operation remains usable; visual disclosure cannot claim
            // display-change coverage without this service-lifetime subscription.
            windowCaptureCapability = false;
            if (displayManager != null) {
                try { displayManager.unregisterDisplayListener(visualDisplayListener); }
                catch (RuntimeException cleanupFailed) { /* Keep the reference for disconnect cleanup. */ }
            }
        }
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

    /** Actual connected-service publication, not a manifest promise or a capture permit. */
    public boolean hasWindowCaptureCapability() {
        return Build.VERSION.SDK_INT >= 34 && isConnected() && windowCaptureCapability;
    }

    /** Pure memory: an image guard must also retain the exact service instance. */
    public long visualEventGeneration() { return visualGeneration; }

    /** No Views, platform calls, budget mutation or cancellation hooks run here. */
    public boolean isVisualEventGenerationCurrent(long original) {
        if (original < 0 || original == Long.MAX_VALUE || visualGeneration != original || !isConnected()) return false;
        // showOverlay invalidates its action-layout publication even for a same-
        // height text change. A request to lay out is not itself changed geometry.
        // Actual layout/drag/visibility/attachment callbacks advance the generation;
        // retain only the current real registration here, never a discovered ID.
        WindowRegistration controls = controlWindowRegistration;
        SecureImageReviewOverlay review = reviewHost;
        WindowRegistration reviewWindow = reviewWindowRegistration;
        return controls != null && (review == null || reviewWindow != null)
                && visualGeneration == original && isConnected();
    }

    private void invalidateVisualEvents() {
        if (visualGeneration != Long.MAX_VALUE) visualGeneration++;
    }

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

    /** Does not touch Views or wait for the main thread. */
    public boolean isControlLeaseCurrent(ControlLease lease) {
        return lease != null && currentControlLease == lease && isOwnedControlWindowCurrent(lease.windowIdentity);
    }

    /** Pure-memory publication; absence never permits matching another window by its label. */
    public OwnedControlWindowIdentity getOwnedControlWindowIdentity() {
        return connected && instance == this ? currentControlWindowIdentity : null;
    }

    public boolean isOwnedControlWindowCurrent(OwnedControlWindowIdentity identity) {
        return identity != null && currentControlWindowIdentity == identity && connected && instance == this;
    }

    /** Internal component factory only: no tool, Intent, capture, or network entry point. */
    SecureImageReviewOverlay showImageReview(ImageReviewSession session, RunControl originalControl, long originalEpoch,
                                             SettingsStore.ConfigurationLease configuration,
                                             SecureImageReviewOverlay.Listener listener) {
        return showImageReview(session, originalControl, originalEpoch, configuration, listener,
                (input, options) -> android.graphics.BitmapFactory.decodeStream(input, null, options));
    }

    /** Decoder seam still uses the same secure host, shared admission, and real window registration. */
    SecureImageReviewOverlay showImageReview(ImageReviewSession session, RunControl originalControl, long originalEpoch,
                                             SettingsStore.ConfigurationLease configuration,
                                             SecureImageReviewOverlay.Listener listener, ImagePreviewLoader.Decoder decoder) {
        return showImageReview(session, originalControl, originalEpoch, configuration, listener,
                decoder, WindowManager::removeViewImmediate);
    }

    /** Internal removal seam; successful cleanup still requires actual View detachment. */
    SecureImageReviewOverlay showImageReview(ImageReviewSession session, RunControl originalControl, long originalEpoch,
                                             SettingsStore.ConfigurationLease configuration,
                                             SecureImageReviewOverlay.Listener listener, ImagePreviewLoader.Decoder decoder,
                                             SecureImageReviewOverlay.WindowRemover windowRemover) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Review must be shown on main");
        if (reviewHost != null) {
            if (!reviewHost.ownsSession(session)) session.invalidate(ImageReviewSession.Reason.HOST_LOST);
            throw new IllegalStateException("已有图片审阅正在进行。");
        }
        SecureImageReviewOverlay created = null;
        try {
            created = new SecureImageReviewOverlay(this, session, originalControl,
                    originalEpoch, configuration, listener, decoder, windowRemover);
            invalidateVisualEvents();
            reviewHost = created;
            created.show(); return created;
        } catch (RuntimeException | Error failed) {
            if (created != null) created.close(SecureImageReviewOverlay.CloseSource.SHOW_FAILED);
            else if (session != null && !ImageReviewPresentationCoordinator.isSessionOwnedByAnother(null, session))
                session.invalidate(ImageReviewSession.Reason.HOST_LOST);
            throw failed;
        }
    }

    OwnedReviewWindowIdentity getOwnedReviewWindowIdentity() {
        return isConnected() ? currentReviewWindowIdentity : null;
    }

    boolean isOwnedReviewWindowCurrent(OwnedReviewWindowIdentity identity) {
        return identity != null && isConnected() && currentReviewWindowIdentity == identity && reviewHost == identity.owner;
    }

    /** Main-only event classification from the real attachment, including temporary layout invalidation. */
    boolean isRegisteredReviewWindow(SecureImageReviewOverlay owner, int windowId) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Review events require main");
        WindowRegistration registration = reviewWindowRegistration;
        if (!isConnected() || reviewHost != owner || registration == null || registration.windowId != windowId
                || registration.connectionGeneration != connectionGeneration
                || registration.attachmentGeneration != reviewAttachmentGeneration) return false;
        View root = owner.rootView();
        Display display = root.getDisplay();
        return root.isAttachedToWindow() && root.getWindowToken() == registration.token
                && display != null && display.getDisplayId() == registration.displayId;
    }

    CompletableFuture<OwnedReviewWindowIdentity> acquireOwnedReviewWindowIdentity(SecureImageReviewOverlay expectedHost) {
        if (expectedHost == null || reviewHost != expectedHost) {
            CompletableFuture<OwnedReviewWindowIdentity> absent = new CompletableFuture<>();
            absent.completeExceptionally(new IllegalStateException("Review host is unavailable"));
            return absent;
        }
        return expectedHost.acquireIdentity();
    }

    /** Main-only, exact service-created root. Never register a caller's arbitrary View. */
    OwnedReviewWindowIdentity refreshReviewWindowIdentity(SecureImageReviewOverlay owner) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Review registration requires main");
        if (!isConnected() || reviewHost != owner || owner.isEnded()) return null;
        View root = owner.rootView();
        if (!root.isAttachedToWindow() || !root.isShown() || !root.isLaidOut() || root.isLayoutRequested()
                || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return null;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
        int required = WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        Display display = root.getDisplay();
        IBinder token = root.getWindowToken();
        if (params.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY || (params.flags & required) != required
                || token == null || display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY) return null;
        WindowRegistration registration = reviewWindowRegistration;
        if (registration == null || registration.token != token || registration.displayId != display.getDisplayId()
                || registration.connectionGeneration != connectionGeneration
                || registration.attachmentGeneration != reviewAttachmentGeneration) {
            invalidateReviewWindow(owner, true);
            android.view.accessibility.AccessibilityNodeInfo node = root.createAccessibilityNodeInfo();
            if (node == null) return null;
            try {
                if (node.getWindowId() < 0) return null;
                registration = new WindowRegistration(token, node.getWindowId(), display.getDisplayId(),
                        connectionGeneration, reviewAttachmentGeneration);
                reviewWindowRegistration = registration;
            } finally { node.recycle(); }
        }
        int[] location = new int[2]; root.getLocationOnScreen(location);
        Rect bounds = new Rect(location[0], location[1], location[0] + root.getWidth(), location[1] + root.getHeight());
        OwnedReviewWindowIdentity current = currentReviewWindowIdentity;
        if (current != null && current.matches(owner, registration, bounds, reviewGeneration)) return current;
        OwnedReviewWindowIdentity next = new OwnedReviewWindowIdentity(owner, registration, bounds, reviewGeneration);
        if (visualReviewGeometry != null && !visualReviewGeometry.sameRegistrationAndBounds(next)) invalidateVisualEvents();
        visualReviewGeometry = next;
        currentReviewWindowIdentity = next;
        return next;
    }

    void invalidateReviewWindow(SecureImageReviewOverlay owner, boolean registrationLost) {
        if (reviewHost != owner) return;
        // requestLayout may be cosmetic; an actual changed View frame is not.
        // onLayout has already installed its new frame when it calls this method.
        OwnedReviewWindowIdentity previous = visualReviewGeometry;
        if (!registrationLost && previous != null) {
            View root = owner.rootView();
            int[] location = new int[2]; root.getLocationOnScreen(location);
            if (!root.isAttachedToWindow() || !root.isShown()
                    || previous.left != location[0] || previous.top != location[1]
                    || previous.right != location[0] + root.getWidth()
                    || previous.bottom != location[1] + root.getHeight()) invalidateVisualEvents();
        }
        currentReviewWindowIdentity = null;
        reviewGeneration++;
        if (registrationLost) {
            invalidateVisualEvents(); visualReviewGeometry = null;
            reviewWindowRegistration = null; reviewAttachmentGeneration++;
        }
    }

    void finishImageReview(SecureImageReviewOverlay owner) {
        if (reviewHost != owner) return;
        invalidateReviewWindow(owner, true);
        reviewHost = null;
    }

    private void closeImageReview(SecureImageReviewOverlay.CloseSource source) {
        SecureImageReviewOverlay host = reviewHost;
        if (host != null) host.close(source);
    }

    /** One device request per process, including replacement services and adapters. */
    public boolean tryBeginDeviceCall() { return DEVICE_CALL_OWNER.compareAndSet(null, Thread.currentThread()); }

    /** The worker that acquired the gate retains it through actual platform completion. */
    public void endDeviceCall() {
        if (!DEVICE_CALL_OWNER.compareAndSet(Thread.currentThread(), null)) {
            throw new IllegalStateException("Device request gate is not owned by this worker");
        }
    }

    public void notifyDevicePaused(String reason) { onMain(() -> { if (callbacks != null) callbacks.onDevicePaused(reason); }); }

    void watchPackages(Set<String> packages) {
        Set<String> immutable = Collections.unmodifiableSet(new HashSet<>(packages));
        onMain(() -> watchedPackages = immutable);
    }

    public void setOverlayCallbacks(OverlayCallbacks value) {
        onMain(() -> {
            if (callbacks != value) {
                if (value == null) invalidateVisualEvents();
                invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_CALLBACKS);
                callbackGeneration++;
                callbacks = value;
            }
        });
    }

    public void showOverlay(String status, boolean paused, String approval) {
        onMain(() -> {
            if (!isDeviceReady()) { stopFromDevice(); return; }
            try {
                String nextStatus = status == null ? "正在操作" : status;
                String nextApproval = approval == null ? "" : approval;
                // Log-only publications must not relayout the controls or revoke
                // an in-flight snapshot. Callback changes still require rebinding
                // the displayed approval, even when all displayed text is equal.
                if (overlay != null && overlay.isAttachedToWindow() && overlay.isShown()
                        && callbacks != null && displayedCallbackGeneration == callbackGeneration
                        && currentStatus.equals(nextStatus) && currentPaused == paused
                        && currentApproval.equals(nextApproval)) return;
                invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_PUBLICATION);
                boolean firstDisplay = overlay == null;
                boolean statusChanged = !currentStatus.equals(nextStatus);
                boolean pauseChanged = currentPaused != paused;
                String previousApproval = currentApproval;
                currentStatus = nextStatus;
                currentPaused = paused;
                currentApproval = nextApproval;
                ensureOverlay();
                if (firstDisplay || statusChanged) statusView.setText("Agent · " + currentStatus);
                if (firstDisplay || pauseChanged) pauseButton.setText(paused ? "继续" : "暂停");
                boolean waiting = approval != null && !approval.isEmpty();
                if (firstDisplay || !currentApproval.equals(previousApproval)) approvalView.setText(waiting ? approval : "");
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
                displayedCallbackGeneration = callbackGeneration;
            } catch (RuntimeException failure) {
                // A task must not continue without its user-visible stop control.
                stopFromDevice();
            }
        });
    }

    public void hideOverlay() { onMain(() -> { watchedPackages = Collections.emptySet(); removeOverlay(); }); }

    /** Waits only for the actual owned View's platform registration and stable layout. */
    public CompletableFuture<OwnedControlWindowIdentity> acquireOwnedControlWindowIdentity() {
        CompletableFuture<OwnedControlWindowIdentity> result = new CompletableFuture<>();
        ControlWindowWaiter waiter = new ControlWindowWaiter(result);
        result.whenComplete((unused, failure) -> onMain(waiter::close));
        onMain(waiter::start);
        return result;
    }

    /** Acquires actual compact control geometry before the final worker snapshot begins. */
    public CompletableFuture<ControlLease> acquireControlLease() {
        CompletableFuture<ControlLease> result = new CompletableFuture<>();
        ControlLeaseWaiter waiter = new ControlLeaseWaiter(result);
        result.whenComplete((unused, failure) -> onMain(waiter::close));
        onMain(waiter::start);
        return result;
    }

    private void invalidateControlLease() {
        invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_INVALIDATED);
    }

    private void invalidateControlLease(SecureImageReviewOverlay.CloseSource source) {
        closeImageReview(source);
        currentControlLease = null;
        currentControlWindowIdentity = null;
        controlGeneration++;
    }

    private void invalidateControlWindowRegistration() {
        invalidateControlWindowRegistration(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_INVALIDATED);
    }

    private void invalidateControlWindowRegistration(SecureImageReviewOverlay.CloseSource source) {
        invalidateVisualEvents(); visualControlGeometry = null;
        invalidateControlLease(source);
        controlWindowRegistration = null;
        controlAttachmentGeneration++;
    }

    /** Called on attachment or a bounded acquisition retry, never on every frame. */
    private void readOwnedControlWindowRegistration() {
        View panel = overlay;
        if (!connected || instance != this || panel == null || !panel.isAttachedToWindow()) return;
        IBinder token = panel.getWindowToken();
        Display display = panel.getDisplay();
        if (token == null || display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY) return;
        WindowRegistration current = controlWindowRegistration;
        if (current != null && current.token == token && current.displayId == display.getDisplayId()
                && current.connectionGeneration == connectionGeneration
                && current.attachmentGeneration == controlAttachmentGeneration) return;
        if (current != null) invalidateControlWindowRegistration();
        AccessibilityNodeInfo ownNode = panel.createAccessibilityNodeInfo();
        if (ownNode == null) return;
        try {
            int id = ownNode.getWindowId();
            if (id < 0) return;
            controlWindowRegistration = new WindowRegistration(token, id, display.getDisplayId(),
                    connectionGeneration, controlAttachmentGeneration);
        } finally { ownNode.recycle(); }
    }

    /** Main-thread local geometry only; an already registered View is not queried again. */
    private OwnedControlWindowIdentity stableControlWindowIdentity() {
        WindowRegistration registration = controlWindowRegistration;
        if (!connected || instance != this || overlay == null || callbacks == null
                || !overlay.isAttachedToWindow() || !overlay.isShown() || !overlay.isLaidOut()
                || overlay.isLayoutRequested() || overlay.getWidth() <= 0 || overlay.getHeight() <= 0
                || registration == null) {
            if (currentControlWindowIdentity != null) invalidateControlLease();
            return null;
        }
        Display display = overlay.getDisplay();
        if (registration.token != overlay.getWindowToken() || display == null
                || registration.displayId != display.getDisplayId()
                || registration.connectionGeneration != connectionGeneration
                || registration.attachmentGeneration != controlAttachmentGeneration) {
            invalidateControlWindowRegistration();
            return null;
        }
        Rect bounds = controlBounds();
        if (bounds.isEmpty()) return null;
        OwnedControlWindowIdentity previous = currentControlWindowIdentity;
        if (previous != null && previous.matches(registration, bounds, controlGeneration, callbackGeneration)) return previous;
        if (previous != null) invalidateControlLease();
        OwnedControlWindowIdentity identity = new OwnedControlWindowIdentity(registration, bounds, controlGeneration, callbackGeneration);
        if (visualControlGeometry != null && !visualControlGeometry.sameRegistrationAndBounds(identity)) invalidateVisualEvents();
        visualControlGeometry = identity;
        currentControlWindowIdentity = identity;
        return identity;
    }

    private final class ControlWindowWaiter implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final CompletableFuture<OwnedControlWindowIdentity> result;
        private final Runnable poll = this::poll;
        private View panel;
        private ViewTreeObserver observer;
        private long deadline;

        ControlWindowWaiter(CompletableFuture<OwnedControlWindowIdentity> result) { this.result = result; }

        void start() {
            if (result.isDone()) return;
            panel = overlay;
            if (!connected || panel == null || callbacks == null || !panel.isAttachedToWindow()) {
                result.completeExceptionally(new IllegalStateException("任务控制窗口尚未附着"));
                return;
            }
            deadline = SystemClock.elapsedRealtime() + CONTROL_IDENTITY_TIMEOUT_MS;
            observer = panel.getViewTreeObserver(); observer.addOnPreDrawListener(this);
            panel.addOnAttachStateChangeListener(this);
            poll();
        }

        private void poll() {
            if (result.isDone()) return;
            if (SystemClock.elapsedRealtime() >= deadline) {
                result.completeExceptionally(new IllegalStateException("任务控制窗口身份未就绪"));
                return;
            }
            if (!validAttachment()) return;
            try { readOwnedControlWindowRegistration(); }
            catch (RuntimeException unavailable) {
                result.completeExceptionally(new IllegalStateException("无法读取任务控制窗口身份"));
                return;
            }
            checkLayout();
            if (!result.isDone()) main.postDelayed(poll, EVENT_READY_CHECK_INTERVAL_MS);
        }

        private boolean validAttachment() {
            if (!connected || instance != AgentAccessibilityService.this || panel != overlay || callbacks == null
                    || panel == null || !panel.isAttachedToWindow()) {
                result.completeExceptionally(new IllegalStateException("任务控制窗口已改变"));
                return false;
            }
            return true;
        }

        private void checkLayout() {
            if (result.isDone() || !validAttachment()) return;
            OwnedControlWindowIdentity identity = stableControlWindowIdentity();
            if (identity != null) result.complete(identity);
        }

        @Override public boolean onPreDraw() { checkLayout(); return true; }
        @Override public void onViewAttachedToWindow(View view) { checkLayout(); }
        @Override public void onViewDetachedFromWindow(View view) {
            result.completeExceptionally(new IllegalStateException("任务控制窗口已移除"));
        }
        void close() {
            main.removeCallbacks(poll);
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            if (panel != null) panel.removeOnAttachStateChangeListener(this);
            observer = null; panel = null;
        }
    }

    /** Main thread only. Never replace a valid token merely because another frame was drawn. */
    private ControlLease stableControlLease() {
        if (!connected || instance != this || overlay == null || callbacks == null
                || !overlay.isAttachedToWindow() || !overlay.isShown() || !overlay.isLaidOut()
                || overlay.isLayoutRequested() || overlay.getWidth() <= 0 || overlay.getHeight() <= 0
                || !currentApproval.isEmpty() || approvalScroll == null || approvalButtons == null
                || approvalScroll.getVisibility() != View.GONE || approvalButtons.getVisibility() != View.GONE) {
            if (currentControlLease != null) invalidateControlLease();
            return null;
        }
        Rect bounds = controlBounds();
        if (bounds.isEmpty()) {
            if (currentControlLease != null) invalidateControlLease();
            return null;
        }
        OwnedControlWindowIdentity identity = stableControlWindowIdentity();
        if (identity == null) return null;
        ControlLease previous = currentControlLease;
        if (previous != null && previous.matches(identity, bounds, controlGeneration,
                connectionGeneration, callbackGeneration)) return previous;
        ControlLease lease = new ControlLease(identity, bounds, controlGeneration,
                connectionGeneration, callbackGeneration);
        currentControlLease = lease;
        return lease;
    }

    private final class ControlLeaseWaiter implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final CompletableFuture<ControlLease> result;
        private CompletableFuture<OwnedControlWindowIdentity> identityReady;
        private View panel;
        private ViewTreeObserver observer;

        ControlLeaseWaiter(CompletableFuture<ControlLease> result) { this.result = result; }

        void start() {
            if (result.isDone()) return;
            panel = overlay;
            if (panel == null || !connected || callbacks == null || !panel.isAttachedToWindow()) {
                result.completeExceptionally(new IllegalStateException("任务控制条不可用"));
                return;
            }
            observer = panel.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            panel.addOnAttachStateChangeListener(this);
            identityReady = acquireOwnedControlWindowIdentity();
            identityReady.whenComplete((identity, failure) -> onMain(() -> {
                if (result.isDone()) return;
                if (failure != null) result.completeExceptionally(new IllegalStateException("任务控制窗口身份不可用"));
                else checkLayout();
            }));
            checkLayout();
        }

        private void checkLayout() {
            if (result.isDone()) return;
            if (!connected || panel != overlay || callbacks == null || !panel.isAttachedToWindow()) {
                result.completeExceptionally(new IllegalStateException("任务控制条已改变"));
                return;
            }
            ControlLease lease = stableControlLease();
            if (lease != null) result.complete(lease);
        }

        @Override public boolean onPreDraw() { checkLayout(); return true; }
        @Override public void onViewAttachedToWindow(View view) { checkLayout(); }
        @Override public void onViewDetachedFromWindow(View view) {
            result.completeExceptionally(new IllegalStateException("任务控制条已移除"));
        }
        void close() {
            if (identityReady != null) identityReady.cancel(false);
            identityReady = null;
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            if (panel != null) panel.removeOnAttachStateChangeListener(this);
            observer = null;
            panel = null;
        }
    }

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
        LinearLayout panel = new LinearLayout(this) {
            @Override public void requestLayout() {
                invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_LAYOUT_REQUEST);
                super.requestLayout();
            }
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                if (changed) {
                    invalidateVisualEvents();
                    invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_LAYOUT_CHANGED);
                }
                super.onLayout(changed, left, top, right, bottom);
            }
            @Override protected void onAttachedToWindow() {
                invalidateControlWindowRegistration(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_ATTACHED);
                super.onAttachedToWindow();
            }
            @Override protected void onDetachedFromWindow() {
                invalidateControlWindowRegistration(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_DETACHED);
                super.onDetachedFromWindow();
            }
            @Override protected void onVisibilityChanged(View changedView, int visibility) {
                invalidateVisualEvents();
                invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_VISIBILITY);
                super.onVisibilityChanged(changedView, visibility);
            }
            @Override protected void onWindowVisibilityChanged(int visibility) {
                invalidateVisualEvents();
                invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_VISIBILITY);
                super.onWindowVisibilityChanged(visibility);
            }
        };
        panel.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (overlay == panel) {
                stableControlWindowIdentity();
                stableControlLease();
            }
            return true;
        });
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
        panel.post(() -> {
            if (overlay != panel || !panel.isAttachedToWindow()) return;
            try { readOwnedControlWindowRegistration(); stableControlWindowIdentity(); }
            catch (RuntimeException unavailable) { invalidateControlWindowRegistration(); }
        });
        statusView.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            int originalY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startY = event.getRawY(); originalY = params.y; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    invalidateVisualEvents();
                    invalidateControlLease();
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
        invalidateControlWindowRegistration(SecureImageReviewOverlay.CloseSource.SERVICE_CONTROL_REMOVED);
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
        displayedCallbackGeneration = -1;
    }

    private void stopFromDevice() {
        invalidateVisualEvents();
        invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_DEVICE_STOP);
        if (stopNotifying) return;
        stopNotifying = true;
        try { if (callbacks != null) callbacks.onStop(); }
        finally { removeOverlay(); watchedPackages = Collections.emptySet(); stopNotifying = false; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Invalidate before review handling or the existing deferred readiness check.
        // Unexpected event kinds are conservative too; no event source is queried.
        if (event == null || !isCurrentOwnedCosmeticEvent(event)) invalidateVisualEvents();
        if (event == null) return;
        SecureImageReviewOverlay review = reviewHost;
        if (review != null) review.onAccessibilityEvent(event.getEventType(), event.getWindowId(),
                event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED ? event.getWindowChanges() : 0);
        WindowRegistration registered = controlWindowRegistration;
        if (registered != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && event.getWindowId() == registered.windowId
                && (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) {
            // A native registration may disappear independently of a View callback.
            // An old window ID cannot be reclaimed by matching a later window's text.
            invalidateControlWindowRegistration();
        }
        // Let the framework process every window-removal/focus event for its cache.
        // Only coalesce our own readiness queries; retain no event or node contents.
        // This deadline is not extended by later events, so continuous scrolling
        // cannot postpone the check indefinitely. Broadcast and dispatch guards
        // remain immediate and do not use this queue.
        if (overlay == null || eventReadyCheckPending) return;
        eventReadyCheckPending = true;
        main.postDelayed(eventReadyCheck, EVENT_READY_CHECK_INTERVAL_MS);
    }

    /** Explicit cosmetic allowlist, only for an actual current, stable owned attachment. */
    private boolean isCurrentOwnedCosmeticEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (event.getWindowChanges() != AccessibilityEvent.WINDOWS_CHANGE_TITLE) return false;
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            int safe = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION;
            int changes = event.getContentChangeTypes();
            if (changes == 0 || (changes & ~safe) != 0) return false;
        } else if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return false;
        int id = event.getWindowId();
        if (id < 0 || !isConnected()) return false;
        // A status publication can clear the action identity before preDraw has
        // republished it. The same actual attachment and its previous measured
        // bounds may identify a cosmetic event only after verifying the current
        // View is laid out, not requesting layout, and still has those bounds.
        OwnedControlWindowIdentity controls = visualControlGeometry;
        if (controls != null && callbacks != null && controls.windowId == id && controls.registration == controlWindowRegistration
                && controls.registration.attachmentGeneration == controlAttachmentGeneration
                && isStableVisualAttachment(overlay, controls.registration, controls.copyBounds(), false)) return true;
        OwnedReviewWindowIdentity review = visualReviewGeometry;
        return review != null && reviewHost == review.owner && !review.owner.isEnded()
                && review.windowId == id && review.registration == reviewWindowRegistration
                && review.registration.attachmentGeneration == reviewAttachmentGeneration
                && isStableVisualAttachment(review.owner.rootView(), review.registration, review.copyBounds(), true);
    }

    /** Main-only local View geometry. No root/window query, label or historical ID establishes ownership. */
    private boolean isStableVisualAttachment(View view, WindowRegistration registration, Rect expected, boolean secure) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown() || !view.isLaidOut()
                || view.isLayoutRequested() || registration.connectionGeneration != connectionGeneration
                || view.getWindowToken() != registration.token) return false;
        Display display = view.getDisplay();
        if (display == null || display.getDisplayId() != registration.displayId
                || !(view.getLayoutParams() instanceof WindowManager.LayoutParams)) return false;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        int required = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | (secure ? WindowManager.LayoutParams.FLAG_SECURE : 0);
        if (params.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY || (params.flags & required) != required) return false;
        int[] location = new int[2]; view.getLocationOnScreen(location);
        return view.getWidth() > 0 && view.getHeight() > 0 && expected.left == location[0] && expected.top == location[1]
                && expected.right == location[0] + view.getWidth() && expected.bottom == location[1] + view.getHeight();
    }

    @Override public void onInterrupt() {
        windowCaptureCapability = false;
        stopFromDevice();
    }

    @Override public void onLowMemory() {
        invalidateVisualEvents();
        closeImageReview(SecureImageReviewOverlay.CloseSource.SERVICE_LOW_MEMORY); super.onLowMemory();
    }

    @Override public void onTrimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            invalidateVisualEvents();
            closeImageReview(level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                    ? SecureImageReviewOverlay.CloseSource.SERVICE_UI_HIDDEN
                    : SecureImageReviewOverlay.CloseSource.SERVICE_TRIM_MEMORY);
        }
        super.onTrimMemory(level);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        invalidateVisualEvents();
        invalidateControlLease(SecureImageReviewOverlay.CloseSource.SERVICE_CONFIGURATION);
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
        windowCaptureCapability = false;
        invalidateVisualEvents();
        invalidateControlWindowRegistration(SecureImageReviewOverlay.CloseSource.SERVICE_DISCONNECTED);
        connectionGeneration++;
        connected = false;
        if (instance == this) instance = null;
        stopFromDevice();
        callbacks = null;
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(visualDisplayListener); }
            catch (RuntimeException unavailable) { /* Disconnection still clears the other registrations below. */ }
            finally { displayManager = null; }
        }
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
