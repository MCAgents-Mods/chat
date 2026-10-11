package io.github.mcagentsmods.chat.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A Knowledge Pack without its body.
 *
 * <p>A list shows twenty of these at once, and a pack's {@code rawContent} and
 * {@code embeddedData} are the two largest columns in the database — an embedding is
 * thousands of numbers. Reading them to render a row that displays a title would pull
 * megabytes off disk to show a few hundred bytes.
 *
 * <p>So a list reads summaries and a detail view reads the whole
 * {@link KnowledgeContent}. {@link #hasEmbedding} carries the one fact about the body that
 * a list actually shows, which is why it is a boolean here rather than the vector itself.
 *
 * @param hasEmbedding whether the pack carries an embedding, and so can take part in
 *                     retrieval
 */
public record KnowledgeSummary(
        String id,
        long categoryId,
        String version,
        boolean premium,
        String title,
        boolean hasEmbedding,
        Instant createdAt,
        Instant updatedAt) {

    public KnowledgeSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A content id cannot be blank");
        }
    }

    /** The summary of a pack already in memory. */
    public static KnowledgeSummary of(KnowledgeContent content) {
        return new KnowledgeSummary(
                content.id(),
                content.categoryId(),
                content.version(),
                content.premium(),
                content.title(),
                content.hasEmbedding(),
                content.createdAt(),
                content.updatedAt());
    }
}
