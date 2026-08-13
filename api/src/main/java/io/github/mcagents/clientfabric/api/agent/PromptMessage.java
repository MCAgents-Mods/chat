package io.github.mcagents.clientfabric.api.agent;

import io.github.mcagents.clientfabric.api.model.ChatRole;

import java.util.Objects;

/**
 * One message on its way to the model.
 *
 * <p>Distinct from {@link io.github.mcagents.clientfabric.api.model.ChatMessage}, which is
 * a stored row with an id and a timestamp. This is the wire shape: a role, some text, and
 * whether the provider should be asked to cache everything up to and including it.
 *
 * @param role           who is speaking
 * @param text           what they said
 * @param cacheBreakpoint whether to ask the provider to cache the prefix ending here
 */
public record PromptMessage(ChatRole role, String text, boolean cacheBreakpoint) {

    public PromptMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }

    /** A message with no breakpoint on it. */
    public PromptMessage(ChatRole role, String text) {
        this(role, text, false);
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

    /**
     * This message, marked as the end of a cacheable prefix.
     *
     * <p>Everything from the start of the conversation up to and including this message is
     * offered to the provider for reuse. Only put one where the bytes before it are
     * genuinely stable between requests — a breakpoint after content that changes every
     * turn caches nothing and costs a write.
     */
    public PromptMessage asCacheBreakpoint() {
        return new PromptMessage(role, text, true);
    }
}
