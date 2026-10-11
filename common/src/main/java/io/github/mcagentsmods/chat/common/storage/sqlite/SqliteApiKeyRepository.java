package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ModelPlatform;
import io.github.mcagentsmods.chat.api.model.Page;
import io.github.mcagentsmods.chat.api.model.PageRequest;
import io.github.mcagentsmods.chat.api.storage.ApiKeyRepository;
import io.github.mcagentsmods.chat.api.storage.DataAccessException;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.chat.common.security.TokenCipherException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * API keys, in the {@code api_keys} table.
 *
 * <p>No message produced here ever contains a key. Failures are described by what was
 * attempted, never by what was attempted with.
 *
 * <h2>The column holds ciphertext, the interface holds keys</h2>
 *
 * <p>{@code key_value} holds whatever {@link TokenCipher} produced — an encrypted envelope when
 * the machine has an encryption key configured, the key itself when it has not. Everything
 * above this class sees the key either way, because encryption and decryption happen here and
 * nowhere else.
 *
 * <p><strong>Ciphertext cannot be matched in SQL.</strong> The same key encrypts differently
 * every time, which is the property that makes a stolen database silent about which keys it
 * holds — and it means {@code WHERE key_value = ?} can no longer find a key. Every lookup by
 * value therefore reads the column and decrypts, comparing in constant time. A player holds a
 * handful of keys, so the cost is a handful of AES operations against a local file; the
 * alternative, a deterministic column to search on, is a fingerprint of the key and gives back
 * exactly what the encryption was for.
 *
 * <h2>A key that cannot be opened is kept, not dropped</h2>
 *
 * <p>A row encrypted with a key this machine no longer has is skipped on read, with one warning
 * naming how many rows it happened to. It is never deleted and never guessed at: the player's
 * keys are recoverable the moment the right {@code MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY} is
 * put back, and a mod that pruned them would have destroyed the only copy.
 */
final class SqliteApiKeyRepository implements ApiKeyRepository {

    private static final Logger LOGGER = System.getLogger(SqliteApiKeyRepository.class.getName());

    private static final String COLUMNS = "key_value, platform, is_active, created_at";

    /**
     * Ties on {@code created_at} break on {@code rowid}, which is insertion order.
     *
     * <p>{@code CURRENT_TIMESTAMP} resolves to the second, so two keys pasted in the same breath
     * carry the same timestamp. The tie used to break on {@code key_value}; that column now
     * holds ciphertext, whose order is arbitrary, so "the oldest active key" would no longer
     * have meant the oldest. It is still a total order, which is what a stable page needs.
     */
    private static final String ORDER = "ORDER BY created_at ASC, rowid ASC";

    private final SqlWorker worker;
    private final TokenCipher cipher;

    SqliteApiKeyRepository(SqlWorker worker, TokenCipher cipher) {
        this.worker = worker;
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public CompletableFuture<List<ApiKey>> findAll() {
        return worker.submit("Reading the stored API keys", connection -> {
            String sql = "SELECT " + COLUMNS + " FROM api_keys " + ORDER;
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                return readAll(rows);
            }
        });
    }

    @Override
    public CompletableFuture<Page<ApiKey>> findPage(PageRequest page) {
        Objects.requireNonNull(page, "page");

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

            // The count is of rows, not of readable rows, so an unreadable one keeps its place
            // in the total. That is deliberate: filtering in SQL would shift every later
            // window's offset, and a page that skips a row is better than a page that repeats
            // or loses a different one.
            String sql = "SELECT " + COLUMNS + " FROM api_keys " + ORDER + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, page.limit());
                statement.setInt(2, page.offset());
                try (ResultSet rows = statement.executeQuery()) {
                    return new Page<>(readAll(rows), page.offset(), page.limit(), total);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ApiKey>> findNextActive(String platform) {
        String owner = ModelPlatform.normalise(platform);

        return worker.submit("Reading the next usable API key", connection -> {
            // Limited in SQL is not safe here: the first row might be one this machine cannot
            // decrypt, and returning empty would tell the player they have no key when they
            // have several. Read the platform's active keys and take the first that opens.
            String sql = "SELECT " + COLUMNS + " FROM api_keys "
                    + "WHERE is_active = 1 AND platform = ? " + ORDER;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, owner);
                try (ResultSet rows = statement.executeQuery()) {
                    return readAll(rows).stream().findFirst();
                }
            }
        });
    }

    @Override
    public CompletableFuture<ApiKey> add(String value, String platform) {
        if (value == null || value.isBlank()) {
            return CompletableFuture.failedFuture(
                    new DataAccessException("An API key cannot be blank"));
        }
        String key = value.trim();
        String owner = ModelPlatform.normalise(platform);

        return worker.submitInTransaction("Storing an API key", connection -> {
            // Re-adding a key the player previously deactivated turns it back on rather than
            // making them delete and retype it, and moves it if they filed it under a
            // different platform the first time.
            Optional<String> existing = storedFormOf(connection, key);
            if (existing.isPresent()) {
                String update = "UPDATE api_keys SET is_active = 1, platform = ? "
                        + "WHERE key_value = ?";
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setString(1, owner);
                    statement.setString(2, existing.get());
                    statement.executeUpdate();
                }
                return requireStored(connection, existing.get());
            }

            String stored = cipher.encrypt(key);
            String insert = "INSERT INTO api_keys (key_value, platform, is_active) "
                    + "VALUES (?, ?, 1)";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, stored);
                statement.setString(2, owner);
                statement.executeUpdate();
            }
            return requireStored(connection, stored);
        });
    }

    @Override
    public CompletableFuture<Boolean> deactivate(String value) {
        return worker.submit("Deactivating an API key", connection ->
                updateByValue(connection, value,
                        "UPDATE api_keys SET is_active = 0 WHERE key_value = ?"));
    }

    @Override
    public CompletableFuture<Boolean> remove(String value) {
        return worker.submit("Removing an API key", connection ->
                updateByValue(connection, value, "DELETE FROM api_keys WHERE key_value = ?"));
    }

    /** Runs a single-parameter statement against whichever row holds {@code value}. */
    private boolean updateByValue(Connection connection, String value, String sql)
            throws SQLException {
        if (value == null || value.isBlank()) {
            return false;
        }
        Optional<String> stored = storedFormOf(connection, value.trim());
        if (stored.isEmpty()) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stored.get());
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * The stored form of {@code key} — its ciphertext, or the key itself when nothing is
     * encrypted — or empty if the player does not have it.
     *
     * <p>The scan the class comment explains: a randomised ciphertext has nothing SQL can match
     * on, so the comparison happens here, on the decrypted value, in constant time.
     */
    private Optional<String> storedFormOf(Connection connection, String key) throws SQLException {
        byte[] wanted = key.getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT key_value FROM api_keys " + ORDER);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String stored = rows.getString("key_value");
                Optional<String> plain = decrypt(stored);
                if (plain.isPresent()
                        && MessageDigest.isEqual(plain.get().getBytes(StandardCharsets.UTF_8), wanted)) {
                    return Optional.of(stored);
                }
            }
        }
        return Optional.empty();
    }

    private ApiKey requireStored(Connection connection, String storedForm) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM api_keys WHERE key_value = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storedForm);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new DataAccessException("The API key was not stored");
                }
                return read(rows).orElseThrow(() ->
                        new DataAccessException("The API key was stored but could not be read back"));
            }
        }
    }

    /** Every readable row of {@code rows}, with a single warning if any was not. */
    private List<ApiKey> readAll(ResultSet rows) throws SQLException {
        List<ApiKey> keys = new ArrayList<>();
        int unreadable = 0;
        while (rows.next()) {
            Optional<ApiKey> key = read(rows);
            if (key.isPresent()) {
                keys.add(key.get());
            } else {
                unreadable++;
            }
        }
        if (unreadable > 0) {
            // Once per read, with a count and no values. The rows are still there; what is
            // missing is the key that opens them.
            LOGGER.log(Level.WARNING,
                    "{0} stored API keys could not be decrypted and were left alone. Set "
                            + TokenCipher.KEY_VARIABLE + " to the value they were stored with "
                            + "to use them again.", unreadable);
        }
        return List.copyOf(keys);
    }

    private Optional<ApiKey> read(ResultSet rows) throws SQLException {
        Optional<String> value = decrypt(rows.getString("key_value"));
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ApiKey(
                value.get(),
                ModelPlatform.normalise(rows.getString("platform")),
                rows.getBoolean("is_active"),
                SqlValues.readInstant(rows.getString("created_at"))));
    }

    /** The key a stored value holds, or empty when this machine cannot open it. */
    private Optional<String> decrypt(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(cipher.decrypt(stored));
        } catch (TokenCipherException e) {
            return Optional.empty();
        }
    }

    /**
     * Encrypts every key still stored in the clear, once, at startup.
     *
     * <p>Run after the schema and before anything reads a key, and only when a cipher is
     * configured — turning encryption on has to protect the keys the player already had, not
     * just the next one they paste.
     *
     * <p>Nothing is deleted and nothing is re-encrypted: a row already carrying an envelope is
     * left exactly as it is, so this is a no-op on every start after the first.
     *
     * @return how many rows were encrypted
     */
    static int encryptStoredKeys(Connection connection, TokenCipher cipher) throws SQLException {
        if (!cipher.enabled()) {
            return 0;
        }
        List<String> plain = new ArrayList<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT key_value FROM api_keys");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String stored = rows.getString("key_value");
                if (stored != null && !TokenCipher.isEncrypted(stored)) {
                    plain.add(stored);
                }
            }
        }
        if (plain.isEmpty()) {
            return 0;
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE api_keys SET key_value = ? WHERE key_value = ?")) {
            for (String key : plain) {
                statement.setString(1, cipher.encrypt(key));
                statement.setString(2, key);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            // A cipher failure part-way through leaves nothing half-written: either every key
            // is encrypted or the database is exactly as it was.
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return plain.size();
    }
}
