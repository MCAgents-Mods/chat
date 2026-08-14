package io.github.mcagents.clientfabric.common.storage;

import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.ContentQuery;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.model.KnowledgeSummary;
import io.github.mcagents.clientfabric.api.model.Page;
import io.github.mcagents.clientfabric.api.model.PageRequest;
import io.github.mcagents.clientfabric.api.storage.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the caching decorator is and is not allowed to do.
 *
 * <p>The delegate here counts its calls, so every assertion is about whether the database
 * was actually touched — which is the only thing a cache changes.
 */
class CachingContentRepositoryTest {

    /** A stand-in repository that records what was asked of it. */
    private static final class CountingRepository implements ContentRepository {
        final Map<String, KnowledgeContent> stored = new HashMap<>();
        final List<ContentCategory> categories = new ArrayList<>();
        int findByIdCalls;
        int findAllCategoriesCalls;
        int findCalls;
        int findSummaryPageCalls;
        int findStoredVersionsCalls;

        @Override
        public CompletableFuture<List<ContentCategory>> findAllCategories() {
            findAllCategoriesCalls++;
            return CompletableFuture.completedFuture(List.copyOf(categories));
        }

        @Override
        public CompletableFuture<ContentCategory> saveCategory(long id, String displayName) {
            Instant now = Instant.now();
            ContentCategory category = new ContentCategory(id, displayName, now, now);
            categories.removeIf(existing -> existing.id() == id);
            categories.add(category);
            return CompletableFuture.completedFuture(category);
        }

        @Override
        public CompletableFuture<List<KnowledgeContent>> find(ContentQuery query) {
            findCalls++;
            return CompletableFuture.completedFuture(List.copyOf(stored.values()));
        }

        @Override
        public CompletableFuture<Page<KnowledgeSummary>> findSummaryPage(
                ContentQuery query, PageRequest page) {
            findSummaryPageCalls++;
            List<KnowledgeSummary> summaries = stored.values().stream()
                    .map(KnowledgeSummary::of)
                    .toList();
            return CompletableFuture.completedFuture(
                    new Page<>(summaries, page.offset(), page.limit(), summaries.size()));
        }

        @Override
        public CompletableFuture<Optional<KnowledgeContent>> findById(String id) {
            findByIdCalls++;
            return CompletableFuture.completedFuture(Optional.ofNullable(stored.get(id)));
        }

        @Override
        public CompletableFuture<Map<String, String>> findStoredVersions() {
            findStoredVersionsCalls++;
            Map<String, String> versions = new HashMap<>();
            stored.forEach((id, pack) -> versions.put(id, pack.version()));
            return CompletableFuture.completedFuture(Map.copyOf(versions));
        }

        @Override
        public CompletableFuture<KnowledgeContent> save(KnowledgeContent content) {
            stored.put(content.id(), content);
            return CompletableFuture.completedFuture(content);
        }

        @Override
        public CompletableFuture<Boolean> remove(String id) {
            return CompletableFuture.completedFuture(stored.remove(id) != null);
        }
    }

    private CountingRepository delegate;
    private CachingContentRepository repository;

    @BeforeEach
    void setUp() {
        delegate = new CountingRepository();
        repository = new CachingContentRepository(delegate);
        delegate.stored.put("guide", pack("guide", "1.0.0", "Original text"));
    }

    private static KnowledgeContent pack(String id, String version, String body) {
        Instant now = Instant.now();
        return new KnowledgeContent(id, 1L, version, false, "Title", body, "[0.1]", now, now);
    }

    @Test
    void readsAPackOnceAndServesTheRestFromMemory() {
        for (int i = 0; i < 5; i++) {
            assertEquals("Original text", await(repository.findById("guide")).orElseThrow().rawContent());
        }

        assertEquals(1, delegate.findByIdCalls, "the database should have been read once");
        assertEquals(4, repository.packCacheStats().hits());
    }

    @Test
    void doesNotCacheAPackThatIsNotThere() {
        // A missing pack may be downloaded a moment later. Remembering the absence would
        // hide it until the entry expired.
        await(repository.findById("absent"));
        await(repository.findById("absent"));

        assertEquals(2, delegate.findByIdCalls);
    }

    @Test
    void showsTheNewVersionImmediatelyAfterADownload() {
        // This is the case that makes expiry a backstop rather than the mechanism: a player
        // who updates a pack must not wait for a timer to see the new text.
        await(repository.findById("guide"));

        await(repository.save(pack("guide", "2.0.0", "Updated text")));

        KnowledgeContent read = await(repository.findById("guide")).orElseThrow();
        assertEquals("2.0.0", read.version());
        assertEquals("Updated text", read.rawContent());
    }

    @Test
    void forgetsAPackThatWasRemoved() {
        await(repository.findById("guide"));

        assertTrue(await(repository.remove("guide")));

        assertTrue(await(repository.findById("guide")).isEmpty(),
                "the cache must not keep serving a pack the player deleted");
    }

    @Test
    void forgetsAPackEvenWhenTheRemoveMatchedNothing() {
        await(repository.findById("guide"));
        delegate.stored.remove("guide");

        assertFalse(await(repository.remove("guide")));

        assertTrue(await(repository.findById("guide")).isEmpty());
    }

    @Test
    void readsTheCategoryListOnce() {
        await(repository.saveCategory(1L, "Guides"));

        await(repository.findAllCategories());
        await(repository.findAllCategories());
        await(repository.findAllCategories());

        assertEquals(1, delegate.findAllCategoriesCalls);
    }

    @Test
    void rereadsTheCategoryListAfterItChanges() {
        await(repository.saveCategory(1L, "Guides"));
        await(repository.findAllCategories());

        await(repository.saveCategory(2L, "Rules"));

        assertEquals(2, await(repository.findAllCategories()).size());
        assertEquals(2, delegate.findAllCategoriesCalls);
    }

    @Test
    void neverCachesASearch() {
        // A search key changes on nearly every keystroke; caching it would evict the pack
        // bodies that are worth holding.
        await(repository.find(ContentQuery.all()));
        await(repository.find(ContentQuery.all()));

        assertEquals(2, delegate.findCalls);
    }

    @Test
    void neverCachesTheStoredVersionMap() {
        // This is the input to an update check. A stale answer would re-download a pack the
        // player already has, or skip one they need.
        await(repository.findStoredVersions());
        await(repository.findStoredVersions());

        assertEquals(2, delegate.findStoredVersionsCalls);
    }

    @Test
    void goesBackToTheDatabaseAfterEverythingIsDropped() {
        await(repository.findById("guide"));

        repository.invalidateAll();
        await(repository.findById("guide"));

        assertEquals(2, delegate.findByIdCalls);
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.join();
    }
}
