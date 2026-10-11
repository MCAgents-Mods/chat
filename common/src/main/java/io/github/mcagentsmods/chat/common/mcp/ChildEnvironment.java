package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.common.security.TokenCipher;

import java.util.Map;

/**
 * What a process the mod starts is allowed to inherit.
 *
 * <p>A child gets the game's environment by default, and the game's environment holds
 * {@link TokenCipher#KEY_VARIABLE} — the passphrase every stored API key is encrypted with.
 * An MCP server is somebody else's code, and git is given URLs a model chose, so neither is
 * handed it. Together with {@code data.db}, which any process running as the player can
 * read, that one variable would be every key the player has stored.
 */
final class ChildEnvironment {

    private ChildEnvironment() {
        throw new AssertionError("No instances.");
    }

    /**
     * Removes what no child may inherit.
     *
     * <p>Applied before a server's own configured environment, so a variable the player set
     * for a server on purpose still reaches it.
     */
    static void scrub(ProcessBuilder builder) {
        Map<String, String> environment = builder.environment();
        environment.remove(TokenCipher.KEY_VARIABLE);
    }

    /**
     * The environment for git, which must never stop to ask a question.
     *
     * <p>A repository that wants credentials makes git prompt on a terminal the game does not
     * have, and the clone then waits out its whole timeout instead of failing at once.
     */
    static void forGit(ProcessBuilder builder) {
        scrub(builder);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    }
}
