package io.github.mcagents.clientfabric.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.fabric.MCAgentsClientMod;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * {@code /mcagents} — a second way in, and a way to find out what is wrong.
 *
 * <h2>Why a command as well as a key</h2>
 *
 * <p>A key binding has several ways to quietly not work: it can be rebound, it can collide
 * with another mod's binding, or it can fail to reach the game's key map at all. When that
 * happens the player has no way to reach the mod and no way to tell whether it even loaded.
 *
 * <p>A command does not share any of those failure modes — it is registered through a
 * different system entirely. So the mod is reachable two independent ways, and
 * {@code /mcagents status} answers the question the player actually has, which is "is this
 * thing working?".
 */
public final class MCAgentsCommands {

    private MCAgentsCommands() {
        throw new AssertionError("No instances.");
    }

    /** Registers {@code /mcagents} and its subcommands. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(ClientCommands.literal(MCAgents.MOD_ID)
                        .executes(command -> openChat(command.getSource()))
                        .then(ClientCommands.literal("chat")
                                .executes(command -> openChat(command.getSource())))
                        .then(ClientCommands.literal("status")
                                .executes(command -> reportStatus(command.getSource())))
                        .then(ClientCommands.literal("reload")
                                .executes(command -> reopenStorage(command.getSource())))));
    }

    private static int openChat(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        // Deferred by one tick: the command runs while the chat screen is still closing,
        // and setting a screen inside that would be undone as it finishes.
        client.execute(() -> ChatScreenLauncher.open(client));
        return 1;
    }

    /**
     * Reports what is working and what is not.
     *
     * <p>This is deliberately the answer to "the key does nothing" — it distinguishes a
     * keybind problem from a storage problem, which the player cannot otherwise tell apart.
     */
    private static int reportStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(MCAgents.MOD_NAME + " status")
                .withStyle(ChatFormatting.AQUA));

        source.sendFeedback(line("Storage", switch (MCAgentsClientMod.storageState()) {
            case READY -> ok("ready");
            case OPENING -> warn("still opening");
            case FAILED -> bad("failed");
        }));
        MCAgentsClientMod.storageFailure().ifPresent(failure ->
                source.sendFeedback(Component.literal("    " + failure).withStyle(ChatFormatting.RED)));

        source.sendFeedback(line("Data directory",
                Component.literal(MCAgentsClientMod.paths().dataDirectory().toString())
                        .withStyle(ChatFormatting.GRAY)));

        source.sendFeedback(line("Open chat key", MCAgentsKeyMappings.boundKeyDescription()));

        if (MCAgentsClientMod.storageState() == MCAgentsClientMod.StorageState.FAILED) {
            source.sendFeedback(Component.literal("  Run /mcagents reload to try opening it again.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    private static int reopenStorage(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Reopening the local database…")
                .withStyle(ChatFormatting.YELLOW));

        Minecraft client = source.getClient();
        MCAgentsClientMod.reopenDatabase().thenAccept(opened -> client.execute(() ->
                ChatScreenLauncher.tell(client, opened
                        ? ok("MCAgents storage is ready.")
                        : bad("MCAgents storage could not be opened. See the game log."))));
        return 1;
    }

    private static Component line(String label, Component value) {
        return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY).append(value);
    }

    private static Component ok(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    private static Component warn(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static Component bad(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /** Whether the key is currently bound to something usable. */
    static boolean isKeyUnbound(InputConstants.Key key) {
        return key == null || key.equals(InputConstants.UNKNOWN);
    }
}
