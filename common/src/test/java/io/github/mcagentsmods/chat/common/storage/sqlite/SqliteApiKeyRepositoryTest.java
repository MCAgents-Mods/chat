package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.storage.ApiKeyRepository;
import io.github.mcagentsmods.chat.api.storage.DataAccessException;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
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

/** Storing, rotating, and masking API keys, and keeping one platform's keys off another. */
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

    // ----------------------------------------------------------------- platforms

    @Test
    void storesAKeyForTheGatewayWhenNoPlatformIsNamed() {
        // The fallback that keeps every database written before this column exactly as it
        // was: an unqualified key is an OpenRouter key, which is what every key used to be.
        ApiKey stored = await(keys.add("sk-or-v1-first"));

        assertEquals(ModelPlatform.OPENROUTER, stored.platform());
        assertTrue(stored.isForOpenRouter());
    }

    @Test
    void storesAKeyForThePlatformItWasGiven() {
        ApiKey stored = await(keys.add("sk-openai-first", "OpenAI"));

        assertEquals("openai", stored.platform(), "a platform is lowercased before the write");
    }

    @Test
    void neverOffersOnePlatformsKeyToAnother() {
        // The rule the whole column exists for: a gateway key is not valid at OpenAI's own
        // door, and sending it there would disclose it to somewhere it does not belong.
        await(keys.add("sk-or-v1-first"));
        await(keys.add("sk-openai-first", "openai"));

        assertEquals("sk-or-v1-first", await(keys.findNextActive()).orElseThrow().value());
        assertEquals("sk-openai-first",
                await(keys.findNextActive("openai")).orElseThrow().value());
        assertTrue(await(keys.findNextActive("anthropic")).isEmpty(),
                "a platform with no key of its own must not borrow one");
    }

    @Test
    void rotatesWithinOnePlatformOnly() {
        await(keys.add("sk-openai-first", "openai"));
        await(keys.add("sk-openai-second", "openai"));
        await(keys.add("sk-or-v1-first"));

        assertTrue(await(keys.deactivate("sk-openai-first")));

        assertEquals("sk-openai-second",
                await(keys.findNextActive("openai")).orElseThrow().value());
        assertEquals("sk-or-v1-first", await(keys.findNextActive()).orElseThrow().value(),
                "deactivating one platform's key must not touch another's");
    }

    @Test
    void movesAKeyWhenItIsAddedAgainUnderADifferentPlatform() {
        // Pasting it again is how a player corrects a key they filed in the wrong place. A
        // second row holding the same secret would be worse than moving the one that exists.
        await(keys.add("sk-openai-first", "openrouter"));

        ApiKey moved = await(keys.add("sk-openai-first", "openai"));

        assertEquals("openai", moved.platform());
        assertEquals(1, await(keys.findAll()).size());
        assertTrue(await(keys.findNextActive()).isEmpty(),
                "the key no longer answers for the platform it was moved off");
    }

    @Test
    void takesAPlatformNobodyHasHeardOf() {
        // Same rule as a model id: the mod does not keep a whitelist of providers.
        ApiKey stored = await(keys.add("sk-somewhere-first", "some-new-platform"));

        assertEquals("some-new-platform", stored.platform());
        assertEquals("sk-somewhere-first",
                await(keys.findNextActive("some-new-platform")).orElseThrow().value());
    }

    // ----------------------------------------------------------------- masking

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
