package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.chat.api.storage.DataAccessException;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conversations and messages. */
class SqliteChatRepositoryTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ChatRepository chat;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        chat = database.chat();
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void givesAnUntitledSessionTheDefaultTitle() {
        ChatSession session = await(chat.createSession(null));

        assertEquals(ChatSession.DEFAULT_TITLE, session.title());
        assertFalse(session.id().isBlank());
    }

    @Test
    void numbersMessagesFromOneInTheOrderTheyArrive() {
        ChatSession session = await(chat.createSession("Numbering"));

        await(chat.appendMessage(session.id(), ChatRole.USER, "How do I get netherite?"));
        await(chat.appendMessage(session.id(), ChatRole.ASSISTANT, "Mine below Y=15."));
        await(chat.appendMessage(session.id(), ChatRole.USER, "How much do I need?"));

        List<ChatMessage> messages = await(chat.findMessages(session.id()));

        assertEquals(List.of(1, 2, 3), messages.stream().map(ChatMessage::index).toList());
        assertEquals(
                List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
                messages.stream().map(ChatMessage::role).toList());
    }

    @Test
    void storesOneRowPerTurnSoTheHistoryCanBeSentAsIs() {
        // The model API wants a list of {role, content}. Storing a turn per row is what
        // makes replaying a conversation a mapping rather than a parse.
        ChatSession session = await(chat.createSession("Shape"));
        await(chat.appendMessage(session.id(), ChatRole.USER, "Hello"));
        await(chat.appendMessage(session.id(), ChatRole.ASSISTANT, "Hello back"));

        List<ChatMessage> messages = await(chat.findMessages(session.id()));

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role().code());
        assertEquals("assistant", messages.get(1).role().code());
        assertEquals("Hello", messages.get(0).content());
    }

    @Test
    void refusesToAppendToASessionThatDoesNotExist() {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> chat.appendMessage("no-such-session", ChatRole.USER, "Hello").join());

        assertTrue(failure.getCause() instanceof DataAccessException,
                "expected a DataAccessException, got " + failure.getCause());
    }

    @Test
    void deletingASessionRemovesItsMessages() {
        // chat_messages declares ON DELETE CASCADE, which SQLite only honours when foreign
        // key enforcement is switched on. This test is what proves it is.
        ChatSession session = await(chat.createSession("Doomed"));
        await(chat.appendMessage(session.id(), ChatRole.USER, "Anyone there?"));

        assertTrue(await(chat.deleteSession(session.id())));

        assertTrue(await(chat.findMessages(session.id())).isEmpty(),
                "the messages should have gone with the session");
        assertTrue(await(chat.findSession(session.id())).isEmpty());
    }

    @Test
    void deletingAMissingSessionReportsThatNothingHappened() {
        assertFalse(await(chat.deleteSession("no-such-session")));
    }

    @Test
    void renamesASession() {
        ChatSession session = await(chat.createSession("Before"));

        assertTrue(await(chat.renameSession(session.id(), "After")));

        assertEquals("After", await(chat.findSession(session.id())).orElseThrow().title());
    }

    @Test
    void findsSessionsByTitleOrByWhatWasSaidInThem() {
        ChatSession byTitle = await(chat.createSession("Redstone doors"));
        ChatSession byContent = await(chat.createSession("Untitled"));
        await(chat.appendMessage(byContent.id(), ChatRole.USER, "How do I wire a piston?"));
        await(chat.createSession("Nothing to do with it"));

        List<String> titles = await(chat.searchSessions("redstone")).stream()
                .map(ChatSession::title)
                .toList();
        assertEquals(List.of("Redstone doors"), titles);

        List<String> byMessage = await(chat.searchSessions("piston")).stream()
                .map(ChatSession::id)
                .toList();
        assertEquals(List.of(byContent.id()), byMessage);

        assertEquals(3, await(chat.searchSessions("  ")).size(),
                "a blank search should return everything");
        assertTrue(await(chat.searchSessions("dragon")).isEmpty());
        assertFalse(titles.contains(byTitle.title() + "!"));
    }

    @Test
    void treatsSearchWildcardsAsText() {
        // Typing "100%" must search for that text, not match every session.
        await(chat.createSession("Efficiency 100% guide"));
        await(chat.createSession("Something else entirely"));
        await(chat.createSession("Underscore_named session"));

        assertEquals(List.of("Efficiency 100% guide"), titles(chat.searchSessions("100%")));
        assertEquals(List.of("Efficiency 100% guide"), titles(chat.searchSessions("%")),
                "a bare percent sign matches the session containing one, not all three");
        assertEquals(List.of("Underscore_named session"), titles(chat.searchSessions("e_n")),
                "an underscore is a literal, not a single-character wildcard");
    }

    private static List<String> titles(CompletableFuture<List<ChatSession>> sessions) {
        return await(sessions).stream().map(ChatSession::title).toList();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
