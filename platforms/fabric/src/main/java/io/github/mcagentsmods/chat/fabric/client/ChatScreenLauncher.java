package io.github.mcagentsmods.chat.fabric.client;

import io.github.mcagentsmods.chat.fabric.client.gui.AgentChatScreen;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens the chat screen. Always.
 *
 * <h2>Why "always" is the whole design</h2>
 *
 * <p>The first version refused to open when the local database was not ready, logged a
 * warning, and returned. In a real game that is a dead key: the player presses it, nothing
 * happens, and nothing on screen distinguishes "storage failed" from "the mod never
 * loaded". That is exactly how this shipped broken, and no amount of logging fixes it,
 * because the player is not reading the log.
 *
 * <p>So the screen no longer depends on storage to open. It opens, and it reports the state
 * of storage inside itself, where the player is already looking. The only reason left not
 * to open is that another screen is already up.
 */
public final class ChatScreenLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatScreenLauncher.class);

    private ChatScreenLauncher() {
        throw new AssertionError("No instances.");
    }

    /**
     * Opens the chat screen.
     *
     * @return whether the screen was opened
     */
    public static boolean open(Minecraft client) {
        if (client == null || client.gui.screen() != null) {
            return false;
        }
        try {
            client.setScreenAndShow(new AgentChatScreen());
            return true;
        } catch (RuntimeException e) {
            // Building a screen should not throw, but if it ever does, the failure must not
            // vanish into the tick handler and look like a dead key again.
            LOGGER.error("Could not open the chat screen", e);
            return false;
        }
    }
}
