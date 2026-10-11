package io.github.mcagentsmods.chat.api.agent;

import io.github.mcagentsmods.core.api.model.ToolCall;
import io.github.mcagentsmods.core.api.model.ToolDefinition;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Offers tools to the model and runs the ones it calls.
 *
 * <p>The seam between the agent loop and whatever actually executes a tool. The agent knows
 * only this: here is what can be offered, and here is the result of a call. It does not know
 * that most of those tools come from external MCP servers, or that one of them clones a new
 * server — which is what lets the loop be tested without a single server running.
 *
 * <p>An implementation never throws for a tool that does not exist or a call that fails. It
 * answers with a {@link ToolOutcome} carrying {@code isError}, because that is something the
 * model can read and recover from.
 */
public interface ToolBroker {

    /**
     * Every tool that can be offered right now.
     *
     * <p>Read once per request and used for the whole of it, including the follow-ups: the
     * set the model was shown must be the set its calls are routed against, or a server
     * disconnecting mid-question makes the model look like it invented a tool.
     */
    List<ToolDefinition> definitions();

    /** Runs one call. The future does not fail; a failure arrives as an error outcome. */
    CompletableFuture<ToolOutcome> invoke(ToolCall call);

    /** A broker with nothing to offer — the mod with no MCP servers, which is the usual case. */
    static ToolBroker none() {
        return new ToolBroker() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of();
            }

            @Override
            public CompletableFuture<ToolOutcome> invoke(ToolCall call) {
                return CompletableFuture.completedFuture(
                        ToolOutcome.error(call.id(), "No tools are available in this session"));
            }
        };
    }
}
