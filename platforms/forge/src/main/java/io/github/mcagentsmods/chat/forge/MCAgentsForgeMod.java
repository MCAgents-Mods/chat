package io.github.mcagentsmods.chat.forge;

import io.github.mcagentsmods.chat.api.MCAgents;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge's entry point. Everything the mod does lives in {@code platforms/shared}.
 *
 * <p>Forge constructs a mod on both sides, so the mod starts only when Forge reports a client,
 * and the client code sits in {@link MCAgentsForgeClient} so a dedicated server with the jar in
 * its mods folder never loads a client class.
 */
@Mod(MCAgents.MOD_ID)
public final class MCAgentsForgeMod {

    public MCAgentsForgeMod() {
        if (FMLEnvironment.dist.isClient()) {
            MCAgentsForgeClient.start();
        }
    }
}
