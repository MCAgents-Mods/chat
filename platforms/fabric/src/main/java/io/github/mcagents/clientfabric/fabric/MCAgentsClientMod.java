package io.github.mcagents.clientfabric.fabric;

import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.ClientPaths;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import io.github.mcagents.clientfabric.fabric.client.MCAgentsKeyMappings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
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
 * screen, and a database that cannot be opened at all leaves the player with a working
 * game and a logged explanation.
 */
public final class MCAgentsClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MCAgents.MOD_NAME);

    private static ClientPaths paths;
    private static final AtomicReference<LocalDatabase> DATABASE = new AtomicReference<>();

    @Override
    public void onInitializeClient() {
        paths = ClientPaths.under(FabricLoader.getInstance().getGameDir());

        try {
            paths.createDataDirectory();
        } catch (IOException e) {
            LOGGER.error("Could not create the data directory at {}; storage is unavailable",
                    paths.dataDirectory(), e);
            return;
        }

        openDatabase();
        registerShutdownHook();
        MCAgentsKeyMappings.register();
    }

    private void openDatabase() {
        SqliteDatabase.open(paths.database()).whenComplete((database, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not open the local database at {}; "
                        + "chat history, API keys, and downloaded packs are unavailable "
                        + "for this session", paths.database(), failure);
                return;
            }
            DATABASE.set(database);
            LOGGER.info("{} is ready, storing data in {}", MCAgents.MOD_NAME, paths.dataDirectory());
        });
    }

    /**
     * Closes the database when the game exits, so the write-ahead log is checkpointed
     * rather than left for the next start to recover.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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
     * <p>Callers handle the empty case rather than assuming it: a player whose database
     * could not be opened still has a running game, and the interface says so instead of
     * crashing.
     */
    public static Optional<LocalDatabase> database() {
        return Optional.ofNullable(DATABASE.get());
    }
}
