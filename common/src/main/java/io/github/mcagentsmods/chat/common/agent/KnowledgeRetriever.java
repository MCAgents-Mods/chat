package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.model.ContentQuery;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.model.ModelClient;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Picks the Knowledge Packs worth putting in front of a question.
 *
 * <p>The question is embedded with the player's embedding model, every stored pack that
 * carries an embedding is scored against it by cosine similarity, and the best few are
 * returned. Everything except the one embedding call is local.
 *
 * <h2>The switch</h2>
 *
 * <p>When {@link ModelPreferences#embeddingEnabled()} is false this does nothing at all —
 * no embedding call, no read of the pack table, no provider request. That is the point of
 * the switch: retrieval costs an extra provider call per question, and a player who does
 * not want to pay for it should not be charged for it silently.
 *
 * <h2>Failing quietly is the correct behaviour here</h2>
 *
 * <p>Retrieval is an improvement to an answer, not a precondition for one. If the embedding
 * call fails, if a pack's vector is unreadable, or if nothing scores well enough, the result
 * is an empty list and the question is asked without extra context. <strong>A player must
 * always be able to chat.</strong> The one thing this must never do is turn a working
 * conversation into an error because a pack could not be ranked.
 */
public final class KnowledgeRetriever {

    private static final Logger LOGGER = System.getLogger(KnowledgeRetriever.class.getName());

    /**
     * How many packs may accompany one question.
     *
     * <p>Small on purpose. Every pack is tokens the player pays for on every turn it is
     * attached, and a model given ten loosely related documents answers worse than one given
     * two relevant ones.
     */
    static final int MAX_PACKS = 3;

    /**
     * The floor a pack must clear to be attached.
     *
     * <p>This is a guard against nonsense, not a precision knob. Without it the best of a
     * library of unrelated packs is still the best, and a question about redstone would be
     * answered with whatever pack happened to score highest. It is set low because a missed
     * relevant pack is invisible to the player while an attached irrelevant one is not.
     */
    static final double MINIMUM_SIMILARITY = 0.2;

    /**
     * The most pack text one question may carry.
     *
     * <p>A cap in characters rather than tokens because the mod cannot count a provider's
     * tokens without asking it. It exists so that a single very large pack cannot crowd out
     * the conversation it was meant to support.
     */
    static final int CHARACTER_BUDGET = 6_000;

    private final LocalDatabase database;
    private final ModelClient client;

    public KnowledgeRetriever(LocalDatabase database, ModelClient client) {
        this.database = Objects.requireNonNull(database, "database");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * The packs to put in front of {@code question}, best first.
     *
     * @param preferences the player's models and their retrieval switch
     * @param question    what the player just asked
     * @return the chosen packs, or an empty list — never a failed future
     */
    public CompletableFuture<List<KnowledgeContent>> retrieve(ModelPreferences preferences,
                                                              String question) {
        Objects.requireNonNull(preferences, "preferences");
        if (!preferences.embeddingEnabled() || question == null || question.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return client.embed(preferences.embeddingModel(), question)
                .thenCompose(this::scoreAgainstStoredPacks)
                .exceptionally(failure -> {
                    // Deliberately swallowed. The player asked a question; they get an
                    // answer without the extra context rather than an error about it.
                    LOGGER.log(Level.DEBUG, "Retrieval was skipped for this question", failure);
                    return List.of();
                });
    }

    private CompletableFuture<List<KnowledgeContent>> scoreAgainstStoredPacks(float[] question) {
        // Whole packs, because ranking needs the vectors and attaching needs the text. This
        // is the one read in the mod that deliberately pulls bodies for every row; the
        // browser list uses summaries for exactly the reason this cannot.
        return database.contents().find(ContentQuery.all())
                .thenApply(packs -> choose(question, packs));
    }

    private static List<KnowledgeContent> choose(float[] question, List<KnowledgeContent> packs) {
        List<Scored> ranked = new ArrayList<>();
        int incomparable = 0;

        for (KnowledgeContent pack : packs) {
            if (!pack.hasEmbedding()) {
                continue;
            }
            double score = Vectors.parse(pack.embeddedData())
                    .map(vector -> Vectors.cosine(question, vector))
                    .orElse(Double.NaN);
            if (Double.isNaN(score)) {
                incomparable++;
                continue;
            }
            if (score >= MINIMUM_SIMILARITY) {
                ranked.add(new Scored(pack, score));
            }
        }

        if (incomparable > 0) {
            // Almost always one thing: the library was embedded with one model and is being
            // searched with another. Worth a line, because the symptom the player sees is
            // "the agent stopped knowing things" and nothing else would explain it.
            LOGGER.log(Level.INFO,
                    "{0} pack(s) could not be compared with this embedding model; "
                            + "re-embed the library if retrieval has gone quiet", incomparable);
        }

        ranked.sort(Comparator.comparingDouble(Scored::score).reversed()
                // A total ordering: two packs can score identically, and an unstable choice
                // between them would change the prompt from one identical question to the
                // next for no reason the player could see.
                .thenComparing(scored -> scored.pack().id()));

        List<KnowledgeContent> chosen = new ArrayList<>(MAX_PACKS);
        int budget = CHARACTER_BUDGET;
        for (Scored scored : ranked) {
            if (chosen.size() == MAX_PACKS) {
                break;
            }
            int cost = scored.pack().rawContent() == null ? 0 : scored.pack().rawContent().length();
            if (cost > budget) {
                // Skipped rather than truncated: half a pack can contradict its own second
                // half, and a rule cut before its exception is worse than no rule.
                continue;
            }
            budget -= cost;
            chosen.add(scored.pack());
        }
        return List.copyOf(chosen);
    }

    private record Scored(KnowledgeContent pack, double score) {
    }
}
