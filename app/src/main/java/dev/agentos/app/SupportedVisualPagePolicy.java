package dev.agentos.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.Display;
import android.view.Surface;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import dev.agentos.core.RunControl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Candidate for one audited binary's ordinary portrait calculator page. This is not a
 * capture authorization, arbitrary-pixel classifier, or network disclosure guard.
 * It is evaluated only behind the task adapter's original identity and capability
 * gates. Production screenshot permission stays closed pending real page acceptance.
 */
final class SupportedVisualPagePolicy {
    static final String PACKAGE_NAME = "org.fossify.math";
    static final String POLICY_ID = "calculator-1.4.0-basic-portrait-v1";
    private static final String VERSION_NAME = "1.4.0";
    private static final long VERSION_CODE = 10;
    private static final long APK_BYTES = 7_047_681L;
    private static final String CERT_SHA256 = "affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292";
    private static final String APK_SHA256 = "7c5443c22d6d62c67dfe80df33ea9161ff0fa440f92590bcb45d7008b57613ed";
    private static final String PREFIX = PACKAGE_NAME + ":id/";
    private static final int MAX_NODES = 300, MAX_TEXT = 160, MAX_PATH = 512, MAX_ACTIONS = 32;
    private static final String TEXT_VIEW = "android.widget.TextView";
    private static final String[] KEYS = {
            "btn_percent", "btn_power", "btn_root", "btn_divide",
            "btn_7", "btn_8", "btn_9", "btn_multiply",
            "btn_4", "btn_5", "btn_6", "btn_minus",
            "btn_1", "btn_2", "btn_3", "btn_plus",
            "btn_0", "btn_decimal", "btn_clear", "btn_equals"
    };
    private static final String[] LABELS = {
            "%", "^", "√", "÷", "7", "8", "9", "×", "4", "5", "6", "-",
            "1", "2", "3", "+", "0", ".", "C", "="
    };
    private static final String[] ANCHORS = {
            "main_coordinator", "main_appbar", "main_toolbar", "main_nested_scrollview", "calculator_holder"
    };

    private SupportedVisualPagePolicy() {}

    enum Reason {
        UNSUPPORTED_API, WRONG_THREAD, INSTALLATION_MISMATCH, INSTALLATION_UNVERIFIABLE,
        INSTALLATION_CHANGED, CONFIGURATION_CHANGED, INCOMPLETE_SNAPSHOT, UNSUPPORTED_GEOMETRY, SENSITIVE_CONTENT,
        UNKNOWN_CONTENT, INVALID_KEYPAD, UNSUPPORTED_FORMAT
    }

    static final class Rejected extends Exception {
        final Reason reason;
        private Rejected(Reason reason) { super("Unsupported visual page: " + reason); this.reason = reason; }
    }

    /** No path, certificate array, node, user display text or mutable platform object escapes. */
    static final class VerifiedInstall {
        private final Installation tuple;
        private final SettingsStore.ConfigurationLease configuration;
        private VerifiedInstall(Installation tuple, SettingsStore.ConfigurationLease configuration) {
            this.tuple = tuple; this.configuration = configuration;
        }
    }

    static final class Match {
        final String policyId = POLICY_ID;
        final String fingerprint;
        private Match(String fingerprint) { this.fingerprint = fingerprint; }
    }

    /**
     * Borrowed for this call only. The caller owns/recycles all nodes and Rects and holds
     * the existing device gate; these accessors must not manufacture a second tree.
     */
    interface SnapshotView {
        String packageName();
        int windowId();
        int displayId();
        int windowType();
        int rotation();
        boolean scoped();
        boolean truncated();
        boolean sensitive();
        boolean highImpact();
        boolean ambiguousFocus();
        boolean hasUnknownObscurer();
        Rect windowBounds();
        Rect rootBounds();
        Rect safeContent();
        Rect displayBounds();
        List<AccessibilityNodeInfo> ownedNodes();
        Map<String, AccessibilityNodeInfo> visibleNodes();
        void checkCurrent() throws Exception;
    }

    /** Hash at most this one reviewed base APK; caller keeps the result in its task adapter. */
    static VerifiedInstall verifyInstalled(Context context, SettingsStore.AppGrant grant,
            RunControl control, long epoch, SettingsStore.ConfigurationLease configuration) throws Exception {
        requireWorker();
        Installation before = readInstallation(context, grant, control, epoch, configuration);
        MessageDigest digest = sha256();
        byte[] scratch = new byte[64 * 1024];
        long total = 0;
        try (FileInputStream input = new FileInputStream(before.sourcePath)) {
            while (true) {
                checkContext(control, epoch, configuration);
                int count = input.read(scratch);
                checkContext(control, epoch, configuration);
                if (count == -1) break;
                if (count == 0 || count > APK_BYTES - total) throw rejected(Reason.INSTALLATION_MISMATCH);
                total += count;
                digest.update(scratch, 0, count);
            }
        } catch (IOException | SecurityException unavailable) {
            // Do not expose the installed path or platform exception message.
            throw rejected(Reason.INSTALLATION_UNVERIFIABLE);
        } finally { Arrays.fill(scratch, (byte) 0); }
        checkContext(control, epoch, configuration);
        if (total != APK_BYTES || !APK_SHA256.equals(hex(digest.digest()))) throw rejected(Reason.INSTALLATION_MISMATCH);
        Installation after = readInstallation(context, grant, control, epoch, configuration);
        if (!before.sameAs(after)) throw rejected(Reason.INSTALLATION_CHANGED);
        return new VerifiedInstall(before, configuration);
    }

    /** Worker/Binder metadata check only, never rehashing and never usable on the HTTP guard. */
    static void requireCurrentInstall(Context context, SettingsStore.AppGrant grant, VerifiedInstall verified,
            RunControl control, long epoch, SettingsStore.ConfigurationLease configuration) throws Exception {
        requireWorker();
        if (verified == null) throw rejected(Reason.INSTALLATION_MISMATCH);
        if (verified.configuration != configuration) throw rejected(Reason.CONFIGURATION_CHANGED);
        Installation current = readInstallation(context, grant, control, epoch, configuration);
        if (!verified.tuple.sameAs(current)) throw rejected(Reason.INSTALLATION_CHANGED);
    }

    /** Read already-owned node fields; no root, parent or child fetch and no retained tree/text. */
    static Match evaluate(VerifiedInstall verified, SnapshotView snapshot) throws Exception {
        requireWorker();
        if (Build.VERSION.SDK_INT < 34) throw rejected(Reason.UNSUPPORTED_API);
        if (verified == null || snapshot == null) throw rejected(Reason.INSTALLATION_MISMATCH);
        snapshot.checkCurrent();
        if (!SettingsStore.isCurrent(verified.configuration)) throw rejected(Reason.CONFIGURATION_CHANGED);
        if (!PACKAGE_NAME.equals(snapshot.packageName()) || !snapshot.scoped()) throw rejected(Reason.INSTALLATION_MISMATCH);
        if (snapshot.truncated()) throw rejected(Reason.INCOMPLETE_SNAPSHOT);
        if (snapshot.sensitive() || snapshot.highImpact()) throw rejected(Reason.SENSITIVE_CONTENT);
        if (snapshot.hasUnknownObscurer() || snapshot.ambiguousFocus()) throw rejected(Reason.UNKNOWN_CONTENT);
        requireGeometry(snapshot);
        List<AccessibilityNodeInfo> owned = snapshot.ownedNodes();
        Map<String, AccessibilityNodeInfo> visible = snapshot.visibleNodes();
        if (owned == null || visible == null || owned.isEmpty() || owned.size() > MAX_NODES
                || visible.isEmpty() || visible.size() > owned.size()) throw rejected(Reason.INCOMPLETE_SNAPSHOT);

        // This index holds borrowed references only and is discarded before returning.
        IdentityHashMap<AccessibilityNodeInfo, String> paths = new IdentityHashMap<>();
        for (Map.Entry<String, AccessibilityNodeInfo> entry : visible.entrySet()) {
            snapshot.checkCurrent();
            String path = entry.getKey();
            if (!validPath(path) || entry.getValue() == null || paths.put(entry.getValue(), path) != null)
                throw rejected(Reason.INCOMPLETE_SNAPSHOT);
        }
        Node[] required = new Node[22];
        Node[] anchors = new Node[ANCHORS.length];
        Node[] toolbar = new Node[4]; // history, converter, optional settings, required native overflow
        int visibleCount = 0;
        for (AccessibilityNodeInfo node : owned) {
            snapshot.checkCurrent();
            if (node == null) throw rejected(Reason.INCOMPLETE_SNAPSHOT);
            if (node.isPassword() || node.isAccessibilityDataSensitive() || node.isEditable())
                throw rejected(Reason.SENSITIVE_CONTENT);
            if (!node.isVisibleToUser()) continue;
            String path = paths.get(node);
            if (path == null || node.getWindowId() != snapshot.windowId()
                    || !same(node.getPackageName(), PACKAGE_NAME)) throw rejected(Reason.INCOMPLETE_SNAPSHOT);
            visibleCount++;
            Node item = new Node(node, path);
            if (item.box.isEmpty() || !snapshot.windowBounds().contains(item.box)) throw rejected(Reason.UNSUPPORTED_GEOMETRY);
            if (!empty(node.getError()) || !empty(node.getHintText()) || !empty(node.getPaneTitle())
                    || !empty(node.getStateDescription())) throw rejected(Reason.UNKNOWN_CONTENT);
            if (item.id.equals(PREFIX + "formula") || item.id.equals(PREFIX + "result")) {
                int index = item.id.endsWith("/formula") ? 0 : 1;
                requireNativeText(item, snapshot.safeContent());
                if (node.isClickable() || node.isScrollable()) throw rejected(Reason.INVALID_KEYPAD);
                requireArithmetic(item.text, index == 0);
                if (!empty(item.description) || !empty(item.tooltip)) throw rejected(Reason.UNKNOWN_CONTENT);
                putOnce(required, index, item);
                continue;
            }
            int key = indexOf(KEYS, item.id);
            if (key >= 0) {
                requireNativeText(item, snapshot.safeContent());
                if (!node.isClickable() || node.isScrollable()
                        || !hasAction(node, AccessibilityNodeInfo.ACTION_CLICK)) throw rejected(Reason.INVALID_KEYPAD);
                boolean correct = key == 17 ? same(item.text, ".") || same(item.text, ",") : same(item.text, LABELS[key]);
                if (!correct || !empty(item.description) || !empty(item.tooltip)) throw rejected(Reason.UNSUPPORTED_FORMAT);
                putOnce(required, key + 2, item);
                continue;
            }
            int anchor = indexOf(ANCHORS, item.id);
            if (anchor >= 0) {
                requireContainer(item, anchor == 3);
                if (!anchorRole(anchor, item.role)) throw rejected(Reason.UNKNOWN_CONTENT);
                putOnce(anchors, anchor, item);
                continue;
            }
            int menu = item.id.equals(PREFIX + "history") ? 0 : item.id.equals(PREFIX + "unit_converter") ? 1
                    : item.id.equals(PREFIX + "settings") ? 2 : -1;
            if (menu >= 0) {
                if (!(item.role.equals(TEXT_VIEW) || item.role.equals("android.widget.Button")) || !node.isEnabled()
                        || !node.isClickable() || !hasAction(node, AccessibilityNodeInfo.ACTION_CLICK)
                        || node.isScrollable()) throw rejected(Reason.UNKNOWN_CONTENT);
                requireToolbarLabel(item);
                putOnce(toolbar, menu, item);
                continue;
            }
            // This exact APK's native overflow button exposes ImageView, not
            // ImageButton. No other image leaf is admitted; require its native
            // tooltip/click contract here and its fixed menu-row position below.
            if (item.role.equals("android.widget.ImageView") && item.id.isEmpty()) {
                if (!node.isEnabled() || !node.isClickable() || !node.isLongClickable()
                        || !hasAction(node, AccessibilityNodeInfo.ACTION_CLICK)
                        || !hasAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)
                        || node.getChildCount() != 0 || node.isScrollable() || !empty(item.text)
                        || empty(item.description) || !same(item.tooltip, item.description.toString())) throw rejected(Reason.UNKNOWN_CONTENT);
                requireToolbarLabel(item);
                putOnce(toolbar, 3, item);
                continue;
            }
            requireStaticStructure(item);
        }
        if (visibleCount != paths.size()) throw rejected(Reason.INCOMPLETE_SNAPSHOT);
        for (Node item : required) if (item == null) throw rejected(Reason.INVALID_KEYPAD);
        // History/converter are always shown; About and More apps always require
        // the native overflow in this exact binary.
        if (toolbar[0] == null || toolbar[1] == null || toolbar[3] == null) throw rejected(Reason.INVALID_KEYPAD);
        requireLayout(snapshot, required, anchors, toolbar, visible);
        snapshot.checkCurrent();
        if (!SettingsStore.isCurrent(verified.configuration)) throw rejected(Reason.CONFIGURATION_CHANGED);
        return new Match(fingerprint(snapshot, required, anchors, toolbar));
    }

    private static void requireGeometry(SnapshotView snapshot) throws Rejected {
        Rect window = snapshot.windowBounds(), root = snapshot.rootBounds(), safe = snapshot.safeContent(), display = snapshot.displayBounds();
        if (window == null || root == null || safe == null || display == null || window.isEmpty()
                || !window.equals(display) || !root.equals(window) || safe.isEmpty() || !window.contains(safe)
                || window.width() >= window.height() || snapshot.windowId() < 0
                || snapshot.displayId() != Display.DEFAULT_DISPLAY
                || snapshot.windowType() != AccessibilityWindowInfo.TYPE_APPLICATION
                || (snapshot.rotation() != Surface.ROTATION_0 && snapshot.rotation() != Surface.ROTATION_180))
            throw rejected(Reason.UNSUPPORTED_GEOMETRY);
    }

    private static void requireNativeText(Node item, Rect safe) throws Rejected {
        if (!item.role.equals(TEXT_VIEW) || !item.node.isEnabled() || !safe.contains(item.box)) throw rejected(Reason.INVALID_KEYPAD);
    }

    private static void requireToolbarLabel(Node item) throws Rejected {
        // A native action's localized tooltip may repeat its fixed label; it is not a
        // free-standing extra content surface. Unknown distinct tooltip text is refused.
        if (!empty(item.tooltip) && !same(item.tooltip, item.description.toString())
                && !same(item.tooltip, item.text.toString())) throw rejected(Reason.UNKNOWN_CONTENT);
    }

    private static void requireArithmetic(CharSequence value, boolean allowEmpty) throws Rejected {
        if (empty(value) && !allowEmpty) throw rejected(Reason.UNSUPPORTED_FORMAT);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || "+-×÷^%√., \u00a0\u202f'".indexOf(c) >= 0) continue;
            throw rejected(Reason.UNSUPPORTED_FORMAT);
        }
    }

    private static void requireContainer(Node item, boolean scrollContainer) throws Rejected {
        if (!empty(item.text) || !empty(item.description) || !empty(item.tooltip) || item.node.isClickable() || item.node.isLongClickable()
                || (item.node.isScrollable() && !scrollContainer) || item.node.getChildCount() <= 0)
            throw rejected(Reason.UNKNOWN_CONTENT);
        requireContainerActions(item.node, scrollContainer);
    }

    private static boolean anchorRole(int anchor, String role) {
        switch (anchor) {
            case 0: return role.equals("android.view.ViewGroup") || role.equals("androidx.coordinatorlayout.widget.CoordinatorLayout")
                    // The fixed main_coordinator's app-bar accessibility delegate
                    // exposes ScrollView; requireContainer(false) still forbids
                    // actual scrolling/clicking/text; the per-node pass still rejects
                    // every unknown descendant.
                    || role.equals("android.widget.ScrollView");
            case 1: return role.equals("android.widget.LinearLayout") || role.equals("com.google.android.material.appbar.AppBarLayout")
                    || role.equals("org.fossify.commons.views.MyAppBarLayout");
            case 2: return role.equals("android.view.ViewGroup") || role.equals("androidx.appcompat.widget.Toolbar")
                    || role.equals("com.google.android.material.appbar.MaterialToolbar");
            case 3: return role.equals("android.widget.ScrollView") || role.equals("androidx.core.widget.NestedScrollView");
            case 4: return role.equals("android.widget.LinearLayout");
            default: return false;
        }
    }

    private static void requireStaticStructure(Node item) throws Rejected {
        if (item.id.equals("android:id/statusBarBackground") || item.id.equals("android:id/navigationBarBackground")) {
            if (!item.role.equals("android.view.View") || !empty(item.text) || !empty(item.description) || !empty(item.tooltip)
                    || item.node.isClickable() || item.node.isLongClickable() || item.node.isScrollable())
                throw rejected(Reason.UNKNOWN_CONTENT);
            requireContainerActions(item.node, false);
            return;
        }
        if (!item.id.isEmpty() && !item.id.equals("android:id/content")) throw rejected(Reason.UNKNOWN_CONTENT);
        boolean structural = item.role.equals("android.widget.FrameLayout") || item.role.equals("android.widget.LinearLayout")
                || item.role.equals("android.view.ViewGroup") || item.role.equals("androidx.appcompat.widget.ActionMenuView");
        if (!structural) throw rejected(Reason.UNKNOWN_CONTENT);
        requireContainer(item, false);
    }

    private static void requireLayout(SnapshotView snapshot, Node[] required, Node[] anchors, Node[] toolbar,
            Map<String, AccessibilityNodeInfo> visible) throws Exception {
        Node formula = required[0], result = required[1];
        if (formula.box.bottom > result.box.top || result.box.bottom > required[2].box.top
                || formula.box.left != result.box.left || formula.box.right != result.box.right)
            throw rejected(Reason.INVALID_KEYPAD);
        Rect calculator = new Rect(formula.box);
        calculator.union(result.box);
        for (int row = 0; row < 5; row++) {
            Node first = required[2 + row * 4];
            for (int col = 0; col < 4; col++) {
                Node item = required[2 + row * 4 + col];
                if (item.box.top != first.box.top || item.box.bottom != first.box.bottom
                        || item.box.left != required[2 + col].box.left || item.box.right != required[2 + col].box.right
                        || (col == 0 && item.box.left < formula.box.left)
                        || (col == 3 && item.box.right > formula.box.right)
                        || (col > 0 && required[1 + row * 4 + col].box.right > item.box.left)
                        || (row > 0 && required[2 + (row - 1) * 4 + col].box.bottom > item.box.top))
                    throw rejected(Reason.INVALID_KEYPAD);
                calculator.union(item.box);
            }
        }
        for (int i = 0; i < required.length; i++) for (int j = i + 1; j < required.length; j++)
            if (Rect.intersects(required[i].box, required[j].box)) throw rejected(Reason.INVALID_KEYPAD);
        Node holder = anchors[4], scroll = anchors[3], coordinator = anchors[0], appbar = anchors[1], bar = anchors[2];
        if (holder != null && (!holder.box.contains(calculator) || !snapshot.safeContent().contains(holder.box)))
            throw rejected(Reason.INVALID_KEYPAD);
        for (Node item : required) {
            descendantWhenExposed(holder, item);
            descendantWhenExposed(scroll, item);
            descendantWhenExposed(coordinator, item);
        }
        if (holder != null) descendantWhenExposed(scroll, holder);
        if (scroll != null) descendantWhenExposed(coordinator, scroll);
        if (bar != null) descendantWhenExposed(appbar, bar);
        if (appbar != null) descendantWhenExposed(coordinator, appbar);
        Rect toolbarRegion = bar == null ? new Rect(snapshot.safeContent().left, snapshot.safeContent().top,
                snapshot.safeContent().right, formula.box.top) : bar.box;
        if (toolbarRegion.isEmpty() || !snapshot.safeContent().contains(toolbarRegion)
                || toolbarRegion.bottom > formula.box.top) throw rejected(Reason.INVALID_KEYPAD);
        for (Node item : toolbar) if (item != null) {
            if (!toolbarRegion.contains(item.box)) throw rejected(Reason.UNKNOWN_CONTENT);
            descendantWhenExposed(bar, item);
            descendantWhenExposed(appbar, item);
            descendantWhenExposed(coordinator, item);
        }
        Node overflow = toolbar[3];
        if (overflow != null) {
            Node preceding = toolbar[2] == null ? toolbar[1] : toolbar[2];
            // In this fixed ordinary LTR toolbar, the overflow is the final native
            // leaf immediately after converter/settings, in the same action row.
            // An unrelated ImageView elsewhere cannot masquerade as the overflow.
            if (overflow.box.left != preceding.box.right
                    || overflow.box.right != toolbarRegion.right
                    || overflow.box.top != toolbar[0].box.top || overflow.box.bottom != toolbar[0].box.bottom
                    || preceding.box.top != toolbar[0].box.top || preceding.box.bottom != toolbar[0].box.bottom)
                throw rejected(Reason.UNKNOWN_CONTENT);
        }
        for (int i = 0; i < toolbar.length; i++) for (int j = i + 1; j < toolbar.length; j++)
            if (toolbar[i] != null && toolbar[j] != null && Rect.intersects(toolbar[i].box, toolbar[j].box))
                throw rejected(Reason.UNKNOWN_CONTENT);
        // Any exposed action-menu container is structural only inside the actual toolbar.
        for (Map.Entry<String, AccessibilityNodeInfo> entry : visible.entrySet()) {
            snapshot.checkCurrent();
            AccessibilityNodeInfo node = entry.getValue();
            if (same(node.getClassName(), "androidx.appcompat.widget.ActionMenuView")) {
                Rect box = new Rect(); node.getBoundsInScreen(box);
                if (!toolbarRegion.contains(box)) throw rejected(Reason.UNKNOWN_CONTENT);
            }
        }
    }

    private static void descendantWhenExposed(Node parent, Node child) throws Rejected {
        if (parent != null && (!child.path.startsWith(parent.path + ".") || !parent.box.contains(child.box)))
            throw rejected(Reason.INVALID_KEYPAD);
    }

    private static void requireContainerActions(AccessibilityNodeInfo node, boolean scroll) throws Rejected {
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null || actions.size() > MAX_ACTIONS) throw rejected(Reason.UNKNOWN_CONTENT);
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action == null) throw rejected(Reason.UNKNOWN_CONTENT);
            int id = action.getId();
            if (id == AccessibilityNodeInfo.ACTION_SELECT || id == AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
                    || id == AccessibilityNodeInfo.ACTION_FOCUS || id == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
                    || id == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS || id == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()) continue;
            if (scroll && (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
                    || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())) continue;
            throw rejected(Reason.UNKNOWN_CONTENT);
        }
    }

    // Fixed native text/menu roles may expose platform selection/navigation actions.
    // This image-page predicate grants none of those actions; it only requires actual
    // click capability for the known keypad/menu. Other tool authorization is separate.
    private static boolean hasAction(AccessibilityNodeInfo node, int expected) throws Rejected {
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null || actions.size() > MAX_ACTIONS) throw rejected(Reason.UNKNOWN_CONTENT);
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) if (action != null && action.getId() == expected) return true;
        return false;
    }

    private static final class Node {
        final AccessibilityNodeInfo node;
        final String path, id, role;
        final CharSequence text, description, tooltip;
        final Rect box = new Rect();
        Node(AccessibilityNodeInfo node, String path) throws Rejected {
            this.node = node; this.path = path;
            id = complete(node.getViewIdResourceName()).toString();
            role = complete(node.getClassName()).toString();
            text = complete(node.getText()); description = complete(node.getContentDescription());
            tooltip = complete(node.getTooltipText());
            node.getBoundsInScreen(box);
        }
    }

    private static CharSequence complete(CharSequence value) throws Rejected {
        if (value == null) return "";
        if (value.length() > MAX_TEXT) throw rejected(Reason.INCOMPLETE_SNAPSHOT);
        if (value instanceof Spanned && ((Spanned) value).getSpans(0, value.length(), ReplacementSpan.class).length != 0)
            throw rejected(Reason.UNKNOWN_CONTENT);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || Character.isSurrogate(c) || c == '\u2026') throw rejected(Reason.UNSUPPORTED_FORMAT);
        }
        return value;
    }

    private static String fingerprint(SnapshotView snapshot, Node[] required, Node[] anchors, Node[] toolbar) {
        MessageDigest digest = sha256();
        update(digest, POLICY_ID); update(digest, APK_SHA256);
        update(digest, snapshot.windowId() + ":" + snapshot.displayId() + ":" + snapshot.rotation());
        update(digest, snapshot.windowBounds().toShortString()); update(digest, snapshot.safeContent().toShortString());
        for (Node[] group : new Node[][]{required, anchors, toolbar}) for (Node item : group) if (item != null) {
            update(digest, item.id); update(digest, item.role); update(digest, item.path); update(digest, item.box.toShortString());
        }
        return hex(digest.digest());
    }

    private static Installation readInstallation(Context context, SettingsStore.AppGrant grant,
            RunControl control, long epoch, SettingsStore.ConfigurationLease configuration) throws Exception {
        if (Build.VERSION.SDK_INT < 34) throw rejected(Reason.UNSUPPORTED_API);
        if (context == null || control == null || grant == null || !PACKAGE_NAME.equals(grant.packageName)
                || !control.getProfileId().equals(grant.profileId) || grant.signers.size() != 1
                || !grant.signers.contains(CERT_SHA256)) throw rejected(Reason.INSTALLATION_MISMATCH);
        checkContext(control, epoch, configuration);
        PackageInfo info;
        try {
            SettingsStore.requirePersonalProfile(context);
            if (!grant.profileId.equals(SettingsStore.currentProfileId(context))) throw rejected(Reason.INSTALLATION_MISMATCH);
            info = context.getPackageManager().getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            throw rejected(Reason.INSTALLATION_UNVERIFIABLE);
        }
        checkContext(control, epoch, configuration);
        if (!PACKAGE_NAME.equals(info.packageName) || !VERSION_NAME.equals(info.versionName) || info.getLongVersionCode() != VERSION_CODE
                || info.applicationInfo == null || !info.applicationInfo.enabled || info.applicationInfo.uid != grant.uid
                || info.firstInstallTime <= 0 || info.firstInstallTime != grant.firstInstallTime || info.lastUpdateTime <= 0
                || (info.splitNames != null && info.splitNames.length != 0)
                || (info.applicationInfo.splitSourceDirs != null && info.applicationInfo.splitSourceDirs.length != 0)
                || info.signingInfo == null || info.signingInfo.hasMultipleSigners()) throw rejected(Reason.INSTALLATION_MISMATCH);
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1 || signers[0] == null
                || !CERT_SHA256.equals(hex(sha256().digest(signers[0].toByteArray())))) throw rejected(Reason.INSTALLATION_MISMATCH);
        String source = info.applicationInfo.sourceDir;
        if (source == null || source.length() > 4096 || !new File(source).isAbsolute()) throw rejected(Reason.INSTALLATION_MISMATCH);
        File file = new File(source);
        long bytes, modified;
        try {
            if (!file.isFile()) throw rejected(Reason.INSTALLATION_UNVERIFIABLE);
            bytes = file.length(); modified = file.lastModified();
        } catch (SecurityException unavailable) { throw rejected(Reason.INSTALLATION_UNVERIFIABLE); }
        if (bytes != APK_BYTES || modified <= 0) throw rejected(Reason.INSTALLATION_MISMATCH);
        checkContext(control, epoch, configuration);
        return new Installation(grant.profileId, source, grant.uid, info.firstInstallTime, info.lastUpdateTime, modified);
    }

    private static final class Installation {
        final String profile, sourcePath;
        final int uid;
        final long installed, updated, fileModified;
        Installation(String profile, String path, int uid, long installed, long updated, long fileModified) {
            this.profile = profile; this.sourcePath = path; this.uid = uid;
            this.installed = installed; this.updated = updated; this.fileModified = fileModified;
        }
        boolean sameAs(Installation other) {
            return other != null && profile.equals(other.profile) && sourcePath.equals(other.sourcePath)
                    && uid == other.uid && installed == other.installed && updated == other.updated && fileModified == other.fileModified;
        }
    }

    private static void putOnce(Node[] items, int index, Node value) throws Rejected {
        if (items[index] != null) throw rejected(Reason.INVALID_KEYPAD);
        items[index] = value;
    }
    private static int indexOf(String[] names, String fullId) {
        for (int i = 0; i < names.length; i++) if (fullId.equals(PREFIX + names[i])) return i;
        return -1;
    }
    private static boolean validPath(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_PATH || value.charAt(0) != 'n') return false;
        if (value.length() == 1) return true;
        boolean digit = false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') { if (i != 1 && !digit) return false; digit = false; }
            else if (c >= '0' && c <= '9' && i > 1) digit = true;
            else return false;
        }
        return digit;
    }
    private static boolean same(CharSequence value, String expected) { return value != null && expected.contentEquals(value); }
    private static boolean empty(CharSequence value) { return value == null || value.length() == 0; }
    private static void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = Character.forDigit((bytes[i] >>> 4) & 15, 16);
            chars[i * 2 + 1] = Character.forDigit(bytes[i] & 15, 16);
        }
        return new String(chars);
    }
    private static void checkContext(RunControl control, long epoch, SettingsStore.ConfigurationLease configuration) throws Exception {
        control.checkExecutionEpoch(epoch);
        if (!SettingsStore.isCurrent(configuration)) throw rejected(Reason.CONFIGURATION_CHANGED);
    }
    private static void requireWorker() throws Rejected {
        if (Looper.myLooper() == Looper.getMainLooper()) throw rejected(Reason.WRONG_THREAD);
    }
    private static Rejected rejected(Reason reason) { return new Rejected(reason); }
}
