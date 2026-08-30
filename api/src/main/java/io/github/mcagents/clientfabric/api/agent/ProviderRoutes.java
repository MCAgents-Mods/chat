package io.github.mcagents.clientfabric.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Decides how a model is reached: directly, or through OpenRouter.
 *
 * <p>The model client depends on this rather than on storage, for the same reason it depends
 * on {@link ApiKeySource} rather than on the database — routing can then be exercised without
 * a database, and the client never learns where a base URL is configured.
 *
 * <p><strong>Resolution never fails.</strong> Every unknown ends in the gateway route, because
 * a player whose direct URL is missing, unreadable, or unusable should get the answer they
 * would have got before direct routes existed, not an error about configuration they never
 * knowingly did.
 */
@FunctionalInterface
public interface ProviderRoutes {

    /**
     * Where a request for {@code storedModelName} goes.
     *
     * @param storedModelName the id as stored — {@code {platform}/{model}}, or an unqualified
     *                        id the player typed
     */
    CompletableFuture<ProviderRoute> route(String storedModelName);

    /** The routing every request had before direct routes existed: everything through OpenRouter. */
    static ProviderRoutes throughOpenRouter() {
        return model -> CompletableFuture.completedFuture(ProviderRoute.throughOpenRouter(model));
    }
}
