package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.core.api.model.ChatCompletion;
import io.github.mcagentsmods.core.api.model.ChatRequest;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.model.ModelException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Giving stored packs a vector they can be retrieved against.
 *
 * <p>Packs are published as text with no embedding, because a vector is only comparable
 * inside the space of the model that produced it. Without this, a freshly downloaded pack
 * is inert: it is in the library, it is readable, and retrieval can never select it.
 */
class EmbeddingBackfillTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private RecordingClient client;
    private EmbeddingBackfill backfill;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        await(database.contents().saveCategory(1, "Guides"));
        client = new RecordingClient();
        backfill = new EmbeddingBackfill(database, client);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void store(String id, String body, String embedding) {
        await(database.contents().save(new KnowledgeContent(id, 1, "1.0.0", false,
                "Pack " + id, body, embedding, Instant.now(), Instant.now())));
    }

    private String embeddingOf(String id) {
        return await(database.contents().findById(id)).orElseThrow().embeddedData();
    }

    @Test
    void embedsAFreshlyDownloadedPack() {
        store("new", "Repeaters delay a signal.", "");

        EmbeddingBackfill.Result result = await(backfill.run(ModelPreferences.defaults()));

        assertEquals(1, result.embedded());
        assertTrue(await(database.contents().findById("new")).orElseThrow().hasEmbedding());
    }

    @Test
    void leavesAPackThatAlreadyHasOneAlone() {
        store("done", "Already embedded.", "[1.0,0.0,0.0]");

        assertEquals(0, await(backfill.run(ModelPreferences.defaults())).embedded());
        assertEquals(0, client.calls.get(),
                "pressing the button twice must cost one request, not a library's worth");
    }

    @Test
    void reEmbedsTheWholeLibraryWhenTheModelChanges() {
        // What changing the embedding model does. The stored vectors still parse; they are
        // simply not comparable with anything the new model produces, which is invisible
        // until retrieval quietly stops selecting anything.
        store("a", "First.", "");
        store("b", "Second.", "");
        await(backfill.run(ModelPreferences.defaults()));
        client.calls.set(0);
        client.width = 6;

        EmbeddingBackfill.Result result = await(backfill.run(
                ModelPreferences.defaults().withEmbeddingModel("some/other-embedder")));

        assertEquals(2, result.embedded(), "every pack, not the ones that look like outliers");
        assertEquals(6, parseWidth(embeddingOf("a")));
        assertEquals(6, parseWidth(embeddingOf("b")));
    }

    @Test
    void doesNotReEmbedWhenTheModelIsUnchanged() {
        store("a", "First.", "");
        await(backfill.run(ModelPreferences.defaults()));
        client.calls.set(0);

        assertEquals(0, await(backfill.run(ModelPreferences.defaults())).embedded());
        assertEquals(0, client.calls.get());
    }

    @Test
    void keepsLookingAfterAModelChangeUntilARunSucceedsCompletely() {
        // The model is recorded only when nothing failed. Recording it after a partial run
        // would claim the whole library was in the new space and hide the stragglers from
        // the next press of the button, which is the one thing that would fix them.
        store("a", "First.", "");
        store("b", "Second.", "");
        await(backfill.run(ModelPreferences.defaults()));

        client.failOn = "Second.";
        ModelPreferences changed =
                ModelPreferences.defaults().withEmbeddingModel("some/other-embedder");
        assertEquals(1, await(backfill.run(changed)).failed());

        assertEquals(2, await(backfill.status(changed)).needing(),
                "still the whole library, because the run did not finish");
    }

    @Test
    void countsWhatNeedsDoingWithoutCallingTheProvider() {
        store("has-one", "Embedded.", "[1.0,0.0,0.0]");
        store("needs-one", "Not embedded.", "");
        store("also-needs", "Nor this.", null);

        EmbeddingBackfill.Status status = await(backfill.status(ModelPreferences.defaults()));

        assertEquals(3, status.packs());
        assertEquals(2, status.needing());
        assertTrue(status.anyNeeded());
        assertEquals(0, client.calls.get(), "a count must be free, or a page cannot show it");
    }

    @Test
    void ignoresAPackWithNoTextToEmbed() {
        // Nothing to embed and nothing the player can do about it. Counting it would leave
        // a number on the page that never goes down however many times the button is pressed.
        store("empty", "", "");

        assertEquals(0, await(backfill.status(ModelPreferences.defaults())).needing());
        assertEquals(0, await(backfill.run(ModelPreferences.defaults())).embedded());
    }

    @Test
    void keepsGoingWhenOnePackFails() {
        store("a", "First.", "");
        store("b", "Second.", "");
        store("c", "Third.", "");
        client.failOn = "Second.";

        EmbeddingBackfill.Result result = await(backfill.run(ModelPreferences.defaults()));

        assertEquals(2, result.embedded());
        assertEquals(1, result.failed());
        assertEquals(List.of("b"), result.failedIds());
        assertFalse(await(database.contents().findById("b")).orElseThrow().hasEmbedding(),
                "the pack that failed is left exactly as it was");
    }

    @Test
    void changesNothingButTheVector() {
        // The version in particular. Writing a vector must not make the stored pack look
        // like a different version of itself.
        store("pack", "Body text.", "");

        await(backfill.run(ModelPreferences.defaults()));

        KnowledgeContent after = await(database.contents().findById("pack")).orElseThrow();
        assertEquals("1.0.0", after.version());
        assertEquals("Body text.", after.rawContent());
        assertEquals("Pack pack", after.title());
        assertEquals(1, after.categoryId());
    }

    @Test
    void embedsWithTheChosenEmbeddingModel() {
        store("pack", "Body text.", "");

        await(backfill.run(ModelPreferences.defaults()
                .withEmbeddingModel("qwen/qwen3-embedding-8b")));

        assertEquals("qwen/qwen3-embedding-8b", client.lastModel,
                "questions will be embedded by this model, so packs must be too");
    }

    @Test
    void runsEvenWhenRetrievalIsSwitchedOff() {
        // The switch governs whether retrieval happens, not whether a library may be
        // prepared. Refusing here would mean a player could not get ready to turn it on.
        store("pack", "Body text.", "");

        assertEquals(1, await(backfill.run(
                ModelPreferences.defaults().withEmbeddingEnabled(false))).embedded());
    }

    @Test
    void writesAVectorRetrievalCanActuallyUse() {
        // The round trip that matters: what this writes must be what the retriever reads.
        store("pack", "Repeaters delay a signal.", "");
        await(backfill.run(ModelPreferences.defaults()));

        List<KnowledgeContent> chosen = await(
                new KnowledgeRetriever(database, client).retrieve(ModelPreferences.defaults(),
                        "how do repeaters work?"));

        assertEquals(List.of("pack"), chosen.stream().map(KnowledgeContent::id).toList());
    }

    @Test
    void leavesNothingNeedingWorkAfterARun() {
        store("a", "First.", "");
        store("b", "Second.", "");

        await(backfill.run(ModelPreferences.defaults()));

        assertFalse(await(backfill.status(ModelPreferences.defaults())).anyNeeded());
    }

    private static int parseWidth(String json) {
        return json.split(",").length;
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }

    /** Returns a fixed-width unit vector and remembers how it was called. */
    private static final class RecordingClient implements ModelClient {

        private final AtomicInteger calls = new AtomicInteger();
        private int width = 3;
        private String lastModel;
        private String failOn;

        @Override
        public CompletableFuture<ChatCompletion> complete(ChatRequest request) {
            throw new UnsupportedOperationException("embedding does not complete chats");
        }

        @Override
        public CompletableFuture<float[]> embed(String model, String text) {
            calls.incrementAndGet();
            lastModel = model;
            if (text.equals(failOn)) {
                return CompletableFuture.failedFuture(
                        new ModelException(ModelException.Kind.RATE_LIMIT, "slow down"));
            }
            float[] vector = new float[width];
            vector[0] = 1;
            return CompletableFuture.completedFuture(vector);
        }

        @Override
        public void close() {
        }
    }
}
