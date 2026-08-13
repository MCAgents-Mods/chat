package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.storage.DataAccessException;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening, reopening, and closing the store.
 *
 * <p>These tests exist because this module has no Minecraft on its classpath. That is the
 * point of the api/common/platform split: the storage layer can be exercised in
 * milliseconds against a real SQLite file instead of only inside a running game.
 */
class SqliteDatabaseTest {

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(databaseFile()));
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createsTheDatabaseFileAndItsParentDirectory() {
        assertTrue(Files.exists(databaseFile()), "the database file should have been created");
        assertTrue(Files.isDirectory(gameDirectory.resolve("MCAgents")),
                "the parent directory should have been created");
    }

    @Test
    void exposesEveryRepository() {
        assertNotNull(database.apiKeys());
        assertNotNull(database.contents());
        assertNotNull(database.chat());
    }

    @Test
    void survivesBeingReopened() {
        // The schema is applied on every start, so it must be safe to apply twice.
        await(database.chat().createSession("Kept across restarts"));
        database.close();

        database = await(SqliteDatabase.open(databaseFile()));

        assertTrue(await(database.chat().findAllSessions()).stream()
                        .anyMatch(session -> session.title().equals("Kept across restarts")),
                "data written before the restart should still be there");
    }

    @Test
    void closingTwiceIsHarmless() {
        database.close();
        assertDoesNotThrow(() -> database.close());
        database = null;
    }

    @Test
    void reportsAFailureToOpenRatherThanThrowingAtTheCaller() throws Exception {
        // A directory where the file should be is the simplest stand-in for the real
        // cases: a read-only folder, or a path the player cannot write to.
        Path blocked = gameDirectory.resolve("blocked/data.db");
        Files.createDirectories(blocked);

        CompletableFuture<LocalDatabase> opening = SqliteDatabase.open(blocked);

        CompletionException failure = assertThrows(CompletionException.class, opening::join);
        assertTrue(failure.getCause() instanceof DataAccessException,
                "the failure should arrive as a DataAccessException, was " + failure.getCause());
    }

    private Path databaseFile() {
        return gameDirectory.resolve("MCAgents").resolve("data.db");
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
