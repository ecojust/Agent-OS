package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.agentos.app.numericstest.NumericPreferencesDocumentsProvider;
import dev.agentos.app.numericstest.NumericPreferencesControlProvider;
import dev.agentos.core.NumericPreferences;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real protected Activities and system SAF. Explicit stale callbacks are only callback-boundary tests. */
@RunWith(AndroidJUnit4.class)
public final class NumericPreferencesInstrumentedTest {
    private static final int[] NUMBERS = {R.id.settings_max_output_tokens, R.id.task_budget_rounds,
            R.id.task_budget_tools, R.id.task_budget_seconds};
    private static final String[] IMPORTED = {"777", "6", "9", "45"};
    private static final String[] DRAFT = {"3072", "17", "63", "420"};
    @Rule public final ScreenshotEvidence screenshots =
            new ScreenshotEvidence("secure_numeric_preferences_capture_not_attempted");

    @Test public void realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields() throws Exception {
        try (Session ui = new Session()) {
            ui.seedDraft();
            Authority before = ui.authority();
            ui.configure("valid");
            Host broker = ui.begin(false);
            ui.chooseImport();
            ui.preview(broker);
            ui.assertNumbers(DRAFT);
            ui.assertUnchanged();
            ui.main(() -> {
                TextView values = broker.activity.findViewById(R.id.numeric_preferences_transfer_values);
                assertTrue(values.getText().toString().equals("每次回复 Token 上限：777\n模型轮次上限：6\n工具调用上限：9\n活动时间上限：45 秒"));
                assertFalse(values.isSaveEnabled());
                ui.click(broker.activity, R.id.numeric_preferences_transfer_apply);
            });
            ui.awaitMain();
            ui.assertNumbers(IMPORTED);
            before.assertMatches(ui.authority());
            ui.assertUnchanged();
            ui.assertActualIo(1, false);
            assertEquals(1, ui.pickerCount());
            ui.recreateMain();
            ui.assertNumbers(IMPORTED);
            before.assertMatches(ui.authority());
            ui.assertUnchanged();
            // Import only changed the draft. This separate real Save is the first persistent mutation.
            ui.saveNumbers(IMPORTED);
            assertEquals(new NumericPreferences(777, 6, 9, 45), ui.savedNumbers());
            ui.assertNoWork();
            screenshots.capture("protected-real-saf-preview-unsaved-import-and-explicit-save");
        }
    }

    @Test public void realSafExportWritesTheCapturedFourFieldSnapshotWithoutConfigurationAuthority() throws Exception {
        try (Session ui = new Session()) {
            ui.seedDraft();
            // Export does not require a usable model configuration and must never export these fields.
            ui.main(() -> {
                ui.editor(R.id.settings_endpoint).setText("synthetic-incomplete-address");
                ui.editor(R.id.settings_model).setText("");
            });
            Authority before = ui.authority();
            ui.configure("valid");
            ui.begin(true);
            ui.chooseExport();
            ui.awaitMain();
            ui.awaitIoClosed();
            Bundle output = ui.provider("export", null);
            byte[] bytes = output.getByteArray("bytes");
            assertNotNull(bytes);
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Set<String> keys = new HashSet<>(); json.keys().forEachRemaining(keys::add);
            assertEquals(new HashSet<>(Arrays.asList("format", "schemaVersion", "maxOutputTokens",
                    "maxModelRounds", "maxToolCalls", "maxActiveSeconds")), keys);
            assertEquals("agent-os-numeric-preferences", json.getString("format"));
            assertEquals(1, json.getInt("schemaVersion"));
            assertEquals(3072, json.getInt("maxOutputTokens"));
            assertEquals(17, json.getInt("maxModelRounds"));
            assertEquals(63, json.getInt("maxToolCalls"));
            assertEquals(420, json.getInt("maxActiveSeconds"));
            assertEquals(new NumericPreferences(3072, 17, 63, 420), NumericPreferences.decode(bytes));
            before.assertMatches(ui.authority());
            ui.assertNumbers(DRAFT);
            ui.assertActualIo(1, true);
            ui.assertUnchanged();
            screenshots.capture("protected-real-saf-export-four-numbers-without-configuration-authority");
        }
    }

    @Test public void malformedOversizedAndCancelledImportsLeaveTheOriginalDraftAndSavedStateUntouched() throws Exception {
        try (Session ui = new Session()) {
            ui.seedDraft(); Authority before = ui.authority();
            for (String mode : new String[]{"malformed", "oversize"}) {
                ui.configure(mode);
                ui.begin(false); ui.chooseImport(); ui.awaitMain(); ui.awaitIoClosed();
                ui.assertNumbers(DRAFT); before.assertMatches(ui.authority()); ui.assertUnchanged();
                ui.main(() -> assertTrue(ui.message().contains("无法导入此数值偏好文件")));
                ui.assertActualIo(1, false);
            }
            ui.configure("valid"); ui.begin(false);
            ui.selectProviderRoot();
            ui.cancelSystemPicker();
            ui.awaitMain();
            assertEquals(0, ui.state().getInt("openEntered"));
            ui.assertNumbers(DRAFT); before.assertMatches(ui.authority()); ui.assertUnchanged();
            ui.configure("valid");
            Host preview = ui.begin(false); ui.chooseImport(); ui.preview(preview);
            ui.main(() -> ui.click(preview.activity, R.id.numeric_preferences_transfer_cancel));
            ui.awaitMain(); ui.awaitIoClosed();
            ui.assertNumbers(DRAFT); before.assertMatches(ui.authority()); ui.assertUnchanged();
            screenshots.capture("protected-invalid-4097-byte-picker-cancel-and-preview-cancel-preserved");
        }
    }

    @Test public void oldPickerResultsCannotReachARecreatedMainOrClearANewerOwner() throws Exception {
        try (Session ui = new Session()) {
            ui.seedDraft(); ui.configure("valid");
            Host oldMain = ui.currentMain();
            Host oldBroker = ui.begin(false);
            NumericPreferencesTransfers.Owner oldOwner = ui.owner(oldMain);
            ui.selectProviderRoot();
            ui.main(oldMain.activity::recreate);
            ui.cancelSystemPicker();
            ui.awaitReplacementMain(oldMain);
            ui.assertNumbers(DRAFT);
            assertFalse(NumericPreferencesTransfers.isCurrent(oldOwner));
            Host replacement = ui.currentMain();
            Host newBroker = ui.begin(false);
            NumericPreferencesTransfers.Owner newOwner = ui.owner(replacement);
            assertNotSame(oldOwner, newOwner);
            // Explicit callback-boundary checks: no claim that Android emitted these stale responses.
            ui.main(() -> {
                result(oldMain.activity, NumericPreferencesTransfers.REQUEST_CODE, Activity.RESULT_OK,
                        new Intent().putExtra(NumericPreferencesTransfers.EXTRA_NONCE, oldOwner.nonce));
                result(replacement.activity, NumericPreferencesTransfers.REQUEST_CODE, Activity.RESULT_CANCELED,
                        new Intent().putExtra(NumericPreferencesTransfers.EXTRA_NONCE, oldOwner.nonce));
                result(replacement.activity, NumericPreferencesTransfers.REQUEST_CODE, Activity.RESULT_CANCELED, new Intent());
                result(oldBroker.activity, 1, Activity.RESULT_CANCELED, null);
                assertSame(newOwner, field(replacement.activity, "numericTransfer"));
                assertTrue(NumericPreferencesTransfers.isCurrent(newOwner));
                assertFalse((Boolean) field(replacement.activity, "numericResultReceived"));
                assertFalse(newBroker.destroyed);
            });
            assertEquals(0, ui.state().getInt("openEntered"));
            ui.selectProviderRoot(); ui.cancelSystemPicker(); ui.awaitMain();
            ui.assertNumbers(DRAFT); ui.assertUnchanged();
            screenshots.capture("protected-real-main-recreate-and-explicit-stale-callback-owner-isolation");
        }
    }

    @Test public void recreatedBrokerDoesNotReopenThePickerOrAdoptAnOrphanedTransfer() throws Exception {
        try (Session ui = new Session()) {
            ui.seedDraft(); ui.configure("valid");
            Host broker = ui.begin(false);
            NumericPreferencesTransfers.Owner original = ui.owner(ui.currentMain());
            int pickers = ui.pickerCount(), brokers = ui.brokerCount();
            ui.selectProviderRoot();
            ui.main(broker.activity::recreate);
            ui.cancelSystemPicker();
            // Android may finish the old broker on picker cancellation before its requested relaunch.
            // A replacement, when actually created, must use the real saved-state rejection path.
            ui.await(() -> ui.read(() -> broker.destroyed), "The original broker did not terminate after real recreate/cancellation");
            ui.awaitMain();
            int actualBrokers = ui.brokerCount();
            assertTrue("Recreate may not create more than one replacement", actualBrokers >= brokers && actualBrokers <= brokers + 1);
            assertEquals(pickers, ui.pickerCount());
            assertFalse(NumericPreferencesTransfers.isCurrent(original));
            assertEquals(0, ui.state().getInt("openEntered"));
            ui.main(() -> {
                assertNull(field(ui.currentMainOnMain().activity, "numericTransfer"));
                for (Host host : ui.hosts) if (host.activity instanceof NumericPreferencesTransferActivity) {
                    assertTrue("No recreated transfer window may remain owned", host.destroyed);
                    if (host != broker) assertTrue("An actual replacement must receive Android saved state", host.restored);
                }
            });
            ui.assertNumbers(DRAFT); ui.assertUnchanged();
            screenshots.capture("protected-real-broker-recreate-no-picker-reopen-or-orphan-adoption");
        }
    }

    @Test public void cancelledProviderIoKeepsItsProcessSlotUntilActualCloseWithoutBlockingMainWork() throws Exception {
        try (Session ui = new Session()) {
            ui.seedDraft(); ui.configure("blocked-open");
            Host broker = ui.begin(false); ui.chooseImport();
            ui.await(() -> ui.state().getInt("blockedWaiting") == 1,
                    "The remote provider did not enter its real bounded openDocument gate");
            assertTrue(NumericPreferencesTransfers.isWorkerBusy());
            ui.main(() -> ui.click(broker.activity, R.id.numeric_preferences_transfer_cancel));
            ui.awaitMain(); ui.recreateMain();
            int pickers = ui.pickerCount();
            ui.clickMain(R.id.numeric_preferences_import);
            ui.main(() -> {
                assertNull(field(ui.currentMainOnMain().activity, "numericTransfer"));
                assertTrue(ui.message().contains("上一项文件操作尚未结束"));
            });
            assertEquals(pickers, ui.pickerCount());
            assertTrue(NumericPreferencesTransfers.isWorkerBusy());
            assertEquals(0, ui.state().getInt("openReturned"));
            // This is real storage work on Main's separate executor, not a queued no-op or enabled-button claim.
            ui.saveNumbers(DRAFT);
            assertEquals(new NumericPreferences(3072, 17, 63, 420), ui.savedNumbers());
            assertTrue("Saving must not pretend the old provider returned", NumericPreferencesTransfers.isWorkerBusy());
            assertEquals(0, ui.state().getInt("openReturned"));
            ui.provider("release", null);
            ui.awaitIoClosed();
            assertFalse(ui.state().getBoolean("gateTimedOut"));
            assertEquals(1, ui.state().getInt("openClosed"));
            ui.configure("valid");
            Host next = ui.begin(false); ui.chooseImport(); ui.preview(next);
            ui.main(() -> ui.click(next.activity, R.id.numeric_preferences_transfer_apply));
            ui.awaitMain(); ui.assertNumbers(IMPORTED);
            // The new import is still only a draft; the explicit Save above remains the persisted value.
            assertEquals(new NumericPreferences(3072, 17, 63, 420), ui.savedNumbers());
            ui.assertUnchanged();
            screenshots.capture("protected-cancelled-provider-real-slot-and-independent-explicit-save");
        }
    }

    private static final class Host {
        final Activity activity;
        final boolean restored;
        boolean resumed, destroyed, finishRequested;
        Host(Activity activity, boolean restored) { this.activity = activity; this.restored = restored; }
    }

    private static final class Authority {
        final String[] text;
        final Set<String> allowed;
        final Map<String, String> grants;
        final Object mode;
        Authority(MainActivity activity) {
            text = new String[]{text(activity, R.id.settings_endpoint), text(activity, R.id.settings_model),
                    text(activity, R.id.settings_api_key), text(activity, R.id.task_prompt)};
            SettingsStore.SavedSettings draft = (SettingsStore.SavedSettings) field(activity, "draft");
            allowed = new HashSet<>(draft.allowedPackages);
            grants = new java.util.HashMap<>();
            for (Map.Entry<String, SettingsStore.AppGrant> entry : draft.grants.entrySet()) {
                SettingsStore.AppGrant grant = entry.getValue();
                grants.put(entry.getKey(), grant.packageName + "|" + grant.profileId + "|" + grant.firstInstallTime
                        + "|" + grant.uid + "|" + new java.util.TreeSet<>(grant.signers));
            }
            mode = draft.executionMode;
        }
        void assertMatches(Authority other) {
            assertTrue("Import/export changed a non-numeric in-memory field", Arrays.equals(text, other.text));
            assertEquals(allowed, other.allowed);
            assertTrue("Application grants changed", grants.equals(other.grants)); assertSame(mode, other.mode);
        }
    }

    private static final class Session implements AutoCloseable, Application.ActivityLifecycleCallbacks {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final Application application = (Application) context.getApplicationContext();
        final AgentController controller = AgentController.get(context);
        final List<Host> hosts = new ArrayList<>();
        final List<Intent> pickers = new ArrayList<>();
        final SettingsStore store = new SettingsStore(context);
        final SharedPreferences preferences = context.getSharedPreferences("agent_settings_secure_"
                + SettingsStore.currentProfileId(context).replace(':', '_'), Context.MODE_PRIVATE);
        final Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()) || Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction()))
                    synchronized (pickers) { pickers.add(new Intent(intent)); }
                return null; // Observe the real system picker; never intercept or substitute its result.
            }
        };
        LocalControllerModelServer server;
        ControllerTestSettings settings;
        SettingsStore.ConfigurationLease lease;
        Map<String, ?> persisted;
        AgentController.TaskUsage originalUsage;
        String lifecycleFailure;
        boolean registered, monitored, automationConfigured, closing;
        int originalAutomationFlags;

        Session() throws Exception {
            assertFalse("Synthetic document tests must not replace a running task", controller.getState().running);
            try {
                AccessibilityServiceInfo info = automation.getServiceInfo();
                originalAutomationFlags = info.flags;
                // Resource IDs and non-actionable container ancestry belong to this test's own SAF selectors.
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                automation.setServiceInfo(info); automationConfigured = true;
                awaitClientIdle();
                provider("reset", null);
                Bundle process = state();
                assertEquals(Process.myUid(), process.getInt("targetUid"));
                assertNotEquals("The DocumentsProvider must belong to the separate test APK UID", Process.myUid(), process.getInt("providerUid"));
                server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
                settings = new ControllerTestSettings(context, server);
                lease = settings.lease; persisted = preferences.getAll(); originalUsage = controller.getState().usage;
                main(() -> { application.registerActivityLifecycleCallbacks(this); registered = true; });
                instrumentation.addMonitor(monitor); monitored = true;
                instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                awaitMain();
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (Exception | AssertionError cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        Bundle provider(String method, String mode) {
            Bundle extras = new Bundle(); if (mode != null) extras.putString("mode", mode);
            Bundle value = context.getContentResolver().call(NumericPreferencesControlProvider.AUTHORITY, method, null, extras);
            assertNotNull("The fixed test provider call returned no result", value);
            return value;
        }
        Bundle state() { return provider("state", null); }
        void configure(String mode) { awaitClientIdle(); await(() -> state().getInt("activeOpens") == 0,
                "A previous real descriptor has not closed"); provider("configure", mode); }
        int pickerCount() { synchronized (pickers) { return pickers.size(); } }
        int brokerCountOnMain() { int count = 0; for (Host host : hosts) if (host.activity instanceof NumericPreferencesTransferActivity) count++; return count; }
        int brokerCount() { return read(this::brokerCountOnMain); }
        Host currentMainOnMain() {
            for (int i = hosts.size() - 1; i >= 0; i--) if (hosts.get(i).activity instanceof MainActivity && !hosts.get(i).destroyed) return hosts.get(i);
            return null;
        }
        Host currentMain() { return read(this::currentMainOnMain); }
        EditText editor(int id) { return currentMainOnMain().activity.findViewById(id); }
        String message() { return ((TextView) field(currentMainOnMain().activity, "messageText")).getText().toString(); }
        NumericPreferencesTransfers.Owner owner(Host host) { return read(() -> (NumericPreferencesTransfers.Owner) field(host.activity, "numericTransfer")); }
        Authority authority() { return read(() -> new Authority((MainActivity) currentMainOnMain().activity)); }
        void assertNumbers(String[] expected) {
            main(() -> { for (int i = 0; i < NUMBERS.length; i++) assertEquals(expected[i], editor(NUMBERS[i]).getText().toString()); });
        }
        void seedDraft() {
            main(() -> {
                Activity activity = currentMainOnMain().activity;
                if (!(Boolean) field(activity, "modelExpanded")) click(activity, R.id.configuration_toggle);
                if (!(Boolean) field(activity, "budgetExpanded")) click(activity, R.id.task_budget_toggle);
                editor(R.id.task_prompt).setText("synthetic-document-task-never-export");
                editor(R.id.settings_model).setText("synthetic-unsaved-document-model");
                editor(R.id.settings_api_key).setText("synthetic-unsaved-key-never-export");
                ((RadioButton) activity.findViewById(R.id.execution_mode_continuous_navigation)).setChecked(true);
                for (int i = 0; i < NUMBERS.length; i++) editor(NUMBERS[i]).setText(DRAFT[i]);
            });
            assertUnchanged();
        }
        void click(Activity activity, int id) {
            View target = activity.findViewById(id);
            assertNotNull(target);
            target.requestRectangleOnScreen(new Rect(0, 0, Math.max(1, target.getWidth()), Math.max(1, target.getHeight())), true);
            assertTrue("The actual control must be attached, shown and enabled", target.isAttachedToWindow() && target.isShown() && target.isEnabled());
            assertTrue("The original click listener did not accept the action", target.performClick());
        }
        void clickMain(int id) { main(() -> click(currentMainOnMain().activity, id)); }
        Host begin(boolean export) {
            int before = brokerCount(), count = pickerCount();
            clickMain(export ? R.id.numeric_preferences_export : R.id.numeric_preferences_import);
            await(() -> brokerCount() == before + 1 && pickerCount() == count + 1, "The real Main action did not launch one broker and one SAF picker");
            Intent picker;
            synchronized (pickers) { picker = pickers.get(count); }
            assertEquals(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
            assertEquals("application/json", picker.getType()); assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE));
            assertFalse(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true));
            assertEquals(0, picker.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            Host host = read(() -> {
                for (int i = hosts.size() - 1; i >= 0; i--) if (hosts.get(i).activity instanceof NumericPreferencesTransferActivity) return hosts.get(i);
                return null;
            });
            assertNotNull(host); main(() -> assertSecure(host.activity));
            await(() -> safRootAvailable(), "The real system DocumentsUI never became foreground");
            return host;
        }
        void preview(Host broker) {
            await(() -> read(() -> broker.resumed && !broker.destroyed && broker.activity.hasWindowFocus()
                    && broker.activity.findViewById(R.id.numeric_preferences_transfer_apply).getVisibility() == View.VISIBLE),
                    "The actual imported file did not reach the protected preview");
            main(() -> assertSecure(broker.activity)); awaitIoClosed();
        }
        void awaitMain() {
            await(() -> read(() -> {
                Host host = currentMainOnMain();
                return host != null && host.resumed && !host.destroyed && host.activity.hasWindowFocus()
                        && (Boolean) field(host.activity, "ready") && !(Boolean) field(host.activity, "busy")
                        && !(Boolean) field(host.activity, "storageDamaged") && field(host.activity, "numericTransfer") == null;
            }), "The original or replacement Main host did not become ready after the real transfer");
            main(() -> assertSecure(currentMainOnMain().activity));
        }
        void recreateMain() { Host old = currentMain(); main(old.activity::recreate); awaitReplacementMain(old); }
        void awaitReplacementMain(Host old) {
            await(() -> read(() -> old.destroyed && currentMainOnMain() != null && currentMainOnMain() != old),
                    "Actual Main recreation did not replace the old host");
            awaitMain();
        }
        NumericPreferences savedNumbers() throws Exception {
            SettingsStore.SavedSettings saved = store.load();
            return NumericPreferences.fromTaskBudget(saved.maxOutputTokens, saved.taskBudget);
        }
        void saveNumbers(String[] expected) throws Exception {
            clickMain(R.id.save_settings);
            // A real Save to the synthetic loopback configuration can require its ordinary HTTP trust dialog.
            main(() -> {
                List<View> matches = new ArrayList<>();
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    TextView positive = root.findViewById(android.R.id.button1);
                    if (positive != null && positive.isShown() && "信任此连接".contentEquals(positive.getText())) matches.add(positive);
                }
                assertTrue("At most one real HTTP trust confirmation may be outstanding", matches.size() <= 1);
                if (!matches.isEmpty()) assertTrue(matches.get(0).performClick());
            });
            await(() -> read(() -> !(Boolean) field(currentMainOnMain().activity, "busy")
                    && (Boolean) field(currentMainOnMain().activity, "ready")
                    && field(currentMainOnMain().activity, "savedConfigurationLease") != lease),
                    "The separate real settings worker did not finish explicit Save");
            SettingsStore.LoadedSettings loaded = store.loadWithLease();
            assertFalse(SettingsStore.isCurrent(lease)); lease = loaded.lease; assertTrue(SettingsStore.isCurrent(lease));
            assertEquals(new NumericPreferences(Integer.parseInt(expected[0]), Integer.parseInt(expected[1]),
                    Integer.parseInt(expected[2]), Integer.parseInt(expected[3])), savedNumbers());
            persisted = preferences.getAll(); assertNoWork();
        }
        void assertNoWork() {
            assertFalse(controller.getState().running); assertSame(originalUsage, controller.getState().usage);
            assertEquals(0, server.requestCount()); server.assertHealthy();
            main(() -> {
                assertNull(field(currentMainOnMain().activity, "modelProbe"));
                assertNull(field(currentMainOnMain().activity, "savedProbeOwner"));
                assertSecure(currentMainOnMain().activity);
            });
        }
        void assertUnchanged() {
            assertTrue("Import/export changed the encrypted saved preferences", persisted.equals(preferences.getAll()));
            assertTrue(SettingsStore.isCurrent(lease)); assertNoWork();
        }
        void assertActualIo(int opens, boolean export) {
            awaitIoClosed(); Bundle value = state();
            assertEquals(opens, value.getInt("openEntered")); assertEquals(opens, value.getInt("openReturned"));
            assertEquals(opens, value.getInt("openClosed")); assertEquals(0, value.getInt("activeOpens"));
            assertEquals(Process.myUid(), value.getInt("lastOpenCallerUid")); assertTrue(value.getBoolean("lastOpenHadGrant"));
            assertEquals(export ? 1 : 0, value.getInt("createCount"));
        }
        void awaitClientIdle() {
            await(() -> !NumericPreferencesTransfers.isWorkerBusy()
                    && ((ThreadPoolExecutor) staticField(NumericPreferencesTransfers.class, "IO_WORKER")).getActiveCount() == 0,
                    "The actual document worker did not finish its original Runnable");
        }
        void awaitIoClosed() { awaitClientIdle(); await(() -> state().getInt("activeOpens") == 0,
                "The remote provider did not observe actual descriptor closure"); }

        boolean safRootAvailable() {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return false;
            try { return isSystemSaf(root); } finally { root.recycle(); }
        }
        boolean isSystemSaf(AccessibilityNodeInfo root) {
            String name = String.valueOf(root.getPackageName());
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(name, 0);
                return (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                        && context.getPackageManager().checkPermission("android.permission.MANAGE_DOCUMENTS", name) == PackageManager.PERMISSION_GRANTED;
            } catch (PackageManager.NameNotFoundException unavailable) { return false; }
        }
        AccessibilityNodeInfo findSaf(Predicate<AccessibilityNodeInfo> predicate, long millis) {
            long deadline = SystemClock.elapsedRealtime() + millis;
            do {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root != null) try {
                    if (isSystemSaf(root)) {
                        List<AccessibilityNodeInfo> found = new ArrayList<>();
                        int[] traversal = {0, 0};
                        find(root, predicate, found, traversal, 0);
                        if (traversal[0] > 1500 || traversal[1] != 0) {
                            for (AccessibilityNodeInfo value : found) value.recycle();
                            fail("Actual SAF hierarchy exceeded the bounded selector traversal");
                        }
                        if (found.size() == 1) return found.get(0);
                        for (AccessibilityNodeInfo value : found) value.recycle();
                        assertTrue("The requested SAF control must be unique in its current window", found.isEmpty());
                    }
                } finally { root.recycle(); }
                SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtime() < deadline);
            return null;
        }
        void selectProviderRoot() {
            Predicate<AccessibilityNodeInfo> providerRoot = node -> node.isVisibleToUser() && node.isEnabled()
                    && NumericPreferencesDocumentsProvider.ROOT_TITLE.contentEquals(node.getText() == null ? "" : node.getText())
                    && hasAncestor(node, ":id/roots_list");
            AccessibilityNodeInfo root = findSaf(providerRoot, 100);
            if (root == null) {
                String description = drawerDescription();
                AccessibilityNodeInfo drawer = findSaf(node -> node.isVisibleToUser() && node.isEnabled() && node.isClickable()
                        && description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()), 5_000);
                assertNotNull("The real DocumentsUI navigation drawer control was not exposed", drawer); clickNode(drawer);
                root = findSaf(providerRoot, 5_000);
            }
            assertNotNull("The test DocumentsProvider root was not offered by real SAF", root); clickNode(root);
            AccessibilityNodeInfo input = findSaf(node -> node.isVisibleToUser()
                    && NumericPreferencesDocumentsProvider.INPUT_NAME.contentEquals(node.getText() == null ? "" : node.getText()), 5_000);
            assertNotNull("The actual provider document was not listed", input); input.recycle();
        }
        void chooseImport() {
            selectProviderRoot();
            AccessibilityNodeInfo input = findSaf(node -> node.isVisibleToUser()
                    && NumericPreferencesDocumentsProvider.INPUT_NAME.contentEquals(node.getText() == null ? "" : node.getText()), 5_000);
            assertNotNull(input); clickNode(input);
        }
        void chooseExport() {
            selectProviderRoot();
            AccessibilityNodeInfo filename = findSaf(node -> node.isVisibleToUser() && node.isEditable()
                    && "android:id/title".equals(node.getViewIdResourceName()) && hasAncestor(node, ":id/container_save"), 5_000);
            assertNotNull("The actual create-document filename editor was not offered", filename);
            try { assertEquals("numeric-preferences.json", String.valueOf(filename.getText())); }
            finally { filename.recycle(); }
            AccessibilityNodeInfo save = findSaf(node -> node.isVisibleToUser() && node.isEnabled()
                    && "android:id/button1".equals(node.getViewIdResourceName()) && hasAncestor(node, ":id/container_save"), 5_000);
            assertNotNull("The real SAF create-document Save control was not offered", save); clickNode(save);
        }
        void cancelSystemPicker() {
            assertTrue("Back must be sent to the actual system picker", safRootAvailable());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        }
        String drawerDescription() {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            assertNotNull(root);
            try {
                assertTrue(isSystemSaf(root));
                String packageName = String.valueOf(root.getPackageName());
                Resources resources = context.getPackageManager().getResourcesForApplication(packageName);
                int id = resources.getIdentifier("drawer_open", "string", packageName);
                assertTrue("This actual DocumentsUI must expose its drawer action description", id != 0);
                return resources.getString(id);
            } catch (PackageManager.NameNotFoundException missing) { throw new AssertionError("The actual SAF package disappeared", missing); }
            finally { root.recycle(); }
        }

        void main(Runnable action) {
            AtomicReference<Throwable> failed = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { failed.set(failure); } });
            Throwable failure = failed.get();
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure != null) throw new AssertionError(failure);
        }
        <T> T read(Supplier<T> action) { AtomicReference<T> result = new AtomicReference<>(); main(() -> result.set(action.get())); return result.get(); }
        void await(BooleanSupplier condition, String message) {
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            do {
                if (!closing) main(() -> assertNull("Bounded lifecycle observation failed", lifecycleFailure));
                if (condition.getAsBoolean()) return;
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            fail(message);
        }
        Host host(Activity activity) { for (Host host : hosts) if (host.activity == activity) return host; return null; }
        @Override public void onActivityPreCreated(Activity activity, Bundle saved) {
            if (activity instanceof MainActivity || activity instanceof NumericPreferencesTransferActivity) hosts.add(new Host(activity, saved != null));
        }
        @Override public void onActivityPostCreated(Activity activity, Bundle saved) {
            if (host(activity) != null) try { assertSecure(activity); }
            catch (RuntimeException | AssertionError failure) { lifecycleFailure = "created_window_not_secure"; }
        }
        @Override public void onActivityCreated(Activity activity, Bundle saved) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { Host host = host(activity); if (host != null) host.resumed = true; }
        @Override public void onActivityPaused(Activity activity) { Host host = host(activity); if (host != null) host.resumed = false; }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) { }
        @Override public void onActivityDestroyed(Activity activity) { Host host = host(activity); if (host != null) { host.destroyed = true; host.resumed = false; } }

        @Override public void close() throws Exception {
            if (closing) return;
            closing = true;
            Throwable failure = null;
            try { provider("release", null); } catch (Exception | AssertionError cleanup) { failure = cleanup; }
            try {
                // A requested recreation can still publish a replacement during cleanup.
                // Keep observing on main and finish each actual host once within the same bound.
                await(() -> read(() -> {
                    boolean done = true;
                    for (Host host : new ArrayList<>(hosts)) if (!host.destroyed) {
                        done = false;
                        if (!host.finishRequested) {
                            host.finishRequested = true;
                            if (host.activity instanceof NumericPreferencesTransferActivity) host.activity.finishActivity(1);
                            host.activity.finish();
                        }
                    }
                    return done;
                }), "Owned numeric-preference Activities did not close");
                List<Host> ended = read(() -> new ArrayList<>(hosts));
                awaitClientIdle();
                await(() -> state().getInt("activeOpens") == 0, "Remote provider descriptors did not close during cleanup");
                for (Host host : ended) if (host.activity instanceof MainActivity) {
                    ExecutorService worker = (ExecutorService) field(host.activity, "storageWorker");
                    assertTrue("Main settings worker must terminate before restoring the original encrypted preferences", worker.awaitTermination(5, TimeUnit.SECONDS));
                    ExecutorService diagnostics = (ExecutorService) field(host.activity, "diagnosticsWorker");
                    assertTrue("Main diagnostics worker must terminate before cleanup ends", diagnostics.awaitTermination(5, TimeUnit.SECONDS));
                }
                provider("reset", null);
            } catch (Exception | AssertionError cleanup) { failure = append(failure, cleanup); }
            finally {
                try { if (monitored) instrumentation.removeMonitor(monitor); }
                catch (RuntimeException | AssertionError cleanup) { failure = append(failure, cleanup); }
                try { if (registered) main(() -> application.unregisterActivityLifecycleCallbacks(this)); }
                catch (RuntimeException | AssertionError cleanup) { failure = append(failure, cleanup); }
                try {
                    if (automationConfigured) {
                        AccessibilityServiceInfo info = automation.getServiceInfo();
                        info.flags = originalAutomationFlags; automation.setServiceInfo(info);
                    }
                } catch (RuntimeException | AssertionError cleanup) { failure = append(failure, cleanup); }
                try { if (settings != null) settings.close(); } catch (Exception cleanup) { failure = append(failure, cleanup); }
                try { if (server != null) server.assertHealthy(); }
                catch (RuntimeException | AssertionError cleanup) { failure = append(failure, cleanup); }
                finally {
                    try { if (server != null) server.close(); }
                    catch (Exception | AssertionError cleanup) { failure = append(failure, cleanup); }
                }
            }
            if (lifecycleFailure != null) failure = append(failure, new AssertionError("Bounded lifecycle observation failed: " + lifecycleFailure));
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof Exception) throw (Exception) failure;
        }
    }

    private static void find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate,
                             List<AccessibilityNodeInfo> matches, int[] visited, int depth) {
        if (depth > 30) { visited[1] = 1; return; }
        if (++visited[0] > 1500 || matches.size() > 1) return;
        if (predicate.test(node)) matches.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < Math.min(node.getChildCount(), 150); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { find(child, predicate, matches, visited, depth + 1); }
            finally { child.recycle(); }
        }
    }
    private static boolean hasAncestor(AccessibilityNodeInfo child, String suffix) {
        AccessibilityNodeInfo parent = child.getParent();
        try {
            for (int i = 0; parent != null && i < 15; i++) {
                if (String.valueOf(parent.getViewIdResourceName()).endsWith(suffix)) return true;
                AccessibilityNodeInfo next = parent.getParent(); parent.recycle(); parent = next;
            }
            return false;
        } finally { if (parent != null) parent.recycle(); }
    }
    private static void clickNode(AccessibilityNodeInfo selected) {
        AccessibilityNodeInfo node = selected;
        try {
            assertTrue("The selected actual SAF node must still exist", node.refresh());
            assertTrue("The selected actual SAF node must remain visible", node.isVisibleToUser());
            for (int depth = 0; depth < 7; depth++) {
                if (node.isClickable() && node.isEnabled()) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return; }
                AccessibilityNodeInfo parent = node.getParent();
                if (parent == null) break;
                node.recycle(); node = parent;
            }
            fail("The actual SAF node had no enabled clickable ancestor");
        } finally { node.recycle(); }
    }
    private static void assertSecure(Activity activity) {
        assertTrue("Protected configuration and transfer windows must keep FLAG_SECURE",
                (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
    private static String text(Activity activity, int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
    private static Object field(Object owner, String name) {
        try { Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect the real owner field", failure); }
    }
    private static Object staticField(Class<?> type, String name) {
        try { Field value = type.getDeclaredField(name); value.setAccessible(true); return value.get(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect the actual worker", failure); }
    }
    private static void result(Activity activity, int request, int result, Intent data) {
        try {
            Method method = activity.getClass().getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
            method.setAccessible(true); method.invoke(activity, request, result, data);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Explicit callback-boundary invocation failed", failure); }
    }
    private static Throwable append(Throwable original, Throwable added) {
        if (original == null) return added; original.addSuppressed(added); return original;
    }
}
