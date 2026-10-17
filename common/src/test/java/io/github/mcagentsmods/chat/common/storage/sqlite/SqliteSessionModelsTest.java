package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.model.SessionModels;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The models a conversation chooses for itself, stored on its row. */
class SqliteSessionModelsTest {

    private static final ModelChoice CHAT = new ModelChoice("deepseek", "deepseek/deepseek-v4-pro");
    private static final ModelChoice EMBEDDING =
            ModelChoice.throughOpenRouter("mistralai/mistral-embed-2312");

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    private ChatRepository chat() {
        if (database == null) {
            database = await(SqliteDatabase.open(file()));
        }
        return database.chat();
    }

    private Path file() {
        return gameDirectory.resolve("MCAgents/data.db");
    }

    @Test
    void aNewConversationUsesTheGlobalModels() {
        ChatSession session = await(chat().createSession(null));

        assertEquals(SessionModels.GLOBAL, await(chat().findSessionModels(session.id())));
    }

    @Test
    void bothChoicesAreStoredWithTheirProviders() {
        ChatSession session = await(chat().createSession(null));

        assertTrue(await(chat().saveSessionModels(session.id(), new SessionModels(CHAT, EMBEDDING))));

        SessionModels stored = await(chat().findSessionModels(session.id()));
        assertEquals(CHAT, stored.chat());
        assertEquals(EMBEDDING, stored.embedding());
    }

    @Test
    void oneChoiceCanBeLeftOnTheGlobalModel() {
        ChatSession session = await(chat().createSession(null));

        await(chat().saveSessionModels(session.id(), SessionModels.GLOBAL.withChat(CHAT)));

        SessionModels stored = await(chat().findSessionModels(session.id()));
        assertEquals(CHAT, stored.chat());
        assertEquals(null, stored.embedding(), "an absent choice is the global model, not a model");
    }

    @Test
    void savingGlobalClearsTheChoice() {
        ChatSession session = await(chat().createSession(null));
        await(chat().saveSessionModels(session.id(), new SessionModels(CHAT, EMBEDDING)));

        await(chat().saveSessionModels(session.id(), SessionModels.GLOBAL));

        assertEquals(SessionModels.GLOBAL, await(chat().findSessionModels(session.id())));
    }

    @Test
    void choosingAModelDoesNotMoveTheConversationUpTheList() {
        ChatSession older = await(chat().createSession("Older"));
        await(chat().appendMessage(older.id(), ChatRole.USER, "first"));
        ChatSession newer = await(chat().createSession("Newer"));
        await(chat().appendMessage(newer.id(), ChatRole.USER, "second"));
        List<String> before = titles();
        var touched = await(chat().findSession(older.id())).orElseThrow().updatedAt();

        await(chat().saveSessionModels(older.id(), SessionModels.GLOBAL.withChat(CHAT)));

        assertEquals(before, titles());
        assertEquals(touched, await(chat().findSession(older.id())).orElseThrow().updatedAt());
    }

    @Test
    void aConversationThatDoesNotExistIsNotWrittenAndReadsAsGlobal() {
        assertFalse(await(chat().saveSessionModels("missing", new SessionModels(CHAT, EMBEDDING))));
        assertEquals(SessionModels.GLOBAL, await(chat().findSessionModels("missing")));
    }

    @Test
    void aRetiredModelIsMovedOffOnTheWayOut() {
        ChatSession session = await(chat().createSession(null));
        await(chat().saveSessionModels(session.id(), SessionModels.GLOBAL.withChat(
                ModelChoice.throughOpenRouter("deepseek/deepseek-v4-flash-latest"))));

        assertEquals("deepseek/deepseek-v4-flash",
                await(chat().findSessionModels(session.id())).chat().model());
    }

    @Test
    void aDatabaseFromBeforeTheColumnsKeepsItsConversationsOnTheGlobalModels() throws Exception {
        Files.createDirectories(file().getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE chat_sessions (
                        id VARCHAR(36) PRIMARY KEY,
                        title VARCHAR(255) DEFAULT 'New Chat',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )""");
            statement.executeUpdate("INSERT INTO chat_sessions (id, title) VALUES ('old', 'Before')");
        }

        assertEquals("Before", await(chat().findSession("old")).orElseThrow().title());
        assertEquals(SessionModels.GLOBAL, await(chat().findSessionModels("old")));
        assertTrue(await(chat().saveSessionModels("old", SessionModels.GLOBAL.withEmbedding(EMBEDDING))));
        assertEquals(EMBEDDING, await(chat().findSessionModels("old")).embedding());
    }

    private List<String> titles() {
        return await(chat().findAllSessions()).stream().map(ChatSession::title).toList();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
