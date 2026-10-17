package io.github.mcagentsmods.chat.common.ui;

import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The conversations the chat holograms share, with no Minecraft in it: the store and the agent
 * once storage is open, and the list of conversations the session picker shows. Each chat
 * hologram reads and asks in one {@link ChatConversation}, which {@link #open} hands out.
 *
 * <h2>One thread owns the state</h2>
 *
 * <p>Every method is called on the client thread, and every result a future brings back is
 * handed to the client thread through the executor given to the constructor before it
 * touches anything here. The holograms read the getters every frame, so they answer from
 * memory and never wait. In the game the executor is {@code Minecraft::execute}; a test hands
 * one that runs at once.
 *
 * <p>The methods that start work return a future that completes once its result is shown,
 * which a test waits on and the game ignores.
 */
public final class ChatSessions {

    private static final Logger LOGGER = System.getLogger(ChatSessions.class.getName());

    private final Executor clientThread;

    /** Null until storage is bound, and for good if it never opens. */
    private ChatRepository chat;
    private Agent agent;

    private List<ChatSession> sessions = List.of();

    /** Moves with every change to the list, so a reader can keep what it built from the last one. */
    private long version;

    /** @param clientThread where results are applied: the game's client thread */
    public ChatSessions(Executor clientThread) {
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
    }

    // ------------------------------------------------------------------ storage

    /**
     * Starts reading from {@code chat} and asking {@code agent}, and reads the conversations.
     * Binding the store already bound only reads them again.
     */
    public CompletableFuture<Void> bind(ChatRepository chat, Agent agent) {
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(agent, "agent");
        if (chat != this.chat) {
            this.chat = chat;
            this.sessions = List.of();
            changed();
        }
        this.agent = agent;
        return refresh();
    }

    /** Whether a store is bound. Nothing can be read, started or asked without one. */
    public boolean hasStorage() {
        return chat != null;
    }

    /** Reads the conversations again. */
    public CompletableFuture<Void> refresh() {
        ChatRepository chat = this.chat;
        if (chat == null) {
            return CompletableFuture.completedFuture(null);
        }
        return logged(chat.findAllSessions()
                .thenCompose(found -> onClientThread(() -> {
                    if (chat == this.chat) {
                        sessions = List.copyOf(found);
                    }
                })), "Could not read the conversation list");
    }

    // ---------------------------------------------------------------- reading

    /** The conversations, the most recently used first. */
    public List<ChatSession> sessions() {
        return sessions;
    }

    /** The conversation with this id, while the list holds it. */
    public Optional<ChatSession> find(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return sessions.stream().filter(session -> session.id().equals(sessionId)).findFirst();
    }

    /** Moves whenever the list has changed. */
    public long version() {
        return version;
    }

    // ---------------------------------------------------------------- actions

    /**
     * A conversation to read and ask in: the stored one with this id, or, for {@code null}, a
     * new one that is stored with its first question. Its messages are read at once.
     */
    public ChatConversation open(String sessionId) {
        ChatConversation conversation = new ChatConversation(this, sessionId);
        conversation.refresh();
        return conversation;
    }

    /** Gives a conversation a new title; a blank one puts the default title back. */
    public CompletableFuture<Void> rename(String sessionId, String title) {
        ChatRepository chat = this.chat;
        if (chat == null || sessionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return logged(chat.renameSession(sessionId, title).thenCompose(renamed -> refresh()),
                "Could not rename the conversation");
    }

    /** Deletes a conversation and every message in it. */
    public CompletableFuture<Void> delete(String sessionId) {
        ChatRepository chat = this.chat;
        if (chat == null || sessionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return logged(chat.deleteSession(sessionId).thenCompose(deleted -> refresh()),
                "Could not delete the conversation");
    }

    // ------------------------------------------------- for the conversations

    ChatRepository repository() {
        return chat;
    }

    Agent agent() {
        return agent;
    }

    Executor clientThread() {
        return clientThread;
    }

    /** Runs {@code change} on the client thread and marks the list changed. */
    CompletableFuture<Void> onClientThread(Runnable change) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        clientThread.execute(() -> {
            try {
                change.run();
                changed();
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Logs a failure rather than letting it vanish into a future nobody reads. */
    static CompletableFuture<Void> logged(CompletableFuture<Void> work, String what) {
        return work.exceptionally(failure -> {
            LOGGER.log(Level.ERROR, what, failure);
            return null;
        });
    }

    private void changed() {
        version++;
    }
}
