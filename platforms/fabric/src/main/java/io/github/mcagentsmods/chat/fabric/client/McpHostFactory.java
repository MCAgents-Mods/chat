package io.github.mcagentsmods.chat.fabric.client;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.chat.common.mcp.GitRepositoryCloner;
import io.github.mcagentsmods.chat.common.mcp.WatchingMcpHost;
import io.github.mcagentsmods.core.api.http.HttpTransport;
import io.github.mcagentsmods.core.common.http.JdkHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds and owns the MCP host, and the cloner that fills the directory it watches.
 *
 * <p>One host per game session, for the same reason there is one model client: it holds
 * child processes and a filesystem watch, and a second would start every server twice.
 *
 * <p>Its threads are daemons. An MCP server that will not stop must never be the reason
 * Minecraft does not exit, and the host is an optional extra — nothing it does is worth
 * holding the JVM open for.
 */
public final class McpHostFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(MCAgents.MOD_NAME);

    private static volatile McpHost host;
    private static volatile McpRepositoryCloner cloner;
    private static volatile ExecutorService executor;
    private static volatile HttpTransport transport;

    private McpHostFactory() {
        throw new AssertionError("No instances.");
    }

    /**
     * Starts discovery and returns the host.
     *
     * <p>Nothing waits on it and no failure propagates. A player with no MCP servers — the
     * usual case — gets an empty registry, and a server that will not start is a row in the
     * registry's status list rather than a startup failure. That is the offline-first
     * mandate applied to MCP.
     */
    public static McpHost start(ClientPaths paths) {
        McpHost existing = host;
        if (existing != null) {
            return existing;
        }
        synchronized (McpHostFactory.class) {
            if (host != null) {
                return host;
            }
            executor = Executors.newCachedThreadPool(daemonThreads());
            transport = new JdkHttpTransport();
            WatchingMcpHost started = new WatchingMcpHost(paths, transport, executor);
            host = started;

            started.start().whenComplete((registry, failure) -> {
                if (failure != null) {
                    LOGGER.warn("MCP discovery failed; no external servers are available", failure);
                    return;
                }
                if (registry.servers().isEmpty()) {
                    LOGGER.info("No MCP servers in {}", paths.mcpDirectory());
                    return;
                }
                LOGGER.info("MCP: {} of {} servers connected, {} tools available",
                        registry.connectedServers().size(),
                        registry.servers().size(),
                        registry.tools().size());
                registry.servers().stream()
                        .filter(status -> !status.isConnected())
                        .forEach(status -> LOGGER.warn("MCP server '{}' is {}: {}",
                                status.spec().id(), status.state(), status.detail()));
            });
            return started;
        }
    }

    /** The running host, or a host that does nothing when MCP has not been started. */
    public static McpHost host() {
        McpHost running = host;
        return running == null ? McpHost.disabled() : running;
    }

    /**
     * The cloner, which writes into the directory the host watches.
     *
     * <p>Available before {@link #start} and after it: cloning is what creates the first
     * server, so requiring a running host to add one would be a loop with no entry.
     */
    public static McpRepositoryCloner cloner(ClientPaths paths) {
        McpRepositoryCloner existing = cloner;
        if (existing != null) {
            return existing;
        }
        synchronized (McpHostFactory.class) {
            if (cloner == null) {
                if (executor == null) {
                    executor = Executors.newCachedThreadPool(daemonThreads());
                }
                cloner = new GitRepositoryCloner(paths, executor);
            }
            return cloner;
        }
    }

    private static java.util.concurrent.ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "MCAgents-mcp-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Stops every server and releases the watch. Called on game shutdown. */
    public static void close() {
        synchronized (McpHostFactory.class) {
            if (host != null) {
                host.close();
                host = null;
            }
            cloner = null;
            if (transport != null) {
                transport.close();
                transport = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }
}
