package io.github.mcagentsmods.chat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mcagentsmods.chat.api.MCAgents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The key that opens the chat screen.
 *
 * <p>Registered as early as the client initialiser runs, and before anything that can fail:
 * a key binding is how a player reaches the mod, and losing it because a directory could not
 * be created leaves them with no way in and no way to find out why.
 *
 * <p>Fabric refuses to register a key mapping once {@code Minecraft.options} exists, and
 * throws when that happens — which crashes startup. So a game that reaches the main menu
 * has already proven its key mappings registered, and a key that appears to do nothing is
 * never a registration problem. It is something downstream, and
 * {@link ChatScreenLauncher} makes sure that something is always visible.
 */
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

        // At INFO so it survives into logs/latest.log. "Did the key register at all?" is
        // the first question when a player reports that nothing happens, and this answers
        // it without a debug build.
        LOGGER.info("Registered the open-chat key binding, default {}",
                InputConstants.KEY_G);
    }

    private static void onEndTick(Minecraft client) {
        if (openChat == null) {
            return;
        }
        // consumeClick drains the queued presses; calling it once per tick is what keeps a
        // held key from opening the screen on every frame.
        boolean pressed = false;
        while (openChat.consumeClick()) {
            pressed = true;
        }
        if (!pressed) {
            return;
        }
        // Every remaining outcome is visible to the player: the screen opens, or chat says
        // why it did not. A keypress must never do nothing at all.
        ChatScreenLauncher.open(client);
    }

    /** What the key is currently bound to, for {@code /mcagents status}. */
    static Component boundKeyDescription() {
        if (openChat == null) {
            return Component.literal("not registered — the mod failed to initialise")
                    .withStyle(ChatFormatting.RED);
        }
        InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(openChat);
        if (bound == null || bound.equals(InputConstants.UNKNOWN)) {
            return Component.literal("unbound — set it in Options, Controls, MCAgents")
                    .withStyle(ChatFormatting.YELLOW);
        }
        return Component.empty()
                .append(bound.getDisplayName().copy().withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (rebind in Options, Controls, MCAgents)")
                        .withStyle(ChatFormatting.GRAY));
    }
}
