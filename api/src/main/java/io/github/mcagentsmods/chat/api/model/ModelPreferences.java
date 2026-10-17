package io.github.mcagentsmods.chat.api.model;

import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelName;
import io.github.mcagentsmods.core.api.model.ModelPlatform;

import java.util.Objects;
import java.util.Optional;

/**
 * The models the player has chosen: one to chat with, one to embed with, each with the provider
 * it is called through, and whether to embed at all.
 *
 * <p>The two choices are core's global model, which the Models page edits and every task with
 * no model of its own falls back to; the switch is chat's own. Either model id may be one the
 * player typed rather than one the mod suggested. A blank model is not an error and is not
 * stored as blank: it resolves to the catalog default, because "no model" is not a state the
 * mod can operate in.
 *
 * <h2>Why the switch lives here</h2>
 *
 * <p>{@code embeddingEnabled} is a model preference rather than a separate setting because
 * it answers the same question the embedding model does: what happens to a question before
 * it is sent. Keeping the two together means every caller that already reads a model choice
 * reads the switch in the same load, and neither can be applied without the other.
 *
 * <p>Turning it off does not clear {@link #embedding()}. The choice is the player's and is
 * theirs to keep; switching retrieval off and back on should return them to the model they
 * picked, not to the default.
 *
 * @param chat             the model questions are answered with, and its provider
 * @param embedding        the model text is embedded with, and its provider
 * @param embeddingEnabled whether questions are embedded and Knowledge Packs retrieved
 */
public record ModelPreferences(ModelChoice chat, ModelChoice embedding, boolean embeddingEnabled) {

    /** What retrieval does when the player has never touched the switch. */
    public static final boolean DEFAULT_EMBEDDING_ENABLED = true;

    public ModelPreferences {
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(embedding, "embedding");
    }

    /** What a player who has never opened the Models page gets: both through OpenRouter. */
    public static ModelPreferences defaults() {
        return of(ModelChoice.throughOpenRouter(ModelCatalog.DEFAULT_CHAT_MODEL),
                ModelChoice.throughOpenRouter(ModelCatalog.DEFAULT_EMBEDDING_MODEL),
                DEFAULT_EMBEDDING_ENABLED);
    }

    /**
     * Trims and lowercases both model ids, substitutes the catalog default for anything blank,
     * replaces an id the provider has retired, and adds the provider's platform to an
     * unqualified id at a direct provider.
     *
     * <p>Applied on the way in and on the way out of storage, so a stray space from a paste
     * cannot become a model id that a provider rejects for a reason the player cannot see, and
     * so a player who saved a model id that has since stopped working is moved off it rather
     * than left with a mod that fails every request until they think to look.
     */
    public static ModelPreferences of(ModelChoice chat, ModelChoice embedding, boolean embeddingEnabled) {
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(embedding, "embedding");
        return new ModelPreferences(
                normalise(chat.provider(), chat.model(), ModelCatalog.DEFAULT_CHAT_MODEL),
                normalise(embedding.provider(), embedding.model(), ModelCatalog.DEFAULT_EMBEDDING_MODEL),
                embeddingEnabled);
    }

    /**
     * The same, from model ids alone, both called through OpenRouter.
     *
     * <p>What every preference was before providers were chosen.
     */
    public static ModelPreferences of(String chatModel, String embeddingModel, boolean embeddingEnabled) {
        return new ModelPreferences(
                normalise(ModelPlatform.OPENROUTER, chatModel, ModelCatalog.DEFAULT_CHAT_MODEL),
                normalise(ModelPlatform.OPENROUTER, embeddingModel, ModelCatalog.DEFAULT_EMBEDDING_MODEL),
                embeddingEnabled);
    }

    /*
     * There is deliberately no two-argument of(chat, embedding).
     *
     * An overload that filled in the switch would read like a convenience and behave like a
     * reset: every caller that saves a model selection would quietly turn retrieval back on
     * for a player who had turned it off.
     */

    private static ModelChoice normalise(String provider, String model, String fallback) {
        String id = model == null || model.isBlank() ? fallback : model;
        // resolve() normalises before it looks a retirement up, so this is already lowercase.
        return ModelChoice.qualified(provider, ModelCatalog.resolve(id));
    }

    /** The stored chat model id. */
    public String chatModel() {
        return chat.model();
    }

    /** The stored embedding model id. */
    public String embeddingModel() {
        return embedding.model();
    }

    /**
     * Which platform serves the chat model, if the id says.
     *
     * <p>Empty for an unqualified id, which the mod does not invent a platform for. This is the
     * model's platform, not the provider it is called through: see {@link #chat()}.
     */
    public Optional<String> chatPlatform() {
        return ModelName.parse(chat.model()).map(ModelName::platform);
    }

    /** Which platform serves the embedding model, if the id says. */
    public Optional<String> embeddingPlatform() {
        return ModelName.parse(embedding.model()).map(ModelName::platform);
    }

    /** This selection with another chat model and provider. */
    public ModelPreferences withChat(ModelChoice choice) {
        return of(choice, embedding, embeddingEnabled);
    }

    /** This selection with another embedding model and provider. */
    public ModelPreferences withEmbedding(ModelChoice choice) {
        return of(chat, choice, embeddingEnabled);
    }

    /** This selection with a different chat model, at the same provider. */
    public ModelPreferences withChatModel(String model) {
        return of(new ModelChoice(chat.provider(), blankAsDefault(model, ModelCatalog.DEFAULT_CHAT_MODEL)),
                embedding, embeddingEnabled);
    }

    /** This selection with a different embedding model, at the same provider. */
    public ModelPreferences withEmbeddingModel(String model) {
        return of(chat, new ModelChoice(embedding.provider(),
                blankAsDefault(model, ModelCatalog.DEFAULT_EMBEDDING_MODEL)), embeddingEnabled);
    }

    /** This selection with retrieval switched on or off. */
    public ModelPreferences withEmbeddingEnabled(boolean enabled) {
        return of(chat, embedding, enabled);
    }

    private static String blankAsDefault(String model, String fallback) {
        return model == null || model.isBlank() ? fallback : model;
    }
}
