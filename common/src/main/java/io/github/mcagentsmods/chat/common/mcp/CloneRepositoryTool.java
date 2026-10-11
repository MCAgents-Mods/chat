package io.github.mcagentsmods.chat.common.mcp;

import com.google.gson.JsonObject;
import io.github.mcagentsmods.chat.api.agent.ToolOutcome;
import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner;
import io.github.mcagentsmods.core.api.model.ToolDefinition;

import java.util.concurrent.CompletableFuture;

/**
 * The clone capability, offered to the model as a tool it can call.
 *
 * <p>This is the one tool that must be built in rather than come from a server: it is how a
 * player with no MCP servers gets their first one, and a tool served by a server they do not
 * have yet is a loop with no entry.
 *
 * <p>It takes a URL and nothing else. There is deliberately no destination parameter — the
 * directory is derived from the URL by {@code RepositoryCoordinate}, so no argument a model
 * writes can decide where files land.
 *
 * <p>After a successful clone it asks the host to reload rather than waiting for the
 * directory watch, so the new server's tools are available on the model's very next turn
 * instead of a few seconds later.
 */
public final class CloneRepositoryTool implements McpToolRouter.BuiltInTool {

    /** Named for what it does, in the flat namespace the model sees. */
    public static final String NAME = "mcp_clone_repository";

    private final McpRepositoryCloner cloner;
    private final McpHost host;

    public CloneRepositoryTool(McpRepositoryCloner cloner, McpHost host) {
        this.cloner = cloner;
        this.host = host;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                NAME,
                "Clone an external MCP server repository so its tools become available. "
                        + "Takes an https git URL; the destination is chosen by the mod. The "
                        + "server is connected automatically, and its tools can be used on "
                        + "the next turn. Cloning a repository that is already present "
                        + "updates it.",
                """
                {
                  "type": "object",
                  "properties": {
                    "url": {
                      "type": "string",
                      "description": "The https git URL of the MCP server repository, for \
                example https://github.com/owner/repository.git"
                    }
                  },
                  "required": ["url"]
                }
                """);
    }

    @Override
    public CompletableFuture<ToolOutcome> invoke(String callId, JsonObject arguments) {
        String url = arguments.has("url") && arguments.get("url").isJsonPrimitive()
                ? arguments.get("url").getAsString()
                : "";
        if (url.isBlank()) {
            return CompletableFuture.completedFuture(
                    ToolOutcome.error(callId, "no 'url' argument was given"));
        }

        return cloner.clone(url)
                .thenCompose(cloned -> host.reload().thenApply(registry -> {
                    String server = cloned.coordinate().repository();
                    long tools = registry.tools().stream()
                            .filter(tool -> tool.serverId().equals(server))
                            .count();
                    String what = cloned.alreadyPresent()
                            ? "Updated " + cloned.coordinate().slug()
                            : "Cloned " + cloned.coordinate().slug();
                    // The count is the useful part: it distinguishes a repository that
                    // arrived and connected from one that arrived and would not start.
                    return ToolOutcome.ok(callId, tools > 0
                            ? what + ". It is connected and offers " + tools
                                    + (tools == 1 ? " tool." : " tools.")
                            : what + ", but it is not offering any tools — it may not be an "
                                    + "MCP server, or it may not say how to start itself.");
                }))
                .handle((outcome, failure) -> failure == null
                        ? outcome
                        : ToolOutcome.error(callId, reasonOf(failure)));
    }

    private static String reasonOf(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
