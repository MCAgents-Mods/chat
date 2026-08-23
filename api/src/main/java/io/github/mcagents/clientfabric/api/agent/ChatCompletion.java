package io.github.mcagents.clientfabric.api.agent;

import java.util.List;
import java.util.Objects;

/**
 * The model's answer, and what it cost.
 *
 * <p>A completion carrying {@link #toolCalls()} is not an answer. It is the model saying
 * what it needs before it can produce one, and {@link #text()} is frequently empty there —
 * showing it to the player would show them nothing.
 *
 * @param text      the answer
 * @param model     the model that actually served the request, which may differ from the one
 *                  asked for when the provider routes elsewhere
 * @param usage     token counts as reported
 * @param toolCalls the tools the model wants run before it continues; empty in an answer
 */
public record ChatCompletion(
        String text, String model, TokenUsage usage, List<ToolCall> toolCalls) {

    public ChatCompletion {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(usage, "usage");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** A completion that answers rather than asking for tools. */
    public ChatCompletion(String text, String model, TokenUsage usage) {
        this(text, model, usage, List.of());
    }

    /** Whether the model is asking for tools rather than answering. */
    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
