package io.github.mcagents.clientfabric.api.model;

import java.util.List;

/**
 * The models the interface offers, and the ones it falls back to.
 *
 * <p><strong>These lists are suggestions, not a whitelist.</strong> OpenRouter adds and
 * retires models far faster than this mod ships, so the interface offers these and also
 * accepts anything the player types. Nothing in the mod validates a model id against these
 * lists — if the player picks something OpenRouter does not serve, OpenRouter says so, and
 * that message is what the player sees.
 *
 * <p>Keeping the lists here rather than in the screen means the same values are available
 * to anything that needs a sensible default without dragging in Minecraft.
 */
public final class ModelCatalog {

    /** Chat models offered in the dropdown, in the order they are shown. */
    public static final List<String> SUGGESTED_CHAT_MODELS = List.of(
            "deepseek/deepseek-v4-flash-latest",
            "deepseek/deepseek-v4-pro",
            "openai/gpt-4o-mini");

    /** Embedding models offered in the dropdown, in the order they are shown. */
    public static final List<String> SUGGESTED_EMBEDDING_MODELS = List.of(
            "mistralai/mistral-embed-2312",
            "google/gemini-embedding-2",
            "google/gemini-embedding-2-preview",
            "qwen/qwen3-embedding-8b");

    /** Used when the player has chosen nothing, or has cleared their choice. */
    public static final String DEFAULT_CHAT_MODEL = "deepseek/deepseek-v4-flash-latest";

    /** Used when the player has chosen nothing, or has cleared their choice. */
    public static final String DEFAULT_EMBEDDING_MODEL = "mistralai/mistral-embed-2312";

    private ModelCatalog() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whether {@code model} is one of the suggested ids.
     *
     * <p>For presentation only — to show which row in the dropdown is the current one. It
     * is never a validity check, and a model that is not on a list is not rejected.
     */
    public static boolean isSuggestedChatModel(String model) {
        return SUGGESTED_CHAT_MODELS.contains(model);
    }

    /** Presentation counterpart of {@link #isSuggestedChatModel(String)}. */
    public static boolean isSuggestedEmbeddingModel(String model) {
        return SUGGESTED_EMBEDDING_MODELS.contains(model);
    }
}
