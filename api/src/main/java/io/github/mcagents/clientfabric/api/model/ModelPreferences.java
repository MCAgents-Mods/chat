package io.github.mcagents.clientfabric.api.model;

import java.util.Objects;

/**
 * The models the player has chosen: one to chat with, one to embed with, and whether to
 * embed at all.
 *
 * <p>Either id may be one the player typed rather than one the mod suggested. A blank
 * choice is not an error and is not stored as blank — it resolves to the catalog default,
 * because "no model" is not a state the mod can operate in.
 *
 * <h2>Why the switch lives here</h2>
 *
 * <p>{@code embeddingEnabled} is a model preference rather than a separate setting because
 * it answers the same question the embedding model does: what happens to a question before
 * it is sent. Keeping the two together means every caller that already reads a model choice
 * reads the switch in the same load, and neither can be applied without the other.
 *
 * <p>Turning it off does not clear {@link #embeddingModel()}. The id is the player's choice
 * and is theirs to keep; switching retrieval off and back on should return them to the model
 * they picked, not to the default.
 *
 * @param chatModel        OpenRouter model id used for chat completions
 * @param embeddingModel   OpenRouter model id used for embeddings
 * @param embeddingEnabled whether questions are embedded and Knowledge Packs retrieved
 */
public record ModelPreferences(String chatModel, String embeddingModel, boolean embeddingEnabled) {

    /** What retrieval does when the player has never touched the switch. */
    public static final boolean DEFAULT_EMBEDDING_ENABLED = true;

    public ModelPreferences {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(embeddingModel, "embeddingModel");
    }

    /** What a player who has never opened the settings screen gets. */
    public static ModelPreferences defaults() {
        return new ModelPreferences(
                ModelCatalog.DEFAULT_CHAT_MODEL,
                ModelCatalog.DEFAULT_EMBEDDING_MODEL,
                DEFAULT_EMBEDDING_ENABLED);
    }

    /**
     * Trims both ids, substitutes the catalog default for anything blank, and replaces an
     * id the provider has retired.
     *
     * <p>Applied on the way in and on the way out of storage, so a stray space from a paste
     * cannot become a model id that OpenRouter rejects for a reason the player cannot see —
     * and so a player who saved a model id that has since stopped working is moved off it
     * rather than left with a mod that fails every request until they think to look.
     */
    public static ModelPreferences of(String chatModel, String embeddingModel,
                                      boolean embeddingEnabled) {
        return new ModelPreferences(
                normalise(chatModel, ModelCatalog.DEFAULT_CHAT_MODEL),
                normalise(embeddingModel, ModelCatalog.DEFAULT_EMBEDDING_MODEL),
                embeddingEnabled);
    }

    /*
     * There is deliberately no two-argument of(chat, embedding).
     *
     * An overload that filled in the switch would read like a convenience and behave like a
     * reset: every caller that saves a model selection would quietly turn retrieval back on
     * for a player who had turned it off. Making the switch impossible to omit is what stops
     * that, and it costs one argument at each of the four call sites.
     */

    private static String normalise(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return ModelCatalog.resolve(value);
    }

    /** This selection with a different chat model. */
    public ModelPreferences withChatModel(String model) {
        return of(model, embeddingModel, embeddingEnabled);
    }

    /** This selection with a different embedding model. */
    public ModelPreferences withEmbeddingModel(String model) {
        return of(chatModel, model, embeddingEnabled);
    }

    /** This selection with retrieval switched on or off. */
    public ModelPreferences withEmbeddingEnabled(boolean enabled) {
        return of(chatModel, embeddingModel, enabled);
    }
}
