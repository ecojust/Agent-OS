package dev.agentos.core;

import java.util.Objects;

/**
 * Decides whether a tool needs a separate user confirmation under a locally chosen mode.
 * A false result grants no execution authority: capability, arguments, application scope,
 * configuration, execution epoch, observation and final dispatch guards remain mandatory.
 */
public final class ActionConfirmationPolicy {
    public enum Mode { CONFIRM_ALL, CONTINUOUS_NAVIGATION }

    private final Mode mode;

    public ActionConfirmationPolicy() { this(Mode.CONFIRM_ALL); }

    public ActionConfirmationPolicy(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Mode getMode() { return mode; }

    /**
     * Only exact, explicitly reviewed tool names can avoid confirmation. Unknown names
     * must still be rejected by the tool validator; true does not make them executable.
     */
    public boolean requiresConfirmation(String tool) {
        if (tool == null) return true;
        switch (tool) {
            case "list_apps":
            case "observe":
            case "resolve_node_at":
            case "wait":
                return false;
            case "launch_app":
            case "scroll":
            case "home":
                return mode != Mode.CONTINUOUS_NAVIGATION;
            default:
                // Back may commit or discard a form. Generic input, gestures and image
                // disclosure also retain their confirmation requirements in either mode.
                return true;
        }
    }
}
