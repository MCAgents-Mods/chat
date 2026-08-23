package io.github.mcagents.clientfabric.api.mcp;

import java.util.concurrent.CompletableFuture;

/**
 * Clones an external MCP repository into the mod's own directory.
 *
 * <p>This is the capability the agent is given as a tool, which is why the contract is
 * narrow. It takes a clone URL and nothing else: the destination is derived from the URL
 * rather than supplied, so no caller — model included — chooses where files land.
 */
@FunctionalInterface
public interface McpRepositoryCloner {

    /**
     * Clones {@code cloneUrl} to {@code {minecraft_dir}/MCAgents/mcps/{owner}/{repository}/}.
     *
     * <p>An existing clone is updated in place rather than re-cloned, so calling this twice
     * is not an error and does not discard local state.
     *
     * @param cloneUrl an https git URL
     * @return what was cloned and where; the future fails with {@link McpException} if the
     *         clone could not be made
     */
    CompletableFuture<ClonedRepository> clone(String cloneUrl);

    /**
     * The outcome of a clone.
     *
     * @param coordinate  what was cloned
     * @param directory   the absolute directory it landed in
     * @param alreadyPresent whether it was already there and was updated instead
     * @param detail      what git reported, for showing to a player
     */
    record ClonedRepository(
            RepositoryCoordinate coordinate,
            java.nio.file.Path directory,
            boolean alreadyPresent,
            String detail) {
    }
}
