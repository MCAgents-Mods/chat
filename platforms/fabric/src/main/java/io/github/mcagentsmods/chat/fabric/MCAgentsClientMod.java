package io.github.mcagentsmods.chat.fabric;

import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.chat.shared.client.ChatButton;
import io.github.mcagentsmods.chat.shared.client.GameEnvironment;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric's client entry point: the Chat button, the events, and the game directory, and
 * nothing else. Everything the mod does lives in {@code platforms/shared}.
 */
public final class MCAgentsClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // First, and before anything that can fail: the button on the agents screen, which G
        // opens, is how the player reaches chat.
        ChatButton.register();

        // Registering a listener cannot fail and needs nothing on disk, so it goes beside
        // the button rather than after storage: a player can be in a world before the
        // database has finished opening, and the join they made is the one worth recording.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                GameEnvironment.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                GameEnvironment.onLeave());

        ChatRuntime.start(FabricLoader.getInstance().getGameDir());
    }
}
