package io.github.mcagentsmods.chat.api.agent;

/**
 * What a request cost, as the provider reported it.
 *
 * <p>{@code cachedTokens} is the part of the prompt the provider served from its cache
 * rather than reprocessing. It is the only way to tell whether prompt caching is actually
 * working: a request whose prefix should have been reused but reports zero cached tokens
 * means the prefix moved, and the cause is nearly always something non-stable placed too
 * early in the message list.
 *
 * @param promptTokens     tokens in the request
 * @param completionTokens tokens in the answer
 * @param cachedTokens     prompt tokens served from the provider's cache
 */
public record TokenUsage(int promptTokens, int completionTokens, int cachedTokens) {

    /** What is reported when a provider sends no usage block. */
    public static final TokenUsage UNKNOWN = new TokenUsage(0, 0, 0);

    public TokenUsage(int promptTokens, int completionTokens) {
        this(promptTokens, completionTokens, 0);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    /** Whether any of the prompt was reused. */
    public boolean hadCacheHit() {
        return cachedTokens > 0;
    }

    /**
     * The share of the prompt that was reused, between 0 and 1.
     *
     * <p>Worth watching over a conversation: it should climb as the history grows, because
     * every prior turn is part of the stable prefix. If it stays at zero, caching is not
     * working.
     */
    public double cacheHitRate() {
        return promptTokens == 0 ? 0.0 : (double) cachedTokens / promptTokens;
    }
}
