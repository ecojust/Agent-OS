package dev.agentos.core;

import com.google.gson.JsonObject;
import java.util.Set;

public interface ToolHandler {
    JsonObject execute(String name, JsonObject arguments, RunControl control) throws Exception;

    /**
     * Executes an existing request without assigning it a newer execution epoch.
     * Stateful device adapters must override this overload and retain requestEpoch
     * through confirmation, queued work and the final platform dispatch guard.
     * The three-argument method remains available for simple legacy handlers and
     * fresh local calls; it is not where an existing request gets a new identity.
     */
    default JsonObject execute(String name, JsonObject arguments, RunControl control,
                               long requestEpoch) throws Exception {
        control.checkExecutionEpoch(requestEpoch);
        return execute(name, arguments, control);
    }

    default Set<String> availableTools() { return ToolCatalog.basicTools(); }
}
