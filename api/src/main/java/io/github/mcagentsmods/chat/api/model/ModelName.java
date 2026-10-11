package io.github.mcagentsmods.chat.api.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A stored model identifier, in the one shape everything reads back: {@code {platform}/{model}},
 * lowercase.
 *
 * <p>The convention comes from the shared agent instruction set — {@code model-naming-convention}
 * — and it exists so that one model has exactly one name whichever route reached it. The format
 * is OpenRouter's own, deliberately: an id stored this way is passed straight through to the
 * gateway, and the same two halves rebuild the request a platform's own API expects.
 *
 * <h2>What each half is for</h2>
 *
 * <ul>
 *   <li>{@link #platform()} says <em>who serves the model</em>. It is what picks the API key and
 *       the base URL when the mod calls a platform directly — see
 *       {@code api.agent.ProviderRoute}.</li>
 *   <li>{@link #model()} is the platform's own id, which is what a direct call names in its
 *       request body. Through OpenRouter the whole {@link #qualified()} string is named
 *       instead.</li>
 * </ul>
 *
 * <h2>Unqualified ids are kept, not invented into</h2>
 *
 * <p>{@link #parse(String)} returns empty for an id with no platform segment, and
 * {@link #normalise(String)} then leaves that id alone apart from trimming and lowercasing.
 * The mod never rejects a model id the player typed — {@code .agents/api/openrouter.md} — and
 * it cannot guess which platform serves one either: {@code openrouter/gpt-4o-mini} is not a
 * shorter spelling of {@code gpt-4o-mini}, it is a model that does not exist. An unqualified id
 * therefore keeps working exactly as it did, through OpenRouter, and only a qualified one can
 * take a direct route.
 *
 * @param platform who serves the model, lowercase, never blank and never containing a slash
 * @param model    the platform's own id for it, lowercase, never blank
 */
public record ModelName(String platform, String model) {

    /** What separates the two halves. One, always present. */
    public static final String SEPARATOR = "/";

    public ModelName {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(model, "model");
        if (platform.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("A model name needs both a platform and a model");
        }
        if (platform.contains(SEPARATOR)) {
            throw new IllegalArgumentException("A platform is one segment and cannot contain '/'");
        }
    }

    /**
     * Builds a name from the two values an integration already holds, lowercasing both.
     *
     * <p>This is the only way a name should be produced. A literal typed into a direct
     * integration is the point at which the direct route and the gateway route start
     * disagreeing about what one model is called.
     *
     * @param platform      the provider, one segment
     * @param platformModel the provider's own id, with no platform prefix
     * @throws IllegalArgumentException if either half is blank, or if {@code platformModel}
     *                                  already carries the platform prefix — doubling it
     *                                  silently is worse than refusing it
     */
    public static ModelName of(String platform, String platformModel) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(platformModel, "platformModel");

        String owner = platform.trim().toLowerCase(java.util.Locale.ROOT);
        String id = platformModel.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith(owner + SEPARATOR)) {
            throw new IllegalArgumentException(
                    "A model id must not already carry its platform prefix");
        }
        return new ModelName(owner, id);
    }

    /**
     * Reads a stored identifier back, or empty if it carries no platform segment.
     *
     * <p>Split at the <em>first</em> slash: a platform is one segment, and a model id may
     * itself contain slashes or a suffix such as {@code :free}.
     */
    public static Optional<ModelName> parse(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        String trimmed = stored.trim().toLowerCase(java.util.Locale.ROOT);
        int slash = trimmed.indexOf(SEPARATOR);
        if (slash <= 0 || slash == trimmed.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new ModelName(trimmed.substring(0, slash), trimmed.substring(slash + 1)));
    }

    /**
     * What to write for {@code raw}: trimmed and lowercased, whether or not it is qualified.
     *
     * <p>Applied <strong>before</strong> the write, never on the read and never in a
     * comparison, so the stored value can never hold two spellings of one model.
     */
    public static String normalise(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether {@code stored} carries a platform segment and so can take a direct route. */
    public static boolean isQualified(String stored) {
        return parse(stored).isPresent();
    }

    /** The full identifier, which is what is stored and what OpenRouter is sent. */
    public String qualified() {
        return platform + SEPARATOR + model;
    }

    /** The platform's own id, which is what a direct call to that platform names. */
    public String bare() {
        return model;
    }

    @Override
    public String toString() {
        return qualified();
    }
}
