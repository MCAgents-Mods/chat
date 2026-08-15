package io.github.mcagents.clientfabric.api.model;

import java.util.Objects;

/**
 * The models the player has chosen: one to chat with, one to embed with.
 *
 * <p>Either may be an id the player typed rather than one the mod suggested. A blank
 * choice is not an error and is not stored as blank — it resolves to the catalog default,
 * because "no model" is not a state the mod can operate in.
 *
 * @param chatModel      OpenRouter model id used for chat completions
 * @param embeddingModel OpenRouter model id used for embeddings
 */
public record ModelPreferences(String chatModel, String embeddingModel) {

    public ModelPreferences {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(embeddingModel, "embeddingModel");
    }

    /** What a player who has never opened the settings screen gets. */
    public static ModelPreferences defaults() {
        return new ModelPreferences(
                ModelCatalog.DEFAULT_CHAT_MODEL,
                ModelCatalog.DEFAULT_EMBEDDING_MODEL);
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
    public static ModelPreferences of(String chatModel, String embeddingModel) {
        return new ModelPreferences(
                normalise(chatModel, ModelCatalog.DEFAULT_CHAT_MODEL),
                normalise(embeddingModel, ModelCatalog.DEFAULT_EMBEDDING_MODEL));
    }

    private static String normalise(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return ModelCatalog.resolve(value);
    }

    /** This selection with a different chat model. */
    public ModelPreferences withChatModel(String model) {
        return of(model, embeddingModel);
    }

    /** This selection with a different embedding model. */
    public ModelPreferences withEmbeddingModel(String model) {
        return of(chatModel, model);
    }
}
