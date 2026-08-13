package io.github.mcagents.clientfabric.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.fabric.MCAgentsClientMod;
import io.github.mcagents.clientfabric.fabric.client.gui.AgentChatScreen;
import io.github.mcagents.clientfabric.fabric.client.gui.AgentReply;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/** The key that opens the chat screen. */
public final class MCAgentsKeyMappings {

    private static final Logger LOGGER = LoggerFactory.getLogger(MCAgentsKeyMappings.class);

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MCAgents.MOD_ID, "main"));

    private static KeyMapping openChat;

    private MCAgentsKeyMappings() {
        throw new AssertionError("No instances.");
    }

    /** Registers the key and the tick handler that acts on it. */
    public static void register() {
        openChat = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mcagents.open_chat", InputConstants.Type.KEYSYM, InputConstants.KEY_G, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(MCAgentsKeyMappings::onEndTick);
    }

    private static void onEndTick(Minecraft client) {
        // consumeClick drains the queued presses; calling it once per tick is what keeps a
        // held key from opening the screen on every frame.
        boolean pressed = false;
        while (openChat.consumeClick()) {
            pressed = true;
        }
        // Minecraft 26.x keeps the current screen on Gui, not on Minecraft itself.
        if (!pressed || client.gui.screen() != null) {
            return;
        }
        openChatScreen(client);
    }

    private static void openChatScreen(Minecraft client) {
        Optional<LocalDatabase> database = MCAgentsClientMod.database();
        if (database.isEmpty()) {
            // Storage is still opening, or failed to open. Saying nothing and doing nothing
            // would look like a broken key, so this is worth a line in the log.
            LOGGER.warn("The chat screen was requested before the local database was ready");
            return;
        }
        client.setScreenAndShow(new AgentChatScreen(database.get(), AgentReply.notConfigured()));
    }
}
