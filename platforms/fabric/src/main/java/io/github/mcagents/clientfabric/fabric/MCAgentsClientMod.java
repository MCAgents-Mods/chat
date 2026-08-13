package io.github.mcagents.clientfabric.fabric;

import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.ClientPaths;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import io.github.mcagents.clientfabric.fabric.client.AgentFactory;
import io.github.mcagents.clientfabric.fabric.client.MCAgentsKeyMappings;
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
        registerShutdownHook();

        paths = ClientPaths.under(FabricLoader.getInstance().getGameDir());
        LOGGER.info("{} initialising, data directory {}", MCAgents.MOD_NAME, paths.dataDirectory());

        reopenDatabase();
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
            return true;
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
            // The model client first: it may still be waiting on a backoff, and there is
            // no point letting that outlive the store it would write the answer to.
            AgentFactory.close();
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
}
