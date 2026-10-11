package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.RepositoryCoordinate;
import io.github.mcagentsmods.chat.common.ClientPaths;
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
    void refusesAPathLongerThanOwnerAndRepository() {
        // Cut down to its first two segments, this would be filed and reported as a/b while
        // git fetched something else.
        for (String url : new String[] {
                "https://evil.example/a/b/../../x",
                "https://github.com/a/b/c",
                "https://github.com/a",
                "https://github.com//b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RepositoryCoordinate.fromCloneUrl(url),
                    url + " should be refused");
        }
    }

    @Test
    void refusesCredentialsPortsQueriesAndFragments() {
        for (String url : new String[] {
                "https://user:secret@github.com/a/b",
                "https://github.com:8443/a/b",
                "https://github.com/a/b?ref=x",
                "https://github.com/a/b#frag"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RepositoryCoordinate.fromCloneUrl(url),
                    url + " should be refused");
        }
    }

    @Test
    void namesTheHostAndRebuildsTheUrlGitIsGiven() {
        String url = "https://GitHub.com/LXAgents-MCP/shared-instruction/";

        assertEquals("github.com", RepositoryCoordinate.hostOf(url));
        assertEquals("https://github.com/LXAgents-MCP/shared-instruction.git",
                RepositoryCoordinate.canonicalCloneUrl(url));
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
