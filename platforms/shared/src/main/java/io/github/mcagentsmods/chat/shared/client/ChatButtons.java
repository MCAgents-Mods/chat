package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.chat.shared.client.gui.ApiKeyScreen;
import io.github.mcagentsmods.chat.shared.client.gui.ModelSettingsScreen;
import io.github.mcagentsmods.chat.shared.client.gui.PersonaScreen;
import io.github.mcagentsmods.chat.shared.client.gui.WalletScreen;
import io.github.mcagentsmods.core.api.button.AgentButton;
import io.github.mcagentsmods.core.common.agent.Agents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Chat's buttons on the agents mod's menu, which is how the player reaches chat.
 *
 * <p>The agents mod owns the key: G shows its menu, a hologram, and every MCAgents mod adds
 * buttons there rather than taking a key of its own. Chat adds five: CHAT opens the Chat
 * Sessions hologram in place of the menu, and KEYS, MODEL, PERSONA and WALLETS open their
 * screens over it. Each loader adds them first thing in its client start-up, before anything that can fail,
 * so chat can always be reached.
 *
 * <p>Every screen opened from here goes back to the game when it closes. A button whose screen
 * needs storage says why on the action bar while there is none, rather than doing nothing.
 */
public final class ChatButtons {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatButtons.class);

    /** The CHAT button's id on the agents menu. */
    public static final String CHAT = "mcagents.chat";
    /** The KEYS button's id. */
    public static final String KEYS = "mcagents.keys";
    /** The MODEL button's id. */
    public static final String MODEL = "mcagents.model";
    /** The PERSONA button's id. */
    public static final String PERSONA = "mcagents.persona";
    /** The WALLETS button's id. */
    public static final String WALLETS = "mcagents.wallets";

    private ChatButtons() {
        throw new AssertionError("No instances.");
    }

    /** Adds the buttons to the agents menu. Called once, by each loader's entry point. */
    public static void register() {
        Agents.buttons().register(new AgentButton(CHAT, "gui.mcagents.agents.chat", 0,
                ChatSessionsHologram::open));
        Agents.buttons().register(new AgentButton(KEYS, "gui.mcagents.agents.keys", 1,
                () -> openWithStorage(database -> new ApiKeyScreen(database, null))));
        Agents.buttons().register(new AgentButton(MODEL, "gui.mcagents.agents.model", 2,
                () -> openWithStorage(database -> new ModelSettingsScreen(database, null))));
        // The one that needs no storage: AGENTS.md is a file, and a player whose database will
        // not open should still be able to write their instructions.
        Agents.buttons().register(new AgentButton(PERSONA, "gui.mcagents.agents.persona", 3,
                ChatButtons::openPersona));
        Agents.buttons().register(new AgentButton(WALLETS, "gui.mcagents.agents.wallets", 4,
                () -> openWithStorage(database -> new WalletScreen(database, null,
                        PlayerAccounts.current(Minecraft.getInstance())))));
        // At INFO so it survives into logs/latest.log: "is chat on the agents menu at all?"
        // is the first question when a player cannot find it.
        LOGGER.info("Added chat's buttons to the agents menu");
    }

    private static void openPersona() {
        ClientPaths paths = ChatRuntime.paths();
        Minecraft.getInstance().setScreenAndShow(new PersonaScreen(
                AgentFactory.personaStore(paths), paths.instructions(), null));
    }

    /** Opens the screen {@code screen} builds, or says on the action bar why it cannot. */
    private static void openWithStorage(Function<LocalDatabase, Screen> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalDatabase database = ChatRuntime.database().orElse(null);
        if (database != null) {
            minecraft.setScreenAndShow(screen.apply(database));
            return;
        }
        Component reason = Component.translatable(
                ChatRuntime.storageState() == ChatRuntime.StorageState.FAILED
                        ? "hologram.mcagents.storage.failed.short"
                        : "hologram.mcagents.storage.opening");
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(reason);
        }
    }
}
