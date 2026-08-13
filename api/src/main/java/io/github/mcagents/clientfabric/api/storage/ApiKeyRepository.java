package io.github.mcagents.clientfabric.api.storage;

import io.github.mcagents.clientfabric.api.model.ApiKey;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The player's OpenRouter API keys.
 *
 * <p>A player may store several. The mod uses the first active one; when a key is rejected
 * it is deactivated and the next takes over, so a rotation does not interrupt a
 * conversation.
 *
 * <p>Every method is asynchronous — see {@link LocalDatabase} for why.
 */
public interface ApiKeyRepository {

    /** Every stored key, active or not, oldest first. */
    CompletableFuture<List<ApiKey>> findAll();

    /** The key the mod should use next, or empty if the player has none active. */
    CompletableFuture<Optional<ApiKey>> findNextActive();

    /**
     * Stores a key, or reactivates it if it is already present.
     *
     * @param value the key; must not be blank
     * @return the stored key
     */
    CompletableFuture<ApiKey> add(String value);

    /**
     * Marks a key unusable without discarding it, so the player can see it was rejected.
     *
     * @return whether a key was changed
     */
    CompletableFuture<Boolean> deactivate(String value);

    /**
     * Removes a key entirely.
     *
     * @return whether a key was removed
     */
    CompletableFuture<Boolean> remove(String value);
}
