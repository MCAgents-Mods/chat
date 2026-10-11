package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.agent.ToolCall;
import io.github.mcagentsmods.chat.api.agent.ToolDefinition;
import io.github.mcagentsmods.chat.api.agent.ToolOutcome;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.chat.common.agent.StubTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model's tool call reaching a real server and coming back.
 *
 * <p>The router is unit-tested against doubles; this is the same path with nothing faked
 * below the process boundary. It is the test that would catch a mismatch between what
 * discovery names a server, what the registry qualifies its tools as, and what the router
 * strips before calling — three places that have to agree and are edited separately.
 */
@Timeout(60)
class McpRoutingEndToEndTest {

    @TempDir
    Path gameDirectory;

    private ClientPaths paths;
    private ExecutorService executor;
    private WatchingMcpHost host;

    @BeforeEach
    void setUp() throws IOException {
        paths = ClientPaths.under(gameDirectory);
        paths.createMcpDirectory();
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "routing-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.close();
        }
        executor.shutdownNow();
    }

    private McpToolRouter routerWith(String owner, String repository, String tool)
            throws IOException {
        FakeMcpServer.writeInto(paths.mcpDirectory().resolve(owner).resolve(repository), tool);
        host = new WatchingMcpHost(paths, new StubTransport(), executor, Duration.ofSeconds(30));
        host.start().join();
        return new McpToolRouter(host);
    }

    @Test
    void aCallOnARealServerRunsAndComesBack() throws IOException {
        McpToolRouter router = routerWith("acme", "echo-mcp", "echo");

        List<ToolDefinition> definitions = router.definitions();
        assertEquals(1, definitions.size());
        assertEquals("echo-mcp__echo", definitions.getFirst().name());

        ToolOutcome outcome = router
                .invoke(new ToolCall("c1", "echo-mcp__echo", "{\"say\":\"hello\"}")).join();

        assertFalse(outcome.isError(), outcome.text());
        assertEquals("echo hello", outcome.text());
    }

    @Test
    void theNameTheModelIsShownIsTheNameThatRoutes() throws IOException {
        // Discovery names the server, the registry qualifies its tools, and the router
        // strips that qualification again. All three must agree, and they are edited apart.
        McpToolRouter router = routerWith("acme", "echo-mcp", "echo");

        String offered = router.definitions().getFirst().name();
        ToolOutcome outcome = router.invoke(new ToolCall("c1", offered, "{\"say\":\"x\"}")).join();

        assertFalse(outcome.isError(), "the offered name must be routable: " + outcome.text());
    }

    @Test
    void aCallToAToolTheServerDoesNotHaveComesBackAsAnError() throws IOException {
        McpToolRouter router = routerWith("acme", "echo-mcp", "echo");

        // Registered in the registry under a name the server will refuse.
        ToolOutcome outcome = router
                .invoke(new ToolCall("c1", "echo-mcp__missing", "{}")).join();

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("no tool called"), outcome.text());
    }

    @Test
    void twoServersRouteIndependently() throws IOException {
        FakeMcpServer.writeInto(paths.mcpDirectory().resolve("alpha").resolve("one-mcp"), "ping");
        FakeMcpServer.writeInto(paths.mcpDirectory().resolve("beta").resolve("two-mcp"), "pong");
        host = new WatchingMcpHost(paths, new StubTransport(), executor, Duration.ofSeconds(30));
        host.start().join();
        McpToolRouter router = new McpToolRouter(host);

        assertEquals(2, router.definitions().size());
        assertFalse(router.invoke(new ToolCall("c1", "one-mcp__ping", "{\"say\":\"a\"}"))
                .join().isError());
        assertFalse(router.invoke(new ToolCall("c2", "two-mcp__pong", "{\"say\":\"b\"}"))
                .join().isError());
    }

    @Test
    void aServerStoppedAfterItsToolsWereOfferedFailsReadably() throws IOException {
        McpToolRouter router = routerWith("acme", "echo-mcp", "echo");
        assertEquals(1, router.definitions().size());

        // The registry still holds the tool; the session is gone. Exactly what a server
        // dying between the model being offered a tool and calling it looks like.
        host.session("echo-mcp").orElseThrow().close();

        ToolOutcome outcome = router
                .invoke(new ToolCall("c1", "echo-mcp__echo", "{\"say\":\"x\"}")).join();

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("not connected"), outcome.text());
    }
}
