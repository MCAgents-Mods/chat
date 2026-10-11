package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.storage.ApiKeyRepository;
import io.github.mcagentsmods.chat.api.storage.SettingsRepository;
import io.github.mcagentsmods.core.api.model.ModelName;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.api.model.ProviderRoute;
import io.github.mcagentsmods.core.api.model.ProviderRoutes;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Routes a request from what the player has actually stored.
 *
 * <h2>Three questions, and the gateway if any of them says no</h2>
 *
 * <ol>
 *   <li><strong>Does the model id name a platform?</strong> {@code openai/gpt-4o-mini} does;
 *       a bare {@code gpt-4o-mini} does not, and nothing here invents one.</li>
 *   <li><strong>Is there a base URL for that platform?</strong> The
 *       {@code api.base-url.{platform}} setting first, then the addresses this release ships
 *       in {@link ModelPlatform}.</li>
 *   <li><strong>Does the player hold a key for it?</strong> A direct call is authenticated by
 *       a key stored for that platform and by no other.</li>
 * </ol>
 *
 * <p>Any "no" is the gateway route, which is what every request was before this class existed.
 * That is the whole fallback story: direct routing is something a player turns on by storing a
 * key under a platform, and everything else keeps working exactly as it did.
 *
 * <h2>Read per request, not cached</h2>
 *
 * <p>Both reads are single-row lookups against a local file, next to an HTTP call to a model.
 * Reading them each time is what lets a key added while the game is running take effect without
 * a restart — the same reason nothing checks for a key at construction time.
 */
public final class StoredProviderRoutes implements ProviderRoutes {

    private static final Logger LOGGER = System.getLogger(StoredProviderRoutes.class.getName());

    private final SettingsRepository settings;
    private final ApiKeyRepository keys;

    public StoredProviderRoutes(SettingsRepository settings, ApiKeyRepository keys) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public CompletableFuture<ProviderRoute> route(String storedModelName) {
        ProviderRoute gateway = ProviderRoute.throughOpenRouter(storedModelName);

        Optional<ModelName> name = ModelName.parse(storedModelName);
        if (name.isEmpty() || ModelPlatform.isOpenRouter(name.get().platform())) {
            return CompletableFuture.completedFuture(gateway);
        }
        ModelName model = name.get();

        return baseUrl(model.platform()).thenCompose(url -> {
            if (url.isEmpty()) {
                return CompletableFuture.completedFuture(gateway);
            }
            // The key is what makes this a route rather than a guess. Without one for this
            // platform the request would arrive unauthenticated, or — far worse — carrying a
            // key belonging to somebody else's API.
            return keys.findNextActive(model.platform())
                    .thenApply(key -> key.isPresent()
                            ? ProviderRoute.direct(model, url.get())
                            : gateway);
        });
    }

    /**
     * Where {@code platform}'s API lives: what the player configured, else what this release
     * knows, else nowhere.
     */
    private CompletableFuture<Optional<String>> baseUrl(String platform) {
        return settings.find(ModelPlatform.baseUrlSettingKey(platform))
                .thenApply(configured -> configured
                        .map(String::trim)
                        .filter(url -> usable(platform, url))
                        .or(() -> ModelPlatform.knownBaseUrl(platform)));
    }

    /**
     * Whether a configured URL is one a key may be sent to.
     *
     * <p>A plain-text URL to a remote host would put the player's key on the wire in the clear,
     * so it is refused and said so once. The platform's own address is used instead of the
     * rejected one, because a key going to the platform it belongs to is the safe reading of
     * what the player meant.
     */
    private static boolean usable(String platform, String url) {
        if (ModelPlatform.isUsableBaseUrl(url)) {
            return true;
        }
        LOGGER.log(Level.WARNING,
                "The base URL configured for {0} is not https and is not loopback, so it is "
                        + "being ignored rather than sent an API key", platform);
        return false;
    }
}
