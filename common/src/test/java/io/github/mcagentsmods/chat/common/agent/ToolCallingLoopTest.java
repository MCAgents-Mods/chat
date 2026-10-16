package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.agent.EnvironmentSource;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.model.ChatCompletion;
import io.github.mcagentsmods.core.api.model.ChatRequest;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.model.PromptMessage;
import io.github.mcagentsmods.core.api.model.TokenUsage;
import io.github.mcagentsmods.core.api.model.ToolCall;
import io.github.mcagentsmods.core.api.model.ToolDefinition;
import io.github.mcagentsmods.core.common.agent.ToolAgent;
import io.github.mcagentsmods.core.tool.ExecutionMode;
import io.github.mcagentsmods.core.tool.ToolExecutor;
import io.github.mcagentsmods.core.tool.ToolRegistry;
import io.github.mcagentsmods.core.tool.ToolResult;
import io.github.mcagentsmods.core.tool.ToolSpec;
import io.github.mcagentsmods.core.tool.WorkerPool;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chat's questions answered through the agents mod's agent: the tools are picked, run, and
 * the model answers.
 *
 * <p>Driven through a scripted model client and a registry of its own, so the exchange is
 * exercised without a provider, an API key, or a running MCP server. The agents mod tests its
 * agent in depth; what is tested here is what chat hands it and what chat keeps — the
 * conversation it answers from, the question it picks tools by, and a history with no tool
 * rows in it.
 */
@Timeout(30)
class ToolCallingLoopTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ScriptedClient client;
    private ToolRegistry registry;
    private WorkerPool pool;
    private final List<String> invoked = new ArrayList<>();
    private int toolRounds = 1;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        client = new ScriptedClient();
        registry = new ToolRegistry();
        pool = new WorkerPool(2);
    }

    @AfterEach
    void close() {
        pool.close();
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
        return new OpenRouterAgent(database, client, new FixedPersona(), EnvironmentSource.unknown(),
                new ToolAgent(client, registry, new ToolExecutor(pool), () -> toolRounds));
    }

    /** A tool in the one category these tests use, so the category step is skipped. */
    private void offer(String name) {
        offer(name, ToolResult.ok("ran " + name));
    }

    private void offer(String name, ToolResult result) {
        registry.register(ToolSpec.builder(name)
                        .description("Does " + name)
                        .category("test")
                        .mode(ExecutionMode.CONCURRENT)
                        .build(),
                arguments -> {
                    synchronized (invoked) {
                        invoked.add(name);
                    }
                    return result;
                });
    }

    @Test
    void aQuestionNeedingNoToolsIsOneRoundTrip() {
        client.answer("Sunny.");

        assertEquals("Sunny.", ask("What is the weather?"));
        assertEquals(1, client.requests.size());
        assertTrue(client.requests.getFirst().tools().isEmpty());
        assertTrue(invoked.isEmpty());
    }

    @Test
    void theAnswerIsWrittenFromTheWholeConversation() {
        client.answer("Sunny.");

        ask("What is the weather?");

        List<PromptMessage> sent = client.requests.getFirst().messages();
        assertEquals(ChatRole.SYSTEM, sent.getFirst().role());
        assertTrue(sent.getFirst().text().contains("Be terse."),
                "the persona must reach the model: " + sent.getFirst().text());
        assertEquals("What is the weather?", sent.getLast().text());
    }

    @Test
    void theToolsArePickedByTheNewestQuestionAlone() {
        offer("weather__forecast");
        client.picks("weather__forecast");
        client.answer("Sunny.");

        ask("What is the weather?");

        List<PromptMessage> picking = client.requests.getFirst().messages();
        assertEquals("What is the weather?", picking.getLast().text());
        assertFalse(picking.stream().anyMatch(message -> message.text().contains("Be terse.")),
                "the persona is for the answer, not for picking tools");
        assertEquals(List.of("weather__forecast"),
                client.requests.get(1).tools().stream().map(ToolDefinition::name).toList());
    }

    @Test
    void aToolCallIsRunAndTheModelIsAskedAgain() {
        offer("weather__forecast");
        client.picks("weather__forecast");
        client.wantsTool("c1", "weather__forecast", "{\"city\":\"Oslo\"}");
        client.answer("It is sunny in Oslo.");

        assertEquals("It is sunny in Oslo.", ask("Weather in Oslo?"));

        assertEquals(3, client.requests.size(), "picking, asking, and answering with the result");
        assertEquals(List.of("weather__forecast"), invoked);
    }

    @Test
    void theFollowUpCarriesTheCallAndItsResultInOrder() {
        offer("weather__forecast");
        client.picks("weather__forecast");
        client.wantsTool("c1", "weather__forecast", "{}");
        client.answer("Done.");

        ask("Weather?");

        List<PromptMessage> followUp = client.requests.get(2).messages();
        PromptMessage assistant = followUp.stream()
                .filter(message -> !message.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow();
        PromptMessage result = followUp.stream()
                .filter(message -> message.role() == ChatRole.TOOL)
                .findFirst()
                .orElseThrow();

        assertEquals("c1", assistant.toolCalls().getFirst().id(),
                "the provider matches results to calls by id, so the call must be echoed back");
        assertEquals("c1", result.toolCallId());
        assertEquals("ran weather__forecast", result.text());
        assertTrue(followUp.indexOf(assistant) < followUp.indexOf(result));
    }

    @Test
    void severalCallsInOneTurnAllRunAndKeepTheModelsOrder() {
        offer("a__one");
        offer("b__two");
        client.picks("a__one", "b__two");
        client.wantsTools(
                new ToolCall("c1", "a__one", "{}"),
                new ToolCall("c2", "b__two", "{}"));
        client.answer("Both done.");

        ask("Do both.");

        assertEquals(2, invoked.size());
        List<String> results = client.requests.get(2).messages().stream()
                .filter(message -> message.role() == ChatRole.TOOL)
                .map(PromptMessage::toolCallId)
                .toList();
        assertEquals(List.of("c1", "c2"), results);
    }

    @Test
    void oneRoundOfToolsIsTheDefaultAndTheModelIsThenToldToAnswer() {
        offer("a__one");
        client.picks("a__one");
        client.wantsTool("c1", "a__one", "{}");
        client.wantsTool("c2", "a__one", "{}");

        String answer = ask("Keep going.");

        assertEquals(1, invoked.size(), "the player's setting starts at one round");
        assertTrue(answer.contains("did not reach an answer"), answer);
    }

    @Test
    void thePlayersRoundsAreFollowedUntilTheModelAnswers() {
        toolRounds = 3;
        offer("a__one");
        client.picks("a__one");
        client.wantsTool("c1", "a__one", "{}");
        client.wantsTool("c2", "a__one", "{}");
        client.wantsTool("c3", "a__one", "{}");
        client.answer("Finally.");

        assertEquals("Finally.", ask("Keep going."));
        assertEquals(3, invoked.size());
    }

    @Test
    void aFailingToolDoesNotFailTheQuestion() {
        offer("a__one", ToolResult.error("the server is not connected right now"));
        client.picks("a__one");
        client.wantsTool("c1", "a__one", "{}");
        client.answer("I could not check, sorry.");

        assertEquals("I could not check, sorry.", ask("Check something."));

        PromptMessage result = client.requests.get(2).messages().stream()
                .filter(message -> message.role() == ChatRole.TOOL)
                .findFirst()
                .orElseThrow();
        assertTrue(result.text().contains("not connected"),
                "the model must be told what went wrong: " + result.text());
    }

    @Test
    void toolMessagesAreNeverWrittenToTheConversation() {
        offer("a__one");
        client.picks("a__one");
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

    /** A persona that is always readable, so these tests are about the tools and nothing else. */
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

        /** The agent's tool step, answered with these names. */
        void picks(String... names) {
            StringBuilder json = new StringBuilder("{\"tools\":[");
            for (int i = 0; i < names.length; i++) {
                json.append(i == 0 ? "" : ",").append('"').append(names[i]).append('"');
            }
            wantsTool("pick", "select_tools", json.append("]}").toString());
        }

        void wantsTool(String id, String name, String arguments) {
            wantsTools(new ToolCall(id, name, arguments));
        }

        void wantsTools(ToolCall... calls) {
            scripted.add(new ChatCompletion("", "test-model", TokenUsage.UNKNOWN, List.of(calls)));
        }

        @Override
        public synchronized CompletableFuture<ChatCompletion> complete(ChatRequest request) {
            requests.add(request);
            if (scripted.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new AssertionError("The agent sent more requests than the test scripted"));
            }
            return CompletableFuture.completedFuture(scripted.poll());
        }

        @Override
        public CompletableFuture<float[]> embed(String model, String text) {
            // Retrieval is not what these tests are about; a one-number vector matches nothing.
            return CompletableFuture.completedFuture(new float[] {1.0f});
        }

        @Override
        public void close() {
        }
    }
}
