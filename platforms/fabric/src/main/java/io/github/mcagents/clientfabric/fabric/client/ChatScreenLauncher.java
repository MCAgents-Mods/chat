package io.github.mcagents.clientfabric.fabric.client;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.fabric.MCAgentsClientMod;
import io.github.mcagents.clientfabric.fabric.client.gui.AgentChatScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The one place the chat screen is opened from, whether the request came from the key
 * binding or the {@code /mcagents} command.
 *
 * <h2>Why this exists</h2>
 *
 * <p>It used to be a private method that logged a warning and returned when the database
 * was not ready. In a real game that is indistinguishable from a key that was never
 * registered, from a key bound to something else, and from a mod that did not load at all —
 * the player presses a key and nothing happens, and nothing on screen says why.
 *
 * <p>So: <strong>a request to open the screen always produces something the player can
 * see.</strong> Either the screen opens, or a message in chat says what is wrong. Silence is
 * treated as a bug, not as a state.
 */
public final class ChatScreenLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatScreenLauncher.class);

    private ChatScreenLauncher() {
        throw new AssertionError("No instances.");
    }

    /**
     * Opens the chat screen, or tells the player why it cannot.
     *
     * @return whether the screen was opened
     */
    public static boolean open(Minecraft client) {
        if (client == null) {
            return false;
        }
        if (client.gui.screen() != null) {
            // Another screen is already up. Not an error, and not worth a message.
            return false;
        }

        Optional<LocalDatabase> database = MCAgentsClientMod.database();
        if (database.isEmpty()) {
            reportStorageUnavailable(client);
            return false;
        }

        try {
            client.setScreenAndShow(new AgentChatScreen(
                    database.get(),
                    AgentFactory.create(database.get(), MCAgentsClientMod.paths())));
            return true;
        } catch (RuntimeException e) {
            // A failure while building the screen would otherwise vanish into the tick
            // handler and look exactly like a dead key.
            LOGGER.error("Could not open the chat screen", e);
            tell(client, Component.literal(
                            "MCAgents could not open the chat screen. See the game log for details.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
    }

    private static void reportStorageUnavailable(Minecraft client) {
        MCAgentsClientMod.StorageState state = MCAgentsClientMod.storageState();
        LOGGER.warn("The chat screen was requested but storage is {}", state);

        Component message = switch (state) {
            case OPENING -> Component.literal(
                            "MCAgents is still opening its database. Try again in a moment.")
                    .withStyle(ChatFormatting.YELLOW);
            case FAILED -> Component.literal(
                            "MCAgents could not open its database, so chat is unavailable. "
                                    + "Run /mcagents status for details.")
                    .withStyle(ChatFormatting.RED);
            case READY -> Component.literal(
                            "MCAgents storage reported ready but was unavailable. "
                                    + "Run /mcagents status.")
                    .withStyle(ChatFormatting.RED);
        };
        tell(client, message);
    }

    /**
     * Puts a line in the player's chat.
     *
     * <p>Guarded because the key can be pressed on the title screen, where there is no
     * player and no chat to write to.
     */
    static void tell(Minecraft client, Component message) {
        if (client.player != null) {
            // 26.x renamed displayClientMessage; this is the surviving equivalent.
            client.player.sendSystemMessage(message);
        } else {
            LOGGER.info("{}", message.getString());
        }
    }
}
