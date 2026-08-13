package io.github.mcagents.clientfabric.fabric.client.gui;

import java.util.concurrent.CompletableFuture;

/**
 * Produces the agent's answer to a player's prompt.
 *
 * <p>This is the seam the OpenRouter client plugs into. The screen depends on this and not
 * on a model client, so the interface can be built, run, and looked at before any network
 * code exists — and so a player with no key still gets a screen that explains itself rather
 * than one that fails.
 *
 * <p>The contract is asynchronous, like everything else that leaves the client thread: an
 * implementation returns immediately and completes the future when the answer arrives.
 */
@FunctionalInterface
public interface AgentReply {

    /**
     * Answers {@code prompt} in the context of the conversation {@code sessionId}.
     *
     * @return the answer text; the future completes exceptionally if the request failed
     */
    CompletableFuture<String> respond(String sessionId, String prompt);

    /**
     * The reply used until the OpenRouter client exists: it explains that the agent is not
     * connected yet rather than pretending to answer.
     */
    static AgentReply notConfigured() {
        return (sessionId, prompt) -> CompletableFuture.completedFuture(
                "The agent is not connected yet. Your message has been saved to this "
                        + "conversation, and it will be answered once an OpenRouter key is "
                        + "configured and the model client is in place.");
    }
}
