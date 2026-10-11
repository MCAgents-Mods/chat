package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.McpException;
import io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner;
import io.github.mcagentsmods.chat.api.mcp.RepositoryCoordinate;
import io.github.mcagentsmods.chat.common.ClientPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Clones MCP repositories with the {@code git} the player already has.
 *
 * <p>Shelling out to git rather than embedding a Java implementation is a deliberate
 * trade. A git library would be several megabytes inside a mod jar that already carries
 * SQLite's native binaries, to do something the player's machine can almost always already
 * do. When git is absent the failure says so plainly, which is a better outcome than a
 * mod that is permanently larger for a feature most players never use.
 *
 * <p>The destination is never chosen by a caller. It is derived from the URL through
 * {@link RepositoryCoordinate}, which rejects anything that is not a plain
 * {@code {owner}/{repository}} pair, and then resolved through
 * {@link ClientPaths#mcpRepository} which refuses a path outside the mcps directory. That
 * matters more here than anywhere else in the mod: this is a tool a language model calls
 * with arguments a prompt can influence.
 */
public final class GitRepositoryCloner implements McpRepositoryCloner {

    /** git's output kept for the result or the error; the rest is read and dropped. */
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    /** How long to wait for the output once git has exited. */
    private static final long DRAIN_GRACE_MILLIS = 2_000;

    private final ClientPaths paths;
    private final Executor executor;
    private final Duration timeout;

    /**
     * @param paths    where the mcps directory is
     * @param executor runs the git process; never the client thread
     * @param timeout  how long a clone may take before it is abandoned
     */
    public GitRepositoryCloner(ClientPaths paths, Executor executor, Duration timeout) {
        this.paths = paths;
        this.executor = executor;
        this.timeout = timeout;
    }

    /** A cloner with a two-minute limit, which is generous for a repository this size. */
    public GitRepositoryCloner(ClientPaths paths, Executor executor) {
        this(paths, executor, Duration.ofMinutes(2));
    }

    @Override
    public CompletableFuture<ClonedRepository> clone(String cloneUrl) {
        return CompletableFuture.supplyAsync(() -> cloneNow(cloneUrl), executor);
    }

    private ClonedRepository cloneNow(String cloneUrl) {
        RepositoryCoordinate coordinate;
        String canonicalUrl;
        try {
            coordinate = RepositoryCoordinate.fromCloneUrl(cloneUrl);
            canonicalUrl = RepositoryCoordinate.canonicalCloneUrl(cloneUrl);
        } catch (IllegalArgumentException rejected) {
            throw new McpException(null, rejected.getMessage(), rejected);
        }

        Path destination = paths.mcpRepository(coordinate);
        boolean alreadyPresent = Files.isDirectory(destination.resolve(".git"));

        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException cannotCreate) {
            throw new McpException(coordinate.slug(),
                    "Could not create " + destination.getParent() + ": " + cannotCreate.getMessage(),
                    cannotCreate);
        }

        if (alreadyPresent) {
            requireSameOrigin(destination, canonicalUrl, coordinate);
            String detail = run(destination, List.of("git", "pull", "--ff-only"), coordinate);
            return new ClonedRepository(coordinate, destination, true, detail);
        }

        if (Files.exists(destination) && !isEmpty(destination)) {
            // Not overwritten. Something is there that git did not put there, and deleting
            // a player's files to make room for a clone is never the right answer.
            throw new McpException(coordinate.slug(),
                    destination + " already exists and is not a git checkout");
        }

        String detail = run(destination.getParent(),
                List.of("git", "clone", "--depth", "1", canonicalUrl, coordinate.repository()),
                coordinate);
        return new ClonedRepository(coordinate, destination, false, detail);
    }

    /**
     * Refuses to report an update of a repository the URL does not name.
     *
     * <p>The directory layout has no place for the host, so {@code https://a.example/x/y} and
     * {@code https://b.example/x/y} land in the same directory. Pulling the checkout that is
     * there and calling it the one asked for would tell the player something untrue about
     * where their server's code came from.
     */
    private void requireSameOrigin(Path checkout, String canonicalUrl, RepositoryCoordinate coordinate) {
        String origin = run(checkout, List.of("git", "config", "--get", "remote.origin.url"), coordinate);
        String normalised;
        try {
            normalised = RepositoryCoordinate.canonicalCloneUrl(origin);
        } catch (IllegalArgumentException notHttps) {
            normalised = origin;
        }
        if (!normalised.equalsIgnoreCase(canonicalUrl)) {
            throw new McpException(coordinate.slug(), coordinate.slug()
                    + " is already cloned from a different address, so it was not updated: "
                    + origin);
        }
    }

    private boolean isEmpty(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException cannotList) {
            return false;
        }
    }

    /**
     * Runs one git command and returns what it said.
     *
     * <p>Standard error is merged into standard output because git reports progress there:
     * on failure that stream holds the only useful description of what went wrong, and
     * discarding it leaves "git exited 128" as the whole diagnosis.
     */
    private String run(Path workingDirectory, List<String> command, RepositoryCoordinate coordinate) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        ChildEnvironment.forGit(builder);

        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotStart) {
            throw new McpException(coordinate.slug(),
                    "git is not available on this machine, so repositories cannot be cloned",
                    cannotStart);
        }

        // Drained on its own thread so the timeout below is what bounds the run. Reading on
        // this thread would wait for git to exit before the timeout was ever looked at, and a
        // stalled network would hold it — and the model's tool call — forever.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().name("MCAgents-git-output").start(
                () -> drainCapped(process.getInputStream(), captured));

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stop(process);
                throw new McpException(coordinate.slug(),
                        "git took longer than " + timeout.toSeconds() + "s and was stopped");
            }
            // git has exited; a helper it left holding the pipe must not hold this thread.
            drain.join(DRAIN_GRACE_MILLIS);
        } catch (InterruptedException interrupted) {
            stop(process);
            Thread.currentThread().interrupt();
            throw new McpException(coordinate.slug(), "Clone was interrupted", interrupted);
        }

        String output;
        synchronized (captured) {
            output = captured.toString(StandardCharsets.UTF_8).trim();
        }

        if (process.exitValue() != 0) {
            throw new McpException(coordinate.slug(),
                    "git failed: " + (output.isEmpty() ? "exit " + process.exitValue() : output));
        }
        return output;
    }

    /**
     * Copies {@code in} into {@code into} up to {@link #MAX_OUTPUT_BYTES}, then keeps reading
     * and discarding, so git never blocks on a full pipe and its output cannot grow without
     * limit.
     */
    private static void drainCapped(InputStream in, ByteArrayOutputStream into) {
        byte[] buffer = new byte[8192];
        try (in) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                synchronized (into) {
                    int room = MAX_OUTPUT_BYTES - into.size();
                    if (room > 0) {
                        into.write(buffer, 0, Math.min(room, read));
                    }
                }
            }
        } catch (IOException closed) {
            // The process was stopped; what was read so far is all there is.
        }
    }

    /** Stops git and anything it started, such as a credential or transport helper. */
    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
