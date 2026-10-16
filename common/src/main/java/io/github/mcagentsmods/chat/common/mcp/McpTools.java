package io.github.mcagentsmods.chat.common.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagentsmods.chat.api.mcp.McpContextRegistry;
import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;
import io.github.mcagentsmods.core.tool.AsyncTool;
import io.github.mcagentsmods.core.tool.ExecutionMode;
import io.github.mcagentsmods.core.tool.ToolArguments;
import io.github.mcagentsmods.core.tool.ToolNames;
import io.github.mcagentsmods.core.tool.ToolRegistration;
import io.github.mcagentsmods.core.tool.ToolRegistrationException;
import io.github.mcagentsmods.core.tool.ToolRegistry;
import io.github.mcagentsmods.core.tool.ToolResult;
import io.github.mcagentsmods.core.tool.ToolSchema;
import io.github.mcagentsmods.core.tool.ToolSpec;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Puts the MCP servers' tools in the agents mod's tool registry, and keeps them there while
 * servers come and go.
 *
 * <p><b>Each server is a category.</b> The agent first picks the categories a question needs,
 * so a server is offered as a whole, by its id, before any of its tools are described. Each
 * tool keeps its qualified {@code {server}__{tool}} name, which is what the model calls; the
 * body strips the qualification again, because a server has never heard of it.
 *
 * <p><b>The registry follows the host.</b> Every time the host's registry changes, tools are
 * added for new servers and removed for servers that went away. A question works from a
 * snapshot of the agents registry, so a server lost while the question runs still answers its
 * calls, as "not connected", rather than leaving the model looking as if it invented them.
 *
 * <p><b>Nothing here fails a question.</b> A server that is not connected, arguments that
 * will not parse, a server that threw: each comes back as an error result the model reads. A
 * tool whose name or schema the registry refuses is left out with a warning, so one badly
 * described tool never costs the others.
 *
 * <p>{@code mcp_clone_repository} sits in its own category, {@value #CLONE_CATEGORY}, so a
 * player with no servers can still ask for one.
 */
public final class McpTools implements AutoCloseable {

    private static final Logger LOGGER = System.getLogger(McpTools.class.getName());

    /** The category the clone tool is in. */
    public static final String CLONE_CATEGORY = "mcp";

    private final McpHost host;
    private final ToolRegistry registry;

    /** Every MCP tool this has registered, by qualified name. Guarded by {@code this}. */
    private final Map<String, Registered> registered = new HashMap<>();
    private ToolRegistration clone;
    private boolean closed;

    /**
     * @param host     the host whose servers' tools are offered
     * @param registry where they are registered: {@code Agents.tools()} in game, a fresh one in
     *                 a test
     */
    public McpTools(McpHost host, ToolRegistry registry) {
        this.host = Objects.requireNonNull(host, "host");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Registers the clone tool, then every tool the host has now, and follows the host from
     * here on.
     *
     * @return this, for chaining
     */
    public McpTools start(McpRepositoryCloner cloner) {
        Objects.requireNonNull(cloner, "cloner");
        synchronized (this) {
            if (clone == null && !closed) {
                registry.declareCategory(CLONE_CATEGORY,
                        "Adding MCP servers, which give the agent new tools");
                CloneRepositoryTool tool = new CloneRepositoryTool(cloner, host);
                clone = registerSafely(tool.spec(), tool);
            }
        }
        host.onRegistryChanged(this::sync);
        sync(host.registry());
        return this;
    }

    /**
     * Makes the agents registry hold exactly the tools {@code current} offers: new ones are
     * added, gone ones removed, and changed ones replaced.
     */
    public synchronized void sync(McpContextRegistry current) {
        if (closed) {
            return;
        }
        Map<String, McpToolDescriptor> wanted = new LinkedHashMap<>();
        for (McpToolDescriptor tool : current.tools()) {
            wanted.putIfAbsent(tool.qualifiedName(), tool);
        }

        registered.entrySet().removeIf(entry -> {
            McpToolDescriptor now = wanted.get(entry.getKey());
            if (entry.getValue().descriptor().equals(now)) {
                return false;
            }
            entry.getValue().registration().close();
            return true;
        });

        for (McpToolDescriptor tool : wanted.values()) {
            if (registered.containsKey(tool.qualifiedName())) {
                continue;
            }
            ToolRegistration registration = register(tool);
            if (registration != null) {
                registered.put(tool.qualifiedName(), new Registered(tool, registration));
            }
        }
    }

    /** Removes every tool this registered, the clone tool included. */
    @Override
    public synchronized void close() {
        closed = true;
        registered.values().forEach(entry -> entry.registration().close());
        registered.clear();
        if (clone != null) {
            clone.close();
            clone = null;
        }
    }

    /** The category a server's tools are in: its id, as a category id must be written. */
    public static String categoryOf(String serverId) {
        StringBuilder out = new StringBuilder(serverId.length());
        for (int i = 0; i < serverId.length() && out.length() < 64; i++) {
            char c = serverId.charAt(i);
            out.append(c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_') ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }

    private ToolRegistration register(McpToolDescriptor tool) {
        String name = tool.qualifiedName();
        if (!ToolNames.isValid(name)) {
            // A provider refuses the whole request over one name it does not accept.
            LOGGER.log(Level.WARNING, "Leaving out the MCP tool {0}: a model provider would refuse its name",
                    name);
            return null;
        }
        String category = categoryOf(tool.serverId());
        registry.declareCategory(category, "Tools from the MCP server '" + tool.serverId() + "'");
        ToolSpec spec = ToolSpec.builder(name)
                .description(describe(tool))
                .category(category)
                .parameters(schemaOf(tool))
                // A model asking for several tools at once has said they are independent.
                .mode(ExecutionMode.CONCURRENT)
                .build();
        return registerSafely(spec, arguments -> call(tool, arguments));
    }

    private ToolRegistration registerSafely(ToolSpec spec, AsyncTool body) {
        try {
            return registry.registerAsync(spec, body);
        } catch (ToolRegistrationException refused) {
            LOGGER.log(Level.WARNING, "Leaving out the tool " + spec.name() + ": " + refused.getMessage());
            return null;
        }
    }

    /**
     * Names the server in the description.
     *
     * <p>A model reads the description, and a server's own description rarely says which
     * server it is. Being explicit stops two similar tools from different servers being used
     * interchangeably.
     */
    private static String describe(McpToolDescriptor tool) {
        String description = tool.description().isBlank()
                ? "A tool offered by the " + tool.serverId() + " server."
                : tool.description();
        return description + " (from the MCP server '" + tool.serverId() + "')";
    }

    /** The server's schema, or an empty object schema when the registry cannot read it. */
    private static ToolSchema schemaOf(McpToolDescriptor tool) {
        try {
            return ToolSchema.of(tool.inputSchema());
        } catch (ToolRegistrationException unreadable) {
            LOGGER.log(Level.WARNING, "The MCP tool {0} has a schema that is not a JSON object; "
                    + "offering it with no arguments", tool.qualifiedName());
            return ToolSchema.empty();
        }
    }

    private CompletionStage<ToolResult> call(McpToolDescriptor tool, ToolArguments arguments) {
        Optional<McpSession> session = host.session(tool.serverId());
        if (session.isEmpty()) {
            return CompletableFuture.completedFuture(ToolResult.error("the server '"
                    + tool.serverId() + "' is not connected right now, so '"
                    + tool.qualifiedName() + "' cannot be run."));
        }
        return session.get()
                // The server's own name, not the qualified one: it has never heard of that.
                .callTool(tool.name(), toArgumentMap(arguments))
                .thenApply(result -> new ToolResult(result.text(), result.isError()));
    }

    /** Hands the arguments to the session as Gson elements, so nothing is re-encoded. */
    private static Map<String, Object> toArgumentMap(ToolArguments arguments) {
        JsonElement parsed = JsonParser.parseString(arguments.json());
        Map<String, Object> values = new LinkedHashMap<>();
        if (parsed.isJsonObject()) {
            JsonObject object = parsed.getAsJsonObject();
            object.keySet().forEach(key -> values.put(key, object.get(key)));
        }
        return values;
    }

    private record Registered(McpToolDescriptor descriptor, ToolRegistration registration) {
    }
}
