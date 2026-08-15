package io.github.mcagents.clientfabric.common.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagents.clientfabric.api.agent.Agent;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.ChatSession;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console, driven the way its pages drive it.
 *
 * <p>Two things get the most attention here. The first is the guard — a console that serves
 * a player's conversations has to refuse everything that is not its own page asking. The
 * second is the revision counter, because a sync that works in one direction looks finished
 * from the side that works.
 */
class LocalWebConsoleTest {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private LocalWebConsole console;

    /** Answers instantly, so the tests measure the console rather than a model provider. */
    private final Agent agent = (sessionId, prompt) ->
            CompletableFuture.completedFuture("Answer to: " + prompt);

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        console = LocalWebConsole.start(database, agent);
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

    // ------------------------------------------------------------ security

    @Test
    void refusesEveryRequestWithoutTheToken() {
        assertEquals(403, statusOf(base() + "/api/chats"));
        assertEquals(403, statusOf(base() + "/api/knowledge"));
        assertEquals(403, statusOf(base() + "/api/revision"));
        assertEquals(403, statusOf(base() + "/knowledge/"));
        assertEquals(403, statusOf(base() + "/api/chats?t=guessed"));
    }

    @Test
    void refusesARequestThatArrivedThroughSomeOtherHostname() {
        // A site pointing one of its own names at 127.0.0.1 reaches the console carrying
        // that name. Written onto a socket by hand because HttpClient forbids setting Host —
        // which is itself the point: no well-behaved client sends this.
        String path = "/api/revision?t=" + token();

        assertTrue(rawGet("console-stealer.example", path).startsWith("HTTP/1.1 403"));
        assertTrue(rawGet("127.0.0.1:" + console.port(), path).startsWith("HTTP/1.1 200"),
                "and the same request from the real origin still works");
    }

    @Test
    void neverHandsBackAKeyItStores() {
        // The console manages keys now, so "there is no route" is no longer the defence.
        // This is: whatever a key route returns, the value is not in it. The test uses an
        // obviously fake key, per .agents/security/api-keys.md.
        String secret = "sk-or-v1-neverleavesthemod";
        await(database.apiKeys().add(secret));

        String listing = body(url("/api/keys"));
        assertFalse(listing.contains(secret), "a key's value must never reach the browser");
        assertTrue(listing.contains("sk-o"), "but its mask must, so the player recognises it");

        JsonObject key = JsonParser.parseString(listing).getAsJsonObject()
                .getAsJsonArray("items").get(0).getAsJsonObject();
        assertFalse(key.has("value"));
        assertTrue(key.get("handle").getAsString().length() >= 16,
                "a handle names the key without being it");
        assertNotEquals(secret, key.get("handle").getAsString());
    }

    @Test
    void stillHasNoRouteForAnythingItDoesNotManage() {
        assertEquals(404, statusOf(url("/api/apikeys")));
        assertEquals(404, statusOf(url("/api/settings")));
        assertEquals(404, statusOf(url("/api/wallets")));
    }

    @Test
    void addsAndRemovesAKeyThroughTheHandle() {
        JsonObject added = json(post(url("/api/keys"), "{\"value\":\"sk-or-v1-added-here\"}"));
        assertEquals(1, await(database.apiKeys().findAll()).size());
        assertFalse(added.toString().contains("added-here"),
                "not even the reply to adding one echoes it back");

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/keys/" + added.get("handle").getAsString())))
                .DELETE()).statusCode());
        assertTrue(await(database.apiKeys().findAll()).isEmpty());
    }

    @Test
    void refusesAHandleThatNamesNothing() {
        assertEquals(404, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/keys/" + "0".repeat(32))))
                .DELETE()).statusCode());
    }

    @Test
    void refusesAnEmptyKey() {
        assertEquals(400, post(url("/api/keys"), "{\"value\":\"   \"}").statusCode());
        assertTrue(await(database.apiKeys().findAll()).isEmpty());
    }

    @Test
    void servesKeysAWindowAtATime() {
        for (int i = 0; i < 23; i++) {
            await(database.apiKeys().add("sk-or-v1-fake-" + i));
        }

        JsonObject page = json(url("/api/keys?limit=10"));

        assertEquals(10, page.getAsJsonArray("items").size());
        assertEquals(23, page.get("total").getAsInt());
        assertTrue(page.get("hasMore").getAsBoolean());
    }

    @Test
    void showsEveryKeyExactlyOnceAcrossWindows() {
        for (int i = 0; i < 25; i++) {
            await(database.apiKeys().add("sk-or-v1-fake-" + i));
        }

        java.util.Set<String> handles = new java.util.HashSet<>();
        int offset = 0;
        while (true) {
            JsonObject page = json(url("/api/keys?limit=10&offset=" + offset));
            page.getAsJsonArray("items").forEach(item ->
                    handles.add(item.getAsJsonObject().get("handle").getAsString()));
            if (!page.get("hasMore").getAsBoolean()) {
                break;
            }
            offset = page.get("nextOffset").getAsInt();
        }

        assertEquals(25, handles.size(), "every key appeared, and none of them twice");
    }

    @Test
    void acceptsTheTokenInAHeaderAsWellAsTheUrl() {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/revision"))
                .header("X-MCAgents-Token", token())
                .GET());

        assertEquals(200, response.statusCode());
    }

    @Test
    void givesEveryRunItsOwnToken() {
        LocalWebConsole second = LocalWebConsole.start(database, agent);
        try {
            assertNotEquals(console.url().getQuery(), second.url().getQuery());
        } finally {
            second.close();
        }
    }

    @Test
    void refusesToBeReadFromDiskThroughTheAssetRoute() {
        // Nothing is served from disk, so the worst a traversal could do is read another
        // resource out of the jar. That is still not something to allow.
        assertEquals(404, statusOf(url("/assets/../../../etc/passwd")));
        assertEquals(404, statusOf(url("/assets/..%2f..%2fpom.xml")));
        assertEquals(404, statusOf(url("/assets/")));
    }

    // ----------------------------------------------------------- knowledge

    @Test
    void servesKnowledgeAWindowAtATime() {
        givenCategory(1, "Guides");
        for (int i = 0; i < 25; i++) {
            givenPack(String.format("pack-%02d", i), 1, "Pack " + i);
        }

        JsonObject first = json(url("/api/knowledge?limit=20"));

        assertEquals(20, first.getAsJsonArray("items").size());
        assertEquals(25, first.get("total").getAsInt());
        assertTrue(first.get("hasMore").getAsBoolean());

        JsonObject second = json(url("/api/knowledge?limit=20&offset="
                + first.get("nextOffset").getAsInt()));

        assertEquals(5, second.getAsJsonArray("items").size());
        assertFalse(second.get("hasMore").getAsBoolean());
    }

    @Test
    void leavesPackBodiesOutOfTheList() {
        // The list route must not become a way to download every embedding at once.
        givenCategory(1, "Guides");
        givenPack("only", 1, "Only pack");

        JsonObject item = json(url("/api/knowledge")).getAsJsonArray("items").get(0)
                .getAsJsonObject();

        assertFalse(item.has("rawContent"), "a list row carries no body");
        assertFalse(item.has("embeddedData"), "and no embedding");
        assertTrue(item.get("hasEmbedding").getAsBoolean());
    }

    @Test
    void servesOnePackInFullOnTheDetailRoute() {
        givenCategory(1, "Guides");
        givenPack("netherite", 1, "Netherite guide");

        JsonObject pack = json(url("/api/knowledge/netherite"));

        assertEquals("netherite", pack.get("id").getAsString());
        assertEquals("1.0.0", pack.get("version").getAsString());
        assertEquals("The body of Netherite guide", pack.get("rawContent").getAsString());
        assertEquals("[0.1,0.2]", pack.get("embeddedData").getAsString());
    }

    @Test
    void searchesAndFiltersTheKnowledgeList() {
        givenCategory(1, "Guides");
        givenCategory(2, "Strategies");
        givenPack("redstone", 1, "Redstone basics");
        givenPack("farming", 2, "Farming");

        assertEquals(1, json(url("/api/knowledge?q=redstone")).get("total").getAsInt());
        assertEquals(1, json(url("/api/knowledge?categoryId=2")).get("total").getAsInt());
        assertEquals(2, json(url("/api/categories")).getAsJsonArray("items").size());
    }

    @Test
    void reportsAMissingPackRatherThanFailing() {
        assertEquals(404, statusOf(url("/api/knowledge/no-such-pack")));
    }

    // ---------------------------------------------------------------- chat

    @Test
    void holdsAConversationTheSameWayTheGameDoes() {
        JsonObject created = json(post(url("/api/chats"), "{\"title\":\"From the browser\"}"));
        String id = created.get("id").getAsString();

        JsonObject exchange = json(post(url("/api/chats/" + id + "/messages"),
                "{\"prompt\":\"how do I find netherite\"}"));

        assertEquals("user", exchange.getAsJsonObject("question").get("role").getAsString());
        assertEquals("assistant", exchange.getAsJsonObject("answer").get("role").getAsString());
        assertEquals("Answer to: how do I find netherite",
                exchange.getAsJsonObject("answer").get("content").getAsString());

        // Stored, not just returned — the same conversation the game will show.
        assertEquals(2, await(database.chat().findMessages(id)).size());
    }

    @Test
    void servesConversationsAWindowAtATime() {
        for (int i = 0; i < 23; i++) {
            await(database.chat().createSession("Chat " + i));
        }

        JsonObject page = json(url("/api/chats?limit=10"));

        assertEquals(10, page.getAsJsonArray("items").size());
        assertEquals(23, page.get("total").getAsInt());
        assertTrue(page.get("hasMore").getAsBoolean());
    }

    @Test
    void searchesConversationsByWhatWasSaidInThem() {
        ChatSession session = await(database.chat().createSession("Untitled"));
        await(database.chat().appendMessage(session.id(), ChatRole.USER, "netherite please"));
        await(database.chat().createSession("Something else"));

        assertEquals(1, json(url("/api/chats?q=netherite")).get("total").getAsInt());
    }

    @Test
    void renamesAndDeletesAConversation() {
        ChatSession session = await(database.chat().createSession("Before"));

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chats/" + session.id())))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"title\":\"After\"}"))).statusCode());
        assertEquals("After", await(database.chat().findSession(session.id())).orElseThrow().title());

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chats/" + session.id())))
                .DELETE()).statusCode());
        assertTrue(await(database.chat().findSession(session.id())).isEmpty());
    }

    @Test
    void refusesAnEmptyPromptWithSomethingWorthReading() {
        ChatSession session = await(database.chat().createSession("Empty"));

        HttpResponse<String> response =
                post(url("/api/chats/" + session.id() + "/messages"), "{\"prompt\":\"   \"}");

        assertEquals(400, response.statusCode());
        assertTrue(JsonParser.parseString(response.body()).getAsJsonObject()
                .get("error").getAsString().toLowerCase().contains("message"));
    }

    @Test
    void reportsAMissingConversationRatherThanCreatingOne() {
        assertEquals(404, post(url("/api/chats/no-such-session/messages"),
                "{\"prompt\":\"hello\"}").statusCode());
        assertEquals(404, statusOf(url("/api/chats/no-such-session/messages")));
    }

    // ------------------------------------------------------------ the sync

    @Test
    void reportsAChangeMadeInTheGameSoTheBrowserCanNotice() {
        long before = json(url("/api/revision")).get("chat").getAsLong();

        // A write through the repository, which is what the in-game screen does.
        await(database.chat().createSession("Typed in game"));

        assertNotEquals(before, json(url("/api/revision")).get("chat").getAsLong(),
                "a conversation started in game must move the counter the browser polls");
    }

    @Test
    void reportsAChangeMadeInTheBrowserSoTheGameCanNotice() {
        long before = database.changes().chatRevision();

        json(post(url("/api/chats"), "{\"title\":\"Typed in the browser\"}"));

        assertNotEquals(before, database.changes().chatRevision(),
                "a conversation started in the browser must move the counter the game reads");
    }

    @Test
    void movesTheCounterForEveryKindOfChange() {
        ChatSession session = await(database.chat().createSession("Watched"));

        long afterCreate = database.changes().chatRevision();
        await(database.chat().appendMessage(session.id(), ChatRole.USER, "hello"));
        long afterMessage = database.changes().chatRevision();
        await(database.chat().renameSession(session.id(), "Renamed"));
        long afterRename = database.changes().chatRevision();
        await(database.chat().deleteSession(session.id()));
        long afterDelete = database.changes().chatRevision();

        assertNotEquals(afterCreate, afterMessage);
        assertNotEquals(afterMessage, afterRename);
        assertNotEquals(afterRename, afterDelete);
    }

    @Test
    void leavesTheCounterAloneWhenNothingChanged() {
        // Renaming a conversation that is not there changes nothing, and telling every
        // reader to reload for it would cost a database read per client tick.
        long before = database.changes().chatRevision();

        await(database.chat().renameSession("no-such-session", "Ghost"));
        await(database.chat().deleteSession("no-such-session"));

        assertEquals(before, database.changes().chatRevision());
    }

    // ----------------------------------------------------------- lifecycle

    @Test
    void keepsListeningUntilItIsClosed() {
        // The opposite of the wallet bridge, on purpose: a browser tab the player is
        // reading must not have its server vanish underneath it.
        assertEquals(200, statusOf(url("/api/revision")));
        assertEquals(200, statusOf(url("/api/revision")));
        assertTrue(console.isRunning());

        console.close();

        assertFalse(console.isRunning());
        assertTrue(console.stopped().isDone());
        assertThrows(IOException.class, () -> connectTo(console.port()));
    }

    @Test
    void closingTwiceIsHarmless() {
        console.close();
        console.close();

        assertFalse(console.isRunning());
    }

    @Test
    void sendsTheRootOnToTheKnowledgePageCarryingTheToken() {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/"))).GET());

        assertEquals(303, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElseThrow()
                .startsWith("/knowledge/?t="));
    }

    // -------------------------------------------------------------- helpers

    private String base() {
        return "http://127.0.0.1:" + console.port();
    }

    private String token() {
        return console.url().getQuery().substring("t=".length());
    }

    private String url(String path) {
        return base() + path + (path.contains("?") ? "&" : "?") + "t=" + token();
    }

    private void givenCategory(long id, String name) {
        await(database.contents().saveCategory(id, name));
    }

    private void givenPack(String id, long categoryId, String title) {
        await(database.contents().save(new KnowledgeContent(id, categoryId, "1.0.0", false,
                title, "The body of " + title, "[0.1,0.2]", Instant.now(), Instant.now())));
    }

    private JsonObject json(String uri) {
        return JsonParser.parseString(
                send(HttpRequest.newBuilder().uri(URI.create(uri)).GET()).body()).getAsJsonObject();
    }

    private JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private HttpResponse<String> post(String uri, String body) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private int statusOf(String uri) {
        return send(HttpRequest.newBuilder().uri(URI.create(uri)).GET()).statusCode();
    }

    private String body(String uri) {
        return send(HttpRequest.newBuilder().uri(URI.create(uri)).GET()).body();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("The console did not answer", e);
        }
    }

    private String rawGet(String hostHeader, String path) {
        try (Socket socket = new Socket("127.0.0.1", console.port())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The console did not answer a hand-written request", e);
        }
    }

    private static void connectTo(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
