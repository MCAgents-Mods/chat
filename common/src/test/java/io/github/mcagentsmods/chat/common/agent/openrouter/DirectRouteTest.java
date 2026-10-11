package io.github.mcagentsmods.chat.common.agent.openrouter;

import io.github.mcagentsmods.chat.api.agent.ChatRequest;
import io.github.mcagentsmods.chat.api.agent.ModelException;
import io.github.mcagentsmods.chat.api.agent.PromptMessage;
import io.github.mcagentsmods.chat.api.agent.ProviderRoute;
import io.github.mcagentsmods.chat.api.agent.ProviderRoutes;
import io.github.mcagentsmods.chat.api.model.ModelName;
import io.github.mcagentsmods.chat.common.agent.HttpCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What changes on the wire when a platform is called directly instead of through the gateway.
 *
 * <p>Four things, and each one is a request the platform would otherwise reject or a key sent
 * somewhere it does not belong: the address, the model id, the gateway's own extensions, and
 * which stored key authenticates the call.
 */
class DirectRouteTest {

    private static final String BASE = "https://api.openai.com/v1";

    private static final String ANSWER = """
            {"model":"gpt-4o-mini",
             "choices":[{"message":{"role":"assistant","content":"Mine below Y=15."}}],
             "usage":{"prompt_tokens":120,"completion_tokens":8}}
            """;

    private static final String VECTOR = """
            {"data":[{"embedding":[0.5,0.25]}]}
            """;

    /** Routing under the test's control: this model, straight to that platform. */
    private static ProviderRoutes directly(String storedName) {
        return model -> CompletableFuture.completedFuture(model.equals(storedName)
                ? ProviderRoute.direct(ModelName.parse(model).orElseThrow(), BASE)
                : ProviderRoute.throughOpenRouter(model));
    }

    private static ChatRequest request() {
        return new ChatRequest("openai/gpt-4o-mini", List.of(
                PromptMessage.system("Be brief.").asCacheBreakpoint(),
                PromptMessage.user("Where is netherite?")));
    }

    @Test
    void postsToThePlatformsOwnAddress() {
        var transport = TestAccess.transport().reply(200, ANSWER);
        var keys = TestAccess.keys().forPlatform("openai", "sk-openai-first");

        try (var client = TestAccess.client(transport, keys, directly("openai/gpt-4o-mini"))) {
            assertEquals("Mine below Y=15.", await(client.complete(request())).text());
        }

        assertEquals(BASE + "/chat/completions", transport.lastCall().url());
    }

    @Test
    void namesTheModelTheWayThePlatformDoes() {
        var transport = TestAccess.transport().reply(200, ANSWER);
        var keys = TestAccess.keys().forPlatform("openai", "sk-openai-first");

        try (var client = TestAccess.client(transport, keys, directly("openai/gpt-4o-mini"))) {
            await(client.complete(request()));
        }

        HttpCall call = transport.lastCall();
        // The stored name is openai/gpt-4o-mini; OpenAI has never heard of the prefix.
        assertTrue(call.body().contains("\"model\":\"gpt-4o-mini\""));
        assertFalse(call.body().contains("openai/gpt-4o-mini"));
    }

    @Test
    void leavesOutTheExtensionsOnlyTheGatewayUnderstands() {
        var transport = TestAccess.transport().reply(200, ANSWER);
        var keys = TestAccess.keys().forPlatform("openai", "sk-openai-first");

        try (var client = TestAccess.client(transport, keys, directly("openai/gpt-4o-mini"))) {
            await(client.complete(request()));
        }

        HttpCall call = transport.lastCall();
        // Both of these are a 400 from a platform's own API — an unrecognised argument is
        // rejected outright rather than ignored, so one stray field fails the whole request.
        assertFalse(call.body().contains("\"usage\""), "usage.include is the gateway's");
        assertFalse(call.body().contains("cache_control"), "so is the cache breakpoint");
        // The message survives the breakpoint being dropped: it is written as plain content.
        assertTrue(call.body().contains("\"content\":\"Be brief.\""));
    }

    @Test
    void sendsNoAttributionHeadersToAPlatformThatDoesNotReadThem() {
        var transport = TestAccess.transport().reply(200, ANSWER);
        var keys = TestAccess.keys().forPlatform("openai", "sk-openai-first");

        try (var client = TestAccess.client(transport, keys, directly("openai/gpt-4o-mini"))) {
            await(client.complete(request()));
        }

        HttpCall call = transport.lastCall();
        assertEquals("Bearer sk-openai-first", call.headers().get("Authorization"));
        assertFalse(call.headers().containsKey("HTTP-Referer"));
        assertFalse(call.headers().containsKey("X-Title"));
    }

    @Test
    void authenticatesWithThatPlatformsKeyAndNoOther() {
        var transport = TestAccess.transport().reply(200, ANSWER);
        // A gateway key is stored too. It must not be the one that travels.
        var keys = TestAccess.keys("sk-or-v1-first").forPlatform("openai", "sk-openai-first");

        try (var client = TestAccess.client(transport, keys, directly("openai/gpt-4o-mini"))) {
            await(client.complete(request()));
        }

        assertEquals("Bearer sk-openai-first",
                transport.lastCall().headers().get("Authorization"));
    }

    @Test
    void saysWhichPlatformHasNoKeyWhenNoneIsStored() {
        var transport = TestAccess.transport();
        var keys = TestAccess.keys("sk-or-v1-first");

        try (var client = TestAccess.client(transport, keys, directly("openai/gpt-4o-mini"))) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> client.complete(request()).join());

            ModelException error = (ModelException) failure.getCause();
            assertEquals(ModelException.Kind.NO_KEY, error.kind());
            assertTrue(error.getMessage().contains("openai"),
                    "the player has to be told which key is missing");
        }
        assertEquals(0, transport.callCount(), "nothing is sent without a key for the route");
    }

    @Test
    void embedsAgainstThePlatformsOwnEndpoint() {
        var transport = TestAccess.transport().reply(200, VECTOR);
        var keys = TestAccess.keys().forPlatform("openai", "sk-openai-first");
        var routes = directly("openai/text-embedding-3-small");

        try (var client = TestAccess.client(transport, keys, routes)) {
            assertEquals(2, await(client.embed("openai/text-embedding-3-small", "netherite")).length);
        }

        HttpCall call = transport.lastCall();
        assertEquals(BASE + "/embeddings", call.url());
        assertTrue(call.body().contains("\"model\":\"text-embedding-3-small\""));
    }

    @Test
    void stillSendsEverythingItAlwaysDidThroughTheGateway() {
        // The other half of the change: a request that is not routed anywhere new must be
        // exactly the request it was, extensions and attribution included.
        var transport = TestAccess.transport().reply(200, ANSWER);

        try (var client = TestAccess.client(transport, TestAccess.keys("sk-or-v1-first"))) {
            await(client.complete(request()));
        }

        HttpCall call = transport.lastCall();
        assertEquals("https://openrouter.ai/api/v1/chat/completions", call.url());
        assertTrue(call.body().contains("\"model\":\"openai/gpt-4o-mini\""));
        assertTrue(call.body().contains("cache_control"));
        assertTrue(call.body().contains("\"usage\""));
        assertEquals("MCAgentsClient", call.headers().get("X-Title"));
    }

    @Test
    void rotatesWithinThePlatformWhenItsKeyIsRejected() {
        var transport = TestAccess.transport()
                .reply(401, "{\"error\":{\"message\":\"no\"}}")
                .reply(200, ANSWER);
        var keys = TestAccess.keys()
                .forPlatform("openai", "sk-openai-first")
                .forPlatform("openai", "sk-openai-second");

        try (var client = TestAccess.client(transport, keys, directly("openai/gpt-4o-mini"))) {
            assertEquals("Mine below Y=15.", await(client.complete(request())).text());
        }

        assertEquals("Bearer sk-openai-second",
                transport.lastCall().headers().get("Authorization"));
        assertTrue(keys.rejected().contains("sk-openai-first"));
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
