package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.agent.ApiKeySource;
import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.storage.ApiKeyRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Serves keys out of the player's local database.
 *
 * <p>Thin on purpose. The interesting decision — deactivate a rejected key rather than
 * delete it, so the player can see what happened — already lives in the repository, and
 * this only connects it to the model client.
 */
public final class RepositoryApiKeySource implements ApiKeySource {

    private final ApiKeyRepository repository;

    public RepositoryApiKeySource(ApiKeyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public CompletableFuture<Optional<String>> next() {
        return repository.findNextActive().thenApply(key -> key.map(ApiKey::value));
    }

    @Override
    public CompletableFuture<Optional<String>> next(String platform) {
        // Never another platform's key: the repository decides that, and this passes the
        // question through rather than second-guessing the answer.
        return repository.findNextActive(platform).thenApply(key -> key.map(ApiKey::value));
    }

    @Override
    public CompletableFuture<Void> reject(String key) {
        // Deactivated, never deleted: a key that vanishes silently looks like a bug, and a
        // key shown as inactive explains itself.
        return repository.deactivate(key).thenAccept(ignored -> {
        });
    }
}
