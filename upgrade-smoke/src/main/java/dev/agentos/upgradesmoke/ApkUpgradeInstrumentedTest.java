package dev.agentos.upgradesmoke;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.agentos.app.AgentAccessibilityService;
import dev.agentos.app.AgentController;
import dev.agentos.app.MainActivity;
import dev.agentos.app.SettingsStore;
import dev.agentos.core.ActionConfirmationPolicy;
import dev.agentos.core.ModelSettings;
import dev.agentos.core.RunControl;
import dev.agentos.core.TaskActionReceipts;
import dev.agentos.core.TaskBudget;
import dev.agentos.core.TaskResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

/** Two separately invoked processes around one real package replacement; never a schema migration. */
@RunWith(AndroidJUnit4.class)
public final class ApkUpgradeInstrumentedTest {
    private static final String TARGET = "dev.agentos.app", FIXTURE = "dev.agentos.fixture";
    private static final String BASELINE = "b1fd337262775a505327088975a3320fe323f034";
    private static final String KEY = "synthetic-upgrade-key", MODEL = "synthetic-upgrade-model";
    private static final int OUTPUT = 3072, ROUNDS = 17, TOOLS = 43;
    private static final long ACTIVE_MILLIS = 180_000;
    private static final long READY_MILLIS = 10_000;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context context;
    private UiAutomation automation;
    private Arguments arguments;
    private AgentController controller;
    private MainActivity activity;
    private ExecutorService storageWorker, diagnosticsWorker;
    private Stage stage = Stage.ARGUMENTS;

    private enum Stage { ARGUMENTS, DEVICE, EMPTY_BASELINE, SAVE, LOAD, MAIN_READY,
        FIXTURE_READY, START, APPROVAL, STOP, DRAIN, VERIFY_FRESH, CLEANUP, RECEIPT }

    @Test public void seedBaseline() throws Exception { runPhase(true); }
    @Test public void verifyCandidate() throws Exception { runPhase(false); }

    private void runPhase(boolean seed) throws Exception {
        JSONObject receipt = null;
        Throwable primary = null;
        try {
            arguments = new Arguments(InstrumentationRegistry.getArguments(), seed);
            context = instrumentation.getTargetContext();
            automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            stage = Stage.DEVICE;
            requireDedicatedDevice(seed ? 1 : 2);
            controller = AgentController.get(context);
            assertFreshController();
            SettingsStore store = new SettingsStore(context);
            SharedPreferences preferences = preferences();
            SettingsStore.LoadedSettings loaded;
            if (seed) {
                stage = Stage.EMPTY_BASELINE;
                check(preferences.getAll().isEmpty(), "BASELINE_CONFIGURATION_NOT_EMPTY");
                loaded = store.loadWithLease();
                check(loaded.lease == null, "BASELINE_LEASE_NOT_EMPTY");
                SettingsStore.SavedSettings saved = loaded.settings;
                saved.endpoint = arguments.endpoint();
                saved.apiKey = KEY;
                saved.model = MODEL;
                saved.maxOutputTokens = OUTPUT;
                saved.taskBudget = new TaskBudget(ROUNDS, TOOLS, ACTIVE_MILLIS);
                saved.confirmEachAction = true;
                saved.executionMode = ActionConfirmationPolicy.Mode.CONFIRM_ALL;
                saved.allowedPackages.add(FIXTURE);
                saved.grants.put(FIXTURE, SettingsStore.captureGrant(context, FIXTURE));
                stage = Stage.SAVE;
                SettingsStore.ConfigurationLease issued = store.save(saved);
                loaded = store.loadWithLease();
                check(issued == loaded.lease && SettingsStore.isCurrent(issued), "SAVED_LEASE_NOT_CURRENT");
            } else {
                // No save/reset or recaptureGrant may precede or follow this read.
                stage = Stage.LOAD;
                loaded = store.loadWithLease();
            }
            assertSettings(store, loaded);
            StoredIdentity identity = new StoredIdentity(preferences, loaded.settings);
            if (!seed) identity.matchesArguments(arguments);
            openAndVerifyMain();
            assertFreshController();
            closeMain();
            if (seed) runAndStopOneTask(loaded);
            else {
                stage = Stage.VERIFY_FRESH;
                awaitWorkerDrained();
                assertFreshController();
            }
            SettingsStore.LoadedSettings finalRead = store.loadWithLease();
            assertSettings(store, finalRead);
            identity.matches(new StoredIdentity(preferences, finalRead.settings));
            receipt = commonReceipt(seed, identity);
            if (seed) receipt.put("approvalObserved", true).put("approvalGranted", false)
                    .put("taskStopped", true).put("stoppedUsageRetained", true)
                    .put("dispatchAttempts", 0).put("workerDrained", true);
            else receipt.put("freshController", true).put("noTaskOrApproval", true)
                    .put("noTaskHistory", true).put("noTaskInput", true).put("workerDrained", true);
        } catch (Exception | AssertionError failure) {
            // SDK/provider failures can contain arbitrary strings. Preserve the
            // failure category and fixed stage, without exporting their message/cause.
            primary = safeFailure(failure, stage);
        } finally {
            stage = Stage.CLEANUP;
            try {
                if (controller != null && controller.getState().running)
                    onMain(controller::stop);
                if (controller != null) awaitWorkerDrained();
            } catch (Exception | AssertionError cleanup) {
                primary = append(primary, safeFailure(cleanup, Stage.CLEANUP));
            }
            try { closeMain(); }
            catch (Exception | AssertionError cleanup) {
                primary = append(primary, safeFailure(cleanup, Stage.CLEANUP));
            }
            // The host restores device settings and removes its adb reverse after BOTH
            // phases, retaining installed packages. Never clear settings, keys or task state here.
        }
        if (primary instanceof AssertionError) throw (AssertionError) primary;
        if (primary != null) throw (Exception) primary;
        stage = Stage.RECEIPT;
        check(receipt != null, "RECEIPT_MISSING");
        byte[] bytes = receipt.toString().getBytes(StandardCharsets.UTF_8);
        check(bytes.length <= 4096, "RECEIPT_LIMIT");
        Bundle output = new Bundle();
        output.putString("upgradeReceipt", new String(bytes, StandardCharsets.UTF_8));
        instrumentation.sendStatus(0, output);
    }

    private void requireDedicatedDevice(long version) throws Exception {
        check(TARGET.equals(context.getPackageName()), "TARGET_MISMATCH");
        check(Build.VERSION.SDK_INT == arguments.api, "API_MISMATCH");
        check(Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64"), "EMULATOR_ABI_REQUIRED");
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand("getprop ro.kernel.qemu");
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != -1) {
                check(bytes.size() < 32, "EMULATOR_PROPERTY_LIMIT");
                bytes.write(next);
            }
            check("1".equals(bytes.toString(StandardCharsets.UTF_8.name()).trim()), "EMULATOR_REQUIRED");
        }
        SettingsStore.requirePersonalProfile(context);
        check("user:0".equals(SettingsStore.currentProfileId(context)), "OWNER_PROFILE_REQUIRED");
        PackageInfo installed = context.getPackageManager().getPackageInfo(TARGET, 0);
        check(installed.getLongVersionCode() == version, "TARGET_VERSION_MISMATCH");
        check(installed.applicationInfo != null && installed.applicationInfo.uid == Process.myUid(), "TARGET_UID_MISMATCH");
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("agent_settings_secure_"
                + SettingsStore.currentProfileId(context).replace(':', '_'), Context.MODE_PRIVATE);
    }

    private void assertSettings(SettingsStore store, SettingsStore.LoadedSettings loaded) throws Exception {
        SettingsStore.SavedSettings saved = loaded.settings;
        check(loaded.lease != null && SettingsStore.isCurrent(loaded.lease), "LOAD_LEASE_INVALID");
        check(saved.profileId.equals(SettingsStore.currentProfileId(context)), "PROFILE_CHANGED");
        check(arguments.endpoint().equals(saved.endpoint) && KEY.equals(saved.apiKey)
                && MODEL.equals(saved.model), "MODEL_SETTINGS_CHANGED");
        check(saved.maxOutputTokens == OUTPUT && saved.taskBudget.maxModelRounds == ROUNDS
                && saved.taskBudget.maxToolCalls == TOOLS && saved.taskBudget.maxActiveMillis == ACTIVE_MILLIS,
                "NUMERIC_SETTINGS_CHANGED");
        check(saved.confirmEachAction && saved.executionMode == ActionConfirmationPolicy.Mode.CONFIRM_ALL,
                "EXECUTION_MODE_CHANGED");
        check(saved.allowedPackages.equals(Collections.singleton(FIXTURE))
                && saved.grants.keySet().equals(Collections.singleton(FIXTURE)), "GRANT_SET_CHANGED");
        check(SettingsStore.isGrantValid(context, saved.grants.get(FIXTURE))
                && store.validateAllowedPackages(saved).equals(Collections.singleton(FIXTURE)), "ORIGINAL_GRANT_INVALID");
    }

    private void openAndVerifyMain() throws Exception {
        stage = Stage.MAIN_READY;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent()
                .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        storageWorker = (ExecutorService) readField(activity, "storageWorker");
        diagnosticsWorker = (ExecutorService) readField(activity, "diagnosticsWorker");
        await(() -> onMainValue(() -> {
            View save = view("save_settings");
            return save != null && save.isEnabled()
                    && text("settings_api_key").equals(KEY) && text("settings_model").equals(MODEL)
                    && text("settings_endpoint").equals(arguments.endpoint());
        }), READY_MILLIS, "MAIN_CONFIGURATION_NOT_READY");
        onMain(() -> {
            check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                    "SECURE_WINDOW_MISSING");
            check(text("task_prompt").isEmpty(), "TASK_INPUT_RESTORED");
            check(!view("task_prompt").isSaveEnabled(), "TASK_INPUT_STATE_SAVING_ENABLED");
        });
    }

    private View view(String name) {
        int id = context.getResources().getIdentifier(name, "id", TARGET);
        check(id != 0, "EXPECTED_VIEW_ID_MISSING");
        return activity.findViewById(id);
    }

    private String text(String id) {
        View value = view(id);
        check(value instanceof TextView, "EXPECTED_TEXT_VIEW_MISSING");
        return ((TextView) value).getText().toString();
    }

    private void runAndStopOneTask(SettingsStore.LoadedSettings loaded) throws Exception {
        stage = Stage.FIXTURE_READY;
        await(() -> AgentAccessibilityService.getInstance() != null
                && AgentAccessibilityService.getInstance().isConnected(), READY_MILLIS, "SERVICE_NOT_CONNECTED");
        context.startActivity(new Intent().setComponent(new ComponentName(FIXTURE, FIXTURE + ".FixtureActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        automation.waitForIdle(500, 8000);
        await(() -> {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return false;
            try { return FIXTURE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()); }
            finally { root.recycle(); }
        }, READY_MILLIS, "FIXTURE_NOT_FOREGROUND");
        stage = Stage.START;
        SettingsStore.SavedSettings saved = loaded.settings;
        onMain(() -> controller.start(new ModelSettings(saved.endpoint, saved.apiKey, saved.model, saved.maxOutputTokens),
                "Open the synthetic fixture only after user approval.", saved.allowedPackages,
                saved.executionMode, saved.taskBudget, loaded.lease));
        RunControl owner = currentOwner();
        check(owner != null, "REAL_OWNER_MISSING");
        stage = Stage.APPROVAL;
        await(() -> {
            AgentController.State state = controller.getState();
            check(state.running, "TASK_ENDED_BEFORE_APPROVAL");
            return !state.approvalId.isEmpty() && !state.approval.isEmpty();
        }, 30_000, "REAL_APPROVAL_NOT_REACHED");
        check(owner.getDispatchAttempts() == 0, "UNAPPROVED_DISPATCH");
        check(owner.modelTokenUsageSnapshot().calls == 1, "MODEL_CALL_COUNT_CHANGED");
        stage = Stage.STOP;
        onMain(controller::stop);
        stage = Stage.DRAIN;
        awaitWorkerDrained();
        AgentController.State stopped = controller.getState();
        check(owner.isCancelled() && owner.getDispatchAttempts() == 0 && currentOwner() == null,
                "STOP_OWNER_NOT_SETTLED");
        check(!stopped.running && stopped.approval.isEmpty() && stopped.approvalId.isEmpty()
                && stopped.resultKind == TaskResult.Kind.STOPPED && "TASK_STOPPED".equals(stopped.resultCode),
                "STOP_STATE_INVALID");
        check(stopped.usage != null && stopped.usage.finished
                && stopped.usage.taskId.equals(owner.getTaskId()) && stopped.usage.modelTokens.calls == 1,
                "STOPPED_USAGE_NOT_RETAINED");
        TaskActionReceipts receipts = controller.getTaskActionReceipts();
        check(receipts != null && receipts.frozen && receipts.taskId.equals(owner.getTaskId())
                && receipts.entries.size() == 1 && receipts.entries.get(0).action == TaskActionReceipts.Action.LAUNCH_APP,
                "STOPPED_RECEIPT_NOT_RETAINED");
        check(!stopped.summary.isEmpty() && !stopped.log.isEmpty() && controller.getTaskDiagnostics() != null,
                "STOPPED_MEMORY_SAMPLE_MISSING");
    }

    private RunControl currentOwner() throws Exception {
        Object lock = readField(controller, "lock");
        synchronized (lock) { return (RunControl) readField(controller, "control"); }
    }

    private void awaitWorkerDrained() throws Exception {
        ThreadPoolExecutor worker = (ThreadPoolExecutor) readField(controller, "worker");
        await(() -> worker.getActiveCount() == 0 && worker.getQueue().isEmpty(), READY_MILLIS, "CONTROLLER_WORKER_NOT_DRAINED");
        instrumentation.waitForIdleSync();
        check(worker.getActiveCount() == 0 && worker.getQueue().isEmpty(), "CONTROLLER_WORKER_REACTIVATED");
    }

    private void assertFreshController() throws Exception {
        AgentController.State state = controller.getState();
        check(!state.running && !state.paused && !state.taskInput.running && state.approval.isEmpty()
                && state.approvalId.isEmpty() && state.summary.isEmpty() && state.log.isEmpty()
                && state.usage == null && state.resultKind == null && state.resultCode.isEmpty()
                && !state.visualRequested && state.visualQualification == AgentController.VisualQualification.NOT_REQUESTED,
                "OLD_CONTROLLER_STATE_RESTORED");
        check(currentOwner() == null && controller.getTaskUsage() == null && controller.getTaskActionReceipts() == null
                && controller.getTaskDiagnostics() == null, "OLD_CONTROLLER_OWNER_RESTORED");
    }

    private void closeMain() throws Exception {
        if (activity == null) return;
        MainActivity owned = activity;
        onMain(() -> { if (!owned.isFinishing() && !owned.isDestroyed()) owned.finish(); });
        long deadline = SystemClock.elapsedRealtime() + READY_MILLIS;
        awaitUntil(() -> onMainValue(owned::isDestroyed), deadline, "MAIN_NOT_DESTROYED");
        Throwable failure = null;
        for (ExecutorService worker : new ExecutorService[]{storageWorker, diagnosticsWorker}) {
            if (worker == null) continue;
            try { awaitUntil(worker::isTerminated, deadline, "MAIN_WORKER_NOT_TERMINATED"); }
            catch (Exception | AssertionError error) { failure = append(failure, error); }
        }
        if (failure instanceof AssertionError) throw (AssertionError) failure;
        if (failure != null) throw (Exception) failure;
        activity = null;
    }

    private JSONObject commonReceipt(boolean seed, StoredIdentity identity) throws Exception {
        PackageInfo installed = context.getPackageManager().getPackageInfo(TARGET, 0);
        return new JSONObject().put("schemaVersion", 1).put("phase", seed ? "seedBaseline" : "verifyCandidate")
                .put("runId", arguments.runId).put("runAttempt", arguments.runAttempt)
                .put("baselineSource", BASELINE).put("candidateSource", arguments.candidateSource)
                .put("api", Build.VERSION.SDK_INT).put("nonceSha256", sha(arguments.nonce))
                .put("targetVersionCode", installed.getLongVersionCode()).put("pid", Process.myPid())
                .put("targetUid", installed.applicationInfo.uid).put("firstInstallTime", installed.firstInstallTime)
                .put("envelopeSha256", identity.envelope).put("revisionSha256", identity.revision)
                .put("grantSha256", identity.grant).put("profileSha256", identity.profile)
                .put("settingsVerified", true).put("fixtureGrantValid", true).put("secureWindowVerified", true)
                .put("captureStatus", "blocked").put("captureReason", "secure_apk_upgrade_capture_not_attempted");
    }

    private static final class StoredIdentity {
        final String envelope, revision, grant, profile;
        StoredIdentity(SharedPreferences preferences, SettingsStore.SavedSettings saved) throws Exception {
            Map<String, ?> raw = preferences.getAll();
            check(raw.size() == 2 && raw.get("encrypted_settings") instanceof String
                    && raw.get("configuration_revision") instanceof String, "STORED_ENVELOPE_INVALID");
            String ciphertext = (String) raw.get("encrypted_settings");
            String token = (String) raw.get("configuration_revision");
            check(!ciphertext.isEmpty() && ciphertext.length() <= 200_000
                    && token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "STORED_IDENTITY_INVALID");
            envelope = sha(ciphertext); revision = sha(token); profile = sha(saved.profileId);
            SettingsStore.AppGrant original = saved.grants.get(FIXTURE);
            check(original != null && original.signers.size() == 1, "ORIGINAL_GRANT_MISSING");
            List<String> signers = new ArrayList<>(original.signers); Collections.sort(signers);
            JSONArray fields = new JSONArray().put(original.packageName).put(original.profileId)
                    .put(original.firstInstallTime).put(original.uid).put(new JSONArray(signers));
            grant = sha(fields.toString());
        }
        void matches(StoredIdentity other) {
            check(envelope.equals(other.envelope) && revision.equals(other.revision)
                    && grant.equals(other.grant) && profile.equals(other.profile), "PERSISTED_DATA_CHANGED");
        }
        void matchesArguments(Arguments args) {
            check(envelope.equals(args.envelope) && revision.equals(args.revision)
                    && grant.equals(args.grant) && profile.equals(args.profile), "UPGRADE_DATA_CHANGED");
        }
    }

    private static final class Arguments {
        final String runId, runAttempt, candidateSource, nonce, envelope, revision, grant, profile;
        final int api, port;
        Arguments(Bundle args, boolean seed) {
            check("agent-os-apk-upgrade-v1".equals(args.getString("upgradeMarker")), "DEDICATED_MARKER_REQUIRED");
            check(BASELINE.equals(args.getString("baselineSource")), "BASELINE_SOURCE_MISMATCH");
            runId = matching(args, "runId", "[1-9][0-9]{0,19}");
            runAttempt = matching(args, "runAttempt", "[1-9][0-9]{0,4}");
            candidateSource = matching(args, "candidateSource", "[0-9a-f]{40}");
            check(!BASELINE.equals(candidateSource), "CANDIDATE_SOURCE_NOT_NEW");
            nonce = matching(args, "nonce", "[0-9a-f]{32}");
            api = Integer.parseInt(matching(args, "expectedApi", "30|31|32|33|34|35|36|37"));
            port = Integer.parseInt(matching(args, "modelPort", "[1-9][0-9]{3,4}"));
            check(port >= 1024 && port <= 65535, "MODEL_PORT_INVALID");
            envelope = seed ? null : matching(args, "expectedEnvelopeSha256", "[0-9a-f]{64}");
            revision = seed ? null : matching(args, "expectedRevisionSha256", "[0-9a-f]{64}");
            grant = seed ? null : matching(args, "expectedGrantSha256", "[0-9a-f]{64}");
            profile = seed ? null : matching(args, "expectedProfileSha256", "[0-9a-f]{64}");
        }
        String endpoint() { return "http://127.0.0.1:" + port + "/v1"; }
        private static String matching(Bundle args, String key, String pattern) {
            String value = args.getString(key);
            check(value != null && value.matches(pattern), "ARGUMENT_INVALID");
            return value;
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target); // Observation only: never replace a production worker or owner.
    }

    private void onMain(CheckedRunnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); }
            catch (Exception | AssertionError error) { failure.set(error); }
        });
        if (failure.get() instanceof AssertionError) throw (AssertionError) failure.get();
        if (failure.get() != null) throw (Exception) failure.get();
    }

    private boolean onMainValue(CheckedCondition condition) throws Exception {
        boolean[] answer = {false};
        onMain(() -> answer[0] = condition.test());
        return answer[0];
    }

    private static void await(CheckedCondition condition, long duration, String reason) throws Exception {
        awaitUntil(condition, SystemClock.elapsedRealtime() + duration, reason);
    }

    private static void awaitUntil(CheckedCondition condition, long deadline, String reason) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.test()) {
                check(SystemClock.elapsedRealtime() < deadline, reason);
                return;
            }
            SystemClock.sleep(25);
        }
        throw new CheckFailure(reason);
    }

    private static Throwable safeFailure(Throwable original, Stage failedStage) {
        String message = "Upgrade phase failed at " + failedStage.name() + "; check="
                + (original instanceof CheckFailure ? ((CheckFailure) original).code : "PLATFORM_EXCEPTION");
        return original instanceof AssertionError ? new AssertionError(message) : new IOException(message);
    }

    private static Throwable append(Throwable first, Throwable later) {
        if (first == null) return later;
        first.addSuppressed(later);
        return first;
    }

    private static String sha(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte next : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", next & 255));
        return result.toString();
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new CheckFailure(reason);
    }
    /** Only our fixed assertion literals can be disclosed; external messages never enter this type. */
    private static final class CheckFailure extends AssertionError {
        final String code;
        CheckFailure(String code) { super(code); this.code = code; }
    }
    private interface CheckedRunnable { void run() throws Exception; }
    private interface CheckedCondition { boolean test() throws Exception; }
}
