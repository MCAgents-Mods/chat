package io.github.mcagentsmods.chat.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Answers a player's question in the context of a conversation.
 *
 * <p>This is what the interface depends on. It is deliberately narrower than
 * {@link ModelClient}: a screen should not know about models, keys, or retries, only that
 * it asked something and an answer will arrive.
 */
@FunctionalInterface
public interface Agent {

    /**
     * Answers {@code prompt} within the conversation {@code sessionId}.
     *
     * <p>The player's message is already stored by the time this is called, so an
     * implementation reads the conversation rather than being handed it.
     *
     * @return the answer text; the future completes exceptionally on failure
     */
    CompletableFuture<String> respond(String sessionId, String prompt);

    /**
     * The agent used when no model client is configured: it explains itself instead of
     * failing, so a player without a key still gets a screen that makes sense.
     */
    static Agent notConfigured() {
        return (sessionId, prompt) -> CompletableFuture.completedFuture(
                "No OpenRouter API key is configured yet. Your message has been saved to "
                        + "this conversation. Add a key and ask again, and it will be answered.");
    }
}
