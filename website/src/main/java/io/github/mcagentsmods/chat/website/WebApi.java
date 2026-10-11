package io.github.mcagentsmods.chat.website;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ChatRole;
import io.github.mcagentsmods.chat.api.model.ContentAvailability;
import io.github.mcagentsmods.chat.api.model.ContentQuery;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.ModelPlatform;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.model.PageRequest;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.api.wallet.ChainPlatform;
import io.github.mcagentsmods.chat.api.wallet.Wallet;
import io.github.mcagentsmods.chat.api.wallet.WalletBinding;
import io.github.mcagentsmods.chat.common.agent.EmbeddingBackfill;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.chat.common.service.CentralService;
import io.github.mcagentsmods.chat.common.service.ContentNotPublishedException;
import io.github.mcagentsmods.chat.common.service.KnowledgeSync;
import io.github.mcagentsmods.chat.common.service.ServiceHealth;
import io.github.mcagentsmods.chat.common.wallet.AccountWallets;
import io.github.mcagentsmods.chat.common.wallet.WalletChallenges;

import java.time.Duration;
import java.util.List;
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
 * <h2>Keys, and the line drawn around them</h2>
 *
 * <p>The console manages API keys, because a player who can read their conversations in a
 * browser can reasonably expect to add a key there too. What it never does is hand one
 * back: {@link #keys} returns masks and handles, and there is no route, parameter, or flag
 * that returns a key's value. Adding one travels inward over loopback, exactly as typing it
 * into the game does; nothing travels outward.
 *
 * <p>See {@code .agents/security/api-keys.md}, which allows a key in the interface as a
 * mask and nowhere else.
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

    /** Short: a health check exists to answer quickly or not at all. */
    private static final Duration SERVICE_TIMEOUT = Duration.ofSeconds(15);

    /** Long: a sync downloads pack bodies one at a time over somebody's home connection. */
    private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Long: embedding a library is one provider call per pack, sequentially.
     *
     * <p>A player with a hundred packs is asking for a hundred round trips, and the honest
     * bound on that is minutes rather than seconds.
     */
    private static final Duration EMBED_TIMEOUT = Duration.ofMinutes(10);

    private final LocalDatabase database;
    private final Agent agent;
    private final CentralService service;
    private final KnowledgeSync sync;
    private final EmbeddingBackfill backfill;
    private final AccountWallets accountWallets;
    private final PersonaStore persona;

    /**
     * The challenges this console has issued and not yet seen answered.
     *
     * <p>One per console rather than one per request, because a challenge is issued by one
     * request and redeemed by the next. It outlives neither the console nor the game.
     */
    private final WalletChallenges challenges = new WalletChallenges();

    WebApi(LocalDatabase database, Agent agent, CentralService service, KnowledgeSync sync,
           EmbeddingBackfill backfill, PersonaStore persona) {
        this.accountWallets = new AccountWallets(database.wallets());
        this.database = database;
        this.agent = agent;
        this.service = service;
        this.sync = sync;
        this.backfill = backfill;
        this.persona = persona;
    }

    /**
     * The player's {@code AGENTS.md}, as text.
     *
     * <p>The same store the game reads, so what the browser shows is what the next
     * question will be asked with — not a copy that has to be kept in step.
     */
    JsonObject persona() {
        JsonObject body = new JsonObject();
        body.addProperty("content", await(persona.read(), STORAGE_TIMEOUT));
        return body;
    }

    /**
     * Replaces {@code AGENTS.md} with what the reader typed.
     *
     * <p>Blank is a legitimate save — emptying the file is how a player goes back to the
     * built-in persona — but an <em>absent</em> field is not: it means the request said
     * nothing about the text, and writing a blank file for it would delete somebody's
     * instructions because a page sent the wrong shape.
     *
     * <p>The store makes the new text live as it writes it, so there is nothing to reload
     * and no window in which the game and the browser disagree.
     */
    JsonObject savePersona(String body) {
        String content = member(body, "content").orElseThrow(() ->
                new IllegalArgumentException("A save has to carry the text to save."));

        JsonObject result = new JsonObject();
        result.addProperty("content", await(persona.save(content), STORAGE_TIMEOUT));
        return result;
    }

    /**
     * Whether the central service is awake.
     *
     * <p>The page asks this on load and then once a minute while it is asleep. Never an
     * error: the host idles, so "not reachable" is an ordinary state and the page shows a
     * banner rather than a failure.
     */
    JsonObject serviceHealth() {
        ServiceHealth health = await(service.health(), SERVICE_TIMEOUT);

        JsonObject body = new JsonObject();
        body.addProperty("reachable", health.reachable());
        body.addProperty("version", health.version());
        body.addProperty("detail", health.detail());
        body.addProperty("url", service.baseUrl());
        return body;
    }

    /**
     * Brings the packs already on this machine up to their published versions.
     *
     * <p>Behind the update button on the chat page, and nothing else. It downloads no pack
     * the player has not already chosen — taking a new one is done from that pack's page,
     * where they can see what they are taking.
     */
    JsonObject updateKnowledge() {
        KnowledgeSync.SyncResult result = await(sync.updateStored(), SYNC_TIMEOUT);

        JsonObject body = new JsonObject();
        body.addProperty("downloaded", result.downloaded());
        body.addProperty("failed", result.failed());
        body.addProperty("published", result.published());
        body.addProperty("changed", result.changedAnything());
        return body;
    }

    /**
     * What a refresh would fetch, without fetching any of it.
     *
     * <p>The chat page asks this when it opens. Checking is one request; downloading is
     * however many packs have changed, over somebody's home connection, while they are in a
     * game. Keeping them separate is what lets the page say "three updates available"
     * without having spent anything to find out.
     */
    JsonObject knowledgeUpdates() {
        KnowledgeSync.UpdateStatus status = await(sync.checkForUpdates(), SERVICE_TIMEOUT);

        JsonObject body = new JsonObject();
        body.addProperty("available", status.available());
        body.addProperty("missing", status.missingIds().size());
        body.addProperty("outdated", status.outdatedIds().size());
        body.addProperty("published", status.published());
        return body;
    }

    /**
     * Downloads one pack by id.
     *
     * <p>The button on a pack's own page. It does not check the version first: the player
     * asked for this pack, and deciding it was already current and doing nothing would look
     * like a broken button.
     */
    JsonObject downloadPack(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Name the pack to download.");
        }
        KnowledgeContent stored = await(sync.download(id), SYNC_TIMEOUT);

        JsonObject body = new JsonObject();
        body.addProperty("id", stored.id());
        body.addProperty("version", stored.version());
        body.addProperty("title", stored.title());
        body.addProperty("hasEmbedding", stored.hasEmbedding());
        return body;
    }

    /**
     * Whether one pack is behind, without downloading it.
     *
     * <p>The detail page asks about the pack it is showing. The chat page asks about the
     * whole library. Neither is a substitute for the other: a reader looking at one pack does
     * not want a listing of every other one, and a player about to ask a question does not
     * want to check packs one at a time.
     */
    JsonObject packUpdate(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Name the pack to check.");
        }
        KnowledgeSync.PackStatus status = await(sync.checkPack(id), SERVICE_TIMEOUT);

        JsonObject body = new JsonObject();
        body.addProperty("id", status.id());
        body.addProperty("stored", status.stored());
        body.addProperty("storedVersion", status.storedVersion());
        body.addProperty("publishedVersion", status.publishedVersion());
        body.addProperty("updateAvailable", status.updateAvailable());
        return body;
    }

    /**
     * Removes a downloaded pack.
     *
     * <p>Local only. Deleting here takes the pack off this machine; it does not unpublish
     * anything, and the pack reappears under "Available to download" the moment the page is
     * reloaded — which is the honest outcome, because that is exactly what has happened.
     *
     * @return whether a pack was removed, so a missing one answers 404 rather than pretending
     */
    boolean deletePack(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Name the pack to delete.");
        }
        return await(database.contents().remove(id), STORAGE_TIMEOUT);
    }

    /**
     * How much of the library can be retrieved against.
     *
     * <p>Free — it reads the local table and compares vector widths — so the chat page can
     * show "4 packs need embedding" on load without spending a provider call to find out.
     */
    JsonObject embeddingStatus() {
        ModelPreferences preferences = await(database.settings().loadModelPreferences());
        EmbeddingBackfill.Status status = await(backfill.status(preferences), STORAGE_TIMEOUT);

        JsonObject body = new JsonObject();
        body.addProperty("packs", status.packs());
        body.addProperty("needing", status.needing());
        body.addProperty("model", preferences.embeddingModel());
        return body;
    }

    /**
     * Embeds every stored pack that cannot take part in retrieval.
     *
     * <p>Uses the player's embedding model, because that is the only space their questions
     * will be embedded into. It runs whether or not the embedding switch is on: the switch
     * governs whether retrieval happens, and a player may reasonably prepare a library
     * before turning it back on.
     */
    JsonObject embedKnowledge() {
        ModelPreferences preferences = await(database.settings().loadModelPreferences());
        EmbeddingBackfill.Result result = await(backfill.run(preferences), EMBED_TIMEOUT);

        JsonObject body = new JsonObject();
        body.addProperty("embedded", result.embedded());
        body.addProperty("failed", result.failed());
        body.addProperty("model", preferences.embeddingModel());
        return body;
    }

    /** The counter the pages poll to notice what the game changed. */
    JsonObject revision() {
        JsonObject body = new JsonObject();
        body.addProperty("chat", database.changes().chatRevision());
        return body;
    }

    /** One window of the stored keys, as masks and handles. Never as values. */
    JsonObject keys(Map<String, String> query) {
        return WebJson.page(
                await(database.apiKeys().findPage(window(query, PageRequest.DEFAULT_CHAT_LIMIT))),
                WebJson::apiKey);
    }

    /**
     * Stores a key the player pasted into the browser.
     *
     * <p>The value arrives in a request body over loopback and goes straight into storage.
     * It is not echoed back, not logged, and not included in the response — the page is told
     * the mask and the handle, the same as every other key.
     */
    JsonObject addKey(String body) {
        String value = string(body, "value").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Paste a key first.");
        }
        // A missing platform is an OpenRouter key, which is what every key was before the mod
        // could call a platform directly — so an older page, or a request that predates this
        // field, keeps storing exactly what it always stored.
        String platform = ModelPlatform.normalise(string(body, "platform"));
        // Deliberately not validated against a provider's format. OpenRouter has changed its
        // prefix before, and a mod that refuses a working key because it looks unfamiliar is
        // worse than one that lets the provider reject it. The platform is not validated
        // against a list either, for the same reason.
        return WebJson.apiKey(await(database.apiKeys().add(value, platform)));
    }

    /**
     * Which platforms are reachable directly, and where each one's API is.
     *
     * <p>Every platform the player holds a key for, plus every platform this release knows an
     * address for, so the page can show both what is configured and what could be. A row is
     * only {@code direct} when both halves are there: an address to send to, and a key for that
     * platform to send with. Everything else goes through the gateway, which is what the page
     * says rather than leaving the player to infer it.
     */
    JsonObject routes() {
        java.util.Set<String> platforms = new java.util.TreeSet<>();
        java.util.Set<String> withKeys = new java.util.TreeSet<>();
        for (ApiKey key : await(database.apiKeys().findAll())) {
            platforms.add(key.platform());
            if (key.active()) {
                withKeys.add(key.platform());
            }
        }
        platforms.addAll(ModelPlatform.knownDirectBaseUrls().keySet());
        platforms.remove(ModelPlatform.OPENROUTER);

        JsonArray items = new JsonArray();
        for (String platform : platforms) {
            String configured = await(database.settings()
                    .find(ModelPlatform.baseUrlSettingKey(platform))).orElse("").trim();
            String known = ModelPlatform.knownBaseUrl(platform).orElse("");
            String effective = ModelPlatform.isUsableBaseUrl(configured) ? configured : known;

            JsonObject item = new JsonObject();
            item.addProperty("platform", platform);
            item.addProperty("configuredUrl", configured);
            item.addProperty("knownUrl", known);
            item.addProperty("baseUrl", effective);
            item.addProperty("hasKey", withKeys.contains(platform));
            item.addProperty("direct", !effective.isEmpty() && withKeys.contains(platform));
            items.add(item);
        }

        JsonArray suggested = new JsonArray();
        // Every platform worth offering as a choice when a key is added. Built in api/ rather
        // than here, because the game's key screen offers the same list and two lists computed
        // in two places stay equal only until one of them is edited.
        ModelCatalog.offeredPlatforms(platforms).forEach(suggested::add);

        JsonObject body = new JsonObject();
        body.add("items", items);
        body.add("suggestedPlatforms", suggested);
        body.addProperty("gatewayUrl", ModelPlatform.OPENROUTER_BASE_URL);
        body.addProperty("gateway", ModelPlatform.OPENROUTER);
        // Whether this machine encrypts what it stores, said plainly rather than implied. A
        // player who has not set the variable is storing keys in the clear, and the page that
        // takes their key is the right place to tell them so.
        JsonObject encryption = new JsonObject();
        encryption.addProperty("enabled", TokenCipher.fromEnvironment().enabled());
        encryption.addProperty("variable", TokenCipher.KEY_VARIABLE);
        body.add("encryption", encryption);
        return body;
    }

    /**
     * Sets, or clears, the address one platform's API is called at.
     *
     * <p>A blank URL clears the override and puts the platform back on whatever this release
     * knows — or on the gateway when it knows nothing. A URL that is neither {@code https} nor
     * loopback is refused rather than stored: the value the player is configuring is where
     * their API key gets sent, and there is no version of that which is safe in the clear.
     */
    JsonObject saveRoute(String body) {
        String platform = ModelPlatform.normalise(string(body, "platform"));
        if (ModelPlatform.isOpenRouter(platform)) {
            throw new IllegalArgumentException("The gateway's own address is not configurable.");
        }
        String url = string(body, "baseUrl").trim();
        if (!url.isEmpty() && !ModelPlatform.isUsableBaseUrl(url)) {
            throw new IllegalArgumentException(
                    "A base URL must start with https://, or be a loopback address.");
        }
        await(database.settings().put(ModelPlatform.baseUrlSettingKey(platform), url));
        return routes();
    }

    /**
     * Removes the key a handle names.
     *
     * <p>The handle is resolved against storage here, inside the mod. The browser never
     * learns which value it named — only that the row it was showing has gone.
     */
    boolean deleteKey(String handle) {
        return ApiKeyHandles.resolve(await(database.apiKeys().findAll()), handle)
                .map(key -> await(database.apiKeys().remove(key.value())))
                .orElse(false);
    }

    /**
     * The chosen models, and the ids the page offers as suggestions.
     *
     * <p>The suggestions travel with the selection so the page has no list of its own to
     * drift out of step with the one the game screen shows.
     */
    JsonObject models() {
        ModelPreferences current = await(database.settings().loadModelPreferences());

        JsonObject body = new JsonObject();
        body.addProperty("chatModel", current.chatModel());
        // Which platform answers, read out of the id by the type that owns its shape rather
        // than by a page splitting a string at a slash.
        body.addProperty("chatPlatform", current.chatPlatform().orElse(""));
        body.addProperty("embeddingModel", current.embeddingModel());
        body.addProperty("embeddingEnabled", current.embeddingEnabled());
        body.addProperty("defaultChatModel", ModelCatalog.DEFAULT_CHAT_MODEL);
        body.addProperty("defaultEmbeddingModel", ModelCatalog.DEFAULT_EMBEDDING_MODEL);

        JsonArray chat = new JsonArray();
        ModelCatalog.SUGGESTED_CHAT_MODELS.forEach(chat::add);
        body.add("suggestedChatModels", chat);

        JsonArray embedding = new JsonArray();
        ModelCatalog.SUGGESTED_EMBEDDING_MODELS.forEach(embedding::add);
        body.add("suggestedEmbeddingModels", embedding);
        return body;
    }

    /**
     * Stores a model selection.
     *
     * <p>Nothing is validated against the suggested lists. They are suggestions, and a
     * provider adds and retires models faster than this mod ships — an id it does not serve
     * fails at request time with the provider's own message, which is more use than
     * anything this could invent. A blank field is not an error either: it resolves to the
     * default, which is how a player gets back to it by hand.
     */
    JsonObject saveModels(String body) {
        // Absent and blank are different requests, for every field here.
        //
        //   absent — this request is not about that setting; leave it as it is
        //   blank  — the player cleared the box, which is how they ask for the default
        //
        // Collapsing the two would make a partial update destructive: the chat page sends
        // only the switch, and a handler that read the missing model ids as blanks would
        // reset both models to the defaults every time somebody flipped it.
        ModelPreferences current = await(database.settings().loadModelPreferences());

        // chatPlatform is the chat page asking a smaller question: not "use this id" but
        // "have this platform answer". Both cannot be honoured at once, and an explicit id
        // wins because it says strictly more — a platform on its own leaves the model to
        // ModelCatalog, which is the only place that rule lives.
        String chatModel = member(body, "chatModel").orElseGet(() ->
                member(body, "chatPlatform")
                        .map(platform -> ModelCatalog.chatModelOn(platform, current.chatModel()))
                        .orElse(current.chatModel()));

        ModelPreferences requested = ModelPreferences.of(
                chatModel,
                member(body, "embeddingModel").orElse(current.embeddingModel()),
                flag(body, "embeddingEnabled").orElse(current.embeddingEnabled()));
        ModelPreferences stored = await(database.settings().saveModelPreferences(requested));

        JsonObject result = new JsonObject();
        result.addProperty("chatModel", stored.chatModel());
        result.addProperty("chatPlatform", stored.chatPlatform().orElse(""));
        result.addProperty("embeddingModel", stored.embeddingModel());
        result.addProperty("embeddingEnabled", stored.embeddingEnabled());
        return result;
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

    /**
     * Packs the service publishes that this machine does not have.
     *
     * <p>Kept apart from {@link #knowledge} rather than merged into it. That list is a
     * window over a local table and pages with an offset; this one comes from the network
     * and is only as large as the catalog. Interleaving the two would mean paging a union of
     * two sources with different costs and different failure modes, and the page would stop
     * working entirely whenever the service was asleep.
     *
     * <p>Returned whole, because a catalog is a published list of titles rather than a
     * player's accumulated library — and because the alternative is holding a cursor into
     * somebody else's paging.
     */
    JsonObject catalog() {
        java.util.List<io.github.mcagentsmods.chat.api.model.KnowledgeSummary> published =
                await(service.catalog(), SERVICE_TIMEOUT);
        Map<String, String> stored = await(database.contents().findStoredVersions());

        JsonArray items = new JsonArray();
        JsonArray outdated = new JsonArray();
        for (var summary : published) {
            String have = stored.get(summary.id());
            if (have == null) {
                items.add(WebJson.summary(summary));
            } else if (!have.equals(summary.version())) {
                // Named, not just counted. The library list is where a stale pack lives, and
                // a page that knows one is behind but cannot say which leaves the reader to
                // open every pack in turn to find it.
                JsonObject behind = new JsonObject();
                behind.addProperty("id", summary.id());
                behind.addProperty("storedVersion", have);
                behind.addProperty("publishedVersion", summary.version());
                outdated.add(behind);
            }
        }

        JsonObject body = new JsonObject();
        body.add("items", items);
        body.addProperty("total", items.size());
        // Out-of-date packs are reported here but never listed as available: they are
        // already in the library list, and showing one under both headings is one pack
        // twice.
        body.add("outdatedItems", outdated);
        body.addProperty("outdated", outdated.size());
        body.addProperty("published", published.size());
        return body;
    }

    /**
     * One pack in full, for the detail page.
     *
     * <p>Falls back to the service when the pack is not stored, so a pack the player has not
     * downloaded still has a page — which is the only place a download button could sensibly
     * live. The reply says which of the two it came from; the page shows an embedding for one
     * and a download button for the other.
     */
    Optional<JsonObject> knowledgeById(String id) {
        Optional<JsonObject> local = await(database.contents().findById(id))
                .map(WebJson::content);
        if (local.isPresent()) {
            local.get().addProperty("stored", true);
            return local;
        }

        KnowledgeContent published;
        try {
            published = await(service.content(id), SERVICE_TIMEOUT);
        } catch (ContentNotPublishedException e) {
            // The service answered and there is no such pack. That is a fact, and 404 is
            // what it means.
            return Optional.empty();
        } catch (RuntimeException e) {
            // Not stored, and the service could not say. "No such pack" would be a lie —
            // it may exist perfectly well on a host that is merely asleep — so say what
            // actually happened instead of guessing.
            throw new IllegalArgumentException(
                    "That pack is not downloaded, and the knowledge service is not answering.");
        }
        JsonObject body = WebJson.content(published);
        body.addProperty("stored", false);
        return Optional.of(body);
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
    /**
     * A boolean member, or empty if the body does not carry one.
     *
     * <p>Empty and {@code false} are different answers here: absent means "do not change
     * it", and a handler that collapsed the two would let a request that says nothing about
     * a setting switch it off.
     */
    private static Optional<Boolean> flag(String body, String member) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has(member) || !root.get(member).isJsonPrimitive()) {
                return Optional.empty();
            }
            return Optional.of(root.get(member).getAsBoolean());
        } catch (JsonSyntaxException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------- wallets

    /**
     * Every account, each with the wallets that belong to it.
     *
     * <p>Grouped rather than flat, because the account <em>is</em> the grouping the player
     * thinks in and the page draws one panel per account. Flattening it here would only mean
     * the page grouping it again, and a page that regroups is a page that can group it
     * differently from the mod.
     *
     * <p>Accounts have no table: one exists exactly as long as a wallet names it. So this is
     * assembled from the wallets rather than read from a list of accounts, and an account
     * with nothing in it simply is not there.
     */
    JsonObject wallets() {
        JsonArray accounts = new JsonArray();
        int wallets = 0;

        for (String accountId : await(database.wallets().findAccounts())) {
            List<Wallet> held = await(database.wallets().findWalletsByAccount(accountId));
            wallets += held.size();

            JsonArray items = new JsonArray();
            held.forEach(wallet -> items.add(WebJson.wallet(wallet)));

            JsonObject account = new JsonObject();
            account.addProperty("id", accountId);
            account.addProperty("total", held.size());
            account.addProperty("verified",
                    held.stream().filter(Wallet::hasVerifiedAddress).count());
            account.add("wallets", items);
            accounts.add(account);
        }

        JsonObject body = new JsonObject();
        body.add("accounts", accounts);
        body.addProperty("accountCount", accounts.size());
        body.addProperty("total", wallets);
        return body;
    }

    /** Creates an empty wallet, creating the account with it if the name is a new one. */
    JsonObject createWallet(String body) {
        String accountId = string(body, "accountId").trim();
        if (accountId.isEmpty()) {
            throw new IllegalArgumentException("Name the account this wallet belongs to.");
        }
        return WebJson.wallet(
                await(database.wallets().createWallet(accountId, string(body, "label").trim())));
    }

    /**
     * Renames a wallet in one account.
     *
     * @return whether a wallet <em>in that account</em> was renamed; false covers a wallet
     *         that has gone and one that was never the caller's to rename
     */
    boolean renameWallet(String walletId, String body) {
        return await(accountWallets.rename(
                account(string(body, "accountId")), walletId, string(body, "label")));
    }

    /** Deletes a wallet in one account, with every address under it. */
    boolean deleteWallet(String walletId, Map<String, String> query) {
        return await(accountWallets.delete(account(query.get("accountId")), walletId));
    }

    /** Removes one chain's address from a wallet in one account. */
    boolean removeWalletAddress(String walletId, String platform, Map<String, String> query) {
        return await(accountWallets.removeAddress(account(query.get("accountId")), walletId,
                ChainPlatform.fromStorageName(platform)));
    }

    /**
     * Records an address the player typed rather than proved.
     *
     * <p>Stored as unverified, and the interface says so. A typed address is a useful record
     * and it is not evidence of anything — letting the two look alike would make the tick
     * beside a proved one meaningless.
     */
    JsonObject addWalletAddress(String walletId, String body) {
        String address = string(body, "address").trim();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("Type an address first.");
        }
        return WebJson.wallet(await(accountWallets.bind(account(string(body, "accountId")),
                walletId, ChainPlatform.fromStorageName(string(body, "platform")),
                address, false)));
    }

    /**
     * Hands the page a message to sign for one address, in one account.
     *
     * <p>The console can do this itself: unlike a Minecraft screen, {@code /wallets/} is
     * already a page in the player's browser, so it reaches {@code window.ethereum} without
     * anything opening a second server. The checking is the same
     * {@link WalletChallenges} the in-game bridge uses.
     */
    JsonObject walletChallenge(Map<String, String> query) {
        String accountId = account(query.get("accountId"));
        String address = query.getOrDefault("address", "").trim();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("A challenge has to name the address it proves.");
        }

        WalletChallenges.Challenge challenge = challenges.issue(accountId, address);
        JsonObject body = new JsonObject();
        body.addProperty("nonce", challenge.nonce());
        body.addProperty("message", challenge.message());
        return body;
    }

    /**
     * Checks a signature and files the address it proves.
     *
     * <p>The address the page claims is never taken at its word — it is compared against the
     * address recovered from the signature. It lands in the account named in the message the
     * player signed, and in no other: see {@link AccountWallets}.
     */
    JsonObject verifyWallet(String body) {
        String accountId = account(string(body, "accountId"));
        WalletBinding proved = challenges.redeem(
                        string(body, "nonce"), string(body, "address"),
                        string(body, "signature"), string(body, "chainId"))
                // Deliberately not saying which of the three was wrong: to anyone probing
                // this route the distinction is the only useful thing it could give.
                .orElseThrow(() -> new IllegalArgumentException(
                        "That signature does not match the address it arrived with."));

        List<Wallet> bound = await(accountWallets.bindAll(accountId, List.of(proved)));

        JsonObject result = new JsonObject();
        result.addProperty("address", proved.address());
        result.addProperty("platform", proved.platform().displayName());
        result.add("wallet", bound.isEmpty() ? null : WebJson.wallet(bound.getFirst()));
        return result;
    }

    private static String account(String value) {
        String accountId = value == null ? "" : value.trim();
        if (accountId.isEmpty()) {
            throw new IllegalArgumentException("Name the account this wallet belongs to.");
        }
        return accountId;
    }

    /**
     * A string member, or empty if the body does not carry one.
     *
     * <p>A present but blank value comes back as a blank string, not as empty — the caller
     * needs to tell "the player cleared this box" from "this request said nothing about it".
     */
    private static Optional<String> member(String body, String name) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
                return Optional.empty();
            }
            return Optional.of(root.get(name).getAsString());
        } catch (JsonSyntaxException | IllegalStateException e) {
            return Optional.empty();
        }
    }

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
