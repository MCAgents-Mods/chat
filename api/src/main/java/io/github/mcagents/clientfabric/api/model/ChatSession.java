package io.github.mcagents.clientfabric.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One conversation, shown as a row in the session list.
 *
 * <p>Sessions exist as their own record so the interface can list every conversation with
 * its title without reading a single message.
 *
 * @param id        a UUID string
 * @param title     what the player sees in the list
 * @param createdAt when the conversation started
 * @param updatedAt when a message was last appended, which is what the list sorts on
 */
public record ChatSession(String id, String title, Instant createdAt, Instant updatedAt) {

    /** The title a session is given until it is renamed. */
    public static final String DEFAULT_TITLE = "New Chat";

    public ChatSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A session id cannot be blank");
        }
    }
}
