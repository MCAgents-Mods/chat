package io.github.mcagents.clientfabric.common.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The size bound, the time bound, and the things a cache must not do. */
class TtlCacheTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    private <K, V> TtlCache<K, V> cache(int size, Duration ttl) {
        return new TtlCache<>(size, ttl, now::get);
    }

    private void advance(Duration amount) {
        now.updateAndGet(instant -> instant.plus(amount));
    }

    @Test
    void servesAStoredValue() {
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));

        cache.put("a", "one");

        assertEquals("one", cache.get("a").orElseThrow());
        assertEquals(1, cache.stats().hits());
        assertEquals(0, cache.stats().misses());
    }

    @Test
    void reportsAMissForAKeyItNeverHeld() {
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));

        assertTrue(cache.get("absent").isEmpty());
        assertEquals(1, cache.stats().misses());
    }

    @Test
    void expiresAnEntryOnceItIsTooOld() {
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));
        cache.put("a", "one");

        advance(Duration.ofMinutes(4));
        assertTrue(cache.get("a").isPresent(), "still inside the lifetime");

        advance(Duration.ofMinutes(2));
        assertTrue(cache.get("a").isEmpty(), "past the lifetime");
        assertEquals(1, cache.stats().expiries());
    }

    @Test
    void evictsTheLeastRecentlyUsedWhenFull() {
        TtlCache<String, String> cache = cache(2, Duration.ofMinutes(5));

        cache.put("a", "one");
        cache.put("b", "two");
        cache.get("a");        // "a" is now the more recently used of the two
        cache.put("c", "three");

        assertTrue(cache.get("b").isEmpty(), "b was least recently used and should have gone");
        assertTrue(cache.get("a").isPresent());
        assertTrue(cache.get("c").isPresent());
        assertEquals(1, cache.stats().evictions());
    }

    @Test
    void neverGrowsPastItsBound() {
        // This is the bound that matters: the cache runs inside Minecraft, and memory taken
        // here is memory the game does not have.
        TtlCache<Integer, String> cache = cache(10, Duration.ofMinutes(5));

        for (int i = 0; i < 500; i++) {
            cache.put(i, "value " + i);
        }

        assertEquals(10, cache.size());
    }

    @Test
    void loadsOnlyOnAMiss() {
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            cache.get("a", key -> {
                loads.incrementAndGet();
                return "loaded";
            });
        }

        assertEquals(1, loads.get(), "the loader should have run once, not five times");
    }

    @Test
    void doesNotCacheAbsence() {
        // A pack that is missing now may be downloaded next. Caching "not found" would hide
        // it until the entry expired.
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            assertTrue(cache.get("missing", key -> {
                loads.incrementAndGet();
                return null;
            }).isEmpty());
        }

        assertEquals(3, loads.get(), "a null load must not be stored");
        assertEquals(0, cache.size());
    }

    @Test
    void forgetsOneKeyOnInvalidate() {
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));
        cache.put("a", "one");
        cache.put("b", "two");

        cache.invalidate("a");

        assertTrue(cache.get("a").isEmpty());
        assertTrue(cache.get("b").isPresent());
    }

    @Test
    void forgetsEverythingOnInvalidateAll() {
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));
        cache.put("a", "one");
        cache.put("b", "two");

        cache.invalidateAll();

        assertEquals(0, cache.size());
    }

    @Test
    void reportsAHitRate() {
        TtlCache<String, String> cache = cache(4, Duration.ofMinutes(5));
        cache.put("a", "one");

        cache.get("a");
        cache.get("a");
        cache.get("b");

        assertEquals(2.0 / 3.0, cache.stats().hitRate(), 0.0001);
        assertEquals(0.0, cache(4, Duration.ofMinutes(5)).stats().hitRate(),
                "no reads is not a division by zero");
    }

    @Test
    void refusesNonsensicalBounds() {
        assertThrows(IllegalArgumentException.class, () -> cache(0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> cache(4, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> cache(4, Duration.ofMinutes(-1)));
    }

    @Test
    void survivesConcurrentUse() throws Exception {
        // Reads come from the client thread and writes from the database thread, so this
        // must not corrupt itself or throw.
        TtlCache<Integer, String> cache = cache(32, Duration.ofMinutes(5));
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < 500; i++) {
                        cache.put(i % 64, "value " + i);
                        cache.get(i % 64);
                    }
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(cache.size() <= 32, "the bound must hold under concurrency, was " + cache.size());
    }
}
