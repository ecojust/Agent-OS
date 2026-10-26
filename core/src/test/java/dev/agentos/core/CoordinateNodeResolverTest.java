package dev.agentos.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.agentos.core.CoordinateNodeResolver.*;
import static org.junit.Assert.*;

/** Screen coordinates identify candidates only; they never authorize or dispatch actions. */
public class CoordinateNodeResolverTest {
    private static final Bounds WINDOW = new Bounds(0, 0, 600, 800);
    private static final Bounds TARGET = new Bounds(20, 20, 80, 80);

    private static Set<NodeFlag> visibleEnabled() { return EnumSet.of(NodeFlag.VISIBLE, NodeFlag.ENABLED); }
    private static Set<ObservationFlag> scopedFocused() { return EnumSet.of(ObservationFlag.SCOPED, ObservationFlag.FOCUSED); }
    private static Binding binding(RunControl control) {
        return new Binding(control.getTaskId(), control.getExecutionEpoch(), control.getProfileId(), "demo.app", 0, 7, "revision-1", WINDOW);
    }
    private static Node target() { return new Node("target", TARGET, EnumSet.of(NodeAction.CLICK), visibleEnabled()); }
    private static Observation observed(Binding binding, Node... nodes) {
        return new Observation(binding, WINDOW, scopedFocused(), Arrays.asList(nodes), List.of());
    }
    private static Query query(Binding binding, int x, int y, NodeAction action) { return new Query(binding, x, y, action); }
    private static void assertCode(Code expected, Observation observation, Query query, RunControl control) throws Exception {
        Resolution result = resolve(observation, query, control);
        assertEquals(expected, result.code);
        if (expected == Code.CANDIDATE) assertNotNull(result.candidate);
        else assertNull("rejected resolutions must not expose a usable candidate", result.candidate);
    }

    @Test public void contractAndActionWireNamesAreBoundedAndRoundTripWithoutGuessing() {
        assertEquals(1, CONTRACT_VERSION); assertEquals(300, MAX_NODES);
        assertEquals(64, MAX_BLOCKED_REGIONS); assertEquals(16384, MAX_COORDINATE);
        assertEquals(4, NodeAction.values().length);
        Set<String> wireNames = new HashSet<>();
        for (NodeAction action : NodeAction.values()) {
            assertNotNull(action.wireName); assertFalse(action.wireName.isEmpty());
            assertTrue(wireNames.add(action.wireName));
            assertEquals(action, NodeAction.fromWireName(action.wireName));
        }
        assertThrows(IllegalArgumentException.class, () -> NodeAction.fromWireName(null));
        assertThrows(IllegalArgumentException.class, () -> NodeAction.fromWireName("tap"));
        assertThrows(IllegalArgumentException.class, () -> NodeAction.fromWireName("swipe"));
    }

    @Test public void uniqueCandidateCanOverlapNeutralLabelsWithoutChangingExecutionState() throws Exception {
        RunControl control = new RunControl("personal-test", TaskBudget.DEFAULT, () -> 0);
        Binding binding = binding(control);
        Node label = new Node("label", new Bounds(25, 25, 60, 60), Set.of(), visibleEnabled());
        Observation observation = observed(binding, target(), label);
        Resolution result = resolve(observation, query(binding, 30, 30, NodeAction.CLICK), control);
        assertEquals(Code.CANDIDATE, result.code); assertEquals("target", result.candidate.nodeId);
        assertEquals(NodeAction.CLICK, result.candidate.action); assertEquals(binding, result.candidate.binding);
        assertEquals(0, control.getDispatchAttempts()); assertFalse(control.hasInFlightDispatch());
        assertEquals(0, control.budgetSnapshot().toolCalls); assertEquals(0, control.budgetSnapshot().modelRounds);
        assertEquals(0, control.budgetSnapshot().activeMillis);
        assertFalse(control.isPaused()); assertFalse(control.isCancelled());
    }

    @Test public void everyExpectedBindingFieldMustMatchTheObservedBinding() throws Exception {
        RunControl control = new RunControl(); Binding actual = binding(control);
        List<Binding> alternatives = List.of(
                new Binding("another-task", actual.executionEpoch, actual.profileId, actual.packageName, 0, actual.windowId, actual.revision, WINDOW),
                new Binding(actual.taskId, actual.executionEpoch, "another-profile", actual.packageName, 0, actual.windowId, actual.revision, WINDOW),
                new Binding(actual.taskId, actual.executionEpoch, actual.profileId, "other.app", 0, actual.windowId, actual.revision, WINDOW),
                new Binding(actual.taskId, actual.executionEpoch, actual.profileId, actual.packageName, 0, actual.windowId + 1, actual.revision, WINDOW),
                new Binding(actual.taskId, actual.executionEpoch, actual.profileId, actual.packageName, 0, actual.windowId, "revision-2", WINDOW),
                new Binding(actual.taskId, actual.executionEpoch, actual.profileId, actual.packageName, 0, actual.windowId, actual.revision, new Bounds(0, 0, 601, 800)));
        for (Binding expected : alternatives) {
            assertCode(Code.STALE_OBSERVATION, observed(actual, target()), query(expected, 30, 30, NodeAction.CLICK), control);
        }
        Binding mismatchedEpoch = new Binding(actual.taskId, actual.executionEpoch + 1, actual.profileId,
                actual.packageName, 0, actual.windowId, actual.revision, WINDOW);
        assertCode(Code.STALE_OBSERVATION, observed(mismatchedEpoch, target()), query(actual, 30, 30, NodeAction.CLICK), control);
        assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class,
                () -> resolve(observed(actual, target()), query(mismatchedEpoch, 30, 30, NodeAction.CLICK), control)).getCode());
        assertThrows(IllegalArgumentException.class, () -> new Binding(actual.taskId, actual.executionEpoch,
                actual.profileId, actual.packageName, 1, actual.windowId, actual.revision, WINDOW));
    }

    @Test public void matchingInputBindingsStillCannotOverrideTheActualTaskProfileOrEpoch() throws Exception {
        RunControl control = new RunControl(); Binding actual = binding(control);
        List<Binding> forged = List.of(
                new Binding("another-task", actual.executionEpoch, actual.profileId, actual.packageName, 0, actual.windowId, actual.revision, WINDOW),
                new Binding(actual.taskId, actual.executionEpoch, "another-profile", actual.packageName, 0, actual.windowId, actual.revision, WINDOW));
        for (Binding binding : forged) {
            assertCode(Code.STALE_OBSERVATION, observed(binding, target()), query(binding, 30, 30, NodeAction.CLICK), control);
        }
        control.pause(); control.resume();
        assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class,
                () -> resolve(observed(actual, target()), query(actual, 30, 30, NodeAction.CLICK), control)).getCode());
        Binding fresh = binding(control);
        assertCode(Code.CANDIDATE, observed(fresh, target()), query(fresh, 30, 30, NodeAction.CLICK), control);
    }

    @Test public void halfOpenEdgesSelectOnlyTheNodeOnTheIncludedSide() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        Observation observation = observed(binding, target());
        for (int[] point : new int[][] {{20, 20}, {79, 20}, {20, 79}, {79, 79}}) {
            assertCode(Code.CANDIDATE, observation, query(binding, point[0], point[1], NodeAction.CLICK), control);
        }
        for (int[] point : new int[][] {{19, 20}, {20, 19}, {80, 20}, {20, 80}, {80, 80}}) {
            assertCode(Code.NO_TARGET, observation, query(binding, point[0], point[1], NodeAction.CLICK), control);
        }
        Node neighbor = new Node("neighbor", new Bounds(80, 20, 120, 80), EnumSet.of(NodeAction.CLICK), visibleEnabled());
        Resolution edge = resolve(observed(binding, target(), neighbor), query(binding, 80, 30, NodeAction.CLICK), control);
        assertEquals(Code.CANDIDATE, edge.code); assertEquals("neighbor", edge.candidate.nodeId);
        for (int[] point : new int[][] {{600, 20}, {20, 800}, {MAX_COORDINATE, 20}}) {
            assertCode(Code.OUT_OF_BOUNDS, observation, query(binding, point[0], point[1], NodeAction.CLICK), control);
        }
        Bounds shiftedWindow = new Bounds(100, 200, 700, 1000);
        Binding shiftedBinding = new Binding(binding.taskId, binding.executionEpoch, binding.profileId,
                binding.packageName, binding.displayId, binding.windowId, "shifted-revision", shiftedWindow);
        Node shiftedTarget = new Node("shifted-target", new Bounds(120, 220, 180, 280), EnumSet.of(NodeAction.CLICK), visibleEnabled());
        Node unrelatedPartialChild = new Node("partly-visible-child", new Bounds(90, 600, 110, 700),
                EnumSet.of(NodeAction.SCROLL_FORWARD), visibleEnabled());
        Observation shifted = new Observation(shiftedBinding, shiftedWindow, scopedFocused(),
                List.of(shiftedTarget, unrelatedPartialChild), List.of());
        Resolution global = resolve(shifted, query(shiftedBinding, 130, 230, NodeAction.CLICK), control);
        assertEquals(Code.CANDIDATE, global.code); assertEquals("shifted-target", global.candidate.nodeId);
        assertCode(Code.OUT_OF_BOUNDS, shifted, query(shiftedBinding, 30, 30, NodeAction.CLICK), control);
        assertCode(Code.OUT_OF_BOUNDS, shifted, query(shiftedBinding, 105, 650, NodeAction.SCROLL_FORWARD), control);
    }

    @Test public void queryAndEntireCandidateMustFitBothSafeAndWindowBounds() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        Bounds safe = new Bounds(10, 10, 590, 790);
        Observation normal = new Observation(binding, safe, scopedFocused(), List.of(target()), List.of());
        assertCode(Code.OUT_OF_BOUNDS, normal, query(binding, 5, 30, NodeAction.CLICK), control);
        for (Bounds partial : List.of(new Bounds(-1, 20, 80, 80), new Bounds(0, 20, 80, 80),
                new Bounds(20, 0, 80, 80), new Bounds(20, 20, 600, 80), new Bounds(20, 20, 80, 800))) {
            Node node = new Node("partial", partial, EnumSet.of(NodeAction.CLICK), visibleEnabled());
            Observation observation = new Observation(binding, safe, scopedFocused(), List.of(node), List.of());
            assertCode(Code.OUT_OF_BOUNDS, observation, query(binding, 30, 30, NodeAction.CLICK), control);
        }
        Node fullWindow = new Node("full-window", WINDOW, EnumSet.allOf(NodeAction.class), visibleEnabled());
        Observation inset = new Observation(binding, safe, scopedFocused(), List.of(fullWindow), List.of());
        for (NodeAction action : NodeAction.values()) {
            boolean scroll = action == NodeAction.SCROLL_FORWARD || action == NodeAction.SCROLL_BACKWARD;
            assertCode(scroll ? Code.CANDIDATE : Code.OUT_OF_BOUNDS, inset, query(binding, 30, 30, action), control);
            assertCode(Code.OUT_OF_BOUNDS, inset, query(binding, 5, 30, action), control);
        }
        Node beyondWindow = new Node("beyond-window", new Bounds(-1, 0, 600, 800),
                EnumSet.of(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD), visibleEnabled());
        Observation outside = new Observation(binding, safe, scopedFocused(), List.of(beyondWindow), List.of());
        for (NodeAction action : List.of(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD)) {
            assertCode(Code.OUT_OF_BOUNDS, outside, query(binding, 30, 30, action), control);
        }
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding,
                new Bounds(-1, 0, 600, 800), scopedFocused(), List.of(target()), List.of()));
    }

    @Test public void intersectingBlockersRejectTheWholeCandidateEvenWhenTheQueryPointIsClear() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        Bounds blocker = new Bounds(70, 30, 100, 60);
        Observation partiallyBlocked = new Observation(binding, WINDOW, scopedFocused(), List.of(target()), List.of(blocker));
        assertCode(Code.BLOCKED_TARGET, partiallyBlocked, query(binding, 30, 30, NodeAction.CLICK), control);
        assertCode(Code.BLOCKED_TARGET, partiallyBlocked, query(binding, 75, 40, NodeAction.CLICK), control);
        Observation touchingOnly = new Observation(binding, WINDOW, scopedFocused(), List.of(target()),
                List.of(new Bounds(80, 20, 100, 80), new Bounds(20, 80, 80, 100)));
        assertCode(Code.CANDIDATE, touchingOnly, query(binding, 30, 30, NodeAction.CLICK), control);
    }

    @Test public void trustedControlsBlockAllQueryPointsButOnlyClickAndTextRequireTheWholeNodeToAvoidThem() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        Node container = new Node("container", new Bounds(10, 10, 120, 120), EnumSet.allOf(NodeAction.class), visibleEnabled());
        Observation observation = new Observation(binding, WINDOW, scopedFocused(), List.of(container), List.of(),
                List.of(new Bounds(60, 60, 90, 90)));
        for (NodeAction action : NodeAction.values()) {
            assertCode(Code.BLOCKED_TARGET, observation, query(binding, 70, 70, action), control);
            assertCode(Code.BLOCKED_TARGET, observation, query(binding, 60, 60, action), control);
            boolean scroll = action == NodeAction.SCROLL_FORWARD || action == NodeAction.SCROLL_BACKWARD;
            assertCode(scroll ? Code.CANDIDATE : Code.BLOCKED_TARGET,
                    observation, query(binding, 30, 30, action), control);
            // The right edge lies outside the half-open control region.
            assertCode(scroll ? Code.CANDIDATE : Code.BLOCKED_TARGET,
                    observation, query(binding, 90, 70, action), control);
        }
    }

    @Test public void trustedScrollExceptionNeverWeakensUnknownOrSensitiveBlockers() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        Node container = new Node("container", new Bounds(10, 10, 120, 120),
                EnumSet.of(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD), visibleEnabled());
        Bounds controlRegion = new Bounds(60, 60, 90, 90);
        for (NodeAction action : List.of(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD)) {
            assertCode(Code.CANDIDATE, new Observation(binding, WINDOW, scopedFocused(), List.of(container), List.of(), List.of(controlRegion)),
                    query(binding, 30, 30, action), control);
            // The same rectangle also reported as unknown must retain its stricter meaning.
            assertCode(Code.BLOCKED_TARGET, new Observation(binding, WINDOW, scopedFocused(), List.of(container),
                    List.of(controlRegion), List.of(controlRegion)), query(binding, 30, 30, action), control);
            for (NodeFlag flag : List.of(NodeFlag.SENSITIVE, NodeFlag.HIGH_IMPACT, NodeFlag.OBSCURED, NodeFlag.CONTENT_TRUNCATED)) {
                Node unsafeLabel = new Node("unsafe-label", new Bounds(80, 80, 100, 100), Set.of(), EnumSet.of(flag));
                assertCode(Code.BLOCKED_TARGET, new Observation(binding, WINDOW, scopedFocused(),
                        List.of(container, unsafeLabel), List.of(), List.of(controlRegion)), query(binding, 30, 30, action), control);
            }
        }
    }

    @Test public void trustedControlRegionsAreDefensivelyCopiedAndShareTheCombinedRegionBudget() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        Bounds original = new Bounds(200, 200, 220, 220);
        List<Bounds> controls = new ArrayList<>(List.of(original));
        Observation copied = new Observation(binding, WINDOW, scopedFocused(), List.of(target()), List.of(), controls);
        controls.clear(); controls.add(TARGET);
        assertEquals(List.of(original), copied.trustedControlRegions);
        assertCode(Code.CANDIDATE, copied, query(binding, 30, 30, NodeAction.CLICK), control);
        assertThrows(UnsupportedOperationException.class, () -> copied.trustedControlRegions.add(TARGET));
        assertTrue(observed(binding, target()).trustedControlRegions.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(), List.of(target()), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(), List.of(target()), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(),
                List.of(target()), List.of(), Arrays.asList(original, null)));
        List<Bounds> unknown = new ArrayList<>(), trusted = new ArrayList<>();
        for (int i = 0; i < MAX_BLOCKED_REGIONS / 2; i++) {
            unknown.add(new Bounds(200 + i, 200, 201 + i, 220));
            trusted.add(new Bounds(300 + i, 200, 301 + i, 220));
        }
        Observation atLimit = new Observation(binding, WINDOW, scopedFocused(), List.of(target()), unknown, trusted);
        assertCode(Code.CANDIDATE, atLimit, query(binding, 30, 30, NodeAction.CLICK), control);
        trusted.add(new Bounds(400, 200, 401, 220));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(), List.of(target()), unknown, trusted));
    }

    @Test public void unsafeNodesCannotBeBypassedThroughOverlappingParentsOrInvisibleLabels() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        for (NodeFlag dangerous : List.of(NodeFlag.SENSITIVE, NodeFlag.HIGH_IMPACT, NodeFlag.OBSCURED, NodeFlag.CONTENT_TRUNCATED)) {
            Set<NodeFlag> targetFlags = visibleEnabled(); targetFlags.add(dangerous);
            Node unsafeTarget = new Node("unsafe", TARGET, EnumSet.of(NodeAction.CLICK), targetFlags);
            assertCode(Code.BLOCKED_TARGET, observed(binding, unsafeTarget), query(binding, 30, 30, NodeAction.CLICK), control);
            // The label is neither visible nor actionable, and the point itself misses it.
            Node unsafeLabel = new Node("unsafe-label", new Bounds(70, 30, 90, 60), Set.of(), EnumSet.of(dangerous));
            assertCode(Code.BLOCKED_TARGET, observed(binding, target(), unsafeLabel), query(binding, 30, 30, NodeAction.CLICK), control);
        }
        Node disabled = new Node("disabled", TARGET, EnumSet.of(NodeAction.CLICK), EnumSet.of(NodeFlag.VISIBLE));
        assertCode(Code.BLOCKED_TARGET, observed(binding, disabled), query(binding, 30, 30, NodeAction.CLICK), control);
        Node invisible = new Node("invisible", TARGET, EnumSet.of(NodeAction.CLICK), EnumSet.of(NodeFlag.ENABLED));
        assertCode(Code.CANDIDATE, observed(binding, target(), invisible), query(binding, 30, 30, NodeAction.CLICK), control);
    }

    @Test public void onlyNodesSupportingTheRequestedActionCompeteForAUniqueCandidate() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        for (NodeAction otherAction : NodeAction.values()) {
            Node child = new Node("child", new Bounds(25, 25, 50, 50), EnumSet.of(otherAction), visibleEnabled());
            for (Observation observation : List.of(observed(binding, target(), child), observed(binding, child, target()))) {
                if (otherAction == NodeAction.CLICK) {
                    assertCode(Code.AMBIGUOUS_TARGET, observation, query(binding, 30, 30, NodeAction.CLICK), control);
                } else {
                    Resolution click = resolve(observation, query(binding, 30, 30, NodeAction.CLICK), control);
                    assertEquals(Code.CANDIDATE, click.code); assertEquals("target", click.candidate.nodeId);
                    Resolution other = resolve(observation, query(binding, 30, 30, otherAction), control);
                    assertEquals(Code.CANDIDATE, other.code); assertEquals("child", other.candidate.nodeId);
                    assertEquals(otherAction, other.candidate.action);
                }
            }
        }
        Node scrollParent = new Node("scroll-parent", new Bounds(10, 10, 100, 100),
                EnumSet.of(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD), visibleEnabled());
        Resolution nestedClick = resolve(observed(binding, scrollParent, target()), query(binding, 30, 30, NodeAction.CLICK), control);
        assertEquals(Code.CANDIDATE, nestedClick.code); assertEquals("target", nestedClick.candidate.nodeId);
        assertCode(Code.UNSUPPORTED_ACTION, observed(binding, target()), query(binding, 30, 30, NodeAction.TYPE_TEXT), control);
        Node label = new Node("label", TARGET, Set.of(), visibleEnabled());
        assertCode(Code.NO_TARGET, observed(binding, label), query(binding, 30, 30, NodeAction.CLICK), control);
        assertCode(Code.NO_TARGET, observed(binding), query(binding, 30, 30, NodeAction.CLICK), control);
        for (NodeAction action : NodeAction.values()) {
            Node node = new Node("action", TARGET, EnumSet.of(action), visibleEnabled());
            Resolution resolved = resolve(observed(binding, node), query(binding, 30, 30, action), control);
            assertEquals(Code.CANDIDATE, resolved.code); assertEquals(action, resolved.candidate.action);
            Node sameAction = new Node("same-action", new Bounds(25, 25, 50, 50), EnumSet.of(action), visibleEnabled());
            assertCode(Code.AMBIGUOUS_TARGET, observed(binding, node, sameAction), query(binding, 30, 30, action), control);
        }
    }

    @Test public void incompleteUnscopedUnfocusedAndGloballyUnsafeObservationsNeverYieldCandidates() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        for (ObservationFlag missing : List.of(ObservationFlag.SCOPED, ObservationFlag.FOCUSED)) {
            Set<ObservationFlag> flags = scopedFocused(); flags.remove(missing);
            assertCode(Code.OUT_OF_SCOPE, new Observation(binding, WINDOW, flags, List.of(target()), List.of()),
                    query(binding, 30, 30, NodeAction.CLICK), control);
        }
        for (ObservationFlag rejected : List.of(ObservationFlag.TRUNCATED, ObservationFlag.SENSITIVE,
                ObservationFlag.HIGH_IMPACT, ObservationFlag.UNKNOWN_OCCLUSION)) {
            Set<ObservationFlag> flags = scopedFocused(); flags.add(rejected);
            assertCode(rejected == ObservationFlag.TRUNCATED ? Code.INCOMPLETE_OBSERVATION : Code.BLOCKED_TARGET,
                    new Observation(binding, WINDOW, flags, List.of(target()), List.of()),
                    query(binding, 30, 30, NodeAction.CLICK), control);
        }
    }

    @Test public void constructorsRejectInvalidGeometryNullsDuplicatesAndOversizedCollections() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        for (int[] rectangle : new int[][] {{1, 1, 1, 2}, {1, 1, 2, 1}, {2, 1, 1, 2},
                {-16385, 0, 1, 1}, {0, 0, 16385, 1}, {Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 1}}) {
            assertThrows(IllegalArgumentException.class, () -> new Bounds(rectangle[0], rectangle[1], rectangle[2], rectangle[3]));
        }
        assertEquals(new Bounds(-MAX_COORDINATE, -MAX_COORDINATE, MAX_COORDINATE, MAX_COORDINATE),
                new Bounds(-16384, -16384, 16384, 16384));
        assertThrows(IllegalArgumentException.class, () -> query(binding, -1, 30, NodeAction.CLICK));
        assertThrows(IllegalArgumentException.class, () -> query(binding, 30, 16385, NodeAction.CLICK));
        assertThrows(IllegalArgumentException.class, () -> query(binding, 30, 30, null));
        assertThrows(IllegalArgumentException.class, () -> query(null, 30, 30, NodeAction.CLICK));
        assertThrows(IllegalArgumentException.class, () -> new Node(null, TARGET, Set.of(), visibleEnabled()));
        assertThrows(IllegalArgumentException.class, () -> new Node("", TARGET, Set.of(), visibleEnabled()));
        assertThrows(IllegalArgumentException.class, () -> new Node("node", null, Set.of(), visibleEnabled()));
        assertThrows(IllegalArgumentException.class, () -> new Node("node", TARGET, null, visibleEnabled()));
        assertThrows(IllegalArgumentException.class, () -> new Node("node", TARGET, Set.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new Observation(null, WINDOW, scopedFocused(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, null, scopedFocused(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> observed(binding, target(), null));
        Node duplicate = new Node("target", new Bounds(200, 200, 220, 220), Set.of(), visibleEnabled());
        assertThrows(IllegalArgumentException.class, () -> observed(binding, target(), duplicate));
        List<Node> nodes = new ArrayList<>(); nodes.add(target());
        for (int i = 1; i < MAX_NODES; i++) nodes.add(new Node("label-" + i, new Bounds(200, 200, 220, 220), Set.of(), visibleEnabled()));
        List<Bounds> blockers = new ArrayList<>();
        for (int i = 0; i < MAX_BLOCKED_REGIONS; i++) blockers.add(new Bounds(200 + i, 200, 201 + i, 220));
        Observation atLimit = new Observation(binding, WINDOW, scopedFocused(), nodes, blockers);
        assertCode(Code.CANDIDATE, atLimit, query(binding, 30, 30, NodeAction.CLICK), control);
        nodes.add(new Node("over-limit", TARGET, Set.of(), visibleEnabled()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(), nodes, List.of()));
        blockers.add(new Bounds(400, 200, 401, 220));
        assertThrows(IllegalArgumentException.class, () -> new Observation(binding, WINDOW, scopedFocused(), List.of(), blockers));
    }

    @Test public void constructorCopiesAndPublicCollectionsCannotBeChangedAfterValidation() throws Exception {
        RunControl control = new RunControl(); Binding binding = binding(control);
        Set<NodeAction> actions = EnumSet.of(NodeAction.CLICK); Set<NodeFlag> nodeFlags = visibleEnabled();
        Node node = new Node("target", TARGET, actions, nodeFlags);
        List<Node> nodes = new ArrayList<>(List.of(node)); List<Bounds> blockers = new ArrayList<>();
        Set<ObservationFlag> flags = scopedFocused();
        Observation observation = new Observation(binding, WINDOW, flags, nodes, blockers);
        actions.clear(); actions.add(NodeAction.TYPE_TEXT); nodeFlags.clear(); flags.clear(); nodes.clear(); blockers.add(TARGET);
        assertCode(Code.CANDIDATE, observation, query(binding, 30, 30, NodeAction.CLICK), control);
        assertEquals(Set.of(NodeAction.CLICK), node.actions); assertEquals(visibleEnabled(), node.flags);
        assertEquals(scopedFocused(), observation.flags); assertEquals(1, observation.nodes.size()); assertTrue(observation.blockedRegions.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> node.actions.add(NodeAction.TYPE_TEXT));
        assertThrows(UnsupportedOperationException.class, node.flags::clear);
        assertThrows(UnsupportedOperationException.class, observation.flags::clear);
        assertThrows(UnsupportedOperationException.class, () -> observation.nodes.add(target()));
        assertThrows(UnsupportedOperationException.class, () -> observation.blockedRegions.add(TARGET));
        Bounds sameWindow = new Bounds(0, 0, 600, 800);
        Binding sameBinding = new Binding(binding.taskId, binding.executionEpoch, binding.profileId,
                binding.packageName, binding.displayId, binding.windowId, binding.revision, sameWindow);
        assertEquals(WINDOW, sameWindow); assertEquals(WINDOW.hashCode(), sameWindow.hashCode());
        assertEquals(binding, sameBinding); assertEquals(binding.hashCode(), sameBinding.hashCode());
        assertNotEquals(WINDOW, new Bounds(1, 0, 600, 800));
    }

    @Test public void cancelledPausedAndExhaustedControlsRejectBeforeReturningACandidate() throws Exception {
        RunControl cancelled = new RunControl(); Binding cancelledBinding = binding(cancelled); cancelled.cancel();
        assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                () -> resolve(observed(cancelledBinding, target()), query(cancelledBinding, 30, 30, NodeAction.CLICK), cancelled)).getCode());
        RunControl paused = new RunControl(); Binding pausedBinding = binding(paused); paused.pause();
        assertEquals("TASK_PAUSED", assertThrows(TaskControlException.class,
                () -> resolve(observed(pausedBinding, target()), query(pausedBinding, 30, 30, NodeAction.CLICK), paused)).getCode());
        AtomicLong clock = new AtomicLong();
        RunControl exhausted = new RunControl("personal-test", new TaskBudget(30, 100, 50), clock::get);
        Binding exhaustedBinding = binding(exhausted); clock.set(TimeUnit.MILLISECONDS.toNanos(50));
        assertEquals("BUDGET_EXCEEDED", assertThrows(TaskControlException.class,
                () -> resolve(observed(exhaustedBinding, target()), query(exhaustedBinding, 30, 30, NodeAction.CLICK), exhausted)).getCode());
    }

    @Test public void finalControlCheckAlsoBoundsBothCandidateAndNoTargetResults() throws Exception {
        for (boolean hasTarget : new boolean[] {true, false}) {
            AtomicInteger reads = new AtomicInteger();
            // Constructor and entry check see time zero; the exit check exhausts the budget.
            RunControl control = new RunControl("personal-test", new TaskBudget(30, 100, 50),
                    () -> reads.getAndIncrement() < 2 ? 0 : TimeUnit.MILLISECONDS.toNanos(50));
            Binding binding = binding(control);
            Observation observation = hasTarget ? observed(binding, target()) : observed(binding);
            assertEquals("BUDGET_EXCEEDED", assertThrows(TaskControlException.class,
                    () -> resolve(observation, query(binding, 30, 30, NodeAction.CLICK), control)).getCode());
            assertEquals(0, control.getDispatchAttempts()); assertFalse(control.hasInFlightDispatch());
        }
    }
}
