package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.PersonaStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code AGENTS.md} on disk: created when it is missing, cached while it is unchanged, and
 * rewritten when the player saves.
 *
 * <h2>Why there is a cache at all</h2>
 *
 * <p>The persona is read on <em>every</em> question. Reading the file each time is a disk
 * round trip per turn for a file that changes perhaps twice in a player's life, and it is
 * how a save made in the browser and a question asked in the game end up disagreeing about
 * what the instructions are.
 *
 * <h2>Why the cache still notices an edit made outside the mod</h2>
 *
 * <p>{@code AGENTS.md} is a markdown file in a folder the player can open. Editing it in a
 * text editor is a supported way to use it — arguably the original one — so a cache that
 * only refreshed on this mod's own writes would quietly ignore that editor. Every read
 * therefore compares the file's modification time and size against what was cached and
 * re-reads when either has moved. That is one {@code stat} per question instead of one full
 * read, and it costs nothing on the client thread because it does not happen there.
 *
 * <h2>Writes are atomic</h2>
 *
 * <p>The new text goes to a temporary file beside the target and is then moved over it. A
 * game that is closed mid-save leaves the player with their previous instructions rather
 * than with half of their new ones.
 */
public final class FilePersonaStore implements PersonaStore {

    private static final Logger LOGGER = System.getLogger(FilePersonaStore.class.getName());

    /** What is cached, and the two facts that say whether it is still true. */
    private record Cached(String text, FileTime modified, long size) {
    }

    private final Path file;
    private final Executor io;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    /** Reads and writes {@code file}, on a thread of its own. */
    public FilePersonaStore(Path file) {
        this(file, defaultExecutor());
    }

    /**
     * @param io where the reading and writing happens; never the caller's thread in the
     *           game, and a test may pass something it controls
     */
    public FilePersonaStore(Path file, Executor io) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.io = Objects.requireNonNull(io, "io");
    }

    /** The file this store is reading and writing. */
    public Path file() {
        return file;
    }

    @Override
    public CompletableFuture<String> read() {
        return run(() -> {
            createIfMissing();
            Cached current = cache.get();
            if (current != null && isStillCurrent(current)) {
                return current.text();
            }
            return load();
        });
    }

    @Override
    public CompletableFuture<String> save(String instructions) {
        String text = instructions == null ? "" : instructions;
        return run(() -> {
            write(text);
            // Cached from what was written rather than by reading the file back: this is
            // the value the next question must use, and re-reading only opens a window in
            // which it could be something else.
            remember(text);
            LOGGER.log(Level.INFO, "Saved the player's agent instructions to {0}", file);
            return text;
        });
    }

    @Override
    public CompletableFuture<Boolean> ensureCreated() {
        return run(this::createIfMissing);
    }

    // ------------------------------------------------------------- the file

    /**
     * Writes the starting persona if there is no file yet.
     *
     * <p>Never touches an existing one, whatever is in it — including a file the player has
     * emptied on purpose. See {@code .agents/rules/repository.md}: this file is the
     * player's, and the mod may create it but may not rewrite it.
     */
    private boolean createIfMissing() {
        if (Files.isRegularFile(file)) {
            return false;
        }
        write(DefaultPersona.markdown());
        remember(DefaultPersona.markdown());
        LOGGER.log(Level.INFO, "Created {0} with a starting persona", file);
        return true;
    }

    private String load() {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            remember(text);
            return text;
        } catch (NoSuchFileException e) {
            // Deleted between the check and the read. Not worth failing a question over.
            cache.set(null);
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private void write(String text) {
        try {
            Path directory = file.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // Some filesystems — a few network shares, and Windows when the file is
                // open elsewhere — refuse an atomic move. A plain replace is still better
                // than writing in place, and losing the save entirely is not an option.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    /** Whether the file on disk is still the one the cache was taken from. */
    private boolean isStillCurrent(Cached cached) {
        try {
            return Files.size(file) == cached.size()
                    && Files.getLastModifiedTime(file).equals(cached.modified());
        } catch (IOException e) {
            // Unreadable for the moment. Re-reading will report the real failure.
            return false;
        }
    }

    private void remember(String text) {
        try {
            cache.set(new Cached(text, Files.getLastModifiedTime(file), Files.size(file)));
        } catch (IOException e) {
            // The text is right even if its stamp could not be taken; caching it without
            // one would mean never noticing an edit, so the cache is dropped instead.
            cache.set(null);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private <T> CompletableFuture<T> run(java.util.function.Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, io);
    }

    /**
     * One daemon thread, named so it is identifiable in a thread dump.
     *
     * <p>Daemon because the game must be able to exit with a save in flight — the write
     * itself is atomic, so the worst a half-finished one leaves behind is a temporary file.
     */
    private static Executor defaultExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MCAgents-persona");
            thread.setDaemon(true);
            return thread;
        });
    }
}
