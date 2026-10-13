package io.github.mcagentsmods.chat.api.agent;

/**
 * Answers "where is the player right now" at the moment a question is asked.
 *
 * <p>This is the seam the environment crosses. {@code platforms/shared} implements it from
 * the connection events each loader forwards; {@code common/} calls it while assembling a
 * prompt and never learns that Minecraft exists.
 *
 * <h2>Why this one is not a {@code CompletableFuture}</h2>
 *
 * <p>Every method in {@code api/} that touches the database, the network, or the filesystem
 * returns a future, and this touches none of them. An implementation reads a field that a
 * connection event has already written — a memory read, on any thread, costing nothing. A
 * future here would be ceremony around a volatile load, and the assembler would have to
 * compose it for no gain.
 */
@FunctionalInterface
public interface EnvironmentSource {

    /**
     * Where the player is, as of now.
     *
     * <p>Never null. A source that cannot tell says {@link PlayerEnvironment.Kind#UNKNOWN}
     * rather than guessing.
     */
    PlayerEnvironment current();

    /**
     * The source used when nothing is watching the game, such as a test. It reports {@link PlayerEnvironment.Kind#UNKNOWN} forever, and the
     * prompt simply carries no environment note.
     */
    static EnvironmentSource unknown() {
        return PlayerEnvironment::unknown;
    }
}
