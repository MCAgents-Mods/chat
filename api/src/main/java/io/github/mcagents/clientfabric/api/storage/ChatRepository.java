package io.github.mcagents.clientfabric.api.storage;

import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.ChatSession;

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
