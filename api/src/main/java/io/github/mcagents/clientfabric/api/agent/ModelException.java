package io.github.mcagents.clientfabric.api.agent;

/**
 * A model request failed.
 *
 * <p>The {@link Kind} is what callers branch on, because the right response differs sharply
 * between them: a rejected key rotates, a rate limit retries, a bad request does not.
 */
public class ModelException extends RuntimeException {

    /** Why the request failed, in the terms that decide what to do about it. */
    public enum Kind {
        /** The key was rejected. Deactivate it and try the next one. */
        AUTHENTICATION,
        /** Rate limited or out of credit. Back off and retry. */
        RATE_LIMIT,
        /** The provider failed. Back off and retry. */
        SERVER,
        /** The request was wrong — a bad model id, a malformed body. Retrying will not help. */
        REQUEST,
        /** The network failed, or nothing answered. Retrying may help. */
        TRANSPORT,
        /**
         * The provider answered successfully but the body could not be used.
         *
         * <p>Deliberately not retryable: the request already succeeded, so the player has
         * already been billed for it, and sending it again would bill them again for the
         * same broken answer.
         */
        MALFORMED_RESPONSE,
        /** The player has no usable key stored. */
        NO_KEY
    }

    private final Kind kind;

    public ModelException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ModelException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Whether retrying the same request could plausibly succeed. */
    public boolean isRetryable() {
        return kind == Kind.RATE_LIMIT || kind == Kind.SERVER || kind == Kind.TRANSPORT;
    }
}
