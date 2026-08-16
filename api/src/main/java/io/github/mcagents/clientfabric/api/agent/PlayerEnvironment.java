package io.github.mcagents.clientfabric.api.agent;

import java.util.Objects;

/**
 * Where the player is playing at the moment they ask a question.
 *
 * <p>Only the platform module can know this — it is the one that sees Minecraft — so it
 * travels down as this record rather than as a Minecraft type. {@code common/} assembles it
 * into the prompt without ever learning what a server list entry is.
 *
 * <h2>Three states, and the third is not a failure</h2>
 *
 * <p>A player is in a single-player world, on a multiplayer server, or in neither — the
 * main menu, a world still loading, a connection that has just dropped. {@link Kind#UNKNOWN}
 * is that third state, and it is reported rather than guessed at: telling the model the
 * player is in single player when the mod does not know would be a confident wrong answer
 * about the one thing this exists to get right.
 *
 * @param kind          which of the three states this is
 * @param serverAddress the address the player is connected to, and only for
 *                      {@link Kind#MULTIPLAYER}
 */
public record PlayerEnvironment(Kind kind, String serverAddress) {

    /** Where a player can be. */
    public enum Kind {
        /** A world opened from the single-player menu, including one shared to LAN. */
        SINGLE_PLAYER,
        /** A server the player connected to, named by {@link #serverAddress()}. */
        MULTIPLAYER,
        /** Not in a world: the main menu, or a connection that has ended. */
        UNKNOWN
    }

    /**
     * What the model is told when the player is not on a server.
     *
     * <p>A constant rather than a sentence written at the call site, because two places
     * build this line — the prompt and the tests that pin it — and a wording that drifted
     * between them would be a difference nobody could see.
     */
    public static final String SINGLE_PLAYER_CONTEXT = "Player is playing in single player.";

    private static final PlayerEnvironment SINGLE_PLAYER =
            new PlayerEnvironment(Kind.SINGLE_PLAYER, "");
    private static final PlayerEnvironment UNKNOWN = new PlayerEnvironment(Kind.UNKNOWN, "");

    public PlayerEnvironment {
        Objects.requireNonNull(kind, "kind");
        serverAddress = serverAddress == null ? "" : serverAddress.trim();
        if (kind == Kind.MULTIPLAYER && serverAddress.isEmpty()) {
            throw new IllegalArgumentException(
                    "A multiplayer environment is the address it names; it cannot be blank");
        }
        if (kind != Kind.MULTIPLAYER && !serverAddress.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only a multiplayer environment carries a server address");
        }
    }

    /** A single-player world. */
    public static PlayerEnvironment singlePlayer() {
        return SINGLE_PLAYER;
    }

    /** A server, named by the address the player connected to. */
    public static PlayerEnvironment multiplayer(String serverAddress) {
        return new PlayerEnvironment(Kind.MULTIPLAYER, serverAddress);
    }

    /** Not in a world, or not yet established. */
    public static PlayerEnvironment unknown() {
        return UNKNOWN;
    }

    /** Whether there is anything worth telling the model. */
    public boolean isKnown() {
        return kind != Kind.UNKNOWN;
    }

    /**
     * The context line itself: the server address, or the single-player sentence.
     *
     * <p>Blank when nothing is known, which is what keeps the note out of the prompt
     * entirely rather than putting an empty heading in front of every question.
     */
    public String context() {
        return switch (kind) {
            case SINGLE_PLAYER -> SINGLE_PLAYER_CONTEXT;
            case MULTIPLAYER -> serverAddress;
            case UNKNOWN -> "";
        };
    }
}
