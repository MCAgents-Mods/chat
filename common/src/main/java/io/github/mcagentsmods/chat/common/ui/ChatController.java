package io.github.mcagentsmods.chat.common.ui;

import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.hologram.TranscriptEntry;
import io.github.mcagentsmods.core.api.model.ModelException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the chat hologram shows and does, with no Minecraft in it: the conversations, the one
 * chosen, its messages, whether a question is waiting for its answer, and why the last one
 * was not answered.
 *
 * <h2>One thread owns the state</h2>
 *
 * <p>Every method is called on the client thread, and every result a future brings back is
 * handed to the client thread through the executor given to the constructor before it
 * touches anything here. The hologram reads the getters every frame, so they answer from
 * memory and never wait. In the game the executor is {@code Minecraft::execute}; a test hands
 * one that runs at once.
 *
 * <h2>Nothing blocks, nothing is lost</h2>
 *
 * <p>A question is stored before the agent is asked, so a failed answer never loses it, and a
 * conversation that did not exist yet appears in the list the moment its first question is
 * stored. The methods that start work return a future that completes once its result is
 * shown, which a test waits on and the game ignores.
 */
public final class ChatController {

    private static final Logger LOGGER = System.getLogger(ChatController.class.getName());

    /** The longest question the prompt box takes. */
    public static final int PROMPT_LIMIT = 4_000;

    /**
     * What the transcript calls each speaker, and the two lines it adds of its own, in the
     * player's language.
     *
     * @param player     who asked: the player's name
     * @param agent      who answered
     * @param context    a message stored with any other role
     * @param thinking   the agent's line while a question waits for its answer
     * @param unanswered put before the reason a question was not answered
     */
    public record TranscriptLabels(String player, String agent, String context, String thinking,
                                   String unanswered) {
        public TranscriptLabels {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(thinking, "thinking");
            Objects.requireNonNull(unanswered, "unanswered");
        }
    }

    private final Executor clientThread;

    /** Null until storage is bound, and for good if it never opens. */
    private ChatRepository chat;
    private Agent agent;

    private List<ChatSession> sessions = List.of();
    private String activeSessionId;
    private List<ChatMessage> messages = List.of();
    private boolean waiting;

    /** Why the last question was not answered, and the conversation it was asked in. */
    private String failure;
    private String failureSessionId;

    /** Moves with every change, so a reader can keep what it built from the last one. */
    private long version;

    private long transcriptVersion = -1;
    private TranscriptLabels transcriptLabels;
    private List<TranscriptEntry> transcript = List.of();

    /** @param clientThread where results are applied: the game's client thread */
    public ChatController(Executor clientThread) {
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
            this.activeSessionId = null;
            this.messages = List.of();
            this.failure = null;
            this.failureSessionId = null;
            changed();
        }
        this.agent = agent;
        return refresh();
    }

    /** Whether a store is bound. Nothing can be read, started or asked without one. */
    public boolean hasStorage() {
        return chat != null;
    }

    /** Reads the conversations and the chosen one's messages again. */
    public CompletableFuture<Void> refresh() {
        return reload();
    }

    // ---------------------------------------------------------------- reading

    /** The conversations, the most recently used first. */
    public List<ChatSession> sessions() {
        return sessions;
    }

    /** Where the chosen conversation is in {@link #sessions()}, or -1 for none. */
    public int selectedIndex() {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id().equals(activeSessionId)) {
                return i;
            }
        }
        return -1;
    }

    /** The chosen conversation, once the list holds it. */
    public Optional<ChatSession> selected() {
        int index = selectedIndex();
        return index < 0 ? Optional.empty() : Optional.of(sessions.get(index));
    }

    /** The chosen conversation's messages, oldest first. Empty with none chosen. */
    public List<ChatMessage> messages() {
        return messages;
    }

    /** Whether a question is waiting for its answer. Only one is asked at a time. */
    public boolean isWaiting() {
        return waiting;
    }

    /** Whether a question may be sent now: there is a store and none is waiting. */
    public boolean canSend() {
        return hasStorage() && !waiting;
    }

    /**
     * Why the last question was not answered, while the conversation it was asked in is the
     * one chosen. Cleared by the next question.
     */
    public Optional<String> failure() {
        if (failure == null || !Objects.equals(failureSessionId, activeSessionId)) {
            return Optional.empty();
        }
        return Optional.of(failure);
    }

    /** Moves whenever anything a getter answers has changed. */
    public long version() {
        return version;
    }

    /**
     * The chosen conversation as the transcript panel shows it: each message under its
     * speaker, then the agent thinking while a question waits, or the reason the last one was
     * not answered.
     *
     * <p>Built again only when something changed or the labels did, so the hologram may call
     * it every frame and gets the same list back until then.
     */
    public List<TranscriptEntry> transcript(TranscriptLabels labels) {
        Objects.requireNonNull(labels, "labels");
        if (transcriptVersion == version && labels.equals(transcriptLabels)) {
            return transcript;
        }
        List<TranscriptEntry> entries = new ArrayList<>(messages.size() + 1);
        for (ChatMessage message : messages) {
            String speaker = switch (message.role()) {
                case USER -> labels.player();
                case ASSISTANT -> labels.agent();
                case SYSTEM, TOOL -> labels.context();
            };
            entries.add(new TranscriptEntry(speaker, message.content()));
        }
        if (waiting) {
            entries.add(new TranscriptEntry(labels.agent(), labels.thinking()));
        } else {
            failure().ifPresent(reason -> entries.add(
                    new TranscriptEntry(labels.agent(), labels.unanswered() + " " + reason)));
        }
        transcript = List.copyOf(entries);
        transcriptLabels = labels;
        transcriptVersion = version;
        return transcript;
    }

    // ---------------------------------------------------------------- actions

    /** Chooses the conversation at {@code index} and reads its messages. */
    public CompletableFuture<Void> select(int index) {
        if (index < 0 || index >= sessions.size()) {
            return CompletableFuture.completedFuture(null);
        }
        String id = sessions.get(index).id();
        if (id.equals(activeSessionId)) {
            return CompletableFuture.completedFuture(null);
        }
        activeSessionId = id;
        messages = List.of();
        changed();
        return loadMessages(id);
    }

    /** Starts an empty conversation and chooses it. */
    public CompletableFuture<Void> newSession() {
        ChatRepository chat = this.chat;
        if (chat == null) {
            return CompletableFuture.completedFuture(null);
        }
        return logged(chat.createSession(null)
                .thenCompose(session -> onClientThread(() -> {
                    activeSessionId = session.id();
                    messages = List.of();
                }))
                .thenCompose(shown -> reload()), "Could not start a new conversation");
    }

    /** Gives a conversation a new title; a blank one puts the default title back. */
    public CompletableFuture<Void> rename(String sessionId, String title) {
        ChatRepository chat = this.chat;
        if (chat == null || sessionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        // Back on the client thread before reload reads which conversation is chosen.
        return logged(chat.renameSession(sessionId, title)
                .thenCompose(renamed -> onClientThread(() -> { }))
                .thenCompose(shown -> reload()), "Could not rename the conversation");
    }

    /** Deletes a conversation and every message in it. */
    public CompletableFuture<Void> delete(String sessionId) {
        ChatRepository chat = this.chat;
        if (chat == null || sessionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return logged(chat.deleteSession(sessionId)
                .thenCompose(deleted -> onClientThread(() -> {
                    if (sessionId.equals(activeSessionId)) {
                        activeSessionId = null;
                        messages = List.of();
                    }
                }))
                .thenCompose(shown -> reload()), "Could not delete the conversation");
    }

    /**
     * Asks a question in the chosen conversation, or in a new one when none is chosen.
     *
     * <p>The question is stored first and shown as soon as it is, then the agent is asked and
     * its answer stored after it. A failure anywhere is kept as the reason the question was
     * not answered, in the provider's own words where there are some.
     *
     * @return whether the question was taken: false when it is blank, when there is no store,
     *         or while another question is waiting
     */
    public boolean send(String text) {
        return ask(text) != null;
    }

    /**
     * {@link #send(String)}, returning a future that completes once the answer, or the reason
     * there is none, is shown; or null when the question was not taken.
     */
    public CompletableFuture<Void> ask(String text) {
        String prompt = text == null ? "" : text.strip();
        if (prompt.isEmpty() || !canSend()) {
            return null;
        }
        if (prompt.length() > PROMPT_LIMIT) {
            prompt = prompt.substring(0, PROMPT_LIMIT);
        }
        String question = prompt;
        ChatRepository chat = this.chat;
        Agent agent = this.agent;
        String askedFrom = activeSessionId;
        // The conversation the question ended up in, once it is stored there.
        AtomicReference<String> askedIn = new AtomicReference<>(askedFrom);

        waiting = true;
        failure = null;
        failureSessionId = null;
        changed();

        CompletableFuture<String> session = askedFrom != null
                ? CompletableFuture.completedFuture(askedFrom)
                : chat.createSession(null).thenApply(ChatSession::id);
        CompletableFuture<String> stored = session.thenCompose(id -> chat
                .appendMessage(id, ChatRole.USER, question)
                .thenApply(message -> id));

        // Shown as soon as it is stored, not after the answer: a slow or failed answer must
        // not leave a question that is already saved out of the list.
        CompletableFuture<Void> shown = stored.thenCompose(id -> onClientThread(() -> {
            askedIn.set(id);
            if (Objects.equals(activeSessionId, askedFrom)) {
                activeSessionId = id;
            }
        })).thenCompose(applied -> reload());

        CompletableFuture<String> answered = stored.thenCompose(id -> agent.respond(id, question)
                .thenCompose(answer -> chat.appendMessage(id, ChatRole.ASSISTANT, answer))
                .thenApply(message -> id));

        return answered
                .handle((id, error) -> error)
                .thenCompose(error -> shown.handle((ignored, unused) -> error))
                .thenCompose(error -> onClientThread(() -> {
                    waiting = false;
                    if (error != null) {
                        LOGGER.log(Level.ERROR, "The question was not answered", error);
                        failure = describe(error);
                        failureSessionId = askedIn.get();
                    }
                }))
                .thenCompose(applied -> reload());
    }

    /**
     * Turns a failure into something worth reading.
     *
     * <p>A {@link ModelException} already carries the provider's own message, which says more
     * than anything this mod could invent: an unserved model id, a rejected key, a rate limit.
     * Anything else falls back to the exception's own text.
     */
    public static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ModelException modelFailure) {
            return switch (modelFailure.kind()) {
                case NO_KEY -> "No usable API key. Add one with KEYS on the MCAgents menu.";
                case AUTHENTICATION -> "The provider rejected every stored key: "
                        + modelFailure.getMessage();
                case RATE_LIMIT -> "Rate limited by the provider: " + modelFailure.getMessage();
                case CLOSED -> "The game is shutting down, so the question was not sent.";
                default -> modelFailure.getMessage();
            };
        }
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank()
                ? cause == null ? "Unknown failure" : cause.getClass().getSimpleName()
                : message;
    }

    // ---------------------------------------------------------------- loading

    /** Reads the conversations and the chosen one's messages, and shows both. */
    private CompletableFuture<Void> reload() {
        ChatRepository chat = this.chat;
        if (chat == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> listed = logged(chat.findAllSessions()
                .thenCompose(found -> onClientThread(() -> {
                    if (chat != this.chat) {
                        return;
                    }
                    sessions = List.copyOf(found);
                    // A conversation deleted out from under the list is no longer chosen.
                    if (activeSessionId != null && selectedIndex() < 0) {
                        activeSessionId = null;
                        messages = List.of();
                    }
                })), "Could not read the conversation list");
        String id = activeSessionId;
        CompletableFuture<Void> read = id == null
                ? CompletableFuture.completedFuture(null)
                : loadMessages(id);
        return CompletableFuture.allOf(listed, read);
    }

    private CompletableFuture<Void> loadMessages(String sessionId) {
        ChatRepository chat = this.chat;
        if (chat == null) {
            return CompletableFuture.completedFuture(null);
        }
        return logged(chat.findMessages(sessionId).thenCompose(found -> onClientThread(() -> {
            // A slower read must not overwrite the conversation the player has since chosen.
            if (chat == this.chat && sessionId.equals(activeSessionId)) {
                messages = List.copyOf(found);
            }
        })), "Could not read the conversation");
    }

    /** Runs {@code change} on the client thread and marks the state changed. */
    private CompletableFuture<Void> onClientThread(Runnable change) {
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
    private static CompletableFuture<Void> logged(CompletableFuture<Void> work, String what) {
        return work.exceptionally(failure -> {
            LOGGER.log(Level.ERROR, what, failure);
            return null;
        });
    }

    private void changed() {
        version++;
    }
}
