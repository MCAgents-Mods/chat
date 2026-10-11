package io.github.mcagentsmods.chat.fabric;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.chat.fabric.client.AgentFactory;
import io.github.mcagentsmods.chat.fabric.client.GameEnvironment;
import io.github.mcagentsmods.chat.fabric.client.KnowledgeSyncFactory;
import io.github.mcagentsmods.chat.fabric.client.MCAgentsKeyMappings;
import io.github.mcagentsmods.chat.fabric.client.McpHostFactory;
import io.github.mcagentsmods.chat.fabric.client.WebConsoleHolder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client entry point.
 *
 * <p>This is the only place that knows where Minecraft keeps its game directory. It
 * resolves the mod's layout from it once, at startup, opens the local database, and
 * everything below this module works from the results.
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
public final class MCAgentsClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MCAgents.MOD_NAME);

    /** Why the local store is or is not usable, in terms the interface can report. */
    public enum StorageState {
        /** Still being opened. Worth trying again shortly. */
        OPENING,
        /** Open and usable. */
        READY,
        /** Could not be opened. The reason is in the log. */
        FAILED
    }

    private static ClientPaths paths;
    private static final AtomicReference<LocalDatabase> DATABASE = new AtomicReference<>();
    private static final AtomicReference<StorageState> STATE =
            new AtomicReference<>(StorageState.OPENING);
    private static final AtomicReference<String> FAILURE = new AtomicReference<>();
    private static final AtomicBoolean OPENING = new AtomicBoolean();

    @Override
    public void onInitializeClient() {
        // Registered first, and before anything that can fail. Fabric throws if a key
        // mapping is registered after Options exists, so this must happen here and only
        // here — and because that throw would crash startup outright, a game that reaches
        // the main menu has proven its key mappings registered.
        MCAgentsKeyMappings.register();
        // Registering a listener cannot fail and needs nothing on disk, so it goes beside
        // the key mapping rather than after storage: a player can be in a world before the
        // database has finished opening, and the join they made is the one worth recording.
        GameEnvironment.register();
        registerShutdownHook();

        paths = ClientPaths.under(FabricLoader.getInstance().getGameDir());
        LOGGER.info("{} initialising, data directory {}", MCAgents.MOD_NAME, paths.dataDirectory());

        ensurePersonaFile();
        startMcpHost();
        reopenDatabase();
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

        return SqliteDatabase.open(paths.database()).handle((database, failure) -> {
            OPENING.set(false);
            if (failure != null) {
                fail("Could not open " + paths.database(), failure);
                return false;
            }
            DATABASE.set(database);
            STATE.set(StorageState.READY);
            LOGGER.info("{} is ready, storing data in {}", MCAgents.MOD_NAME, paths.dataDirectory());
            fetchBaseKnowledge(database);
            return true;
        });
    }

    /**
     * Fetches the pack describing the mod, once, in the background.
     *
     * <p>The only thing the mod asks the central service for without being told to, and the
     * only reason it is worth an exception: without it the agent cannot answer "what are
     * you", which is the first question a new player has.
     *
     * <p>Nothing waits on it. The future it returns never fails, so a service that is asleep
     * — the normal state of a host that idles — costs a log line and nothing else. Every
     * screen opens, and every stored pack still answers, whether this succeeds or not.
     */
    private static void fetchBaseKnowledge(LocalDatabase database) {
        KnowledgeSyncFactory.create(database).ensureBaseKnowledge()
                .thenAccept(updated -> {
                    if (updated) {
                        LOGGER.info("Fetched the base knowledge pack");
                    }
                });
    }

    private static void fail(String what, Throwable cause) {
        OPENING.set(false);
        STATE.set(StorageState.FAILED);
        FAILURE.set(what + " — " + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        LOGGER.error("{}; chat history, API keys, and downloaded packs are unavailable", what, cause);
    }

    /**
     * Closes the model client and the database when the game exits, so the write-ahead log
     * is checkpointed rather than left for the next start to recover.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // The console first: it holds a listening socket and worker threads, and a
            // request it is serving would otherwise reach for a store that is closing.
            WebConsoleHolder.stop();
            // Then the model client: it may still be waiting on a backoff, and there is
            // no point letting that outlive the store it would write the answer to.
            AgentFactory.close();
            KnowledgeSyncFactory.close();
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
     * @throws IllegalStateException if called before the client initialiser has run
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

    /** Clones an external MCP repository into {@code MCAgents/mcps/{owner}/{repository}/}. */
    public static io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner mcpCloner() {
        return McpHostFactory.cloner(paths());
    }
}
