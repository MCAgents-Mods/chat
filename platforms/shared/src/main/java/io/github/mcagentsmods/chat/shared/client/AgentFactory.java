package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.agents.common.Agents;
import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.chat.common.agent.CachingModelClient;
import io.github.mcagentsmods.chat.common.agent.ChatModelClients;
import io.github.mcagentsmods.chat.common.agent.FilePersonaStore;
import io.github.mcagentsmods.chat.common.agent.OpenRouterAgent;
import io.github.mcagentsmods.core.api.model.ModelClient;

/**
 * Builds the agent the chat screen uses, and owns the model client's lifetime.
 *
 * <p>The client is created once and reused: it holds an HTTP connection pool and a
 * scheduler for retry backoff, and building one per question would throw both away every
 * time.
 *
 * <p>Nothing here checks whether the player has a key. That check belongs at request time,
 * not at construction time — a player who adds a key while the game is running should not
 * have to restart, and a player who has none still gets a screen that opens.
 */
public final class AgentFactory {

    private static volatile ModelClient modelClient;
    private static volatile PersonaStore personaStore;

    private AgentFactory() {
        throw new AssertionError("No instances.");
    }

    /**
     * The agent for this session.
     *
     * @param database the player's local store
     * @param paths    the mod's on-disk layout, for the persona file
     */
    public static Agent create(LocalDatabase database, ClientPaths paths) {
        ModelClient client = sharedClient(database);
        // The agents mod's agent, over every tool any mod registered: the MCP servers' tools
        // and the clone tool are put there by McpHostFactory, and kept there as servers come
        // and go, so a server connected since the screen opened is offered at once.
        return new OpenRouterAgent(database, client, personaStore(paths),
                GameEnvironment.source(), Agents.agent(client));
    }

    /**
     * The one persona store this game session uses.
     *
     * <p>Shared for the same reason the model client is, and more sharply: the editor
     * screen, the web console, and the agent all read and write {@code AGENTS.md}, and a
     * second store would hold a second copy of its contents. The player would save in the
     * browser, ask in the game, and be answered by the persona they had just replaced.
     */
    public static PersonaStore personaStore(ClientPaths paths) {
        PersonaStore existing = personaStore;
        if (existing != null) {
            return existing;
        }
        synchronized (AgentFactory.class) {
            if (personaStore == null) {
                personaStore = new FilePersonaStore(paths.instructions());
            }
            return personaStore;
        }
    }

    /**
     * The one model client this game session uses.
     *
     * <p>Exposed so the web console embeds packs through the same client the agent asks
     * questions with: same connection pool, same retry scheduler, same embedding cache. A
     * second stack beside it would double all three and share none of them.
     */
    public static ModelClient modelClient(LocalDatabase database) {
        return sharedClient(database);
    }

    private static ModelClient sharedClient(LocalDatabase database) {
        ModelClient existing = modelClient;
        if (existing != null) {
            return existing;
        }
        synchronized (AgentFactory.class) {
            if (modelClient == null) {
                // Embeddings are cached locally; completions are not, and the prompt
                // itself is reused provider-side through the breakpoints the assembler
                // places. See CachingModelClient and ChatModelClients.
                modelClient = ChatModelClients.create(database);
            }
            return modelClient;
        }
    }

    /** Releases the HTTP client and its retry scheduler. Called on game shutdown. */
    public static void close() {
        synchronized (AgentFactory.class) {
            if (modelClient != null) {
                modelClient.close();
                modelClient = null;
            }
        }
    }
}
