package io.github.mcagentsmods.chat.common;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.mcp.RepositoryCoordinate;
import io.github.mcagentsmods.core.common.file.ModFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the mod owns on disk, resolved from a game directory it is handed.
 *
 * <p>This class deliberately knows nothing about Minecraft. The platform module resolves
 * the game directory and calls {@link #under(Path)}; from there the layout is the same
 * whether it came from a real installation or from a temporary directory in a test.
 */
public final class ClientPaths {

    /** SQLite's journal files, which travel with the database when it moves. */
    private static final List<String> DATABASE_JOURNALS = List.of("-wal", "-shm", "-journal");

    private final Path dataDirectory;
    private final Path legacyDirectory;

    private ClientPaths(Path dataDirectory, Path legacyDirectory) {
        this.dataDirectory = dataDirectory;
        this.legacyDirectory = legacyDirectory;
    }

    /**
     * Describes the mod's layout under {@code gameDirectory}. Creates nothing — call
     * {@link #createDataDirectory()} for that.
     *
     * @param gameDirectory the Minecraft game directory
     */
    public static ClientPaths under(Path gameDirectory) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Path mods = gameDirectory.resolve(MCAgents.DATA_DIRECTORY).normalize();
        return new ClientPaths(mods.resolve(MCAgents.MOD_DIRECTORY), mods);
    }

    /** {@code {game_dir}/MCAgents/chat} — the directory holding every file the mod owns. */
    public Path dataDirectory() {
        return dataDirectory;
    }

    /** {@code {game_dir}/MCAgents/chat/data.db} — the local SQLite database. */
    public Path database() {
        return dataDirectory.resolve(MCAgents.DATABASE_FILE);
    }

    /** {@code {game_dir}/MCAgents/chat/AGENTS.md} — the player's own agent instructions. */
    public Path instructions() {
        return dataDirectory.resolve(MCAgents.INSTRUCTIONS_FILE);
    }

    /**
     * {@code {game_dir}/MCAgents/chat/mcps} — external MCP servers, one directory per
     * {@code {owner}/{repository}}.
     */
    public Path mcpDirectory() {
        return dataDirectory.resolve(MCAgents.MCP_DIRECTORY);
    }

    /** {@code {game_dir}/MCAgents/chat/mcps/mcp_servers.json} — the optional server catalog. */
    public Path mcpServerCatalog() {
        return mcpDirectory().resolve(MCAgents.MCP_SERVERS_FILE);
    }

    /**
     * Where a cloned repository belongs.
     *
     * <p>The result is normalized and checked to be under {@link #mcpDirectory()}. The
     * coordinate has already rejected traversal, so this is a second lock on the same door
     * — cheap, and the one that would still hold if the first were ever loosened.
     *
     * @throws IllegalArgumentException if the resolved path escapes the mcps directory
     */
    public Path mcpRepository(RepositoryCoordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        Path root = mcpDirectory();
        Path resolved = root.resolve(coordinate.owner()).resolve(coordinate.repository()).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Repository " + coordinate.slug() + " resolves outside " + root);
        }
        return resolved;
    }

    /** Creates {@link #mcpDirectory()} if it does not exist, and returns it. */
    public Path createMcpDirectory() throws IOException {
        return Files.createDirectories(mcpDirectory());
    }

    /**
     * {@code {game_dir}/MCAgents} — where chat kept {@code data.db}, {@code AGENTS.md} and
     * {@code mcps/} before 0.15.0, loose beside every other MCAgents mod's directory.
     */
    public Path legacyDirectory() {
        return legacyDirectory;
    }

    /**
     * Moves the files chat kept in {@link #legacyDirectory()} before 0.15.0 into
     * {@link #dataDirectory()}: the database with its journal files, {@code AGENTS.md}, and the
     * whole {@code mcps/} directory. Each moves only when it is not in the new place yet; one
     * that is stays, and the old copy is left alone. Call it before anything opens them. Safe to
     * repeat.
     *
     * @return the files and directories this call moved, in their new place
     * @throws IOException if a move was needed and failed; what was not moved yet stays put
     */
    public List<Path> moveLegacyFiles() throws IOException {
        List<Path> moved = new ArrayList<>();
        Path database = database();
        Path legacyDatabase = legacyDirectory.resolve(MCAgents.DATABASE_FILE);
        // The journals belong to the database beside them, so they move only with it, and
        // first: a database moved without its write-ahead log would lose what the log holds.
        if (Files.exists(legacyDatabase) && !Files.exists(database)) {
            for (String journal : DATABASE_JOURNALS) {
                Path target = database.resolveSibling(MCAgents.DATABASE_FILE + journal);
                if (ModFiles.moveIfAbsent(legacyDirectory.resolve(MCAgents.DATABASE_FILE + journal), target)) {
                    moved.add(target);
                }
            }
            if (ModFiles.moveIfAbsent(legacyDatabase, database)) {
                moved.add(database);
            }
        }
        for (String name : List.of(MCAgents.INSTRUCTIONS_FILE, MCAgents.MCP_DIRECTORY)) {
            Path target = dataDirectory.resolve(name);
            if (ModFiles.moveIfAbsent(legacyDirectory.resolve(name), target)) {
                moved.add(target);
            }
        }
        return List.copyOf(moved);
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
