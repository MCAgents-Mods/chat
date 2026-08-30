package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.ApiKeySource;
import io.github.mcagents.clientfabric.api.model.ModelPlatform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * An in-memory key source that records which keys were rejected.
 *
 * <p>Keys given to the constructor are the gateway's, which is what every key was. A test
 * that needs a platform's own key adds one with {@link #forPlatform(String, String)}, and the
 * source then answers per platform exactly as the repository does — including answering with
 * nothing for a platform it holds no key for.
 */
public final class StubKeySource implements ApiKeySource {

    private final List<String> active;
    private final Map<String, List<String>> byPlatform = new LinkedHashMap<>();
    private final Set<String> rejected = new LinkedHashSet<>();

    public StubKeySource(String... keys) {
        this.active = new ArrayList<>(List.of(keys));
    }

    /** Adds a key that answers only for {@code platform}. */
    public StubKeySource forPlatform(String platform, String key) {
        byPlatform.computeIfAbsent(platform, ignored -> new ArrayList<>()).add(key);
        return this;
    }

    @Override
    public CompletableFuture<Optional<String>> next() {
        return CompletableFuture.completedFuture(
                active.stream().filter(key -> !rejected.contains(key)).findFirst());
    }

    @Override
    public CompletableFuture<Optional<String>> next(String platform) {
        List<String> forPlatform = byPlatform.get(platform);
        if (forPlatform == null) {
            // The constructor's keys are the gateway's. A platform with none registered has
            // none, and never borrows another platform's — the repository behaves the same.
            return ModelPlatform.isOpenRouter(platform)
                    ? next()
                    : CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(
                forPlatform.stream().filter(key -> !rejected.contains(key)).findFirst());
    }

    @Override
    public CompletableFuture<Void> reject(String key) {
        rejected.add(key);
        return CompletableFuture.completedFuture(null);
    }

    public Set<String> rejected() {
        return Set.copyOf(rejected);
    }
}
