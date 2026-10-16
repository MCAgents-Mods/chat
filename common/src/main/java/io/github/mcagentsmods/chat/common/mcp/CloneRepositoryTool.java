package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner;
import io.github.mcagentsmods.chat.api.mcp.McpServerStatus;
import io.github.mcagentsmods.chat.api.mcp.RepositoryCoordinate;
import io.github.mcagentsmods.core.tool.AsyncTool;
import io.github.mcagentsmods.core.tool.ExecutionMode;
import io.github.mcagentsmods.core.tool.ToolArguments;
import io.github.mcagentsmods.core.tool.ToolResult;
import io.github.mcagentsmods.core.tool.ToolSchema;
import io.github.mcagentsmods.core.tool.ToolSpec;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
 * directory watch, so the new server's tools are registered by the time the result reaches
 * the model, instead of a few seconds later.
 *
 * <p>It runs as {@link ExecutionMode#WAIT} work: it writes files and changes which servers are
 * running, so nothing else asked in the same turn runs beside it.
 */
public final class CloneRepositoryTool implements AsyncTool {

    /** Named for what it does, in the flat namespace the model sees. */
    public static final String NAME = "mcp_clone_repository";

    /**
     * Long enough for git's own limit and the reload after it, which starts the new server.
     */
    static final Duration TIMEOUT = Duration.ofMinutes(4);

    private final McpRepositoryCloner cloner;
    private final McpHost host;

    public CloneRepositoryTool(McpRepositoryCloner cloner, McpHost host) {
        this.cloner = cloner;
        this.host = host;
    }

    /** How the agents registry offers it, in the {@value McpTools#CLONE_CATEGORY} category. */
    public ToolSpec spec() {
        return ToolSpec.builder(NAME)
                .description("Clone an external MCP server repository. Takes an https git URL naming "
                        + "exactly {owner}/{repository}; the destination is chosen by the mod. "
                        + "A cloned server does not start until the player approves it, so "
                        + "tell the player what the result says it would run and how to "
                        + "approve it. Cloning a repository that is already present updates "
                        + "it, and a changed command needs approving again.")
                .category(McpTools.CLONE_CATEGORY)
                .parameters(ToolSchema.builder()
                        .string("url", "The https git URL of the MCP server repository, for "
                                + "example https://github.com/owner/repository.git", true)
                        .build())
                .mode(ExecutionMode.WAIT)
                .timeout(TIMEOUT)
                .build();
    }

    @Override
    public CompletionStage<ToolResult> call(ToolArguments arguments) {
        String url = arguments.string("url", "");
        if (url.isBlank()) {
            return CompletableFuture.completedFuture(ToolResult.error("no 'url' argument was given"));
        }

        return cloner.clone(url)
                .thenCompose(cloned -> host.reload().thenApply(registry -> {
                    String server = cloned.coordinate().repository();
                    long tools = registry.tools().stream()
                            .filter(tool -> tool.serverId().equals(server))
                            .count();
                    // The host is named because the directory is not: two hosts can serve an
                    // owner/repository of the same name, and the player should know which ran.
                    String what = (cloned.alreadyPresent() ? "Updated " : "Cloned ")
                            + cloned.coordinate().slug() + " from " + RepositoryCoordinate.hostOf(url);
                    Optional<McpServerStatus> status = registry.servers().stream()
                            .filter(row -> row.spec().id().equals(server))
                            .findFirst();
                    if (status.isPresent()
                            && status.get().state() == McpServerStatus.State.AWAITING_APPROVAL) {
                        return ToolResult.ok(what + ". " + status.get().detail());
                    }
                    // The count is the useful part: it distinguishes a repository that
                    // arrived and connected from one that arrived and would not start.
                    return ToolResult.ok(tools > 0
                            ? what + ". It is connected and offers " + tools
                                    + (tools == 1 ? " tool." : " tools.")
                            : what + ", but it is not offering any tools — it may not be an "
                                    + "MCP server, or it may not say how to start itself.");
                }))
                .handle((outcome, failure) -> failure == null
                        ? outcome
                        : ToolResult.error(reasonOf(failure)));
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
