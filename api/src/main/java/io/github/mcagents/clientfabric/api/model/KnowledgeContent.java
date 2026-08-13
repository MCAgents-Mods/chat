package io.github.mcagents.clientfabric.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A downloaded Knowledge Pack: the text the agent can draw on, plus the embedding used to
 * decide whether it is relevant to a given question.
 *
 * <p>A pack is identified by {@code id}, and {@code version} says which revision of it is
 * stored. The central service publishes a new version rather than editing an existing one,
 * so comparing the two is all an update check needs.
 *
 * <p>Everything here is stored locally. Once downloaded, a pack is usable with no network
 * at all.
 *
 * @param id           the pack's identifier, assigned by the central service
 * @param categoryId   the category it belongs to
 * @param version      the revision stored, e.g. {@code 1.0.2}
 * @param premium      whether it was a paid pack
 * @param title        what the player sees in the browser
 * @param rawContent   the pack's text, as published
 * @param embeddedData the embedding vector, serialised as published
 * @param createdAt    when it was first stored
 * @param updatedAt    when this version replaced an earlier one
 */
public record KnowledgeContent(
        String id,
        long categoryId,
        String version,
        boolean premium,
        String title,
        String rawContent,
        String embeddedData,
        Instant createdAt,
        Instant updatedAt) {

    public KnowledgeContent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A content id cannot be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("A content version cannot be blank");
        }
    }

    /** Whether this pack carries an embedding, and so can take part in retrieval. */
    public boolean hasEmbedding() {
        return embeddedData != null && !embeddedData.isBlank();
    }
}
