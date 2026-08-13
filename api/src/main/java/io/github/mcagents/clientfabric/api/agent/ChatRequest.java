package io.github.mcagents.clientfabric.api.agent;

import java.util.List;
import java.util.Objects;

/**
 * A chat completion to send.
 *
 * <p>The order of {@link #messages()} is not cosmetic. Providers cache prompts by matching
 * a prefix, so everything stable belongs at the front and everything that changes per
 * request belongs at the back. See {@code .agents/api/openrouter.md}.
 *
 * @param model    the OpenRouter model id, exactly as the player chose it
 * @param messages the conversation, oldest first
 */
public record ChatRequest(String model, List<PromptMessage> messages) {

    public ChatRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(messages, "messages");
        if (model.isBlank()) {
            throw new IllegalArgumentException("A chat request needs a model");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A chat request needs at least one message");
        }
        messages = List.copyOf(messages);
    }
}
