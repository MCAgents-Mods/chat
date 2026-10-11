package io.github.mcagentsmods.chat.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A grouping for Knowledge Packs, used by the content browser's filter.
 *
 * @param id          the storage identifier, assigned on insert
 * @param displayName what the player sees
 * @param createdAt   when the category was first stored
 * @param updatedAt   when it was last changed
 */
public record ContentCategory(long id, String displayName, Instant createdAt, Instant updatedAt) {

    public ContentCategory {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("A category display name cannot be blank");
        }
    }
}
