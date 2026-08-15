package io.github.mcagents.clientfabric.common.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.github.mcagents.clientfabric.api.agent.Agent;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.ContentAvailability;
import io.github.mcagents.clientfabric.api.model.ContentQuery;
import io.github.mcagents.clientfabric.api.model.PageRequest;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The console's JSON endpoints.
 *
 * <h2>Blocking here is correct, and only here</h2>
 *
 * <p>Every method waits on the future it starts. That is the opposite of the rule the
 * screens follow, and it is right for the same reason: an HTTP handler exists to produce a
 * response, it runs on the console's own worker thread, and the thread it must never block
 * — the one rendering the game — is not this one. The database has its own single thread,
 * so waiting here cannot deadlock it either.
 *
 * <p>Every wait is bounded. A request that hangs forever would hold a worker thread for the
 * lifetime of the game.
 *
 * <h2>What has no endpoint</h2>
 *
 * <p>API keys. Not to read, not to write, not to count. The console shows conversations and
 * Knowledge Packs, and a route that touched {@code apiKeys()} would put a provider key one
 * request away from any page the browser happened to be showing.
 */
final class WebApi {

    /** How long a storage call may take before the request gives up on it. */
    private static final Duration STORAGE_TIMEOUT = Duration.ofSeconds(20);

    /**
     * How long an answer may take.
     *
     * <p>Generous, because it covers a model provider on a slow day — this is the same call
     * the in-game screen makes, and there it is allowed to take as long as it takes.
     */
    private static final Duration ANSWER_TIMEOUT = Duration.ofMinutes(3);

    private final LocalDatabase database;
    private final Agent agent;

    WebApi(LocalDatabase database, Agent agent) {
        this.database = database;
        this.agent = agent;
    }

    /** The counter the pages poll to notice what the game changed. */
    JsonObject revision() {
        JsonObject body = new JsonObject();
        body.addProperty("chat", database.changes().chatRevision());
        return body;
    }

    JsonObject categories() {
        JsonArray items = new JsonArray();
        await(database.contents().findAllCategories()).forEach(category ->
                items.add(WebJson.category(category)));

        JsonObject body = new JsonObject();
        body.add("items", items);
        return body;
    }

    /** A window of the knowledge list, filtered by the search box and the category chips. */
    JsonObject knowledge(Map<String, String> query) {
        ContentQuery request = new ContentQuery(
                query.getOrDefault("q", ""),
                availability(query.get("availability")),
                categoryId(query.get("categoryId")));

        return WebJson.page(
                await(database.contents().findSummaryPage(request, window(query, PageRequest.DEFAULT_LIMIT))),
                WebJson::summary);
    }

    /** One pack in full, for the detail page. */
    Optional<JsonObject> knowledgeById(String id) {
        return await(database.contents().findById(id)).map(WebJson::content);
    }

    JsonObject chats(Map<String, String> query) {
        return WebJson.page(
                await(database.chat().findSessionPage(query.get("q"),
                        window(query, PageRequest.DEFAULT_CHAT_LIMIT))),
                WebJson::session);
    }

    Optional<JsonObject> chatMessages(String sessionId) {
        if (await(database.chat().findSession(sessionId)).isEmpty()) {
            return Optional.empty();
        }
        JsonArray items = new JsonArray();
        await(database.chat().findMessages(sessionId)).forEach(message ->
                items.add(WebJson.message(message)));

        JsonObject body = new JsonObject();
        body.add("items", items);
        return Optional.of(body);
    }

    JsonObject createChat(String body) {
        String title = string(body, "title");
        return WebJson.session(await(database.chat().createSession(title.isBlank() ? null : title)));
    }

    boolean renameChat(String sessionId, String body) {
        return await(database.chat().renameSession(sessionId, string(body, "title")));
    }

    boolean deleteChat(String sessionId) {
        return await(database.chat().deleteSession(sessionId));
    }

    /**
     * Asks the agent a question, exactly as the in-game screen does.
     *
     * <p>Same store, same agent, same key — so a question asked in the browser costs the
     * player's own credits and lands in the same conversation they would see in game. That
     * is the point of the page, and it is worth being clear that it is not a preview.
     *
     * @return the stored question and the stored answer, or empty if there is no such
     *         conversation
     */
    Optional<JsonObject> sendMessage(String sessionId, String body) {
        String prompt = string(body, "prompt").trim();
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("A message needs something in it.");
        }
        if (await(database.chat().findSession(sessionId)).isEmpty()) {
            return Optional.empty();
        }

        ChatMessage question = await(database.chat()
                .appendMessage(sessionId, ChatRole.USER, prompt));

        // The answer is stored before it is returned, so a browser that is closed mid-reply
        // still leaves the conversation complete — the same order the in-game screen uses.
        String answer = await(agent.respond(sessionId, prompt), ANSWER_TIMEOUT);
        ChatMessage reply = await(database.chat()
                .appendMessage(sessionId, ChatRole.ASSISTANT, answer));

        JsonObject result = new JsonObject();
        result.add("question", WebJson.message(question));
        result.add("answer", WebJson.message(reply));
        return Optional.of(result);
    }

    // ------------------------------------------------------------- parsing

    private static PageRequest window(Map<String, String> query, int fallbackLimit) {
        // PageRequest clamps, so whatever arrives in the query string ends up in range.
        return new PageRequest(
                integer(query.get("offset"), 0),
                integer(query.get("limit"), fallbackLimit));
    }

    private static int integer(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Long categoryId(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ContentAvailability availability(String value) {
        if (value == null || value.isBlank()) {
            return ContentAvailability.ANY;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "free" -> ContentAvailability.FREE;
            case "premium" -> ContentAvailability.PREMIUM;
            default -> ContentAvailability.ANY;
        };
    }

    /** Reads one string member out of a request body, treating anything malformed as absent. */
    private static String string(String body, String member) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            return root.has(member) && root.get(member).isJsonPrimitive()
                    ? root.get(member).getAsString()
                    : "";
        } catch (JsonSyntaxException | IllegalStateException e) {
            return "";
        }
    }

    // -------------------------------------------------------------- waiting

    private static <T> T await(CompletableFuture<T> future) {
        return await(future, STORAGE_TIMEOUT);
    }

    /**
     * Waits for a result on the console's worker thread.
     *
     * <p>Unwraps the {@link CompletionException} the future would otherwise throw, so the
     * caller above sees the failure the storage or the agent actually produced rather than
     * a wrapper around it.
     */
    private static <T> T await(CompletableFuture<T> future, Duration timeout) {
        try {
            return future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }
}
