package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.shared.client.gui.AgentChatScreen;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

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
 * of storage inside itself, where the player is already looking. It takes the place of the
 * screen that is up, which is the agents screen whose Chat button asked for it.
 */
public final class ChatScreenLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatScreenLauncher.class);

    private ChatScreenLauncher() {
        throw new AssertionError("No instances.");
    }

    /**
     * Opens the chat screen in place of the current one.
     *
     * @throws RuntimeException if the screen could not be built; the agents screen shows the
     *                          player that its button failed, so the failure never looks like
     *                          a button that does nothing
     */
    public static void open(Minecraft client) {
        Objects.requireNonNull(client, "client");
        try {
            client.setScreenAndShow(new AgentChatScreen());
        } catch (RuntimeException e) {
            LOGGER.error("Could not open the chat screen", e);
            throw e;
        }
    }
}
