package io.github.mcagentsmods.chat.neoforge;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.chat.shared.client.ChatButtons;
import io.github.mcagentsmods.chat.shared.client.GameEnvironment;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge's client entry point: chat's menu buttons, the events, and the game directory, and
 * nothing else. Everything the mod does lives in {@code platforms/shared}.
 *
 * <p>Declared for the client distribution only, so a dedicated server that has the jar in its
 * mods folder never constructs it.
 */
@Mod(value = MCAgents.MOD_ID, dist = Dist.CLIENT)
public final class MCAgentsNeoForgeClient {

    public MCAgentsNeoForgeClient() {
        // First, and before anything that can fail: the buttons on the agents menu, which G
        // shows, are how the player reaches chat.
        ChatButtons.register();

        // Both fire on the client thread, which is where GameEnvironment reads the server.
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
                event -> GameEnvironment.onJoin(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                event -> GameEnvironment.onLeave());

        ChatRuntime.start(FMLPaths.GAMEDIR.get());
    }
}
