package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.agent.ChatCompletion;
import io.github.mcagentsmods.chat.api.agent.ChatRequest;
import io.github.mcagentsmods.chat.api.agent.ModelClient;
import io.github.mcagentsmods.chat.api.agent.PromptMessage;
import io.github.mcagentsmods.chat.api.agent.TokenUsage;
import io.github.mcagentsmods.chat.api.agent.ToolBroker;
import io.github.mcagentsmods.chat.api.agent.ToolCall;
import io.github.mcagentsmods.chat.api.agent.ToolDefinition;
import io.github.mcagentsmods.chat.api.agent.ToolOutcome;
import io.github.mcagentsmods.chat.api.model.ChatRole;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision-making loop: the model asks for tools, they run, and it answers.
 *
 * <p>Driven through a scripted model client, so the loop is exercised without a provider,
 * an API key, or a running MCP server. What is being tested is the shape of the exchange —
 * what gets appended, in what order, and when the loop stops.
 */
@Timeout(30)
class ToolCallingLoopTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ScriptedClient client;
    private RecordingBroker broker;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        client = new ScriptedClient();
        broker = new RecordingBroker();
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    private String ask(String question) {
        String sessionId = startSession(question);
        return await(agent().respond(sessionId, question));
    }

    private String startSession(String question) {
        String sessionId = await(database.chat().createSession("Test")).id();
        await(database.chat().appendMessage(sessionId, ChatRole.USER, question));
        return sessionId;
    }

    private OpenRouterAgent agent() {
        return new OpenRouterAgent(database, client, new FixedPersona(),
                io.github.mcagentsmods.chat.api.agent.EnvironmentSource.unknown(), broker);
    }

    @Test
    void aQuestionNeedingNoToolsIsOneRoundTrip() {
        client.answer("Sunny.");

        assertEquals("Sunny.", ask("What is the weather?"));
        assertEquals(1, client.requests.size());
        assertTrue(broker.invoked.isEmpty());
    }

    @Test
    void everyRequestOffersTheToolsTheBrokerHas() {
        broker.offer("weather__forecast");
        client.answer("Sunny.");

        ask("What is the weather?");

        assertEquals(List.of("weather__forecast"),
                client.requests.getFirst().tools().stream().map(ToolDefinition::name).toList());
    }

    @Test
    void aToolCallIsRunAndTheModelIsAskedAgain() {
        broker.offer("weather__forecast");
        client.wantsTool("c1", "weather__forecast", "{\"city\":\"Oslo\"}");
        client.answer("It is sunny in Oslo.");

        assertEquals("It is sunny in Oslo.", ask("Weather in Oslo?"));

        assertEquals(2, client.requests.size(), "one round of tools means two requests");
        assertEquals(List.of("weather__forecast"), broker.invoked);
    }

    @Test
    void theSecondRequestCarriesTheCallAndItsResultInOrder() {
        broker.offer("weather__forecast");
        client.wantsTool("c1", "weather__forecast", "{}");
        client.answer("Done.");

        ask("Weather?");

        List<PromptMessage> second = client.requests.get(1).messages();
        PromptMessage assistant = second.get(second.size() - 2);
        PromptMessage result = second.getLast();

        assertEquals(ChatRole.ASSISTANT, assistant.role());
        assertEquals(1, assistant.toolCalls().size());
        assertEquals("c1", assistant.toolCalls().getFirst().id(),
                "the provider matches results to calls by id, so the call must be echoed back");

        assertEquals(ChatRole.TOOL, result.role());
        assertEquals("c1", result.toolCallId());
        assertEquals("ran weather__forecast", result.text());
    }

    @Test
    void theSameToolsAreOfferedOnTheFollowUp() {
        broker.offer("weather__forecast");
        client.wantsTool("c1", "weather__forecast", "{}");
        client.answer("Done.");

        ask("Weather?");

        assertEquals(client.requests.getFirst().tools(), client.requests.get(1).tools(),
                "the set the model was shown must be the set its calls are routed against");
    }

    @Test
    void severalCallsInOneTurnAllRunAndKeepTheModelsOrder() {
        broker.offer("a__one");
        broker.offer("b__two");
        client.wantsTools(
                new ToolCall("c1", "a__one", "{}"),
                new ToolCall("c2", "b__two", "{}"));
        client.answer("Both done.");

        ask("Do both.");

        assertEquals(List.of("a__one", "b__two"), broker.invoked);
        List<PromptMessage> second = client.requests.get(1).messages();
        assertEquals("c1", second.get(second.size() - 2).toolCallId());
        assertEquals("c2", second.getLast().toolCallId());
    }

    @Test
    void severalRoundsAreFollowedUntilTheModelAnswers() {
        broker.offer("a__one");
        client.wantsTool("c1", "a__one", "{}");
        client.wantsTool("c2", "a__one", "{}");
        client.wantsTool("c3", "a__one", "{}");
        client.answer("Finally.");

        assertEquals("Finally.", ask("Keep going."));
        assertEquals(4, client.requests.size());
        assertEquals(3, broker.invoked.size());
    }

    @Test
    void aModelThatNeverConvergesIsStoppedAndTheAnswerIsStillTheBestOneAvailable() {
        broker.offer("a__one");
        for (int i = 0; i < 20; i++) {
            client.wantsTool("c" + i, "a__one", "{}");
        }

        String answer = ask("Loop forever.");

        assertEquals(8, client.requests.size(), "the round limit must bound the exchange");
        assertTrue(answer.contains("did not reach an answer"), answer);
    }

    @Test
    void aFailingToolDoesNotFailTheQuestion() {
        broker.offer("a__one");
        broker.failWith("the server is not connected right now");
        client.wantsTool("c1", "a__one", "{}");
        client.answer("I could not check, sorry.");

        assertEquals("I could not check, sorry.", ask("Check something."));

        PromptMessage result = client.requests.get(1).messages().getLast();
        assertTrue(result.text().contains("not connected"),
                "the model must be told what went wrong: " + result.text());
    }

    @Test
    void aModelWithNoToolsOfferedIsUnaffected() {
        client.answer("Plain answer.");

        assertEquals("Plain answer.", ask("Hello?"));
        assertTrue(client.requests.getFirst().tools().isEmpty());
    }

    @Test
    void toolMessagesAreNeverWrittenToTheConversation() {
        broker.offer("a__one");
        client.wantsTool("c1", "a__one", "{}");
        client.answer("Done.");

        String sessionId = startSession("Go");
        await(agent().respond(sessionId, "Go"));

        boolean anyToolRows = await(database.chat().findMessages(sessionId)).stream()
                .anyMatch(message -> message.role() == ChatRole.TOOL);
        assertFalse(anyToolRows,
                "a replayed conversation must read as the conversation the player had");
    }

    // ---- doubles ----

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    /** A persona that is always readable, so these tests are about the loop and nothing else. */
    private static final class FixedPersona
            implements io.github.mcagentsmods.chat.api.agent.PersonaStore {

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

    /** A model client that answers from a script and records what it was sent. */
    private static final class ScriptedClient implements ModelClient {

        private final Deque<ChatCompletion> scripted = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        void answer(String text) {
            scripted.add(new ChatCompletion(text, "test-model", TokenUsage.UNKNOWN));
        }

        void wantsTool(String id, String name, String arguments) {
            wantsTools(new ToolCall(id, name, arguments));
        }

        void wantsTools(ToolCall... calls) {
            scripted.add(new ChatCompletion("", "test-model", TokenUsage.UNKNOWN, List.of(calls)));
        }

        @Override
        public CompletableFuture<ChatCompletion> complete(ChatRequest request) {
            requests.add(request);
            if (scripted.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new AssertionError("The agent sent more requests than the test scripted"));
            }
            return CompletableFuture.completedFuture(scripted.poll());
        }

        @Override
        public CompletableFuture<float[]> embed(String model, String text) {
            // Retrieval is not what these tests are about; an empty vector disables it.
            return CompletableFuture.completedFuture(new float[] {1.0f});
        }

        @Override
        public void close() {
        }
    }

    /** A broker that offers named tools and records which were invoked. */
    private static final class RecordingBroker implements ToolBroker {

        private final List<ToolDefinition> offered = new ArrayList<>();
        private final List<String> invoked = new ArrayList<>();
        private final AtomicInteger order = new AtomicInteger();
        private String failure;

        void offer(String name) {
            offered.add(new ToolDefinition(name, "Does " + name, ""));
        }

        void failWith(String reason) {
            failure = reason;
        }

        @Override
        public List<ToolDefinition> definitions() {
            return List.copyOf(offered);
        }

        @Override
        public CompletableFuture<ToolOutcome> invoke(ToolCall call) {
            synchronized (invoked) {
                invoked.add(call.name());
            }
            order.incrementAndGet();
            return CompletableFuture.completedFuture(failure == null
                    ? ToolOutcome.ok(call.id(), "ran " + call.name())
                    : ToolOutcome.error(call.id(), failure));
        }
    }
}
