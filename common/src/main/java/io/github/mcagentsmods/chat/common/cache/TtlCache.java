package io.github.mcagentsmods.chat.common.cache;

import io.github.mcagentsmods.chat.api.cache.Cache;
import io.github.mcagentsmods.chat.api.cache.CacheStats;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A bounded LRU cache whose entries also expire with age.
 *
 * <p>Both limits matter and they guard different things. **The size bound** is what keeps a
 * player with a thousand Knowledge Packs from holding all of them in memory at once — this
 * runs inside Minecraft, and memory taken here is memory the game does not have. **The time
 * bound** is what keeps an entry from outliving a change made outside this cache's sight.
 *
 * <p>Writes go through {@code invalidate}, so the common case does not depend on expiry —
 * the clock is a backstop, not the mechanism.
 *
 * <p>Guarded by a single lock rather than built on a concurrent map. The maps here hold
 * tens of entries and are read from the database thread and the client thread only; a lock
 * that is never contended costs nothing, and it makes the LRU order and the statistics
 * consistent with each other, which striped locking would not.
 */
public final class TtlCache<K, V> implements Cache<K, V> {

    private final int maximumSize;
    private final Duration timeToLive;
    private final Supplier<Instant> clock;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expiries = new AtomicLong();

    private final Object lock = new Object();
    private final LinkedHashMap<K, CacheEntry<V>> entries;

    private record CacheEntry<V>(V value, Instant expiresAt) {
        boolean hasExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /**
     * @param maximumSize most entries to hold; the least recently used goes first
     * @param timeToLive  how long an entry stays usable
     */
    public TtlCache(int maximumSize, Duration timeToLive) {
        this(maximumSize, timeToLive, Instant::now);
    }

    /** Test seam: the clock is injectable so expiry can be exercised without sleeping. */
    TtlCache(int maximumSize, Duration timeToLive, Supplier<Instant> clock) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be at least 1, got " + maximumSize);
        }
        if (timeToLive.isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("timeToLive must be positive, got " + timeToLive);
        }
        this.maximumSize = maximumSize;
        this.timeToLive = timeToLive;
        this.clock = Objects.requireNonNull(clock, "clock");

        // accessOrder = true makes get() count as a use, which is what makes this LRU
        // rather than first-in-first-out.
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                boolean full = size() > TtlCache.this.maximumSize;
                if (full) {
                    evictions.incrementAndGet();
                }
                return full;
            }
        };
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            CacheEntry<V> entry = entries.get(key);
            if (entry == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            if (entry.hasExpired(clock.get())) {
                entries.remove(key);
                expiries.incrementAndGet();
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(entry.value());
        }
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (lock) {
            entries.put(key, new CacheEntry<>(value, clock.get().plus(timeToLive)));
        }
    }

    @Override
    public Optional<V> get(K key, Function<K, V> loader) {
        Objects.requireNonNull(loader, "loader");

        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached;
        }

        // Deliberately outside the lock: the loader reads the database, and holding a lock
        // across that would serialise every caller behind the slowest one. Two callers may
        // load the same key concurrently; that is a duplicate read, not a correctness
        // problem, and it is cheaper than the contention.
        V loaded = loader.apply(key);
        if (loaded == null) {
            // Absence is not cached. A pack that does not exist yet may exist after the
            // next download, and a negative entry would hide it until it expired.
            return Optional.empty();
        }
        put(key, loaded);
        return Optional.of(loaded);
    }

    @Override
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            entries.remove(key);
        }
    }

    @Override
    public void invalidateAll() {
        synchronized (lock) {
            entries.clear();
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits.get(), misses.get(), evictions.get(), expiries.get());
    }

    @Override
    public String toString() {
        return "TtlCache[size=" + size() + "/" + maximumSize + ", ttl=" + timeToLive + "]";
    }
}
