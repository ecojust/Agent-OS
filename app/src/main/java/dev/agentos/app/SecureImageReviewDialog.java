package dev.agentos.app;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import dev.agentos.core.ImageAttachment;
import dev.agentos.core.ImageReviewSession;

import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal component host, with no production capture or network entry point.
 * Bringing this Activity forward changes third-party focus and may invalidate a captured
 * target window. Synthetic dialog tests do not establish that such a target remains valid.
 * A future production host must reuse the presentation under its own trusted overlay/window
 * guards and revalidate the actual target; this dialog never exempts an Agent foreground
 * window from the session's binding checks.
 *
 * The Activity must already own FLAG_SECURE. This dialog also secures its own window and
 * never clears either flag. One host and one decode operation are admitted process-wide;
 * old RenderThread references may briefly outlive drawable detachment, so releasing a
 * preview is not a promise of synchronous pixel-memory erasure.
 */
final class SecureImageReviewDialog extends Dialog implements AutoCloseable {
    interface Listener {
        void onApproved(ImageReviewSession.ApprovalToken approval);
        void onClosed(ImageReviewSession.State state);
    }

    private static final long CONTEXT_CHECK_INTERVAL_MS = 250;
    private static final ThreadPoolExecutor DECODER = new ThreadPoolExecutor(0, 1, 10,
            TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
        Thread worker = new Thread(task, "agent-os-image-preview");
        worker.setDaemon(true);
        return worker;
    }, new ThreadPoolExecutor.AbortPolicy());
    // Includes decoded results posted to main; worker completion alone must not free this slot.
    private static final AtomicBoolean PREVIEW_SLOT = new AtomicBoolean();
    // Accessed only on the main thread. A single decoder alone would not bound visible images.
    private static SecureImageReviewDialog activeHost;

    private final Activity activity;
    private final ImageReviewSession session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageReviewView reviewView;
    private Listener listener;
    private volatile ImageReviewSession.HostToken hostToken;
    private volatile boolean closeRequested;
    private boolean shown, ended, registered, hadWindowFocus;

    private final Runnable deadline = this::onDeadline;
    private final Runnable contextCheck = new Runnable() {
        @Override public void run() {
            if (ended) return;
            refresh();
            if (!ended) main.postDelayed(this, Math.min(CONTEXT_CHECK_INTERVAL_MS,
                    Math.max(1, session.remainingMillis())));
        }
    };
    private final ViewTreeObserver.OnPreDrawListener presented = this::onPreDraw;
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity value, Bundle state) { }
        @Override public void onActivityStarted(Activity value) { }
        @Override public void onActivityResumed(Activity value) { }
        @Override public void onActivityPaused(Activity value) { if (value == activity) dismiss(); }
        @Override public void onActivityStopped(Activity value) { if (value == activity) dismiss(); }
        @Override public void onActivitySaveInstanceState(Activity value, Bundle state) { }
        @Override public void onActivityDestroyed(Activity value) { if (value == activity) dismiss(); }
    };
    private final ComponentCallbacks2 configuration = new ComponentCallbacks2() {
        @Override public void onConfigurationChanged(Configuration value) { dismiss(); }
        @Override public void onLowMemory() { dismiss(); }
        @Override public void onTrimMemory(int level) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) dismiss();
        }
    };

    SecureImageReviewDialog(Activity activity, ImageReviewSession session, Listener listener) {
        super(activity);
        requireMain();
        if (session == null || listener == null) throw new IllegalArgumentException("图片审阅会话不完整。");
        this.activity = activity;
        this.session = session;
        this.listener = listener;
        setOwnerActivity(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window == null) throw new IllegalStateException("图片审阅窗口不可用。");
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.setDecorFitsSystemWindows(true);
        setCanceledOnTouchOutside(false);
        reviewView = new ImageReviewView(activity, session.binding, this::approve, this::reject);
        setContentView(reviewView);
    }

    private void onDeadline() {
        session.expire();
        refresh();
    }

    private boolean onPreDraw() {
        if (ended) return true;
        if (!hostReady()) { dismiss(); return true; }
        if (session.state() == ImageReviewSession.State.PREPARING
                && reviewView.isReadyToPresent() && reviewView.hasWindowFocus()) {
            reviewView.setPresented(session.markPresented(hostToken));
        }
        return true;
    }

    /** Call only after PNG validation has completed on a worker; no PNG parsing happens here. */
    @Override public void show() {
        requireMain();
        if (shown || ended || closeRequested) throw new IllegalStateException("图片审阅窗口不可复用。");
        if (activeHost != null) {
            if (activeHost.session == session) {
                // This unclaimed duplicate shell owns no session resources. Leave its owner alone.
                ended = true;
                reviewView.clearPreview();
                listener = null;
            } else { finishHost(); }
            throw new IllegalStateException("已有图片审阅正在进行。");
        }
        if (!hostReady()) {
            session.invalidate(ImageReviewSession.Reason.HOST_LOST);
            finishHost();
            throw new IllegalStateException("请在已解锁的安全窗口中审阅图片。");
        }
        hostToken = session.claimHost();
        if (hostToken == null) {
            finishHost();
            throw new IllegalStateException("图片审阅已失效。");
        }
        shown = true;
        activeHost = this;
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        activity.getApplication().registerComponentCallbacks(configuration);
        registered = true;
        reviewView.getViewTreeObserver().addOnPreDrawListener(presented);
        try {
            super.show();
            Window window = getWindow();
            if (window == null || !hostReady()) throw new IllegalStateException("图片审阅窗口已失效。");
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            main.postDelayed(deadline, Math.max(1, session.remainingMillis()));
            main.postDelayed(contextCheck, CONTEXT_CHECK_INTERVAL_MS);
            if (!PREVIEW_SLOT.compareAndSet(false, true)) {
                throw new RejectedExecutionException("上一图片预览仍在释放资源。");
            }
            try { DECODER.execute(this::decodePreview); }
            catch (RuntimeException rejected) { PREVIEW_SLOT.set(false); throw rejected; }
        } catch (RuntimeException failure) {
            dismiss();
            throw failure;
        }
    }

    private void decodePreview() {
        byte[] encoded = null;
        Bitmap decoded = null;
        try {
            encoded = session.copyPngForPreview(hostToken);
            if (encoded != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                options.inMutable = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
                if (decoded != null && (decoded.getWidth() != session.width || decoded.getHeight() != session.height
                        || decoded.isMutable() || decoded.getConfig() != Bitmap.Config.ARGB_8888
                        || decoded.getAllocationByteCount() > ImageAttachment.MAX_DECODED_BYTES)) {
                    // This bitmap has never been posted to a renderer.
                    decoded.recycle();
                    decoded = null;
                }
            }
        } catch (RuntimeException | OutOfMemoryError failedPreview) {
            // Decode errors can contain source details; expose only the failed review state.
            if (decoded != null) decoded.recycle();
            decoded = null;
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
        Bitmap result = decoded;
        if (!main.post(() -> {
            try { installPreview(result); }
            finally { PREVIEW_SLOT.set(false); }
        })) {
            if (result != null) result.recycle();
            PREVIEW_SLOT.set(false);
        }
    }

    private void installPreview(Bitmap bitmap) {
        requireMain();
        if (ended || !hostReady() || session.state() != ImageReviewSession.State.PREPARING || bitmap == null) {
            if (bitmap != null) bitmap.recycle(); // Never attached, so no renderer can own it.
            if (!ended) dismiss();
            return;
        }
        reviewView.installPreview(bitmap);
        reviewView.requestLayout();
        reviewView.invalidate();
    }

    private void approve() {
        requireMain();
        if (ended || !hostReady() || !reviewView.isReadyToPresent()) { dismiss(); return; }
        ImageReviewSession.ApprovalToken approval = session.approve(hostToken);
        if (approval == null) { refresh(); return; }
        reviewView.setApproved();
        try { if (listener != null) listener.onApproved(approval); }
        catch (RuntimeException failedConsumer) { dismiss(); return; }
        // Keep an unconsumed approval revocable. Automatic dismissal here would revoke it.
        refresh();
    }

    private void reject() {
        session.reject(hostToken);
        // reject is a one-shot decision; dismiss also revokes an already approved permit.
        dismiss();
    }

    /** Prompt cancellation entry for a future controller; the timer also checks missed changes. */
    void refresh() {
        requireMain();
        if (ended) return;
        ImageReviewSession.State state = session.state();
        if (!hostReady() || state == ImageReviewSession.State.CONSUMED
                || state == ImageReviewSession.State.REJECTED || state == ImageReviewSession.State.EXPIRED
                || state == ImageReviewSession.State.INVALIDATED || state == ImageReviewSession.State.CLOSED) {
            dismiss();
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            hadWindowFocus = true;
            if (!ended) reviewView.invalidate();
        } else if (hadWindowFocus && shown && !ended) dismiss();
    }

    @Override public void dismiss() {
        closeRequested = true;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Revoke this claimed host synchronously; UI teardown may wait for the main queue.
            // An unclaimed shell has no authority to revoke another host of the same session.
            ImageReviewSession.HostToken claimed = hostToken;
            if (claimed != null) session.invalidateHost(claimed);
            main.post(this::dismiss);
            return;
        }
        finishHost();
        super.dismiss();
    }

    @Override protected void onStop() { finishHost(); super.onStop(); }
    @Override public void onDetachedFromWindow() { finishHost(); super.onDetachedFromWindow(); }
    @Override public void close() { dismiss(); }

    private void finishHost() {
        requireMain();
        if (ended) return;
        ended = true;
        main.removeCallbacks(deadline);
        main.removeCallbacks(contextCheck);
        if (hostToken != null) session.invalidateHost(hostToken);
        else if (activeHost == null || activeHost == this || activeHost.session != session) {
            // An unshown duplicate shell must not revoke the session claimed by another host.
            session.invalidate(ImageReviewSession.Reason.HOST_LOST);
        }
        reviewView.clearPreview();
        ViewTreeObserver observer = reviewView.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(presented);
        if (registered) {
            registered = false;
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            activity.getApplication().unregisterComponentCallbacks(configuration);
        }
        if (activeHost == this) activeHost = null;
        Listener completed = listener;
        listener = null;
        if (completed != null) {
            try { completed.onClosed(session.state()); }
            catch (RuntimeException ignored) { }
        }
    }

    private boolean hostReady() {
        Window window = getWindow();
        if (activity.isFinishing() || activity.isDestroyed() || window == null
                || (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0
                || (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) return false;
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        PowerManager power = activity.getSystemService(PowerManager.class);
        return keyguard != null && power != null && !keyguard.isKeyguardLocked() && power.isInteractive();
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("请在主线程显示图片审阅。");
    }
}
