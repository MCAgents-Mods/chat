package io.github.mcagents.clientfabric.api.mcp;

import java.time.Instant;
import java.util.Objects;

/**
 * What happened to one server the last time the loader tried it.
 *
 * <p>This exists so a failure is visible rather than silent. An MCP server that will not
 * start is not an error the player should have to find in a log: it is a row in a screen
 * saying which server, and why. The mod keeps working either way.
 *
 * @param spec       the server this describes
 * @param state      where it got to
 * @param detail     a human-readable reason, empty when connected
 * @param toolCount  tools it contributed
 * @param observedAt when this was last true
 */
public record McpServerStatus(
        McpServerSpec spec, State state, String detail, int toolCount, Instant observedAt) {

    /** Where a server got to. */
    public enum State {
        /** Connected, and its tools are in the registry. */
        CONNECTED,
        /** Configured but switched off; nothing was started. */
        DISABLED,
        /** Tried and could not be reached, or broke the protocol. */
        FAILED
    }

    public McpServerStatus {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(observedAt, "observedAt");
        detail = detail == null ? "" : detail;
    }

    public static McpServerStatus connected(McpServerSpec spec, int toolCount) {
        return new McpServerStatus(spec, State.CONNECTED, "", toolCount, Instant.now());
    }

    public static McpServerStatus disabled(McpServerSpec spec) {
        return new McpServerStatus(spec, State.DISABLED, "Disabled in configuration", 0, Instant.now());
    }

    public static McpServerStatus failed(McpServerSpec spec, String detail) {
        return new McpServerStatus(spec, State.FAILED, detail, 0, Instant.now());
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }
}
