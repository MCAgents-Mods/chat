package io.github.mcagents.clientfabric.api.storage;

import io.github.mcagents.clientfabric.api.model.ApiKey;
import io.github.mcagents.clientfabric.api.model.ModelPlatform;
import io.github.mcagents.clientfabric.api.model.Page;
import io.github.mcagents.clientfabric.api.model.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The player's API keys, one platform at a time.
 *
 * <p>A player may store several per platform. The mod uses the first active one for whichever
 * platform a request is routed to; when a key is rejected it is deactivated and the next takes
 * over, so a rotation does not interrupt a conversation.
 *
 * <p><strong>A key is only ever offered for its own platform.</strong> An OpenRouter key is not
 * valid at OpenAI's own door and an OpenAI key is not valid at the gateway, so the platform a
 * key was stored under is what decides which requests may use it. Keys stored before platforms
 * were recorded are {@link ModelPlatform#OPENROUTER} keys, which is what every key was.
 *
 * <p>Every method is asynchronous — see {@link LocalDatabase} for why.
 */
public interface ApiKeyRepository {

    /** Every stored key, active or not, oldest first. */
    CompletableFuture<List<ApiKey>> findAll();

    /**
     * One window of the stored keys, oldest first.
     *
     * <p>A player with a handful of keys never needs this; a player who has been topping up
     * for a year does. The ordering is total — {@code created_at} then the key itself, which
     * is the primary key — so a window taken from it is stable.
     */
    CompletableFuture<Page<ApiKey>> findPage(PageRequest page);

    /**
     * The gateway key the mod should use next, or empty if the player has none active.
     *
     * <p>Shorthand for {@link #findNextActive(String)} with {@link ModelPlatform#OPENROUTER},
     * because the gateway is where everything not explicitly routed elsewhere goes.
     */
    default CompletableFuture<Optional<ApiKey>> findNextActive() {
        return findNextActive(ModelPlatform.OPENROUTER);
    }

    /**
     * The key {@code platform} should be called with next, or empty if the player has none
     * active for it.
     *
     * <p>Never falls back to another platform's key. A key that cannot work at the address the
     * request is going to would be rejected there at best, and disclosed to somewhere it does
     * not belong at worst.
     *
     * @param platform the platform id; blank means {@link ModelPlatform#OPENROUTER}
     */
    CompletableFuture<Optional<ApiKey>> findNextActive(String platform);

    /**
     * Stores a gateway key, or reactivates it if it is already present.
     *
     * @param value the key; must not be blank
     * @return the stored key
     */
    default CompletableFuture<ApiKey> add(String value) {
        return add(value, ModelPlatform.OPENROUTER);
    }

    /**
     * Stores a key for one platform, or reactivates it if it is already present.
     *
     * <p>Re-adding a key that is already stored under a different platform moves it: pasting it
     * again with the right platform is how a player corrects a key they filed in the wrong
     * place, and the alternative — a second row holding the same secret — is worse.
     *
     * @param value    the key; must not be blank
     * @param platform which platform it authenticates to; blank means
     *                 {@link ModelPlatform#OPENROUTER}
     * @return the stored key
     */
    CompletableFuture<ApiKey> add(String value, String platform);

    /**
     * Marks a key unusable without discarding it, so the player can see it was rejected.
     *
     * @return whether a key was changed
     */
    CompletableFuture<Boolean> deactivate(String value);

    /**
     * Removes a key entirely.
     *
     * @return whether a key was removed
     */
    CompletableFuture<Boolean> remove(String value);
}
