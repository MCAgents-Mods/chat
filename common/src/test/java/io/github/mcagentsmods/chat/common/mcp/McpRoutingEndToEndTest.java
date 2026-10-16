package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.core.common.testing.StubTransport;
import io.github.mcagentsmods.core.tool.ToolExecutor;
import io.github.mcagentsmods.core.tool.ToolInvocation;
import io.github.mcagentsmods.core.tool.ToolOutcome;
import io.github.mcagentsmods.core.tool.ToolRegistry;
import io.github.mcagentsmods.core.tool.ToolSpec;
import io.github.mcagentsmods.core.tool.WorkerPool;
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
 * <p>{@link McpTools} is unit-tested against doubles; this is the same path, through the
 * agents registry and executor, with nothing faked below the process boundary. It is the test
 * that would catch a mismatch between what discovery names a server, what the registry
 * qualifies its tools as, and what {@code McpTools} strips before calling — three places that
 * have to agree and are edited separately.
 */
@Timeout(60)
class McpRoutingEndToEndTest {

    @TempDir
    Path gameDirectory;

    private ClientPaths paths;
    private ExecutorService executor;
    private WatchingMcpHost host;
    private final ToolRegistry registry = new ToolRegistry();
    private final WorkerPool pool = new WorkerPool(2);
    private final ToolExecutor tools = new ToolExecutor(pool);

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
        pool.close();
        executor.shutdownNow();
    }

    private void serve(String owner, String repository, String tool) throws IOException {
        FakeMcpServer.writeInto(paths.mcpDirectory().resolve(owner).resolve(repository), tool);
        FakeMcpServer.approveEverything(paths.mcpDirectory());
        startHost();
    }

    private void startHost() {
        host = new WatchingMcpHost(paths, new StubTransport(), executor, Duration.ofSeconds(30));
        host.start().join();
        new McpTools(host, registry).start(url -> java.util.concurrent.CompletableFuture
                .failedFuture(new IllegalStateException("not in this test")));
    }

    /** The servers' tools the registry offers, without the clone tool. */
    private List<String> offered() {
        return registry.tools().stream()
                .map(ToolSpec::name)
                .filter(name -> !name.equals(CloneRepositoryTool.NAME))
                .toList();
    }

    private ToolOutcome call(String id, String name, String arguments) {
        return tools.run(new ToolInvocation(id, name, arguments), registry::find).join();
    }

    @Test
    void aCallOnARealServerRunsAndComesBack() throws IOException {
        serve("acme", "echo-mcp", "echo");

        assertEquals(List.of("echo-mcp__echo"), offered());

        ToolOutcome outcome = call("c1", "echo-mcp__echo", "{\"say\":\"hello\"}");

        assertFalse(outcome.isError(), outcome.text());
        assertEquals("echo hello", outcome.text());
    }

    @Test
    void theNameTheModelIsShownIsTheNameThatRoutes() throws IOException {
        // Discovery names the server, the registry qualifies its tools, and McpTools strips
        // that qualification again. All three must agree, and they are edited apart.
        serve("acme", "echo-mcp", "echo");

        ToolOutcome outcome = call("c1", offered().getFirst(), "{\"say\":\"x\"}");

        assertFalse(outcome.isError(), "the offered name must be routable: " + outcome.text());
    }

    @Test
    void aCallToAToolTheServerDoesNotHaveComesBackAsAnError() throws IOException {
        serve("acme", "echo-mcp", "echo");

        ToolOutcome outcome = call("c1", "echo-mcp__missing", "{}");

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("no tool called"), outcome.text());
    }

    @Test
    void twoServersRouteIndependently() throws IOException {
        FakeMcpServer.writeInto(paths.mcpDirectory().resolve("alpha").resolve("one-mcp"), "ping");
        FakeMcpServer.writeInto(paths.mcpDirectory().resolve("beta").resolve("two-mcp"), "pong");
        FakeMcpServer.approveEverything(paths.mcpDirectory());
        startHost();

        assertEquals(2, offered().size());
        assertEquals(java.util.Set.of("one-mcp"),
                registry.find("one-mcp__ping").orElseThrow().spec().categories(),
                "each server is a category of its own");
        assertFalse(call("c1", "one-mcp__ping", "{\"say\":\"a\"}").isError());
        assertFalse(call("c2", "two-mcp__pong", "{\"say\":\"b\"}").isError());
    }

    @Test
    void aServerStoppedAfterItsToolsWereOfferedFailsReadably() throws IOException {
        serve("acme", "echo-mcp", "echo");
        assertEquals(1, offered().size());

        // The registry still holds the tool; the session is gone. Exactly what a server
        // dying between the model being offered a tool and calling it looks like.
        host.session("echo-mcp").orElseThrow().close();

        ToolOutcome outcome = call("c1", "echo-mcp__echo", "{\"say\":\"x\"}");

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("not connected"), outcome.text());
    }
}
