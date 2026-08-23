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
 * <p>{@link #tools()} sits after the messages in the payload but is stable across the
 * follow-up requests of one exchange, which is what lets a tool round trip reuse the cached
 * prefix rather than paying for the whole conversation again.
 *
 * @param model    the OpenRouter model id, exactly as the player chose it
 * @param messages the conversation, oldest first
 * @param tools    what the model may call; empty means it is not offered any
 */
public record ChatRequest(String model, List<PromptMessage> messages, List<ToolDefinition> tools) {

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
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** A request offering the model no tools. */
    public ChatRequest(String model, List<PromptMessage> messages) {
        this(model, messages, List.of());
    }

    /** This request continued with {@code messages}, keeping the same model and tools. */
    public ChatRequest continuedWith(List<PromptMessage> messages) {
        return new ChatRequest(model, messages, tools);
    }
}
