package io.github.mcagentsmods.chat.common.mcp;

import com.google.gson.JsonObject;
import io.github.mcagentsmods.chat.api.mcp.McpException;
import io.github.mcagentsmods.chat.api.mcp.McpPromptDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpResourceDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpToolResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An MCP server the mod runs as a child process, spoken to over its standard streams.
 *
 * <p>This is the common case: a repository the player cloned is a program on disk, and the
 * mod starts it. Three things about the arrangement are worth stating because getting any
 * of them wrong produces a hang rather than an error.
 *
 * <p><b>Standard output is the protocol.</b> One JSON-RPC message per line, and nothing
 * else may be written there. A server that logs to standard output corrupts its own stream;
 * this reader skips lines that are not JSON objects rather than failing, so a chatty server
 * degrades instead of dying.
 *
 * <p><b>Standard error is drained.</b> A child whose error stream fills its pipe buffer
 * blocks forever, and it blocks inside a write the mod never sees. The drain thread exists
 * for that reason alone, not to collect the output.
 *
 * <p><b>Every request has a deadline.</b> A server that accepts a request and never answers
 * would otherwise leave a future no one completes. Each call is timed out and the waiter is
 * failed, so a wedged server costs one request rather than a leak.
 */
final class StdioMcpSession implements McpSession {

    private final String serverId;
    private final Process process;
    private final BufferedWriter toServer;
    private final Duration timeout;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<String>> waiting = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private StdioMcpSession(String serverId, Process process, Duration timeout) {
        this.serverId = serverId;
        this.process = process;
        this.timeout = timeout;
        this.toServer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Starts {@code spec} and completes the MCP handshake.
     *
     * @param mcpRoot the directory {@link McpServerSpec#workingDirectory()} is relative to
     * @throws McpException if the process cannot be started or does not complete the
     *         handshake within {@code timeout}
     */
    static StdioMcpSession start(McpServerSpec spec, Path mcpRoot, Duration timeout) {
        Path workingDirectory = spec.workingDirectory().isBlank()
                ? mcpRoot
                : mcpRoot.resolve(spec.workingDirectory()).normalize();
        if (!workingDirectory.startsWith(mcpRoot)) {
            throw new McpException(spec.id(),
                    "Working directory escapes the mcps directory: " + spec.workingDirectory());
        }
        if (!Files.isDirectory(workingDirectory)) {
            throw new McpException(spec.id(), "No such directory: " + workingDirectory);
        }

        List<String> command = new java.util.ArrayList<>();
        command.add(spec.command());
        command.addAll(spec.arguments());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.environment().putAll(spec.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotStart) {
            throw new McpException(spec.id(),
                    "Could not start '" + spec.command() + "': " + cannotStart.getMessage(),
                    cannotStart);
        }

        StdioMcpSession session = new StdioMcpSession(spec.id(), process, timeout);
        session.readStandardOutput();
        session.drainStandardError();
        try {
            session.handshake();
        } catch (RuntimeException failed) {
            session.close();
            throw failed;
        }
        return session;
    }

    private void handshake() {
        JsonObject result = McpJson.result(serverId,
                McpJson.await(serverId, send("initialize", McpJson.initializeParams())));
        if (!result.has("serverInfo")) {
            throw new McpException(serverId, "Server did not identify itself during initialize");
        }
        write(McpJson.notification("notifications/initialized"));
    }

    /**
     * Reads replies and hands each to whoever is waiting for its id.
     *
     * <p>A daemon thread, so it can never hold the game open. When the stream ends every
     * outstanding waiter is failed — a server that exits mid-request must not leave a future
     * that never completes.
     */
    private void readStandardOutput() {
        Thread reader = new Thread(() -> {
            try (BufferedReader from = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = from.readLine()) != null) {
                    deliver(line.trim());
                }
            } catch (IOException streamEnded) {
                // The child closed its output. Handled below, like a clean exit.
            } finally {
                failEveryWaiter(new McpException(serverId, "Server stopped responding"));
            }
        }, "MCAgents-mcp-" + serverId);
        reader.setDaemon(true);
        reader.start();
    }

    private void deliver(String line) {
        if (line.isEmpty() || line.charAt(0) != '{') {
            return; // Not protocol. A server logging to stdout, which is its bug, not ours.
        }
        long id;
        try {
            JsonObject message = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            if (!message.has("id") || !message.get("id").isJsonPrimitive()) {
                return; // A notification from the server; nothing is waiting for it.
            }
            id = message.get("id").getAsLong();
        } catch (RuntimeException notAMessage) {
            return;
        }
        CompletableFuture<String> waiter = waiting.remove(id);
        if (waiter != null) {
            waiter.complete(line);
        }
    }

    /** Keeps the child's error pipe empty so it never blocks writing to it. */
    private void drainStandardError() {
        Thread drain = new Thread(() -> {
            try (BufferedReader errors = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (errors.readLine() != null) {
                    // Discarded on purpose: a server's log is not the mod's to keep, and
                    // it is exactly where a server prints the credentials it was given.
                }
            } catch (IOException ignored) {
                // The child is gone; nothing left to drain.
            }
        }, "MCAgents-mcp-" + serverId + "-stderr");
        drain.setDaemon(true);
        drain.start();
    }

    private CompletableFuture<String> send(String method, JsonObject params) {
        if (closed || !process.isAlive()) {
            return CompletableFuture.failedFuture(
                    new McpException(serverId, "Server is not running"));
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<String> reply = new CompletableFuture<>();
        waiting.put(id, reply);
        try {
            write(McpJson.request(id, method, params));
        } catch (RuntimeException cannotWrite) {
            waiting.remove(id);
            return CompletableFuture.failedFuture(cannotWrite);
        }
        return reply
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, failure) -> waiting.remove(id));
    }

    private synchronized void write(String message) {
        try {
            toServer.write(message);
            toServer.write('\n');
            toServer.flush();
        } catch (IOException cannotWrite) {
            throw new McpException(serverId, "Could not write to the server", cannotWrite);
        }
    }

    private void failEveryWaiter(Throwable cause) {
        waiting.values().forEach(waiter -> waiter.completeExceptionally(cause));
        waiting.clear();
    }

    @Override
    public String serverId() {
        return serverId;
    }

    @Override
    public CompletableFuture<List<McpToolDescriptor>> listTools() {
        return send("tools/list", new JsonObject())
                .thenApply(reply -> McpJson.tools(serverId, McpJson.result(serverId, reply)));
    }

    @Override
    public CompletableFuture<List<McpResourceDescriptor>> listResources() {
        return send("resources/list", new JsonObject())
                .thenApply(reply -> McpJson.resources(serverId, McpJson.result(serverId, reply)));
    }

    @Override
    public CompletableFuture<List<McpPromptDescriptor>> listPrompts() {
        return send("prompts/list", new JsonObject())
                .thenApply(reply -> McpJson.prompts(serverId, McpJson.result(serverId, reply)));
    }

    @Override
    public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> arguments) {
        return send("tools/call", McpJson.callToolParams(name, arguments))
                .thenApply(reply -> McpJson.toolResult(McpJson.result(serverId, reply)));
    }

    @Override
    public CompletableFuture<String> readResource(String uri) {
        return send("resources/read", McpJson.readResourceParams(uri))
                .thenApply(reply -> McpJson.resourceText(McpJson.result(serverId, reply)));
    }

    @Override
    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    /**
     * Stops the server.
     *
     * <p>Asked first, then killed. A server given no chance to exit cleanly can leave its
     * own temporary state behind, and the wait is short enough that a hung one costs a
     * second rather than a shutdown that never finishes.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        failEveryWaiter(new McpException(serverId, "Session closed"));
        try {
            toServer.close();
        } catch (IOException ignored) {
            // Closing a pipe to a process that has already gone is not a failure.
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
