package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.model.ModelCatalog;
import io.github.mcagents.clientfabric.api.model.ModelPreferences;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.api.storage.SettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model selection: defaults, custom ids, and surviving a restart. */
class SqliteSettingsRepositoryTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private SettingsRepository settings;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        settings = database.settings();
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void givesAPlayerWhoHasChosenNothingTheCatalogDefaults() {
        ModelPreferences preferences = await(settings.loadModelPreferences());

        assertEquals(ModelCatalog.DEFAULT_CHAT_MODEL, preferences.chatModel());
        assertEquals(ModelCatalog.DEFAULT_EMBEDDING_MODEL, preferences.embeddingModel());
    }

    @Test
    void storesASelectionTakenFromTheSuggestedLists() {
        await(settings.saveModelPreferences(
                new ModelPreferences("deepseek/deepseek-v4-pro", "qwen/qwen3-embedding-8b")));

        ModelPreferences stored = await(settings.loadModelPreferences());

        assertEquals("deepseek/deepseek-v4-pro", stored.chatModel());
        assertEquals("qwen/qwen3-embedding-8b", stored.embeddingModel());
    }

    @Test
    void storesAModelIdTheModHasNeverHeardOf() {
        // The suggested lists are a convenience, not a whitelist. OpenRouter's catalog
        // moves faster than this mod ships, so an unknown id must be storable.
        await(settings.saveModelPreferences(
                new ModelPreferences("some-vendor/brand-new-model", "some-vendor/brand-new-embed")));

        ModelPreferences stored = await(settings.loadModelPreferences());

        assertEquals("some-vendor/brand-new-model", stored.chatModel());
        assertEquals("some-vendor/brand-new-embed", stored.embeddingModel());
        assertTrue(!ModelCatalog.isSuggestedChatModel(stored.chatModel()),
                "the point of this test is that the id is not on the list");
    }

    @Test
    void treatsABlankChoiceAsTheDefaultRatherThanAsNoModel() {
        // "Clear the field and save" is how a player resets by hand; it must not leave the
        // mod with an empty model id.
        await(settings.saveModelPreferences(new ModelPreferences("   ", "")));

        ModelPreferences stored = await(settings.loadModelPreferences());

        assertEquals(ModelCatalog.DEFAULT_CHAT_MODEL, stored.chatModel());
        assertEquals(ModelCatalog.DEFAULT_EMBEDDING_MODEL, stored.embeddingModel());
    }

    @Test
    void trimsWhitespaceFromAPastedModelId() {
        ModelPreferences stored = await(settings.saveModelPreferences(
                new ModelPreferences("  openai/gpt-4o-mini  ", "  google/gemini-embedding-2  ")));

        assertEquals("openai/gpt-4o-mini", stored.chatModel());
        assertEquals("google/gemini-embedding-2", stored.embeddingModel());
    }

    @Test
    void replacesTheSelectionRatherThanAccumulatingRows() {
        await(settings.saveModelPreferences(
                new ModelPreferences("openai/gpt-4o-mini", "google/gemini-embedding-2")));
        await(settings.saveModelPreferences(
                new ModelPreferences("deepseek/deepseek-v4-pro", "qwen/qwen3-embedding-8b")));

        assertEquals("deepseek/deepseek-v4-pro", await(settings.loadModelPreferences()).chatModel());
    }

    @Test
    void survivesARestart() {
        // The settings table is a migration appended to the schema list, so it must be
        // created on an existing database as readily as on a fresh one.
        await(settings.saveModelPreferences(
                new ModelPreferences("deepseek/deepseek-v4-pro", "qwen/qwen3-embedding-8b")));
        database.close();

        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));

        assertEquals("deepseek/deepseek-v4-pro",
                await(database.settings().loadModelPreferences()).chatModel());
    }

    @Test
    void storesAndReadsARawSetting() {
        await(settings.put("some.key", "some value"));

        assertEquals("some value", await(settings.find("some.key")).orElseThrow());
        assertTrue(await(settings.find("never.set")).isEmpty());
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
