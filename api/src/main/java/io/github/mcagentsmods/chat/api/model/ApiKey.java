package io.github.mcagentsmods.chat.api.model;

import io.github.mcagentsmods.core.api.model.ModelName;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import java.time.Instant;
import java.util.Objects;

/**
 * An API key the player has stored, and the platform it authenticates to.
 *
 * <p>Keys are held on the player's own machine and are sent to exactly one place: the platform
 * named by {@link #platform()}. A key that stops working is deactivated rather than deleted, so
 * the player can see what happened and the mod can move on to the next one.
 *
 * <h2>The platform, and what it decides</h2>
 *
 * <p>A key's platform is the {@code platform} half of a {@link ModelName} — {@code openrouter}
 * for a gateway key, {@code openai} for a key that speaks to OpenAI's own API. It decides which
 * requests may use the key: a request routed directly to a platform uses a key stored for that
 * platform and no other, because a gateway key is not valid at the platform's own door and
 * sending it there would leak it to somewhere it does not belong.
 *
 * <p><strong>{@link ModelPlatform#OPENROUTER} is the fallback.</strong> A key stored before this
 * column existed, or added without naming a platform, is an OpenRouter key — which is what every
 * key was.
 *
 * <h2>The value is the plain key, in memory only</h2>
 *
 * <p>{@link #value()} is the key as the player pasted it. What is written to the database is the
 * encrypted form when the mod has been given an encryption key; decryption happens at the
 * storage boundary, so nothing above it changes. See {@code .agents/security/api-keys.md}.
 *
 * @param value     the key itself, as the player entered it
 * @param platform  which platform it authenticates to, lowercase
 * @param active    whether the mod may still use it
 * @param createdAt when it was added
 */
public record ApiKey(String value, String platform, boolean active, Instant createdAt) {

    public ApiKey {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An API key cannot be blank");
        }
        // Normalised here as well as at the write, so a key built in memory and a key read
        // back from the database cannot disagree about what its platform is called.
        platform = ModelPlatform.normalise(platform);
    }

    /**
     * A key for the gateway — what every key was before platforms were recorded.
     *
     * <p>Kept so that a caller who has no platform to give does not have to invent one, and so
     * the fallback is spelled the same way everywhere.
     */
    public ApiKey(String value, boolean active, Instant createdAt) {
        this(value, ModelPlatform.OPENROUTER, active, createdAt);
    }

    /** Whether this key is for the gateway rather than for a platform called directly. */
    public boolean isForOpenRouter() {
        return ModelPlatform.isOpenRouter(platform);
    }

    /**
     * A short, safe rendering for logs and the interface — never the whole key.
     *
     * <p>Anything that displays or records a key uses this. The full value leaves the mod
     * only in an Authorization header.
     */
    public String masked() {
        int visible = 4;
        if (value.length() <= visible * 2) {
            return "*".repeat(value.length());
        }
        return value.substring(0, visible) + "…" + value.substring(value.length() - visible);
    }

    @Override
    public String toString() {
        return "ApiKey[" + masked() + ", platform=" + platform + ", active=" + active + "]";
    }
}
