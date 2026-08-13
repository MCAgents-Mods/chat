package io.github.mcagents.clientfabric.api.agent;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Supplies the key a request should use, and is told when one is rejected.
 *
 * <p>The model client depends on this rather than on the database, so key rotation can be
 * exercised without a database and so the client never learns where keys are stored.
 *
 * <p>Nothing here ever renders or logs a key value —
 * {@code .agents/security/api-keys.md}.
 */
public interface ApiKeySource {

    /** The key to use next, or empty when the player has none active. */
    CompletableFuture<Optional<String>> next();

    /**
     * Reports that the provider rejected {@code key}.
     *
     * <p>The key is deactivated rather than deleted, so the player can see what happened
     * instead of watching a key silently vanish.
     */
    CompletableFuture<Void> reject(String key);
}
