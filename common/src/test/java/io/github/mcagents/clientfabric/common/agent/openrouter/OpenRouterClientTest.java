package io.github.mcagents.clientfabric.common.agent.openrouter;

import io.github.mcagents.clientfabric.api.agent.ChatCompletion;
import io.github.mcagents.clientfabric.api.agent.ChatRequest;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
 * Key rotation, retries, and error mapping — all without a network or an API key.
 *
 * <p>Every one of these is a behaviour a player would otherwise only discover in the worst
 * possible moment: mid-conversation, with a key that just ran out of credit.
 */
class OpenRouterClientTest {

    private static final String ANSWER = """
            {"model":"deepseek/deepseek-v4-pro",
             "choices":[{"message":{"role":"assistant","content":"Mine below Y=15."}}],
             "usage":{"prompt_tokens":120,"completion_tokens":8}}
            """;

    private static ChatRequest request() {
        return new ChatRequest("deepseek/deepseek-v4-pro",
                List.of(PromptMessage.system("Be brief."), PromptMessage.user("Where is netherite?")));
    }

    @Test
    void sendsTheKeyAsABearerTokenAndNothingElse() {
        var transport = TestAccess.transport().reply(200, ANSWER);
        var keys = TestAccess.keys("sk-or-v1-first");

        try (var client = TestAccess.client(transport, keys)) {
            assertEquals("Mine below Y=15.", await(client.complete(request())).text());
        }

        var call = transport.lastCall();
        assertEquals("Bearer sk-or-v1-first", call.headers().get("Authorization"));
        assertEquals("https://openrouter.ai/api/v1/chat/completions", call.url());
        assertTrue(call.body().contains("\"model\":\"deepseek/deepseek-v4-pro\""));
        assertTrue(call.body().contains("Where is netherite?"));
    }

    @Test
    void reportsWhatTheRequestCost() {
        var transport = TestAccess.transport().reply(200, ANSWER);
        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            ChatCompletion completion = await(client.complete(request()));

            assertEquals(120, completion.usage().promptTokens());
            assertEquals(8, completion.usage().completionTokens());
            assertEquals(128, completion.usage().totalTokens());
        }
    }

    @Test
    void rotatesToTheNextKeyWhenOneIsRejected() {
        // The case that matters: a key runs out of credit mid-conversation and the player
        // has another. They should never see a failure.
        var transport = TestAccess.transport()
                .reply(401, """
                        {"error":{"message":"No auth credentials found"}}""")
                .reply(200, ANSWER);
        var keys = TestAccess.keys("sk-or-v1-dead", "sk-or-v1-live");

        try (var client = TestAccess.client(transport, keys)) {
            assertEquals("Mine below Y=15.", await(client.complete(request())).text());
        }

        assertEquals(Set("sk-or-v1-dead"), keys.rejected());
        assertEquals("Bearer sk-or-v1-live", transport.lastCall().headers().get("Authorization"));
    }

    @Test
    void treatsRunningOutOfCreditAsAKeyToRotatePastNotAFailure() {
        var transport = TestAccess.transport()
                .reply(402, """
                        {"error":{"message":"Insufficient credits"}}""")
                .reply(200, ANSWER);
        var keys = TestAccess.keys("sk-or-v1-broke", "sk-or-v1-funded");

        try (var client = TestAccess.client(transport, keys)) {
            assertEquals("Mine below Y=15.", await(client.complete(request())).text());
        }
        assertEquals(Set("sk-or-v1-broke"), keys.rejected());
    }

    @Test
    void givesUpWithNoKeyWhenEveryKeyIsRejected() {
        var transport = TestAccess.transport()
                .reply(401, "{}")
                .reply(401, "{}");
        var keys = TestAccess.keys("a", "b");

        try (var client = TestAccess.client(transport, keys)) {
            ModelException failure = failureOf(client.complete(request()));
            assertEquals(ModelException.Kind.NO_KEY, failure.kind());
        }
        assertEquals(2, keys.rejected().size());
    }

    @Test
    void saysSoWhenThePlayerHasNoKeyAtAll() {
        var transport = TestAccess.transport();
        try (var client = TestAccess.client(transport, TestAccess.keys())) {
            ModelException failure = failureOf(client.complete(request()));

            assertEquals(ModelException.Kind.NO_KEY, failure.kind());
            assertEquals(0, transport.callCount(), "nothing should have been sent");
        }
    }

    @Test
    void retriesARateLimitAndThenSucceeds() {
        var transport = TestAccess.transport()
                .reply(429, """
                        {"error":{"message":"Rate limit exceeded"}}""")
                .reply(200, ANSWER);

        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            assertEquals("Mine below Y=15.", await(client.complete(request())).text());
        }
        assertEquals(2, transport.callCount());
    }

    @Test
    void retriesAProviderFailureAndATransportFailure() {
        var transport = TestAccess.transport()
                .reply(503, "{}")
                .fail(new IOException("connection reset"))
                .reply(200, ANSWER);

        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            assertEquals("Mine below Y=15.", await(client.complete(request())).text());
        }
        assertEquals(3, transport.callCount());
    }

    @Test
    void stopsRetryingAfterThreeAttempts() {
        var transport = TestAccess.transport()
                .reply(503, "{}")
                .reply(503, "{}")
                .reply(503, "{}");

        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            ModelException failure = failureOf(client.complete(request()));
            assertEquals(ModelException.Kind.SERVER, failure.kind());
        }
        assertEquals(3, transport.callCount(), "three attempts, not an unbounded loop");
    }

    @Test
    void neverRetriesABadRequest() {
        // A model id the provider does not serve will not start working on a second try.
        var transport = TestAccess.transport().reply(400, """
                {"error":{"message":"not a valid model id"}}""");

        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            ModelException failure = failureOf(client.complete(request()));

            assertEquals(ModelException.Kind.REQUEST, failure.kind());
            assertEquals("not a valid model id", failure.getMessage(),
                    "the provider's own wording is more useful than anything we could invent");
        }
        assertEquals(1, transport.callCount());
    }

    @Test
    void survivesAGatewayAnsweringWithHtmlInsteadOfJson() {
        // A gateway failure is retryable, so all three attempts are scripted.
        var transport = TestAccess.transport()
                .reply(502, "<html><body>Bad Gateway</body></html>")
                .reply(502, "<html><body>Bad Gateway</body></html>")
                .reply(502, "<html><body>Bad Gateway</body></html>");

        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            ModelException failure = failureOf(client.complete(request()));
            assertEquals(ModelException.Kind.SERVER, failure.kind());
            assertTrue(failure.getMessage().contains("502"),
                    "falling back to the status beats echoing a page of HTML");
        }
    }

    @Test
    void reportsAnEmptyChoicesArrayRatherThanCrashing() {
        var transport = TestAccess.transport().reply(200, """
                {"choices":[]}""");

        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            ModelException failure = failureOf(client.complete(request()));
            assertEquals(ModelException.Kind.MALFORMED_RESPONSE, failure.kind());
            assertTrue(failure.getMessage().contains("no choices"));
        }
        // The request succeeded and was billed. Retrying it would bill the player again
        // for the same broken answer, so exactly one call must have been made.
        assertEquals(1, transport.callCount());
    }

    @Test
    void readsAnEmbedding() {
        var transport = TestAccess.transport().reply(200, """
                {"data":[{"embedding":[0.25,-0.5,0.75]}]}""");

        try (var client = TestAccess.client(transport, TestAccess.keys("k"))) {
            float[] vector = await(client.embed("mistralai/mistral-embed-2312", "netherite"));

            assertEquals(3, vector.length);
            assertEquals(0.25f, vector[0]);
            assertEquals(-0.5f, vector[1]);
            assertEquals("https://openrouter.ai/api/v1/embeddings", transport.lastCall().url());
        }
    }

    @Test
    void closesTheTransportItWasGiven() {
        var transport = TestAccess.transport();
        var client = TestAccess.client(transport, TestAccess.keys("k"));

        assertFalse(transport.isClosed());
        client.close();
        assertTrue(transport.isClosed());
    }

    private static java.util.Set<String> Set(String... values) {
        return java.util.Set.of(values);
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }

    private static ModelException failureOf(CompletableFuture<?> future) {
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertTrue(thrown.getCause() instanceof ModelException,
                "expected a ModelException, got " + thrown.getCause());
        return (ModelException) thrown.getCause();
    }
}
