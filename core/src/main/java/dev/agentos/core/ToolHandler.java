package dev.agentos.core;

import com.google.gson.JsonObject;
import java.util.Set;

public interface ToolHandler {
    JsonObject execute(String name, JsonObject arguments, RunControl control) throws Exception;
    default Set<String> availableTools() { return ToolCatalog.basicTools(); }
}
