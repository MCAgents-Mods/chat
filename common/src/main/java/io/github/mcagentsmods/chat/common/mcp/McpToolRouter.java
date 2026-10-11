package io.github.mcagentsmods.chat.common.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagentsmods.chat.api.agent.ToolBroker;
import io.github.mcagentsmods.chat.api.agent.ToolCall;
import io.github.mcagentsmods.chat.api.agent.ToolDefinition;
import io.github.mcagentsmods.chat.api.agent.ToolOutcome;
import io.github.mcagentsmods.chat.api.mcp.McpContextRegistry;
import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Routes a model's tool call to the server that owns the tool.
 *
 * <p>The whole job is resolving one name. The model is shown a flat list of
 * {@code {server}__{tool}} names and calls one of them; this looks that name up in the
 * registry, finds the session for the server it came from, and calls the tool by its own
 * name — qualification is stripped, because a server has never heard of it.
 *
 * <p><b>Nothing here fails a request.</b> A tool that does not exist, arguments that will
 * not parse, a server that has disconnected since the model was shown its tools — every one
 * of them comes back as a {@link ToolOutcome} the model reads. It can then correct itself or
 * tell the player what it could not do, which is strictly better than the player's question
 * dying on a mistake the model could have recovered from.
 *
 * <p>Built-in tools are offered alongside the MCP ones and are checked first, so a server
 * cannot shadow one by choosing a colliding name.
 */
public final class McpToolRouter implements ToolBroker {

    private final McpHost host;
    private final Map<String, BuiltInTool> builtIns = new LinkedHashMap<>();

    /**
     * A tool the mod implements itself rather than one an MCP server published.
     *
     * <p>{@code mcp_clone_repository} is the reason this exists: the agent has to be able to
     * add a server, and it cannot do that through a server it does not yet have.
     */
    public interface BuiltInTool {

        /** How the model is told about it. */
        ToolDefinition definition();

        /** Runs it. The future should not fail — return an error outcome instead. */
        CompletableFuture<ToolOutcome> invoke(String callId, JsonObject arguments);
    }

    public McpToolRouter(McpHost host) {
        this.host = host;
    }

    /** Adds a tool the mod implements itself. */
    public McpToolRouter withBuiltIn(BuiltInTool tool) {
        builtIns.put(tool.definition().name(), tool);
        return this;
    }

    /**
     * Every tool that can be offered, built-ins first.
     *
     * <p>A tool whose name a provider would reject is dropped here rather than sent. The
     * provider rejects the entire request over one bad name, so one badly named tool from
     * one server would otherwise silence every tool from every server.
     */
    @Override
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> definitions = new ArrayList<>();
        builtIns.values().forEach(tool -> definitions.add(tool.definition()));

        for (McpToolDescriptor tool : host.registry().tools()) {
            String name = tool.qualifiedName();
            if (!ToolDefinition.isAcceptable(name) || builtIns.containsKey(name)) {
                continue;
            }
            definitions.add(new ToolDefinition(name, describe(tool), tool.inputSchema()));
        }
        return List.copyOf(definitions);
    }

    /**
     * Names the server in the description.
     *
     * <p>The qualified name already carries it, but a model reads the description and a
     * server's own description rarely says which server it is. Being explicit is what stops
     * two similar tools from different servers being used interchangeably.
     */
    private static String describe(McpToolDescriptor tool) {
        String description = tool.description().isBlank()
                ? "A tool offered by the " + tool.serverId() + " server."
                : tool.description();
        return description + " (from the MCP server '" + tool.serverId() + "')";
    }

    @Override
    public CompletableFuture<ToolOutcome> invoke(ToolCall call) {
        JsonObject arguments;
        try {
            arguments = parseArguments(call.arguments());
        } catch (RuntimeException malformed) {
            // The model wrote the arguments, so the model is the one who can fix them.
            return CompletableFuture.completedFuture(ToolOutcome.error(call.id(),
                    "the arguments were not a JSON object. Call it again with valid JSON."));
        }

        BuiltInTool builtIn = builtIns.get(call.name());
        if (builtIn != null) {
            return guard(call, builtIn.invoke(call.id(), arguments));
        }

        McpContextRegistry registry = host.registry();
        Optional<McpToolDescriptor> descriptor = registry.findTool(call.name());
        if (descriptor.isEmpty()) {
            return CompletableFuture.completedFuture(ToolOutcome.error(call.id(),
                    "there is no tool called '" + call.name() + "'. Available tools are: "
                            + available()));
        }

        McpToolDescriptor tool = descriptor.get();
        Optional<McpSession> session = host.session(tool.serverId());
        if (session.isEmpty()) {
            return CompletableFuture.completedFuture(ToolOutcome.error(call.id(),
                    "the server '" + tool.serverId() + "' is not connected right now, so "
                            + "'" + call.name() + "' cannot be run."));
        }

        return guard(call, session.get()
                // The server's own name, not the qualified one — it has never heard of that.
                .callTool(tool.name(), toArgumentMap(arguments))
                .thenApply(result -> new ToolOutcome(call.id(), result.text(), result.isError())));
    }

    /** A short list for the model to correct itself against, capped so it stays readable. */
    private String available() {
        List<String> names = definitions().stream().map(ToolDefinition::name).toList();
        if (names.isEmpty()) {
            return "none";
        }
        return names.size() <= 20
                ? String.join(", ", names)
                : String.join(", ", names.subList(0, 20)) + ", and " + (names.size() - 20) + " more";
    }

    /**
     * Turns any failure into an outcome the model can read.
     *
     * <p>This is the rule the whole class exists to keep: a tool call that goes wrong is a
     * message, never a broken request.
     */
    private static CompletableFuture<ToolOutcome> guard(
            ToolCall call, CompletableFuture<ToolOutcome> running) {
        return running.handle((outcome, failure) -> {
            if (failure == null) {
                return outcome;
            }
            Throwable cause = failure;
            while ((cause instanceof java.util.concurrent.CompletionException
                    || cause instanceof java.util.concurrent.ExecutionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String reason = cause.getMessage() == null || cause.getMessage().isBlank()
                    ? cause.getClass().getSimpleName()
                    : cause.getMessage();
            return ToolOutcome.error(call.id(), reason);
        });
    }

    private static JsonObject parseArguments(String arguments) {
        JsonElement parsed = JsonParser.parseString(arguments);
        if (parsed.isJsonNull()) {
            return new JsonObject();
        }
        return parsed.getAsJsonObject();
    }

    /** Hands the arguments to the session as Gson elements, so nothing is re-encoded. */
    private static Map<String, Object> toArgumentMap(JsonObject arguments) {
        Map<String, Object> values = new LinkedHashMap<>();
        arguments.keySet().forEach(key -> values.put(key, arguments.get(key)));
        return values;
    }
}
