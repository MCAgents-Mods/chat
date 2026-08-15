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

    /**
     * Chat models offered in the dropdown, in the order they are shown.
     *
     * <p>{@code deepseek/deepseek-v4-flash-latest} was here and has been removed: the
     * {@code -latest} alias is not served, so it failed every request made with it — and
     * because it was also the default, it failed them for every player who had never opened
     * the model screen. A pinned id is offered instead. Do not reintroduce a floating alias
     * without checking the provider serves it.
     */
    public static final List<String> SUGGESTED_CHAT_MODELS = List.of(
            "deepseek/deepseek-v4-flash",
            "deepseek/deepseek-v4-pro",
            "openai/gpt-4o-mini");

    /** Embedding models offered in the dropdown, in the order they are shown. */
    public static final List<String> SUGGESTED_EMBEDDING_MODELS = List.of(
            "openai/text-embedding-3-small",
            "mistralai/mistral-embed-2312",
            "google/gemini-embedding-2",
            "qwen/qwen3-embedding-8b");

    /** Used when the player has chosen nothing, or has cleared their choice. */
    public static final String DEFAULT_CHAT_MODEL = "deepseek/deepseek-v4-flash";

    /**
     * Used when the player has chosen nothing, or has cleared their choice.
     *
     * <p>Changing this changes which vector space new embeddings live in. Packs embedded
     * with a different model are not comparable with packs embedded with this one, so a
     * change here is a re-embedding of the library rather than a preference.
     */
    public static final String DEFAULT_EMBEDDING_MODEL = "openai/text-embedding-3-small";

    /**
     * Model ids that no longer work, mapped to what replaces them.
     *
     * <p>Removing an id from the suggested list stops it being offered; it does not stop it
     * being <em>used</em>, because a player who once selected it has it saved. This is what
     * closes that gap. It is deliberately narrow — an id belongs here only when the provider
     * does not serve it at all, never because something newer exists. A player's choice is
     * theirs to keep; a choice that cannot work is not a choice.
     */
    private static final java.util.Map<String, String> RETIRED = java.util.Map.of(
            "deepseek/deepseek-v4-flash-latest", DEFAULT_CHAT_MODEL);

    private ModelCatalog() {
        throw new AssertionError("No instances.");
    }

    /**
     * The id to use in place of {@code model}, which is {@code model} itself unless it has
     * been retired.
     */
    public static String resolve(String model) {
        return model == null ? null : RETIRED.getOrDefault(model.trim(), model.trim());
    }

    /** Whether this id is one the mod will silently replace. */
    public static boolean isRetired(String model) {
        return model != null && RETIRED.containsKey(model.trim());
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
