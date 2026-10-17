package io.github.mcagentsmods.chat.common.ui;

import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static io.github.mcagentsmods.chat.common.ui.ChatConversationTest.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The list of conversations the session picker shows, on the real store. */
class ChatSessionsTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private ChatRepository chat;
    private ChatConversationTest.ScriptedAgent agent;
    private ChatSessions sessions;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        chat = database.chat();
        agent = new ChatConversationTest.ScriptedAgent();
        sessions = new ChatSessions(Runnable::run);
        await(sessions.bind(chat, agent));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void bindingReadsTheStoredConversations() {
        await(chat.createSession("First"));
        await(chat.createSession("Second"));
        ChatSessions fresh = new ChatSessions(Runnable::run);

        await(fresh.bind(chat, agent));

        assertTrue(fresh.hasStorage());
        assertEquals(2, fresh.sessions().size());
    }

    @Test
    void findAnswersOnlyForAListedConversation() {
        ChatSession stored = await(chat.createSession("Stored"));
        await(sessions.refresh());

        assertEquals("Stored", sessions.find(stored.id()).orElseThrow().title());
        assertTrue(sessions.find("missing").isEmpty());
        assertTrue(sessions.find(null).isEmpty());
    }

    @Test
    void renamingShowsTheNewTitle() {
        ChatSession stored = await(chat.createSession(null));
        await(sessions.refresh());

        await(sessions.rename(stored.id(), "Redstone"));

        assertEquals("Redstone", sessions.find(stored.id()).orElseThrow().title());
    }

    @Test
    void deletingRemovesTheConversationFromTheList() {
        ChatSession stored = await(chat.createSession("Doomed"));
        await(sessions.refresh());

        await(sessions.delete(stored.id()));

        assertTrue(sessions.sessions().isEmpty());
        assertTrue(await(chat.findMessages(stored.id())).isEmpty());
    }

    @Test
    void theVersionMovesWhenTheListChanges() {
        long before = sessions.version();

        agent.answers.add(CompletableFuture.completedFuture("Hi."));
        await(sessions.open(null).ask("Hello"));

        assertNotEquals(before, sessions.version());
        assertEquals(1, sessions.sessions().size());
    }

    @Test
    void withoutAStoreNothingIsListedOrChanged() {
        ChatSessions unbound = new ChatSessions(Runnable::run);

        assertFalse(unbound.hasStorage());
        await(unbound.rename("any", "title"));
        await(unbound.delete("any"));
        await(unbound.refresh());
        assertTrue(unbound.sessions().isEmpty());
    }
}
