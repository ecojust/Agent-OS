package dev.agentos.core;

import com.google.gson.JsonObject;

/** Trusted local metadata is supplied by the coordinator, never by model arguments. */
public final class ToolRequest {
    public final int protocolVersion;
    public final String taskId, callId, profileId, tool;
    public final long executionEpoch;
    private final JsonObject arguments;
    public ToolRequest(int protocolVersion, String taskId, String callId, long executionEpoch,
                       String profileId, String tool, JsonObject arguments) {
        this.protocolVersion = protocolVersion; this.taskId = taskId; this.callId = callId;
        this.executionEpoch = executionEpoch; this.profileId = profileId; this.tool = tool;
        this.arguments = arguments == null ? null : arguments.deepCopy();
    }
    public JsonObject getArguments() { return arguments == null ? null : arguments.deepCopy(); }
}
