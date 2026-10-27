package dev.agentos.core;

import com.google.gson.JsonElement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.*;

public class ActionConfirmationPolicyTest {
    @Test public void defaultModeConfirmsEveryCataloguedMutationAndImageDisclosure() {
        ActionConfirmationPolicy policy = new ActionConfirmationPolicy();
        assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, policy.getMode());
        for (String tool : cataloguedTools()) {
            boolean expected = ToolCatalog.isMutation(tool) || "take_screenshot".equals(tool);
            assertEquals(tool, expected, policy.requiresConfirmation(tool));
        }
    }

    @Test public void continuousNavigationHasAnExplicitDecisionForEveryCurrentTool() {
        Map<String, Boolean> decisions = new LinkedHashMap<>();
        decisions.put("list_apps", false);
        decisions.put("observe", false);
        decisions.put("resolve_node_at", false);
        decisions.put("launch_app", false);
        decisions.put("click", true);
        decisions.put("type_text", true);
        decisions.put("scroll", false);
        decisions.put("tap", true);
        decisions.put("swipe", true);
        decisions.put("back", true);
        decisions.put("home", false);
        decisions.put("wait", false);
        decisions.put("take_screenshot", true);
        // New protocol tools require an explicit review even though the policy defaults
        // to confirmation for unknown names.
        assertEquals(cataloguedTools(), decisions.keySet());

        ActionConfirmationPolicy policy = new ActionConfirmationPolicy(
                ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION);
        assertEquals(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION, policy.getMode());
        for (Map.Entry<String, Boolean> decision : decisions.entrySet()) {
            assertEquals(decision.getKey(), decision.getValue().booleanValue(),
                    policy.requiresConfirmation(decision.getKey()));
        }
    }

    @Test public void switchingModesOnlyChangesTheThreeReviewedNavigationDecisions() {
        ActionConfirmationPolicy confirm = new ActionConfirmationPolicy(ActionConfirmationPolicy.Mode.CONFIRM_ALL);
        ActionConfirmationPolicy navigation = new ActionConfirmationPolicy(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION);
        Set<String> changed = new HashSet<>();
        for (String tool : cataloguedTools()) {
            if (confirm.requiresConfirmation(tool) != navigation.requiresConfirmation(tool)) changed.add(tool);
        }
        assertEquals(Set.of("launch_app", "scroll", "home"), changed);
    }

    @Test public void screenshotDisclosureIsNotExemptedAsANonMutatingTool() {
        assertFalse(ToolCatalog.isMutation("take_screenshot"));
        for (ActionConfirmationPolicy.Mode mode : ActionConfirmationPolicy.Mode.values()) {
            assertTrue(new ActionConfirmationPolicy(mode).requiresConfirmation("take_screenshot"));
        }
    }

    @Test public void backAndGenericWritesKeepConfirmationInBothModes() {
        for (ActionConfirmationPolicy.Mode mode : ActionConfirmationPolicy.Mode.values()) {
            ActionConfirmationPolicy policy = new ActionConfirmationPolicy(mode);
            for (String tool : new String[]{"back", "click", "type_text", "tap", "swipe"}) {
                assertTrue(mode + ": " + tool, policy.requiresConfirmation(tool));
            }
        }
    }

    @Test public void unknownAndMalformedNamesNeverGainAnExemption() {
        String[] names = {null, "", " ", "\n", "future_read_tool", "future_write_tool", "run_shell",
                "Launch_App", "SCROLL", " home", "home ", "observe\n", "resolve_node_at\0",
                "launch_app.confirmed", "scroll_forward", "back_to_home", "take_screenshot_readonly",
                "h\u043eme", "\uFF48ome"};
        for (ActionConfirmationPolicy.Mode mode : ActionConfirmationPolicy.Mode.values()) {
            ActionConfirmationPolicy policy = new ActionConfirmationPolicy(mode);
            for (String tool : names) assertTrue(mode + ": " + tool, policy.requiresConfirmation(tool));
        }
    }

    @Test public void decisionsCannotChangeAnotherPolicyInstanceOrItsMode() {
        ActionConfirmationPolicy strict = new ActionConfirmationPolicy();
        ActionConfirmationPolicy navigation = new ActionConfirmationPolicy(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION);
        assertFalse(navigation.requiresConfirmation("launch_app"));
        assertTrue(strict.requiresConfirmation("launch_app"));
        assertTrue(navigation.requiresConfirmation("back"));
        assertFalse(navigation.requiresConfirmation("launch_app"));
        assertEquals(ActionConfirmationPolicy.Mode.CONFIRM_ALL, strict.getMode());
        assertEquals(ActionConfirmationPolicy.Mode.CONTINUOUS_NAVIGATION, navigation.getMode());
    }

    @Test public void anAbsentExplicitModeIsRejectedInsteadOfEnablingNavigation() {
        assertThrows(NullPointerException.class, () -> new ActionConfirmationPolicy(null));
    }

    private static Set<String> cataloguedTools() {
        Set<String> result = new HashSet<>();
        for (JsonElement definition : ToolCatalog.definitions()) {
            assertTrue(result.add(definition.getAsJsonObject().getAsJsonObject("function").get("name").getAsString()));
        }
        return result;
    }
}
