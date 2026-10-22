package io.github.mcagentsmods.chat.shared;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.chat.common.agent.LegacyModelSettings;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.chat.shared.client.AgentFactory;
import io.github.mcagentsmods.chat.shared.client.McpHostFactory;
import io.github.mcagentsmods.core.common.agent.AgentsPaths;
import io.github.mcagentsmods.core.common.model.Models;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client start-up every loader calls, and the state it leaves behind.
 *
 * <p>Each loader's entry point adds chat's menu buttons and registers its events, then calls
 * {@link #start(Path)} with the game directory it knows. From that directory this resolves
 * the mod's layout once, opens the local database, and everything below works from the
 * results.
 *
 * <p>Startup never touches the network and never blocks. Opening the database is
 * asynchronous, so a slow disk delays the mod's features rather than the game's loading
 * screen.
 *
 * <p><strong>No failure here is silent and none is permanent.</strong> Whatever goes wrong
 * is logged at a level that survives into {@code logs/latest.log}, the reason is kept in
 * {@link #storageState()} so the interface can explain itself, and
 * {@link #reopenDatabase()} lets the player retry without restarting the game.
 */
public final class ChatRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(MCAgents.MOD_NAME);

    /** Why the local store is or is not usable, in terms the interface can report. */
    public enum StorageState {
        /** Still being opened. Worth trying again shortly. */
        OPENING,
        /** Open and usable. */
        READY,
        /** Could not be opened. The reason is in the log. */
        FAILED,
        /**
         * Could not be opened because the game has no SQLite driver: no mod jar carries
         * sqlite-jdbc, so the game must provide it. Trying again changes nothing until it does.
         */
        NO_SQLITE
    }

    private static ClientPaths paths;
    private static final AtomicReference<LocalDatabase> DATABASE = new AtomicReference<>();
    private static final AtomicReference<StorageState> STATE =
            new AtomicReference<>(StorageState.OPENING);
    private static final AtomicReference<String> FAILURE = new AtomicReference<>();
    private static final AtomicBoolean OPENING = new AtomicBoolean();

    private ChatRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * Starts the mod on the client. Called once, by the loader's entry point, after it has
     * added chat's menu buttons and registered its connection events.
     *
     * @param gameDirectory the directory Minecraft runs in, as the loader reports it
     */
    public static void start(Path gameDirectory) {
        if (paths != null) {
            throw new IllegalStateException(MCAgents.MOD_NAME + " has already started");
        }
        registerShutdownHook();

        paths = ClientPaths.under(gameDirectory);
        LOGGER.info("{} initialising, data directory {}", MCAgents.MOD_NAME, paths.dataDirectory());
        moveLegacyFiles();

        // Core's global model, which the Models page edits. Core reads the same file when its
        // own start-up runs; whichever start runs first reads it, and the other does nothing.
        // Core's own files move first, in case chat starts before core does.
        AgentsPaths core = AgentsPaths.under(gameDirectory);
        try {
            core.moveLegacyFiles();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not move core's files from {} to {}", core.legacyDirectory(),
                    core.dataDirectory(), failure);
        }
        Models.start(core.modelsFile());

        ensurePersonaFile();
        startMcpHost();
        reopenDatabase();
    }

    /**
     * Moves the database, {@code AGENTS.md} and {@code mcps/} from {@code MCAgents/}, where chat
     * kept them before 0.15.0, into {@code MCAgents/chat/}, so a player who updates keeps their
     * chats, keys, persona and MCP servers. Runs before anything opens them; a failure is logged
     * and the mod starts on whatever is in the new place.
     */
    private static void moveLegacyFiles() {
        try {
            for (Path moved : paths.moveLegacyFiles()) {
                LOGGER.info("Moved {} from {} to {}", moved.getFileName(), paths.legacyDirectory(),
                        paths.dataDirectory());
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not move chat's files from {} to {}; move them by hand to keep them",
                    paths.legacyDirectory(), paths.dataDirectory(), failure);
        }
    }

    /**
     * Starts the MCP host, which connects to whatever external servers the player has.
     *
     * <p>Not conditional on the database, and nothing waits on it. MCP servers are an
     * optional extra: a player with none — the usual case — loses nothing, and a server
     * that will not start is reported in the registry rather than failing startup. It runs
     * before the database opens because the two are unrelated, and making the agent's tools
     * wait on storage would delay them for no reason.
     */
    private static void startMcpHost() {
        try {
            McpHostFactory.start(paths);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not start the MCP host; no external servers will be available",
                    failure);
        }
    }

    /**
     * Writes {@code AGENTS.md} if the player does not have one yet.
     *
     * <p>Separate from opening the database, and not conditional on it: the persona is a
     * file the player edits, and a storage failure is no reason to leave them without one.
     * Nothing waits on it — a slow disk delays the file, not the game — and a failure is
     * logged rather than surfaced, because the mod works perfectly well without the file
     * and the assembler already substitutes a default persona.
     */
    private static void ensurePersonaFile() {
        AgentFactory.personaStore(paths).ensureCreated()
                .whenComplete((created, failure) -> {
                    if (failure != null) {
                        LOGGER.warn("Could not create {}; the agent will use its default persona",
                                paths.instructions(), failure);
                    } else if (created) {
                        LOGGER.info("Created {} — edit it to change how the agent answers",
                                paths.instructions());
                    }
                });
    }

    /**
     * Opens the local store, or reopens it after a failure.
     *
     * <p>Safe to call repeatedly: a second call while one is in flight does nothing.
     *
     * @return whether an attempt was started
     */
    public static CompletableFuture<Boolean> reopenDatabase() {
        if (paths == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!OPENING.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }

        STATE.set(StorageState.OPENING);
        FAILURE.set(null);

        try {
            paths.createDataDirectory();
        } catch (IOException e) {
            fail("Could not create " + paths.dataDirectory(), e);
            return CompletableFuture.completedFuture(false);
        }

        return SqliteDatabase.open(paths.database(), TokenCipher.fromEnvironment(), Models.store())
                .handle((database, failure) -> {
            OPENING.set(false);
            if (failure != null) {
                fail("Could not open " + paths.database(), failure);
                return false;
            }
            DATABASE.set(database);
            STATE.set(StorageState.READY);
            LOGGER.info("{} is ready, storing data in {}", MCAgents.MOD_NAME, paths.dataDirectory());
            moveModelSettings(database);
            return true;
        });
    }

    /**
     * Moves the models chat stored before core held the global model into core's store, once.
     * Nothing waits on it: until it lands the global model is the defaults, as it was before.
     */
    private static void moveModelSettings(LocalDatabase database) {
        LegacyModelSettings.migrate(database, Models.store()).whenComplete((moved, failure) -> {
            if (failure != null) {
                LOGGER.warn("Could not move the stored models into the global model", failure);
            }
        });
    }

    private static void fail(String what, Throwable cause) {
        OPENING.set(false);
        STATE.set(missingSqlite(cause) ? StorageState.NO_SQLITE : StorageState.FAILED);
        FAILURE.set(what + " — " + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        LOGGER.error("{}; chat history, API keys, and downloaded packs are unavailable", what, cause);
    }

    /** Whether the failure is the SQLite driver missing, which only the player can fix. */
    private static boolean missingSqlite(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if ((cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException)
                    && String.valueOf(cause.getMessage()).replace('/', '.').contains("org.sqlite.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes the model client and the database when the game exits, so the write-ahead log
     * is checkpointed rather than left for the next start to recover.
     */
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // The model client first: it may still be waiting on a backoff, and there is
            // no point letting that outlive the store it would write the answer to.
            AgentFactory.close();
            // Then the MCP servers: each is a child process, and one left running would
            // outlive the game that started it.
            McpHostFactory.close();
            LocalDatabase database = DATABASE.getAndSet(null);
            if (database != null) {
                database.close();
            }
        }, "MCAgents-shutdown"));
    }

    /**
     * The mod's on-disk layout, resolved at startup.
     *
     * @throws IllegalStateException if called before {@link #start(Path)} has run
     */
    public static ClientPaths paths() {
        if (paths == null) {
            throw new IllegalStateException("MCAgentsClient has not been initialised yet");
        }
        return paths;
    }

    /**
     * The local store, or empty while it is still opening or if opening it failed.
     *
     * <p>Callers handle the empty case rather than assuming it, and use
     * {@link #storageState()} to tell the player which of the two it is.
     */
    public static Optional<LocalDatabase> database() {
        return Optional.ofNullable(DATABASE.get());
    }

    /** Whether the store is opening, ready, or failed. */
    public static StorageState storageState() {
        return STATE.get();
    }

    /** A one-line description of the last storage failure, if there was one. */
    public static Optional<String> storageFailure() {
        return Optional.ofNullable(FAILURE.get());
    }

    /**
     * The MCP host: the tools, resources and prompts external servers currently offer.
     *
     * <p>Never empty as an object and frequently empty as a registry. Callers read
     * {@code registry()} from it and act on what is there, rather than checking whether MCP
     * is set up at all.
     */
    public static io.github.mcagentsmods.chat.api.mcp.McpHost mcpHost() {
        return McpHostFactory.host();
    }

    /** Clones an external MCP repository into {@code MCAgents/chat/mcps/{owner}/{repository}/}. */
    public static io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner mcpCloner() {
        return McpHostFactory.cloner(paths());
    }
}
