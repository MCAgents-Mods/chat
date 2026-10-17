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
 * The chat holograms' logic, run on the real store with an agent that answers from a script.
 *
 * <p>The executor runs each result at once, on whichever thread finished the work, so a test
 * waits on the future an action returns and then reads the state it left.
 */
class ChatConversationTest {

    private static final ChatConversation.TranscriptLabels LABELS = new ChatConversation.TranscriptLabels(
            "Steve", "Agent", "Context", "Thinking…", "Not answered:");

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ChatRepository chat;
    private ScriptedAgent agent;
    private ChatSessions sessions;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        chat = database.chat();
        agent = new ScriptedAgent();
        sessions = new ChatSessions(Runnable::run);
        await(sessions.bind(chat, agent));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aFirstQuestionStoresANewConversationAndShowsTheAnswer() {
        ChatConversation conversation = sessions.open(null);
        assertTrue(conversation.sessionId().isEmpty());
        agent.answers.add(CompletableFuture.completedFuture("Mine at Y=-59."));

        await(conversation.ask("Where are diamonds?"));

        assertEquals(1, sessions.sessions().size());
        assertEquals(sessions.sessions().getFirst().id(), conversation.sessionId().orElseThrow());
        assertEquals(sessions.sessions().getFirst(), conversation.session().orElseThrow());
        assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT),
                conversation.messages().stream().map(ChatMessage::role).toList());
        assertEquals(List.of(
                        new TranscriptEntry("Steve", "Where are diamonds?"),
                        new TranscriptEntry("Agent", "Mine at Y=-59.")),
                conversation.transcript(LABELS));
        assertFalse(conversation.isWaiting());
        assertTrue(conversation.canSend());
    }

    @Test
    void aQuestionGoesToTheConversationItWasOpenedOn() {
        ChatSession first = await(chat.createSession("First"));
        await(chat.createSession("Second"));
        await(sessions.refresh());
        ChatConversation conversation = sessions.open(first.id());
        agent.answers.add(CompletableFuture.completedFuture("Yes."));

        await(conversation.ask("Still here?"));

        assertEquals(2, sessions.sessions().size());
        assertEquals(first.id(), conversation.sessionId().orElseThrow());
        assertEquals(2, await(chat.findMessages(first.id())).size());
        assertEquals(List.of(first.id()), agent.askedIn);
    }

    @Test
    void openingAStoredConversationReadsItsMessages() {
        ChatSession first = await(chat.createSession("First"));
        ChatSession second = await(chat.createSession("Second"));
        await(chat.appendMessage(first.id(), ChatRole.USER, "in the first"));
        await(chat.appendMessage(second.id(), ChatRole.USER, "in the second"));

        ChatConversation two = sessions.open(second.id());
        ChatConversation one = sessions.open(first.id());
        await(two.refresh());
        await(one.refresh());

        assertEquals("in the second", two.messages().getFirst().content());
        assertEquals("in the first", one.messages().getFirst().content());
    }

    @Test
    void whileAnAnswerIsAwaitedTheTranscriptSaysSoAndNoSecondQuestionIsTaken() {
        ChatConversation conversation = sessions.open(null);
        CompletableFuture<String> answer = new CompletableFuture<>();
        agent.answers.add(answer);

        CompletableFuture<Void> exchange = conversation.ask("Can pigs fly?");
        await(agent.asked);
        await(conversation.refresh());

        assertTrue(conversation.isWaiting());
        assertFalse(conversation.canSend());
        assertFalse(conversation.send("And cows?"));
        assertEquals(List.of(
                        new TranscriptEntry("Steve", "Can pigs fly?"),
                        new TranscriptEntry("Agent", "Thinking…")),
                conversation.transcript(LABELS));

        answer.complete("Only with a saddle and a lead.");
        await(exchange);

        assertFalse(conversation.isWaiting());
        assertEquals(new TranscriptEntry("Agent", "Only with a saddle and a lead."),
                conversation.transcript(LABELS).getLast());
    }

    @Test
    void twoConversationsWaitForTheirOwnAnswersAtOnce() {
        ChatConversation one = sessions.open(null);
        ChatConversation two = sessions.open(null);
        CompletableFuture<String> firstAnswer = new CompletableFuture<>();
        CompletableFuture<String> secondAnswer = new CompletableFuture<>();
        agent.answers.add(firstAnswer);
        agent.answers.add(secondAnswer);

        CompletableFuture<Void> first = one.ask("Where is the nether?");
        CompletableFuture<Void> second = two.ask("Where is the end?");

        assertTrue(one.isWaiting());
        assertTrue(two.isWaiting());
        assertFalse(one.canSend());
        assertFalse(two.canSend());

        secondAnswer.complete("Through a stronghold.");
        await(second);
        assertTrue(one.isWaiting());
        assertFalse(two.isWaiting());

        firstAnswer.complete("Through a portal.");
        await(first);
        assertFalse(one.isWaiting());
        assertEquals(2, sessions.sessions().size());
        assertFalse(one.sessionId().equals(two.sessionId()));
        assertEquals("Through a portal.", one.messages().getLast().content());
        assertEquals("Through a stronghold.", two.messages().getLast().content());
    }

    @Test
    void aFailedAnswerKeepsTheQuestionAndSaysWhyUntilTheNextQuestion() {
        ChatConversation conversation = sessions.open(null);
        agent.answers.add(CompletableFuture.failedFuture(
                new ModelException(ModelException.Kind.NO_KEY, "no key")));

        await(conversation.ask("Hello?"));

        assertEquals(List.of(ChatRole.USER),
                conversation.messages().stream().map(ChatMessage::role).toList());
        assertEquals("No usable API key. Add one with KEYS on the MCAgents menu.",
                conversation.failure().orElseThrow());
        assertEquals(new TranscriptEntry("Agent",
                        "Not answered: No usable API key. Add one with KEYS on the MCAgents menu."),
                conversation.transcript(LABELS).getLast());
        assertTrue(conversation.canSend());

        agent.answers.add(CompletableFuture.completedFuture("Hi."));
        await(conversation.ask("Again"));
        assertTrue(conversation.failure().isEmpty());
    }

    @Test
    void aFailureStaysInTheConversationItHappenedIn() {
        ChatConversation failed = sessions.open(null);
        agent.answers.add(CompletableFuture.failedFuture(
                new ModelException(ModelException.Kind.RATE_LIMIT, "slow down")));
        await(failed.ask("One"));

        ChatConversation other = sessions.open(null);

        assertEquals("Rate limited by the provider: slow down", failed.failure().orElseThrow());
        assertTrue(other.failure().isEmpty());
    }

    @Test
    void blankQuestionsAreNotTaken() {
        ChatConversation conversation = sessions.open(null);

        assertFalse(conversation.send(""));
        assertFalse(conversation.send("   \n "));
        assertFalse(conversation.send(null));

        assertTrue(sessions.sessions().isEmpty());
        assertTrue(agent.askedIn.isEmpty());
    }

    @Test
    void aQuestionPastTheLimitIsCut() {
        ChatConversation conversation = sessions.open(null);
        agent.answers.add(CompletableFuture.completedFuture("Long."));

        await(conversation.ask("x".repeat(ChatConversation.PROMPT_LIMIT + 10)));

        assertEquals(ChatConversation.PROMPT_LIMIT,
                conversation.messages().getFirst().content().length());
    }

    @Test
    void withoutAStoreNothingIsTaken() {
        ChatConversation conversation = new ChatSessions(Runnable::run).open(null);

        assertFalse(conversation.canSend());
        assertFalse(conversation.send("Anyone?"));
        assertTrue(conversation.transcript(LABELS).isEmpty());
    }

    @Test
    void theTranscriptIsTheSameListUntilSomethingChanges() {
        ChatConversation conversation = sessions.open(null);
        List<TranscriptEntry> first = conversation.transcript(LABELS);
        assertSame(first, conversation.transcript(LABELS));

        agent.answers.add(CompletableFuture.completedFuture("Hi."));
        await(conversation.ask("Hello"));
        List<TranscriptEntry> afterAnswer = conversation.transcript(LABELS);
        assertNotSame(first, afterAnswer);
        assertSame(afterAnswer, conversation.transcript(LABELS));

        ChatConversation.TranscriptLabels renamed = new ChatConversation.TranscriptLabels(
                "Alex", "Agent", "Context", "Thinking…", "Not answered:");
        assertEquals("Alex", conversation.transcript(renamed).getFirst().speaker());
    }

    @Test
    void aWrappedFailureIsDescribedByItsCause() {
        assertEquals("The game is shutting down, so the question was not sent.",
                ChatConversation.describe(new CompletionException(
                        new ModelException(ModelException.Kind.CLOSED, "closed"))));
        assertEquals("disk full", ChatConversation.describe(
                new CompletionException(new IllegalStateException("disk full"))));
        assertEquals("IllegalStateException",
                ChatConversation.describe(new IllegalStateException()));
    }

    static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Answers each question with the next future in its script, and records where it was asked. */
    static final class ScriptedAgent implements Agent {

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
