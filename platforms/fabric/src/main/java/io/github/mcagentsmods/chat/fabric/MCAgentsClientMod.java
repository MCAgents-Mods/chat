package io.github.mcagentsmods.chat.fabric;

import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.chat.shared.client.GameEnvironment;
import io.github.mcagentsmods.chat.shared.client.MCAgentsKeyMappings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

/**
 * Fabric's client entry point: the key, the events, and the game directory, and nothing
 * else. Everything the mod does lives in {@code platforms/shared}.
 */
public final class MCAgentsClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Registered first, and before anything that can fail. Fabric throws if a key
        // mapping is registered after Options exists, so this must happen here and only
        // here — and because that throw would crash startup outright, a game that reaches
        // the main menu has proven its key mappings registered.
        KeyMapping.Category category = KeyMapping.Category.register(MCAgentsKeyMappings.CATEGORY_ID);
        KeyMappingHelper.registerKeyMapping(MCAgentsKeyMappings.create(category));
        ClientTickEvents.END_CLIENT_TICK.register(MCAgentsKeyMappings::onEndTick);

        // Registering a listener cannot fail and needs nothing on disk, so it goes beside
        // the key mapping rather than after storage: a player can be in a world before the
        // database has finished opening, and the join they made is the one worth recording.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                GameEnvironment.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                GameEnvironment.onLeave());

        ChatRuntime.start(FabricLoader.getInstance().getGameDir());
    }
}
