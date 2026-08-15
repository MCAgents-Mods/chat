package io.github.mcagents.clientfabric.website;

import io.github.mcagents.clientfabric.api.model.ApiKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * A name for a key that is not the key.
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>A key's identity in storage <em>is</em> its value — {@code key_value} is the primary
 * key of {@code api_keys}, and {@code ApiKeyRepository#remove} takes that value. So the
 * obvious way to let a browser delete one is to send it the value and take it back on the
 * next request, and that would put the player's OpenRouter key in a page, a fetch, and the
 * browser's memory, for the sake of a delete button.
 *
 * <p>{@code .agents/security/api-keys.md} allows a key to be shown as
 * {@link ApiKey#masked()} and nowhere else. A mask cannot identify a key — two keys from the
 * same provider share a prefix — so the console needs something that names one uniquely
 * without being one.
 *
 * <h2>What a handle is</h2>
 *
 * <p>The SHA-256 of the key, hex, truncated. It is stable, unique in any collection a
 * player will ever hold, and useless to anyone who obtains it: a hash does not authenticate
 * to OpenRouter, and the key cannot be recovered from it.
 *
 * <p>The console resolves a handle back to a key by hashing what it already has in storage.
 * That resolution happens inside the mod, and the value never travels outward at any point.
 */
final class ApiKeyHandles {

    /**
     * Half of a SHA-256, which is 128 bits.
     *
     * <p>Far more than a collision needs — a player holds a handful of keys, not billions —
     * and short enough to sit in a URL without being unreadable.
     */
    private static final int HANDLE_LENGTH = 32;

    private ApiKeyHandles() {
        throw new AssertionError("No instances.");
    }

    /** The handle naming {@code key}. */
    static String of(ApiKey key) {
        return of(key.value());
    }

    static String of(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HANDLE_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JDK; this cannot happen on a machine that runs
            // Minecraft at all.
            throw new IllegalStateException("SHA-256 should be available on this JDK", e);
        }
    }

    /**
     * Finds the key a handle names.
     *
     * <p>Compared with a constant-time equality even though a handle is not a secret: it is
     * one line, and the day someone decides a handle should be harder to enumerate, this is
     * already right.
     */
    static Optional<ApiKey> resolve(List<ApiKey> keys, String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        byte[] wanted = handle.trim().getBytes(StandardCharsets.UTF_8);
        return keys.stream()
                .filter(key -> MessageDigest.isEqual(
                        of(key).getBytes(StandardCharsets.UTF_8), wanted))
                .findFirst();
    }
}
