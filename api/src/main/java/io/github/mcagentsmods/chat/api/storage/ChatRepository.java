package io.github.mcagentsmods.chat.api.storage;

import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.model.Page;
import io.github.mcagentsmods.chat.api.model.PageRequest;
import io.github.mcagentsmods.chat.api.model.SessionModels;
import io.github.mcagentsmods.core.api.chat.ChatRole;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Conversations and the messages inside them.
 *
 * <p>Sessions and messages are separate so the interface can render the session list
 * without loading a single message body.
 *
 * <p>Every method is asynchronous — see {@link LocalDatabase} for why.
 */
public interface ChatRepository {

    /** Every conversation, most recently updated first. */
    CompletableFuture<List<ChatSession>> findAllSessions();

    /**
     * Conversations whose title or message text contains {@code text}, most recently
     * updated first. A blank search returns everything.
     */
    CompletableFuture<List<ChatSession>> searchSessions(String text);

    /**
     * One window of the conversations matching a search, most recently updated first.
     *
     * <p>A blank search returns everything. The ordering is total rather than merely
     * sensible: two conversations touched in the same second carry identical timestamps,
     * and a window taken from an ambiguous ordering shows some rows twice and skips others
     * as the reader scrolls.
     */
    CompletableFuture<Page<ChatSession>> findSessionPage(String search, PageRequest page);

    /** One conversation, or empty if it does not exist. */
    CompletableFuture<Optional<ChatSession>> findSession(String id);

    /**
     * Starts a conversation.
     *
     * @param title the title to show, or {@code null} for {@link ChatSession#DEFAULT_TITLE}
     */
    CompletableFuture<ChatSession> createSession(String title);

    /**
     * Renames a conversation.
     *
     * @return whether a conversation was renamed
     */
    CompletableFuture<Boolean> renameSession(String id, String title);

    /**
     * Deletes a conversation and every message in it.
     *
     * @return whether a conversation was deleted
     */
    CompletableFuture<Boolean> deleteSession(String id);

    /**
     * The models a conversation chose for itself, or {@link SessionModels#GLOBAL} when it chose
     * none or does not exist.
     */
    CompletableFuture<SessionModels> findSessionModels(String sessionId);

    /**
     * Stores the models a conversation chose for itself; an absent choice goes back to the
     * global model. Does not touch the conversation's {@code updatedAt}: choosing a model is not
     * a turn, and the list must not re-sort for it.
     *
     * @return whether a conversation was found and updated
     */
    CompletableFuture<Boolean> saveSessionModels(String sessionId, SessionModels models);

    /** Every message in a conversation, in order, ready to send to the model API. */
    CompletableFuture<List<ChatMessage>> findMessages(String sessionId);

    /**
     * Appends a message, assigning it the next index in the conversation and touching the
     * session's {@code updatedAt} so the list re-sorts.
     *
     * @throws DataAccessException if the session does not exist
     */
    CompletableFuture<ChatMessage> appendMessage(String sessionId, ChatRole role, String content);
}
