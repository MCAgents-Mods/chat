package io.github.mcagentsmods.chat.website;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routes that read, show and remove a pack on this machine.
 *
 * <p>Every pack the console shows is one that is already stored: there is no source to browse
 * and nothing to download, so a pack that is not in the library does not exist as far as these
 * routes are concerned.
 */
class PackRoutesTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ConsoleUnderTest console;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        console = ConsoleUnderTest.start(database,
                (sessionId, prompt) -> CompletableFuture.completedFuture("answer"));
    }

    @AfterEach
    void close() {
        console.close();
        database.close();
    }

    @Test
    void showsAStoredPackInFull() {
        store("have-it", "1.0.0");

        JsonObject body = json("/api/chat/knowledge/have-it");

        assertEquals("have-it", body.get("id").getAsString());
        assertEquals("1.0.0", body.get("version").getAsString());
    }

    @Test
    void answersNotFoundForAPackThatIsNotStored() {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/chat/knowledge/unknown"))
                .header("X-MCAgents-Token", token()));

        assertEquals(404, response.statusCode());
    }

    @Test
    void deletesAStoredPack() {
        store("unwanted", "1.0.0");

        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/chat/knowledge/unwanted"))
                .header("X-MCAgents-Token", token())
                .DELETE());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(await(database.contents().findById("unwanted")).isEmpty());
    }

    @Test
    void reportsAPackThatCannotBeDeletedBecauseItIsNotThere() {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/chat/knowledge/never-had-it"))
                .header("X-MCAgents-Token", token())
                .DELETE());

        assertEquals(404, response.statusCode());
    }

    @Test
    void hasNoRouteForTheRemovedCatalogOrDownloads() {
        for (String path : new String[] {"/api/chat/catalog", "/api/chat/service/health",
                "/api/chat/service/updates"}) {
            assertEquals(404, send(HttpRequest.newBuilder()
                    .uri(URI.create(base() + path))
                    .header("X-MCAgents-Token", token())).statusCode(), path);
        }
    }

    // ------------------------------------------------------------- plumbing

    private void store(String id, String version) {
        await(database.contents().saveCategory(1, "Guides"));
        await(database.contents().save(new KnowledgeContent(id, 1, version, false,
                "Stored " + id, "Body", "[0.1]", Instant.now(), Instant.now())));
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
