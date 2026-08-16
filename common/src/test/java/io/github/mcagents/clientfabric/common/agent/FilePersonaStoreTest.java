package io.github.mcagents.clientfabric.common.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AGENTS.md}: written once, never rewritten, and always current.
 *
 * <p>The three that matter are the last three. A store that generated the file is easy; a
 * store that leaves the player's own writing alone, that a save takes effect through
 * immediately, and that still notices an edit made in a text editor is the actual contract
 * — and each of those is a way a player quietly loses their instructions if it is wrong.
 */
class FilePersonaStoreTest {

    @TempDir
    Path gameDirectory;

    private Path file() {
        return gameDirectory.resolve("MCAgents/AGENTS.md");
    }

    @Test
    void writesAStartingPersonaWhenThereIsNoFile() {
        FilePersonaStore store = new FilePersonaStore(file());

        assertTrue(await(store.ensureCreated()), "the first call is the one that creates it");
        assertTrue(Files.isRegularFile(file()), "including the directory above it");

        String written = await(store.read());
        assertTrue(written.contains("AGENTS.md"));
        assertTrue(written.contains("Player is playing in single player."),
                "the starting file explains the environment note the mod sends");
    }

    @Test
    void neverTouchesAFileThatIsAlreadyThere() {
        write("# Mine\n\nAnswer only in haiku.\n");
        FilePersonaStore store = new FilePersonaStore(file());

        assertFalse(await(store.ensureCreated()), "somebody else's file is not this one to write");
        assertEquals("# Mine\n\nAnswer only in haiku.\n", await(store.read()));
    }

    @Test
    void leavesAnEmptiedFileEmpty() {
        // Clearing the file is how a player asks for the built-in persona back. Treating
        // "blank" as "missing" would hand them the starting template again every start.
        write("");
        FilePersonaStore store = new FilePersonaStore(file());

        assertFalse(await(store.ensureCreated()));
        assertEquals("", await(store.read()));
    }

    @Test
    void makesASaveTheVeryNextThingReadBack() {
        FilePersonaStore store = new FilePersonaStore(file());
        await(store.ensureCreated());

        assertEquals("Be brief.", await(store.save("Be brief.")));
        assertEquals("Be brief.", await(store.read()));
        assertEquals("Be brief.", read(), "and on disk, not only in the cache");
    }

    @Test
    void noticesAnEditMadeOutsideTheMod() throws IOException {
        FilePersonaStore store = new FilePersonaStore(file());
        await(store.ensureCreated());
        await(store.read());

        // The player opens the file in a text editor, which is a supported way to use it.
        // A cache that only refreshed on this mod's own writes would answer with the old
        // text for the rest of the session.
        Files.writeString(file(), "Edited in Notepad.", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file(),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        assertEquals("Edited in Notepad.", await(store.read()));
    }

    @Test
    void survivesTheFileBeingDeletedUnderIt() {
        FilePersonaStore store = new FilePersonaStore(file());
        await(store.ensureCreated());
        await(store.read());

        delete();

        // Re-created rather than failed: a player who deletes it gets it back, and a
        // question asked at that moment is still answered.
        assertFalse(await(store.read()).isBlank());
        assertTrue(Files.isRegularFile(file()));
    }

    @Test
    void treatsANullSaveAsAnEmptyOne() {
        FilePersonaStore store = new FilePersonaStore(file());

        assertEquals("", await(store.save(null)));
        assertEquals("", await(store.read()));
    }

    // -------------------------------------------------------------- helpers

    private void write(String text) {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String read() {
        try {
            return Files.readString(file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void delete() {
        try {
            Files.delete(file());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
