package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ui.ChatConversation;
import io.github.mcagentsmods.chat.common.ui.ChatSessions;
import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.hologram.HologramButton;
import io.github.mcagentsmods.core.api.hologram.PromptInput;
import io.github.mcagentsmods.core.api.hologram.Transcript;
import io.github.mcagentsmods.core.api.hologram.TranscriptEntry;
import io.github.mcagentsmods.core.client.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The chat holograms: one per conversation, each two panels standing in front of the player,
 * Conversation and User Prompt. Several can stand at once, and each waits for its own answer.
 *
 * <p>Core draws them and takes the clicks and the typing; this class builds them from
 * {@link ChatSessions}, which holds the conversations, and one {@link ChatConversation} per
 * hologram, which asks the agent. A stored conversation's hologram has the id
 * {@code mcagents.chat.{session id}}, so opening it again moves it rather than doubling it; a
 * new conversation's has {@code mcagents.chat.new-{n}} and keeps it once its first question
 * stores the conversation. {@link ChatSessionsHologram} fades the conversations open here.
 *
 * <p>A hologram opens whether or not storage is ready, and says which in the conversation
 * panel. Opening it is never the step that fails: a player who presses CHAT and sees nothing
 * cannot tell a mod that is broken from one that is waiting. Call every method on the game's
 * thread.
 */
public final class ChatHologram {

    /** What every chat hologram's id starts with. */
    public static final String ID_PREFIX = "mcagents.chat.";

    private static final String CONVERSATION = "hologram.mcagents.conversation";
    private static final String NEW_CHAT = "hologram.mcagents.new_chat";
    private static final String PROMPT = "hologram.mcagents.prompt";
    private static final String RENAME = "hologram.mcagents.rename";
    private static final String DELETE = "hologram.mcagents.delete";
    private static final String RENAME_TITLE = "gui.mcagents.session.rename";
    private static final String RENAME_SAVE = "gui.mcagents.session.rename.save";
    private static final String DELETE_TITLE = "gui.mcagents.session.delete.title";
    private static final String DELETE_MESSAGE = "gui.mcagents.session.delete.message";
    private static final String DELETE_CONFIRM = "gui.mcagents.session.delete.confirm";

    private static ChatSessions sessions;

    /** The conversation behind each chat hologram opened, by hologram id. */
    private static final Map<String, ChatConversation> open = new HashMap<>();

    private static int newCount;

    private ChatHologram() {
        throw new AssertionError("No instances.");
    }

    /**
     * The conversations every chat hologram and the session picker share, bound to storage
     * once it is open.
     */
    static ChatSessions sessions() {
        if (sessions == null) {
            sessions = new ChatSessions(Minecraft.getInstance());
        }
        bindStorage(sessions);
        return sessions;
    }

    /** Opens the hologram of a stored conversation, or moves it in front of the player. */
    public static void open(ChatSession session) {
        Objects.requireNonNull(session, "session");
        forgetClosed();
        String id = ID_PREFIX + session.id();
        ChatConversation conversation = open.get(id);
        if (conversation == null) {
            conversation = sessions().open(session.id());
            open.put(id, conversation);
        } else {
            conversation.refresh();
        }
        Holograms.open(build(id, conversation));
    }

    /** Opens a hologram on a new, empty conversation, stored with its first question. */
    public static void openNew() {
        forgetClosed();
        String id = ID_PREFIX + "new-" + ++newCount;
        ChatConversation conversation = sessions().open(null);
        open.put(id, conversation);
        Holograms.open(build(id, conversation));
    }

    /** Whether a chat hologram for the conversation with this id is open. */
    public static boolean isOpen(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        for (Map.Entry<String, ChatConversation> entry : open.entrySet()) {
            if (sessionId.equals(entry.getValue().sessionId().orElse(null))
                    && Holograms.isOpen(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    /** Closes every chat hologram. */
    public static void closeAll() {
        for (String id : List.copyOf(open.keySet())) {
            Holograms.close(id);
        }
        open.clear();
    }

    /** Drops the conversations whose hologram has closed; an answer on its way is still stored. */
    private static void forgetClosed() {
        open.keySet().removeIf(id -> !Holograms.isOpen(id));
    }

    private static Hologram build(String id, ChatConversation conversation) {
        return Hologram.of(id,
                new Transcript(CONVERSATION, () -> entries(conversation), () -> heading(conversation),
                        List.of(new HologramButton(RENAME, () -> rename(conversation)),
                                new HologramButton(DELETE, () -> delete(id, conversation)))),
                new PromptInput(PROMPT, PromptInput.SEND, ChatConversation.PROMPT_LIMIT,
                        conversation::canSend, text -> send(conversation, text)));
    }

    /**
     * Binds the store once it is open. Read every frame through the transcripts and the
     * picker, so a store that opens, or reopens after NEW CHAT, is picked up without the player
     * doing anything.
     */
    private static void bindStorage(ChatSessions chat) {
        if (chat.hasStorage()) {
            return;
        }
        LocalDatabase database = ChatRuntime.database().orElse(null);
        if (database == null) {
            return;
        }
        chat.bind(database.chat(), AgentFactory.create(database, ChatRuntime.paths()));
        open.values().forEach(ChatConversation::refresh);
    }

    /** The conversation's title, or New Chat until its first question stores it. */
    private static String heading(ChatConversation conversation) {
        return conversation.session().map(ChatSession::title)
                .orElse(conversation.sessionId().isEmpty() ? NEW_CHAT : CONVERSATION);
    }

    private static List<TranscriptEntry> entries(ChatConversation conversation) {
        ChatSessions chat = sessions();
        if (!chat.hasStorage()) {
            return List.of(new TranscriptEntry(I18n.get("hologram.mcagents.storage"), storageState()));
        }
        return conversation.transcript(new ChatConversation.TranscriptLabels(
                playerName(),
                I18n.get("gui.mcagents.role.assistant"),
                I18n.get("gui.mcagents.role.system"),
                I18n.get("hologram.mcagents.thinking"),
                I18n.get("hologram.mcagents.unanswered")));
    }

    /** What a panel says while there is no store to read. */
    static String storageState() {
        return switch (ChatRuntime.storageState()) {
            case OPENING, READY -> I18n.get("hologram.mcagents.storage.opening");
            case FAILED -> I18n.get("hologram.mcagents.storage.failed",
                    ChatRuntime.storageFailure().orElse("?"));
        };
    }

    private static String playerName() {
        String name = Minecraft.getInstance().getUser().getName();
        return name == null || name.isBlank() ? I18n.get("hologram.mcagents.player") : name;
    }

    private static void send(ChatConversation conversation, String text) {
        // Thrown so core keeps the text in the box; SEND is greyed whenever this would happen.
        if (!conversation.send(text)) {
            throw new IllegalStateException("The question was not taken: nothing to send, "
                    + "no storage, or another question is waiting");
        }
    }

    /** Does nothing until the conversation is stored: a new one has no title to change. */
    private static void rename(ChatConversation conversation) {
        conversation.session().ifPresent(session -> HologramDialogs.name(RENAME_TITLE,
                session.title(), session.title(), RENAME_SAVE,
                title -> sessions().rename(session.id(), title)));
    }

    /**
     * Asks before deleting: a conversation takes every message in it with it, and it cannot
     * be undone, so it is the one action here that does not happen on a single click. Once
     * deleted, its hologram closes. A new conversation not stored yet has nothing to delete.
     */
    private static void delete(String hologramId, ChatConversation conversation) {
        conversation.session().ifPresent(session -> HologramDialogs.confirm(DELETE_TITLE,
                I18n.get(DELETE_MESSAGE, session.title()), DELETE_CONFIRM, () -> {
                    sessions().delete(session.id());
                    Holograms.close(hologramId);
                    open.remove(hologramId);
                }));
    }
}
