package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.model.ApiKey;
import io.github.mcagents.clientfabric.api.storage.ApiKeyRepository;
import io.github.mcagents.clientfabric.api.storage.DataAccessException;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storing, rotating, and masking OpenRouter API keys. */
class SqliteApiKeyRepositoryTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ApiKeyRepository keys;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        keys = database.apiKeys();
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void reportsNoUsableKeyWhenThePlayerHasNotAddedOne() {
        assertTrue(await(keys.findNextActive()).isEmpty());
        assertTrue(await(keys.findAll()).isEmpty());
    }

    @Test
    void storesAKeyAsActive() {
        ApiKey stored = await(keys.add("sk-or-v1-first"));

        assertEquals("sk-or-v1-first", stored.value());
        assertTrue(stored.active());
        assertEquals("sk-or-v1-first", await(keys.findNextActive()).orElseThrow().value());
    }

    @Test
    void movesToTheNextKeyWhenOneIsDeactivated() {
        // This is the rotation the mod performs when OpenRouter rejects a key: deactivate
        // it and carry on with the next, without interrupting the player.
        await(keys.add("sk-or-v1-first"));
        await(keys.add("sk-or-v1-second"));

        assertEquals("sk-or-v1-first", await(keys.findNextActive()).orElseThrow().value());

        assertTrue(await(keys.deactivate("sk-or-v1-first")));

        assertEquals("sk-or-v1-second", await(keys.findNextActive()).orElseThrow().value());
        assertEquals(2, await(keys.findAll()).size(),
                "a rejected key is kept so the player can see what happened");
    }

    @Test
    void addingAKeyAgainReactivatesItInsteadOfFailing() {
        await(keys.add("sk-or-v1-first"));
        await(keys.deactivate("sk-or-v1-first"));

        ApiKey readded = await(keys.add("sk-or-v1-first"));

        assertTrue(readded.active());
        assertEquals(1, await(keys.findAll()).size(), "re-adding must not duplicate the row");
    }

    @Test
    void trimsSurroundingWhitespaceFromAPastedKey() {
        ApiKey stored = await(keys.add("  sk-or-v1-pasted  "));

        assertEquals("sk-or-v1-pasted", stored.value());
    }

    @Test
    void rejectsABlankKey() {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> keys.add("   ").join());

        assertTrue(failure.getCause() instanceof DataAccessException,
                "expected a DataAccessException, got " + failure.getCause());
    }

    @Test
    void removesAKey() {
        await(keys.add("sk-or-v1-first"));

        assertTrue(await(keys.remove("sk-or-v1-first")));
        assertFalse(await(keys.remove("sk-or-v1-first")));
        assertTrue(await(keys.findAll()).isEmpty());
    }

    @Test
    void neverRendersTheWholeKey() {
        // Anything that displays or logs a key uses these. The full value must leave the
        // mod only in an Authorization header.
        ApiKey key = new ApiKey("sk-or-v1-abcdefghijklmnop", true, java.time.Instant.now());

        assertEquals("sk-o…mnop", key.masked());
        assertFalse(key.toString().contains("abcdefghijkl"),
                "toString must not leak the key, it ends up in logs");
    }

    @Test
    void masksAShortKeyEntirely() {
        ApiKey key = new ApiKey("short", true, java.time.Instant.now());

        assertEquals("*****", key.masked());
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
