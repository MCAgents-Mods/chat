package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.model.ApiKey;
import io.github.mcagents.clientfabric.api.model.Page;
import io.github.mcagents.clientfabric.api.model.PageRequest;
import io.github.mcagents.clientfabric.api.storage.ApiKeyRepository;
import io.github.mcagents.clientfabric.api.storage.DataAccessException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * OpenRouter API keys, in the {@code api_keys} table.
 *
 * <p>No message produced here ever contains a key. Failures are described by what was
 * attempted, never by what was attempted with.
 */
final class SqliteApiKeyRepository implements ApiKeyRepository {

    private static final String COLUMNS = "key_value, is_active, created_at";

    private final SqlWorker worker;

    SqliteApiKeyRepository(SqlWorker worker) {
        this.worker = worker;
    }

    @Override
    public CompletableFuture<List<ApiKey>> findAll() {
        return worker.submit("Reading the stored API keys", connection -> {
            String sql = "SELECT " + COLUMNS + " FROM api_keys ORDER BY created_at ASC, key_value ASC";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                List<ApiKey> keys = new ArrayList<>();
                while (rows.next()) {
                    keys.add(read(rows));
                }
                return List.copyOf(keys);
            }
        });
    }

    @Override
    public CompletableFuture<Page<ApiKey>> findPage(PageRequest page) {
        java.util.Objects.requireNonNull(page, "page");

        return worker.submit("Reading a page of the stored API keys", connection -> {
            long total;
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT COUNT(*) FROM api_keys");
                 ResultSet rows = statement.executeQuery()) {
                total = rows.next() ? rows.getLong(1) : 0;
            }
            if (total == 0) {
                return Page.empty(page);
            }

            // key_value is the primary key, so this ordering is already total and a window
            // taken from it cannot show a key twice or skip one.
            String sql = "SELECT " + COLUMNS + " FROM api_keys "
                    + "ORDER BY created_at ASC, key_value ASC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, page.limit());
                statement.setInt(2, page.offset());
                try (ResultSet rows = statement.executeQuery()) {
                    List<ApiKey> keys = new ArrayList<>();
                    while (rows.next()) {
                        keys.add(read(rows));
                    }
                    return new Page<>(keys, page.offset(), page.limit(), total);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ApiKey>> findNextActive() {
        return worker.submit("Reading the next usable API key", connection -> {
            String sql = "SELECT " + COLUMNS + " FROM api_keys WHERE is_active = 1 "
                    + "ORDER BY created_at ASC, key_value ASC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.<ApiKey>empty();
            }
        });
    }

    @Override
    public CompletableFuture<ApiKey> add(String value) {
        if (value == null || value.isBlank()) {
            return CompletableFuture.failedFuture(
                    new DataAccessException("An API key cannot be blank"));
        }
        String key = value.trim();

        return worker.submitInTransaction("Storing an API key", connection -> {
            // Re-adding a key the player previously deactivated turns it back on rather
            // than making them delete and retype it.
            String upsert = "INSERT INTO api_keys (key_value, is_active) VALUES (?, 1) "
                    + "ON CONFLICT(key_value) DO UPDATE SET is_active = 1";
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
            return requireStored(connection, key);
        });
    }

    @Override
    public CompletableFuture<Boolean> deactivate(String value) {
        return worker.submit("Deactivating an API key", connection -> {
            String sql = "UPDATE api_keys SET is_active = 0 WHERE key_value = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(String value) {
        return worker.submit("Removing an API key", connection -> {
            String sql = "DELETE FROM api_keys WHERE key_value = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static ApiKey requireStored(java.sql.Connection connection, String key) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM api_keys WHERE key_value = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new DataAccessException("The API key was not stored");
                }
                return read(rows);
            }
        }
    }

    private static ApiKey read(ResultSet rows) throws SQLException {
        return new ApiKey(
                rows.getString("key_value"),
                rows.getBoolean("is_active"),
                SqlValues.readInstant(rows.getString("created_at")));
    }
}
