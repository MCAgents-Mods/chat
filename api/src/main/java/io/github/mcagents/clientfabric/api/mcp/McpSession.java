package io.github.mcagents.clientfabric.api.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A live connection to one MCP server.
 *
 * <p>One session, one server. The transport is behind this interface entirely: a stdio
 * child process and an HTTP endpoint are the same thing to everything above, which is what
 * lets the loader treat a cloned repository and a hosted service identically.
 *
 * <p>Every method returns a future, per the repository's rule that no I/O blocks the client
 * thread. A session is created already-initialized — {@link #serverId()} and the descriptor
 * lists are answerable the moment a caller holds one.
 */
public interface McpSession extends AutoCloseable {

    /** The id this server is registered under, and the prefix on its qualified tool names. */
    String serverId();

    /** The tools it offers, discovered at connect time. */
    CompletableFuture<List<McpToolDescriptor>> listTools();

    /** The resources it offers. */
    CompletableFuture<List<McpResourceDescriptor>> listResources();

    /** The prompts it offers. */
    CompletableFuture<List<McpPromptDescriptor>> listPrompts();

    /**
     * Calls a tool by its own name — not its qualified name. Qualification is the
     * registry's concern, and it is stripped before a call reaches a session.
     *
     * @param name      the tool's name on this server
     * @param arguments the arguments, already parsed out of the model's call
     */
    CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> arguments);

    /** Reads one resource by URI. */
    CompletableFuture<String> readResource(String uri);

    /** Whether the connection is still usable. */
    boolean isAlive();

    /** Stops the server and releases the transport. Idempotent. */
    @Override
    void close();
}
