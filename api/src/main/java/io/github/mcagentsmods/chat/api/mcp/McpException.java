package io.github.mcagentsmods.chat.api.mcp;

/**
 * An MCP server could not be reached, or answered with an error.
 *
 * <p>Unchecked on purpose: it travels as the cause of a failed
 * {@link java.util.concurrent.CompletableFuture}, which is how every other failure in this
 * codebase moves. Nothing that catches it may let it end the session for the player — an
 * MCP server is an optional extra, and the mod works without every one of them.
 */
public class McpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String serverId;

    public McpException(String serverId, String message) {
        super(message);
        this.serverId = serverId;
    }

    public McpException(String serverId, String message, Throwable cause) {
        super(message, cause);
        this.serverId = serverId;
    }

    /** The server this failure came from, or {@code "?"} when it happened before one was chosen. */
    public String serverId() {
        return serverId == null ? "?" : serverId;
    }
}
