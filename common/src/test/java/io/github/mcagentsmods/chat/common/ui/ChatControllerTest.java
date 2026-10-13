package io.github.mcagentsmods.chat.common.ui;

import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.hologram.TranscriptEntry;
import io.github.mcagentsmods.core.api.model.ModelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat hologram's logic, run on the real store with an agent that answers from a script.
 *
 * <p>The executor runs each result at once, on whichever thread finished the work, so a test
 * waits on the future an action returns and then reads the state it left.
 */
class ChatControllerTest {

    private static final ChatController.TranscriptLabels LABELS = new ChatController.TranscriptLabels(
            "Steve", "Agent", "Context", "Thinking…", "Not answered:");

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ChatRepository chat;
    private ScriptedAgent agent;
    private ChatController controller;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        chat = database.chat();
        agent = new ScriptedAgent();
        controller = new ChatController(Runnable::run);
        await(controller.bind(chat, agent));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aFirstQuestionStartsAConversationAndShowsTheAnswer() {
        agent.answers.add(CompletableFuture.completedFuture("Mine at Y=-59."));

        await(controller.ask("Where are diamonds?"));

        assertEquals(1, controller.sessions().size());
        assertEquals(0, controller.selectedIndex());
        assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT),
                controller.messages().stream().map(ChatMessage::role).toList());
        assertEquals(List.of(
                        new TranscriptEntry("Steve", "Where are diamonds?"),
                        new TranscriptEntry("Agent", "Mine at Y=-59.")),
                controller.transcript(LABELS));
        assertFalse(controller.isWaiting());
        assertTrue(controller.canSend());
    }

    @Test
    void aQuestionGoesToTheChosenConversation() {
        ChatSession first = await(chat.createSession("First"));
        await(chat.createSession("Second"));
        await(controller.refresh());
        await(controller.select(indexOf(first)));
        agent.answers.add(CompletableFuture.completedFuture("Yes."));

        await(controller.ask("Still here?"));

        assertEquals(2, controller.sessions().size());
        assertEquals(first.id(), controller.selected().orElseThrow().id());
        assertEquals(2, await(chat.findMessages(first.id())).size());
        assertEquals(List.of(first.id()), agent.askedIn);
    }

    @Test
    void whileAnAnswerIsAwaitedTheTranscriptSaysSoAndNoSecondQuestionIsTaken() {
        CompletableFuture<String> answer = new CompletableFuture<>();
        agent.answers.add(answer);

        CompletableFuture<Void> exchange = controller.ask("Can pigs fly?");
        await(agent.asked);
        await(controller.refresh());

        assertTrue(controller.isWaiting());
        assertFalse(controller.canSend());
        assertFalse(controller.send("And cows?"));
        assertEquals(List.of(
                        new TranscriptEntry("Steve", "Can pigs fly?"),
                        new TranscriptEntry("Agent", "Thinking…")),
                controller.transcript(LABELS));

        answer.complete("Only with a saddle and a lead.");
        await(exchange);

        assertFalse(controller.isWaiting());
        assertEquals(new TranscriptEntry("Agent", "Only with a saddle and a lead."),
                controller.transcript(LABELS).getLast());
    }

    @Test
    void aFailedAnswerKeepsTheQuestionAndSaysWhy() {
        agent.answers.add(CompletableFuture.failedFuture(
                new ModelException(ModelException.Kind.NO_KEY, "no key")));

        await(controller.ask("Hello?"));

        assertEquals(List.of(ChatRole.USER),
                controller.messages().stream().map(ChatMessage::role).toList());
        assertEquals("No usable API key. Add one with KEYS on the MCAgents menu.",
                controller.failure().orElseThrow());
        assertEquals(new TranscriptEntry("Agent",
                        "Not answered: No usable API key. Add one with KEYS on the MCAgents menu."),
                controller.transcript(LABELS).getLast());
        assertTrue(controller.canSend());
    }

    @Test
    void theReasonStaysWithTheConversationItWasAskedInUntilTheNextQuestion() {
        agent.answers.add(CompletableFuture.failedFuture(
                new ModelException(ModelException.Kind.RATE_LIMIT, "slow down")));
        await(controller.ask("One"));
        ChatSession failed = controller.selected().orElseThrow();

        await(controller.newSession());
        assertTrue(controller.failure().isEmpty());

        await(controller.select(indexOf(failed)));
        assertEquals("Rate limited by the provider: slow down", controller.failure().orElseThrow());

        agent.answers.add(CompletableFuture.completedFuture("Two"));
        await(controller.ask("Again"));
        assertTrue(controller.failure().isEmpty());
    }

    @Test
    void choosingAConversationReadsItsMessages() {
        ChatSession first = await(chat.createSession("First"));
        ChatSession second = await(chat.createSession("Second"));
        await(chat.appendMessage(first.id(), ChatRole.USER, "in the first"));
        await(chat.appendMessage(second.id(), ChatRole.USER, "in the second"));
        await(controller.refresh());

        await(controller.select(indexOf(second)));
        assertEquals("in the second", controller.messages().getFirst().content());

        await(controller.select(indexOf(first)));
        assertEquals("in the first", controller.messages().getFirst().content());
        assertEquals(first.id(), controller.selected().orElseThrow().id());
    }

    @Test
    void anIndexOutsideTheListChoosesNothing() {
        await(controller.select(3));
        await(controller.select(-1));

        assertEquals(-1, controller.selectedIndex());
        assertTrue(controller.messages().isEmpty());
    }

    @Test
    void aNewConversationIsChosenAndEmpty() {
        agent.answers.add(CompletableFuture.completedFuture("Hi."));
        await(controller.ask("Hello"));

        await(controller.newSession());

        assertEquals(2, controller.sessions().size());
        assertEquals(ChatSession.DEFAULT_TITLE, controller.selected().orElseThrow().title());
        assertTrue(controller.messages().isEmpty());
        assertEquals(List.of(), controller.transcript(LABELS));
    }

    @Test
    void renamingShowsTheNewTitle() {
        await(controller.newSession());
        String id = controller.selected().orElseThrow().id();

        await(controller.rename(id, "Redstone"));

        assertEquals("Redstone", controller.selected().orElseThrow().title());
    }

    @Test
    void deletingTheChosenConversationChoosesNone() {
        agent.answers.add(CompletableFuture.completedFuture("Hi."));
        await(controller.ask("Hello"));
        String id = controller.selected().orElseThrow().id();

        await(controller.delete(id));

        assertTrue(controller.sessions().isEmpty());
        assertEquals(-1, controller.selectedIndex());
        assertTrue(controller.messages().isEmpty());
    }

    @Test
    void blankQuestionsAreNotTaken() {
        assertFalse(controller.send(""));
        assertFalse(controller.send("   \n "));
        assertFalse(controller.send(null));

        assertTrue(controller.sessions().isEmpty());
        assertTrue(agent.askedIn.isEmpty());
    }

    @Test
    void aQuestionPastTheLimitIsCut() {
        agent.answers.add(CompletableFuture.completedFuture("Long."));

        await(controller.ask("x".repeat(ChatController.PROMPT_LIMIT + 10)));

        assertEquals(ChatController.PROMPT_LIMIT, controller.messages().getFirst().content().length());
    }

    @Test
    void withoutAStoreNothingIsTakenOrStarted() {
        ChatController unbound = new ChatController(Runnable::run);

        assertFalse(unbound.hasStorage());
        assertFalse(unbound.canSend());
        assertFalse(unbound.send("Anyone?"));
        await(unbound.newSession());
        assertTrue(unbound.sessions().isEmpty());
        assertTrue(unbound.transcript(LABELS).isEmpty());
    }

    @Test
    void theTranscriptIsTheSameListUntilSomethingChanges() {
        List<TranscriptEntry> first = controller.transcript(LABELS);
        assertSame(first, controller.transcript(LABELS));

        agent.answers.add(CompletableFuture.completedFuture("Hi."));
        await(controller.ask("Hello"));
        List<TranscriptEntry> afterAnswer = controller.transcript(LABELS);
        assertNotSame(first, afterAnswer);
        assertSame(afterAnswer, controller.transcript(LABELS));

        ChatController.TranscriptLabels renamed = new ChatController.TranscriptLabels(
                "Alex", "Agent", "Context", "Thinking…", "Not answered:");
        assertEquals("Alex", controller.transcript(renamed).getFirst().speaker());
    }

    @Test
    void aWrappedFailureIsDescribedByItsCause() {
        assertEquals("The game is shutting down, so the question was not sent.",
                ChatController.describe(new CompletionException(
                        new ModelException(ModelException.Kind.CLOSED, "closed"))));
        assertEquals("disk full", ChatController.describe(
                new CompletionException(new IllegalStateException("disk full"))));
        assertEquals("IllegalStateException",
                ChatController.describe(new IllegalStateException()));
    }

    private int indexOf(ChatSession session) {
        List<ChatSession> sessions = controller.sessions();
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id().equals(session.id())) {
                return i;
            }
        }
        throw new AssertionError("Not listed: " + session.title());
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Answers each question with the next future in its script, and records where it was asked. */
    private static final class ScriptedAgent implements Agent {

        final List<CompletableFuture<String>> answers = new ArrayList<>();
        final List<String> askedIn = new ArrayList<>();
        final CompletableFuture<Void> asked = new CompletableFuture<>();

        @Override
        public synchronized CompletableFuture<String> respond(String sessionId, String prompt) {
            askedIn.add(sessionId);
            asked.complete(null);
            return answers.remove(0);
        }
    }
}
