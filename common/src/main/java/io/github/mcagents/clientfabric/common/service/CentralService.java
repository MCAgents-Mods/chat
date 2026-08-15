package io.github.mcagents.clientfabric.common.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.common.agent.HttpCall;
import io.github.mcagents.clientfabric.common.agent.HttpReply;
import io.github.mcagents.clientfabric.common.agent.HttpTransport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads the central Knowledge Pack catalog at {@link MCAgents#SERVICE_URL}.
 *
 * <h2>The rule that shapes every method here</h2>
 *
 * <p><strong>This service is a content source, not a dependency.</strong> Nothing on this
 * class is on the path of anything the player does in game: they can chat, search, and read
 * every downloaded pack with it unreachable. So no method here throws its way up into
 * something that matters — a failure is a reported failure, and the caller carries on.
 *
 * <p>It is also the only class in the mod that talks to that service. Keeping it in one
 * place is what makes "the mod works offline" checkable rather than hoped for.
 *
 * <h2>Nothing of the player's is sent</h2>
 *
 * <p>No API key, no conversation, no prompt, no identifier. The requests are a health check,
 * a catalog read, and a list of pack versions already held — which says what is installed
 * and nothing about who installed it.
 */
public final class CentralService {

    /** How the service is described when it answers but says nothing useful. */
    private static final String UNKNOWN_VERSION = "unknown";

    private final HttpTransport transport;
    private final String baseUrl;

    public CentralService(HttpTransport transport) {
        this(transport, MCAgents.SERVICE_URL);
    }

    /** @param baseUrl without a trailing slash; a test points this at a local stub */
    public CentralService(HttpTransport transport, String baseUrl) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    /** Where this instance is pointed, for a screen that wants to show it. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Asks whether the service is awake.
     *
     * <p>Never completes exceptionally. A refused connection, a timeout, and a 500 are all
     * simply "not reachable yet" — which is the same thing to a caller waiting for a host
     * that sleeps when idle, and turning any of them into an exception would only make
     * every caller catch it.
     */
    public CompletableFuture<ServiceHealth> health() {
        return transport.send(HttpCall.get(baseUrl + "/health", Map.of()))
                .handle((reply, failure) -> {
                    if (failure != null) {
                        return ServiceHealth.unreachable(describe(failure));
                    }
                    if (!reply.isSuccess()) {
                        return ServiceHealth.unreachable("The service answered " + reply.status() + ".");
                    }
                    JsonObject body = parseObject(reply).orElse(new JsonObject());
                    return ServiceHealth.reachable(
                            body.has("version") ? body.get("version").getAsString() : UNKNOWN_VERSION);
                });
    }

    /** Every category the service publishes. */
    public CompletableFuture<List<ContentCategory>> categories() {
        return get("/v1/categories").thenApply(body -> {
            List<ContentCategory> categories = new ArrayList<>();
            for (JsonElement element : array(body, "items")) {
                JsonObject row = element.getAsJsonObject();
                categories.add(new ContentCategory(
                        row.get("id").getAsLong(),
                        row.get("displayName").getAsString(),
                        readInstant(row, "createdAt"),
                        readInstant(row, "updatedAt")));
            }
            return List.copyOf(categories);
        });
    }

    /**
     * The version of every pack the service publishes, by id.
     *
     * <p>Read from the summary listing rather than from a dedicated endpoint, because a
     * summary already carries the version and this way one round trip answers "what exists
     * and at which version" for the whole catalog.
     */
    public CompletableFuture<Map<String, String>> publishedVersions() {
        return publishedVersions(0, new LinkedHashMap<>());
    }

    private CompletableFuture<Map<String, String>> publishedVersions(
            int offset, Map<String, String> collected) {
        return get("/v1/contents?offset=" + offset + "&pageSize=100").thenCompose(body -> {
            for (JsonElement element : array(body, "items")) {
                JsonObject row = element.getAsJsonObject();
                collected.put(row.get("id").getAsString(), row.get("version").getAsString());
            }
            // Paged rather than assumed: a catalog larger than one page would otherwise
            // silently look like a catalog that had lost most of its packs.
            boolean hasMore = body.has("hasMore") && body.get("hasMore").getAsBoolean();
            int next = body.has("nextOffset")
                    ? body.get("nextOffset").getAsInt()
                    : offset + 100;
            return hasMore && next > offset
                    ? publishedVersions(next, collected)
                    : CompletableFuture.completedFuture(Map.copyOf(collected));
        });
    }

    /** One pack in full, ready to store. */
    public CompletableFuture<KnowledgeContent> content(String id) {
        return get("/v1/contents/" + encode(id)).thenApply(CentralService::readContent);
    }

    // ------------------------------------------------------------ plumbing

    private CompletableFuture<JsonObject> get(String path) {
        return transport.send(HttpCall.get(baseUrl + path, Map.of())).thenApply(reply -> {
            if (!reply.isSuccess()) {
                throw new CentralServiceException(explain(reply));
            }
            return parseObject(reply).orElseThrow(() ->
                    new CentralServiceException("The service sent something that was not JSON."));
        });
    }

    /**
     * Turns a failed reply into something worth showing a player.
     *
     * <p>A 429 is the one they will actually meet — it is what pressing refresh repeatedly
     * produces — so it says how long to wait rather than reporting a number.
     */
    private static String explain(HttpReply reply) {
        if (reply.status() == 429) {
            int seconds = parseObject(reply)
                    .map(body -> body.getAsJsonObject("error"))
                    .filter(Objects::nonNull)
                    .map(error -> error.getAsJsonObject("details"))
                    .filter(Objects::nonNull)
                    .filter(details -> details.has("retryAfterSeconds"))
                    .map(details -> details.get("retryAfterSeconds").getAsInt())
                    .orElse(60);
            return "Too many requests just now. Try again in about " + seconds + " seconds.";
        }
        if (reply.status() == 404) {
            return "The service does not publish that.";
        }
        return "The service answered " + reply.status() + ".";
    }

    private static KnowledgeContent readContent(JsonObject row) {
        return new KnowledgeContent(
                row.get("id").getAsString(),
                row.get("categoryId").getAsLong(),
                row.get("version").getAsString(),
                row.has("premium") && row.get("premium").getAsBoolean(),
                string(row, "title"),
                string(row, "rawContent"),
                string(row, "embeddedData"),
                readInstant(row, "createdAt"),
                readInstant(row, "updatedAt"));
    }

    private static JsonArray array(JsonObject body, String member) {
        return body.has(member) && body.get(member).isJsonArray()
                ? body.getAsJsonArray(member)
                : new JsonArray();
    }

    private static String string(JsonObject row, String member) {
        return row.has(member) && row.get(member).isJsonPrimitive()
                ? row.get(member).getAsString()
                : "";
    }

    /** A timestamp the service sent, or now — a bad date must not lose a usable pack. */
    private static Instant readInstant(JsonObject row, String member) {
        try {
            return row.has(member) && row.get(member).isJsonPrimitive()
                    ? Instant.parse(row.get(member).getAsString())
                    : Instant.now();
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private static Optional<JsonObject> parseObject(HttpReply reply) {
        try {
            JsonElement parsed = JsonParser.parseString(reply.body());
            return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                ? failure.getCause() : failure;
        // The class name, not the message: a transport failure's message can carry the
        // whole URL and is written for a developer rather than a player.
        return cause == null ? "Not reachable." : "Not reachable (" + cause.getClass().getSimpleName() + ").";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
