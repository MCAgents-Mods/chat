package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.core.api.model.ChatCompletion;
import io.github.mcagentsmods.core.api.model.ChatRequest;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.model.PromptMessage;
import io.github.mcagentsmods.core.api.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the local cache in front of the provider does, and what it refuses to do. */
class CachingModelClientTest {

    /** A delegate that counts calls and returns a distinguishable vector each time. */
    private static final class CountingClient implements ModelClient {
        int embedCalls;
        int completeCalls;
        boolean closed;

        @Override
        public CompletableFuture<ChatCompletion> complete(ChatRequest request) {
            completeCalls++;
            return CompletableFuture.completedFuture(
                    new ChatCompletion("answer " + completeCalls, "m", TokenUsage.UNKNOWN));
        }

        @Override
        public CompletableFuture<float[]> embed(String model, String text) {
            embedCalls++;
            return CompletableFuture.completedFuture(new float[] {embedCalls, 0.5f});
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private CountingClient delegate;
    private CachingModelClient client;

    @BeforeEach
    void setUp() {
        delegate = new CountingClient();
        client = new CachingModelClient(delegate);
    }

    @Test
    void embedsTheSameTextOnlyOnce() {
        // Retrieval embeds the player's question every turn, and questions repeat.
        for (int i = 0; i < 5; i++) {
            client.embed("mistralai/mistral-embed-2312", "how do I get netherite").join();
        }

        assertEquals(1, delegate.embedCalls);
        assertEquals(4, client.embeddingCacheStats().hits());
    }

    @Test
    void keepsModelsApart() {
        // The same text embedded by two models gives two different vectors. Mixing them
        // silently ruins retrieval, which is the worst kind of bug: nothing crashes.
        float[] first = client.embed("model-a", "netherite").join();
        float[] second = client.embed("model-b", "netherite").join();

        assertEquals(2, delegate.embedCalls);
        assertTrue(first[0] != second[0], "each model must get its own entry");
    }

    @Test
    void handsOutACopySoACallerCannotCorruptTheEntry() {
        // The vector is a mutable array. A caller that normalises it in place would
        // otherwise poison the cache for everyone after them.
        float[] first = client.embed("m", "netherite").join();
        float[] original = first.clone();
        first[0] = 999f;

        float[] second = client.embed("m", "netherite").join();

        assertArrayEquals(original, second);
        assertNotSame(first, second);
    }

    @Test
    void neverCachesACompletion() {
        // Asking the same question twice should be able to give a different answer, and a
        // player who asks again generally wants a second attempt, not a replay.
        String one = client.complete(request()).join().text();
        String two = client.complete(request()).join().text();

        assertEquals(2, delegate.completeCalls);
        assertEquals("answer 1", one);
        assertEquals("answer 2", two);
    }

    @Test
    void closesTheClientItWraps() {
        client.close();

        assertTrue(delegate.closed);
    }

    private static ChatRequest request() {
        return new ChatRequest("m", List.of(PromptMessage.user("same question")));
    }
}
