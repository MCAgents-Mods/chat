package io.github.mcagents.clientfabric.fabric.client;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.ClientPaths;
import io.github.mcagents.clientfabric.website.LocalWebConsole;

import java.util.Optional;

/**
 * Owns the running web console, for as long as the player wants it.
 *
 * <h2>Why this outlives the screen that started it</h2>
 *
 * <p>The obvious thing — start the console when the screen opens and stop it when the
 * screen closes — is wrong here, and wrong in a way the player would feel immediately. They
 * open the console precisely so they can go back to the game and read in a browser beside
 * it. Tying the server's life to a screen nobody is looking at would close the port the
 * moment it started being useful.
 *
 * <p>So the console is held here instead: started once, stopped when the player asks or
 * when the game exits. That is the whole difference between this and the wallet bridge,
 * which is a listener for one handshake and must never outlive it.
 */
public final class WebConsoleHolder {

    private static volatile LocalWebConsole console;

    private WebConsoleHolder() {
        throw new AssertionError("No instances.");
    }

    /** The console, if one is running. */
    public static synchronized Optional<LocalWebConsole> current() {
        LocalWebConsole running = console;
        if (running != null && !running.isRunning()) {
            // It stopped without going through stop() — treat it as gone rather than
            // handing out a handle to a closed port.
            console = null;
            return Optional.empty();
        }
        return Optional.ofNullable(running);
    }

    /**
     * Starts the console, or returns the one already running.
     *
     * <p>Idempotent on purpose: pressing the button twice should show the same address, not
     * open a second server the player has no way to find again.
     */
    public static synchronized LocalWebConsole start(LocalDatabase database, ClientPaths paths) {
        return current().orElseGet(() -> {
            // The same agent the in-game screen uses, so a question asked in the browser is
            // answered exactly as one asked in the game — and the same model client, so
            // embedding a pack from the console reuses the connection pool and the embedding
            // cache the game already has rather than opening a second stack beside it.
            //
            // The persona store is shared for a sharper reason than either: the console can
            // write AGENTS.md, and a store of its own would leave the game answering from
            // the text the player had just replaced.
            LocalWebConsole started = LocalWebConsole.start(
                    database,
                    AgentFactory.create(database, paths),
                    KnowledgeSyncFactory.service(),
                    AgentFactory.modelClient(database),
                    AgentFactory.personaStore(paths));
            console = started;
            return started;
        });
    }

    /** Stops the console if one is running. Safe to call when none is. */
    public static synchronized void stop() {
        LocalWebConsole running = console;
        console = null;
        if (running != null) {
            running.close();
        }
    }
}
