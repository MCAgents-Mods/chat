package io.github.mcagents.clientfabric.common.mcp;

import io.github.mcagents.clientfabric.api.mcp.McpException;
import io.github.mcagents.clientfabric.api.mcp.McpRepositoryCloner;
import io.github.mcagents.clientfabric.api.mcp.RepositoryCoordinate;
import io.github.mcagents.clientfabric.common.ClientPaths;

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
        try {
            coordinate = RepositoryCoordinate.fromCloneUrl(cloneUrl);
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
                List.of("git", "clone", "--depth", "1", cloneUrl.trim(), coordinate.repository()),
                coordinate);
        return new ClonedRepository(coordinate, destination, false, detail);
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

        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotStart) {
            throw new McpException(coordinate.slug(),
                    "git is not available on this machine, so repositories cannot be cloned",
                    cannotStart);
        }

        String output;
        try (InputStream out = process.getInputStream()) {
            output = new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException cannotRead) {
            output = "";
        }

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new McpException(coordinate.slug(),
                        "git took longer than " + timeout.toSeconds() + "s and was stopped");
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new McpException(coordinate.slug(), "Clone was interrupted", interrupted);
        }

        if (process.exitValue() != 0) {
            throw new McpException(coordinate.slug(),
                    "git failed: " + (output.isEmpty() ? "exit " + process.exitValue() : output));
        }
        return output;
    }
}
