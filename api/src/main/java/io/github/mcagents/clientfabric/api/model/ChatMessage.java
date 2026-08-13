package io.github.mcagents.clientfabric.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One message in a conversation.
 *
 * <p>A message is a single turn by a single speaker, not a question-and-answer pair. That
 * is the shape the model API expects, so a stored conversation can be replayed to it
 * without being reassembled.
 *
 * @param id        the storage identifier, assigned on insert
 * @param sessionId the conversation this belongs to
 * @param index     position within the conversation, starting at 1
 * @param role      who produced it
 * @param content   the text
 * @param createdAt when it was stored
 */
public record ChatMessage(
        long id,
        String sessionId,
        int index,
        ChatRole role,
        String content,
        Instant createdAt) {

    public ChatMessage {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        if (index < 1) {
            throw new IllegalArgumentException("A message index starts at 1, got " + index);
        }
    }
}
