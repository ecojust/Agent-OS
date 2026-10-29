package dev.agentos.app;

import android.accessibilityservice.AccessibilityService;
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
import java.util.concurrent.TimeoutException;
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
        String lastVerifiedSafPackage, selectedSafPackage, selectedRootsLayout = "UNKNOWN";
        String lastSafPhase = "NOT_OBSERVED", lastSafDirectory = "notSampled";
        int selectedSafWindow = -1;
        boolean backAttempted, backAccepted, createDocument;

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
            lastSafPhase = "PICKER_REQUESTED"; lastSafDirectory = "notSampled";
            backAttempted = false; backAccepted = false;
            selectedSafWindow = -1; selectedSafPackage = null; selectedRootsLayout = "UNKNOWN";
            int before = brokerCount(), count = pickerCount();
            clickMain(export ? R.id.numeric_preferences_export : R.id.numeric_preferences_import);
            await(() -> brokerCount() == before + 1 && pickerCount() == count + 1, "The real Main action did not launch one broker and one SAF picker");
            Intent picker;
            synchronized (pickers) { picker = pickers.get(count); }
            assertEquals(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
            createDocument = Intent.ACTION_CREATE_DOCUMENT.equals(picker.getAction());
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
            String[] last = {"notSampled"};
            try {
                await(() -> read(() -> {
                    Host host = currentMainOnMain();
                    boolean resumed = host != null && host.resumed, destroyed = host != null && host.destroyed;
                    boolean focused = host != null && host.activity.hasWindowFocus();
                    boolean ready = host != null && (Boolean) field(host.activity, "ready");
                    boolean busy = host != null && (Boolean) field(host.activity, "busy");
                    boolean damaged = host != null && (Boolean) field(host.activity, "storageDamaged");
                    NumericPreferencesTransfers.Owner owner = host == null ? null
                            : (NumericPreferencesTransfers.Owner) field(host.activity, "numericTransfer");
                    // Main can resume before finish's separate broker destruction callback.
                    // Observe every host already delivered to this session within this same wait.
                    int activeBrokers = 0;
                    for (Host candidate : hosts) if (candidate.activity instanceof NumericPreferencesTransferActivity
                            && !candidate.destroyed) activeBrokers++;
                    try {
                        Host broker = null;
                        for (Host candidate : hosts) if (candidate.activity instanceof NumericPreferencesTransferActivity) broker = candidate;
                        NumericPreferencesTransfers.Owner brokerOwner = broker == null ? null
                                : (NumericPreferencesTransfers.Owner) field(broker.activity, "owner");
                        last[0] = "mainDiagnosticSampled=true,mainPresent=" + (host != null) + ",resumed=" + resumed + ",destroyed=" + destroyed
                            + ",focus=" + focused + ",ready=" + ready + ",busy=" + busy + ",storageDamaged=" + damaged
                            + ",ownerPresent=" + (owner != null) + ",ownerCurrent=" + NumericPreferencesTransfers.isCurrent(owner)
                            + ",ownerReturning=" + NumericPreferencesTransfers.isReturning(owner)
                            + ",resultReceived=" + (host != null && (Boolean) field(host.activity, "numericResultReceived"))
                            + ",brokerPresent=" + (broker != null) + ",brokerResumed=" + (broker != null && broker.resumed)
                            + ",brokerDestroyed=" + (broker != null && broker.destroyed)
                            + ",activeBrokerCount=" + activeBrokers
                            + ",brokerPhase=" + (broker == null ? "NONE" : fixedValue(String.valueOf(field(broker.activity, "phase")),
                            "NEW", "PICKER", "WAITING_RESUME", "IO", "PREVIEW", "RETURNING"))
                            + ",brokerOwnerCurrent=" + NumericPreferencesTransfers.isCurrent(brokerOwner)
                                + ",brokerOwnerReturning=" + NumericPreferencesTransfers.isReturning(brokerOwner);
                    } catch (RuntimeException | AssertionError unavailable) { last[0] = "mainDiagnosticSampled=false"; }
                    return host != null && resumed && !destroyed && focused && ready && !busy && !damaged
                            && owner == null && activeBrokers == 0;
                }), "The original or replacement Main host did not become ready after the real transfer");
            } catch (AssertionError failure) {
                throw new AssertionError(failure.getMessage() + "; " + last[0] + "; lastSafPhase=" + lastSafPhase
                        + ",backAttempted=" + backAttempted + ",backAccepted=" + backAccepted
                        + "; lastPreparedSaf={" + lastSafDirectory + "}; " + safIdentityDiagnostic(), failure);
            }
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
                boolean verified = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                        && context.getPackageManager().checkPermission("android.permission.MANAGE_DOCUMENTS", name) == PackageManager.PERMISSION_GRANTED;
                if (verified) lastVerifiedSafPackage = name;
                return verified;
            } catch (PackageManager.NameNotFoundException unavailable) { return false; }
        }
        AccessibilityNodeInfo findSaf(Predicate<AccessibilityNodeInfo> predicate, long millis) {
            long deadline = SystemClock.elapsedRealtime() + millis;
            do {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root != null) try {
                    if (isSystemSaf(root)) {
                        List<AccessibilityNodeInfo> found = new ArrayList<>();
                        int[] traversal = {0, 0, 0};
                        find(root, predicate, found, traversal, 0);
                        if (traversal[0] > 1500 || traversal[1] != 0) {
                            for (AccessibilityNodeInfo value : found) value.recycle();
                            fail("Actual SAF hierarchy exceeded the bounded selector traversal");
                        }
                        if (found.size() == 1 && traversal[2] == 0) return found.get(0);
                        for (AccessibilityNodeInfo value : found) value.recycle();
                        assertTrue("The requested SAF control must be unique in its current window", found.size() < 2);
                    }
                } finally { root.recycle(); }
                SystemClock.sleep(25);
            } while (SystemClock.elapsedRealtime() < deadline);
            return null;
        }
        SafReady prepareSafAction(Predicate<AccessibilityNodeInfo> predicate, String boundary,
                                  boolean scroll, SafDiagnostic diagnostic) {
            diagnostic.operation = scroll ? "ROOTS_SCROLL" : "TARGET_CLICK";
            diagnostic.preparation = "NO_ROOT"; diagnostic.matches = -1; diagnostic.refresh = -1;
            diagnostic.window = -1; diagnostic.bounds.setEmpty();
            diagnostic.actionDepth = -1; diagnostic.actionMask = 0; diagnostic.actionBounds.setEmpty();
            diagnostic.actionClickable = false; diagnostic.actionSelected = false; diagnostic.idleObserved = false;
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return null;
            List<AccessibilityNodeInfo> found = new ArrayList<>();
            try {
                diagnostic.window = root.getWindowId();
                if (!isSystemSaf(root)) { diagnostic.preparation = "NOT_SYSTEM_SAF"; return null; }
                if (!root.refresh()) { diagnostic.preparation = "ROOT_STALE"; return null; }
                if (!isSystemSaf(root)) { diagnostic.preparation = "NOT_SYSTEM_SAF"; return null; }
                String packageName = String.valueOf(root.getPackageName());
                int window = root.getWindowId();
                int[] traversal = {0, 0, 0};
                find(root, predicate, found, traversal, 0);
                diagnostic.matches = found.size();
                if (traversal[0] > 1500 || traversal[1] != 0)
                    throw safFailure("SAF traversal exceeded its original bound", diagnostic);
                if (found.size() > 1) throw safFailure("SAF target is not unique", diagnostic);
                if (traversal[2] != 0) { diagnostic.preparation = "INCOMPLETE_TREE"; return null; }
                if (found.isEmpty()) { diagnostic.preparation = "NO_MATCH"; return null; }
                AccessibilityNodeInfo selected = found.get(0);
                diagnostic.refresh = selected.refresh() ? 1 : 0;
                if (diagnostic.refresh == 0) { diagnostic.preparation = "TARGET_STALE"; return null; }
                selected.getBoundsInScreen(diagnostic.bounds);
                if (!sameSafWindow(selected, window, packageName) || !selected.isVisibleToUser()
                        || !selected.isEnabled() || !predicate.test(selected)) {
                    diagnostic.preparation = "TARGET_CHANGED"; return null;
                }
                AccessibilityNodeInfo action = AccessibilityNodeInfo.obtain(selected);
                try {
                    for (int depth = 0; action != null && depth < (scroll ? 1 : 7); depth++) {
                        if (!action.refresh()) { diagnostic.preparation = "ANCESTOR_STALE"; return null; }
                        if (!sameSafWindow(action, window, packageName) || !action.isVisibleToUser() || !action.isEnabled()) {
                            diagnostic.preparation = "ANCESTOR_CHANGED"; return null;
                        }
                        // A root row must not turn into an action on its whole list or outside it.
                        if (!scroll && boundary != null && String.valueOf(action.getViewIdResourceName()).endsWith(boundary)) break;
                        diagnostic.actionDepth = depth;
                        diagnostic.recordAction(action);
                        // DocumentsUI's document delegate can advertise ACTION_CLICK without
                        // View.isClickable(): it routes accessibility through TouchDetector's handler.
                        if (scroll ? action.isScrollable() : advertisesClick(action)) {
                            SafReady ready = new SafReady(selected, action, window, packageName, boundary, scroll);
                            if (!ready.completeList) {
                                ready.close(); diagnostic.preparation = "LIST_INCOMPLETE"; return null;
                            }
                            diagnostic.preparation = "READY";
                            return ready;
                        }
                        AccessibilityNodeInfo parent = action.getParent(); action.recycle(); action = parent;
                    }
                    diagnostic.preparation = "NO_ACTIONABLE_ANCESTOR";
                    return null;
                } finally { if (action != null) action.recycle(); }
            } finally {
                for (AccessibilityNodeInfo value : found) value.recycle();
                root.recycle();
            }
        }
        void clickSaf(String phase, Predicate<AccessibilityNodeInfo> predicate, String boundary, boolean scrollRoots) {
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            SafDiagnostic diagnostic = new SafDiagnostic(phase);
            SafReady previous = null, previousList = null, lastScrolled = null;
            boolean scrollingStopped = false;
            try {
                do {
                    SafReady ready = prepareSafAction(predicate, boundary, false, diagnostic);
                    if (ready != null) {
                        if (previousList != null) { previousList.close(); previousList = null; }
                        boolean stable = previous != null && ready.sameGeometryAndContext(previous);
                        if (previous != null) previous.close(); previous = ready;
                        if (!stable) diagnostic.preparation = "TARGET_NOT_STABLE";
                        else if (SystemClock.elapsedRealtime() < deadline) {
                            if ("PROVIDER_ROOT".equals(phase)) {
                                long remaining = deadline - SystemClock.elapsedRealtime();
                                if (remaining <= 0) { diagnostic.preparation = "DEADLINE_EXPIRED"; break; }
                                diagnostic.idleAttempted = true;
                                try {
                                    // A quiet accessibility-event interval is only preparation;
                                    // it does not expose ListView's private adapter/layout state.
                                    automation.waitForIdle(100, remaining);
                                    diagnostic.idleObserved = true;
                                } catch (TimeoutException timedOut) {
                                    diagnostic.preparation = "IDLE_TIMEOUT";
                                    AssertionError failure = safFailure("The provider root did not reach event idle before its original deadline", diagnostic);
                                    failure.initCause(timedOut); throw failure;
                                }
                            }
                            if (SystemClock.elapsedRealtime() >= deadline) { diagnostic.preparation = "DEADLINE_EXPIRED"; break; }
                            String rootsLayout = "PROVIDER_ROOT".equals(phase) ? rootsLayout(ready.selected, diagnostic) : "NOT_APPLICABLE";
                            if ("UNKNOWN".equals(rootsLayout)) {
                                diagnostic.preparation = "UNKNOWN_ROOTS_LAYOUT";
                                throw safFailure("The actual SAF root row has no recognized drawer or rail context", diagnostic);
                            }
                            // Recheck layout and the actual active window after any idle wait,
                            // then finish all other reads before the final target/action refresh.
                            // A false dispatch is still a failure, not evidence permitting another click.
                            if (!isCurrentSafWindow(ready)) {
                                diagnostic.preparation = "CURRENT_WINDOW_CHANGED"; previous.close(); previous = null;
                            } else {
                                if (SystemClock.elapsedRealtime() >= deadline) { diagnostic.preparation = "DEADLINE_EXPIRED"; break; }
                                if (!ready.revalidate(predicate, diagnostic)) { previous.close(); previous = null; }
                                else {
                                    if (SystemClock.elapsedRealtime() >= deadline) { diagnostic.preparation = "DEADLINE_EXPIRED"; break; }
                                    diagnostic.actionAttempted = true;
                                    diagnostic.actionResult = ready.action.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    if (!diagnostic.actionResult) throw safFailure("The single SAF click returned false", diagnostic);
                                    lastSafPhase = phase + "_CLICK_ACCEPTED";
                                    if ("PROVIDER_ROOT".equals(phase)) {
                                        selectedRootsLayout = rootsLayout; selectedSafWindow = ready.window; selectedSafPackage = ready.packageName;
                                    }
                                    return;
                                }
                            }
                        }
                    } else {
                        if (previous != null) { previous.close(); previous = null; }
                        if (scrollRoots && "NO_MATCH".equals(diagnostic.preparation) && !scrollingStopped && diagnostic.scrolls < 4) {
                            SafReady list = prepareSafAction(Session::isRootsList, null, true, diagnostic);
                            if (list != null) {
                                // A successful scroll must produce observed list progress before another is allowed.
                                // The desired row is always searched first, whether or not a scroll event was delivered.
                                boolean sameScrollContext = lastScrolled == null || list.sameGeometryAndContext(lastScrolled);
                                boolean progressed = sameScrollContext && (lastScrolled == null || !list.sameListContents(lastScrolled));
                                boolean stable = previousList != null && list.sameGeometryAndContext(previousList)
                                        && list.sameListContents(previousList);
                                if (previousList != null) previousList.close(); previousList = list;
                                if (!sameScrollContext) diagnostic.preparation = "SCROLL_CONTEXT_CHANGED";
                                else if (!progressed) diagnostic.preparation = "SCROLL_PROGRESS_PENDING";
                                else if (!stable) diagnostic.preparation = "LIST_NOT_STABLE";
                                else if (!list.revalidate(Session::isRootsList, diagnostic)) { previousList.close(); previousList = null; }
                                else if (!isCurrentSafWindow(list)) {
                                    diagnostic.preparation = "CURRENT_WINDOW_CHANGED"; previousList.close(); previousList = null;
                                } else if (SystemClock.elapsedRealtime() < deadline) {
                                    diagnostic.scrolls++;
                                    diagnostic.scrollResult = list.action.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                                    scrollingStopped = !diagnostic.scrollResult;
                                    if (lastScrolled != null) lastScrolled.close();
                                    lastScrolled = list; previousList = null;
                                }
                            } else if (previousList != null) { previousList.close(); previousList = null; }
                        } else if (previousList != null) { previousList.close(); previousList = null; }
                    }
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining > 0) SystemClock.sleep(Math.min(25, remaining));
                } while (SystemClock.elapsedRealtime() < deadline);
                throw safFailure("The actual SAF target was not ready within its original deadline", diagnostic);
            } finally {
                if (previous != null) previous.close();
                if (previousList != null) previousList.close();
                if (lastScrolled != null) lastScrolled.close();
            }
        }
        static boolean isRootsList(AccessibilityNodeInfo node) {
            return node.isVisibleToUser() && node.isEnabled()
                    && String.valueOf(node.getViewIdResourceName()).endsWith(":id/roots_list");
        }
        boolean isCurrentSafWindow(SafReady ready) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return false;
            try { return root.refresh() && isSystemSaf(root) && sameSafWindow(root, ready.window, ready.packageName); }
            finally { root.recycle(); }
        }
        AssertionError safFailure(String fixedMessage, SafDiagnostic diagnostic) {
            String providerDiagnostic;
            try {
                // Existing control IPC, with bounded output only; this does not claim a hard Binder deadline.
                Bundle state = state();
                providerDiagnostic = ",rootsEntered=" + state.getInt("rootsQueryEntered", -1)
                        + ",rootsReturned=" + state.getInt("rootsQueryReturned", -1)
                        + ",rootsOutcome=" + fixedValue(state.getString("rootsQueryLastOutcome"),
                        "NOT_QUERIED", "IN_PROGRESS", "RETURNED", "REJECTED", "ERROR")
                        + ",rootsCaller=" + fixedValue(state.getString("rootsQueryLastCallerCheck"),
                        "NOT_CHECKED", "TARGET_ALLOWED", "SPECIAL_UID_REJECTED", "MANAGE_PERMISSION_REJECTED",
                        "NO_VISIBLE_PACKAGES", "NO_SYSTEM_PACKAGE", "SYSTEM_ALLOWED", "LOOKUP_ERROR")
                        + ",rootsPackages=" + state.getInt("rootsQueryLastPackageCount", -1)
                        + ",rootsApplications=" + state.getInt("rootsQueryLastApplicationInfoCount", -1)
                        + ",rootsLookupMisses=" + state.getInt("rootsQueryLastLookupMissCount", -1)
                        + ",rootsCountersSaturated=" + state.getBoolean("rootsQueryCountersSaturated")
                        + ",providerDiagnosticSampled=true";
            } catch (RuntimeException | AssertionError unavailable) {
                providerDiagnostic = ",providerDiagnosticSampled=false";
            }
            return new AssertionError(fixedMessage + "; " + diagnostic + providerDiagnostic
                    + "; lastPreparedSaf={" + lastSafDirectory + "}; " + safIdentityDiagnostic());
        }
        String safIdentityDiagnostic() {
            try { return NumericPreferencesSafDiagnostics.sample(context, lastVerifiedSafPackage, automation); }
            catch (RuntimeException | AssertionError unavailable) { return "safIdentityDiagnosticSampled=false"; }
        }
        SafDirectory readProviderDirectory() {
            SafDirectory page = new SafDirectory(createDocument);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return page;
            List<AccessibilityNodeInfo> found = new ArrayList<>();
            try {
                if (!root.refresh() || !isSystemSaf(root)) return page;
                page.window = root.getWindowId();
                page.systemSaf = true; page.packageName = String.valueOf(root.getPackageName());
                page.sameSelection = sameSafWindow(root, selectedSafWindow, selectedSafPackage);
                root.getBoundsInScreen(page.rootBounds);
                int[] traversal = {0, 0, 0};
                find(root, node -> NumericPreferencesDocumentsProvider.INPUT_NAME.contentEquals(node.getText() == null ? "" : node.getText())
                        || idEndsWith(node, ":id/roots_list") || idEndsWith(node, ":id/drawer_roots")
                        || idEndsWith(node, ":id/nav_rail_container_roots")
                        || page.createDocument && "android:id/title".equals(node.getViewIdResourceName()), found, traversal, 0, false);
                page.limitExceeded = traversal[0] > 1500 || traversal[1] != 0;
                page.complete = !page.limitExceeded && traversal[2] == 0;
                for (AccessibilityNodeInfo node : found) {
                    if (!node.refresh() || !sameSafWindow(node, page.window, page.packageName)) { page.complete = false; continue; }
                    if (!node.isVisibleToUser()) continue;
                    if (idEndsWith(node, ":id/roots_list")) {
                        String layout = rootsLayout(node);
                        page.drawerVisible |= "DRAWER".equals(layout);
                        page.railVisible |= "RAIL".equals(layout);
                        page.unknownLayout |= "UNKNOWN".equals(layout);
                    } else if (idEndsWith(node, ":id/drawer_roots")) {
                        page.drawerVisible = true;
                        page.unknownLayout |= !hasAncestor(node, ":id/drawer_layout");
                    } else if (idEndsWith(node, ":id/nav_rail_container_roots")) page.railVisible = true;
                    else if (NumericPreferencesDocumentsProvider.INPUT_NAME.contentEquals(node.getText() == null ? "" : node.getText())) {
                        page.inputs++;
                        if (page.input == null) {
                            page.input = AccessibilityNodeInfo.obtain(node); node.getBoundsInScreen(page.inputBounds);
                            page.inputEnabled = node.isEnabled();
                        }
                    } else if (page.createDocument && "android:id/title".equals(node.getViewIdResourceName())
                            && hasAncestor(node, ":id/container_save")) {
                        page.filenames++;
                        if (page.filename == null) {
                            page.filename = AccessibilityNodeInfo.obtain(node); node.getBoundsInScreen(page.filenameBounds);
                            page.filenameEnabled = node.isEnabled(); page.filenameEditable = node.isEditable();
                            page.filenameMatches = "numeric-preferences.json".contentEquals(node.getText() == null ? "" : node.getText());
                        }
                    }
                }
                return page;
            } catch (RuntimeException | Error failure) { page.close(); throw failure; }
            finally { for (AccessibilityNodeInfo node : found) node.recycle(); root.recycle(); }
        }
        void awaitProviderDirectory() {
            // Reuse the original document-listing budget. These are observable preparation
            // conditions, not a claim to read DrawerLayout's private animation completion bit.
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            SafDirectory previous = null;
            SafDiagnostic diagnostic = new SafDiagnostic("DIRECTORY_READY");
            try {
                do {
                    SafDirectory page = readProviderDirectory();
                    lastSafDirectory = "selectedLayout=" + selectedRootsLayout + "," + page.summary();
                    diagnostic.window = page.window; diagnostic.matches = page.inputs; diagnostic.bounds.set(page.inputBounds);
                    diagnostic.preparation = page.preparation();
                    if (page.limitExceeded || page.inputs > 1 || page.filenames > 1) {
                        page.close(); throw safFailure("The actual provider directory was not complete and unique", diagnostic);
                    }
                    boolean stable = page.ready() && previous != null && previous.ready() && page.sameAs(previous);
                    if (previous != null) previous.close(); previous = page;
                    if (stable && SystemClock.elapsedRealtime() < deadline) { lastSafPhase = "DIRECTORY_READY"; return; }
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining > 0) SystemClock.sleep(Math.min(25, remaining));
                } while (SystemClock.elapsedRealtime() < deadline);
                throw safFailure("The actual provider directory and drawer did not reach the original preparation condition", diagnostic);
            } finally { if (previous != null) previous.close(); }
        }
        void selectProviderRoot() {
            Predicate<AccessibilityNodeInfo> providerRoot = node -> node.isVisibleToUser() && node.isEnabled()
                    && NumericPreferencesDocumentsProvider.ROOT_TITLE.contentEquals(node.getText() == null ? "" : node.getText())
                    && hasAncestor(node, ":id/roots_list");
            AccessibilityNodeInfo root = findSaf(providerRoot, 100);
            if (root == null) {
                String description = drawerDescription();
                clickSaf("DRAWER", node -> node.isVisibleToUser() && node.isEnabled() && node.isClickable()
                        && description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()), null, false);
            } else root.recycle();
            clickSaf("PROVIDER_ROOT", providerRoot, ":id/roots_list", true);
            awaitProviderDirectory();
        }
        void chooseImport() {
            selectProviderRoot();
            clickSaf("IMPORT_DOCUMENT", node -> node.isVisibleToUser()
                    && NumericPreferencesDocumentsProvider.INPUT_NAME.contentEquals(node.getText() == null ? "" : node.getText())
                    && hasAncestor(node, ":id/dir_list"), ":id/dir_list", false);
        }
        void chooseExport() {
            selectProviderRoot();
            AccessibilityNodeInfo filename = findSaf(node -> node.isVisibleToUser() && node.isEditable()
                    && "android:id/title".equals(node.getViewIdResourceName()) && hasAncestor(node, ":id/container_save"), 5_000);
            assertNotNull("The actual create-document filename editor was not offered", filename);
            try { assertEquals("numeric-preferences.json", String.valueOf(filename.getText())); }
            finally { filename.recycle(); }
            clickSaf("EXPORT_SAVE", node -> node.isVisibleToUser() && node.isEnabled()
                    && "android:id/button1".equals(node.getViewIdResourceName()) && hasAncestor(node, ":id/container_save"), ":id/container_save", false);
        }
        void cancelSystemPicker() {
            assertTrue("Back must be sent to the actual system picker", safRootAvailable());
            backAttempted = true; lastSafPhase = "BACK_ATTEMPTED";
            backAccepted = automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            assertTrue("The single system SAF Back action must be accepted", backAccepted);
            lastSafPhase = "BACK_ACCEPTED";
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

    private static boolean sameSafWindow(AccessibilityNodeInfo node, int window, String packageName) {
        return packageName != null && node.getWindowId() == window
                && packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName());
    }
    private static String fixedValue(String actual, String... allowed) {
        for (String value : allowed) if (value.equals(actual)) return value;
        return "UNAVAILABLE";
    }
    private static boolean idEndsWith(AccessibilityNodeInfo node, String suffix) {
        return String.valueOf(node.getViewIdResourceName()).endsWith(suffix);
    }
    private static String rootsLayout(AccessibilityNodeInfo selected) {
        return rootsLayout(selected, null);
    }
    private static String rootsLayout(AccessibilityNodeInfo selected, SafDiagnostic diagnostic) {
        if (diagnostic != null) {
            diagnostic.rootsLayoutDepth = -1; diagnostic.rootsLayoutVisited = 0; diagnostic.rootsLayoutMask = 0;
            diagnostic.rootsLayoutExit = "NOT_SAMPLED"; diagnostic.rootsLayoutResult = "UNKNOWN";
        }
        int window = selected.getWindowId();
        String packageName = String.valueOf(selected.getPackageName());
        boolean drawer = false, drawerLayout = false, rail = false;
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(selected);
        try {
            for (int depth = 0; node != null && depth < 15; depth++) {
                if (diagnostic != null) diagnostic.rootsLayoutDepth = depth;
                if (!node.refresh()) {
                    if (diagnostic != null) diagnostic.rootsLayoutExit = "REFRESH_FALSE";
                    return "UNKNOWN";
                }
                if (!sameSafWindow(node, window, packageName)) {
                    if (diagnostic != null) diagnostic.rootsLayoutExit = "CONTEXT_CHANGED";
                    return "UNKNOWN";
                }
                if (diagnostic != null) diagnostic.rootsLayoutVisited++;
                drawer |= idEndsWith(node, ":id/drawer_roots");
                drawerLayout |= idEndsWith(node, ":id/drawer_layout");
                rail |= idEndsWith(node, ":id/nav_rail_container_roots");
                // Fixed bits from the existing checks only: drawer=1, drawer layout=2, rail=4.
                if (diagnostic != null) diagnostic.rootsLayoutMask = (drawer ? 1 : 0) | (drawerLayout ? 2 : 0) | (rail ? 4 : 0);
                AccessibilityNodeInfo parent = node.getParent(); node.recycle(); node = parent;
            }
            // A null parent is only this traversal's result, not proof of a complete hierarchy.
            if (diagnostic != null) diagnostic.rootsLayoutExit = node == null ? "PARENT_NULL" : "DEPTH_LIMIT";
            // container_roots alone is also used by fixed sidebars and proves no drawer.
            if (rail && !drawer) {
                if (diagnostic != null) diagnostic.rootsLayoutResult = "RAIL";
                return "RAIL";
            }
            String result = drawer && drawerLayout && !rail ? "DRAWER" : "UNKNOWN";
            if (diagnostic != null) diagnostic.rootsLayoutResult = result;
            return result;
        } finally { if (node != null) node.recycle(); }
    }
    private static final class SafDirectory implements AutoCloseable {
        final boolean createDocument;
        AccessibilityNodeInfo input, filename;
        String packageName = "";
        int window = -1, inputs, filenames;
        boolean systemSaf, sameSelection, complete, limitExceeded, inputEnabled, drawerVisible, railVisible, unknownLayout;
        boolean filenameEnabled, filenameEditable, filenameMatches;
        final Rect inputBounds = new Rect(), rootBounds = new Rect(), filenameBounds = new Rect();
        SafDirectory(boolean createDocument) { this.createDocument = createDocument; }
        boolean ready() {
            // CREATE lists the read-only input as a directory sentinel, not an overwrite target.
            // Its actionable control is the real filename editor; Save keeps its own final checks.
            return systemSaf && sameSelection && complete && inputs == 1 && input != null
                    && !inputBounds.isEmpty() && !drawerVisible && !unknownLayout
                    && (createDocument ? filenames == 1 && filename != null && filenameEnabled && filenameEditable
                    && filenameMatches && !filenameBounds.isEmpty() : inputEnabled);
        }
        boolean sameAs(SafDirectory other) {
            return createDocument == other.createDocument && window == other.window
                    && packageName.equals(other.packageName) && input.equals(other.input) && inputEnabled == other.inputEnabled
                    && inputBounds.equals(other.inputBounds) && rootBounds.equals(other.rootBounds)
                    && railVisible == other.railVisible
                    && (!createDocument || filename.equals(other.filename) && filenameBounds.equals(other.filenameBounds));
        }
        String preparation() {
            if (!systemSaf || !sameSelection) return "DIRECTORY_WINDOW_CHANGED";
            if (!complete) return "INCOMPLETE_TREE";
            if (unknownLayout) return "UNKNOWN_ROOTS_LAYOUT";
            if (drawerVisible) return "DRAWER_VISIBLE";
            if (inputs != 1 || input == null || inputBounds.isEmpty()) return "INPUT_SENTINEL_NOT_READY";
            if (!createDocument && !inputEnabled) return "IMPORT_INPUT_DISABLED";
            if (createDocument && (filenames != 1 || filename == null || filenameBounds.isEmpty())) return "CREATE_FILENAME_NOT_READY";
            if (createDocument && !filenameEnabled) return "CREATE_FILENAME_DISABLED";
            if (createDocument && !filenameEditable) return "CREATE_FILENAME_NOT_EDITABLE";
            if (createDocument && !filenameMatches) return "CREATE_FILENAME_MISMATCH";
            return ready() ? "DIRECTORY_SAMPLE_READY" : "DIRECTORY_NOT_READY";
        }
        String summary() {
            return "mode=" + (createDocument ? "CREATE" : "OPEN") + ",window=" + window
                    + ",systemSaf=" + systemSaf + ",sameSelection=" + sameSelection + ",complete=" + complete
                    + ",inputs=" + inputs + ",inputEnabled=" + inputEnabled + ",drawerVisible=" + drawerVisible
                    + ",railVisible=" + railVisible + ",unknownLayout=" + unknownLayout + ",bounds=" + inputBounds.toShortString()
                    + ",filenames=" + filenames + ",filenameEnabled=" + filenameEnabled + ",filenameEditable=" + filenameEditable
                    + ",filenameMatches=" + filenameMatches + ",filenameBounds=" + filenameBounds.toShortString();
        }
        @Override public void close() {
            if (input != null) { input.recycle(); input = null; }
            if (filename != null) { filename.recycle(); filename = null; }
        }
    }
    private static final class SafDiagnostic {
        final String phase;
        final Rect bounds = new Rect(), actionBounds = new Rect();
        String operation = "TARGET_CLICK", preparation = "NOT_OBSERVED";
        int matches = -1, window = -1, refresh = -1, scrolls, actionDepth = -1, actionMask;
        String rootsLayoutExit = "NOT_SAMPLED", rootsLayoutResult = "UNKNOWN";
        int rootsLayoutDepth = -1, rootsLayoutVisited, rootsLayoutMask;
        boolean actionAttempted, actionResult, scrollResult, actionClickable, actionSelected, idleAttempted, idleObserved;
        SafDiagnostic(String phase) { this.phase = phase; }
        void recordAction(AccessibilityNodeInfo node) {
            actionMask = fixedActionMask(node); actionClickable = node.isClickable(); actionSelected = node.isSelected();
            node.getBoundsInScreen(actionBounds);
        }
        @Override public String toString() {
            return "phase=" + phase + ",operation=" + operation + ",preparation=" + preparation + ",matches=" + matches + ",window=" + window
                    + ",bounds=" + bounds.toShortString() + ",refresh=" + refresh + ",actionAttempted=" + actionAttempted
                    + ",actionResult=" + actionResult + ",scrolls=" + scrolls + ",scrollResult=" + scrollResult
                    + ",actionDepth=" + actionDepth + ",actionMask=" + actionMask + ",actionClickable=" + actionClickable
                    + ",actionSelected=" + actionSelected + ",actionBounds=" + actionBounds.toShortString()
                    + ",idleAttempted=" + idleAttempted + ",idleObserved=" + idleObserved
                    + ",rootsLayoutDepth=" + rootsLayoutDepth + ",rootsLayoutVisited=" + rootsLayoutVisited
                    + ",rootsLayoutMask=" + rootsLayoutMask + ",rootsLayoutExit=" + rootsLayoutExit
                    + ",rootsLayoutResult=" + rootsLayoutResult;
        }
    }
    private static boolean advertisesClick(AccessibilityNodeInfo node) {
        return node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }
    private static int fixedActionMask(AccessibilityNodeInfo node) {
        // Fixed capability bits only: click=1, select=2, clear-selection=4, scroll-forward=8.
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        return (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) ? 1 : 0)
                | (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SELECT) ? 2 : 0)
                | (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_SELECTION) ? 4 : 0)
                | (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD) ? 8 : 0);
    }
    private static final class SafReady implements AutoCloseable {
        final AccessibilityNodeInfo selected, action, firstChild, lastChild;
        final int window, childCount, actionMask;
        final String packageName, boundary;
        final boolean scroll, completeList, actionClickable, actionSelected;
        final Rect bounds = new Rect(), actionBounds = new Rect(), firstBounds = new Rect(), lastBounds = new Rect();
        SafReady(AccessibilityNodeInfo selected, AccessibilityNodeInfo action, int window, String packageName, String boundary, boolean scroll) {
            this.selected = AccessibilityNodeInfo.obtain(selected); this.action = AccessibilityNodeInfo.obtain(action);
            this.window = window; this.packageName = packageName; this.boundary = boundary; this.scroll = scroll;
            actionMask = fixedActionMask(action); actionClickable = action.isClickable(); actionSelected = action.isSelected();
            selected.getBoundsInScreen(bounds); action.getBoundsInScreen(actionBounds);
            childCount = scroll ? action.getChildCount() : 0;
            boolean boundedChildren = childCount > 0 && childCount <= 150;
            firstChild = boundedChildren ? action.getChild(0) : null;
            lastChild = boundedChildren ? action.getChild(childCount - 1) : null;
            completeList = !scroll || boundedChildren && snapshotListEndpoint(firstChild, firstBounds)
                    && snapshotListEndpoint(lastChild, lastBounds);
        }
        boolean snapshotListEndpoint(AccessibilityNodeInfo node, Rect snapshot) {
            if (node == null || !node.refresh() || !sameSafWindow(node, window, packageName)) return false;
            node.getBoundsInScreen(snapshot);
            return !snapshot.isEmpty();
        }
        boolean sameGeometryAndContext(SafReady other) {
            return window == other.window && packageName.equals(other.packageName)
                    && selected.equals(other.selected) && action.equals(other.action)
                    && bounds.equals(other.bounds) && actionBounds.equals(other.actionBounds)
                    && actionMask == other.actionMask && actionClickable == other.actionClickable && actionSelected == other.actionSelected;
        }
        boolean sameListContents(SafReady other) {
            return completeList && other.completeList && childCount == other.childCount
                    && sameNode(firstChild, other.firstChild) && sameNode(lastChild, other.lastChild)
                    && firstBounds.equals(other.firstBounds) && lastBounds.equals(other.lastBounds);
        }
        boolean revalidate(Predicate<AccessibilityNodeInfo> predicate, SafDiagnostic diagnostic) {
            diagnostic.refresh = selected.refresh() ? 1 : 0;
            if (diagnostic.refresh == 0) { diagnostic.preparation = "TARGET_STALE"; return false; }
            selected.getBoundsInScreen(diagnostic.bounds);
            if (!sameSafWindow(selected, window, packageName) || !selected.isVisibleToUser() || !selected.isEnabled()
                    || !bounds.equals(diagnostic.bounds) || !predicate.test(selected)) {
                diagnostic.preparation = "TARGET_CHANGED"; return false;
            }
            AccessibilityNodeInfo ancestor = AccessibilityNodeInfo.obtain(selected);
            boolean sameAncestry = false;
            try {
                for (int depth = 0; ancestor != null && depth < 7; depth++) {
                    if (!ancestor.refresh() || !sameSafWindow(ancestor, window, packageName)) break;
                    if (boundary != null && String.valueOf(ancestor.getViewIdResourceName()).endsWith(boundary)) break;
                    if (ancestor.equals(action)) { sameAncestry = true; break; }
                    AccessibilityNodeInfo parent = ancestor.getParent(); ancestor.recycle(); ancestor = parent;
                }
            } finally { if (ancestor != null) ancestor.recycle(); }
            if (!sameAncestry) { diagnostic.preparation = "ANCESTRY_CHANGED"; return false; }
            if (scroll) {
                // Endpoint/count comparison must use a fresh list snapshot, as before.
                if (!action.refresh()) { diagnostic.preparation = "ANCESTOR_STALE"; return false; }
                try (SafReady current = new SafReady(selected, action, window, packageName, boundary, true)) {
                    if (!current.completeList) { diagnostic.preparation = "LIST_INCOMPLETE"; return false; }
                    if (!sameListContents(current)) { diagnostic.preparation = "LIST_MOVING"; return false; }
                }
            }
            // Keep the dispatch node's refresh last: remaining checks read only this frozen node.
            if (!action.refresh()) { diagnostic.preparation = "ANCESTOR_STALE"; return false; }
            diagnostic.recordAction(action);
            if (!sameSafWindow(action, window, packageName) || !action.isVisibleToUser() || !action.isEnabled()
                    || !actionBounds.equals(diagnostic.actionBounds) || !(scroll ? action.isScrollable() : advertisesClick(action))
                    || actionMask != diagnostic.actionMask || actionClickable != diagnostic.actionClickable
                    || actionSelected != diagnostic.actionSelected) {
                diagnostic.preparation = "ANCESTOR_CHANGED"; return false;
            }
            diagnostic.preparation = "READY"; return true;
        }
        static boolean sameNode(AccessibilityNodeInfo first, AccessibilityNodeInfo second) {
            return first == null ? second == null : first.equals(second);
        }
        @Override public void close() {
            selected.recycle(); action.recycle();
            if (firstChild != null) firstChild.recycle();
            if (lastChild != null) lastChild.recycle();
        }
    }
    private static void find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate,
                             List<AccessibilityNodeInfo> matches, int[] visited, int depth) {
        find(node, predicate, matches, visited, depth, true);
    }
    private static void find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate,
                             List<AccessibilityNodeInfo> matches, int[] visited, int depth, boolean stopAfterAmbiguity) {
        if (depth > 30) { visited[1] = 1; return; }
        if (++visited[0] > 1500 || stopAfterAmbiguity && matches.size() > 1) return;
        if (predicate.test(node)) matches.add(AccessibilityNodeInfo.obtain(node));
        int childCount = node.getChildCount();
        if (childCount > 150) { visited[1] = 1; return; }
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) { visited[2]++; continue; }
            try { find(child, predicate, matches, visited, depth + 1, stopAfterAmbiguity); }
            finally { child.recycle(); }
        }
    }
    private static boolean hasAncestor(AccessibilityNodeInfo child, String suffix) {
        int window = child.getWindowId();
        String packageName = String.valueOf(child.getPackageName());
        AccessibilityNodeInfo parent = child.getParent();
        try {
            for (int i = 0; parent != null && i < 15; i++) {
                if (!parent.refresh() || !sameSafWindow(parent, window, packageName)) return false;
                if (String.valueOf(parent.getViewIdResourceName()).endsWith(suffix)) return true;
                AccessibilityNodeInfo next = parent.getParent(); parent.recycle(); parent = next;
            }
            return false;
        } finally { if (parent != null) parent.recycle(); }
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
