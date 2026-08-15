package io.github.mcagents.clientfabric.common.service;

import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.agent.StubTransport;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bringing the local library into step with the service.
 *
 * <p>Driven through a stub transport, so none of this needs a network or a running service.
 * The behaviour worth pinning is what happens when the service is <em>not</em> there, since
 * that is the state the mod is designed around.
 */
class KnowledgeSyncTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private StubTransport transport;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        transport = new StubTransport();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private KnowledgeSync sync() {
        return new KnowledgeSync(new CentralService(transport, "https://example.invalid"), database);
    }

    // ------------------------------------------------------------- health

    @Test
    void reportsAServiceThatAnswers() {
        transport.reply(200, "{\"status\":\"ok\",\"version\":\"0.0.0\"}");

        ServiceHealth health = await(new CentralService(transport, "https://example.invalid").health());

        assertTrue(health.reachable());
        assertEquals("0.0.0", health.version());
    }

    @Test
    void treatsASleepingServiceAsNotReachableRatherThanAsAFailure() {
        // The service is hosted somewhere that sleeps when idle, so the first answer after a
        // quiet night is a refused connection. Turning that into an exception would make the
        // ordinary case look like a fault and force every caller to catch it.
        transport.fail(new IOException("connection refused"));

        ServiceHealth health = await(new CentralService(transport, "https://example.invalid").health());

        assertFalse(health.reachable());
        assertFalse(health.detail().isBlank(), "a player is watching a button; say something");
    }

    @Test
    void treatsAnErrorStatusAsNotReachableToo() {
        transport.reply(502, "<html>bad gateway</html>");

        assertFalse(await(new CentralService(transport, "https://example.invalid").health()).reachable());
    }

    // ------------------------------------------------------------ refresh

    @Test
    void downloadsOnlyWhatIsMissingOrOutOfDate() {
        await(database.contents().saveCategory(1, "Guides"));
        await(database.contents().save(pack("current", "1.0.0")));
        await(database.contents().save(pack("stale", "1.0.0")));

        transport
                .reply(200, categories())
                .reply(200, """
                        {"items":[
                          {"id":"current","version":"1.0.0"},
                          {"id":"stale","version":"2.0.0"},
                          {"id":"brand-new","version":"1.0.0"}
                        ],"hasMore":false,"nextOffset":3}
                        """)
                .reply(200, fullPack("brand-new", "1.0.0"))
                .reply(200, fullPack("stale", "2.0.0"));

        KnowledgeSync.SyncResult result = await(sync().refresh());

        assertEquals(2, result.downloaded(), "the up-to-date pack must not be fetched again");
        assertEquals(0, result.failed());
        assertEquals(3, result.published());
        assertEquals("2.0.0", await(database.contents().findById("stale")).orElseThrow().version());
    }

    @Test
    void doesNothingTheSecondTime() {
        // What an impatient player produces: refresh, nothing seems to happen, refresh again.
        await(database.contents().saveCategory(1, "Guides"));
        await(database.contents().save(pack("only", "1.0.0")));

        transport
                .reply(200, categories())
                .reply(200, "{\"items\":[{\"id\":\"only\",\"version\":\"1.0.0\"}],\"hasMore\":false}");

        KnowledgeSync.SyncResult result = await(sync().refresh());

        assertEquals(0, result.downloaded());
        assertFalse(result.changedAnything());
    }

    @Test
    void readsEveryPageOfTheCatalog() {
        // A catalog larger than one page would otherwise look like a catalog that had lost
        // most of its packs, and everything after the first page would never be downloaded.
        await(database.contents().saveCategory(1, "Guides"));

        transport
                .reply(200, categories())
                .reply(200, "{\"items\":[{\"id\":\"a\",\"version\":\"1.0.0\"}],\"hasMore\":true,\"nextOffset\":100}")
                .reply(200, "{\"items\":[{\"id\":\"b\",\"version\":\"1.0.0\"}],\"hasMore\":false,\"nextOffset\":200}")
                .reply(200, fullPack("a", "1.0.0"))
                .reply(200, fullPack("b", "1.0.0"));

        KnowledgeSync.SyncResult result = await(sync().refresh());

        assertEquals(2, result.downloaded());
        assertEquals(2, result.published());
    }

    @Test
    void keepsGoingWhenOnePackFails() {
        // One pack failing must not cost the others. Nothing half-written is stored: a pack
        // that did not arrive is simply not saved.
        await(database.contents().saveCategory(1, "Guides"));

        transport
                .reply(200, categories())
                .reply(200, """
                        {"items":[{"id":"good","version":"1.0.0"},{"id":"broken","version":"1.0.0"}],
                         "hasMore":false}
                        """)
                .reply(200, fullPack("broken", "1.0.0").replace("{", "{{"))
                .reply(200, fullPack("good", "1.0.0"));

        KnowledgeSync.SyncResult result = await(sync().refresh());

        assertEquals(1, result.downloaded());
        assertEquals(1, result.failed());
        assertEquals(java.util.List.of("broken"), result.failedIds());
        assertTrue(await(database.contents().findById("good")).isPresent());
        assertTrue(await(database.contents().findById("broken")).isEmpty());
    }

    @Test
    void explainsARateLimitInTermsOfWhatToDo() {
        // The one failure a player will actually meet, because it is what pressing refresh
        // repeatedly produces.
        transport.reply(429, """
                {"error":{"code":"rate_limited","message":"Too many requests.",
                 "details":{"retryAfterSeconds":42}}}
                """);

        Exception failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class, () -> sync().refresh().join());

        assertTrue(failure.getCause() instanceof CentralServiceException);
        assertTrue(failure.getCause().getMessage().contains("42"),
                "tell the player how long to wait rather than reporting a status code");
    }

    // ----------------------------------------------------- base knowledge

    @Test
    void fetchesTheBasePackIntoACompletelyEmptyStore() {
        // The first run of a fresh install: nothing stored, no categories, no packs. This
        // is the case that broke — contents.category_id is a foreign key, so saving the
        // pack before its category exists fails, and because the whole call is best-effort
        // it failed silently on exactly the run that mattered most.
        transport
                .reply(200, fullPack(MCAgents.BASE_KNOWLEDGE_ID, "0.0.0"))
                .reply(200, categories());

        assertTrue(await(sync().ensureBaseKnowledge()));
        assertTrue(await(database.contents().findById(MCAgents.BASE_KNOWLEDGE_ID)).isPresent());
    }

    @Test
    void leavesTheBasePackAloneWhenItIsAlreadyCurrent() {
        await(database.contents().saveCategory(1, "Guides"));
        await(database.contents().save(pack(MCAgents.BASE_KNOWLEDGE_ID, "0.0.0")));
        transport.reply(200, fullPack(MCAgents.BASE_KNOWLEDGE_ID, "0.0.0"));

        assertFalse(await(sync().ensureBaseKnowledge()), "nothing to do, and it says so");
    }

    @Test
    void neverFailsStartupWhenTheServiceIsAsleep() {
        // This runs while the game is loading. It must be impossible for it to throw into
        // whatever started it, on a first run with no internet or any run before the host
        // has woken up.
        transport.fail(new IOException("connection refused"));

        assertFalse(await(sync().ensureBaseKnowledge()));
        assertTrue(await(database.contents().findById(MCAgents.BASE_KNOWLEDGE_ID)).isEmpty());
    }

    @Test
    void sendsNothingOfThePlayersToTheService() {
        // No key, no prompt, no identifier. The requests say what is installed and nothing
        // about who installed it.
        await(database.contents().saveCategory(1, "Guides"));
        await(database.apiKeys().add("sk-or-v1-must-never-be-sent"));
        transport
                .reply(200, categories())
                .reply(200, "{\"items\":[],\"hasMore\":false}");

        await(sync().refresh());

        transport.calls().forEach(call -> {
            assertFalse(call.body().contains("sk-or-v1"), "no key leaves the mod this way");
            assertFalse(call.headers().toString().contains("sk-or-v1"));
        });
    }

    // ------------------------------------------------------------ helpers

    private static String categories() {
        return "{\"items\":[{\"id\":1,\"displayName\":\"Guides\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}]}";
    }

    private static String fullPack(String id, String version) {
        return "{\"id\":\"" + id + "\",\"categoryId\":1,\"version\":\"" + version + "\","
                + "\"premium\":false,\"title\":\"Pack " + id + "\","
                + "\"rawContent\":\"Body of " + id + "\",\"embeddedData\":\"[0.1]\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}";
    }

    private static KnowledgeContent pack(String id, String version) {
        return new KnowledgeContent(id, 1, version, false, "Stored " + id,
                "Body", "[0.1]", Instant.now(), Instant.now());
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
