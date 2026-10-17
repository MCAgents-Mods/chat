package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.DataAccessException;
import io.github.mcagentsmods.chat.api.storage.SettingsRepository;
import io.github.mcagentsmods.core.api.model.GlobalModelStore;
import io.github.mcagentsmods.core.api.model.GlobalModels;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Player preferences, in the {@code settings} table, and the model choices in core's global
 * model.
 *
 * <p>The chat and embedding choices are core's {@link GlobalModelStore}: what every task with
 * no model of its own uses, in every MCAgents mod. The embedding switch is chat's own and stays
 * in this database. The rows {@value #CHAT_MODEL_KEY} and {@value #EMBEDDING_MODEL_KEY} are
 * where the choices lived before; they are read once, by {@code LegacyModelSettings}, and never
 * written again.
 */
final class SqliteSettingsRepository implements SettingsRepository {

    static final String CHAT_MODEL_KEY = "model.chat";
    static final String EMBEDDING_MODEL_KEY = "model.embedding";
    static final String EMBEDDING_ENABLED_KEY = "model.embedding.enabled";

    private final SqlWorker worker;
    private final GlobalModelStore models;

    SqliteSettingsRepository(SqlWorker worker, GlobalModelStore models) {
        this.worker = worker;
        this.models = Objects.requireNonNull(models, "models");
    }

    @Override
    public CompletableFuture<ModelPreferences> loadModelPreferences() {
        return worker.submit("Reading the model preferences", connection -> {
            boolean enabled = readFlag(connection, EMBEDDING_ENABLED_KEY,
                    ModelPreferences.DEFAULT_EMBEDDING_ENABLED);
            // Read on the database thread with the switch, so one answer holds both. The global
            // model is read now rather than held, so a save in another mod shows here too.
            GlobalModels global = models.current();
            return ModelPreferences.of(global.chat(), global.embedding(), enabled);
        });
    }

    @Override
    public CompletableFuture<ModelPreferences> saveModelPreferences(ModelPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        // Normalise before storing: a blank becomes the default, so nothing downstream has
        // to handle an empty model id.
        ModelPreferences normalised = ModelPreferences.of(
                preferences.chat(), preferences.embedding(), preferences.embeddingEnabled());

        return worker.submit("Storing the embedding switch", connection -> {
            // The embedding choice is kept whether or not retrieval is on. Switching off is
            // not a reason to forget which model the player picked.
            writeValue(connection, EMBEDDING_ENABLED_KEY,
                    Boolean.toString(normalised.embeddingEnabled()));
            return null;
        }).thenCompose(ignored -> {
            // The custom providers are carried over from what is current: they are added and
            // removed on their own, never by saving the models.
            GlobalModels current = models.current();
            return models.save(current.withChat(normalised.chat()).withEmbedding(normalised.embedding()));
        }).thenApply(ignored -> normalised);
    }

    @Override
    public CompletableFuture<Map<String, String>> findStartingWith(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return worker.submit("Reading settings by prefix", connection -> {
            Map<String, String> found = new TreeMap<>();
            // substr rather than LIKE, so an underscore or a percent sign in the prefix is
            // matched as itself.
            String sql = "SELECT key, value FROM settings WHERE substr(key, 1, ?) = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, prefix.length());
                statement.setString(2, prefix);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        found.put(rows.getString("key"), rows.getString("value"));
                    }
                }
            }
            return found;
        });
    }

    @Override
    public CompletableFuture<Optional<String>> find(String key) {
        return worker.submit("Reading a setting", connection -> readValue(connection, key));
    }

    @Override
    public CompletableFuture<Void> put(String key, String value) {
        if (key == null || key.isBlank()) {
            return CompletableFuture.failedFuture(
                    new DataAccessException("A setting key cannot be blank"));
        }
        if (value == null) {
            return CompletableFuture.failedFuture(
                    new DataAccessException("A setting value cannot be null"));
        }
        return worker.submit("Storing a setting", connection -> {
            writeValue(connection, key, value);
            return null;
        });
    }

    private static Optional<String> readValue(Connection connection, String key) throws SQLException {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString("value")) : Optional.empty();
            }
        }
    }

    /**
     * A stored flag, or {@code fallback} if it has never been set.
     *
     * <p>Only the two values this repository writes are recognised. Anything else — a row
     * edited by hand, a value from a future version — falls back rather than being guessed
     * at, so an unreadable setting behaves as though it were unset.
     */
    private static boolean readFlag(Connection connection, String key, boolean fallback)
            throws SQLException {
        Optional<String> stored = readValue(connection, key);
        if (stored.isEmpty()) {
            return fallback;
        }
        String value = stored.get().trim();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static void writeValue(Connection connection, String key, String value) throws SQLException {
        String sql = "INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET "
                + "value = excluded.value, updated_at = CURRENT_TIMESTAMP";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }
}
