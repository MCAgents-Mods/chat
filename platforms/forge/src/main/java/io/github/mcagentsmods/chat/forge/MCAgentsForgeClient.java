package io.github.mcagentsmods.chat.forge;

import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.chat.shared.client.ChatButtons;
import io.github.mcagentsmods.chat.shared.client.GameEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge's client start-up: chat's menu buttons, the connection events, and the game directory,
 * and nothing else. Only {@link MCAgentsForgeMod} calls it, and only on the client.
 */
final class MCAgentsForgeClient {

    private MCAgentsForgeClient() {
        throw new AssertionError("No instances.");
    }

    static void start() {
        // First, and before anything that can fail: the buttons on the agents menu, which G
        // shows, are how the player reaches chat.
        ChatButtons.register();

        // Both fire on the client thread, which is where GameEnvironment reads the server.
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(
                event -> GameEnvironment.onJoin(Minecraft.getInstance()));
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(
                event -> GameEnvironment.onLeave());

        ChatRuntime.start(FMLPaths.GAMEDIR.get());
    }
}
