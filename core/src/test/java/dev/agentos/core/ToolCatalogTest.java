package dev.agentos.core;

import com.google.gson.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ToolCatalogTest {
    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test public void modelSeesOnlyKnownToolsAndObjectSchemasRejectExtraArguments() {
        Set<String> names = new HashSet<>();
        for (JsonElement entry : ToolCatalog.definitions()) {
            JsonObject function = entry.getAsJsonObject().getAsJsonObject("function");
            assertTrue(names.add(function.get("name").getAsString()));
            JsonObject parameters = function.getAsJsonObject("parameters");
            assertEquals("object", parameters.get("type").getAsString());
            assertFalse(parameters.get("additionalProperties").getAsBoolean());
        }
        assertEquals(new HashSet<>(Arrays.asList("list_apps", "observe", "resolve_node_at", "launch_app", "click", "type_text", "scroll", "tap", "swipe", "back", "home", "wait", "take_screenshot")), names);
    }

    @Test public void rejectsUnknownMissingAndUnexpectedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("run_shell", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("click", object("{\"nodeId\":\"n1\"}")));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("click", object("{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\",\"ignorePermissions\":true}")));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("observe", object("{\"packageName\":\"secret.app\"}")));
    }

    @Test public void validatesScalarTypesAndGestureBoundsWithoutCoercingStrings() {
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("tap", object("{\"x\":\"12\",\"y\":30,\"expectedRevision\":\"r1\"}")));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("tap", object("{\"x\":1.5,\"y\":30,\"expectedRevision\":\"r1\"}")));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("tap", object("{\"x\":-1,\"y\":30,\"expectedRevision\":\"r1\"}")));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("scroll", object("{\"direction\":\"sideways\",\"expectedRevision\":\"r1\"}")));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("wait", object("{\"milliseconds\":60000}")));
        ToolCatalog.validate("tap", object("{\"x\":12,\"y\":30,\"expectedRevision\":\"r1\"}"));
        ToolCatalog.validate("wait", object("{\"milliseconds\":3000}"));
        ToolCatalog.validate("scroll", object("{\"direction\":\"forward\",\"expectedRevision\":\"r1\"}"));
    }

    @Test public void coordinateLookupIsReadOnlyAndRequiresAnExplicitScreenSpaceAndAction() {
        assertFalse(ToolCatalog.isMutation("resolve_node_at"));
        assertTrue(ToolCatalog.requiresRevision("resolve_node_at"));
        for (String action : Arrays.asList("click", "type_text", "scroll_forward", "scroll_backward")) {
            JsonObject args = object("{\"x\":24,\"y\":260,\"coordinateSpace\":\"screen_pixels\",\"expectedRevision\":\"r1\"}");
            args.addProperty("action", action);
            ToolCatalog.validate("resolve_node_at", args);
        }
        JsonObject valid = object("{\"x\":24,\"y\":260,\"action\":\"click\",\"coordinateSpace\":\"screen_pixels\",\"expectedRevision\":\"r1\"}");
        for (String missing : Arrays.asList("x", "y", "action", "coordinateSpace", "expectedRevision")) {
            JsonObject args = valid.deepCopy(); args.remove(missing);
            assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("resolve_node_at", args));
        }
        for (String invalidSpace : Arrays.asList("dp", "normalized", "image_pixels")) {
            JsonObject args = valid.deepCopy(); args.addProperty("coordinateSpace", invalidSpace);
            assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("resolve_node_at", args));
        }
        for (String invalidAction : Arrays.asList("tap", "swipe", "long_click", "scroll")) {
            JsonObject args = valid.deepCopy(); args.addProperty("action", invalidAction);
            assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("resolve_node_at", args));
        }
        JsonObject stringCoordinate = valid.deepCopy(); stringCoordinate.addProperty("x", "24");
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("resolve_node_at", stringCoordinate));
        JsonObject fractionalCoordinate = valid.deepCopy(); fractionalCoordinate.addProperty("x", 0.5);
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("resolve_node_at", fractionalCoordinate));
        JsonObject unauthorizedOverride = valid.deepCopy(); unauthorizedOverride.addProperty("ignoreOcclusion", true);
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validate("resolve_node_at", unauthorizedOverride));
    }
}
