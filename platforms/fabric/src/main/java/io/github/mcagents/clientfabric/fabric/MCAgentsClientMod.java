package io.github.mcagents.clientfabric.fabric;

import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.common.ClientPaths;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Client entry point.
 *
 * <p>This is the only place that knows where Minecraft keeps its game directory. It
 * resolves the mod's layout from it once, at startup, and everything below this module
 * works from the resulting {@link ClientPaths}.
 *
 * <p>Startup must never depend on the network. Nothing here contacts the central service
 * or OpenRouter — those are the player's actions, taken later, and a failure in either one
 * leaves the mod running.
 */
public final class MCAgentsClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MCAgents.MOD_NAME);

    private static ClientPaths paths;

    @Override
    public void onInitializeClient() {
        paths = ClientPaths.under(FabricLoader.getInstance().getGameDir());

        try {
            paths.createDataDirectory();
            LOGGER.info("{} initialised, data directory {}", MCAgents.MOD_NAME, paths.dataDirectory());
        } catch (IOException e) {
            // The player keeps a working game; only this mod's storage is unavailable.
            LOGGER.error("Could not create the data directory at {}", paths.dataDirectory(), e);
        }
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
}
