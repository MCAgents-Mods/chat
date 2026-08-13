package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.model.ModelPreferences;
import io.github.mcagents.clientfabric.api.storage.DataAccessException;
import io.github.mcagents.clientfabric.api.storage.SettingsRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Player preferences, in the {@code settings} table. */
final class SqliteSettingsRepository implements SettingsRepository {

    static final String CHAT_MODEL_KEY = "model.chat";
    static final String EMBEDDING_MODEL_KEY = "model.embedding";

    private final SqlWorker worker;

    SqliteSettingsRepository(SqlWorker worker) {
        this.worker = worker;
    }

    @Override
    public CompletableFuture<ModelPreferences> loadModelPreferences() {
        return worker.submit("Reading the model preferences", connection -> {
            // Read both in one call rather than two round trips, and let ModelPreferences
            // substitute the defaults for anything unset.
            String chat = readValue(connection, CHAT_MODEL_KEY).orElse(null);
            String embedding = readValue(connection, EMBEDDING_MODEL_KEY).orElse(null);
            return ModelPreferences.of(chat, embedding);
        });
    }

    @Override
    public CompletableFuture<ModelPreferences> saveModelPreferences(ModelPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        // Normalise before storing: a blank becomes the default, so nothing downstream has
        // to handle an empty model id.
        ModelPreferences normalised =
                ModelPreferences.of(preferences.chatModel(), preferences.embeddingModel());

        return worker.submitInTransaction("Storing the model preferences", connection -> {
            writeValue(connection, CHAT_MODEL_KEY, normalised.chatModel());
            writeValue(connection, EMBEDDING_MODEL_KEY, normalised.embeddingModel());
            return normalised;
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
