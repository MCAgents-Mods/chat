package io.github.mcagents.clientfabric.common.agent;

import java.util.concurrent.CompletableFuture;

/** Sends an {@link HttpCall}. The seam that keeps the model client testable. */
@FunctionalInterface
public interface HttpTransport extends AutoCloseable {

    /**
     * Sends the call.
     *
     * <p>The future completes exceptionally only on a transport failure — a non-2xx status
     * is a normal completion carrying that status, because the client needs the body to
     * tell a rejected key from a rate limit.
     */
    CompletableFuture<HttpReply> send(HttpCall call);

    @Override
    default void close() {
    }
}
