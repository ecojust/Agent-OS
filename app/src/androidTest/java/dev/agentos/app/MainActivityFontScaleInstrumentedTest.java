package dev.agentos.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.text.Layout;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.agentos.core.ActionConfirmationPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real system configuration recreation; synthetic drafts remain in memory, never screenshots or saved state. */
@RunWith(AndroidJUnit4.class)
public final class MainActivityFontScaleInstrumentedTest {
    private static final String TASK = "synthetic-font-task-memory-marker";
    private static final String KEY = "synthetic-font-key-memory-marker";
    private static final String MODEL = "synthetic-font-model-memory-marker";
    private static final String ENDPOINT_SUFFIX = "/synthetic-font-endpoint-marker";
    private static final int[] EDITORS = {R.id.settings_endpoint, R.id.settings_model, R.id.settings_api_key,
            R.id.settings_max_output_tokens, R.id.task_prompt, R.id.task_budget_rounds,
            R.id.task_budget_tools, R.id.task_budget_seconds};
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_activity_font_draft_capture_not_attempted");

    @Test public void realSystemFontScaleRecreatesSecureActivityAndRetainsOnlyInMemoryDraft() throws Exception {
        try (DeviceHarness device = new DeviceHarness();
             LocalControllerModelServer server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK)) {
            AgentController controller = AgentController.get(device.context);
            assertFalse("The isolated UI test requires an idle controller", controller.getState().running);
            ControllerBaseline controllerBaseline = ControllerBaseline.prepare(device, controller);
            try (ControllerTestSettings settings = new ControllerTestSettings(device.context, server)) {
                FontScaleControlWindowInstrumentedTest.OriginalFont original =
                        new FontScaleControlWindowInstrumentedTest.OriginalFont(device);
                onMain(device, device.service::hideOverlay);
                SettingsStore store = new SettingsStore(device.context);
                SettingsStore.SavedSettings saved = store.load();
                SharedPreferences preferences = device.context.getSharedPreferences(
                        "agent_settings_secure_" + saved.profileId.replace(':', '_'), Context.MODE_PRIVATE);
                Map<String, ?> persisted = preferences.getAll();
                Lifecycle lifecycle = new Lifecycle(device);
                String phase = "baseline";
                Throwable primary = null;
                try {
                    lifecycle.register();
                    FontScaleControlWindowInstrumentedTest.changeFont(device, "1.0", 1.0f);
                    Host first = open(device, lifecycle, 1.0f);
                    Baseline before = onMain(device, () -> {
                        assertSecure(first.activity);
                        seedDraft(first.activity, server.endpoint());
                        assertDraft(first.activity, saved.profileId, server.endpoint(), false);
                        first.activity.dismissOwnTaskKeyboard();
                        return new Baseline(first.activity);
                    });
                    assertUnchanged(device, controllerBaseline, server, store, settings, preferences, persisted);
                    screenshots.capture("secure-main-activity-font-1_0-memory-draft");

                    phase = "real_configuration_recreation";
                    // Register first; only the actual system setting triggers replacement.
                    // The settings helper retains its original ten-second deadline.
                    long recreationDeadline = SystemClock.elapsedRealtime() + 10_000;
                    FontScaleControlWindowInstrumentedTest.changeFont(device, "1.3", 1.3f);
                    Host enlarged = awaitHost(device, lifecycle, first.activity, 1.3f, recreationDeadline);
                    onMain(device, () -> {
                        lifecycle.assertHealthy();
                        assertNotSame(first.activity, enlarged.activity);
                        assertTrue("The original Activity must actually be destroyed", first.destroyed);
                        assertTrue("The old instance must be destroyed for a real configuration change", first.configurationDestroy);
                        assertTrue("The system must save the original Activity state", first.savedStates > 0);
                        assertTrue("The original saved-state Bundle must have been checked", first.bundleBytes > 0);
                        assertSecure(first.activity); assertSecure(enlarged.activity);
                        assertDraft(enlarged.activity, saved.profileId, server.endpoint(), true);
                        Baseline after = new Baseline(enlarged.activity);
                        assertTrue(after.editorPixels > before.editorPixels);
                        assertTrue(after.buttonPixels > before.buttonPixels);
                        assertEquals(before.density, after.density, 0.0001f);
                        assertEquals(before.rotation, after.rotation);
                        assertEquals(before.displayBounds, after.displayBounds);
                        assertEquals(LinearLayout.VERTICAL,
                                ((LinearLayout) enlarged.activity.findViewById(R.id.task_budget_fields)).getOrientation());
                        enlarged.activity.dismissOwnTaskKeyboard();
                    });
                    awaitWorkerExit(first, SystemClock.elapsedRealtime() + 5_000);
                    assertReachable(device, enlarged.activity, R.id.start_task, false);
                    assertReachable(device, enlarged.activity, R.id.save_settings, true);
                    // Reachability does not invoke save, task start or a model probe.
                    assertUnchanged(device, controllerBaseline, server, store, settings, preferences, persisted);
                    screenshots.capture("secure-main-activity-font-1_3-real-replacement");

                    phase = "finish_and_fresh_open";
                    finish(device, enlarged, SystemClock.elapsedRealtime() + 10_000);
                    Host reopened = open(device, lifecycle, 1.3f);
                    onMain(device, () -> {
                        lifecycle.assertHealthy();
                        assertNotSame(enlarged.activity, reopened.activity);
                        assertFalse("An explicit finish is not configuration retention", enlarged.configurationDestroy);
                        assertNull("A fresh launch must not inherit the old in-memory draft",
                                reopened.activity.getLastNonConfigurationInstance());
                        assertSaved(reopened.activity, saved);
                        assertSecure(reopened.activity);
                    });
                    assertUnchanged(device, controllerBaseline, server, store, settings, preferences, persisted);
                    screenshots.capture("secure-fresh-main-activity-discards-unsaved-draft");
                } catch (Exception | AssertionError failure) {
                    String diagnostic = "Secure Activity font test failed;phase=" + phase
                            + ",requests=" + server.requestCount() + ",dispatchAttempts=" + device.control.getDispatchAttempts();
                    if (failure instanceof AssertionError) {
                        AssertionError reported = new AssertionError(diagnostic, failure);
                        primary = reported;
                        throw reported;
                    }
                    // Preserve JUnit's original error/failure classification.
                    failure.addSuppressed(new AssertionError(diagnostic));
                    primary = failure;
                    throw failure;
                } finally {
                    Throwable cleanup = null;
                    // Restoration can create another real Activity. Keep lifecycle
                    // ownership until every observed host and its workers have exited.
                    try { original.restore(device); }
                    catch (Exception | AssertionError failure) { cleanup = failure; }
                    try { lifecycle.finishAll(); }
                    catch (Exception | AssertionError failure) { cleanup = append(cleanup, failure); }
                    finally {
                        try { lifecycle.unregister(); }
                        catch (Exception | AssertionError failure) { cleanup = append(cleanup, failure); }
                        try { onMain(device, device.service::hideOverlay); }
                        catch (Exception | AssertionError failure) { cleanup = append(cleanup, failure); }
                    }
                    try {
                        onMain(device, lifecycle::assertHealthy);
                        assertUnchanged(device, controllerBaseline, server, store, settings, preferences, persisted);
                        server.assertHealthy();
                    } catch (Exception | AssertionError failure) { cleanup = append(cleanup, failure); }
                    if (cleanup != null) {
                        if (primary != null) primary.addSuppressed(cleanup);
                        else {
                            cleanup.addSuppressed(new AssertionError("Could not restore the font or close all synthetic Activity hosts"));
                            rethrow(cleanup);
                        }
                    }
                }
            }
        }
    }

    private static void seedDraft(MainActivity activity, String endpoint) {
        if (((View) field(activity, "modelFields")).getVisibility() != View.VISIBLE)
            assertTrue(button(activity, R.id.configuration_toggle).performClick());
        if (activity.findViewById(R.id.task_budget_fields).getVisibility() != View.VISIBLE)
            assertTrue(button(activity, R.id.task_budget_toggle).performClick());
        editor(activity, R.id.settings_endpoint).setText(endpoint + ENDPOINT_SUFFIX);
        editor(activity, R.id.settings_model).setText(MODEL);
        editor(activity, R.id.settings_api_key).setText(KEY);
        editor(activity, R.id.task_prompt).setText(TASK);
        editor(activity, R.id.settings_max_output_tokens).setText("8193");
        editor(activity, R.id.task_budget_rounds).setText("19");
        editor(activity, R.id.task_budget_tools).setText("1001");
        editor(activity, R.id.task_budget_seconds).setText("241");
        RadioButton continuous = activity.findViewById(R.id.execution_mode_continuous_navigation);
        // CompoundButton toggles before returning whether an OnClickListener handled the click.
        continuous.performClick();
        assertTrue("The real radio click must select continuous navigation", continuous.isChecked());
        assertEquals(R.id.execution_mode_continuous_navigation,
                ((RadioGroup) activity.findViewById(R.id.confirm_actions)).getCheckedRadioButtonId());
        assertEquals(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION,
                ((SettingsStore.SavedSettings) field(activity, "draft")).executionMode);
    }

    private static void assertDraft(MainActivity activity, String profile, String endpoint, boolean retained) {
        assertText(activity, R.id.settings_endpoint, endpoint + ENDPOINT_SUFFIX);
        assertText(activity, R.id.settings_model, MODEL); assertText(activity, R.id.settings_api_key, KEY);
        assertText(activity, R.id.task_prompt, TASK); assertText(activity, R.id.settings_max_output_tokens, "8193");
        assertText(activity, R.id.task_budget_rounds, "19"); assertText(activity, R.id.task_budget_tools, "1001");
        assertText(activity, R.id.task_budget_seconds, "241");
        assertTrue(((RadioButton) activity.findViewById(R.id.execution_mode_continuous_navigation)).isChecked());
        assertEquals(View.VISIBLE, ((View) field(activity, "modelFields")).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.task_budget_fields).getVisibility());
        assertTrue(editor(activity, R.id.settings_api_key).getTransformationMethod() instanceof PasswordTransformationMethod);
        assertTrue("A draft must not acquire saved-probe authority",
                "测试当前填写的图像理解".contentEquals(button(activity, R.id.test_model_vision).getText()));
        assertNull(field(activity, "modelProbe")); assertNull(field(activity, "savedProbeOwner"));
        for (int id : EDITORS) assertFalse("Editors must never write View state; id=" + id, editor(activity, id).isSaveEnabled());
        assertFalse(activity.findViewById(R.id.confirm_actions).isSaveEnabled());
        assertFalse(activity.findViewById(R.id.execution_mode_continuous_navigation).isSaveEnabled());
        if (retained) {
            Object memory = activity.getLastNonConfigurationInstance(); assertNotNull(memory);
            SettingsStore.SavedSettings retainedSettings = (SettingsStore.SavedSettings) field(memory, "settings");
            assertTrue("Retained state must belong to the same profile", profile.equals(retainedSettings.profileId));
            assertSame("The loaded Activity must use the actual nonconfiguration draft", retainedSettings, field(activity, "draft"));
            assertTrue("The task draft must stay in the real in-memory object", TASK.equals(field(memory, "task")));
            assertTrue("Invalid token spelling must stay in the real in-memory object", "8193".equals(field(memory, "maxOutputTokensText")));
            assertNull("The temporary handoff reference must be consumed after binding", field(activity, "retainedDraft"));
        }
    }

    private static void assertSaved(MainActivity activity, SettingsStore.SavedSettings saved) {
        assertText(activity, R.id.settings_endpoint, saved.endpoint); assertText(activity, R.id.settings_model, saved.model);
        assertText(activity, R.id.settings_api_key, saved.apiKey); assertText(activity, R.id.task_prompt, "");
        assertText(activity, R.id.settings_max_output_tokens, Integer.toString(saved.maxOutputTokens));
        assertText(activity, R.id.task_budget_rounds, Integer.toString(saved.taskBudget.maxModelRounds));
        assertText(activity, R.id.task_budget_tools, Integer.toString(saved.taskBudget.maxToolCalls));
        assertText(activity, R.id.task_budget_seconds, Long.toString(saved.taskBudget.maxActiveMillis / 1000));
        assertTrue(((RadioButton) activity.findViewById(R.id.execution_mode_confirm_all)).isChecked());
        assertTrue(editor(activity, R.id.settings_api_key).getTransformationMethod() instanceof PasswordTransformationMethod);
        for (int id : EDITORS) assertFalse(editor(activity, id).isSaveEnabled());
    }

    private static void assertUnchanged(DeviceHarness device, ControllerBaseline controllerBaseline,
                                        LocalControllerModelServer server, SettingsStore store, ControllerTestSettings settings,
                                        SharedPreferences preferences, Map<String, ?> persisted) throws Exception {
        controllerBaseline.assertUnchanged();
        assertEquals(0, server.requestCount()); assertEquals(0, device.control.getDispatchAttempts());
        assertTrue("Unsaved UI changes must not change encrypted preferences", persisted.equals(preferences.getAll()));
        assertSame(settings.lease, store.loadWithLease().lease); assertTrue(SettingsStore.isCurrent(settings.lease));
        assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, store.load().executionMode);
    }

    private static final class ControllerBaseline {
        final AgentController controller;
        final Object lock;
        final ThreadPoolExecutor worker;
        final long generation, completedTasks;
        ControllerBaseline(AgentController controller, Object lock, ThreadPoolExecutor worker) {
            this.controller = controller; this.lock = lock; this.worker = worker;
            synchronized (lock) { generation = (Long) field(controller, "generation"); }
            completedTasks = worker.getCompletedTaskCount();
        }
        static ControllerBaseline prepare(DeviceHarness device, AgentController controller) throws Exception {
            Object lock = field(controller, "lock");
            ThreadPoolExecutor worker = (ThreadPoolExecutor) field(controller, "worker");
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            await(() -> {
                synchronized (lock) {
                    return field(controller, "control") == null && worker.getActiveCount() == 0 && worker.getQueue().isEmpty();
                }
            }, deadline, "The previous controller worker did not become idle");
            onMain(device, controller::clearCompletedTask);
            await(() -> onMain(device, () -> !((java.util.concurrent.atomic.AtomicBoolean)
                            field(controller, "publicationPending")).get() && controller.getState().usage == null),
                    deadline, "The empty controller state did not finish its real UI publication");
            ControllerBaseline baseline = new ControllerBaseline(controller, lock, worker);
            baseline.assertUnchanged();
            return baseline;
        }
        void assertUnchanged() {
            synchronized (lock) {
                // Every real task creates an owner and advances this monotonic
                // generation. A brief task cannot hide behind an idle end state.
                assertEquals("The actual controller must not create a task generation", generation,
                        ((Long) field(controller, "generation")).longValue());
                assertNull("The actual controller must not retain a task owner", field(controller, "control"));
                AgentController.State state = controller.getState();
                assertFalse(state.running); assertNull(state.usage); assertNull(state.resultKind);
                assertTrue(state.approvalId.isEmpty());
            }
            assertEquals(0, worker.getActiveCount()); assertTrue(worker.getQueue().isEmpty());
            assertEquals("No controller task worker may run", completedTasks, worker.getCompletedTaskCount());
        }
    }

    private static Host open(DeviceHarness device, Lifecycle lifecycle, float scale) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        MainActivity activity = (MainActivity) device.instrumentation.startActivitySync(
                new Intent(device.context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Host host = awaitHost(device, lifecycle, null, scale, deadline);
        assertSame("The tracked host must be the Activity that was actually launched", activity, host.activity);
        return host;
    }

    private static Host awaitHost(DeviceHarness device, Lifecycle lifecycle, MainActivity previous,
                                  float scale, long deadline) throws Exception {
        AtomicReference<Host> result = new AtomicReference<>();
        await(() -> onMain(device, () -> {
            lifecycle.assertHealthy();
            Host host = lifecycle.latest();
            if (host == null || host.activity == previous || !host.resumed || host.destroyed) return false;
            MainActivity activity = host.activity;
            View root = activity.findViewById(android.R.id.content);
            if (!activity.hasWindowFocus() || root == null || !root.isAttachedToWindow() || !root.isLaidOut()
                    || root.isLayoutRequested() || !button(activity, R.id.save_settings).isEnabled()
                    || !editor(activity, R.id.settings_max_output_tokens).isEnabled()
                    || Math.abs(activity.getResources().getConfiguration().fontScale - scale) >= 0.0001f) return false;
            result.set(host); return true;
        }), deadline, "Actual Activity, resource configuration and asynchronous saved settings did not become ready");
        return result.get();
    }

    private static void assertReachable(DeviceHarness device, MainActivity activity, int id, boolean scroll) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        onMain(device, () -> {
            activity.dismissOwnTaskKeyboard();
            if (scroll) {
                View child = activity.findViewById(id);
                // Actual scrolling through the public View API; no physical swipe claim.
                assertTrue("The important action must be inside the real ScrollView", scrollParent(child) != null);
                child.requestRectangleOnScreen(new Rect(0, 0, child.getWidth(), child.getHeight()), true);
            }
        });
        await(() -> onMain(device, () -> {
            View view = activity.findViewById(id);
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            return view.isAttachedToWindow() && view.isLaidOut() && !view.isLayoutRequested()
                    && insets != null && !insets.isVisible(WindowInsets.Type.ime()) && whollyVisible(view);
        }), deadline, "The enlarged important action did not become fully visible after real scrolling");
        onMain(device, () -> {
            assertSecure(activity);
            Button action = button(activity, id);
            assertTrue(action.isEnabled() && action.isClickable() && action.isShown());
            float density = activity.getResources().getDisplayMetrics().density;
            assertTrue(action.getWidth() >= Math.round(48 * density));
            assertTrue(action.getHeight() >= Math.round(48 * density));
            Insets bars = activity.getWindow().getDecorView().getRootWindowInsets()
                    .getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Rect safe = new Rect(activity.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds());
            safe.set(safe.left + bars.left, safe.top + bars.top, safe.right - bars.right, safe.bottom - bars.bottom);
            assertTrue("Important action geometry must remain inside the real safe window", safe.contains(bounds(action)));
            assertTrue(whollyVisible(action));
            Layout text = action.getLayout(); assertNotNull(text); assertTrue(text.getLineCount() > 0);
            assertEquals(action.getText().length(), text.getLineEnd(text.getLineCount() - 1));
            assertTrue(text.getHeight() <= action.getHeight() - action.getCompoundPaddingTop() - action.getCompoundPaddingBottom());
            int width = action.getWidth() - action.getCompoundPaddingLeft() - action.getCompoundPaddingRight();
            for (int line = 0; line < text.getLineCount(); line++) {
                assertEquals(0, text.getEllipsisCount(line));
                assertTrue(text.getLineLeft(line) >= -0.5f && text.getLineRight(line) <= width + 0.5f);
            }
        });
    }

    private static ScrollView scrollParent(View view) {
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent())
            if (parent instanceof ScrollView) return (ScrollView) parent;
        return null;
    }
    private static boolean whollyVisible(View view) {
        Rect visible = new Rect();
        return view.getLocalVisibleRect(visible) && visible.equals(new Rect(0, 0, view.getWidth(), view.getHeight()));
    }
    private static Rect bounds(View view) {
        int[] xy = new int[2]; view.getLocationOnScreen(xy);
        return new Rect(xy[0], xy[1], xy[0] + view.getWidth(), xy[1] + view.getHeight());
    }

    private static final class Baseline {
        final float editorPixels, buttonPixels, density;
        final int rotation;
        final Rect displayBounds;
        Baseline(MainActivity activity) {
            editorPixels = editor(activity, R.id.task_prompt).getTextSize();
            buttonPixels = button(activity, R.id.start_task).getTextSize();
            density = activity.getResources().getDisplayMetrics().density;
            rotation = activity.getWindow().getDecorView().getDisplay().getRotation();
            displayBounds = new Rect(activity.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds());
        }
    }

    private static final class Host {
        final MainActivity activity;
        ExecutorService storage, diagnostics;
        boolean resumed, destroyed, configurationDestroy;
        int savedStates, bundleBytes;
        Host(MainActivity activity) { this.activity = activity; }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        private static final int MAX_EXPECTED_HOSTS = 8, MAX_BUNDLE_BYTES = 4 * 1024 * 1024;
        final DeviceHarness device;
        final Application application;
        final List<Host> hosts = new ArrayList<>();
        String firstFailure;
        boolean registered;
        Lifecycle(DeviceHarness device) {
            this.device = device; application = (Application) device.context.getApplicationContext();
        }
        void register() { onMain(device, () -> { application.registerActivityLifecycleCallbacks(this); registered = true; }); }
        void unregister() { onMain(device, () -> { if (registered) application.unregisterActivityLifecycleCallbacks(this); registered = false; }); }
        Host latest() { return hosts.isEmpty() ? null : hosts.get(hosts.size() - 1); }
        Host find(Activity activity) { for (Host host : hosts) if (host.activity == activity) return host; return null; }
        void reject(String code) { if (firstFailure == null) firstFailure = code; }
        void assertHealthy() { assertNull("First bounded Activity lifecycle invariant failure", firstFailure); }
        private void secure(Activity activity, String stage) {
            if (!(activity instanceof MainActivity)) return;
            try {
                if (activity.getWindow() == null
                        || (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0)
                    reject(stage + "_secure_flag_missing");
            } catch (RuntimeException failed) { reject(stage + "_window_unavailable"); }
        }
        @Override public void onActivityCreated(Activity activity, Bundle state) { /* super.onCreate precedes the app's secure flag. */ }
        @Override public void onActivityPostCreated(Activity activity, Bundle state) {
            if (!(activity instanceof MainActivity)) return;
            secure(activity, "post_created");
            // Own every actual host before any diagnostic/reflection can fail.
            // Unexpected extra recreations remain failures but still get cleanup.
            Host host = new Host((MainActivity) activity); hosts.add(host);
            if (hosts.size() > MAX_EXPECTED_HOSTS) reject("too_many_activity_hosts");
            try {
                host.storage = (ExecutorService) field(activity, "storageWorker");
                host.diagnostics = (ExecutorService) field(activity, "diagnosticsWorker");
            }
            catch (RuntimeException | AssertionError failed) { reject("host_worker_snapshot_unavailable"); }
        }
        @Override public void onActivityStarted(Activity activity) { secure(activity, "started"); }
        @Override public void onActivityResumed(Activity activity) {
            secure(activity, "resumed"); Host host = find(activity); if (host != null) host.resumed = true;
        }
        @Override public void onActivityPaused(Activity activity) { Host host = find(activity); if (host != null) host.resumed = false; }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityPostSaveInstanceState(Activity activity, Bundle state) {
            Host host = find(activity); if (host == null) return;
            host.savedStates++;
            Parcel parcel = Parcel.obtain();
            try {
                state.writeToParcel(parcel, 0);
                host.bundleBytes = parcel.dataSize();
                if (host.bundleBytes <= 0 || host.bundleBytes > MAX_BUNDLE_BYTES) { reject("saved_state_size_out_of_bound"); return; }
                byte[] bytes = parcel.marshall();
                String[] markers = {TASK, KEY, MODEL, ENDPOINT_SUFFIX,
                        LocalControllerModelServer.SYNTHETIC_KEY, LocalControllerModelServer.MODEL};
                for (String marker : markers) {
                    if (contains(bytes, marker.getBytes(StandardCharsets.UTF_8))
                            || contains(bytes, marker.getBytes(StandardCharsets.UTF_16LE))) {
                        reject("sensitive_marker_in_actual_saved_state"); return;
                    }
                }
            } catch (RuntimeException failed) { reject("saved_state_scan_unavailable"); }
            finally { parcel.recycle(); }
        }
        @Override public void onActivityDestroyed(Activity activity) {
            Host host = find(activity);
            if (host != null) { host.destroyed = true; host.resumed = false; host.configurationDestroy = activity.isChangingConfigurations(); }
        }
        void finishAll() throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            Throwable failure = null;
            while (true) {
                List<Host> batch = onMain(device, () -> new ArrayList<>(hosts));
                failure = append(failure, requestFinish(batch));
                // One failure must not prevent a different host or worker from
                // receiving cleanup. Every wait shares the original deadline.
                for (Host host : batch) {
                    try {
                        await(() -> onMain(device, () -> host.destroyed && host.activity.isDestroyed()), deadline,
                                "A test-owned Activity did not finish during cleanup");
                    } catch (Exception | AssertionError failed) { failure = append(failure, failed); }
                    try { awaitWorkerExit(host, deadline); }
                    catch (Exception | AssertionError failed) { failure = append(failure, failed); }
                }
                if (onMain(device, () -> hosts.size() == batch.size())) break;
                if (SystemClock.elapsedRealtime() >= deadline) {
                    failure = append(failure, requestFinish(onMain(device, () -> new ArrayList<>(hosts))));
                    failure = append(failure, new AssertionError("Additional Activity hosts exceeded the original cleanup deadline"));
                    break;
                }
            }
            rethrow(failure);
        }
        private Throwable requestFinish(List<Host> batch) {
            return onMain(device, () -> {
                Throwable failure = null;
                for (Host host : batch) {
                    try {
                        if (!host.activity.isDestroyed() && !host.activity.isFinishing()) host.activity.finish();
                    } catch (RuntimeException | AssertionError failed) { failure = append(failure, failed); }
                }
                return failure;
            });
        }
    }

    private static boolean contains(byte[] bytes, byte[] needle) {
        outer: for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (bytes[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
    private static void finish(DeviceHarness device, Host host, long deadline) throws Exception {
        onMain(device, () -> { if (!host.activity.isDestroyed() && !host.activity.isFinishing()) host.activity.finish(); });
        await(() -> onMain(device, () -> host.destroyed && host.activity.isDestroyed()), deadline,
                "The test-owned Activity did not finish");
        awaitWorkerExit(host, deadline);
    }
    private static void awaitWorkerExit(Host host, long deadline) throws Exception {
        Throwable failure = null;
        for (ExecutorService worker : new ExecutorService[]{host.storage, host.diagnostics}) {
            try {
                assertNotNull("The actual Activity worker snapshot must exist", worker);
                assertTrue("The old Activity worker did not terminate", worker.awaitTermination(
                        Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS));
                assertTrue("The Activity worker exceeded its original deadline", SystemClock.elapsedRealtime() < deadline);
            } catch (Exception | AssertionError failed) { failure = append(failure, failed); }
        }
        rethrow(failure);
    }
    private static void await(BooleanSupplier condition, long deadline, String message) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean matched = condition.getAsBoolean();
            if (SystemClock.elapsedRealtime() >= deadline) break;
            if (matched) return;
            Thread.sleep(25);
        }
        throw new AssertionError(message);
    }
    private static void assertSecure(Activity activity) {
        assertTrue("Every actual application window must remain secure",
                (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
    private static EditText editor(Activity activity, int id) { return activity.findViewById(id); }
    private static Button button(Activity activity, int id) { return activity.findViewById(id); }
    private static void assertText(Activity activity, int id, String expected) {
        assertTrue("Synthetic editor value mismatch; id=" + id, expected.contentEquals(editor(activity, id).getText()));
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failed) { throw new AssertionError("Fixed test field is unavailable: " + name, failed); }
    }
    private static Throwable append(Throwable first, Throwable next) {
        if (next == null) return first;
        if (first == null) return next; if (first != next) first.addSuppressed(next); return first;
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw new AssertionError("Unexpected cleanup failure type", failure);
    }
    private static void onMain(DeviceHarness device, Runnable action) { onMain(device, () -> { action.run(); return null; }); }
    private static <T> T onMain(DeviceHarness device, Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        device.instrumentation.runOnMainSync(() -> { try { result.set(action.get()); } catch (Throwable failed) { error.set(failed); } });
        Throwable failure = error.get();
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure != null) throw new AssertionError("Main-thread test action failed", failure);
        return result.get();
    }
}
