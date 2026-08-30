package io.github.mcagents.clientfabric.website;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mcagents.clientfabric.api.model.ApiKey;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatSession;
import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.model.KnowledgeSummary;
import io.github.mcagents.clientfabric.api.model.Page;
import io.github.mcagents.clientfabric.api.wallet.ChainAddress;
import io.github.mcagents.clientfabric.api.wallet.Wallet;

import java.util.function.Function;

/**
 * Turns the model records into the JSON the console's pages read.
 *
 * <h2>The one rule this file must never break</h2>
 *
 * <p>{@link #apiKey} writes a key's mask and a handle, and <strong>never its value</strong>.
 * There is deliberately no branch, no flag, and no second method that would emit the whole
 * thing — see {@code .agents/security/api-keys.md}. If a future route needs to identify a
 * key, it uses the handle; if it needs to show one, it shows the mask.
 *
 * <p>Timestamps go out as ISO-8601 strings rather than epoch numbers, because the pages
 * render them with {@code Intl.DateTimeFormat} and a string that says what it means is
 * easier to read in a failing response than a ten-digit number.
 */
final class WebJson {

    private WebJson() {
        throw new AssertionError("No instances.");
    }

    /**
     * One wallet, with every chain address under it.
     *
     * <p>The account travels with it. The page acts on a wallet by naming the account as
     * well as the id, and a row that did not carry its own account would leave the page
     * guessing which panel it came from.
     */
    static JsonObject wallet(Wallet wallet) {
        JsonArray addresses = new JsonArray();
        wallet.addresses().forEach(address -> addresses.add(chainAddress(address)));

        JsonObject body = new JsonObject();
        body.addProperty("id", wallet.id());
        body.addProperty("accountId", wallet.accountId());
        body.addProperty("label", wallet.label());
        body.addProperty("active", wallet.active());
        body.addProperty("verified", wallet.hasVerifiedAddress());
        body.add("addresses", addresses);
        body.addProperty("createdAt", wallet.createdAt().toString());
        body.addProperty("updatedAt", wallet.updatedAt().toString());
        return body;
    }

    /**
     * One public address on one chain.
     *
     * <p>Both forms travel: the shortened one the list renders, and the whole value, because
     * an address the player cannot copy is an address they cannot use. Shortening is a
     * layout decision here, never secrecy — a public address authorises nothing.
     */
    static JsonObject chainAddress(ChainAddress address) {
        JsonObject body = new JsonObject();
        body.addProperty("id", address.id());
        body.addProperty("platform", address.platform().storageName());
        body.addProperty("platformName", address.platform().displayName());
        body.addProperty("address", address.address());
        body.addProperty("masked", address.masked());
        body.addProperty("verified", address.verified());
        body.addProperty("createdAt", address.createdAt().toString());
        return body;
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

    /**
     * A stored key, as the console is allowed to describe one.
     *
     * <p>The mask for the player to recognise it by, the handle for the page to act on it
     * with, and nothing that could be sent to OpenRouter.
     */
    static JsonObject apiKey(ApiKey key) {
        JsonObject body = new JsonObject();
        body.addProperty("handle", ApiKeyHandles.of(key));
        body.addProperty("masked", key.masked());
        // Which API the key opens, not a second thing to keep secret: the platform is the
        // one part of a key that is safe to show, and without it a player cannot tell two
        // masked keys apart.
        body.addProperty("platform", key.platform());
        body.addProperty("active", key.active());
        body.addProperty("createdAt", key.createdAt().toString());
        return body;
    }

    static JsonObject error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body;
    }
}
