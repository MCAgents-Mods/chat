package io.github.mcagentsmods.chat.shared.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mcagentsmods.chat.api.MCAgents;
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
 * <p>Built here and registered by each loader, as early as its client start-up runs and
 * before anything that can fail: a key binding is how a player reaches the mod, and losing it
 * because a directory could not be created leaves them with no way in and no way to find out
 * why. The loader also calls {@link #onEndTick} at the end of every client tick.
 *
 * <p>Both loaders refuse a key mapping once {@code Minecraft.options} exists — Fabric throws,
 * which crashes startup, and NeoForge only offers registration on its start-up event. So a
 * game that reaches the main menu has already proven its key mappings registered, and a key
 * that appears to do nothing is never a registration problem. It is something downstream,
 * and {@link ChatScreenLauncher} makes sure that something is always visible.
 */
public final class MCAgentsKeyMappings {

    private static final Logger LOGGER = LoggerFactory.getLogger(MCAgentsKeyMappings.class);

    /** The Controls category the key is listed under; each loader registers it its own way. */
    public static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath(MCAgents.MOD_ID, "main");

    private static KeyMapping openChat;

    private MCAgentsKeyMappings() {
        throw new AssertionError("No instances.");
    }

    /**
     * Builds the open-chat key in {@code category}, for the loader to register.
     *
     * @return the key, which the loader hands to its own registration
     */
    public static KeyMapping create(KeyMapping.Category category) {
        openChat = new KeyMapping(
                "key.mcagents.open_chat", InputConstants.Type.KEYSYM, InputConstants.KEY_G, category);

        // At INFO so it survives into logs/latest.log. "Did the key register at all?" is
        // the first question when a player reports that nothing happens, and this answers
        // it without a debug build.
        LOGGER.info("Registered the open-chat key binding, default {}",
                InputConstants.KEY_G);
        return openChat;
    }

    /** Acts on the key; each loader calls it at the end of every client tick. */
    public static void onEndTick(Minecraft client) {
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
        if (openChat.isUnbound()) {
            return Component.literal("unbound — set it in Options, Controls, MCAgents")
                    .withStyle(ChatFormatting.YELLOW);
        }
        return Component.empty()
                .append(openChat.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (rebind in Options, Controls, MCAgents)")
                        .withStyle(ChatFormatting.GRAY));
    }
}
