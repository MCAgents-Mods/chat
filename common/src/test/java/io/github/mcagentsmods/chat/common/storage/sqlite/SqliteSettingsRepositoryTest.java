package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.api.storage.SettingsRepository;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.core.api.model.GlobalModelStore;
import io.github.mcagentsmods.core.api.model.GlobalModels;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model selection: defaults, custom ids, the global model it lives in, and surviving a restart. */
class SqliteSettingsRepositoryTest {

    @TempDir
    Path gameDirectory;

    /** Core's global model, which outlives a restart of the database as the file does in game. */
    private final GlobalModelStore models = GlobalModelStore.inMemory(GlobalModels.DEFAULTS);

    private LocalDatabase database;
    private SettingsRepository settings;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db"), TokenCipher.disabled(), models));
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
                ModelPreferences.of("deepseek/deepseek-v4-pro", "qwen/qwen3-embedding-8b", true)));

        ModelPreferences stored = await(settings.loadModelPreferences());

        assertEquals("deepseek/deepseek-v4-pro", stored.chatModel());
        assertEquals("qwen/qwen3-embedding-8b", stored.embeddingModel());
    }

    @Test
    void storesAModelIdTheModHasNeverHeardOf() {
        // The suggested lists are a convenience, not a whitelist. OpenRouter's catalog
        // moves faster than this mod ships, so an unknown id must be storable.
        await(settings.saveModelPreferences(
                ModelPreferences.of("some-vendor/brand-new-model", "some-vendor/brand-new-embed", true)));

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
        await(settings.saveModelPreferences(ModelPreferences.of("   ", "", true)));

        ModelPreferences stored = await(settings.loadModelPreferences());

        assertEquals(ModelCatalog.DEFAULT_CHAT_MODEL, stored.chatModel());
        assertEquals(ModelCatalog.DEFAULT_EMBEDDING_MODEL, stored.embeddingModel());
    }

    @Test
    void trimsWhitespaceFromAPastedModelId() {
        ModelPreferences stored = await(settings.saveModelPreferences(
                ModelPreferences.of("  openai/gpt-4o-mini  ", "  google/gemini-embedding-2  ", true)));

        assertEquals("openai/gpt-4o-mini", stored.chatModel());
        assertEquals("google/gemini-embedding-2", stored.embeddingModel());
    }

    @Test
    void replacesTheSelectionRatherThanAccumulatingRows() {
        await(settings.saveModelPreferences(
                ModelPreferences.of("openai/gpt-4o-mini", "google/gemini-embedding-2", true)));
        await(settings.saveModelPreferences(
                ModelPreferences.of("deepseek/deepseek-v4-pro", "qwen/qwen3-embedding-8b", true)));

        assertEquals("deepseek/deepseek-v4-pro", await(settings.loadModelPreferences()).chatModel());
    }

    @Test
    void survivesARestart() {
        // The settings table is a migration appended to the schema list, so it must be
        // created on an existing database as readily as on a fresh one.
        await(settings.saveModelPreferences(
                ModelPreferences.of("deepseek/deepseek-v4-pro", "qwen/qwen3-embedding-8b", true)));
        database.close();

        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db"), TokenCipher.disabled(), models));

        assertEquals("deepseek/deepseek-v4-pro",
                await(database.settings().loadModelPreferences()).chatModel());
    }

    @Test
    void retrievesKnowledgeUntilThePlayerSaysOtherwise() {
        // A player who has never seen the switch gets the feature the mod advertises.
        assertTrue(await(settings.loadModelPreferences()).embeddingEnabled());
    }

    @Test
    void rememberstheSwitchAcrossARestart() {
        await(settings.saveModelPreferences(ModelPreferences.defaults().withEmbeddingEnabled(false)));
        database.close();

        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db"), TokenCipher.disabled(), models));

        assertFalse(await(database.settings().loadModelPreferences()).embeddingEnabled(),
                "a switch that resets on restart is a switch the player has to keep setting");
    }

    @Test
    void keepsTheEmbeddingModelWhenRetrievalIsSwitchedOff() {
        // Off is not "forget my choice". Switching back on must return the player to the
        // model they picked rather than to the default.
        await(settings.saveModelPreferences(
                ModelPreferences.of("openai/gpt-4o-mini", "qwen/qwen3-embedding-8b", false)));

        ModelPreferences stored = await(settings.loadModelPreferences());

        assertEquals("qwen/qwen3-embedding-8b", stored.embeddingModel());
        assertFalse(stored.embeddingEnabled());

        await(settings.saveModelPreferences(stored.withEmbeddingEnabled(true)));
        assertEquals("qwen/qwen3-embedding-8b",
                await(settings.loadModelPreferences()).embeddingModel());
    }

    @Test
    void treatsAnUnreadableFlagAsUnset() {
        // A value neither this repository nor a future one wrote — edited by hand, most
        // likely. Guessing at it would be worse than behaving as though it were never set.
        await(settings.put("model.embedding.enabled", "perhaps"));

        assertTrue(await(settings.loadModelPreferences()).embeddingEnabled());
    }

    @Test
    void keepsTheChoicesInTheGlobalModelAndOnlyTheSwitchInTheDatabase() {
        ModelChoice chat = new ModelChoice("deepseek", "deepseek/deepseek-v4-flash");
        await(settings.saveModelPreferences(ModelPreferences.of(
                chat, ModelChoice.throughOpenRouter("openai/text-embedding-3-small"), false)));

        assertEquals(chat, models.current().chat());
        assertTrue(models.saved());
        assertTrue(await(settings.find("model.chat")).isEmpty(), "the old row is never written again");
        assertEquals("false", await(settings.find("model.embedding.enabled")).orElseThrow());
    }

    @Test
    void keepsTheCustomProvidersWhenTheModelsAreSaved() {
        ModelProvider homelab = new ModelProvider("homelab", "http://127.0.0.1:8080/v1");
        await(models.save(models.current().withProvider(homelab)));

        await(settings.saveModelPreferences(ModelPreferences.of(
                ModelChoice.qualified("homelab", "llama3"), models.current().embedding(), true)));

        assertEquals(List.of(homelab), models.current().customProviders());
        assertEquals("homelab/llama3", await(settings.loadModelPreferences()).chatModel());
    }

    @Test
    void findsTheSettingsUnderAPrefixAndNoOthers() {
        await(settings.put("api.base-url.deepseek", "https://eu.deepseek.example/v1"));
        await(settings.put("api.base-url.homelab", "http://127.0.0.1:8080/v1"));
        await(settings.put("api_base-url.lookalike", "nope"));

        assertEquals(List.of("api.base-url.deepseek", "api.base-url.homelab"),
                List.copyOf(await(settings.findStartingWith("api.base-url.")).keySet()));
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
