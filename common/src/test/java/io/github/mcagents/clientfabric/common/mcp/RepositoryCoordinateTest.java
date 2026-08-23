package io.github.mcagents.clientfabric.common.mcp;

import io.github.mcagents.clientfabric.api.mcp.RepositoryCoordinate;
import io.github.mcagents.clientfabric.common.ClientPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validation that stands between a model-supplied string and the player's filesystem.
 *
 * <p>These are the tests worth having twice. A coordinate is turned straight into a path
 * under {@code MCAgents/mcps/}, and the values reaching it are frequently chosen by a
 * language model rather than by a person.
 */
class RepositoryCoordinateTest {

    @TempDir
    Path gameDirectory;

    @Test
    void readsOwnerAndRepositoryFromACloneUrl() {
        RepositoryCoordinate coordinate =
                RepositoryCoordinate.fromCloneUrl("https://github.com/LXAgents-MCP/shared-instruction.git");

        assertEquals("LXAgents-MCP", coordinate.owner());
        assertEquals("shared-instruction", coordinate.repository());
        assertEquals("LXAgents-MCP/shared-instruction", coordinate.slug());
    }

    @Test
    void acceptsAUrlWithoutTheGitSuffixOrWithATrailingSlash() {
        assertEquals("a/b", RepositoryCoordinate.fromCloneUrl("https://github.com/a/b").slug());
        assertEquals("a/b", RepositoryCoordinate.fromCloneUrl("https://github.com/a/b/").slug());
    }

    @Test
    void refusesEverySchemeButHttps() {
        for (String url : new String[] {
                "git@github.com:owner/repo.git",
                "file:///etc/passwd",
                "git://github.com/owner/repo",
                "http://github.com/owner/repo"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RepositoryCoordinate.fromCloneUrl(url),
                    url + " should be refused");
        }
    }

    @Test
    void refusesTraversalInEitherSegment() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryCoordinate("..", "repo"));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryCoordinate("owner", ".."));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryCoordinate("a/b", "repo"));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryCoordinate("owner", "a/../../b"));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryCoordinate("owner", ""));
    }

    @Test
    void aTraversingUrlCannotProduceACoordinate() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinate.fromCloneUrl("https://github.com/../../etc/passwd"));
    }

    @Test
    void everyValidCoordinateResolvesInsideTheMcpsDirectory() {
        ClientPaths paths = ClientPaths.under(gameDirectory);
        Path resolved = paths.mcpRepository(new RepositoryCoordinate("LXAgents-MCP", "shared-instruction"));

        assertTrue(resolved.startsWith(paths.mcpDirectory()),
                resolved + " must be under " + paths.mcpDirectory());
        assertEquals(paths.mcpDirectory().resolve("LXAgents-MCP").resolve("shared-instruction"),
                resolved);
    }
}
