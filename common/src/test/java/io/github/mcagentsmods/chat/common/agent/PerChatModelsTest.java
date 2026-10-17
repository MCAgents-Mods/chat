package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.model.SessionModels;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.model.ChatCompletion;
import io.github.mcagentsmods.core.api.model.ChatRequest;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.model.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A conversation answered and embedded with the models it chose for itself, and with the
 * global model wherever it chose none.
 */
@Timeout(30)
class PerChatModelsTest {

    private static final ModelChoice OWN_CHAT = new ModelChoice("deepseek", "deepseek/deepseek-v4-pro");
    private static final ModelChoice OWN_EMBEDDING =
            ModelChoice.throughOpenRouter("qwen/qwen3-embedding-8b");

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private final RecordingClient client = new RecordingClient();

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String conversation(SessionModels models) {
        String id = await(database.chat().createSession("Test")).id();
        await(database.chat().saveSessionModels(id, models));
        await(database.chat().appendMessage(id, ChatRole.USER, "How do repeaters work?"));
        return id;
    }

    private void ask(String sessionId) {
        await(new OpenRouterAgent(database, client, new FixedPersona())
                .respond(sessionId, "How do repeaters work?"));
    }

    @Test
    void aConversationThatChoseNothingIsAnsweredWithTheGlobalModel() {
        ask(conversation(SessionModels.GLOBAL));

        ChatRequest sent = client.requests.getLast();
        assertEquals(ModelPreferences.defaults().chatModel(), sent.model());
        assertEquals(ModelPreferences.defaults().chat().provider(), sent.provider());
        assertEquals(ModelPreferences.defaults().embeddingModel(), client.embeddedWith.getFirst());
    }

    @Test
    void aConversationIsAnsweredAndEmbeddedWithItsOwnModels() {
        ask(conversation(new SessionModels(OWN_CHAT, OWN_EMBEDDING)));

        ChatRequest sent = client.requests.getLast();
        assertEquals("deepseek/deepseek-v4-pro", sent.model());
        assertEquals("deepseek", sent.provider(), "the provider is part of the choice");
        assertEquals("qwen/qwen3-embedding-8b", client.embeddedWith.getFirst());
    }

    @Test
    void aChoiceLeftOnGlobalFollowsTheModelsPage() {
        String id = conversation(SessionModels.GLOBAL.withEmbedding(OWN_EMBEDDING));
        await(database.settings().saveModelPreferences(ModelPreferences.defaults()
                .withChat(ModelChoice.throughOpenRouter("openai/gpt-4o-mini"))));

        ask(id);

        assertEquals("openai/gpt-4o-mini", client.requests.getLast().model(),
                "a change on the Models page reaches the next question at once");
        assertEquals("qwen/qwen3-embedding-8b", client.embeddedWith.getFirst());
    }

    @Test
    void theLibraryIsSearchedInTheConversationsOwnEmbeddingModel() {
        await(database.contents().saveCategory(1, "Guides"));
        await(database.contents().save(new KnowledgeContent("redstone", 1, "1.0.0", false,
                "Redstone", "Repeaters delay a signal.", "[1, 0, 0]", Instant.now(), Instant.now())));

        ask(conversation(SessionModels.GLOBAL.withEmbedding(OWN_EMBEDDING)));

        assertEquals(List.of("qwen/qwen3-embedding-8b", "qwen/qwen3-embedding-8b"), client.embeddedWith,
                "the question, then the pack, both in the conversation's model: the stored "
                        + "vector is in the global model's space");
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }

    private static final class FixedPersona implements PersonaStore {

        @Override
        public CompletableFuture<String> read() {
            return CompletableFuture.completedFuture("Be terse.");
        }

        @Override
        public CompletableFuture<String> save(String instructions) {
            return CompletableFuture.completedFuture(instructions);
        }

        @Override
        public CompletableFuture<Boolean> ensureCreated() {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static final class RecordingClient implements ModelClient {

        private final List<ChatRequest> requests = new ArrayList<>();
        private final List<String> embeddedWith = new ArrayList<>();

        @Override
        public synchronized CompletableFuture<ChatCompletion> complete(ChatRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(
                    new ChatCompletion("Answered.", request.model(), TokenUsage.UNKNOWN));
        }

        @Override
        public synchronized CompletableFuture<float[]> embed(String model, String text) {
            embeddedWith.add(model);
            return CompletableFuture.completedFuture(new float[] {1, 0, 0});
        }

        @Override
        public void close() {
        }
    }
}
