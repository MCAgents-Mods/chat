package io.github.mcagentsmods.chat.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Talks to the model provider.
 *
 * <p>Both methods are asynchronous, like everything else that leaves the client thread. A
 * failure arrives as a {@link ModelException} completing the future, carrying the
 * {@link ModelException.Kind} that says what to do about it.
 *
 * <p>An implementation is responsible for key rotation, retries, and timeouts. Callers are
 * not: the interface answers, or it explains why it could not.
 */
public interface ModelClient extends AutoCloseable {

    /** Sends a conversation and returns the answer. */
    CompletableFuture<ChatCompletion> complete(ChatRequest request);

    /**
     * Embeds {@code text} with {@code model}, for retrieval.
     *
     * @return the vector, as the provider returned it
     */
    CompletableFuture<float[]> embed(String model, String text);

    @Override
    void close();
}
