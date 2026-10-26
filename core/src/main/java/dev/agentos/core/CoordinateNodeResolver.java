package dev.agentos.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Versioned, read-only screen-coordinate lookup into an authoritative local node observation.
 * Inputs must come from the local adapter, never from model-supplied claims about authorization.
 * Version 1 uses screen-coordinate pixels matching the adapter's observed node bounds,
 * on the default display with the screen's top-left origin, not physical panel pixels.
 * It does not convert image, normalized or density-independent coordinates, inject a gesture,
 * infer a clickable ancestor, or prove that unchanged node geometry means unchanged pixels.
 * The adapter's revision must cover display rotation and other relevant window geometry.
 * A candidate still needs ordinary action authorization, confirmation and final device guards.
 */
public final class CoordinateNodeResolver {
    public static final int CONTRACT_VERSION = 1;
    public static final int MAX_NODES = 300;
    public static final int MAX_BLOCKED_REGIONS = 64;
    public static final int MAX_COORDINATE = 16_384;

    private CoordinateNodeResolver() { }

    public enum NodeAction {
        CLICK("click"), TYPE_TEXT("type_text"),
        SCROLL_FORWARD("scroll_forward"), SCROLL_BACKWARD("scroll_backward");

        public final String wireName;
        NodeAction(String wireName) { this.wireName = wireName; }
        public static NodeAction fromWireName(String value) {
            for (NodeAction action : values()) if (action.wireName.equals(value)) return action;
            throw invalid();
        }
    }

    public enum NodeFlag { VISIBLE, ENABLED, SENSITIVE, HIGH_IMPACT, OBSCURED, CONTENT_TRUNCATED }
    public enum ObservationFlag { SCOPED, FOCUSED, TRUNCATED, SENSITIVE, HIGH_IMPACT, UNKNOWN_OCCLUSION }
    public enum Code {
        CANDIDATE, STALE_OBSERVATION, OUT_OF_SCOPE, INCOMPLETE_OBSERVATION,
        OUT_OF_BOUNDS, BLOCKED_TARGET, AMBIGUOUS_TARGET, NO_TARGET, UNSUPPORTED_ACTION
    }

    /** Positive-area screen-coordinate rectangle with exclusive right and bottom edges. */
    public static final class Bounds {
        public final int left, top, right, bottom;
        public Bounds(int left, int top, int right, int bottom) {
            if (!coordinate(left) || !coordinate(top) || !coordinate(right) || !coordinate(bottom)
                    || left >= right || top >= bottom) throw invalid();
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public boolean contains(int x, int y) { return x >= left && x < right && y >= top && y < bottom; }
        public boolean contains(Bounds other) {
            return other != null && other.left >= left && other.top >= top
                    && other.right <= right && other.bottom <= bottom;
        }
        public boolean intersects(Bounds other) {
            return other != null && left < other.right && other.left < right
                    && top < other.bottom && other.top < bottom;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Bounds)) return false;
            Bounds value = (Bounds) other;
            return left == value.left && top == value.top && right == value.right && bottom == value.bottom;
        }
        @Override public int hashCode() { return ((left * 31 + top) * 31 + right) * 31 + bottom; }
    }

    /** Immutable provenance, not a permission token. Non-default displays are unsupported in v1. */
    public static final class Binding {
        public final String taskId, profileId, packageName, revision;
        public final long executionEpoch;
        public final int displayId, windowId;
        public final Bounds windowBounds;

        public Binding(String taskId, long executionEpoch, String profileId, String packageName,
                       int displayId, int windowId, String revision, Bounds windowBounds) {
            this.taskId = identifier(taskId, 128); this.profileId = identifier(profileId, 128);
            this.packageName = identifier(packageName, 256); this.revision = identifier(revision, 128);
            if (executionEpoch < 1 || displayId != 0 || windowId < 0 || windowBounds == null) throw invalid();
            this.executionEpoch = executionEpoch; this.displayId = displayId;
            this.windowId = windowId; this.windowBounds = windowBounds;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Binding)) return false;
            Binding value = (Binding) other;
            return executionEpoch == value.executionEpoch && displayId == value.displayId && windowId == value.windowId
                    && taskId.equals(value.taskId) && profileId.equals(value.profileId) && packageName.equals(value.packageName)
                    && revision.equals(value.revision) && windowBounds.equals(value.windowBounds);
        }
        @Override public int hashCode() {
            int value = taskId.hashCode(); value = 31 * value + Long.hashCode(executionEpoch);
            value = 31 * value + profileId.hashCode(); value = 31 * value + packageName.hashCode();
            value = 31 * value + displayId; value = 31 * value + windowId;
            return (31 * value + revision.hashCode()) * 31 + windowBounds.hashCode();
        }
    }

    public static final class Node {
        public final String id;
        public final Bounds bounds;
        public final Set<NodeAction> actions;
        public final Set<NodeFlag> flags;

        public Node(String id, Bounds bounds, Set<NodeAction> actions, Set<NodeFlag> flags) {
            this.id = identifier(id, 128);
            if (bounds == null) throw invalid();
            this.bounds = bounds;
            this.actions = enumCopy(NodeAction.class, actions);
            this.flags = enumCopy(NodeFlag.class, flags);
        }
    }

    /** Collections are copied; malformed entries are rejected instead of silently omitted. */
    public static final class Observation {
        public final Binding binding;
        public final Bounds safeBounds;
        public final Set<ObservationFlag> flags;
        public final List<Node> nodes;
        /** Untrusted or external blockers: the whole candidate must avoid them for every action. */
        public final List<Bounds> blockedRegions;
        /** Locally verified task controls, never regions labelled trusted by a model. */
        public final List<Bounds> trustedControlRegions;

        public Observation(Binding binding, Bounds safeBounds, Set<ObservationFlag> flags,
                           List<Node> nodes, List<Bounds> blockedRegions) {
            this(binding, safeBounds, flags, nodes, blockedRegions, Collections.emptyList());
        }

        public Observation(Binding binding, Bounds safeBounds, Set<ObservationFlag> flags,
                           List<Node> nodes, List<Bounds> blockedRegions, List<Bounds> trustedControlRegions) {
            if (binding == null || !binding.windowBounds.contains(safeBounds)) throw invalid();
            this.binding = binding; this.safeBounds = safeBounds;
            this.flags = enumCopy(ObservationFlag.class, flags);
            this.nodes = boundedCopy(nodes, MAX_NODES);
            this.blockedRegions = boundedCopy(blockedRegions, MAX_BLOCKED_REGIONS);
            this.trustedControlRegions = boundedCopy(trustedControlRegions, MAX_BLOCKED_REGIONS);
            if (this.blockedRegions.size() + this.trustedControlRegions.size() > MAX_BLOCKED_REGIONS) throw invalid();
            Set<String> identifiers = new HashSet<>();
            for (Node node : this.nodes) if (!identifiers.add(node.id)) throw invalid();
            // Partly visible children may extend outside the window. A selected
            // candidate must fit the window; click/type must also fit safe content.
        }
    }

    public static final class Query {
        public final Binding expected;
        public final int x, y;
        public final NodeAction action;
        public Query(Binding expected, int x, int y, NodeAction action) {
            if (expected == null || action == null || x < 0 || y < 0
                    || x > MAX_COORDINATE || y > MAX_COORDINATE) throw invalid();
            this.expected = expected; this.x = x; this.y = y; this.action = action;
        }
    }

    /** A locator suggestion only. It has no action payload or execution/approval capability. */
    public static final class Candidate {
        public final String nodeId;
        public final NodeAction action;
        public final Binding binding;
        private Candidate(Node node, NodeAction action, Binding binding) {
            this.nodeId = node.id; this.action = action; this.binding = binding;
        }
    }

    public static final class Resolution {
        public final Code code;
        public final Candidate candidate;
        private Resolution(Code code, Candidate candidate) { this.code = code; this.candidate = candidate; }
    }

    public static Resolution resolve(Observation current, Query query, RunControl control) throws TaskControlException {
        if (current == null || query == null || control == null) throw invalid();
        control.checkExecutionEpoch(query.expected.executionEpoch);
        Resolution result = locate(current, query, control);
        control.checkExecutionEpoch(query.expected.executionEpoch);
        return result;
    }

    private static Resolution locate(Observation current, Query query, RunControl control) {
        Binding binding = current.binding;
        if (!binding.equals(query.expected) || !binding.taskId.equals(control.getTaskId())
                || !binding.profileId.equals(control.getProfileId())) return denied(Code.STALE_OBSERVATION);
        if (!current.flags.contains(ObservationFlag.SCOPED) || !current.flags.contains(ObservationFlag.FOCUSED))
            return denied(Code.OUT_OF_SCOPE);
        if (current.flags.contains(ObservationFlag.TRUNCATED)) return denied(Code.INCOMPLETE_OBSERVATION);
        if (current.flags.contains(ObservationFlag.SENSITIVE) || current.flags.contains(ObservationFlag.HIGH_IMPACT)
                || current.flags.contains(ObservationFlag.UNKNOWN_OCCLUSION)) return denied(Code.BLOCKED_TARGET);
        if (!binding.windowBounds.contains(query.x, query.y) || !current.safeBounds.contains(query.x, query.y))
            return denied(Code.OUT_OF_BOUNDS);
        for (Bounds blocked : current.blockedRegions) if (blocked.contains(query.x, query.y)) return denied(Code.BLOCKED_TARGET);
        for (Bounds controls : current.trustedControlRegions) if (controls.contains(query.x, query.y)) return denied(Code.BLOCKED_TARGET);

        Node target = null;
        int actionMatches = 0;
        boolean anyInteractive = false;
        for (Node node : current.nodes) {
            if (!node.bounds.contains(query.x, query.y)) continue;
            if (unsafe(node)) return denied(Code.BLOCKED_TARGET);
            if (!node.flags.contains(NodeFlag.VISIBLE) || node.actions.isEmpty()) continue;
            anyInteractive = true;
            if (!node.actions.contains(query.action)) continue;
            if (!node.flags.contains(NodeFlag.ENABLED)) return denied(Code.BLOCKED_TARGET);
            actionMatches++; target = node;
        }
        // This resolves an explicit node action, not touch hit-testing. A scroll-only
        // parent does not make a child's click ambiguous; two clickable targets do.
        if (actionMatches > 1) return denied(Code.AMBIGUOUS_TARGET);
        if (target == null) return denied(anyInteractive ? Code.UNSUPPORTED_ACTION : Code.NO_TARGET);
        if (!binding.windowBounds.contains(target.bounds))
            return denied(Code.OUT_OF_BOUNDS);
        boolean structuredScroll = query.action == NodeAction.SCROLL_FORWARD || query.action == NodeAction.SCROLL_BACKWARD;
        // A structured scroll container may extend under system bars. The lookup
        // point is still in safe content; click/type require the entire safe rectangle.
        if (!structuredScroll && !current.safeBounds.contains(target.bounds)) return denied(Code.OUT_OF_BOUNDS);
        for (Bounds blocked : current.blockedRegions) if (blocked.intersects(target.bounds)) return denied(Code.BLOCKED_TARGET);
        // Structured scrolling acts on a verified node, not on a screen touch point.
        // The adapter already knows which regions belong to its own trusted controls.
        if (!structuredScroll)
            for (Bounds controls : current.trustedControlRegions) if (controls.intersects(target.bounds)) return denied(Code.BLOCKED_TARGET);
        // A neutral-looking ancestor must not bypass a sensitive or obscured child.
        // Check the whole action rectangle, even if the query point itself is clear.
        for (Node node : current.nodes) if (unsafe(node) && node.bounds.intersects(target.bounds)) return denied(Code.BLOCKED_TARGET);
        return new Resolution(Code.CANDIDATE, new Candidate(target, query.action, binding));
    }

    private static boolean unsafe(Node node) {
        return node.flags.contains(NodeFlag.SENSITIVE) || node.flags.contains(NodeFlag.HIGH_IMPACT)
                || node.flags.contains(NodeFlag.OBSCURED) || node.flags.contains(NodeFlag.CONTENT_TRUNCATED);
    }
    private static Resolution denied(Code code) { return new Resolution(code, null); }
    private static boolean coordinate(int value) { return value >= -MAX_COORDINATE && value <= MAX_COORDINATE; }
    private static String identifier(String value, int maximum) {
        if (value == null || value.trim().isEmpty() || value.length() > maximum) throw invalid();
        return value;
    }
    private static <E extends Enum<E>> Set<E> enumCopy(Class<E> type, Set<E> source) {
        if (source == null) throw invalid();
        EnumSet<E> result = EnumSet.noneOf(type);
        for (E item : source) { if (item == null) throw invalid(); result.add(item); }
        return Collections.unmodifiableSet(result);
    }
    private static <E> List<E> boundedCopy(List<E> source, int maximum) {
        if (source == null || source.size() > maximum) throw invalid();
        List<E> result = new ArrayList<>();
        for (E item : source) {
            if (item == null || result.size() >= maximum) throw invalid();
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("节点定位输入的绑定、几何或有界观察无效。");
    }
}
