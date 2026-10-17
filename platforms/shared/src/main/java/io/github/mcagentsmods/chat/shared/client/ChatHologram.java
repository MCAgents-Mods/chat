package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ui.ChatConversation;
import io.github.mcagentsmods.chat.common.ui.ChatSessions;
import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.chat.shared.client.gui.RenameScreen;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.hologram.HologramButton;
import io.github.mcagentsmods.core.api.hologram.PromptInput;
import io.github.mcagentsmods.core.api.hologram.SelectList;
import io.github.mcagentsmods.core.api.hologram.Transcript;
import io.github.mcagentsmods.core.api.hologram.TranscriptEntry;
import io.github.mcagentsmods.core.client.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The chat hologram: three panels standing in front of the player, Sessions, Conversation and
 * User Prompt.
 *
 * <p>Core draws it and takes the clicks and the typing; this class builds it from
 * {@link ChatSessions}, which holds the conversations, and the {@link ChatConversation} chosen
 * in them, which asks the agent. There is one of each for the whole game, so closing the
 * hologram and opening it again keeps the conversation that was open, and an answer still on
 * its way lands in it.
 *
 * <p>The hologram opens whether or not storage is ready, and says which in the conversation
 * panel. Opening it is never the step that fails: a player who presses CHAT and sees nothing
 * cannot tell a mod that is broken from one that is waiting. Call every method on the game's
 * thread.
 */
public final class ChatHologram {

    /** The chat hologram's id, as core's {@code Holograms.isOpen} and {@code close} take it. */
    public static final String ID = "mcagents.chat";

    private static final String SESSIONS = "hologram.mcagents.sessions";
    private static final String CONVERSATION = "hologram.mcagents.conversation";
    private static final String PROMPT = "hologram.mcagents.prompt";
    private static final String NEW = "hologram.mcagents.new";
    private static final String RENAME = "hologram.mcagents.rename";
    private static final String DELETE = "hologram.mcagents.delete";

    private static ChatSessions sessions;
    private static ChatConversation conversation;

    /** The session titles last handed to the list, and the list version they came from. */
    private static List<String> rows = List.of();
    private static long rowsVersion = -1;

    private ChatHologram() {
        throw new AssertionError("No instances.");
    }

    /** Opens the chat hologram in front of the player, in place of the one open. */
    public static void open() {
        ChatSessions chat = sessions();
        if (!bindStorage(chat)) {
            chat.refresh();
            conversation().refresh();
        }
        Holograms.open(build(chat));
    }

    /** Closes the chat hologram, if it is the one open. */
    public static void close() {
        Holograms.close(ID);
    }

    /** Whether the chat hologram is the one open. */
    public static boolean isOpen() {
        return Holograms.isOpen(ID);
    }

    private static ChatSessions sessions() {
        if (sessions == null) {
            sessions = new ChatSessions(Minecraft.getInstance());
        }
        return sessions;
    }

    /** The conversation the hologram shows; a new one until the player picks another. */
    private static ChatConversation conversation() {
        if (conversation == null) {
            conversation = sessions().open(null);
        }
        return conversation;
    }

    private static Hologram build(ChatSessions chat) {
        return Hologram.of(ID,
                new SelectList(SESSIONS, () -> rows(chat), () -> selectedIndex(chat),
                        index -> select(chat, index),
                        List.of(new HologramButton(NEW, () -> newSession(chat)),
                                new HologramButton(RENAME, () -> rename(chat)),
                                new HologramButton(DELETE, () -> delete(chat)))),
                new Transcript(CONVERSATION, () -> entries(chat)),
                new PromptInput(PROMPT, PromptInput.SEND, ChatConversation.PROMPT_LIMIT,
                        () -> conversation().canSend(), ChatHologram::send));
    }

    /** Where the conversation shown is in the list, or -1 while it is new. */
    private static int selectedIndex(ChatSessions chat) {
        String id = conversation().sessionId().orElse(null);
        List<ChatSession> listed = chat.sessions();
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static void select(ChatSessions chat, int index) {
        List<ChatSession> listed = chat.sessions();
        if (index >= 0 && index < listed.size() && index != selectedIndex(chat)) {
            conversation = chat.open(listed.get(index).id());
        }
    }

    /**
     * Binds the store once it is open. Read every frame through the transcript, so a store that
     * opens, or reopens after NEW, is picked up without the player doing anything.
     *
     * @return whether this call bound it
     */
    private static boolean bindStorage(ChatSessions chat) {
        if (chat.hasStorage()) {
            return false;
        }
        LocalDatabase database = ChatRuntime.database().orElse(null);
        if (database == null) {
            return false;
        }
        chat.bind(database.chat(), AgentFactory.create(database, ChatRuntime.paths()));
        conversation().refresh();
        return true;
    }

    private static List<String> rows(ChatSessions chat) {
        if (rowsVersion != chat.version()) {
            rows = chat.sessions().stream().map(ChatSession::title).toList();
            rowsVersion = chat.version();
        }
        return rows;
    }

    private static List<TranscriptEntry> entries(ChatSessions chat) {
        bindStorage(chat);
        if (!chat.hasStorage()) {
            return List.of(new TranscriptEntry(I18n.get("hologram.mcagents.storage"), storageState()));
        }
        return conversation().transcript(new ChatConversation.TranscriptLabels(
                playerName(),
                I18n.get("gui.mcagents.role.assistant"),
                I18n.get("gui.mcagents.role.system"),
                I18n.get("hologram.mcagents.thinking"),
                I18n.get("hologram.mcagents.unanswered")));
    }

    /** What the conversation panel says while there is no store to read. */
    private static String storageState() {
        return switch (ChatRuntime.storageState()) {
            case OPENING -> I18n.get("hologram.mcagents.storage.opening");
            case READY -> I18n.get("hologram.mcagents.storage.opening");
            case FAILED -> I18n.get("hologram.mcagents.storage.failed",
                    ChatRuntime.storageFailure().orElse("?"));
        };
    }

    private static String playerName() {
        String name = Minecraft.getInstance().getUser().getName();
        return name == null || name.isBlank() ? I18n.get("hologram.mcagents.player") : name;
    }

    private static void send(String text) {
        // Thrown so core keeps the text in the box; SEND is greyed whenever this would happen.
        if (!conversation().send(text)) {
            throw new IllegalStateException("The question was not taken: nothing to send, "
                    + "no storage, or another question is waiting");
        }
    }

    /** NEW starts a conversation, or, while the store could not be opened, tries again. */
    private static void newSession(ChatSessions chat) {
        if (chat.hasStorage()) {
            // Stored with its first question, as every new conversation is.
            conversation = chat.open(null);
        } else if (ChatRuntime.storageState() == ChatRuntime.StorageState.FAILED) {
            ChatRuntime.reopenDatabase();
        }
    }

    private static void rename(ChatSessions chat) {
        conversation().session().ifPresent(session -> Minecraft.getInstance().setScreenAndShow(
                RenameScreen.forSession(session, null, title -> chat.rename(session.id(), title))));
    }

    /**
     * Asks before deleting: a conversation takes every message in it with it, and it cannot
     * be undone, so it is the one action here that does not happen on a single click.
     */
    private static void delete(ChatSessions chat) {
        conversation().session().ifPresent(session -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreenAndShow(new ConfirmScreen(
                    confirmed -> {
                        if (confirmed) {
                            chat.delete(session.id());
                            conversation = chat.open(null);
                        }
                        minecraft.setScreenAndShow(null);
                    },
                    Component.translatable("gui.mcagents.session.delete.title"),
                    Component.translatable("gui.mcagents.session.delete.message", session.title()),
                    Component.translatable("gui.mcagents.session.delete.confirm"),
                    Component.translatable("gui.cancel")));
        });
    }
}
