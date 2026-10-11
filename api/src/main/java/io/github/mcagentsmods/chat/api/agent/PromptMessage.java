package io.github.mcagentsmods.chat.api.agent;

import io.github.mcagentsmods.chat.api.model.ChatRole;

import java.util.List;
import java.util.Objects;

/**
 * One message on its way to the model.
 *
 * <p>Distinct from {@link io.github.mcagentsmods.chat.api.model.ChatMessage}, which is
 * a stored row with an id and a timestamp. This is the wire shape: a role, some text, and
 * whether the provider should be asked to cache everything up to and including it.
 *
 * @param role           who is speaking
 * @param text           what they said
 * @param cacheBreakpoint whether to ask the provider to cache the prefix ending here
 */
public record PromptMessage(
        ChatRole role,
        String text,
        boolean cacheBreakpoint,
        List<ToolCall> toolCalls,
        String toolCallId) {

    public PromptMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = toolCallId == null ? "" : toolCallId;
    }

    /** A message carrying neither a breakpoint nor any part of a tool exchange. */
    public PromptMessage(ChatRole role, String text, boolean cacheBreakpoint) {
        this(role, text, cacheBreakpoint, List.of(), "");
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
        return new PromptMessage(role, text, true, toolCalls, toolCallId);
    }

    /**
     * The assistant turn in which the model asked for tools.
     *
     * <p>It must be sent back verbatim alongside the results, ids and all. A provider
     * matches each result to the call that produced it, and rejects results for a call it
     * cannot see.
     */
    public static PromptMessage assistantToolCalls(String text, List<ToolCall> calls) {
        return new PromptMessage(ChatRole.ASSISTANT, text, false, calls, "");
    }

    /** One tool's result, answering the call with {@code callId}. */
    public static PromptMessage toolResult(String callId, String text) {
        return new PromptMessage(ChatRole.TOOL, text, false, List.of(), callId);
    }

    /** Whether this message is part of a tool exchange rather than plain conversation. */
    public boolean isToolExchange() {
        return !toolCalls.isEmpty() || !toolCallId.isEmpty();
    }
}
