package io.github.mcagents.clientfabric.api.model;

import java.util.Objects;
import java.util.Optional;

/**
 * What the content browser is asking for: a search box, a free/premium filter, and an
 * optional category.
 *
 * @param text         matched against a pack's title and text; blank means no text filter
 * @param availability the free/premium filter
 * @param categoryId   restrict to one category, or {@code null} for all of them
 */
public record ContentQuery(String text, ContentAvailability availability, Long categoryId) {

    public ContentQuery {
        Objects.requireNonNull(availability, "availability");
        text = text == null ? "" : text.trim();
    }

    /** Everything stored, unfiltered. */
    public static ContentQuery all() {
        return new ContentQuery("", ContentAvailability.ANY, null);
    }

    /** Everything matching a search box. */
    public static ContentQuery matching(String text) {
        return new ContentQuery(text, ContentAvailability.ANY, null);
    }

    /** Whether a text filter was supplied. */
    public boolean hasText() {
        return !text.isEmpty();
    }

    /** The category filter, if any. */
    public Optional<Long> category() {
        return Optional.ofNullable(categoryId);
    }

    /** This query, restricted to one category. */
    public ContentQuery inCategory(long id) {
        return new ContentQuery(text, availability, id);
    }

    /** This query, with a different free/premium filter. */
    public ContentQuery withAvailability(ContentAvailability newAvailability) {
        return new ContentQuery(text, newAvailability, categoryId);
    }
}
