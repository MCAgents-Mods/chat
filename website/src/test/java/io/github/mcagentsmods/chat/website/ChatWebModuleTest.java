package io.github.mcagentsmods.chat.website;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chat's pages and routes, driven the way its pages drive them, through core's console.
 *
 * <p>Two things get the most attention here. The first is the guard — a console that serves
 * a player's conversations has to refuse everything that is not its own page asking. The
 * second is the revision counter, because a sync that works in one direction looks finished
 * from the side that works.
 */
class ChatWebModuleTest {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ConsoleUnderTest console;

    /** Answers instantly, so the tests measure the console rather than a model provider. */
    private final Agent agent = (sessionId, prompt) ->
            CompletableFuture.completedFuture("Answer to: " + prompt);

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        console = ConsoleUnderTest.start(database, agent);
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
        assertEquals(403, statusOf(base() + "/api/chat/chats"));
        assertEquals(403, statusOf(base() + "/api/chat/knowledge"));
        assertEquals(403, statusOf(base() + "/api/chat/revision"));
        assertEquals(403, statusOf(base() + "/chat/knowledge/"));
        assertEquals(403, statusOf(base() + "/api/chat/chats?t=guessed"));
    }

    @Test
    void refusesARequestThatArrivedThroughSomeOtherHostname() {
        // A site pointing one of its own names at 127.0.0.1 reaches the console carrying
        // that name. Written onto a socket by hand because HttpClient forbids setting Host —
        // which is itself the point: no well-behaved client sends this.
        String path = "/api/chat/revision?t=" + token();

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

        String listing = body(url("/api/chat/keys"));
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
        assertEquals(404, statusOf(url("/api/chat/apikeys")));
        assertEquals(404, statusOf(url("/api/chat/settings")));
        assertEquals(404, statusOf(url("/api/chat/accounts")));
    }

    @Test
    void addsAndRemovesAKeyThroughTheHandle() {
        JsonObject added = json(post(url("/api/chat/keys"), "{\"value\":\"sk-or-v1-added-here\"}"));
        assertEquals(1, await(database.apiKeys().findAll()).size());
        assertFalse(added.toString().contains("added-here"),
                "not even the reply to adding one echoes it back");

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/keys/" + added.get("handle").getAsString())))
                .DELETE()).statusCode());
        assertTrue(await(database.apiKeys().findAll()).isEmpty());
    }

    @Test
    void refusesAHandleThatNamesNothing() {
        assertEquals(404, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/keys/" + "0".repeat(32))))
                .DELETE()).statusCode());
    }

    @Test
    void refusesAnEmptyKey() {
        assertEquals(400, post(url("/api/chat/keys"), "{\"value\":\"   \"}").statusCode());
        assertTrue(await(database.apiKeys().findAll()).isEmpty());
    }

    @Test
    void storesThePlatformAKeyWasAddedFor() {
        assertEquals(201, post(url("/api/chat/keys"),
                "{\"value\":\"sk-openai-added-here\",\"platform\":\"OpenAI\"}").statusCode());

        assertEquals("openai", await(database.apiKeys().findAll()).get(0).platform(),
                "lowercased before the write, like every other half of a model name");
        assertEquals("openai", JsonParser.parseString(body(url("/api/chat/keys")))
                .getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject()
                .get("platform").getAsString(),
                "and shown back, because two masked keys are otherwise indistinguishable");
    }

    @Test
    void treatsAKeyAddedWithNoPlatformAsAGatewayKey() {
        // The fallback an older page, or a player who ignores the box, relies on.
        assertEquals(201, post(url("/api/chat/keys"), "{\"value\":\"sk-or-v1-added-here\"}")
                .statusCode());

        assertEquals("openrouter", await(database.apiKeys().findAll()).get(0).platform());
    }

    @Test
    void saysWhichPlatformsAreReachedDirectlyAndWhichAreNot() {
        await(database.apiKeys().add("sk-openai-first", "openai"));

        JsonObject routes = json(url("/api/chat/routes"));
        JsonObject openai = routeFor(routes, "openai");

        assertTrue(openai.get("direct").getAsBoolean(),
                "an address it ships plus a key the player stored is a direct route");
        assertTrue(openai.get("hasKey").getAsBoolean());
        assertFalse(routeFor(routes, "together").get("direct").getAsBoolean(),
                "a platform with no key stays on the gateway, and the page says so");
        assertTrue(routes.getAsJsonObject("encryption").has("enabled"),
                "the page that takes a key says whether keys are encrypted here");
    }

    @Test
    void switchesTheChatModelToThePlatformTheChatPageChose() {
        assertEquals("deepseek/deepseek-v4-flash",
                json(url("/api/chat/models")).get("chatModel").getAsString(), "the default, to start");

        JsonObject stored = JsonParser.parseString(
                putJson("/api/chat/models", "{\"chatPlatform\":\"openai\"}").body()).getAsJsonObject();

        assertEquals("openai/gpt-4o-mini", stored.get("chatModel").getAsString(),
                "OpenAI does not serve deepseek-v4-flash, so its own suggestion is used");
        assertEquals("openai", stored.get("chatPlatform").getAsString());
        assertEquals("openai/gpt-4o-mini",
                await(database.settings().loadModelPreferences()).chatModel(),
                "and it is stored, so the game reads the same choice");
    }

    @Test
    void leavesTheEmbeddingChoiceAloneWhenOnlyTheProviderChanges() {
        // The chat page sends one field. A handler that read the absent ones as blanks would
        // reset the embedding model and its switch every time a provider was picked.
        assertEquals(200, putJson("/api/chat/models",
                "{\"embeddingModel\":\"qwen/qwen3-embedding-8b\",\"embeddingEnabled\":false}")
                .statusCode());

        assertEquals(200, putJson("/api/chat/models", "{\"chatPlatform\":\"openai\"}").statusCode());

        JsonObject models = json(url("/api/chat/models"));
        assertEquals("qwen/qwen3-embedding-8b", models.get("embeddingModel").getAsString());
        assertFalse(models.get("embeddingEnabled").getAsBoolean());
    }

    @Test
    void letsAnExplicitModelIdOutrankAPlatform() {
        // Both name the chat model, and the id says strictly more than the platform does.
        JsonObject stored = JsonParser.parseString(putJson("/api/chat/models",
                "{\"chatModel\":\"groq/llama-4-70b\",\"chatPlatform\":\"openai\"}").body())
                .getAsJsonObject();

        assertEquals("groq/llama-4-70b", stored.get("chatModel").getAsString());
        assertEquals("groq", stored.get("chatPlatform").getAsString());
    }

    @Test
    void takesAProviderTheModDoesNotShipAnAddressFor() {
        // The selector's "Other…" box. The lists are suggestions, so a platform this release
        // has never heard of is stored exactly as typed and routed through the gateway.
        JsonObject stored = JsonParser.parseString(
                putJson("/api/chat/models", "{\"chatPlatform\":\"Fireworks\"}").body())
                .getAsJsonObject();

        assertEquals("fireworks/deepseek-v4-flash", stored.get("chatModel").getAsString(),
                "lowercased, with the model the player already had carried over");
    }

    @Test
    void offersEveryPlatformTheSelectorHasToShow() {
        // The page picks a platform from this list rather than asking the player to spell one,
        // so a platform they already use has to be on it — otherwise adding a second key for
        // it means typing the name again, under "Other…", and a typo files it somewhere new.
        await(database.apiKeys().add("sk-fireworks-first", "fireworks"));

        var offered = json(url("/api/chat/routes")).getAsJsonArray("suggestedPlatforms").asList()
                .stream().map(element -> element.getAsString()).toList();

        assertTrue(offered.contains("openrouter"), "the gateway, and the default choice");
        assertTrue(offered.contains("openai"), "a platform this release ships an address for");
        assertTrue(offered.contains("deepseek"), "a platform whose models the mod suggests");
        assertTrue(offered.contains("fireworks"),
                "and one the mod has never heard of, because the player holds a key for it");
    }

    @Test
    void storesAndClearsThePlatformsAddress() {
        assertEquals(200, putJson("/api/chat/routes",
                "{\"platform\":\"openai\",\"baseUrl\":\"https://openai.example.test/v1\"}")
                .statusCode());
        assertEquals("https://openai.example.test/v1",
                routeFor(json(url("/api/chat/routes")), "openai").get("baseUrl").getAsString());

        assertEquals(200, putJson("/api/chat/routes",
                "{\"platform\":\"openai\",\"baseUrl\":\"\"}").statusCode());
        assertEquals("https://api.openai.com/v1",
                routeFor(json(url("/api/chat/routes")), "openai").get("baseUrl").getAsString(),
                "clearing it goes back to the address the mod ships");
    }

    @Test
    void refusesToStoreAnAddressAKeyWouldTravelToInTheClear() {
        assertEquals(400, putJson("/api/chat/routes",
                "{\"platform\":\"openai\",\"baseUrl\":\"http://openai.example.test/v1\"}")
                .statusCode());
        assertEquals("", routeFor(json(url("/api/chat/routes")), "openai")
                .get("configuredUrl").getAsString());
    }

    @Test
    void refusesToRepointTheGatewayItself() {
        assertEquals(400, putJson("/api/chat/routes",
                "{\"platform\":\"openrouter\",\"baseUrl\":\"https://elsewhere.example.test\"}")
                .statusCode());
    }

    @Test
    void servesKeysAWindowAtATime() {
        for (int i = 0; i < 23; i++) {
            await(database.apiKeys().add("sk-or-v1-fake-" + i));
        }

        JsonObject page = json(url("/api/chat/keys?limit=10"));

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
            JsonObject page = json(url("/api/chat/keys?limit=10&offset=" + offset));
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
    void readsAndWritesTheModelSelection() {
        JsonObject before = json(url("/api/chat/models"));
        assertEquals("deepseek/deepseek-v4-flash", before.get("chatModel").getAsString());
        assertEquals("openai/text-embedding-3-small", before.get("embeddingModel").getAsString());
        assertTrue(before.getAsJsonArray("suggestedChatModels").size() > 0,
                "the page takes its suggestions from the console, so there is only one list");

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"chatModel\":\"openai/gpt-4o-mini\",\"embeddingModel\":\"\"}")))
                .statusCode());

        JsonObject after = json(url("/api/chat/models"));
        assertEquals("openai/gpt-4o-mini", after.get("chatModel").getAsString());
        assertEquals("openai/text-embedding-3-small", after.get("embeddingModel").getAsString(),
                "a blank field resolves to the default rather than storing nothing");
    }

    @Test
    void readsAndWritesTheEmbeddingSwitch() {
        assertTrue(json(url("/api/chat/models")).get("embeddingEnabled").getAsBoolean(),
                "retrieval is on until the player says otherwise");

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"chatModel\":\"\",\"embeddingModel\":\"\",\"embeddingEnabled\":false}")))
                .statusCode());

        assertFalse(json(url("/api/chat/models")).get("embeddingEnabled").getAsBoolean());
    }

    @Test
    void leavesTheEmbeddingSwitchAloneWhenARequestDoesNotMentionIt() {
        // A page that edits only the model ids must not reset retrieval. Absent is "do not
        // change it" — collapsing absent into false would switch the feature off on every
        // save made by a client that had never heard of the field.
        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"chatModel\":\"\",\"embeddingModel\":\"\",\"embeddingEnabled\":false}")))
                .statusCode());

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"chatModel\":\"openai/gpt-4o-mini\",\"embeddingModel\":\"\"}")))
                .statusCode());

        JsonObject after = json(url("/api/chat/models"));
        assertEquals("openai/gpt-4o-mini", after.get("chatModel").getAsString());
        assertFalse(after.get("embeddingEnabled").getAsBoolean(),
                "the switch survived a save that said nothing about it");
    }

    @Test
    void leavesTheModelsAloneWhenARequestOnlyFlipsTheSwitch() {
        // The chat page's toggle sends nothing but the flag. A handler that read the two
        // absent ids as blanks would reset both models to the defaults every time a player
        // switched retrieval off.
        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"chatModel\":\"openai/gpt-4o-mini\","
                                + "\"embeddingModel\":\"qwen/qwen3-embedding-8b\"}")))
                .statusCode());

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"embeddingEnabled\":false}")))
                .statusCode());

        JsonObject after = json(url("/api/chat/models"));
        assertEquals("openai/gpt-4o-mini", after.get("chatModel").getAsString());
        assertEquals("qwen/qwen3-embedding-8b", after.get("embeddingModel").getAsString());
        assertFalse(after.get("embeddingEnabled").getAsBoolean());
    }

    @Test
    void stillTreatsAClearedBoxAsARequestForTheDefault() {
        // Blank must keep meaning "give me the default" — it is how a player resets by hand,
        // and it is the one case that must not be folded into "said nothing about it".
        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"chatModel\":\"openai/gpt-4o-mini\",\"embeddingModel\":\"\"}")))
                .statusCode());

        assertEquals("openai/text-embedding-3-small",
                json(url("/api/chat/models")).get("embeddingModel").getAsString());
    }

    @Test
    void acceptsAModelIdItHasNeverHeardOf() {
        // The lists are suggestions. A provider adds and retires models faster than the mod
        // ships, so refusing an unfamiliar id would break a player who is right.
        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/models")))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"chatModel\":\"someone/brand-new-model\",\"embeddingModel\":\"\"}")))
                .statusCode());

        assertEquals("someone/brand-new-model",
                json(url("/api/chat/models")).get("chatModel").getAsString());
    }

    @Test
    void acceptsTheTokenInAHeaderAsWellAsTheUrl() {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/chat/revision"))
                .header("X-MCAgents-Token", token())
                .GET());

        assertEquals(200, response.statusCode());
    }

    // ----------------------------------------------------------- knowledge

    @Test
    void servesKnowledgeAWindowAtATime() {
        givenCategory(1, "Guides");
        for (int i = 0; i < 25; i++) {
            givenPack(String.format("pack-%02d", i), 1, "Pack " + i);
        }

        JsonObject first = json(url("/api/chat/knowledge?limit=20"));

        assertEquals(20, first.getAsJsonArray("items").size());
        assertEquals(25, first.get("total").getAsInt());
        assertTrue(first.get("hasMore").getAsBoolean());

        JsonObject second = json(url("/api/chat/knowledge?limit=20&offset="
                + first.get("nextOffset").getAsInt()));

        assertEquals(5, second.getAsJsonArray("items").size());
        assertFalse(second.get("hasMore").getAsBoolean());
    }

    @Test
    void leavesPackBodiesOutOfTheList() {
        // The list route must not become a way to download every embedding at once.
        givenCategory(1, "Guides");
        givenPack("only", 1, "Only pack");

        JsonObject item = json(url("/api/chat/knowledge")).getAsJsonArray("items").get(0)
                .getAsJsonObject();

        assertFalse(item.has("rawContent"), "a list row carries no body");
        assertFalse(item.has("embeddedData"), "and no embedding");
        assertTrue(item.get("hasEmbedding").getAsBoolean());
    }

    @Test
    void servesOnePackInFullOnTheDetailRoute() {
        givenCategory(1, "Guides");
        givenPack("netherite", 1, "Netherite guide");

        JsonObject pack = json(url("/api/chat/knowledge/netherite"));

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

        assertEquals(1, json(url("/api/chat/knowledge?q=redstone")).get("total").getAsInt());
        assertEquals(1, json(url("/api/chat/knowledge?categoryId=2")).get("total").getAsInt());
        assertEquals(2, json(url("/api/chat/categories")).getAsJsonArray("items").size());
    }

    @Test
    void reportsAMissingPackRatherThanFailing() {
        assertEquals(404, statusOf(url("/api/chat/knowledge/no-such-pack")));
    }

    // ---------------------------------------------------------------- chat

    @Test
    void holdsAConversationTheSameWayTheGameDoes() {
        JsonObject created = json(post(url("/api/chat/chats"), "{\"title\":\"From the browser\"}"));
        String id = created.get("id").getAsString();

        JsonObject exchange = json(post(url("/api/chat/chats/" + id + "/messages"),
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

        JsonObject page = json(url("/api/chat/chats?limit=10"));

        assertEquals(10, page.getAsJsonArray("items").size());
        assertEquals(23, page.get("total").getAsInt());
        assertTrue(page.get("hasMore").getAsBoolean());
    }

    @Test
    void searchesConversationsByWhatWasSaidInThem() {
        ChatSession session = await(database.chat().createSession("Untitled"));
        await(database.chat().appendMessage(session.id(), ChatRole.USER, "netherite please"));
        await(database.chat().createSession("Something else"));

        assertEquals(1, json(url("/api/chat/chats?q=netherite")).get("total").getAsInt());
    }

    @Test
    void renamesAndDeletesAConversation() {
        ChatSession session = await(database.chat().createSession("Before"));

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/chats/" + session.id())))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"title\":\"After\"}"))).statusCode());
        assertEquals("After", await(database.chat().findSession(session.id())).orElseThrow().title());

        assertEquals(200, send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/chat/chats/" + session.id())))
                .DELETE()).statusCode());
        assertTrue(await(database.chat().findSession(session.id())).isEmpty());
    }

    @Test
    void refusesAnEmptyPromptWithSomethingWorthReading() {
        ChatSession session = await(database.chat().createSession("Empty"));

        HttpResponse<String> response =
                post(url("/api/chat/chats/" + session.id() + "/messages"), "{\"prompt\":\"   \"}");

        assertEquals(400, response.statusCode());
        assertTrue(JsonParser.parseString(response.body()).getAsJsonObject()
                .get("error").getAsString().toLowerCase().contains("message"));
    }

    @Test
    void reportsAMissingConversationRatherThanCreatingOne() {
        assertEquals(404, post(url("/api/chat/chats/no-such-session/messages"),
                "{\"prompt\":\"hello\"}").statusCode());
        assertEquals(404, statusOf(url("/api/chat/chats/no-such-session/messages")));
    }

    // ------------------------------------------------------------ the sync

    @Test
    void reportsAChangeMadeInTheGameSoTheBrowserCanNotice() {
        long before = json(url("/api/chat/revision")).get("chat").getAsLong();

        // A write through the repository, which is what the in-game screen does.
        await(database.chat().createSession("Typed in game"));

        assertNotEquals(before, json(url("/api/chat/revision")).get("chat").getAsLong(),
                "a conversation started in game must move the counter the browser polls");
    }

    @Test
    void reportsAChangeMadeInTheBrowserSoTheGameCanNotice() {
        long before = database.changes().chatRevision();

        json(post(url("/api/chat/chats"), "{\"title\":\"Typed in the browser\"}"));

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

    // ------------------------------------------------------------- address

    @Test
    void landsOnTheKnowledgePageCarryingTheToken() {
        assertEquals("/chat/knowledge/", console.url().getPath());
        assertEquals(200, statusOf(console.url().toString()));
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

    /** One route row from the routing payload, by platform. */
    private JsonObject routeFor(JsonObject routes, String platform) {
        for (var item : routes.getAsJsonArray("items")) {
            JsonObject row = item.getAsJsonObject();
            if (platform.equals(row.get("platform").getAsString())) {
                return row;
            }
        }
        throw new AssertionError("no route row for " + platform + " in " + routes);
    }

    private HttpResponse<String> putJson(String path, String body) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
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

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
