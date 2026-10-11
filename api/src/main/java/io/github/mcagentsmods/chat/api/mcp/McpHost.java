package io.github.mcagentsmods.chat.api.mcp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The mod acting as an MCP client: it discovers servers, connects to them, and keeps one
 * merged registry of what they offer.
 *
 * <p>Everything here is optional to the mod. A host that finds no servers, or fails to
 * start every one it finds, is a host in a normal state — the offline-first mandate applies
 * to MCP exactly as it applies to the central service. Nothing in this interface throws
 * because a server is missing.
 *
 * <p>Discovery is continuous, not a startup step. {@link #start()} takes the first reading
 * and then keeps watching, so a server cloned while the game is running appears without a
 * restart.
 */
public interface McpHost extends AutoCloseable {

    /**
     * Begins discovery and connects to what is found.
     *
     * @return the first registry, once the initial pass has finished
     */
    CompletableFuture<McpContextRegistry> start();

    /**
     * The current registry. Cheap, non-blocking, and safe to call from the client thread —
     * it returns the last snapshot rather than going and asking.
     */
    McpContextRegistry registry();

    /**
     * Re-reads configuration and the mcps directory now, instead of waiting for the watcher
     * to notice. Servers whose wiring is unchanged keep their existing connections.
     */
    CompletableFuture<McpContextRegistry> reload();

    /**
     * The live session for {@code serverId}, when there is one.
     *
     * <p>This is the seam the routing engine calls through: a tool call is resolved to a
     * server, and the server is resolved to the session that can serve it.
     */
    java.util.Optional<McpSession> session(String serverId);

    /**
     * Registers a listener told whenever the registry changes.
     *
     * <p>Called on a background thread. A listener that touches Minecraft state must hand
     * itself back to the client thread first.
     */
    void onRegistryChanged(Consumer<McpContextRegistry> listener);

    /** What the host would connect to, whether or not it currently can. */
    List<McpServerSpec> configuredServers();

    /** Disconnects every session and stops watching. Idempotent. */
    @Override
    void close();

    /** A host that does nothing, for a mod built without MCP support enabled. */
    static McpHost disabled() {
        return new McpHost() {
            @Override
            public CompletableFuture<McpContextRegistry> start() {
                return CompletableFuture.completedFuture(McpContextRegistry.empty());
            }

            @Override
            public McpContextRegistry registry() {
                return McpContextRegistry.empty();
            }

            @Override
            public CompletableFuture<McpContextRegistry> reload() {
                return CompletableFuture.completedFuture(McpContextRegistry.empty());
            }

            @Override
            public java.util.Optional<McpSession> session(String serverId) {
                return java.util.Optional.empty();
            }

            @Override
            public void onRegistryChanged(Consumer<McpContextRegistry> listener) {
            }

            @Override
            public List<McpServerSpec> configuredServers() {
                return List.of();
            }

            @Override
            public void close() {
            }
        };
    }
}
