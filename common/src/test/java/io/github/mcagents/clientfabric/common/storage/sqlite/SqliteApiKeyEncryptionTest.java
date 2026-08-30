package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.model.ApiKey;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually lands in {@code data.db} when a token is stored.
 *
 * <p>Every assertion here reads the file with its own JDBC connection rather than through the
 * repository, because the claim being tested is about the bytes on disk: someone who copies
 * the database learns nothing from it. A test that went through the repository would only
 * prove the repository can read its own output.
 */
class SqliteApiKeyEncryptionTest {

    /** Obviously fake. A test never contains anything shaped like a real key. */
    private static final String KEY = "sk-or-v1-first";
    private static final String OTHER_KEY = "sk-or-v1-second";

    private static final String MATERIAL = "the key this machine is configured with";

    @TempDir
    Path gameDirectory;

    @Test
    void writesNothingReadableIntoTheDatabaseFile() {
        LocalDatabase database = open(TokenCipher.fromKeyMaterial(MATERIAL));
        try {
            await(database.apiKeys().add(KEY));
        } finally {
            database.close();
        }

        String stored = storedValues().get(0);
        assertNotEquals(KEY, stored);
        assertFalse(stored.contains(KEY));
        assertTrue(TokenCipher.isEncrypted(stored));
    }

    @Test
    void storesTheSameKeyDifferentlyEveryTimeItIsWritten() {
        // Two writes of one value, two different rows on disk. This is the property a hash
        // cannot give: nothing in the file says these two rows ever held the same key.
        TokenCipher cipher = TokenCipher.fromKeyMaterial(MATERIAL);

        LocalDatabase database = open(cipher);
        String first;
        String second;
        try {
            await(database.apiKeys().add(KEY));
            first = storedValues().get(0);

            assertTrue(await(database.apiKeys().remove(KEY)));
            await(database.apiKeys().add(KEY));
            second = storedValues().get(0);
        } finally {
            database.close();
        }

        assertNotEquals(first, second);
        assertEquals(KEY, cipher.decrypt(first));
        assertEquals(KEY, cipher.decrypt(second));
    }

    @Test
    void readsBackEverythingItStoredWhenReopened() {
        TokenCipher cipher = TokenCipher.fromKeyMaterial(MATERIAL);

        LocalDatabase first = open(cipher);
        try {
            await(first.apiKeys().add(KEY));
            await(first.apiKeys().add(OTHER_KEY, "openai"));
        } finally {
            first.close();
        }

        LocalDatabase second = open(cipher);
        try {
            List<ApiKey> stored = await(second.apiKeys().findAll());

            assertEquals(2, stored.size());
            assertEquals(KEY, stored.get(0).value());
            assertEquals(OTHER_KEY, stored.get(1).value());
            assertEquals("openai", stored.get(1).platform());
        } finally {
            second.close();
        }
    }

    @Test
    void findsAKeyByItsValueEvenThoughTheColumnHoldsCiphertext() {
        // No SQL predicate can match a randomised ciphertext, so every lookup by value goes
        // through a decrypt-and-compare. If that broke, a player could never remove a key.
        LocalDatabase database = open(TokenCipher.fromKeyMaterial(MATERIAL));
        try {
            await(database.apiKeys().add(KEY));
            await(database.apiKeys().add(OTHER_KEY));

            assertTrue(await(database.apiKeys().deactivate(KEY)));
            assertEquals(OTHER_KEY, await(database.apiKeys().findNextActive()).orElseThrow().value());

            assertTrue(await(database.apiKeys().remove(KEY)));
            assertFalse(await(database.apiKeys().remove(KEY)));
            assertEquals(1, await(database.apiKeys().findAll()).size());
        } finally {
            database.close();
        }
    }

    @Test
    void keepsStoringPlainTextWhenNoEncryptionKeyIsConfigured() {
        // The fallback. A player who has never set the variable gets the mod they had.
        LocalDatabase database = open(TokenCipher.disabled());
        try {
            await(database.apiKeys().add(KEY));

            assertEquals(KEY, await(database.apiKeys().findNextActive()).orElseThrow().value());
        } finally {
            database.close();
        }

        assertEquals(KEY, storedValues().get(0));
    }

    @Test
    void encryptsTheKeysAPlayerAlreadyHadWhenTheVariableIsSet() {
        LocalDatabase before = open(TokenCipher.disabled());
        try {
            await(before.apiKeys().add(KEY));
        } finally {
            before.close();
        }
        assertEquals(KEY, storedValues().get(0), "stored in the clear to begin with");

        LocalDatabase after = open(TokenCipher.fromKeyMaterial(MATERIAL));
        try {
            // Turning encryption on has to protect what is already there. Protecting only the
            // next key the player pastes would be a promise the file does not keep.
            assertTrue(TokenCipher.isEncrypted(storedValues().get(0)));
            assertEquals(KEY, await(after.apiKeys().findNextActive()).orElseThrow().value());
        } finally {
            after.close();
        }
    }

    @Test
    void reEncryptsNothingOnASecondStart() {
        TokenCipher cipher = TokenCipher.fromKeyMaterial(MATERIAL);

        LocalDatabase first = open(cipher);
        try {
            await(first.apiKeys().add(KEY));
        } finally {
            first.close();
        }
        String afterFirstStart = storedValues().get(0);

        LocalDatabase second = open(cipher);
        try {
            assertEquals(afterFirstStart, storedValues().get(0),
                    "an already-encrypted row is left exactly as it is");
        } finally {
            second.close();
        }
    }

    @Test
    void leavesAKeyItCannotOpenAloneRatherThanDroppingIt() {
        LocalDatabase original = open(TokenCipher.fromKeyMaterial(MATERIAL));
        try {
            await(original.apiKeys().add(KEY));
        } finally {
            original.close();
        }

        LocalDatabase withTheWrongKey = open(TokenCipher.fromKeyMaterial("something else"));
        try {
            assertTrue(await(withTheWrongKey.apiKeys().findAll()).isEmpty(),
                    "a key that cannot be opened is not offered to the model");
            assertTrue(await(withTheWrongKey.apiKeys().findNextActive()).isEmpty());
            assertEquals(1, storedValues().size(), "but the row is still in the database");
        } finally {
            withTheWrongKey.close();
        }

        LocalDatabase recovered = open(TokenCipher.fromKeyMaterial(MATERIAL));
        try {
            // The whole point of keeping the row: putting the right variable back is all it
            // takes, and nothing the player stored was destroyed in the meantime.
            assertEquals(KEY, await(recovered.apiKeys().findNextActive()).orElseThrow().value());
        } finally {
            recovered.close();
        }
    }

    @Test
    void countsAnUnreadableRowInThePageTotalRatherThanShiftingEveryWindow() {
        LocalDatabase original = open(TokenCipher.fromKeyMaterial(MATERIAL));
        try {
            await(original.apiKeys().add(KEY));
        } finally {
            original.close();
        }

        LocalDatabase withTheWrongKey = open(TokenCipher.fromKeyMaterial("something else"));
        try {
            var page = await(withTheWrongKey.apiKeys()
                    .findPage(new io.github.mcagents.clientfabric.api.model.PageRequest(0, 10)));

            assertEquals(1, page.total());
            assertTrue(page.items().isEmpty());
        } finally {
            withTheWrongKey.close();
        }
    }

    // -------------------------------------------------------------- helpers

    private LocalDatabase open(TokenCipher cipher) {
        return await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db"), cipher));
    }

    /** What the {@code key_value} column actually holds, read outside the repository. */
    private List<String> storedValues() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:"
                + gameDirectory.resolve("MCAgents/data.db").toAbsolutePath());

        return assertDoesNotThrow(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT key_value FROM api_keys ORDER BY rowid");
                 ResultSet rows = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString("key_value"));
                }
                return values;
            }
        });
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
