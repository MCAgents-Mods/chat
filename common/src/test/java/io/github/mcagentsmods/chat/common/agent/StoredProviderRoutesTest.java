package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.api.model.ProviderRoute;
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

/**
 * Which of the two routes a request takes, decided from what the player actually stored.
 *
 * <p>Every case that is not "a key and an address, both for this platform" has to come out as
 * the gateway. That is the fallback the whole feature rests on: a player who has configured
 * nothing must get the mod that existed before direct routes did.
 */
class StoredProviderRoutesTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private StoredProviderRoutes routes;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        routes = new StoredProviderRoutes(database.settings(), database.apiKeys());
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void sendsAnUnqualifiedModelThroughTheGateway() {
        // No platform segment, so nothing to route on. The mod does not invent one.
        ProviderRoute route = await(routes.route("gpt-4o-mini"));

        assertTrue(route.isThroughOpenRouter());
        assertEquals(ModelPlatform.OPENROUTER_BASE_URL + "/chat/completions", route.chatUrl());
        assertEquals("gpt-4o-mini", route.modelId());
    }

    @Test
    void sendsTheWholeStoredNameToTheGateway() {
        // The gateway addresses models by {platform}/{model}, so the stored name goes as it is.
        ProviderRoute route = await(routes.route("openai/gpt-4o-mini"));

        assertTrue(route.isThroughOpenRouter());
        assertEquals("openai/gpt-4o-mini", route.modelId());
    }

    @Test
    void sendsAPlatformStraightToItsOwnApiOnceThereIsAKeyForIt() {
        await(database.apiKeys().add("sk-openai-first", "openai"));

        ProviderRoute route = await(routes.route("openai/gpt-4o-mini"));

        assertFalse(route.isThroughOpenRouter());
        assertEquals("openai", route.platform());
        assertEquals("https://api.openai.com/v1/chat/completions", route.chatUrl());
        // The platform's own id, not the qualified one: OpenAI has never heard of the prefix.
        assertEquals("gpt-4o-mini", route.modelId());
    }

    @Test
    void keepsThePlatformOnTheGatewayWhileItHasNoKeyOfItsOwn() {
        // An address with no key would arrive unauthenticated, and borrowing the gateway's
        // key would disclose it to somewhere it does not belong.
        await(database.apiKeys().add("sk-or-v1-first"));

        assertTrue(await(routes.route("openai/gpt-4o-mini")).isThroughOpenRouter());
    }

    @Test
    void keepsAPlatformItKnowsNoAddressForOnTheGateway() {
        await(database.apiKeys().add("sk-anthropic-first", "anthropic"));

        // Deliberately absent from the known addresses: Anthropic's own API is not the shape
        // this client speaks, so a direct call would be a request it rejects.
        assertTrue(await(routes.route("anthropic/claude-4-opus")).isThroughOpenRouter());
    }

    @Test
    void usesTheAddressThePlayerConfiguredOverTheOneItShips() {
        await(database.apiKeys().add("sk-openai-first", "openai"));
        await(database.settings().put(ModelPlatform.baseUrlSettingKey("openai"),
                "https://openai.example.test/v1"));

        ProviderRoute route = await(routes.route("openai/gpt-4o-mini"));

        assertEquals("https://openai.example.test/v1/chat/completions", route.chatUrl());
    }

    @Test
    void routesToAGatewayThePlayerRunsThemselves() {
        // A local server — Ollama, LM Studio, a proxy — is the case loopback is allowed for.
        await(database.apiKeys().add("sk-local-first", "local"));
        await(database.settings().put(ModelPlatform.baseUrlSettingKey("local"),
                "http://localhost:11434/v1"));

        ProviderRoute route = await(routes.route("local/llama-3.1-8b"));

        assertFalse(route.isThroughOpenRouter());
        assertEquals("http://localhost:11434/v1/embeddings", route.embeddingsUrl());
    }

    @Test
    void refusesToSendAKeyToAPlainTextAddress() {
        await(database.apiKeys().add("sk-openai-first", "openai"));
        await(database.settings().put(ModelPlatform.baseUrlSettingKey("openai"),
                "http://openai.example.test/v1"));

        // Ignored rather than used: the value being configured is where an API key gets sent.
        // The platform's own address is what the player plainly meant, so that is used.
        ProviderRoute route = await(routes.route("openai/gpt-4o-mini"));

        assertEquals("https://api.openai.com/v1/chat/completions", route.chatUrl());
    }

    @Test
    void putsAPlatformBackOnTheGatewayWhenItsAddressIsCleared() {
        await(database.apiKeys().add("sk-somewhere-first", "somewhere"));
        await(database.settings().put(ModelPlatform.baseUrlSettingKey("somewhere"),
                "https://somewhere.example.test/v1"));
        assertFalse(await(routes.route("somewhere/a-model")).isThroughOpenRouter());

        await(database.settings().put(ModelPlatform.baseUrlSettingKey("somewhere"), ""));

        assertTrue(await(routes.route("somewhere/a-model")).isThroughOpenRouter());
    }

    @Test
    void readsWhatIsStoredOnEveryRequest() {
        // A key added while the game is running takes effect on the next question, without a
        // restart — the same promise the model client already makes about keys.
        assertTrue(await(routes.route("openai/gpt-4o-mini")).isThroughOpenRouter());

        await(database.apiKeys().add("sk-openai-first", "openai"));

        assertFalse(await(routes.route("openai/gpt-4o-mini")).isThroughOpenRouter());
    }

    @Test
    void treatsADeactivatedKeyAsNoKeyAtAll() {
        await(database.apiKeys().add("sk-openai-first", "openai"));
        await(database.apiKeys().deactivate("sk-openai-first"));

        assertTrue(await(routes.route("openai/gpt-4o-mini")).isThroughOpenRouter());
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
