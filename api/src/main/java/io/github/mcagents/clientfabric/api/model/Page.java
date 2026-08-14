package io.github.mcagents.clientfabric.api.model;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One window of a longer list, and enough context to ask for the next.
 *
 * <p>{@code total} is counted rather than guessed. It costs a second query, and it buys the
 * two things a caller cannot work out from the items alone: whether to keep scrolling, and
 * what to tell the reader they are looking at. Inferring "there is more" from a full window
 * is wrong exactly once per list — when the last window happens to be full — and that is
 * the case a reader notices.
 *
 * @param items  the rows in this window, in the order the query returned them
 * @param offset how many rows were skipped to reach them
 * @param limit  how many were asked for
 * @param total  how many rows match the query in full, ignoring the window
 */
public record Page<T>(List<T> items, int offset, int limit, long total) {

    public Page {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
    }

    /** An empty window, for a query that matched nothing. */
    public static <T> Page<T> empty(PageRequest request) {
        return new Page<>(List.of(), request.offset(), request.limit(), 0);
    }

    /** Whether anything follows this window. */
    public boolean hasMore() {
        return offset + items.size() < total;
    }

    /** Where the next window starts. */
    public int nextOffset() {
        return offset + items.size();
    }

    /**
     * The same window with its rows converted.
     *
     * <p>Lets a repository return records and a caller hand out whatever shape it presents,
     * without either of them recounting the total.
     */
    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new Page<>(items.stream().<R>map(mapper).toList(), offset, limit, total);
    }
}
