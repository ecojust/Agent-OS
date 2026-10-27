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

    /**
     * Complete original request identity for adapters with reviewed local images.
     * Override this entry to return an existing PNG owner without a data URL. Do
     * not assign a new epoch/call ID or treat the result type as disclosure consent.
     * The default retains the existing text/data-URL adapter path.
     */
    default LocalToolResult execute(ToolRequest request, RunControl control) throws Exception {
        control.checkExecutionEpoch(request.executionEpoch);
        return LocalToolResult.metadata(execute(request.tool, request.getArguments(), control,
                request.executionEpoch));
    }

    default Set<String> availableTools() { return ToolCatalog.basicTools(); }
}
