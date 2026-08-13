package io.github.mcagents.clientfabric.common.storage;

import io.github.mcagents.clientfabric.api.cache.Cache;
import io.github.mcagents.clientfabric.api.cache.CacheStats;
import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.ContentQuery;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.storage.ContentRepository;
import io.github.mcagents.clientfabric.common.cache.TtlCache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link ContentRepository} that remembers what it has already read.
 *
 * <p>Only two things are cached, and both are chosen because they are read far more often
 * than they change:
 *
 * <ul>
 *   <li><strong>A pack by id.</strong> Its text and embedding are the largest rows in the
 *       database, and retrieval reads them for every question the player asks.</li>
 *   <li><strong>The category list.</strong> Tiny, but read on every render of the content
 *       browser.</li>
 * </ul>
 *
 * <p><strong>Searches are not cached.</strong> A query is a different key on almost every
 * keystroke, so caching one would fill the cache with entries that are never read again and
 * evict the pack bodies that are.
 *
 * <p>Every write invalidates what it affects, in the same call, before the future
 * completes. Expiry is a backstop for anything that changed outside this instance's sight,
 * not the mechanism — a player who downloads a pack sees the new version immediately, not
 * in five minutes.
 */
public final class CachingContentRepository implements ContentRepository {

    /** A pack's id is stable, so this only needs to cover a browsing session. */
    private static final Duration PACK_TTL = Duration.ofMinutes(10);

    /** Packs held at once. Well past what a content browser shows, well short of a problem. */
    private static final int PACK_CACHE_SIZE = 64;

    private static final Duration CATEGORY_TTL = Duration.ofMinutes(10);

    /** The one key under which the whole category list is held. */
    private static final String CATEGORY_KEY = "all";

    private final ContentRepository delegate;
    private final Cache<String, KnowledgeContent> packs;
    private final Cache<String, List<ContentCategory>> categories;

    /** Wraps {@code delegate} with the default cache sizes and lifetimes. */
    public CachingContentRepository(ContentRepository delegate) {
        this(delegate,
                new TtlCache<>(PACK_CACHE_SIZE, PACK_TTL),
                new TtlCache<>(1, CATEGORY_TTL));
    }

    CachingContentRepository(ContentRepository delegate,
                             Cache<String, KnowledgeContent> packs,
                             Cache<String, List<ContentCategory>> categories) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.categories = Objects.requireNonNull(categories, "categories");
    }

    @Override
    public CompletableFuture<List<ContentCategory>> findAllCategories() {
        return categories.get(CATEGORY_KEY)
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> delegate.findAllCategories()
                        .thenApply(loaded -> {
                            categories.put(CATEGORY_KEY, loaded);
                            return loaded;
                        }));
    }

    @Override
    public CompletableFuture<ContentCategory> saveCategory(long id, String displayName) {
        return delegate.saveCategory(id, displayName)
                .thenApply(saved -> {
                    categories.invalidateAll();
                    return saved;
                });
    }

    @Override
    public CompletableFuture<List<KnowledgeContent>> find(ContentQuery query) {
        // Not cached: a search key changes on nearly every keystroke, so caching would
        // evict the pack bodies that are worth holding.
        return delegate.find(query);
    }

    @Override
    public CompletableFuture<Optional<KnowledgeContent>> findById(String id) {
        Optional<KnowledgeContent> cached = packs.get(id);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached);
        }
        return delegate.findById(id).thenApply(loaded -> {
            // Absence is not cached: a pack that is missing now may be downloaded next,
            // and a negative entry would hide it until it expired.
            loaded.ifPresent(pack -> packs.put(id, pack));
            return loaded;
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> findStoredVersions() {
        // Not cached: this is the input to an update check, and a stale answer here would
        // make the mod re-download a pack it already has, or skip one it needs.
        return delegate.findStoredVersions();
    }

    @Override
    public CompletableFuture<KnowledgeContent> save(KnowledgeContent content) {
        return delegate.save(content).thenApply(saved -> {
            packs.put(saved.id(), saved);
            return saved;
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(String id) {
        return delegate.remove(id).thenApply(removed -> {
            // Invalidate whatever the delegate reported: a remove that matched nothing
            // still means this instance should not be holding that id.
            packs.invalidate(id);
            return removed;
        });
    }

    /** What the pack cache has done, for diagnosing a cache that is not helping. */
    public CacheStats packCacheStats() {
        return packs.stats();
    }

    /** Drops everything held. The database is the record; this only costs a re-read. */
    public void invalidateAll() {
        packs.invalidateAll();
        categories.invalidateAll();
    }
}
