package io.github.mcagents.clientfabric.api.model;

/**
 * How much of a list to read, and from where.
 *
 * <p>Lists in this mod are read a window at a time rather than whole. A player who has
 * downloaded a few hundred Knowledge Packs should not wait for all of them, and a browser
 * that asks for everything at once is a browser that stutters — so the interface asks for
 * the first window and then for the next as the reader scrolls.
 *
 * <h2>Out-of-range values are clamped, not rejected</h2>
 *
 * <p>These values come from query strings, which anyone with the console's address can
 * type. A negative offset or a limit of a million is not an attack worth an error page; it
 * is a value to bring back into range. Clamping here means no caller can build a query that
 * reads the whole table, however the numbers arrived.
 *
 * @param offset how many rows to skip, never negative
 * @param limit  how many rows to return, between 1 and {@link #MAX_LIMIT}
 */
public record PageRequest(int offset, int limit) {

    /** The most any single request may read, whatever it asks for. */
    public static final int MAX_LIMIT = 100;

    /** What the knowledge list asks for. */
    public static final int DEFAULT_LIMIT = 20;

    /** What the conversation list asks for; smaller, because the rows are wider. */
    public static final int DEFAULT_CHAT_LIMIT = 10;

    public PageRequest {
        offset = Math.max(0, offset);
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }

    /** The first window of the default size. */
    public static PageRequest first() {
        return new PageRequest(0, DEFAULT_LIMIT);
    }

    /** The first window of a given size. */
    public static PageRequest first(int limit) {
        return new PageRequest(0, limit);
    }

    /** The window after this one. */
    public PageRequest next() {
        return new PageRequest(offset + limit, limit);
    }
}
