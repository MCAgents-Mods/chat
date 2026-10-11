package io.github.mcagentsmods.chat.api.cache;

import java.util.Optional;
import java.util.function.Function;

/**
 * A bounded, expiring store of values that are expensive to produce and cheap to redo.
 *
 * <p>Caching in this mod exists for one reason: a Knowledge Pack's text and embedding are
 * large, and the interface reads them repeatedly while the player scrolls, searches, and
 * asks questions. Going back to SQLite for the same bytes on every keystroke costs the
 * database thread work it does not need to do.
 *
 * <p><strong>A cache is never the source of truth.</strong> Everything here can be
 * discarded at any moment without changing what the mod knows — the database on disk is the
 * record. That is what makes it safe to bound it and to expire from it.
 *
 * <p>Implementations are thread-safe. Values are held in memory only, and never written to
 * disk: a cache file would be a second copy of the player's data to keep in step, and a
 * second place an API key could end up.
 *
 * @param <K> key type; must be a sensible {@code equals}/{@code hashCode}
 * @param <V> value type; must be immutable, since callers share the instance
 */
public interface Cache<K, V> {

    /** The value for {@code key} if it is present and has not expired. */
    Optional<V> get(K key);

    /** Stores a value, evicting the least recently used entry if the cache is full. */
    void put(K key, V value);

    /**
     * Returns the cached value, or computes and stores one.
     *
     * <p>{@code loader} returning {@code null} stores nothing and yields an empty result, so
     * "this does not exist" is not cached as a value.
     */
    Optional<V> get(K key, Function<K, V> loader);

    /** Forgets one key. Called after a write, so the next read sees the new value. */
    void invalidate(K key);

    /** Forgets everything. */
    void invalidateAll();

    /** How many entries are held, including any that have expired but not yet been evicted. */
    int size();

    /** Hit and miss counts since creation, for diagnosing a cache that is not helping. */
    CacheStats stats();
}
