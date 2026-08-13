package io.github.mcagents.clientfabric.common;

import io.github.mcagents.clientfabric.api.MCAgents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Everything the mod owns on disk, resolved from a game directory it is handed.
 *
 * <p>This class deliberately knows nothing about Minecraft. The platform module resolves
 * the game directory and calls {@link #under(Path)}; from there the layout is the same
 * whether it came from a real installation or from a temporary directory in a test.
 */
public final class ClientPaths {

    private final Path dataDirectory;

    private ClientPaths(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    /**
     * Describes the mod's layout under {@code gameDirectory}. Creates nothing — call
     * {@link #createDataDirectory()} for that.
     *
     * @param gameDirectory the Minecraft game directory
     */
    public static ClientPaths under(Path gameDirectory) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        return new ClientPaths(gameDirectory.resolve(MCAgents.DATA_DIRECTORY).normalize());
    }

    /** {@code {game_dir}/MCAgents} — the directory holding every file the mod owns. */
    public Path dataDirectory() {
        return dataDirectory;
    }

    /** {@code {game_dir}/MCAgents/data.db} — the local SQLite database. */
    public Path database() {
        return dataDirectory.resolve(MCAgents.DATABASE_FILE);
    }

    /** {@code {game_dir}/MCAgents/AGENTS.md} — the player's own agent instructions. */
    public Path instructions() {
        return dataDirectory.resolve(MCAgents.INSTRUCTIONS_FILE);
    }

    /**
     * Creates the data directory if it does not exist, and returns this instance so the
     * call can be chained.
     *
     * @throws IOException if the directory cannot be created
     */
    public ClientPaths createDataDirectory() throws IOException {
        Files.createDirectories(dataDirectory);
        return this;
    }

    @Override
    public String toString() {
        return "ClientPaths[" + dataDirectory + "]";
    }
}
