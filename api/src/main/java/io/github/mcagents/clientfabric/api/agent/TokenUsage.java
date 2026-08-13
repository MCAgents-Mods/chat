package io.github.mcagents.clientfabric.api.agent;

/**
 * What a request cost, as the provider reported it.
 *
 * <p>Worth carrying because the player pays for it. A player who can see that a long
 * conversation is costing more every turn can start a new session; one who cannot, cannot.
 *
 * @param promptTokens     tokens in the request
 * @param completionTokens tokens in the answer
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    /** What is reported when a provider sends no usage block. */
    public static final TokenUsage UNKNOWN = new TokenUsage(0, 0);

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
