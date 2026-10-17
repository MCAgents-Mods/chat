package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.model.GlobalModelStore;
import io.github.mcagentsmods.core.api.model.GlobalModels;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelName;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.api.model.ModelProvider;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Moves the model settings chat kept before core held the global model into core's store, once.
 *
 * <p>Before chat 0.12.0 the chat and embedding models were the {@code model.chat} and
 * {@code model.embedding} rows of chat's own settings, a platform's address was an
 * {@code api.base-url.{platform}} row, and the route was not stored at all: a model went
 * straight to its platform when an address was known and a key for it was stored, and through
 * OpenRouter otherwise. This turns all of that into the global model:
 *
 * <ul>
 *   <li>each model keeps the route it was taking, now written down as its provider;</li>
 *   <li>each usable address becomes a custom provider of that platform's name.</li>
 * </ul>
 *
 * <p>It runs only while core's store has never been saved, so it happens once and never
 * overwrites a choice made on the Models page. The old rows are left where they are: they are
 * no longer read, and an older chat would still find them.
 */
public final class LegacyModelSettings {

    private static final Logger LOGGER = System.getLogger(LegacyModelSettings.class.getName());

    static final String CHAT_MODEL_KEY = "model.chat";
    static final String EMBEDDING_MODEL_KEY = "model.embedding";

    private LegacyModelSettings() {
        throw new AssertionError("No instances.");
    }

    /**
     * Moves the old settings into {@code store} if it has never been saved.
     *
     * @return whether anything was moved
     */
    public static CompletableFuture<Boolean> migrate(LocalDatabase database, GlobalModelStore store) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(store, "store");
        if (store.saved()) {
            return CompletableFuture.completedFuture(false);
        }
        var settings = database.settings();
        return settings.findStartingWith(ModelPlatform.BASE_URL_SETTING_PREFIX)
                .thenCombine(settings.find(CHAT_MODEL_KEY).thenCombine(settings.find(EMBEDDING_MODEL_KEY), Old::new),
                        (urls, old) -> old.withUrls(urls))
                .thenCombine(database.apiKeys().findAll(), Old::withKeys)
                .thenCompose(old -> {
                    if (!old.anything() || store.saved()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    GlobalModels moved = old.toGlobal();
                    return store.save(moved).thenApply(saved -> {
                        LOGGER.log(Level.INFO, "Moved the model settings into the global model: "
                                + "chat {0}, embedding {1}, {2} custom providers",
                                moved.chat(), moved.embedding(), moved.customProviders().size());
                        return true;
                    });
                });
    }

    /** What chat had stored, read together. */
    private record Old(Optional<String> chat, Optional<String> embedding, Map<String, String> urls,
                       Set<String> keyedPlatforms) {

        Old(Optional<String> chat, Optional<String> embedding) {
            this(chat, embedding, Map.of(), Set.of());
        }

        Old withUrls(Map<String, String> found) {
            return new Old(chat, embedding, found, keyedPlatforms);
        }

        Old withKeys(List<ApiKey> keys) {
            return new Old(chat, embedding, urls, keys.stream()
                    .filter(ApiKey::active)
                    .map(ApiKey::platform)
                    .map(ModelPlatform::normalise)
                    .collect(Collectors.toSet()));
        }

        boolean anything() {
            return chat.filter(id -> !id.isBlank()).isPresent()
                    || embedding.filter(id -> !id.isBlank()).isPresent()
                    || !providers().isEmpty();
        }

        /** Each usable address, as a custom provider of its platform's name. */
        List<ModelProvider> providers() {
            List<ModelProvider> providers = new ArrayList<>();
            urls.forEach((key, url) -> {
                String platform = key.substring(ModelPlatform.BASE_URL_SETTING_PREFIX.length());
                if (url == null || url.isBlank() || !ModelProvider.isValidId(platform)
                        || ModelPlatform.isOpenRouter(platform) || !ModelPlatform.isUsableBaseUrl(url)) {
                    return;
                }
                providers.add(new ModelProvider(platform, url));
            });
            return providers;
        }

        GlobalModels toGlobal() {
            GlobalModels withProviders = new GlobalModels(
                    GlobalModels.DEFAULTS.chat(), GlobalModels.DEFAULTS.embedding(), providers());
            ModelPreferences models = ModelPreferences.of(
                    route(chat, GlobalModels.DEFAULT_CHAT_MODEL, withProviders),
                    route(embedding, GlobalModels.DEFAULT_EMBEDDING_MODEL, withProviders), true);
            return withProviders.withChat(models.chat()).withEmbedding(models.embedding());
        }

        /**
         * The route a stored model was taking: straight to its platform when an address was known
         * and a key for it was stored, through OpenRouter otherwise.
         */
        private ModelChoice route(Optional<String> stored, String fallback, GlobalModels known) {
            String model = ModelName.normalise(stored.filter(id -> !id.isBlank()).orElse(fallback));
            String direct = ModelName.parse(model)
                    .map(ModelName::platform)
                    .filter(platform -> !ModelPlatform.isOpenRouter(platform))
                    .filter(platform -> known.provider(platform).isPresent())
                    .filter(keyedPlatforms::contains)
                    .orElse(ModelPlatform.OPENROUTER);
            return new ModelChoice(direct, model);
        }
    }
}
