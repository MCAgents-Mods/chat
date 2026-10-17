package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.model.ProviderRoutes;
import io.github.mcagentsmods.core.common.http.JdkHttpTransport;
import io.github.mcagentsmods.core.common.model.OpenRouterClient;

import java.util.Objects;

/**
 * Builds the model client chat talks to providers through.
 *
 * <p>The client itself is core's {@link OpenRouterClient}, shared by every MCAgents mod.
 * What is chat's is put around it here, once, so no two callers can build two different
 * stacks: keys read from chat's encrypted store per request, routes read from core's global
 * model per request, embeddings cached locally, and chat's own name sent to OpenRouter.
 */
public final class ChatModelClients {

    /** What OpenRouter is told about chat's requests. Names the mod, never the player. */
    public static final OpenRouterClient.Attribution ATTRIBUTION =
            new OpenRouterClient.Attribution("https://github.com/MCAgents-Mods/chat", MCAgents.MOD_NAME);

    private ChatModelClients() {
        throw new AssertionError("No instances.");
    }

    /**
     * The client chat uses, over {@code database}'s keys.
     *
     * <p>Routing and keys are read per request, so a key or a custom provider added while the
     * game is running takes effect without a restart. The caller owns the client and closes
     * it at shutdown.
     *
     * @param database the player's local database
     * @param routes   where a chosen provider is called; in the game, core's
     *                 {@code Models.routes()}, which knows the player's custom providers
     * @return a new client
     */
    public static ModelClient create(LocalDatabase database, ProviderRoutes routes) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(routes, "routes");
        return new CachingModelClient(new OpenRouterClient(
                new JdkHttpTransport(),
                new RepositoryApiKeySource(database.apiKeys()),
                routes,
                ATTRIBUTION));
    }
}
