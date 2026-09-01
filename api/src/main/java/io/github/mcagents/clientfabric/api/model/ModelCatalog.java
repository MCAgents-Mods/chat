package io.github.mcagents.clientfabric.api.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
     *
     * <p>Normalised with {@link ModelName#normalise(String)} first, so a retired id survives
     * being pasted back with different capitalisation. One model, one spelling.
     */
    public static String resolve(String model) {
        String normalised = ModelName.normalise(model);
        return normalised == null ? null : RETIRED.getOrDefault(normalised, normalised);
    }

    /** Whether this id is one the mod will silently replace. */
    public static boolean isRetired(String model) {
        return model != null && RETIRED.containsKey(ModelName.normalise(model));
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

    /**
     * The suggested chat models {@code platform} serves, in the order they are offered.
     *
     * <p>Empty for a platform the lists say nothing about, which is not a statement that it
     * serves nothing — the lists are suggestions. It is the difference between "here is one to
     * start from" and "there is nothing to suggest", and {@link #chatModelOn} needs to tell
     * those apart.
     */
    public static List<String> suggestedChatModelsFor(String platform) {
        String owner = ModelPlatform.normalise(platform);
        return SUGGESTED_CHAT_MODELS.stream()
                .filter(id -> ModelName.parse(id)
                        .filter(name -> name.platform().equals(owner)).isPresent())
                .toList();
    }

    /**
     * The chat model to use once the player has chosen {@code platform} to answer them.
     *
     * <p>Choosing a platform is not the same as choosing a model, and the two cannot be kept
     * independent: a stored id names both. So the choice is resolved in one place, by three
     * rules applied in order.
     *
     * <ol>
     *   <li><strong>The platform already serves this model</strong> — nothing changes. Picking
     *       the platform you are already on is not an instruction to switch models.</li>
     *   <li><strong>The mod suggests a model for that platform, and the current one is not
     *       among them</strong> — its first suggestion is used. Carrying
     *       {@code deepseek-v4-flash} over to OpenAI would name a model OpenAI does not serve,
     *       and the player asked for an answer, not for a request that fails.</li>
     *   <li><strong>Otherwise the model half is carried over.</strong> For a platform the mod
     *       suggests nothing for there is no better guess than the id the player chose, and
     *       inventing one would be the mod overruling them. If that platform does not serve it,
     *       the platform says so — which is the rule this class has always followed.</li>
     * </ol>
     *
     * <p>The result is normalised and retirement-resolved like any other stored id.
     *
     * @param platform     who should answer; blank means the gateway, which serves no model of
     *                     its own, so the current id is returned untouched
     * @param currentModel the stored chat model, qualified or not
     */
    public static String chatModelOn(String platform, String currentModel) {
        String owner = ModelPlatform.normalise(platform);
        String current = resolve(currentModel);
        if (current == null || current.isBlank() || ModelPlatform.isOpenRouter(owner)) {
            // The gateway is a route, never a model's platform: openrouter/gpt-4o-mini is not
            // a longer spelling of gpt-4o-mini, it is a model nobody serves.
            return current;
        }

        Optional<ModelName> parsed = ModelName.parse(current);
        if (parsed.filter(name -> name.platform().equals(owner)).isPresent()) {
            return current;
        }

        String bare = parsed.map(ModelName::bare).orElse(current);
        List<String> suggestions = suggestedChatModelsFor(owner);
        boolean suggested = suggestions.stream()
                .anyMatch(id -> ModelName.parse(id)
                        .filter(name -> name.bare().equals(bare)).isPresent());
        if (!suggestions.isEmpty() && !suggested) {
            return suggestions.get(0);
        }
        return ModelName.of(owner, bare).qualified();
    }

    /**
     * Every platform worth offering as a choice, in the order they should be shown.
     *
     * <p>Three sources, in this order: the gateway and the platforms this mod's suggested
     * models name, the platforms it ships an address for, and the ones the player is already
     * using. Duplicates collapse and the first appearance wins the position.
     *
     * <p><strong>This is the one list both interfaces offer.</strong> The console built it
     * inline and the game screen would have had to build it again; two lists computed in two
     * places from the same three sources stay equal exactly until one of them is edited. The
     * same reasoning already keeps the suggested model ids in this class rather than in the
     * screens that show them.
     *
     * <p>Like every list here it is a suggestion. A platform absent from it is not rejected —
     * both interfaces let one be typed, and it is stored exactly as typed.
     *
     * @param alreadyInUse platforms the player holds keys for, or is otherwise already on;
     *                     normalised here, and blanks ignored
     */
    public static List<String> offeredPlatforms(Collection<String> alreadyInUse) {
        Objects.requireNonNull(alreadyInUse, "alreadyInUse");

        LinkedHashSet<String> offered = new LinkedHashSet<>(suggestedPlatforms());
        offered.addAll(ModelPlatform.knownDirectBaseUrls().keySet());
        for (String platform : alreadyInUse) {
            if (platform != null && !platform.isBlank()) {
                offered.add(ModelPlatform.normalise(platform));
            }
        }
        return List.copyOf(offered);
    }

    /**
     * The same list with the gateway removed, for asking <em>who serves a model</em>.
     *
     * <p>OpenRouter answers a different question from the rest of the list: it is the route
     * everything falls back to, not a platform that serves models of its own. Offering it
     * where a model's platform is chosen would produce {@code openrouter/gpt-4o-mini}, which
     * {@link ModelName} has always said is a model nobody serves rather than a longer spelling
     * of {@code gpt-4o-mini}.
     */
    public static List<String> offeredModelPlatforms(Collection<String> alreadyInUse) {
        return offeredPlatforms(alreadyInUse).stream()
                .filter(platform -> !ModelPlatform.isOpenRouter(platform))
                .toList();
    }

    /**
     * Every platform the suggested lists name, in the order they first appear.
     *
     * <p>Offered where a player is asked which platform a key belongs to: the platforms they
     * are most likely to hold a key for are the ones whose models the mod already suggests.
     * Like the lists themselves this is a suggestion and never a whitelist — a key may be
     * stored for any platform at all.
     */
    public static List<String> suggestedPlatforms() {
        java.util.LinkedHashSet<String> platforms = new java.util.LinkedHashSet<>();
        platforms.add(ModelPlatform.OPENROUTER);
        java.util.stream.Stream.concat(
                        SUGGESTED_CHAT_MODELS.stream(), SUGGESTED_EMBEDDING_MODELS.stream())
                .map(ModelName::parse)
                .flatMap(java.util.Optional::stream)
                .map(ModelName::platform)
                .forEach(platforms::add);
        return List.copyOf(platforms);
    }
}
