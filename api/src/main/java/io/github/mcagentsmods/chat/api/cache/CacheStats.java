package io.github.mcagentsmods.chat.api.cache;

/**
 * What a cache has done since it was created.
 *
 * <p>Worth having because a cache that never hits is pure cost, and the only way to know
 * is to count. These are cumulative and never reset.
 *
 * @param hits      reads served from the cache
 * @param misses    reads that had to go to the database
 * @param evictions entries dropped because the cache was full
 * @param expiries  entries dropped because they were too old
 */
public record CacheStats(long hits, long misses, long evictions, long expiries) {

    /** Reads served from the cache, between 0 and 1. Zero reads counts as zero. */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    @Override
    public String toString() {
        return "CacheStats[hits=%d, misses=%d, evictions=%d, expiries=%d, hitRate=%.2f]"
                .formatted(hits, misses, evictions, expiries, hitRate());
    }
}
