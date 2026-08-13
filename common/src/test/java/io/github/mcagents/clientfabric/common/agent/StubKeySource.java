package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.ApiKeySource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** An in-memory key source that records which keys were rejected. */
public final class StubKeySource implements ApiKeySource {

    private final List<String> active;
    private final Set<String> rejected = new LinkedHashSet<>();

    public StubKeySource(String... keys) {
        this.active = new ArrayList<>(List.of(keys));
    }

    @Override
    public CompletableFuture<Optional<String>> next() {
        return CompletableFuture.completedFuture(
                active.stream().filter(key -> !rejected.contains(key)).findFirst());
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
