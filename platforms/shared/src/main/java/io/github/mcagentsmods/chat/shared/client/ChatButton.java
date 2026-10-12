package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.agents.api.button.AgentButton;
import io.github.mcagentsmods.agents.common.Agents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Chat button on the agents mod's screen, which is how the player reaches chat.
 *
 * <p>The agents mod owns the key: G opens its screen, and every MCAgents mod adds a button
 * there rather than taking a key of its own. Each loader adds this one first thing in its
 * client start-up, before anything that can fail, so chat can always be reached.
 */
public final class ChatButton {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatButton.class);

    /** The button's id on the agents screen. */
    public static final String ID = "mcagents.chat";

    /** The translation key of its label. */
    static final String LABEL = "gui.mcagents.agents.chat";

    private ChatButton() {
        throw new AssertionError("No instances.");
    }

    /** Adds the button to the agents screen. Called once, by each loader's entry point. */
    public static void register() {
        Agents.buttons().register(new AgentButton(ID, LABEL, 0,
                () -> ChatScreenLauncher.open(Minecraft.getInstance())));
        // At INFO so it survives into logs/latest.log: "is chat on the agents screen at all?"
        // is the first question when a player cannot find it.
        LOGGER.info("Added the Chat button to the agents screen");
    }
}
