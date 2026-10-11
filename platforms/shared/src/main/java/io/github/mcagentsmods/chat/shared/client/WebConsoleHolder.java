package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.chat.website.ChatWebModule;
import io.github.mcagentsmods.core.api.web.WebConsole;
import io.github.mcagentsmods.core.common.web.CoreWebRegistry;

import java.net.URI;
import java.util.Optional;

/**
 * Chat's place in MCAgents core's web console.
 *
 * <p>Core owns the console: one server for every MCAgents mod, opened on demand and closed
 * when the game exits. Chat registers its pages into it once, the first time the player asks
 * for the console, and takes its own pages out again when the game shuts down, before the
 * database those pages read is closed.
 *
 * <h2>Why the console outlives the screen that opened it</h2>
 *
 * <p>The player opens the console so they can go back to the game and read in a browser
 * beside it. Tying the server's life to a screen nobody is looking at would close the port
 * the moment it started being useful. So it runs until the player stops it or the game exits
 * — which is the whole difference between this and the wallet bridge, a listener for one
 * handshake that must never outlive it.
 */
public final class WebConsoleHolder {

    /** Whether chat's pages are in core's registry. */
    private static boolean registered;

    private WebConsoleHolder() {
        throw new AssertionError("No instances.");
    }

    /**
     * Where chat's pages are, if the console is running and holds them.
     *
     * <p>Another mod may have opened the console before chat registered; until chat does,
     * there is no chat address to show.
     *
     * @return the address with the token, or empty
     */
    public static synchronized Optional<URI> address() {
        if (!registered) {
            return Optional.empty();
        }
        return CoreWebRegistry.shared().current().map(console -> console.url(ChatWebModule.ID));
    }

    /**
     * Registers chat's pages if they are not yet, and opens the console if it is not running.
     *
     * <p>The pages use the agent, model client and persona store the game already has, so a
     * question asked in the browser is answered as one asked in the game.
     *
     * @param database the player's local database
     * @param paths    where the game's files are
     * @return the address of chat's first page, with the token
     */
    public static synchronized URI start(LocalDatabase database, ClientPaths paths) {
        if (!registered) {
            CoreWebRegistry.shared().register(ChatWebModule.create(
                    database,
                    AgentFactory.create(database, paths),
                    AgentFactory.modelClient(database),
                    AgentFactory.personaStore(paths)));
            registered = true;
        }
        WebConsole console = CoreWebRegistry.shared().open();
        return console.url(ChatWebModule.ID);
    }

    /**
     * Stops the console at the player's request.
     *
     * <p>The console is shared, so this closes every mod's pages, as the player asked. Chat's
     * stay registered and come back with the next start.
     */
    public static synchronized void stopConsole() {
        CoreWebRegistry.shared().close();
    }

    /**
     * Takes chat's pages out of the console. Called on game shutdown, before the database
     * closes, so no request can reach a store that is closing.
     */
    public static synchronized void stop() {
        if (registered) {
            CoreWebRegistry.shared().unregister(ChatWebModule.ID);
            registered = false;
        }
    }
}
