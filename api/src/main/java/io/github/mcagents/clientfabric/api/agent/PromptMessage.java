package io.github.mcagents.clientfabric.api.agent;

import io.github.mcagents.clientfabric.api.model.ChatRole;

import java.util.Objects;

/**
 * One message on its way to the model.
 *
 * <p>Distinct from {@link io.github.mcagents.clientfabric.api.model.ChatMessage}, which is
 * a stored row with an id and a timestamp. This is the wire shape: a role and some text,
 * and nothing the model has no use for.
 *
 * @param role who is speaking
 * @param text what they said
 */
public record PromptMessage(ChatRole role, String text) {

    public PromptMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }

    public static PromptMessage system(String text) {
        return new PromptMessage(ChatRole.SYSTEM, text);
    }

    public static PromptMessage user(String text) {
        return new PromptMessage(ChatRole.USER, text);
    }

    public static PromptMessage assistant(String text) {
        return new PromptMessage(ChatRole.ASSISTANT, text);
    }
}
