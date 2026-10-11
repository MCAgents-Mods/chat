package io.github.mcagentsmods.chat.neoforge;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.chat.shared.client.GameEnvironment;
import io.github.mcagentsmods.chat.shared.client.MCAgentsKeyMappings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge's client entry point: the key, the events, and the game directory, and nothing
 * else. Everything the mod does lives in {@code platforms/shared}.
 *
 * <p>Declared for the client distribution only, so a dedicated server that has the jar in its
 * mods folder never constructs it.
 */
@Mod(value = MCAgents.MOD_ID, dist = Dist.CLIENT)
public final class MCAgentsNeoForgeClient {

    public MCAgentsNeoForgeClient(IEventBus modBus) {
        // NeoForge offers key registration only on this start-up event, before Options
        // exists, so a game that reaches the main menu has proven the key registered.
        modBus.addListener(RegisterKeyMappingsEvent.class, MCAgentsNeoForgeClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> MCAgentsKeyMappings.onEndTick(Minecraft.getInstance()));

        // Both fire on the client thread, which is where GameEnvironment reads the server.
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
                event -> GameEnvironment.onJoin(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                event -> GameEnvironment.onLeave());

        ChatRuntime.start(FMLPaths.GAMEDIR.get());
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        // The event's own category registration, not KeyMapping.Category.register, which
        // NeoForge deprecates for mods.
        KeyMapping.Category category = new KeyMapping.Category(MCAgentsKeyMappings.CATEGORY_ID);
        event.registerCategory(category);
        event.register(MCAgentsKeyMappings.create(category));
    }
}
