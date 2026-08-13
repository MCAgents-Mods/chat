package io.github.mcagents.clientfabric.api.agent;

import java.util.Objects;

/**
 * The model's answer, and what it cost.
 *
 * @param text  the answer
 * @param model the model that actually served the request, which may differ from the one
 *              asked for when the provider routes elsewhere
 * @param usage token counts as reported
 */
public record ChatCompletion(String text, String model, TokenUsage usage) {

    public ChatCompletion {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(usage, "usage");
    }
}
