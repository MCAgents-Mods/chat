package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.common.ui.ChatSessions;
import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.hologram.HologramButton;
import io.github.mcagentsmods.core.api.hologram.PickList;
import io.github.mcagentsmods.core.api.hologram.PickRow;
import io.github.mcagentsmods.core.client.hologram.Holograms;

import java.util.ArrayList;
import java.util.List;

/**
 * The Chat Sessions hologram CHAT opens: every conversation, the most recently used first,
 * under a nav bar with NEW CHAT on its right.
 *
 * <p>Picking a conversation opens its chat hologram ({@link ChatHologram}) beside this one,
 * which stays open. While a conversation's hologram stands, its row is faded and cannot be
 * picked; it keeps its place in the list. NEW CHAT opens a hologram on a new conversation,
 * whose row appears, faded, once its first question is stored.
 *
 * <p>While storage is not open, the list holds one faded row saying why, and NEW CHAT tries
 * to open it again when it failed. Call every method on the game's thread.
 */
public final class ChatSessionsHologram {

    /** The picker's hologram id, as core's {@code Holograms.isOpen} and {@code close} take it. */
    public static final String ID = "mcagents.chat.sessions";

    private static final String TITLE = "hologram.mcagents.chat_sessions";
    private static final String NEW_CHAT = "hologram.mcagents.new_chat";

    /** The conversations last listed, as picked by index; read on the frame they were drawn. */
    private static List<ChatSession> listed = List.of();

    private ChatSessionsHologram() {
        throw new AssertionError("No instances.");
    }

    /** Opens the picker in front of the player, or moves it there if it is open. */
    public static void open() {
        ChatSessions chat = ChatHologram.sessions();
        chat.refresh();
        Holograms.open(Hologram.of(ID, new PickList(TITLE, ChatSessionsHologram::rows,
                ChatSessionsHologram::pick, List.of(new HologramButton(NEW_CHAT,
                        ChatSessionsHologram::newChat)))));
    }

    /** Closes the picker, if it is open. */
    public static void close() {
        Holograms.close(ID);
    }

    /** Whether the picker is open. */
    public static boolean isOpen() {
        return Holograms.isOpen(ID);
    }

    /**
     * Read every frame: a short list, built from memory, with each row faded while its
     * conversation's hologram stands.
     */
    private static List<PickRow> rows() {
        ChatSessions chat = ChatHologram.sessions();
        if (!chat.hasStorage()) {
            listed = List.of();
            return List.of(new PickRow(ChatHologram.storageState(), false));
        }
        listed = chat.sessions();
        List<PickRow> rows = new ArrayList<>(listed.size());
        for (ChatSession session : listed) {
            rows.add(new PickRow(session.title(), !ChatHologram.isOpen(session.id())));
        }
        return rows;
    }

    private static void pick(int index) {
        if (index >= 0 && index < listed.size()) {
            ChatHologram.open(listed.get(index));
        }
    }

    /** NEW CHAT opens a new conversation, or, while the store could not be opened, tries again. */
    private static void newChat() {
        if (ChatHologram.sessions().hasStorage()) {
            ChatHologram.openNew();
        } else if (ChatRuntime.storageState() == ChatRuntime.StorageState.FAILED) {
            ChatRuntime.reopenDatabase();
        }
    }
}
