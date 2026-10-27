package dev.agentos.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentos.core.ActionConfirmationPolicy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Test-only real-app setup/evidence. Never registers a page, grants capture, or replaces a Snapshot. */
final class PinnedCalculatorPageHarness implements AutoCloseable {
    private static final String PACKAGE = "org.fossify.math";
    private static final int MAX_NODES = 300, MAX_JSON = 96 * 1024;
    private final String caseName;
    private final JsonObject report = new JsonObject();
    private final JsonArray stages = new JsonArray();
    private final Bundle arguments;
    DeviceHarness device;
    LocalControllerModelServer server;
    ControllerTestSettings settings;
    AndroidDeviceTools tools;
    SettingsStore.AppGrant grant;
    SettingsStore.ConfigurationLease lease;
    SupportedVisualPagePolicy.VerifiedInstall installed;
    private File directory;
    private boolean finished;
    private int stageNumber;
    private long epoch = -1;

    PinnedCalculatorPageHarness(String caseName) throws Exception {
        this.caseName = caseName;
        arguments = InstrumentationRegistry.getArguments();
        preflight(); // Must precede DeviceHarness's setting changes.
        report.addProperty("schemaVersion", 1);
        report.addProperty("kind", "pinned-calculator-page-predicate");
        report.addProperty("testClass", PinnedCalculatorVisualPageInstrumentedTest.class.getName());
        report.addProperty("testName", caseName);
        report.addProperty("api", Build.VERSION.SDK_INT);
        report.addProperty("sourceSha", arguments.getString("sourceSha"));
        report.addProperty("runId", arguments.getString("runId"));
        report.addProperty("runAttempt", arguments.getString("runAttempt"));
        report.addProperty("deviceSerial", arguments.getString("deviceSerial"));
        report.addProperty("scope", "Real pinned APK and production service predicate; native capture permission remains disabled");
        report.addProperty("startedAt", System.currentTimeMillis());
        report.addProperty("status", "unverified");
        report.add("stages", stages);
        try {
            device = new DeviceHarness();
            epoch = device.control.getExecutionEpoch();
            directory = new File(device.context.getFilesDir(), "pinned-calculator-page");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Pinned evidence directory unavailable");
            persist();
            server = new LocalControllerModelServer(LocalControllerModelServer.Scenario.REJECT_CLICK);
            settings = new ControllerTestSettings(device.context, server);
            SettingsStore store = new SettingsStore(device.context);
            SettingsStore.SavedSettings saved = store.load();
            saved.allowedPackages.clear(); saved.grants.clear();
            grant = SettingsStore.captureGrant(device.context, PACKAGE);
            saved.allowedPackages.add(PACKAGE); saved.grants.put(PACKAGE, grant);
            lease = store.save(saved);
            tools = new AndroidDeviceTools(device.service, Collections.singleton(PACKAGE), Collections.singletonMap(PACKAGE, grant),
                    ActionConfirmationPolicy.Mode.CONFIRM_ALL, lease,
                    (description, control, epoch) -> { throw new AssertionError("Page predicate must not request an image or action approval"); });
            installed = SupportedVisualPagePolicy.verifyInstalled(device.context, grant, device.control,
                    epoch, lease);
            report.addProperty("installedArtifactVerified", true);
            assertNoVisualCapability();
            Intent launcher = device.context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
            assertNotNull("Actual pinned launcher alias is missing", launcher);
            assertNotNull(launcher.getComponent());
            assertEquals(PACKAGE, launcher.getComponent().getPackageName());
            device.context.startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            awaitTree("ordinary calculator page", tree -> tree.id("btn_equals", false) != null && tree.id("result", false) != null);
            device.awaitUiIdle();
        } catch (Exception | Error failure) {
            try { failureEvidence(failure); } catch (Exception | Error diagnostic) { failure.addSuppressed(diagnostic); }
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void preflight() throws Exception {
        assertEquals("Only the dedicated pinned-page workflow may run this class", "agent-os-pinned-page-v1", arguments.getString("pinnedPageMarker"));
        assertEquals("Fresh installation must be checked by the bounded driver", "agent-os-pinned-page-fresh-v1", arguments.getString("freshInstallMarker"));
        assertTrue("Targeted page matrix must use API 34–37", Build.VERSION.SDK_INT >= 34 && Build.VERSION.SDK_INT <= 37);
        assertEquals(Integer.toString(Build.VERSION.SDK_INT), arguments.getString("expectedApi"));
        assertTrue(arguments.getString("sourceSha", "").matches("[0-9a-f]{40}"));
        assertTrue(arguments.getString("runId", "").matches("[1-9][0-9]{0,18}"));
        assertTrue(arguments.getString("runAttempt", "").matches("[1-9][0-9]{0,18}"));
        assertTrue(arguments.getString("deviceSerial", "").matches("emulator-[0-9]{4,5}"));
        assertEquals("This fixed synthetic UI probe uses the emulator's English resources", "en", Locale.getDefault().getLanguage());
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertEquals("1", shell(automation, "getprop ro.kernel.qemu").trim());
        assertEquals("x86_64", shell(automation, "getprop ro.product.cpu.abi").trim());
        assertEquals("0", shell(automation, "am get-current-user").trim());
    }

    void assertNoVisualCapability() throws Exception {
        AccessibilityServiceInfo info = device.service.getServiceInfo();
        assertNotNull(info);
        assertEquals("Predicate acceptance must not enable native capture", 0,
                info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);
        assertFalse(tools.availableTools().contains("take_screenshot"));
        assertFalse(tools.availableTools().contains("tap"));
        assertFalse(tools.availableTools().contains("swipe"));
        assertEquals("No model probe or task request belongs in page predicate tests", 0, server.requestCount());
        assertTrue(SettingsStore.isCurrent(lease));
        report.addProperty("captureCapabilityAbsent", true);
        report.addProperty("imageAndGestureToolsAbsent", true);
        report.addProperty("modelRequests", server.requestCount());
    }

    void resetNormally() throws Exception {
        clickId("btn_clear", AccessibilityNodeInfo.ACTION_LONG_CLICK);
        awaitTree("actual reset result", tree -> tree.textEquals("result", "0"));
        device.awaitUiIdle();
    }

    void calculateFive() throws Exception {
        for (String id : new String[]{"btn_2", "btn_plus", "btn_3", "btn_equals"}) clickId(id, AccessibilityNodeInfo.ACTION_CLICK);
        awaitTree("actual arithmetic result", tree -> tree.textEquals("result", "5"));
        device.awaitUiIdle();
    }

    void clickId(String id, int action) throws Exception {
        try (Tree tree = new Tree()) {
            AccessibilityNodeInfo node = tree.id(id, true);
            assertTrue(node.isEnabled());
            assertTrue(action == AccessibilityNodeInfo.ACTION_LONG_CLICK ? node.isLongClickable() : node.isClickable());
            assertTrue("Actual pinned resource rejected setup action", node.performAction(action));
        }
        device.automation.waitForIdle(150, 5_000);
    }

    boolean clickIdIfPresent(String id) throws Exception {
        try (Tree tree = new Tree()) { if (tree.id(id, false) == null) return false; }
        clickId(id, AccessibilityNodeInfo.ACTION_CLICK); return true;
    }

    void clickText(String expected) throws Exception {
        try (Tree tree = new Tree()) {
            AccessibilityNodeInfo selected = null;
            for (Map.Entry<String, AccessibilityNodeInfo> item : tree.nodes.entrySet()) {
                AccessibilityNodeInfo node = item.getValue();
                if (!node.isVisibleToUser() || !expected.contentEquals(node.getText() == null ? "" : node.getText())) continue;
                assertNull("Popup setup label was not unique", selected);
                selected = node;
                // Menu text can be inside a clickable row. Use only an already-owned
                // ancestor from this same actual tree, never a new parent lookup.
                String path = item.getKey();
                while (!selected.isClickable() && path.contains(".")) {
                    path = path.substring(0, path.lastIndexOf('.'));
                    selected = tree.nodes.get(path);
                    assertNotNull(selected);
                }
            }
            assertNotNull("Actual popup setup label missing", selected);
            assertTrue(selected.isClickable() && selected.isEnabled());
            assertTrue(selected.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        }
        device.automation.waitForIdle(150, 5_000);
    }

    void openOverflow() throws Exception {
        try (Tree tree = new Tree()) {
            // The real service's 28-node tree folds away toolbar/appbar wrappers.
            // Locate only the unique native overflow leaf after the actual known
            // menu actions, not an arbitrary image or an assumed XML hierarchy.
            AccessibilityNodeInfo history = tree.id("history", true), converter = tree.id("unit_converter", true);
            AccessibilityNodeInfo settingsAction = tree.id("settings", false);
            Rect row = bounds(history), preceding = bounds(settingsAction == null ? converter : settingsAction);
            assertEquals(row.top, preceding.top); assertEquals(row.bottom, preceding.bottom);
            AccessibilityNodeInfo overflow = null;
            for (AccessibilityNodeInfo node : tree.nodes.values()) {
                Rect box = bounds(node);
                if (!node.isVisibleToUser() || !"android.widget.ImageView".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                        || (node.getViewIdResourceName() != null && !node.getViewIdResourceName().isEmpty()) || node.getChildCount() != 0
                        || !node.isClickable() || !node.isLongClickable() || !node.isEnabled() || node.isScrollable()
                        || !"More options".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())
                        || box.left != preceding.right || box.right != tree.windowBounds.right
                        || box.top != row.top || box.bottom != row.bottom) continue;
                assertNull("Native toolbar overflow was not unique", overflow); overflow = node;
            }
            assertNotNull("Actual native overflow control missing", overflow);
            assertTrue(overflow.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        }
        device.automation.waitForIdle(150, 5_000);
    }

    void openNonemptyHistory() throws Exception {
        // Result rendering precedes the official app's asynchronous history insert.
        // Only an actual empty-history notification permits another setup click;
        // a final predicate refusal is never retried or converted to readiness.
        AtomicBoolean emptyHistory = new AtomicBoolean();
        device.automation.setOnAccessibilityEventListener(event -> {
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                    && PACKAGE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) {
                for (CharSequence text : event.getText()) if ("History is empty".contentEquals(text == null ? "" : text)) emptyHistory.set(true);
            }
        });
        try {
            long deadline = SystemClock.elapsedRealtime() + 8_000;
            int retries = 0;
            String settledGeometry = null;
            long quietSince = -1;
            clickId("history", AccessibilityNodeInfo.ACTION_CLICK);
            do {
                check();
                boolean ordinary = false;
                try (Tree tree = new Tree()) {
                    AccessibilityNodeInfo list = tree.id("history_list", false);
                    if (list != null && list.isVisibleToUser() && list.getChildCount() > 0) {
                        // A visible dialog may still be animating or retain the old
                        // application window. This is preparation, before observe
                        // and the single final predicate, within the original limit.
                        String geometry = tree.windowGeometrySignature() + ":list=" + bounds(list).toShortString();
                        long now = SystemClock.elapsedRealtime();
                        if (!geometry.equals(settledGeometry)) {
                            settledGeometry = geometry; quietSince = now;
                        } else if (now - quietSince >= 300 && now < deadline) return;
                    } else {
                        settledGeometry = null; quietSince = -1;
                        ordinary = tree.id("btn_equals", false) != null;
                    }
                } catch (NotReady transition) {
                    settledGeometry = null; quietSince = -1;
                }
                if (ordinary && retries < 2 && emptyHistory.compareAndSet(true, false)) {
                    retries++; clickId("history", AccessibilityNodeInfo.ACTION_CLICK);
                }
                SystemClock.sleep(80);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("Pinned UI did not present actual nonempty history");
        } finally { device.automation.setOnAccessibilityEventListener(null); }
    }

    void back() throws Exception {
        assertTrue(device.service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
        device.automation.waitForIdle(150, 5_000);
    }

    void requireFocusedActivity(String shortName) throws Exception {
        assertTrue(shortName.equals("SettingsActivity") || shortName.equals("UnitConverterPickerActivity"));
        String dump = shell(device.automation, "dumpsys -t 2 window visible-apps");
        Matcher focus = Pattern.compile("(?m)^[\\t ]*mCurrentFocus=Window\\{[^}]*\\s" + Pattern.quote(PACKAGE)
                + "/(?:" + Pattern.quote(PACKAGE) + ")?\\.activities\\." + Pattern.quote(shortName) + "\\}").matcher(dump);
        assertTrue("WMS did not confirm the actual expected pinned activity", focus.find());
        assertFalse("WMS focus was ambiguous", focus.find());
    }

    @FunctionalInterface interface TreeCondition { boolean matches(Tree tree) throws Exception; }
    void awaitTree(String purpose, TreeCondition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        do {
            check();
            try (Tree tree = new Tree()) { if (condition.matches(tree)) return; }
            catch (NotReady transition) { /* Initial real UI readiness only, never a final predicate retry. */ }
            SystemClock.sleep(80);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Pinned UI did not reach " + purpose);
    }

    String stableRevision() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        String previous = "", lastCode = "none";
        do {
            check();
            JsonObject result = tools.execute("observe", new JsonObject(), device.control);
            boolean ok = result.has("ok") && result.get("ok").getAsBoolean();
            if (ok && result.has("data")) {
                JsonObject data = result.getAsJsonObject("data");
                if (PACKAGE.equals(DeviceHarness.string(data, "packageName"))) {
                    String revision = DeviceHarness.string(data, "revision");
                    if (!revision.isEmpty() && revision.equals(previous)) return revision;
                    previous = revision;
                }
                lastCode = "OK";
            } else lastCode = result.has("error") && result.get("error").isJsonObject()
                    ? DeviceHarness.string(result.getAsJsonObject("error"), "code") : "UNKNOWN";
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("No stable original service observation; lastCode=" + lastCode);
    }

    SupportedVisualPagePolicy.Match accept(String stage, String revision) throws Exception {
        diagnostic(stage + "-before-predicate");
        SupportedVisualPagePolicy.Match match = tools.evaluateSupportedVisualPage(device.control,
                epoch, revision, installed);
        assertNotNull(match); assertEquals(SupportedVisualPagePolicy.POLICY_ID, match.policyId);
        assertTrue(match.fingerprint.matches("[0-9a-f]{64}"));
        outcome(stage, "accepted", ""); assertNoVisualCapability(); return match;
    }

    void refuse(String stage, String revision) throws Exception {
        diagnostic(stage + "-before-predicate");
        try {
            tools.evaluateSupportedVisualPage(device.control, epoch, revision, installed);
            fail("Actual alternate page unexpectedly matched the fixed basic-page policy");
        } catch (SupportedVisualPagePolicy.Rejected rejected) {
            boolean pageRefusal = rejected.reason == SupportedVisualPagePolicy.Reason.UNKNOWN_CONTENT
                    || rejected.reason == SupportedVisualPagePolicy.Reason.INVALID_KEYPAD
                    || rejected.reason == SupportedVisualPagePolicy.Reason.UNSUPPORTED_GEOMETRY
                    || rejected.reason == SupportedVisualPagePolicy.Reason.SENSITIVE_CONTENT
                    || rejected.reason == SupportedVisualPagePolicy.Reason.UNSUPPORTED_FORMAT;
            if (!pageRefusal) throw new AssertionError(
                    "Installation/configuration/API failures are not page refusal evidence: " + rejected.reason, rejected);
            outcome(stage, "rejected", rejected.reason.name());
        } catch (AndroidDeviceTools.WindowCaptureException rejected) {
            boolean pageRefusal = rejected.code == AndroidDeviceTools.WindowCaptureFailure.OCCLUDED
                    || rejected.code == AndroidDeviceTools.WindowCaptureFailure.UNSUPPORTED_GEOMETRY
                    || rejected.code == AndroidDeviceTools.WindowCaptureFailure.SENSITIVE_CONTENT
                    || rejected.code == AndroidDeviceTools.WindowCaptureFailure.HIGH_IMPACT_CONTENT;
            if (!pageRefusal) throw new AssertionError(
                    "Stale/busy/timeout/context failures must not pass a page negative: " + rejected.code, rejected);
            outcome(stage, "rejected", rejected.code.name());
        }
        assertNoVisualCapability();
    }

    private void outcome(String stage, String status, String reason) throws IOException {
        JsonObject item = new JsonObject(); item.addProperty("stage", stage); item.addProperty("predicate", status);
        if (!reason.isEmpty()) item.addProperty("reason", reason);
        stages.add(item); persist();
    }

    private void check() throws Exception {
        device.control.checkExecutionEpoch(epoch);
        assertTrue("The original real saved lease became invalid", SettingsStore.isCurrent(lease));
    }

    /** Diagnostic read, separately timed and never substituted for the authoritative Snapshot. */
    private void diagnostic(String stage) throws Exception {
        JsonObject item;
        try (Tree tree = new Tree()) { item = tree.metadata(stage); }
        String filename = caseName + "-" + stageNumber++ + ".json";
        write(new File(directory, filename), item, MAX_JSON);
        JsonObject entry = new JsonObject(); entry.addProperty("stage", stage); entry.addProperty("metadata", filename);
        entry.addProperty("sampleRole", "Independent bounded production-service diagnostic; not the predicate Snapshot");
        stages.add(entry);
        capturePinned(stage, entry);
        persist();
    }

    /** UiAutomation evidence of this dedicated pinned app, never proof of native capture. */
    private void capturePinned(String stage, JsonObject item) throws Exception {
        Bitmap image = null;
        item.addProperty("secureFlagsCleared", false);
        item.addProperty("captureMechanism", "test-only UiAutomation evidence");
        try {
            SupportedVisualPagePolicy.requireCurrentInstall(device.context, grant, installed, device.control, epoch, lease);
            int window;
            Rect before;
            try (Tree tree = new Tree()) {
                if (!tree.evidenceCaptureEligible()) {
                    item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", "nonfull_or_unknown_overlapping_window"); return;
                }
                window = tree.windowId; before = new Rect(tree.windowBounds);
            }
            WindowFlags flags = inspectedWindow(shell(device.automation, "dumpsys -t 2 window visible-apps"));
            if (flags.secure) { item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", "application_window_is_secure"); return; }
            image = device.automation.takeScreenshot();
            if (image == null) { item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", "platform_refused_or_secure"); return; }
            if (image.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap software = image.copy(Bitmap.Config.ARGB_8888, false); image.recycle(); image = software;
            }
            if (image == null || image.getWidth() > 4096 || image.getHeight() > 4096
                    || (long) image.getWidth() * image.getHeight() > 16_777_216L || !hasContent(image)) {
                item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", "blank_protected_or_oversized"); return;
            }
            try (Tree tree = new Tree()) {
                if (tree.windowId != window || !tree.windowBounds.equals(before) || !tree.evidenceCaptureEligible()) {
                    item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", "focused_window_changed"); return;
                }
            }
            WindowFlags after = inspectedWindow(shell(device.automation, "dumpsys -t 2 window visible-apps"));
            if (after.secure || !flags.identity.equals(after.identity)) {
                item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", "focused_window_flags_changed"); return;
            }
            SupportedVisualPagePolicy.requireCurrentInstall(device.context, grant, installed, device.control, epoch, lease);
            File output = new File(directory, caseName + "-" + stageNumber++ + ".png");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Evidence encoding failed");
                stream.getFD().sync();
            }
            if (output.length() <= 0 || output.length() > 8 * 1024 * 1024) {
                if (!output.delete()) throw new IOException("Could not remove oversized evidence");
                throw new IOException("Evidence exceeded byte limit");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(output)) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            item.addProperty("captureStatus", "captured"); item.addProperty("image", output.getName());
            item.addProperty("imageSha256", hex(digest.digest())); item.addProperty("imageBytes", output.length());
            item.addProperty("imageWidth", image.getWidth()); item.addProperty("imageHeight", image.getHeight());
        } catch (CaptureRefused unavailable) {
            item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", unavailable.getMessage());
        } catch (NotReady transition) {
            item.addProperty("captureStatus", "blocked"); item.addProperty("captureReason", "target_not_stable");
        } finally { if (image != null) image.recycle(); }
    }

    void failureEvidence(Throwable failure) throws Exception {
        if (directory == null || finished) return;
        report.addProperty("failureClass", failure.getClass().getSimpleName());
        if (failure instanceof SupportedVisualPagePolicy.Rejected) report.addProperty("failureCode", ((SupportedVisualPagePolicy.Rejected) failure).reason.name());
        if (failure instanceof AndroidDeviceTools.WindowCaptureException) report.addProperty("failureCode", ((AndroidDeviceTools.WindowCaptureException) failure).code.name());
        try { if (installed != null) diagnostic("failure-independent-sample"); }
        finally { finish(false); }
    }
    void finish(boolean success) throws IOException {
        report.addProperty("status", success ? "passed" : "failed");
        report.addProperty("finishedAt", System.currentTimeMillis()); finished = true; persist();
    }
    private void persist() throws IOException { if (directory != null) write(new File(directory, caseName + ".case.json"), report, MAX_JSON); }

    final class Tree implements AutoCloseable {
        private final List<AccessibilityNodeInfo> owned = new ArrayList<>();
        private List<AccessibilityWindowInfo> windows;
        final Map<String, AccessibilityNodeInfo> nodes = new LinkedHashMap<>();
        final Rect windowBounds = new Rect(), rootBounds = new Rect();
        int windowId, windowLayer;
        private boolean gate, closed, truncated;
        Tree() throws Exception {
            check();
            if (!device.service.tryBeginDeviceCall()) throw new AssertionError("Page sampler device gate unexpectedly busy");
            gate = true;
            try {
                windows = AndroidDeviceTools.freshWindows(device.service, PinnedCalculatorPageHarness.this::check);
                if (windows == null || windows.isEmpty() || windows.size() > 40) throw new NotReady();
                AccessibilityWindowInfo focused = null;
                for (AccessibilityWindowInfo window : windows) if (window.isFocused()) {
                    if (focused != null || window.getDisplayId() != Display.DEFAULT_DISPLAY) throw new NotReady();
                    focused = window;
                }
                if (focused == null || focused.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) throw new NotReady();
                AccessibilityNodeInfo root = focused.getRoot();
                if (root == null) throw new NotReady();
                owned.add(root); windowId = focused.getId(); windowLayer = focused.getLayer(); focused.getBoundsInScreen(windowBounds); root.getBoundsInScreen(rootBounds);
                if (!PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                        || root.getWindowId() != windowId || windowBounds.isEmpty()) throw new NotReady();
                visit(root, "n", 0);
                check();
            } catch (Exception | Error failure) { close(); throw failure; }
        }
        private void visit(AccessibilityNodeInfo node, String path, int depth) throws Exception {
            check();
            if (depth > 30 || nodes.size() >= MAX_NODES) { truncated = true; return; }
            nodes.put(path, node);
            // The pinned app should have no such content on its ordinary page. A
            // diagnostic must not descend into sensitive alternate-page subtrees.
            if (node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) return;
            for (int i = 0; i < node.getChildCount(); i++) {
                if (owned.size() >= MAX_NODES) { truncated = true; break; }
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) { owned.add(child); visit(child, path + "." + i, depth + 1); }
            }
        }
        AccessibilityNodeInfo id(String local, boolean required) {
            AccessibilityNodeInfo found = null;
            for (AccessibilityNodeInfo node : nodes.values()) if (node.isVisibleToUser() && (PACKAGE + ":id/" + local).equals(node.getViewIdResourceName())) {
                assertNull("Actual resource identifier was not unique", found); found = node;
            }
            if (required) assertNotNull("Required pinned resource missing: " + local, found);
            return found;
        }
        boolean hasVisibleUnitCategoryInGrid() {
            AccessibilityNodeInfo grid = id("unit_types_grid", false);
            if (grid == null || !"androidx.recyclerview.widget.RecyclerView".contentEquals(
                    grid.getClassName() == null ? "" : grid.getClassName())) return false;
            String prefix = null;
            for (Map.Entry<String, AccessibilityNodeInfo> entry : nodes.entrySet()) {
                if (entry.getValue() == grid) { prefix = entry.getKey() + "."; break; }
            }
            if (prefix == null) return false;
            // Category labels intentionally repeat the resource ID. Do not use
            // id(), which must retain its unique-ID assertion for other controls.
            for (Map.Entry<String, AccessibilityNodeInfo> entry : nodes.entrySet()) {
                AccessibilityNodeInfo node = entry.getValue();
                if (entry.getKey().startsWith(prefix) && node.isVisibleToUser() && node.getChildCount() == 0
                        && (PACKAGE + ":id/unit_label").equals(node.getViewIdResourceName())
                        && "android.widget.TextView".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                        && node.getText() != null && node.getText().length() > 0) return true;
            }
            return false;
        }
        String windowGeometrySignature() {
            List<String> actual = new ArrayList<>();
            for (AccessibilityWindowInfo window : windows) {
                Rect box = new Rect(); window.getBoundsInScreen(box);
                actual.add(window.getId() + ":" + window.getDisplayId() + ":" + window.getType() + ":"
                        + window.getLayer() + ":" + window.isFocused() + ":" + box.toShortString());
            }
            Collections.sort(actual);
            return windowId + ":" + windowBounds.toShortString() + ":" + rootBounds.toShortString() + ":" + actual;
        }
        boolean textEquals(String local, String expected) {
            AccessibilityNodeInfo node = id(local, false);
            return node != null && expected.contentEquals(node.getText() == null ? "" : node.getText());
        }
        boolean hasVisibleText(String expected) {
            for (AccessibilityNodeInfo node : nodes.values()) if (node.isVisibleToUser()
                    && expected.contentEquals(node.getText() == null ? "" : node.getText())) return true;
            return false;
        }
        JsonObject metadata(String stage) {
            JsonObject result = new JsonObject(); result.addProperty("stage", stage); result.addProperty("sampledAt", System.currentTimeMillis());
            result.addProperty("sampleRole", "Independent diagnostic, not authoritative predicate Snapshot");
            result.addProperty("windowId", windowId); result.addProperty("windowBounds", windowBounds.toShortString());
            result.addProperty("rootBounds", rootBounds.toShortString()); result.addProperty("truncated", truncated);
            WindowManager manager = (WindowManager) device.service.getSystemService(Context.WINDOW_SERVICE);
            assertNotNull(manager); WindowMetrics metrics = manager.getCurrentWindowMetrics();
            result.addProperty("displayBounds", metrics.getBounds().toShortString());
            result.addProperty("bars", metrics.getWindowInsets().getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()).toString());
            Insets safe = metrics.getWindowInsets().getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
            Rect display = metrics.getBounds();
            result.addProperty("displaySafeBounds", new Rect(display.left + safe.left, display.top + safe.top, display.right - safe.right, display.bottom - safe.bottom).toShortString());
            result.addProperty("rotation", manager.getDefaultDisplay().getRotation());
            JsonArray inventory = new JsonArray();
            for (AccessibilityWindowInfo window : windows) {
                JsonObject item = new JsonObject(); Rect box = new Rect(); window.getBoundsInScreen(box);
                item.addProperty("id", window.getId()); item.addProperty("display", window.getDisplayId()); item.addProperty("type", window.getType());
                item.addProperty("layer", window.getLayer()); item.addProperty("focused", window.isFocused()); item.addProperty("bounds", box.toShortString()); inventory.add(item);
            }
            result.add("windows", inventory);
            JsonArray data = new JsonArray(); int metadataBytes = 0;
            for (Map.Entry<String, AccessibilityNodeInfo> entry : nodes.entrySet()) {
                AccessibilityNodeInfo node = entry.getValue(); JsonObject item = new JsonObject();
                item.addProperty("path", entry.getKey()); item.addProperty("viewId", technical(node.getViewIdResourceName()));
                item.addProperty("role", technical(node.getClassName())); item.addProperty("package", technical(node.getPackageName()));
                item.addProperty("bounds", bounds(node).toShortString()); item.addProperty("visible", node.isVisibleToUser());
                item.addProperty("enabled", node.isEnabled()); item.addProperty("editable", node.isEditable()); item.addProperty("password", node.isPassword());
                boolean secret = node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
                item.addProperty("dataSensitive", Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
                item.addProperty("clickable", node.isClickable()); item.addProperty("longClickable", node.isLongClickable()); item.addProperty("scrollable", node.isScrollable());
                item.addProperty("textLength", secret ? -1 : length(node.getText())); item.addProperty("descriptionLength", secret ? -1 : length(node.getContentDescription()));
                item.addProperty("tooltipLength", secret ? -1 : length(node.getTooltipText())); item.addProperty("replacementSpan", !secret && replacement(node.getText()));
                JsonArray actions = new JsonArray(); List<AccessibilityNodeInfo.AccessibilityAction> actual = node.getActionList();
                for (int i = 0; i < Math.min(32, actual.size()); i++) actions.add(actual.get(i).getId());
                item.add("actionIds", actions); item.addProperty("actionsTruncated", actual.size() > 32);
                metadataBytes += item.toString().getBytes(StandardCharsets.UTF_8).length;
                if (metadataBytes > 64 * 1024) { result.addProperty("metadataTruncated", true); break; }
                data.add(item);
            }
            result.add("nodes", data); return result;
        }
        boolean evidenceCaptureEligible() {
            WindowManager manager = (WindowManager) device.service.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) return false;
            WindowMetrics metrics = manager.getCurrentWindowMetrics(); Rect display = metrics.getBounds();
            if (!windowBounds.equals(display)) return false;
            Insets bars = metrics.getWindowInsets().getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            Rect[] strips = {new Rect(display.left, display.top, display.right, display.top + bars.top),
                    new Rect(display.left, display.bottom - bars.bottom, display.right, display.bottom),
                    new Rect(display.left, display.top, display.left + bars.left, display.bottom),
                    new Rect(display.right - bars.right, display.top, display.right, display.bottom)};
            AgentAccessibilityService.OwnedControlWindowIdentity controls = device.service.getOwnedControlWindowIdentity();
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() == windowId || window.getDisplayId() != Display.DEFAULT_DISPLAY || window.getLayer() < windowLayer) continue;
                Rect box = new Rect(); window.getBoundsInScreen(box);
                if (!Rect.intersects(display, box)) continue;
                if (controls != null && device.service.isOwnedControlWindowCurrent(controls)
                        && window.getId() == controls.windowId && window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        && !window.isFocused() && box.equals(controls.copyBounds())) continue;
                boolean bar = false;
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM)
                    for (Rect strip : strips) if (!strip.isEmpty() && strip.contains(box)) bar = true;
                if (!bar) return false;
            }
            return true;
        }
        @Override public void close() {
            if (closed) return; closed = true;
            try { for (AccessibilityNodeInfo node : owned) node.recycle(); }
            finally { try { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
                finally { if (gate) { gate = false; device.service.endDeviceCall(); } } }
        }
    }

    @Override public void close() throws Exception {
        Throwable failure = null;
        for (Cleanup step : new Cleanup[]{
                () -> { if (device != null) device.control.cancel(); },
                () -> { if (settings != null) settings.close(); },
                () -> { if (server != null) server.close(); },
                () -> { if (device != null) device.close(); }}) {
            try { step.run(); }
            catch (Exception | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (directory != null && (failure != null || !finished)) {
            try { if (failure != null) report.addProperty("cleanupFailureClass", failure.getClass().getSimpleName()); finish(false); }
            catch (Exception | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw (Error) failure;
    }
    @FunctionalInterface private interface Cleanup { void run() throws Exception; }

    // Same fail-closed numeric/named secure-flag parser as the pinned compatibility
    // evidence helper. It permits evidence of MainActivity only; alternate windows
    // remain blocked unless this independently read WMS contract is satisfied.
    private static WindowFlags inspectedWindow(String dump) throws CaptureRefused {
        Matcher currentFocusFields = Pattern.compile("\\bmCurrentFocus=").matcher(dump);
        int focusFields = 0;
        while (currentFocusFields.find()) focusFields++;
        if (focusFields != 1) throw new CaptureRefused("window_manager_focus_is_missing_or_ambiguous");
        Pattern focusPattern = Pattern.compile("mCurrentFocus=Window\\{([0-9a-fA-F]+)\\s+u0\\s+(" + Pattern.quote(PACKAGE) + "/[^}\\s]+)\\}");
        Matcher focus = focusPattern.matcher(dump);
        if (!focus.find()) throw new CaptureRefused("window_manager_did_not_confirm_frozen_application_focus");
        String token = focus.group(1);
        String component = focus.group(2);
        if (focus.find()) throw new CaptureRefused("window_manager_focus_is_ambiguous");
        if (!component.equals(PACKAGE + "/" + PACKAGE + ".activities.MainActivity")
                && !component.equals(PACKAGE + "/.activities.MainActivity")) {
            throw new CaptureRefused("focused_activity_is_not_the_frozen_application_page");
        }
        Pattern header = Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{" + Pattern.quote(token)
                + "\\s+u0\\s+" + Pattern.quote(component) + "\\}:");
        Matcher target = header.matcher(dump);
        if (!target.find()) throw new CaptureRefused("focused_window_attributes_are_unavailable");
        int start = target.end();
        Matcher next = Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{").matcher(dump);
        int end = next.find(start) ? next.start() : dump.length();
        String block = dump.substring(start, end);
        int attrsStart = block.indexOf("mAttrs={");
        if (attrsStart < 0) throw new CaptureRefused("focused_window_attributes_are_unavailable");
        int brace = attrsStart + "mAttrs=".length();
        int depth = 0, attrsEnd = -1;
        for (int i = brace; i < block.length(); i++) {
            if (block.charAt(i) == '{') depth++;
            else if (block.charAt(i) == '}' && --depth == 0) { attrsEnd = i; break; }
        }
        if (attrsEnd < 0 || attrsEnd - brace > 8192) throw new CaptureRefused("focused_window_attributes_are_incomplete");
        String attributes = block.substring(brace + 1, attrsEnd);
        Matcher flagsMatcher = Pattern.compile("(?:^|\\s)fl=([^\\r\\n}]*)").matcher(attributes);
        if (!flagsMatcher.find()) throw new CaptureRefused("window_secure_flag_could_not_be_read");
        String flags = flagsMatcher.group(1).trim();
        if (flags.isEmpty()) throw new CaptureRefused("window_secure_flag_could_not_be_read");
        boolean secure;
        if (flags.matches("(?:0x)?[0-9a-fA-F]+")) {
            try { secure = (Long.parseLong(flags.replaceFirst("^0x", ""), 16) & 0x2000L) != 0; }
            catch (NumberFormatException invalid) { throw new CaptureRefused("window_flags_have_unknown_format"); }
        } else if (flags.matches("[A-Z0-9_| ]+")) {
            secure = Pattern.compile("(?:^|[ |])(?:FLAG_)?SECURE(?:$|[ |])").matcher(flags).find();
        } else {
            throw new CaptureRefused("window_flags_have_unknown_format");
        }
        return new WindowFlags(token + "/" + component + "/" + flags, flags, secure);
    }

    private static final class WindowFlags {
        final String identity, flags;
        final boolean secure;
        WindowFlags(String identity, String flags, boolean secure) { this.identity = identity; this.flags = flags; this.secure = secure; }
    }
    private static final class CaptureRefused extends Exception {
        CaptureRefused(String reason) { super(reason); }
    }
    private static final class NotReady extends Exception { }
    private static Rect bounds(AccessibilityNodeInfo node) { Rect value = new Rect(); node.getBoundsInScreen(value); return value; }
    private static int length(CharSequence value) { return value == null ? 0 : value.length(); }
    private static boolean replacement(CharSequence value) {
        return value instanceof Spanned && value.length() <= 4096 && ((Spanned) value).getSpans(0, value.length(), ReplacementSpan.class).length > 0;
    }
    private static String technical(CharSequence value) {
        if (value == null) return "";
        return value.length() <= 160 ? value.toString() : "[technical-field-over-budget]";
    }
    private static boolean hasContent(Bitmap image) {
        int first = image.getPixel(image.getWidth() / 2, image.getHeight() / 2), differences = 0;
        for (int y = 2; y < 18; y++) for (int x = 2; x < 18; x++)
            if (image.getPixel(image.getWidth() * x / 20, image.getHeight() * y / 20) != first) differences++;
        return differences > 8;
    }
    private static void write(File file, JsonObject json, int limit) throws IOException {
        byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > limit) throw new IOException("Bounded pinned-page evidence exceeded limit");
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) { output.write(data); output.getFD().sync(); }
        if (!temp.renameTo(file)) throw new IOException("Could not replace pinned-page evidence atomically");
    }
    private static String shell(UiAutomation automation, String command) throws IOException {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 128 * 1024 - output.size()) throw new IOException("Bounded test command output exceeded limit");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
}
