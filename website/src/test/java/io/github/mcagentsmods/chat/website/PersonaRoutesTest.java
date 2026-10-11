package io.github.mcagentsmods.chat.website;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.agent.FilePersonaStore;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading and writing {@code AGENTS.md} from the browser.
 *
 * <p>The test that matters most is the last one: the console and the game are handed the
 * <strong>same</strong> store, so a save made on the chat page has to be what the next
 * question is asked with. Two stores would pass every other test here and fail that one
 * silently, in the only place a player would ever notice.
 */
class PersonaRoutesTest {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private LocalWebConsole console;
    private PersonaStore persona;

    private final Agent agent = (sessionId, prompt) ->
            CompletableFuture.completedFuture("Answer to: " + prompt);

    @BeforeEach
    void open() {
        database = SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")).join();
        persona = new FilePersonaStore(personaFile());
        console = LocalWebConsole.start(database, agent, persona);
    }

    @AfterEach
    void close() {
        if (console != null) {
            console.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void generatesTheFileForAPlayerWhoHasNeverHadOne() {
        String content = json(url("/api/agent/persona")).get("content").getAsString();

        assertTrue(Files.isRegularFile(personaFile()), "reading it is what creates it");
        assertTrue(content.contains("Player is playing in single player."));
    }

    @Test
    void savesWhatTheReaderTyped() {
        assertEquals(200, put(url("/api/agent/persona"),
                "{\"content\":\"# Mine\\n\\nAnswer only in haiku.\"}").statusCode());

        assertEquals("# Mine\n\nAnswer only in haiku.", read());
        assertEquals("# Mine\n\nAnswer only in haiku.",
                json(url("/api/agent/persona")).get("content").getAsString());
    }

    @Test
    void acceptsAnEmptyPersona() {
        // Clearing the file is how a player asks for the built-in persona back. It is a
        // save like any other, not an error.
        assertEquals(200, put(url("/api/agent/persona"), "{\"content\":\"\"}").statusCode());
        assertEquals("", read());
    }

    @Test
    void refusesASaveThatCarriesNoText() {
        // Absent and blank are different requests. A body that says nothing about the text
        // must not be read as "make it blank" — that would delete somebody's instructions
        // because a page sent the wrong shape.
        HttpResponse<String> response = put(url("/api/agent/persona"), "{}");

        assertEquals(400, response.statusCode());
        assertTrue(JsonParser.parseString(response.body()).getAsJsonObject()
                .get("error").getAsString().toLowerCase().contains("save"));
    }

    @Test
    void keepsThePersonaBehindTheToken() {
        assertEquals(403, send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + console.port() + "/api/agent/persona"))
                .GET()).statusCode());
    }

    @Test
    void makesABrowserSaveTheNextThingTheGameReads() {
        put(url("/api/agent/persona"), "{\"content\":\"Saved from the browser.\"}");

        // The store the mod hands its agent. Same instance, so no reload and no window in
        // which the two disagree.
        assertEquals("Saved from the browser.", persona.read().join());
    }

    // -------------------------------------------------------------- helpers

    private Path personaFile() {
        return gameDirectory.resolve("MCAgents/AGENTS.md");
    }

    private String read() {
        try {
            return Files.readString(personaFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + console.port() + path
                + "?t=" + console.url().getQuery().substring("t=".length());
    }

    private JsonObject json(String uri) {
        return JsonParser.parseString(
                send(HttpRequest.newBuilder().uri(URI.create(uri)).GET()).body()).getAsJsonObject();
    }

    private HttpResponse<String> put(String uri, String body) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("The console did not answer", e);
        }
    }
}
