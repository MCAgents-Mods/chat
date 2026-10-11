package io.github.mcagentsmods.chat.api.storage;

import io.github.mcagentsmods.chat.api.model.ModelPreferences;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Player preferences, stored as key/value pairs in the local database.
 *
 * <p>Every method is asynchronous — see {@link LocalDatabase} for why.
 */
public interface SettingsRepository {

    /**
     * The player's model selection, or {@link ModelPreferences#defaults()} if they have not
     * chosen. Never empty: the mod always has a model to use.
     */
    CompletableFuture<ModelPreferences> loadModelPreferences();

    /**
     * Stores the model selection, normalising blanks to the catalog defaults.
     *
     * @return what was actually stored, after normalisation
     */
    CompletableFuture<ModelPreferences> saveModelPreferences(ModelPreferences preferences);

    /** One raw setting, or empty if it has never been set. */
    CompletableFuture<Optional<String>> find(String key);

    /** Stores one raw setting, replacing any existing value. */
    CompletableFuture<Void> put(String key, String value);
}
