package io.github.mcagentsmods.chat.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPathsTest {

    @TempDir
    Path gameDirectory;

    @Test
    void everyFileLivesInChatsOwnDirectory() {
        ClientPaths paths = ClientPaths.under(gameDirectory);
        Path chat = gameDirectory.resolve("MCAgents").resolve("chat");

        assertEquals(chat, paths.dataDirectory());
        assertEquals(chat.resolve("data.db"), paths.database());
        assertEquals(chat.resolve("AGENTS.md"), paths.instructions());
        assertEquals(chat.resolve("mcps"), paths.mcpDirectory());
        assertEquals(chat.resolve("mcps").resolve("mcp_servers.json"), paths.mcpServerCatalog());
        assertEquals(gameDirectory.resolve("MCAgents"), paths.legacyDirectory());
    }

    @Test
    void describingTheLayoutCreatesNothing() {
        ClientPaths.under(gameDirectory);

        assertFalse(Files.exists(gameDirectory.resolve("MCAgents")));
    }

    @Test
    void theFilesChatKeptLooseInMCAgentsAreMovedIn() throws Exception {
        Path legacy = Files.createDirectories(gameDirectory.resolve("MCAgents"));
        Files.writeString(legacy.resolve("data.db"), "db");
        Files.writeString(legacy.resolve("data.db-wal"), "wal");
        Files.writeString(legacy.resolve("AGENTS.md"), "# persona");
        Files.createDirectories(legacy.resolve("mcps/owner/repo"));
        Files.writeString(legacy.resolve("mcps/mcp_servers.json"), "{}");
        Files.createDirectories(legacy.resolve("core"));
        ClientPaths paths = ClientPaths.under(gameDirectory);

        List<Path> moved = paths.moveLegacyFiles();

        assertEquals(List.of(paths.database().resolveSibling("data.db-wal"), paths.database(),
                paths.instructions(), paths.mcpDirectory()), moved);
        assertEquals("db", Files.readString(paths.database()));
        assertEquals("wal", Files.readString(paths.database().resolveSibling("data.db-wal")));
        assertEquals("# persona", Files.readString(paths.instructions()));
        assertEquals("{}", Files.readString(paths.mcpServerCatalog()));
        assertTrue(Files.isDirectory(paths.mcpDirectory().resolve("owner/repo")));
        assertFalse(Files.exists(legacy.resolve("data.db")));
        assertTrue(Files.isDirectory(legacy.resolve("core")), "another mod's directory stays");
    }

    @Test
    void aDatabaseAlreadyInPlaceKeepsItsOwnJournal() throws Exception {
        Path legacy = Files.createDirectories(gameDirectory.resolve("MCAgents"));
        Files.writeString(legacy.resolve("data.db"), "old");
        Files.writeString(legacy.resolve("data.db-wal"), "old wal");
        ClientPaths paths = ClientPaths.under(gameDirectory);
        paths.createDataDirectory();
        Files.writeString(paths.database(), "new");

        assertEquals(List.of(), paths.moveLegacyFiles());

        assertEquals("new", Files.readString(paths.database()));
        assertFalse(Files.exists(paths.database().resolveSibling("data.db-wal")));
        assertEquals("old wal", Files.readString(legacy.resolve("data.db-wal")));
    }

    @Test
    void movingIsSafeToRepeat() throws Exception {
        ClientPaths paths = ClientPaths.under(gameDirectory);

        assertEquals(List.of(), paths.moveLegacyFiles());
        assertEquals(List.of(), paths.moveLegacyFiles());
    }

    @Test
    void aMissingGameDirectoryIsRejected() {
        assertThrows(NullPointerException.class, () -> ClientPaths.under(null));
    }
}
