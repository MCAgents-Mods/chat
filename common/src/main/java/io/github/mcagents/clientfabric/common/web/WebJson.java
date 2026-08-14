package io.github.mcagents.clientfabric.common.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatSession;
import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.model.KnowledgeSummary;
import io.github.mcagents.clientfabric.api.model.Page;

import java.util.function.Function;

/**
 * Turns the model records into the JSON the console's pages read.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>There is no writer here for an {@code ApiKey}, and that is not an oversight. The
 * console shows conversations and Knowledge Packs; a provider key is neither. Keeping the
 * conversion out of this file means a future route cannot serialise one by reaching for a
 * helper that already exists.
 *
 * <p>Timestamps go out as ISO-8601 strings rather than epoch numbers, because the pages
 * render them with {@code Intl.DateTimeFormat} and a string that says what it means is
 * easier to read in a failing response than a ten-digit number.
 */
final class WebJson {

    private WebJson() {
        throw new AssertionError("No instances.");
    }

    /** A window of rows, with everything the page needs to ask for the next one. */
    static <T> JsonObject page(Page<T> page, Function<T, JsonObject> writer) {
        JsonArray items = new JsonArray();
        page.items().forEach(item -> items.add(writer.apply(item)));

        JsonObject body = new JsonObject();
        body.add("items", items);
        body.addProperty("offset", page.offset());
        body.addProperty("limit", page.limit());
        body.addProperty("total", page.total());
        body.addProperty("hasMore", page.hasMore());
        body.addProperty("nextOffset", page.nextOffset());
        return body;
    }

    static JsonObject category(ContentCategory category) {
        JsonObject body = new JsonObject();
        body.addProperty("id", category.id());
        body.addProperty("displayName", category.displayName());
        return body;
    }

    /** A row in the knowledge list: everything except the two large columns. */
    static JsonObject summary(KnowledgeSummary summary) {
        JsonObject body = new JsonObject();
        body.addProperty("id", summary.id());
        body.addProperty("categoryId", summary.categoryId());
        body.addProperty("version", summary.version());
        body.addProperty("premium", summary.premium());
        body.addProperty("title", summary.title());
        body.addProperty("hasEmbedding", summary.hasEmbedding());
        body.addProperty("createdAt", summary.createdAt().toString());
        body.addProperty("updatedAt", summary.updatedAt().toString());
        return body;
    }

    /**
     * A whole pack, for the detail page.
     *
     * <p>The embedding travels as the string it is stored as. The detail page shows it as
     * evidence that the pack can take part in retrieval, not as something to compute with,
     * and re-encoding thousands of numbers into a JSON array to be printed back as text
     * would be work in service of nothing.
     */
    static JsonObject content(KnowledgeContent content) {
        JsonObject body = new JsonObject();
        body.addProperty("id", content.id());
        body.addProperty("categoryId", content.categoryId());
        body.addProperty("version", content.version());
        body.addProperty("premium", content.premium());
        body.addProperty("title", content.title());
        body.addProperty("rawContent", content.rawContent());
        body.addProperty("embeddedData", content.embeddedData());
        body.addProperty("hasEmbedding", content.hasEmbedding());
        body.addProperty("createdAt", content.createdAt().toString());
        body.addProperty("updatedAt", content.updatedAt().toString());
        return body;
    }

    static JsonObject session(ChatSession session) {
        JsonObject body = new JsonObject();
        body.addProperty("id", session.id());
        body.addProperty("title", session.title());
        body.addProperty("createdAt", session.createdAt().toString());
        body.addProperty("updatedAt", session.updatedAt().toString());
        return body;
    }

    static JsonObject message(ChatMessage message) {
        JsonObject body = new JsonObject();
        body.addProperty("id", message.id());
        body.addProperty("sessionId", message.sessionId());
        body.addProperty("index", message.index());
        body.addProperty("role", message.role().wireName());
        body.addProperty("content", message.content());
        body.addProperty("createdAt", message.createdAt().toString());
        return body;
    }

    static JsonObject error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body;
    }
}
