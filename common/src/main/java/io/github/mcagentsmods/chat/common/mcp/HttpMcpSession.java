package io.github.mcagentsmods.chat.common.mcp;

import com.google.gson.JsonObject;
import io.github.mcagentsmods.chat.api.mcp.McpException;
import io.github.mcagentsmods.chat.api.mcp.McpPromptDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpResourceDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpToolResult;
import io.github.mcagentsmods.chat.common.agent.HttpCall;
import io.github.mcagentsmods.chat.common.agent.HttpReply;
import io.github.mcagentsmods.chat.common.agent.HttpTransport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An MCP server reached over streamable HTTP.
 *
 * <p>Every request is a POST carrying one JSON-RPC message. The reply comes back either as
 * {@code application/json} or as {@code text/event-stream} frames, and both are accepted
 * because servers choose freely between them — a client that handles only one works against
 * half the ecosystem.
 *
 * <p>It sends through the same {@link HttpTransport} abstraction the model client uses,
 * which is the reason this class is testable at all: the whole HTTP path is exercised
 * against a scripted transport, with no network and no server to host. The instance is the
 * host's, not the model client's — the two keep separate connection pools.
 *
 * <p><b>What this does not do.</b> There is no long-lived stream. Requests are
 * request/response, so a server that pushes unsolicited notifications — a tool list that
 * changed, progress on a long call — is not heard. That is a real limitation and not an
 * oversight: the registry refreshes on reload and on the directory watch instead, and
 * adding a persistent connection would mean a thread per server for a signal the mod has no
 * use for yet.
 */
final class HttpMcpSession implements McpSession {

    private final String serverId;
    private final String url;
    private final HttpTransport transport;
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile String sessionHeader = "";
    private volatile boolean closed;

    private HttpMcpSession(String serverId, String url, HttpTransport transport) {
        this.serverId = serverId;
        this.url = url;
        this.transport = transport;
    }

    /**
     * Connects to {@code spec} and completes the MCP handshake.
     *
     * @throws McpException if the endpoint cannot be reached or does not speak MCP
     */
    static HttpMcpSession connect(McpServerSpec spec, HttpTransport transport) {
        HttpMcpSession session = new HttpMcpSession(spec.id(), spec.url(), transport);
        JsonObject result = McpJson.result(spec.id(), McpJson.await(spec.id(),
                session.post(McpJson.request(0, "initialize", McpJson.initializeParams()))));
        if (!result.has("serverInfo")) {
            throw new McpException(spec.id(),
                    "Endpoint did not identify itself — check the URL includes the MCP path");
        }
        session.postAndForget(McpJson.notification("notifications/initialized"));
        return session;
    }

    private CompletableFuture<String> send(String method, JsonObject params) {
        if (closed) {
            return CompletableFuture.failedFuture(new McpException(serverId, "Session closed"));
        }
        return post(McpJson.request(nextId.getAndIncrement(), method, params));
    }

    private CompletableFuture<String> post(String body) {
        return transport.send(HttpCall.post(url, headers(), body))
                .thenApply(reply -> {
                    if (!reply.isSuccess()) {
                        throw new McpException(serverId, describe(reply));
                    }
                    return payloadOf(reply.body());
                });
    }

    private void postAndForget(String body) {
        transport.send(HttpCall.post(url, headers(), body)).exceptionally(ignored -> null);
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        // Both are offered because the server picks; refusing one halves compatibility.
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("MCP-Protocol-Version", McpJson.PROTOCOL_VERSION);
        if (!sessionHeader.isEmpty()) {
            headers.put("Mcp-Session-Id", sessionHeader);
        }
        return headers;
    }

    /**
     * Turns a response body into the JSON-RPC message inside it.
     *
     * <p>An event-stream body is a sequence of {@code data:} lines, possibly several frames.
     * The last one carrying a JSON object is the reply — earlier frames are progress
     * notifications, which this client does not act on.
     */
    static String payloadOf(String body) {
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        String found = "";
        for (String line : trimmed.split("\n")) {
            String stripped = line.strip();
            if (!stripped.startsWith("data:")) {
                continue;
            }
            String data = stripped.substring("data:".length()).strip();
            if (data.startsWith("{")) {
                found = data;
            }
        }
        return found.isEmpty() ? trimmed : found;
    }

    private String describe(HttpReply reply) {
        return switch (reply.status()) {
            case 401, 403 -> "Endpoint rejected the connection (" + reply.status()
                    + ") — it may need credentials, or the URL may be missing its MCP path";
            case 404, 405 -> "Nothing at this URL speaks MCP (" + reply.status()
                    + ") — check the endpoint includes its MCP path, usually /mcp";
            default -> "Endpoint answered " + reply.status();
        };
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
        return !closed;
    }

    /**
     * Marks the session closed.
     *
     * <p>The transport is not closed here. It belongs to the host and is shared with every
     * other HTTP server, so closing it from one session would take down connections that
     * session does not own.
     */
    @Override
    public void close() {
        closed = true;
    }
}
