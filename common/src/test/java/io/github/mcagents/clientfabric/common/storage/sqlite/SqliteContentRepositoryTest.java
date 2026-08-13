package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.model.ContentAvailability;
import io.github.mcagents.clientfabric.api.model.ContentQuery;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.storage.ContentRepository;
import io.github.mcagents.clientfabric.api.storage.DataAccessException;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Downloaded Knowledge Packs: storing them, finding them, and tracking their versions. */
class SqliteContentRepositoryTest {

    private static final long GUIDES = 1L;
    private static final long RULES = 2L;

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ContentRepository contents;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        contents = database.contents();
        await(contents.saveCategory(GUIDES, "Guides"));
        await(contents.saveCategory(RULES, "Server rules"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void keepsTheCategoryIdTheCentralServiceAssigned() {
        assertEquals(
                List.of("Guides", "Server rules"),
                await(contents.findAllCategories()).stream()
                        .map(category -> category.displayName())
                        .toList());
        assertEquals(GUIDES, await(contents.findAllCategories()).getFirst().id());
    }

    @Test
    void renamesACategoryInsteadOfDuplicatingIt() {
        await(contents.saveCategory(GUIDES, "Strategy guides"));

        assertEquals(2, await(contents.findAllCategories()).size());
        assertEquals("Server rules", await(contents.findAllCategories()).getFirst().displayName());
    }

    @Test
    void storesADownloadedPackWithItsEmbedding() {
        await(contents.save(pack("skyblock-intro", GUIDES, "1.0.0", false,
                "Skyblock basics", "Start by punching the tree.", "[0.1,0.2,0.3]")));

        KnowledgeContent stored = await(contents.findById("skyblock-intro")).orElseThrow();

        assertEquals("1.0.0", stored.version());
        assertEquals("Skyblock basics", stored.title());
        assertTrue(stored.hasEmbedding());
        assertFalse(stored.premium());
    }

    @Test
    void replacesAPackWhenANewerVersionIsDownloaded() {
        // The central service publishes a new version rather than editing the old one, so
        // storing an update must overwrite in place and not accumulate rows.
        await(contents.save(pack("skyblock-intro", GUIDES, "1.0.0", false,
                "Skyblock basics", "Old text", "[0.1]")));
        await(contents.save(pack("skyblock-intro", GUIDES, "1.1.0", false,
                "Skyblock basics", "New text", "[0.2]")));

        assertEquals(1, await(contents.find(ContentQuery.all())).size());
        KnowledgeContent stored = await(contents.findById("skyblock-intro")).orElseThrow();
        assertEquals("1.1.0", stored.version());
        assertEquals("New text", stored.rawContent());
    }

    @Test
    void reportsStoredVersionsSoAnUpdateCheckIsAComparison() {
        await(contents.save(pack("a", GUIDES, "1.0.0", false, "A", "text", null)));
        await(contents.save(pack("b", RULES, "2.3.1", true, "B", "text", null)));

        assertEquals(Map.of("a", "1.0.0", "b", "2.3.1"), await(contents.findStoredVersions()));
    }

    @Test
    void filtersByFreeAndPremium() {
        await(contents.save(pack("free-one", GUIDES, "1.0.0", false, "Free guide", "text", null)));
        await(contents.save(pack("paid-one", GUIDES, "1.0.0", true, "Paid guide", "text", null)));

        assertEquals(2, await(contents.find(ContentQuery.all())).size());
        assertEquals(List.of("free-one"), ids(ContentQuery.all()
                .withAvailability(ContentAvailability.FREE)));
        assertEquals(List.of("paid-one"), ids(ContentQuery.all()
                .withAvailability(ContentAvailability.PREMIUM)));
    }

    @Test
    void filtersByCategory() {
        await(contents.save(pack("guide", GUIDES, "1.0.0", false, "A guide", "text", null)));
        await(contents.save(pack("rules", RULES, "1.0.0", false, "The rules", "text", null)));

        assertEquals(List.of("rules"), ids(ContentQuery.all().inCategory(RULES)));
    }

    @Test
    void searchesTitleAndBody() {
        await(contents.save(pack("by-title", GUIDES, "1.0.0", false,
                "Enchanting table setup", "nothing relevant", null)));
        await(contents.save(pack("by-body", GUIDES, "1.0.0", false,
                "Unrelated title", "Place bookshelves around the enchanting table.", null)));

        assertEquals(List.of("by-title", "by-body"),
                ids(ContentQuery.matching("enchanting")).stream().sorted().toList().reversed());
        assertEquals(List.of("by-body"), ids(ContentQuery.matching("bookshelves")));
        assertTrue(ids(ContentQuery.matching("nether portal")).isEmpty());
    }

    @Test
    void refusesAPackWhoseCategoryHasNotBeenStored() {
        // contents.category_id is a foreign key. Saying which category is missing beats
        // surfacing a raw constraint violation to the player.
        CompletionException failure = assertThrows(CompletionException.class,
                () -> contents.save(pack("orphan", 99L, "1.0.0", false, "Orphan", "text", null)).join());

        assertTrue(failure.getCause() instanceof DataAccessException,
                "expected a DataAccessException, got " + failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("99"),
                "the message should name the missing category");
    }

    @Test
    void removesAPack() {
        await(contents.save(pack("gone", GUIDES, "1.0.0", false, "Gone", "text", null)));

        assertTrue(await(contents.remove("gone")));
        assertFalse(await(contents.remove("gone")));
        assertTrue(await(contents.findById("gone")).isEmpty());
    }

    private List<String> ids(ContentQuery query) {
        return await(contents.find(query)).stream().map(KnowledgeContent::id).toList();
    }

    private static KnowledgeContent pack(
            String id, long categoryId, String version, boolean premium,
            String title, String body, String embedding) {
        Instant now = Instant.now();
        return new KnowledgeContent(id, categoryId, version, premium, title, body, embedding, now, now);
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
