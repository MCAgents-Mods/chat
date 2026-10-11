package io.github.mcagentsmods.chat.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * The player's own instructions, at {@code {minecraft_dir}/MCAgents/AGENTS.md}.
 *
 * <p>One store per game session, shared by everything that reads or writes the file: the
 * agent that puts it at the front of every prompt, the in-game editor, and the web console.
 * <strong>Sharing it is the feature.</strong> A save made in the browser has to be the
 * persona the next in-game question is asked with, and two stores each holding their own
 * copy is exactly how that stops being true.
 *
 * <h2>Every method here is asynchronous, without exception</h2>
 *
 * <p>This is a file on a disk that may be a network share, a spinning platter, or a drive
 * that is busy. Minecraft renders on the client thread, and a read of unknown duration on
 * that thread is a frozen game. See {@code .agents/rules/repository.md}.
 */
public interface PersonaStore {

    /**
     * The player's instructions as they stand.
     *
     * <p>Creates the file with a starting persona if it is not there, so a player who has
     * never opened the editor still has something to edit. Blank is a legitimate answer: a
     * player may empty the file, and the assembler substitutes its own default rather than
     * sending nothing.
     */
    CompletableFuture<String> read();

    /**
     * Writes {@code instructions} and makes them the persona from the very next question on.
     *
     * <p>The reload is part of the contract rather than a courtesy. A save that left the
     * previous text in front of the model until the game restarted would look like a button
     * that did nothing.
     *
     * @return what was actually stored, which is what a screen should show afterwards
     */
    CompletableFuture<String> save(String instructions);

    /**
     * Creates the file with a starting persona if it does not exist yet.
     *
     * <p>Called at startup so the file is on disk and editable before anybody asks for it.
     * It never touches a file that is already there — the persona is the player's writing,
     * and an update that rewrote it would be data loss dressed as a default.
     *
     * @return whether this call is what created the file
     */
    CompletableFuture<Boolean> ensureCreated();

    /**
     * The store used where there is no game directory to read from — a console started
     * outside the mod, or a test that is not about the persona.
     *
     * <p>It fails rather than inventing an empty file: a screen that saved into this would
     * otherwise report success and write nothing.
     */
    static PersonaStore notConfigured() {
        return new PersonaStore() {
            private static final String REASON =
                    "No AGENTS.md is available: this console was started without a game directory.";

            @Override
            public CompletableFuture<String> read() {
                return CompletableFuture.failedFuture(new IllegalStateException(REASON));
            }

            @Override
            public CompletableFuture<String> save(String instructions) {
                return CompletableFuture.failedFuture(new IllegalStateException(REASON));
            }

            @Override
            public CompletableFuture<Boolean> ensureCreated() {
                return CompletableFuture.completedFuture(false);
            }
        };
    }
}
