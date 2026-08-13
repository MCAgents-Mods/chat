package io.github.mcagents.clientfabric.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * An OpenRouter API key the player has stored.
 *
 * <p>Keys are held on the player's own machine and never sent anywhere except OpenRouter.
 * A key that stops working is deactivated rather than deleted, so the player can see what
 * happened and the mod can move on to the next one.
 *
 * @param value     the key itself, and its primary key in storage
 * @param active    whether the mod may still use it
 * @param createdAt when it was added
 */
public record ApiKey(String value, boolean active, Instant createdAt) {

    public ApiKey {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An API key cannot be blank");
        }
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
        return "ApiKey[" + masked() + ", active=" + active + "]";
    }
}
