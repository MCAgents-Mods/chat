package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.core.api.model.GlobalModelStore;
import io.github.mcagentsmods.core.api.model.GlobalModels;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat's old model rows move into the global model once, each model keeping its route. */
class LegacyModelSettingsTest {

    @TempDir
    Path gameDirectory;

    private GlobalModelStore store;
    private LocalDatabase database;

    @BeforeEach
    void open() {
        store = GlobalModelStore.inMemory(GlobalModels.DEFAULTS);
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db"),
                TokenCipher.disabled(), store));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aModelWithAKeyAndAnAddressKeepsGoingStraightToItsPlatform() {
        await(database.settings().put("model.chat", "deepseek/deepseek-v4-flash"));
        await(database.apiKeys().add("sk-deepseek", "deepseek"));

        assertTrue(await(LegacyModelSettings.migrate(database, store)));

        assertEquals(new ModelChoice("deepseek", "deepseek/deepseek-v4-flash"), store.current().chat());
        assertEquals(GlobalModels.DEFAULTS.embedding(), store.current().embedding());
    }

    @Test
    void aModelWithNoKeyForItsPlatformKeepsGoingThroughOpenRouter() {
        await(database.settings().put("model.chat", "deepseek/deepseek-v4-flash"));
        await(database.settings().put("model.embedding", "qwen/qwen3-embedding-8b"));

        assertTrue(await(LegacyModelSettings.migrate(database, store)));

        assertEquals(ModelChoice.throughOpenRouter("deepseek/deepseek-v4-flash"), store.current().chat());
        assertEquals(ModelChoice.throughOpenRouter("qwen/qwen3-embedding-8b"), store.current().embedding());
    }

    @Test
    void eachUsableAddressBecomesACustomProvider() {
        await(database.settings().put("api.base-url.homelab", "http://127.0.0.1:8080/v1"));
        await(database.settings().put("api.base-url.remote", "http://example.com/v1"));
        await(database.settings().put("api.base-url.cleared", ""));
        await(database.settings().put("model.chat", "homelab/llama3"));
        await(database.apiKeys().add("sk-home", "homelab"));

        await(LegacyModelSettings.migrate(database, store));

        GlobalModels moved = store.current();
        assertEquals(1, moved.customProviders().size(), "a plain-text remote address is never taken");
        assertEquals("http://127.0.0.1:8080/v1", moved.provider("homelab").orElseThrow().baseUrl());
        assertEquals(new ModelChoice("homelab", "homelab/llama3"), moved.chat());
    }

    @Test
    void runsOnlyWhileTheGlobalModelWasNeverSaved() {
        await(database.settings().put("model.chat", "openai/gpt-4o-mini"));
        ModelChoice chosen = new ModelChoice("deepseek", "deepseek/deepseek-v4-flash");
        await(store.save(GlobalModels.DEFAULTS.withChat(chosen)));

        assertFalse(await(LegacyModelSettings.migrate(database, store)));
        assertEquals(chosen, store.current().chat());
    }

    @Test
    void leavesAFreshPlayerOnTheDefaults() {
        assertFalse(await(LegacyModelSettings.migrate(database, store)));
        assertFalse(store.saved());
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
