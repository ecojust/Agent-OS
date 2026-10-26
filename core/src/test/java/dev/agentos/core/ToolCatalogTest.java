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
        assertEquals(new HashSet<>(Arrays.asList("list_apps", "observe", "launch_app", "click", "type_text", "scroll", "tap", "swipe", "back", "home", "wait", "take_screenshot")), names);
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
}
