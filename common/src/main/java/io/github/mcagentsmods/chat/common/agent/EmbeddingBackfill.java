package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.agent.ModelClient;
import io.github.mcagentsmods.chat.api.model.ContentQuery;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Embeds the packs that cannot take part in retrieval yet.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Packs are stored as text with no vector. That is deliberate: an embedding is only
 * comparable inside the space of the model that produced it, so a vector that arrived with a
 * pack would silently force every player onto whichever model produced it, and would be wrong
 * for everyone who chose another. The client embeds what it stores, with the model that player
 * chose.
 *
 * <p>The consequence is that a freshly downloaded pack is inert until this has run over it,
 * which is why there is a button for it rather than a hope.
 *
 * <h2>What counts as needing one</h2>
 *
 * <p>Two cases, and the second is the one nobody expects:
 *
 * <ul>
 *   <li>the pack has no vector, because it was just downloaded;</li>
 *   <li>the player has changed their embedding model since the library was embedded, which
 *       leaves every stored vector in a space their questions no longer live in.</li>
 * </ul>
 *
 * <p>The second is answered from a recorded fact rather than inferred from the vectors. The
 * model that embedded the library is written to {@code settings} when a run completes, so
 * "has the model changed" is a string comparison and costs nothing.
 *
 * <p>It was tempting to infer it instead — take the width most stored vectors share and
 * re-embed the odd ones out. That is exactly backwards after a model change: the stale
 * majority looks authoritative and the correctly embedded minority looks wrong, so the
 * button would push the library further from the space it needs to be in, one press at a
 * time.
 */
public final class EmbeddingBackfill {

    private static final Logger LOGGER = System.getLogger(EmbeddingBackfill.class.getName());

    /** Where the model that embedded the library is recorded. */
    static final String EMBEDDED_WITH_KEY = "knowledge.embedded.model";

    private final LocalDatabase database;
    private final ModelClient client;

    public EmbeddingBackfill(LocalDatabase database, ModelClient client) {
        this.database = Objects.requireNonNull(database, "database");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Counts the packs that would be embedded, without embedding any of them.
     *
     * <p>Free: it reads the local table and one setting. Nothing here calls the provider,
     * so a page can show "4 packs need embedding" without spending anything to learn it.
     */
    public CompletableFuture<Status> status(ModelPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        return candidates(preferences).thenCompose(wanted ->
                database.contents().find(ContentQuery.all())
                        .thenApply(packs -> new Status(packs.size(), wanted.size())));
    }

    /**
     * Embeds every pack that needs it.
     *
     * <p>One at a time, and for the same reason downloads are: this runs while the player is
     * in a game, and a library's worth of parallel provider calls would compete with it for
     * bandwidth and hit a rate limit besides.
     *
     * <p>A pack that fails is left as it was and named in the result. It is not retried here
     * — the player can press the button again, and doing that automatically would turn one
     * bad key into a hundred rejected requests.
     */
    public CompletableFuture<Result> run(ModelPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");

        return candidates(preferences)
                .thenCompose(wanted ->
                        embedEach(preferences.embeddingModel(), wanted, new Result(0, 0, List.of())))
                .thenCompose(result -> record(preferences, result));
    }

    /**
     * The packs to embed, in a stable order.
     *
     * <p>Reads only the local table, so a caller may use it to count without spending
     * anything.
     */
    private CompletableFuture<List<KnowledgeContent>> candidates(ModelPreferences preferences) {
        return database.settings().find(EMBEDDED_WITH_KEY).thenCompose(embeddedWith -> {
            // Absent means nothing has been embedded here yet, which is not a model change.
            boolean modelChanged = embeddedWith.isPresent()
                    && !embeddedWith.get().equals(preferences.embeddingModel());

            return database.contents().find(ContentQuery.all()).thenApply(packs -> {
                List<KnowledgeContent> wanted = new ArrayList<>();
                for (KnowledgeContent pack : packs) {
                    if (!hasText(pack)) {
                        // Nothing to embed, and nothing the player can do about it. Counting
                        // it would leave a number on the page that never goes down.
                        continue;
                    }
                    if (modelChanged || Vectors.parse(pack.embeddedData()).isEmpty()) {
                        wanted.add(pack);
                    }
                }
                wanted.sort((left, right) -> left.id().compareTo(right.id()));
                return List.copyOf(wanted);
            });
        });
    }

    /**
     * Records which model the library is embedded with, but only after a clean run.
     *
     * <p>Written on success only. If a pack failed, the library is a mixture of two spaces,
     * and claiming it is all in the new one would hide the stragglers from the next press of
     * the button — which is the one thing that would fix them.
     */
    private CompletableFuture<Result> record(ModelPreferences preferences, Result result) {
        if (result.failed() > 0) {
            return CompletableFuture.completedFuture(result);
        }
        return database.settings().put(EMBEDDED_WITH_KEY, preferences.embeddingModel())
                .thenApply(ignored -> result);
    }

    private CompletableFuture<Result> embedEach(String model, List<KnowledgeContent> packs,
                                                Result carried) {
        if (packs.isEmpty()) {
            return CompletableFuture.completedFuture(carried);
        }
        KnowledgeContent pack = packs.getFirst();
        List<KnowledgeContent> rest = packs.subList(1, packs.size());

        return client.embed(model, pack.rawContent())
                .thenCompose(vector -> database.contents()
                        .save(withEmbedding(pack, vector))
                        .thenApply(saved -> carried.withEmbedded()))
                .exceptionally(failure -> {
                    LOGGER.log(Level.WARNING, "Could not embed " + pack.id(), failure);
                    return carried.withFailure(pack.id());
                })
                .thenCompose(next -> embedEach(model, rest, next));
    }

    private static boolean hasText(KnowledgeContent pack) {
        return pack.rawContent() != null && !pack.rawContent().isBlank();
    }

    private static KnowledgeContent withEmbedding(KnowledgeContent pack, float[] vector) {
        StringBuilder json = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(vector[i]);
        }
        json.append(']');

        // Everything else is left exactly as stored. This writes a vector and nothing more:
        // the version in particular must not change.
        return new KnowledgeContent(pack.id(), pack.categoryId(), pack.version(),
                pack.premium(), pack.title(), pack.rawContent(), json.toString(),
                pack.createdAt(), pack.updatedAt());
    }

    /**
     * How much of the library can take part in retrieval.
     *
     * @param packs   how many are stored
     * @param needing how many cannot be retrieved against until they are embedded
     */
    public record Status(int packs, int needing) {

        public boolean anyNeeded() {
            return needing > 0;
        }
    }

    /**
     * What a run managed.
     *
     * @param embedded  packs given a usable vector
     * @param failed    packs that could not be, by count
     * @param failedIds which ones, so a page can name them
     */
    public record Result(int embedded, int failed, List<String> failedIds) {

        public Result {
            failedIds = List.copyOf(failedIds);
        }

        Result withEmbedded() {
            return new Result(embedded + 1, failed, failedIds);
        }

        Result withFailure(String id) {
            List<String> extended = new ArrayList<>(failedIds);
            extended.add(id);
            return new Result(embedded, failed + 1, extended);
        }
    }
}
