package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Real platform accessibility nodes and actions; no claim about TalkBack speech or navigation. */
@RunWith(AndroidJUnit4.class)
public final class MainActivityAccessibilityInstrumentedTest {
    private static final String KEY = "synthetic-accessibility-key-memory-only";
    private static final int[] EDITORS = {R.id.settings_endpoint, R.id.settings_model, R.id.settings_api_key,
            R.id.settings_max_output_tokens, R.id.task_budget_rounds, R.id.task_budget_tools,
            R.id.task_budget_seconds, R.id.task_prompt};
    private static final int[] LABELS = {R.id.settings_endpoint_label, R.id.settings_model_label,
            R.id.settings_api_key_label, R.id.settings_max_output_tokens_label, R.id.task_budget_rounds_label,
            R.id.task_budget_tools_label, R.id.task_budget_seconds_label, R.id.task_prompt_label};
    private static final String[] LABEL_TEXT = {"服务地址", "模型名称", "API 密钥", "每次回复 Token 上限",
            "模型轮次", "工具调用", "活动时间（秒）", "这次想完成什么？"};
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_native_accessibility_capture_not_attempted");

    @Test public void filledEditorsPublishRealLabelRelationshipsWithoutSavingOrStartingWork() throws Exception {
        try (Form form = new Form()) {
            form.expanded(R.id.configuration_toggle, true);
            form.expanded(R.id.task_budget_toggle, true);
            String[] values = {form.server.endpoint() + "/draft", "synthetic-accessibility-model", KEY,
                    "3072", "17", "63", "420", "synthetic-accessibility-task-memory-only"};
            for (int i = 0; i < EDITORS.length; i++) {
                final int index = i;
                form.setText(EDITORS[i], values[i]);
                form.withVisibleNode(EDITORS[i], node -> {
                    assertTrue("A filled editor must remain editable and enabled", node.isEditable() && node.isEnabled());
                    assertTrue("A fixed description must not replace the editor's own content", node.getContentDescription() == null);
                    AccessibilityNodeInfo label = node.getLabeledBy();
                    assertNotNull("The remote editor must publish its actual label relationship", label);
                    try {
                        assertEquals(form.resource(LABELS[index]), label.getViewIdResourceName());
                        assertTrue("The remote label must retain its fixed wording", LABEL_TEXT[index].equals(String.valueOf(label.getText())));
                        assertEquals(node.getWindowId(), label.getWindowId());
                        AccessibilityNodeInfo target = label.getLabelFor();
                        assertNotNull("The remote label must resolve back to its editor", target);
                        try {
                            assertEquals(form.resource(EDITORS[index]), target.getViewIdResourceName());
                            assertEquals(node.getWindowId(), target.getWindowId());
                        } finally { target.recycle(); }
                    } finally { label.recycle(); }
                });
            }
            form.onMain(() -> {
                for (int i = 0; i < EDITORS.length; i++)
                    assertTrue("Only the in-memory editor may contain the synthetic draft", values[i].contentEquals(form.editor(EDITORS[i]).getText()));
            });
            form.assertUnchanged();
            screenshots.capture("protected-filled-editors-remote-labels");
        }
    }

    @Test public void remoteHeadingsAndCollapseActionsExposeCurrentStateAndVisibleChildren() throws Exception {
        try (Form form = new Form()) {
            int[] headings = {R.id.service_section_heading, R.id.scope_section_heading, R.id.task_result_section_heading,
                    R.id.task_prompt_label, R.id.configuration_toggle, R.id.task_budget_toggle};
            for (int id : headings) form.withVisibleNode(id,
                    node -> assertTrue("The actual platform node must identify this section as a heading", node.isHeading()));
            int[][] groups = {{R.id.settings_endpoint, R.id.settings_model, R.id.settings_api_key, R.id.settings_max_output_tokens},
                    {R.id.task_budget_rounds, R.id.task_budget_tools, R.id.task_budget_seconds}};
            int[] toggles = {R.id.configuration_toggle, R.id.task_budget_toggle};
            for (int i = 0; i < toggles.length; i++) {
                form.expanded(toggles[i], true);
                for (int id : groups[i]) form.withVisibleNode(id, node -> assertTrue(node.isEditable()));
                form.click(toggles[i]);
                form.awaitState(toggles[i], "已收起");
                for (int id : groups[i]) form.awaitNotVisible(id);
                form.click(toggles[i]);
                form.awaitState(toggles[i], "已展开");
                for (int id : groups[i]) form.withVisibleNode(id, node -> assertTrue(node.isEditable()));
            }
            form.assertUnchanged();
            screenshots.capture("protected-remote-section-and-collapse-states");
        }
    }

    @Test public void remoteKeyActionsAndRealRecreationKeepFixedDescriptionsAndRemaskTheDraft() throws Exception {
        try (Form form = new Form()) {
            form.expanded(R.id.configuration_toggle, true);
            form.setText(R.id.settings_api_key, KEY);
            final int[] inputType = new int[1];
            form.onMain(() -> {
                inputType[0] = form.editor(R.id.settings_api_key).getInputType();
                assertEquals(InputType.TYPE_CLASS_TEXT, inputType[0] & InputType.TYPE_MASK_CLASS);
                assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, inputType[0] & InputType.TYPE_MASK_VARIATION);
            });
            form.assertKeyState(true, inputType[0]);
            form.click(R.id.toggle_api_key_visibility);
            form.assertKeyState(false, inputType[0]);
            form.click(R.id.toggle_api_key_visibility);
            form.assertKeyState(true, inputType[0]);
            form.click(R.id.toggle_api_key_visibility);
            form.assertKeyState(false, inputType[0]);
            MainActivity previous = form.activity;
            long recreationDeadline = SystemClock.elapsedRealtime() + 10_000;
            form.onMain(() -> { form.expectedHosts = form.hosts.size() + 1; previous.recreate(); });
            form.awaitReady(previous, recreationDeadline);
            assertNotSame(previous, form.activity);
            form.awaitMain(previous::isDestroyed, "The previous real Activity was not destroyed");
            form.assertKeyState(true, inputType[0]);
            form.assertUnchanged();
            screenshots.capture("protected-key-actions-and-recreated-draft-remasked");
        }
    }

    /** This fixture owns only its Activities, synthetic saved configuration and UiAutomation flags. */
    private static final class Form implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final Application application = (Application) context.getApplicationContext();
        final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final AgentController controller = AgentController.get(context);
        final List<MainActivity> hosts = new ArrayList<>();
        final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPostCreated(Activity activity, Bundle state) {
                if (activity instanceof MainActivity) hosts.add((MainActivity) activity);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        };
        LocalControllerModelServer server;
        ControllerTestSettings settings;
        SharedPreferences preferences;
        Map<String, ?> persisted;
        MainActivity activity;
        int originalFlags;
        boolean flagsOwned, lifecycleOwned;
        int expectedHosts;
        long generation, controllerTasks;

        Form() throws Exception {
            try {
                assertFalse("Native form tests require an idle controller", controller.getState().running);
                ThreadPoolExecutor worker = (ThreadPoolExecutor) field(controller, "worker");
                await(() -> worker.getActiveCount() == 0 && worker.getQueue().isEmpty(), "The controller worker is not idle");
                synchronized (field(controller, "lock")) {
                    generation = (Long) field(controller, "generation");
                    assertNull(field(controller, "control"));
                    controllerTasks = worker.getTaskCount();
                }
                AccessibilityServiceInfo info = automation.getServiceInfo();
                assertNotNull("The real UiAutomation connection must be available", info);
                originalFlags = info.flags; flagsOwned = true;
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                automation.setServiceInfo(info);
                server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(context, server);
                String profile = SettingsStore.currentProfileId(context);
                preferences = context.getSharedPreferences("agent_settings_secure_" + profile.replace(':', '_'), Context.MODE_PRIVATE);
                persisted = preferences.getAll();
                application.registerActivityLifecycleCallbacks(lifecycle); lifecycleOwned = true;
                long openDeadline = SystemClock.elapsedRealtime() + 10_000;
                expectedHosts = 1;
                Activity launched = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                awaitReady(null, openDeadline);
                assertSame("The inspected Activity must be the one actually launched", launched, activity);
            } catch (Throwable failure) {
                try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                rethrow(failure);
            }
        }

        void awaitReady(MainActivity previous, long deadline) {
            await(() -> {
                boolean[] ready = {false};
                onMain(() -> ready[0] = readyHost(previous));
                return ready[0];
            }, deadline, "The real native Activity did not become attached, resumed and ready");
        }

        private boolean readyHost(MainActivity previous) {
            if (hosts.isEmpty()) return false;
            assertTrue("Unexpected repeated Activity replacement", hosts.size() <= 4);
            MainActivity candidate = hosts.get(hosts.size() - 1);
            if (candidate == previous || candidate.isDestroyed() || !candidate.hasWindowFocus()
                    || !(Boolean) field(candidate, "resumed") || !(Boolean) field(candidate, "ready")
                    || (Boolean) field(candidate, "busy")) return false;
            View root = candidate.findViewById(android.R.id.content);
            if (root == null || !root.isAttachedToWindow() || !root.isLaidOut() || root.isLayoutRequested()
                    || !candidate.findViewById(R.id.save_settings).isEnabled()) return false;
            assertSecure(candidate); activity = candidate; return true;
        }

        String resource(int id) { return context.getResources().getResourceName(id); }
        EditText editor(int id) { return activity.findViewById(id); }
        void onMain(Runnable action) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
            Throwable error = failure.get();
            if (error instanceof Error) throw (Error) error;
            if (error instanceof RuntimeException) throw (RuntimeException) error;
            if (error != null) throw new AssertionError(error);
        }
        void awaitMain(BooleanSupplier condition, String message) {
            await(() -> { boolean[] value = {false}; onMain(() -> value[0] = condition.getAsBoolean()); return value[0]; }, message);
        }

        void expose(int id) {
            awaitMain(() -> {
                View target = activity.findViewById(id);
                return target != null && target.isAttachedToWindow() && target.isShown()
                        && target.isLaidOut() && !target.isLayoutRequested()
                        && !activity.findViewById(android.R.id.content).isLayoutRequested();
            }, "The owned control did not finish its actual layout before scrolling");
            onMain(() -> {
                assertSecure(activity);
                assertTrue("The inspected Activity must own the focused application window", activity.hasWindowFocus());
                View target = activity.findViewById(id);
                assertNotNull("The actual form control must exist", target);
                assertTrue(target.isAttachedToWindow() && target.isShown());
                // Real scrolling of the owned form, not a claim about a gesture or TalkBack navigation.
                int labelSpace = Math.round(36 * target.getResources().getDisplayMetrics().density);
                target.requestRectangleOnScreen(new Rect(0, -labelSpace, Math.max(1, target.getWidth()),
                        Math.max(1, target.getHeight())), true);
            });
        }

        AccessibilityNodeInfo find(int id) {
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            assertNotNull(windows);
            AccessibilityNodeInfo result = null;
            try {
                assertTrue("Unexpected window count", windows.size() <= 32);
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused()) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    try {
                        if (!context.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(resource(id));
                        assertNotNull(nodes);
                        try {
                            assertTrue("A fixed form ID must not identify many nodes", nodes.size() <= 4);
                            for (AccessibilityNodeInfo node : nodes) {
                                if (!node.refresh()) continue;
                                assertEquals(window.getId(), node.getWindowId());
                                assertTrue("A fixed form ID must have one current node", result == null);
                                result = AccessibilityNodeInfo.obtain(node);
                            }
                        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
                    } finally { root.recycle(); }
                }
                AccessibilityNodeInfo found = result; result = null; return found;
            } finally {
                if (result != null) result.recycle();
                for (AccessibilityWindowInfo window : windows) window.recycle();
            }
        }

        AccessibilityNodeInfo awaitNode(int id, Predicate<AccessibilityNodeInfo> condition) {
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            do {
                AccessibilityNodeInfo node = find(id);
                if (node != null) {
                    boolean keep = false;
                    try { if (condition.test(node)) { keep = true; return node; } }
                    finally { if (!keep) node.recycle(); }
                }
                SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("A current remote form node did not satisfy readiness: " + resource(id));
        }
        void withVisibleNode(int id, Consumer<AccessibilityNodeInfo> check) {
            expose(id);
            AccessibilityNodeInfo node = awaitNode(id, AccessibilityNodeInfo::isVisibleToUser);
            try { check.accept(node); } finally { node.recycle(); }
        }
        void click(int id) {
            withVisibleNode(id, node -> {
                assertTrue(node.isEnabled() && node.isClickable());
                assertTrue("The real remote control rejected ACTION_CLICK", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            });
        }
        void setText(int id, String value) {
            withVisibleNode(id, node -> {
                assertTrue(node.isEditable() && node.isEnabled());
                Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                assertTrue("The real remote editor rejected ACTION_SET_TEXT", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
            });
            if (id != R.id.settings_api_key) {
                AccessibilityNodeInfo filled = awaitNode(id, node -> node.getText() != null && value.contentEquals(node.getText()));
                filled.recycle();
            }
            awaitMain(() -> value.contentEquals(editor(id).getText()), "Remote editing must update the real in-memory draft");
        }
        void awaitState(int id, String state) {
            String action = id == R.id.configuration_toggle ? "模型连接 · " : "任务预算 · ";
            action += "已展开".equals(state) ? "收起设置" : "展开设置";
            final String expectedAction = action;
            AccessibilityNodeInfo node = awaitNode(id, value -> state.equals(String.valueOf(value.getStateDescription()))
                    && (id != R.id.configuration_toggle && id != R.id.task_budget_toggle
                    || expectedAction.equals(String.valueOf(value.getText()))));
            node.recycle();
        }
        void expanded(int id, boolean expanded) {
            final String wanted = expanded ? "已展开" : "已收起";
            withVisibleNode(id, node -> {
                String state = String.valueOf(node.getStateDescription());
                assertTrue("The toggle must publish one fixed state", "已展开".equals(state) || "已收起".equals(state));
                if (!wanted.equals(state)) assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            });
            awaitState(id, wanted);
        }
        void awaitNotVisible(int id) {
            await(() -> {
                AccessibilityNodeInfo node = find(id);
                if (node == null) return true;
                try { return !node.isVisibleToUser(); } finally { node.recycle(); }
            }, "A collapsed editor remained visible in the remote tree");
        }
        void assertKeyState(boolean hidden, int inputType) {
            final String action = hidden ? "显示密钥" : "隐藏密钥";
            final String state = hidden ? "密钥已隐藏" : "密钥已显示";
            awaitState(R.id.toggle_api_key_visibility, state);
            withVisibleNode(R.id.toggle_api_key_visibility, node -> {
                assertTrue("The key control must expose only its fixed next action", action.contentEquals(node.getText()));
                assertTrue("The key state must be fixed metadata", state.contentEquals(node.getStateDescription()));
                assertTrue("Key visibility metadata must not contain editor values", node.getContentDescription() == null);
                assertTrue(node.isEnabled() && node.isClickable());
            });
            withVisibleNode(R.id.settings_api_key, node -> {
                if (hidden) assertTrue("The masked remote editor must retain password semantics", node.isPassword());
                // Explicit reveal can legitimately change TextView's remote password flag/text.
                assertTrue(node.getContentDescription() == null);
            });
            onMain(() -> {
                assertSecure(activity);
                EditText key = editor(R.id.settings_api_key);
                assertEquals(inputType, key.getInputType());
                assertEquals(hidden, key.getTransformationMethod() instanceof PasswordTransformationMethod);
                assertFalse(key.isSaveEnabled());
                assertTrue("The synthetic key must remain the same in-memory draft", KEY.contentEquals(key.getText()));
            });
        }

        void assertUnchanged() {
            server.assertHealthy();
            assertEquals("Form metadata/actions must not send a model request", 0, server.requestCount());
            assertTrue("Reading and editing the form must not save its draft", persisted.equals(preferences.getAll()));
            synchronized (field(controller, "lock")) {
                assertEquals(generation, ((Long) field(controller, "generation")).longValue());
                assertNull(field(controller, "control"));
                ThreadPoolExecutor worker = (ThreadPoolExecutor) field(controller, "worker");
                assertEquals(controllerTasks, worker.getTaskCount());
                assertFalse(controller.getState().running);
            }
            onMain(() -> { assertSecure(activity); assertNull(field(activity, "modelProbe")); });
        }

        @Override public void close() throws Exception {
            final Throwable[] failure = {null};
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            List<MainActivity> owned = new ArrayList<>();
            boolean[] destroyed = {false};
            do {
                try {
                    onMain(() -> {
                        // Include a replacement that was still being created when a test failed.
                        for (MainActivity host : hosts) {
                            if (!owned.contains(host)) {
                                owned.add(host);
                                try { if (!host.isDestroyed()) host.finish(); }
                                catch (Throwable error) { failure[0] = append(failure[0], error); }
                            }
                        }
                        destroyed[0] = hosts.size() >= expectedHosts;
                        for (MainActivity host : hosts) destroyed[0] &= host.isDestroyed();
                    });
                } catch (Throwable error) { failure[0] = append(failure[0], error); }
                if (destroyed[0]) break;
                SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtime() < deadline);
            if (!destroyed[0]) failure[0] = append(failure[0], new AssertionError("Every expected owned Activity must be destroyed"));
            for (MainActivity host : owned) {
                try {
                    ExecutorService worker = (ExecutorService) field(host, "storageWorker");
                    assertTrue("Every owned settings worker must finish before preferences are restored",
                            worker.awaitTermination(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS));
                } catch (Throwable error) { failure[0] = append(failure[0], error); }
            }
            if (lifecycleOwned) {
                try { application.unregisterActivityLifecycleCallbacks(lifecycle); }
                catch (Throwable error) { failure[0] = append(failure[0], error); }
            }
            if (flagsOwned) {
                try {
                    AccessibilityServiceInfo info = automation.getServiceInfo(); assertNotNull(info);
                    info.flags = originalFlags; automation.setServiceInfo(info);
                } catch (Throwable error) { failure[0] = append(failure[0], error); }
            }
            if (settings != null) try { settings.close(); } catch (Throwable error) { failure[0] = append(failure[0], error); }
            if (server != null) try { server.close(); server.assertHealthy(); }
            catch (Throwable error) { failure[0] = append(failure[0], error); }
            if (failure[0] != null) rethrow(failure[0]);
        }
    }

    private static void assertSecure(Activity activity) {
        assertTrue("The real Activity window must retain FLAG_SECURE",
                (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
    private static void await(BooleanSupplier condition, String message) {
        await(condition, SystemClock.elapsedRealtime() + 5_000, message);
    }
    private static void await(BooleanSupplier condition, long deadline, String message) {
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(25); }
        while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(message);
    }
    private static Object field(Object owner, String name) {
        try { Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Throwable append(Throwable original, Throwable next) {
        if (original == null) return next;
        original.addSuppressed(next); return original;
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError(failure);
    }
}
