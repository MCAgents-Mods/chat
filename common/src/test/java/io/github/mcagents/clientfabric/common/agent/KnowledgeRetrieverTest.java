package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.ChatCompletion;
import io.github.mcagents.clientfabric.api.agent.ChatRequest;
import io.github.mcagents.clientfabric.api.agent.ModelClient;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.model.ModelPreferences;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing which Knowledge Packs go in front of a question.
 *
 * <p>The vectors here are deliberately trivial — unit axes and their combinations — so the
 * expected ranking is arithmetic rather than a judgement about what a model would produce.
 */
class KnowledgeRetrieverTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private RecordingClient client;
    private KnowledgeRetriever retriever;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        await(database.contents().saveCategory(1, "Guides"));
        client = new RecordingClient();
        retriever = new KnowledgeRetriever(database, client);
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    private static ModelPreferences on() {
        return ModelPreferences.defaults();
    }

    private static ModelPreferences off() {
        return ModelPreferences.defaults().withEmbeddingEnabled(false);
    }

    private void store(String id, String body, String embedding) {
        await(database.contents().save(new KnowledgeContent(
                id, 1, "1.0.0", false, "Pack " + id, body, embedding,
                Instant.now(), Instant.now())));
    }

    @Test
    void doesNothingAtAllWhenTheSwitchIsOff() {
        store("redstone", "Repeaters delay a signal.", "[1, 0, 0]");

        assertTrue(await(retriever.retrieve(off(), "how do repeaters work?")).isEmpty());
        assertEquals(0, client.embedCalls.get(),
                "off must cost nothing: no embedding call means no charge for a feature "
                        + "the player switched off");
    }

    @Test
    void putsTheClosestPackFirst() {
        store("redstone", "Repeaters delay a signal.", "[1, 0, 0]");
        store("farming", "Wheat needs light.", "[0, 1, 0]");
        store("nether", "Portals need obsidian.", "[0, 0, 1]");

        client.vector = new float[] {0.9f, 0.1f, 0};

        List<KnowledgeContent> chosen = await(retriever.retrieve(on(), "repeaters?"));

        assertEquals("redstone", chosen.get(0).id());
    }

    @Test
    void leavesOutPacksThatAreNotCloseEnough() {
        store("redstone", "Repeaters delay a signal.", "[1, 0, 0]");
        store("unrelated", "Nothing to do with it.", "[0, 1, 0]");

        client.vector = new float[] {1, 0, 0};

        List<KnowledgeContent> chosen = await(retriever.retrieve(on(), "repeaters?"));

        assertEquals(1, chosen.size(), "an orthogonal pack scores zero and must not be sent");
        assertEquals("redstone", chosen.get(0).id());
    }

    @Test
    void attachesNoMoreThanTheCap() {
        // Every pack is a perfect match, so only the cap can limit what is attached.
        for (int i = 0; i < KnowledgeRetriever.MAX_PACKS + 3; i++) {
            store("pack-" + i, "Body " + i, "[1, 0, 0]");
        }
        client.vector = new float[] {1, 0, 0};

        assertEquals(KnowledgeRetriever.MAX_PACKS,
                await(retriever.retrieve(on(), "anything")).size());
    }

    @Test
    void skipsAPackTooLargeForTheBudget() {
        store("huge", "x".repeat(KnowledgeRetriever.CHARACTER_BUDGET + 1), "[1, 0, 0]");
        store("small", "A short and useful note.", "[1, 0, 0]");

        client.vector = new float[] {1, 0, 0};

        List<KnowledgeContent> chosen = await(retriever.retrieve(on(), "anything"));

        assertEquals(List.of("small"), chosen.stream().map(KnowledgeContent::id).toList(),
                "the oversized pack is skipped rather than truncated, and skipping it does "
                        + "not stop the next one being attached");
    }

    @Test
    void ignoresPacksEmbeddedWithADifferentModel() {
        // A different vector length is a different vector space. Scoring across the two
        // would multiply numbers that mean nothing to each other.
        store("other-space", "Text embedded by another model.", "[1, 0, 0, 0, 0, 0]");
        store("this-space", "Text embedded by this one.", "[1, 0, 0]");

        client.vector = new float[] {1, 0, 0};

        assertEquals(List.of("this-space"),
                await(retriever.retrieve(on(), "anything")).stream()
                        .map(KnowledgeContent::id).toList());
    }

    @Test
    void ignoresPacksWithNoEmbeddingOrABrokenOne() {
        store("empty", "Published without a vector.", "[]");
        store("missing", "No vector at all.", null);
        store("broken", "Not a vector.", "{\"nope\":1}");
        store("usable", "The only comparable pack.", "[1, 0, 0]");

        client.vector = new float[] {1, 0, 0};

        assertEquals(List.of("usable"),
                await(retriever.retrieve(on(), "anything")).stream()
                        .map(KnowledgeContent::id).toList());
    }

    @Test
    void answersWithoutKnowledgeWhenTheEmbeddingCallFails() {
        // The whole point: retrieval improves an answer, it is not a precondition for one.
        // A player whose embedding model is wrong, rate limited, or unreachable must still
        // be able to chat.
        store("redstone", "Repeaters delay a signal.", "[1, 0, 0]");
        client.failure = new ModelException(ModelException.Kind.RATE_LIMIT, "slow down");

        List<KnowledgeContent> chosen =
                assertDoesNotThrow(() -> retriever.retrieve(on(), "repeaters?")
                        .get(10, TimeUnit.SECONDS));

        assertTrue(chosen.isEmpty());
    }

    @Test
    void doesNotEmbedABlankQuestion() {
        store("redstone", "Repeaters delay a signal.", "[1, 0, 0]");

        assertTrue(await(retriever.retrieve(on(), "   ")).isEmpty());
        assertEquals(0, client.embedCalls.get());
    }

    @Test
    void embedsWithTheChosenEmbeddingModel() {
        store("redstone", "Repeaters delay a signal.", "[1, 0, 0]");
        client.vector = new float[] {1, 0, 0};

        await(retriever.retrieve(on().withEmbeddingModel("qwen/qwen3-embedding-8b"), "hello"));

        assertEquals("qwen/qwen3-embedding-8b", client.lastModel,
                "the chat model would produce a vector in the wrong space, if it produced "
                        + "one at all");
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }

    /** A model client that returns a fixed vector and remembers how it was called. */
    private static final class RecordingClient implements ModelClient {

        private final AtomicInteger embedCalls = new AtomicInteger();
        private float[] vector = new float[] {1, 0, 0};
        private String lastModel;
        private RuntimeException failure;

        @Override
        public CompletableFuture<ChatCompletion> complete(ChatRequest request) {
            throw new UnsupportedOperationException("retrieval does not complete chats");
        }

        @Override
        public CompletableFuture<float[]> embed(String model, String text) {
            embedCalls.incrementAndGet();
            lastModel = model;
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(vector.clone());
        }

        @Override
        public void close() {
        }
    }
}
