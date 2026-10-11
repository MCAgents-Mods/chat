package io.github.mcagentsmods.chat.website;

import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.agent.ChatModelClients;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.web.WebConsole;
import io.github.mcagentsmods.core.common.web.CoreWebRegistry;

import java.net.URI;

/**
 * Chat's pages in a console of their own: a fresh core registry with {@link ChatWebModule}
 * registered and the console open, the way the game wires it.
 *
 * <p>The tests drive it over HTTP, so what they prove is chat's pages and routes as a
 * browser reaches them through core — the token, the paths and the answers together.
 */
final class ConsoleUnderTest implements AutoCloseable {

    private final CoreWebRegistry registry;
    private final WebConsole console;

    private ConsoleUnderTest(CoreWebRegistry registry, WebConsole console) {
        this.registry = registry;
        this.console = console;
    }

    static ConsoleUnderTest start(LocalDatabase database, Agent agent) {
        return start(database, agent, ChatModelClients.create(database), PersonaStore.notConfigured());
    }

    static ConsoleUnderTest start(LocalDatabase database, Agent agent, PersonaStore personaStore) {
        return start(database, agent, ChatModelClients.create(database), personaStore);
    }

    static ConsoleUnderTest start(LocalDatabase database, Agent agent, ModelClient modelClient) {
        return start(database, agent, modelClient, PersonaStore.notConfigured());
    }

    static ConsoleUnderTest start(LocalDatabase database, Agent agent, ModelClient modelClient,
                                  PersonaStore personaStore) {
        CoreWebRegistry registry = new CoreWebRegistry();
        registry.register(ChatWebModule.create(database, agent, modelClient, personaStore));
        return new ConsoleUnderTest(registry, registry.open());
    }

    /** Chat's first page, with the token — what the game hands the browser. */
    URI url() {
        return console.url(ChatWebModule.ID);
    }

    int port() {
        return console.port();
    }

    @Override
    public void close() {
        registry.close();
    }
}
