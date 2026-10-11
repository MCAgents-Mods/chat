package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.common.http.JdkHttpTransport;
import io.github.mcagentsmods.core.common.model.OpenRouterClient;

import java.util.Objects;

/**
 * Builds the model client chat talks to providers through.
 *
 * <p>The client itself is core's {@link OpenRouterClient}, shared by every MCAgents mod.
 * What is chat's is put around it here, once, so the game and the console cannot build two
 * different stacks: keys read from chat's encrypted store per request, routes read from the
 * player's settings, embeddings cached locally, and chat's own name sent to OpenRouter.
 */
public final class ChatModelClients {

    /** What OpenRouter is told about chat's requests. Names the mod, never the player. */
    public static final OpenRouterClient.Attribution ATTRIBUTION =
            new OpenRouterClient.Attribution("https://github.com/MCAgents-Mods/chat", MCAgents.MOD_NAME);

    private ChatModelClients() {
        throw new AssertionError("No instances.");
    }

    /**
     * The client chat uses, over {@code database}'s keys and settings.
     *
     * <p>Routing and keys are read per request, so a key added or a base URL set while the
     * game is running takes effect without a restart. The caller owns the client and closes
     * it at shutdown.
     *
     * @param database the player's local database
     * @return a new client
     */
    public static ModelClient create(LocalDatabase database) {
        Objects.requireNonNull(database, "database");
        return new CachingModelClient(new OpenRouterClient(
                new JdkHttpTransport(),
                new RepositoryApiKeySource(database.apiKeys()),
                new StoredProviderRoutes(database.settings(), database.apiKeys()),
                ATTRIBUTION));
    }
}
