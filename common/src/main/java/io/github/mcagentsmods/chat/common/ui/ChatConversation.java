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

/**
 * What one chat hologram shows and does, with no Minecraft in it: one conversation's messages,
 * whether a question in it is waiting for its answer, and why the last one was not answered.
 * {@link ChatSessions#open} hands one out; several can be open at once, each waiting for its
 * own answer.
 *
 * <p>A conversation opened with no id is new: it is stored with its first question, and from
 * then on it has the id of the conversation it was stored as.
 *
 * <h2>One thread owns the state</h2>
 *
 * <p>As in {@link ChatSessions}, every method is called on the client thread and every result
 * is applied there, so the getters answer from memory and the hologram may read them every
 * frame.
 *
 * <h2>Nothing blocks, nothing is lost</h2>
 *
 * <p>A question is stored before the agent is asked, so a failed answer never loses it, and a
 * new conversation appears in the list the moment its first question is stored.
 */
public final class ChatConversation {

    private static final Logger LOGGER = System.getLogger(ChatConversation.class.getName());

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

    private final ChatSessions sessions;

    /** Null until a new conversation's first question is stored. */
    private String sessionId;
    private List<ChatMessage> messages = List.of();
    private boolean waiting;

    /** Why the last question was not answered; cleared by the next one. */
    private String failure;

    /** Moves with every change, so a reader can keep what it built from the last one. */
    private long version;

    private long transcriptVersion = -1;
    private TranscriptLabels transcriptLabels;
    private List<TranscriptEntry> transcript = List.of();

    ChatConversation(ChatSessions sessions, String sessionId) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sessionId = sessionId;
    }

    // ---------------------------------------------------------------- reading

    /** The stored conversation's id; empty while a new one has not been stored yet. */
    public Optional<String> sessionId() {
        return Optional.ofNullable(sessionId);
    }

    /** The stored conversation, while the list holds it. */
    public Optional<ChatSession> session() {
        return sessions.find(sessionId);
    }

    /** The messages, oldest first. Empty for a new conversation. */
    public List<ChatMessage> messages() {
        return messages;
    }

    /** Whether a question is waiting for its answer. One is asked at a time per conversation. */
    public boolean isWaiting() {
        return waiting;
    }

    /** Whether a question may be sent now: there is a store and none is waiting here. */
    public boolean canSend() {
        return sessions.hasStorage() && !waiting;
    }

    /** Why the last question was not answered. Cleared by the next question. */
    public Optional<String> failure() {
        return Optional.ofNullable(failure);
    }

    /** Moves whenever anything a getter here answers has changed. */
    public long version() {
        return version;
    }

    /**
     * The conversation as the transcript panel shows it: each message under its speaker, then
     * the agent thinking while a question waits, or the reason the last one was not answered.
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
        } else if (failure != null) {
            entries.add(new TranscriptEntry(labels.agent(), labels.unanswered() + " " + failure));
        }
        transcript = List.copyOf(entries);
        transcriptLabels = labels;
        transcriptVersion = version;
        return transcript;
    }

    // ---------------------------------------------------------------- actions

    /** Reads the messages again. A new conversation has none to read. */
    public CompletableFuture<Void> refresh() {
        ChatRepository chat = sessions.repository();
        String id = sessionId;
        if (chat == null || id == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ChatSessions.logged(chat.findMessages(id).thenCompose(found -> onClientThread(() -> {
            // A store opened again since does not get the old one's messages.
            if (chat == sessions.repository() && id.equals(sessionId)) {
                messages = List.copyOf(found);
            }
        })), "Could not read the conversation");
    }

    /**
     * Asks a question in this conversation, storing a new one first when it has no id yet.
     *
     * <p>The question is stored first and shown as soon as it is, then the agent is asked and
     * its answer stored after it. A failure anywhere is kept as the reason the question was
     * not answered, in the provider's own words where there are some.
     *
     * @return whether the question was taken: false when it is blank, when there is no store,
     *         or while another question here is waiting
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
        ChatRepository chat = sessions.repository();
        Agent agent = sessions.agent();
        String askedIn = sessionId;

        waiting = true;
        failure = null;
        changed();

        CompletableFuture<String> session = askedIn != null
                ? CompletableFuture.completedFuture(askedIn)
                : chat.createSession(null).thenApply(ChatSession::id);
        CompletableFuture<String> stored = session.thenCompose(id -> chat
                .appendMessage(id, ChatRole.USER, question)
                .thenApply(message -> id));

        // Shown as soon as it is stored, not after the answer: a slow or failed answer must
        // not leave a question that is already saved out of the list.
        CompletableFuture<Void> shown = stored.thenCompose(id -> onClientThread(() -> sessionId = id))
                .thenCompose(applied -> CompletableFuture.allOf(refresh(), sessions.refresh()));

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
                    }
                }))
                .thenCompose(applied -> CompletableFuture.allOf(refresh(), sessions.refresh()));
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
                // Core's message names the provider, which is the one thing the player needs:
                // a direct provider is called with its own key, never through OpenRouter.
                case NO_KEY -> modelFailure.getMessage() + ". Add one with KEYS on the MCAgents menu.";
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

    /** Runs {@code change} on the client thread and marks this conversation changed. */
    private CompletableFuture<Void> onClientThread(Runnable change) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        sessions.clientThread().execute(() -> {
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

    private void changed() {
        version++;
    }
}
