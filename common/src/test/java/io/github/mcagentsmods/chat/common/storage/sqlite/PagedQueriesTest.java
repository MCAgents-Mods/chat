package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.model.ContentAvailability;
import io.github.mcagentsmods.chat.api.model.ContentQuery;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.model.KnowledgeSummary;
import io.github.mcagentsmods.chat.api.model.Page;
import io.github.mcagentsmods.chat.api.model.PageRequest;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.chat.api.storage.ContentRepository;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a long list a window at a time.
 *
 * <p>The tests that matter most here are the ones that walk a whole list window by window
 * and check that every row appeared exactly once. That is the failure paging actually has:
 * not an exception, but rows shown twice while others are never shown at all, which a
 * reader experiences as data loss rather than as a sort bug.
 */
class PagedQueriesTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ContentRepository contents;
    private ChatRepository chat;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        contents = database.contents();
        chat = database.chat();
    }

    @AfterEach
    void close() {
        database.close();
    }

    // ------------------------------------------------------------ knowledge

    @Test
    void readsKnowledgeAWindowAtATime() {
        givenCategory(1, "Guides");
        givenPacks(1, 25);

        Page<KnowledgeSummary> first = await(contents.findSummaryPage(ContentQuery.all(),
                PageRequest.first()));

        assertEquals(20, first.items().size(), "the default window is twenty");
        assertEquals(25, first.total());
        assertTrue(first.hasMore());
        assertEquals(20, first.nextOffset());

        Page<KnowledgeSummary> second = await(contents.findSummaryPage(ContentQuery.all(),
                new PageRequest(first.nextOffset(), 20)));

        assertEquals(5, second.items().size());
        assertFalse(second.hasMore(), "the last window says so rather than leaving it to be inferred");
    }

    @Test
    void showsEveryKnowledgeRowExactlyOnceAcrossWindows() {
        givenCategory(1, "Guides");
        givenPacks(1, 47);

        List<String> seen = new ArrayList<>();
        PageRequest window = PageRequest.first(7);
        while (true) {
            Page<KnowledgeSummary> page = await(contents.findSummaryPage(ContentQuery.all(), window));
            page.items().forEach(summary -> seen.add(summary.id()));
            if (!page.hasMore()) {
                break;
            }
            window = new PageRequest(page.nextOffset(), 7);
        }

        assertEquals(47, seen.size(), "every pack appears");
        assertEquals(47, Set.copyOf(seen).size(), "and none of them twice");
    }

    @Test
    void leavesTheBodiesOnDisk() {
        // The whole reason summaries exist. A window of twenty rows carrying raw_content and
        // embedded_data would read megabytes to render a list of titles.
        givenCategory(1, "Guides");
        await(contents.save(new KnowledgeContent("with-embedding", 1, "1.0.0", false,
                "Has an embedding", "body text", "[0.1,0.2,0.3]", Instant.now(), Instant.now())));
        await(contents.save(new KnowledgeContent("without-embedding", 1, "1.0.0", false,
                "No embedding", "body text", "   ", Instant.now(), Instant.now())));

        List<KnowledgeSummary> items =
                await(contents.findSummaryPage(ContentQuery.all(), PageRequest.first())).items();

        assertTrue(items.stream().filter(s -> s.id().equals("with-embedding"))
                .findFirst().orElseThrow().hasEmbedding());
        assertFalse(items.stream().filter(s -> s.id().equals("without-embedding"))
                .findFirst().orElseThrow().hasEmbedding(),
                "a blank embedding is no embedding, and the list says so without reading it");
    }

    @Test
    void filtersAPageBySearchCategoryAndAvailability() {
        givenCategory(1, "Guides");
        givenCategory(2, "Strategies");
        await(contents.save(pack("redstone-basics", 1, "Redstone basics", false)));
        await(contents.save(pack("redstone-advanced", 2, "Redstone advanced", true)));
        await(contents.save(pack("farming", 1, "Farming", false)));

        Page<KnowledgeSummary> byText = await(contents.findSummaryPage(
                ContentQuery.matching("redstone"), PageRequest.first()));
        assertEquals(2, byText.total());

        Page<KnowledgeSummary> byCategory = await(contents.findSummaryPage(
                ContentQuery.all().inCategory(1), PageRequest.first()));
        assertEquals(2, byCategory.total());

        Page<KnowledgeSummary> premium = await(contents.findSummaryPage(
                ContentQuery.all().withAvailability(ContentAvailability.PREMIUM),
                PageRequest.first()));
        assertEquals(1, premium.total());
        assertEquals("redstone-advanced", premium.items().getFirst().id());

        Page<KnowledgeSummary> combined = await(contents.findSummaryPage(
                ContentQuery.matching("redstone").inCategory(1), PageRequest.first()));
        assertEquals(1, combined.total());
        assertEquals("redstone-basics", combined.items().getFirst().id());
    }

    @Test
    void countsTheWholeMatchNotJustTheWindow() {
        // The count is what tells a reader there is more to scroll to. Counting the window
        // would make every list look complete.
        givenCategory(1, "Guides");
        givenPacks(1, 30);

        Page<KnowledgeSummary> page = await(contents.findSummaryPage(ContentQuery.all(),
                PageRequest.first(5)));

        assertEquals(5, page.items().size());
        assertEquals(30, page.total());
    }

    @Test
    void treatsASearchThatMatchesNothingAsAnEmptyPage() {
        givenCategory(1, "Guides");
        givenPacks(1, 3);

        Page<KnowledgeSummary> page = await(contents.findSummaryPage(
                ContentQuery.matching("nothing matches this"), PageRequest.first()));

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertFalse(page.hasMore());
    }

    @Test
    void escapesSearchTextRatherThanLettingItMatchEverything() {
        givenCategory(1, "Guides");
        await(contents.save(pack("literal", 1, "100% cocoa", false)));
        await(contents.save(pack("other", 1, "Something else", false)));

        assertEquals(1, await(contents.findSummaryPage(
                ContentQuery.matching("100%"), PageRequest.first())).total());
    }

    // ----------------------------------------------------------------- chat

    @Test
    void readsConversationsAWindowAtATime() {
        for (int i = 0; i < 23; i++) {
            await(chat.createSession("Chat " + i));
        }

        Page<ChatSession> first = await(chat.findSessionPage(null,
                PageRequest.first(PageRequest.DEFAULT_CHAT_LIMIT)));

        assertEquals(10, first.items().size());
        assertEquals(23, first.total());
        assertTrue(first.hasMore());
    }

    @Test
    void ordersConversationsTouchedInTheSameSecondMostRecentFirst() {
        // CURRENT_TIMESTAMP resolves to the second, so all three of these carry the same
        // updated_at and the sort keys cannot separate them. The query breaks the tie on
        // rowid DESC, which means most recently created first — and that is what this
        // asserts, because it is the only observable difference between an ordering that is
        // total and one that merely happens to come out of the current query plan.
        ChatSession oldest = await(chat.createSession("First"));
        ChatSession middle = await(chat.createSession("Second"));
        ChatSession newest = await(chat.createSession("Third"));

        List<String> order = await(chat.findSessionPage(null, PageRequest.first(10)))
                .items().stream().map(ChatSession::id).toList();

        assertEquals(List.of(newest.id(), middle.id(), oldest.id()), order);
    }

    @Test
    void showsEveryConversationExactlyOnceAcrossWindows() {
        // Walks the whole list window by window. Note what this does and does not prove:
        // it catches an off-by-one in the offset arithmetic, and it would catch an ordering
        // that changed between queries — but SQLite's own scan order is already stable for
        // a dataset this size, so this passes with or without the rowid tie-break. The
        // tie-break is what makes the ordering total by definition rather than by the luck
        // of the current query plan; the test above is the one that pins it.
        for (int i = 0; i < 34; i++) {
            await(chat.createSession("Chat " + i));
        }

        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        PageRequest window = PageRequest.first(10);
        while (true) {
            Page<ChatSession> page = await(chat.findSessionPage(null, window));
            for (ChatSession session : page.items()) {
                if (!seen.add(session.id())) {
                    duplicates++;
                }
            }
            if (!page.hasMore()) {
                break;
            }
            window = new PageRequest(page.nextOffset(), 10);
        }

        assertEquals(0, duplicates, "a row shown twice means the ordering is not total");
        assertEquals(34, seen.size(), "and every conversation was shown at least once");
    }

    @Test
    void searchesConversationsByTitleAndByWhatWasSaidInThem() {
        ChatSession byTitle = await(chat.createSession("Netherite guide"));
        ChatSession byMessage = await(chat.createSession("Untitled"));
        await(chat.appendMessage(byMessage.id(), ChatRole.USER, "how do I find netherite"));
        await(chat.createSession("Something else"));

        Page<ChatSession> page = await(chat.findSessionPage("netherite", PageRequest.first(10)));

        assertEquals(2, page.total());
        assertEquals(Set.of(byTitle.id(), byMessage.id()),
                page.items().stream().map(ChatSession::id).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void countsAConversationOnceHoweverManyMessagesMatch() {
        // The search joins messages, so without DISTINCT a conversation with five matching
        // messages would be five rows and the total would be nonsense.
        ChatSession session = await(chat.createSession("Repeats"));
        for (int i = 0; i < 5; i++) {
            await(chat.appendMessage(session.id(), ChatRole.USER, "netherite question " + i));
        }

        Page<ChatSession> page = await(chat.findSessionPage("netherite", PageRequest.first(10)));

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
    }

    @Test
    void treatsABlankSearchAsNoSearch() {
        await(chat.createSession("Only one"));

        assertEquals(1, await(chat.findSessionPage(null, PageRequest.first(10))).total());
        assertEquals(1, await(chat.findSessionPage("   ", PageRequest.first(10))).total());
    }

    // --------------------------------------------------------- page request

    @Test
    void clampsAWindowThatCameFromAQueryString() {
        // These numbers arrive from a URL, so they are whatever someone typed. Clamping
        // means no request can read the whole table however it asks.
        assertEquals(0, new PageRequest(-50, 20).offset());
        assertEquals(1, new PageRequest(0, 0).limit());
        assertEquals(1, new PageRequest(0, -10).limit());
        assertEquals(PageRequest.MAX_LIMIT, new PageRequest(0, 100_000).limit());
    }

    @Test
    void readsPastTheEndAsAnEmptyWindowRatherThanAFailure() {
        givenCategory(1, "Guides");
        givenPacks(1, 3);

        Page<KnowledgeSummary> page = await(contents.findSummaryPage(ContentQuery.all(),
                new PageRequest(500, 20)));

        assertTrue(page.items().isEmpty());
        assertEquals(3, page.total(), "the total still describes the query, not the window");
        assertFalse(page.hasMore());
    }

    // -------------------------------------------------------------- helpers

    private void givenCategory(long id, String name) {
        await(contents.saveCategory(id, name));
    }

    private void givenPacks(long categoryId, int count) {
        for (int i = 0; i < count; i++) {
            // Zero-padded so that the title order and the numeric order agree, and a
            // failure reads as "pack-07 came after pack-10" rather than as noise.
            String id = String.format("pack-%02d", i);
            await(contents.save(pack(id, categoryId, "Pack " + id, false)));
        }
    }

    private static KnowledgeContent pack(String id, long categoryId, String title, boolean premium) {
        return new KnowledgeContent(id, categoryId, "1.0.0", premium, title,
                "The body of " + title, "[0.1,0.2]", Instant.now(), Instant.now());
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
