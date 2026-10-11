package io.github.mcagentsmods.chat.website;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.agent.StubTransport;
import io.github.mcagentsmods.chat.common.service.CentralService;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browsing and downloading packs the player does not have.
 *
 * <p>Before this there was no way to reach a pack that had not been downloaded, which is
 * why the detail page had no download button: every page you could open was about a pack
 * you already had.
 */
class CatalogRoutesTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private StubTransport transport;
    private LocalWebConsole console;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        transport = new StubTransport();
        console = LocalWebConsole.start(database,
                (sessionId, prompt) -> CompletableFuture.completedFuture("answer"),
                new CentralService(transport, "https://example.invalid"));
    }

    @AfterEach
    void close() {
        console.close();
        database.close();
    }

    @Test
    void listsPublishedPacksThisMachineDoesNotHave() {
        store("have-it", "1.0.0");
        transport.reply(200, catalogOf("have-it", "not-yet"));

        JsonObject body = json("/api/catalog");

        assertEquals(1, body.get("total").getAsInt());
        assertEquals("not-yet",
                body.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString(),
                "a pack already stored belongs in the library list, not in what is available");
        assertEquals(2, body.get("published").getAsInt());
    }

    @Test
    void namesAnOutOfDatePackWithoutListingItAsAvailable() {
        // It is already in the library list, where the player will look for it. Listing it
        // here as well would show one pack twice under two different headings — but the
        // library needs to know *which* pack, or a page that knows one is behind leaves the
        // reader to open every pack in turn to find it.
        store("stale", "1.0.0");
        transport.reply(200, catalogOf(entry("stale", "2.0.0")));

        JsonObject body = json("/api/catalog");

        assertEquals(0, body.get("total").getAsInt());
        assertEquals(1, body.get("outdated").getAsInt());

        JsonObject behind = body.getAsJsonArray("outdatedItems").get(0).getAsJsonObject();
        assertEquals("stale", behind.get("id").getAsString());
        assertEquals("1.0.0", behind.get("storedVersion").getAsString());
        assertEquals("2.0.0", behind.get("publishedVersion").getAsString());
    }

    @Test
    void namesNothingWhenEveryStoredPackIsCurrent() {
        store("current", "1.0.0");
        transport.reply(200, catalogOf(entry("current", "1.0.0")));

        JsonObject body = json("/api/catalog");

        assertEquals(0, body.getAsJsonArray("outdatedItems").size());
        assertEquals(0, body.get("outdated").getAsInt());
    }

    @Test
    void opensAPageForAPackThatHasNotBeenDownloaded() {
        transport.reply(200, fullPack("not-yet", "1.0.0"));

        JsonObject body = json("/api/knowledge/not-yet");

        assertFalse(body.get("stored").getAsBoolean(),
                "the page needs to know it is looking at the service, not the library");
        assertEquals("Body of not-yet", body.get("rawContent").getAsString());
    }

    @Test
    void marksAStoredPackAsStored() {
        store("have-it", "1.0.0");

        assertTrue(json("/api/knowledge/have-it").get("stored").getAsBoolean());
        assertEquals(0, transport.callCount(),
                "a pack that is stored is answered from disk; the service is not asked");
    }

    @Test
    void saysSoWhenAPackIsNeitherStoredNorReachable() {
        // "No such pack" would be a lie: it may exist perfectly well on a service that is
        // merely asleep, which is the ordinary state of a host that idles.
        transport.fail(new IOException("connection refused"));

        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/knowledge/unknown"))
                .header("X-MCAgents-Token", token()));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("not answering"), response.body());
    }

    @Test
    void downloadsOnePackOnRequest() {
        transport
                .reply(200, "{\"items\":[{\"id\":1,\"displayName\":\"Guides\","
                        + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}]}")
                .reply(200, fullPack("wanted", "1.0.0"));

        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/knowledge/wanted/download"))
                .header("X-MCAgents-Token", token())
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(await(database.contents().findById("wanted")).isPresent(),
                "the pack is on this machine now, which is the whole point of the button");
    }

    @Test
    void reportsWhatARefreshWouldFetch() {
        store("have-it", "1.0.0");
        transport.reply(200, catalogOf("have-it", "not-yet"));

        JsonObject body = json("/api/service/updates");

        assertEquals(1, body.get("available").getAsInt());
        assertEquals(1, body.get("missing").getAsInt());
        assertEquals(0, body.get("outdated").getAsInt());
    }

    @Test
    void deletesAStoredPack() {
        store("unwanted", "1.0.0");

        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/knowledge/unwanted"))
                .header("X-MCAgents-Token", token())
                .DELETE());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(await(database.contents().findById("unwanted")).isEmpty());
        assertEquals(0, transport.callCount(),
                "deleting is local; nothing is unpublished and the service is not told");
    }

    @Test
    void reportsAPackThatCannotBeDeletedBecauseItIsNotThere() {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/knowledge/never-had-it"))
                .header("X-MCAgents-Token", token())
                .DELETE());

        assertEquals(404, response.statusCode());
    }

    @Test
    void checksOnePackForAnUpdateWithoutListingTheRest() {
        store("watched", "1.0.0");
        transport.reply(200, fullPack("watched", "2.0.0"));

        JsonObject body = json("/api/knowledge/watched/update");

        assertTrue(body.get("updateAvailable").getAsBoolean());
        assertEquals("1.0.0", body.get("storedVersion").getAsString());
        assertEquals("2.0.0", body.get("publishedVersion").getAsString());
        assertEquals(1, transport.callCount(),
                "one request, for one pack — a reader looking at one pack did not ask about "
                        + "every other one");
    }

    @Test
    void saysWhenOnePackIsAlreadyCurrent() {
        store("watched", "1.0.0");
        transport.reply(200, fullPack("watched", "1.0.0"));

        JsonObject body = json("/api/knowledge/watched/update");

        assertFalse(body.get("updateAvailable").getAsBoolean());
        assertTrue(body.get("stored").getAsBoolean());
    }

    // ------------------------------------------------------------- plumbing

    private void store(String id, String version) {
        await(database.contents().saveCategory(1, "Guides"));
        await(database.contents().save(new KnowledgeContent(id, 1, version, false,
                "Stored " + id, "Body", "[0.1]", Instant.now(), Instant.now())));
    }

    private static String entry(String id, String version) {
        return "{\"id\":\"" + id + "\",\"categoryId\":1,\"version\":\"" + version + "\","
                + "\"premium\":false,\"title\":\"Pack " + id + "\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}";
    }

    private static String catalogOf(String... ids) {
        StringBuilder items = new StringBuilder();
        for (String id : ids) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append(id.startsWith("{") ? id : entry(id, "1.0.0"));
        }
        return "{\"items\":[" + items + "],\"hasMore\":false}";
    }

    private static String fullPack(String id, String version) {
        return "{\"id\":\"" + id + "\",\"categoryId\":1,\"version\":\"" + version + "\","
                + "\"premium\":false,\"title\":\"Pack " + id + "\","
                + "\"rawContent\":\"Body of " + id + "\",\"embeddedData\":\"\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}";
    }

    private String base() {
        return "http://127.0.0.1:" + console.port();
    }

    private String token() {
        return console.url().getQuery().replaceAll(".*\\bt=([0-9a-f]+).*", "$1");
    }

    private JsonObject json(String path) {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("X-MCAgents-Token", token()));
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) {
        return assertDoesNotThrow(() -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
