package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.McpContextRegistry;
import io.github.mcagentsmods.chat.api.mcp.McpServerStatus;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolResult;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.core.common.testing.StubTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host, driven against real child processes.
 *
 * <p>These are the tests that would have caught the things a unit test cannot see: a
 * handshake that never completes, a server whose stray stdout line derails the stream, a
 * reload that kills a working connection. Each one starts an actual process and speaks
 * actual JSON-RPC to it.
 */
@Timeout(60)
class WatchingMcpHostTest {

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
            Thread thread = new Thread(runnable, "mcp-test");
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

    private WatchingMcpHost host() {
        host = new WatchingMcpHost(paths, new StubTransport(), executor, Duration.ofSeconds(30));
        return host;
    }

    private Path repository(String owner, String name) {
        return paths.mcpDirectory().resolve(owner).resolve(name);
    }

    @Test
    void aModWithNoServersStartsAndIsNotAnError() {
        McpContextRegistry registry = host().start().join();

        assertTrue(registry.isEmpty());
        assertTrue(registry.servers().isEmpty());
        assertTrue(host.configuredServers().isEmpty());
    }

    @Test
    void connectsToADiscoveredServerAndMergesWhatItOffers() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        approveEverything();

        McpContextRegistry registry = host().start().join();

        assertEquals(1, registry.servers().size());
        McpServerStatus status = registry.servers().getFirst();
        assertTrue(status.isConnected(), "failed with: " + status.detail());
        assertEquals(1, status.toolCount());

        assertEquals(1, registry.tools().size());
        assertEquals("echo-mcp__echo", registry.tools().getFirst().qualifiedName());
        assertEquals("Echoes back", registry.tools().getFirst().description());
        assertEquals(1, registry.resources().size());
        assertEquals("fake://one", registry.resources().getFirst().uri());
        assertEquals(1, registry.prompts().size());
        assertEquals("greet", registry.prompts().getFirst().name());
    }

    @Test
    void aStrayLineOnStandardOutputDoesNotDerailTheSession() throws IOException {
        // The fake server prints a non-JSON line before answering anything. If the reader
        // treated that as protocol, the handshake would never complete.
        FakeMcpServer.writeInto(repository("acme", "noisy-mcp"), "echo");
        approveEverything();

        assertTrue(host().start().join().servers().getFirst().isConnected());
    }

    @Test
    void routesACallThroughTheSessionTheRegistryNames() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        approveEverything();
        host().start().join();

        Optional<McpSession> session = host.session("echo-mcp");
        assertTrue(session.isPresent());

        McpToolResult result = session.get().callTool("echo", Map.of("say", "hello")).join();
        assertEquals("echo hello", result.text());
        assertFalse(result.isError());

        assertEquals("body", session.get().readResource("fake://one").join());
    }

    @Test
    void twoServersAreMergedAndKeptApartByTheirPrefixes() throws IOException {
        FakeMcpServer.writeInto(repository("alpha", "first-mcp"), "search");
        FakeMcpServer.writeInto(repository("beta", "second-mcp"), "search");
        approveEverything();

        McpContextRegistry registry = host().start().join();

        assertEquals(2, registry.tools().size(), "same tool name, different servers");
        assertTrue(registry.findTool("first-mcp__search").isPresent());
        assertTrue(registry.findTool("second-mcp__search").isPresent());
    }

    @Test
    void aServerThatCannotStartIsReportedAndTheOthersStillLoad() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "good-mcp"), "echo");
        Path broken = repository("acme", "bad-mcp");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("mcp.json"),
                "{\"command\":\"definitely-not-a-real-program\",\"args\":[]}");
        approveEverything();

        McpContextRegistry registry = host().start().join();

        assertEquals(2, registry.servers().size());
        assertEquals(1, registry.connectedServers().size());
        assertEquals(1, registry.tools().size(), "the working server still contributes");

        McpServerStatus failed = registry.servers().stream()
                .filter(s -> s.spec().id().equals("bad-mcp")).findFirst().orElseThrow();
        assertEquals(McpServerStatus.State.FAILED, failed.state());
        assertFalse(failed.detail().isBlank(), "a failure must say why");
        assertFalse(failed.detail().contains("Exception"),
                "the reason must be readable, not a wrapper class name: " + failed.detail());
    }

    @Test
    void aDisabledServerIsNotStarted() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        Files.writeString(paths.mcpServerCatalog(),
                "{\"mcpServers\":{\"echo-mcp\":{\"enabled\":false}}}");

        McpContextRegistry registry = host().start().join();

        assertEquals(McpServerStatus.State.DISABLED, registry.servers().getFirst().state());
        assertTrue(registry.tools().isEmpty());
        assertTrue(host.session("echo-mcp").isEmpty());
    }

    @Test
    void aReloadKeepsALiveSessionWhoseWiringDidNotChange() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        approveEverything();
        host().start().join();
        McpSession before = host.session("echo-mcp").orElseThrow();

        host.reload().join();

        assertSame(before, host.session("echo-mcp").orElseThrow(),
                "restarting an unchanged server would throw away a working process");
        assertTrue(before.isAlive());
    }

    @Test
    void aReloadReplacesASessionWhoseWiringChanged() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        approveEverything();
        host().start().join();
        McpSession before = host.session("echo-mcp").orElseThrow();

        // A different environment is a different server as far as wiring goes: the child
        // has already been started and cannot be handed a new one.
        Files.writeString(paths.mcpServerCatalog(),
                "{\"mcpServers\":{\"echo-mcp\":{\"command\":" + jsonJava()
                        + ",\"args\":[\"FakeServer.java\"],\"cwd\":\"acme/echo-mcp\","
                        + "\"env\":{\"MCAGENTS_TEST\":\"1\"}}}}");
        host.reload().join();

        McpSession after = host.session("echo-mcp").orElseThrow();
        assertTrue(after.isAlive());
        assertFalse(before.isAlive(), "the replaced process must be stopped, not leaked");
    }

    @Test
    void aServerRemovedFromDiskIsDisconnected() throws IOException {
        Path repository = repository("acme", "echo-mcp");
        FakeMcpServer.writeInto(repository, "echo");
        approveEverything();
        host().start().join();
        McpSession before = host.session("echo-mcp").orElseThrow();

        try (var entries = Files.walk(repository)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // Best effort; the assertion below is what matters.
                }
            });
        }
        host.reload().join();

        assertTrue(host.session("echo-mcp").isEmpty());
        assertFalse(before.isAlive(), "its process must not outlive the configuration");
        assertTrue(host.registry().tools().isEmpty());
    }

    @Test
    void aServerClonedWhileRunningIsPickedUpWithoutARestart() throws Exception {
        // Approved ahead of the clone, so what is under test is the watcher noticing it.
        FakeMcpServer.approve(paths.mcpDirectory(), Map.of("late-mcp",
                McpApproval.fingerprint(FakeMcpServer.discoveredSpec("acme", "late-mcp"))));
        host().start().join();
        assertTrue(host.registry().isEmpty(), "nothing to begin with");

        CountDownLatch appeared = new CountDownLatch(1);
        host.onRegistryChanged(registry -> {
            if (!registry.tools().isEmpty()) {
                appeared.countDown();
            }
        });

        // Exactly what the clone tool does: a new repository under mcps/.
        FakeMcpServer.writeInto(repository("acme", "late-mcp"), "late");

        assertTrue(appeared.await(45, TimeUnit.SECONDS),
                "the watcher must notice a new server without the game restarting");
        assertTrue(host.registry().findTool("late-mcp__late").isPresent());
        assertNotNull(host.session("late-mcp").orElse(null));
    }

    @Test
    void closingStopsEveryChildProcess() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        approveEverything();
        host().start().join();
        McpSession session = host.session("echo-mcp").orElseThrow();

        host.close();

        assertFalse(session.isAlive());
        assertTrue(host.registry().isEmpty());
        assertTrue(host.session("echo-mcp").isEmpty());
    }

    @Test
    void aClonedServerIsNotStartedUntilThePlayerApprovesIt() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");

        McpContextRegistry registry = host().start().join();

        McpServerStatus status = registry.servers().getFirst();
        assertEquals(McpServerStatus.State.AWAITING_APPROVAL, status.state());
        String fingerprint = McpApproval.fingerprint(FakeMcpServer.discoveredSpec("acme", "echo-mcp"));
        assertTrue(status.detail().contains(fingerprint), status.detail());
        assertTrue(status.detail().contains("FakeServer.java"),
                "the player must be shown what would run: " + status.detail());
        assertTrue(registry.tools().isEmpty());
        assertTrue(host.session("echo-mcp").isEmpty(), "nothing may run before approval");
    }

    @Test
    void anApprovalForAnotherCommandDoesNotStartIt() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        approveEverything();
        host().start().join();
        McpSession before = host.session("echo-mcp").orElseThrow();

        // What a pull bringing in a changed mcp.json looks like.
        Files.writeString(repository("acme", "echo-mcp").resolve("mcp.json"),
                "{\"command\":" + jsonJava() + ",\"args\":[\"FakeServer.java\",\"--other\"]}");
        McpContextRegistry registry = host.reload().join();

        assertEquals(McpServerStatus.State.AWAITING_APPROVAL, registry.servers().getFirst().state());
        assertFalse(before.isAlive(), "the approved process must stop once its wiring changes");
        assertTrue(host.session("echo-mcp").isEmpty());
    }

    @Test
    void aServerTheCatalogWiresItselfNeedsNoApproval() throws IOException {
        FakeMcpServer.writeInto(repository("acme", "echo-mcp"), "echo");
        Files.writeString(paths.mcpServerCatalog(),
                "{\"mcpServers\":{\"echo-mcp\":{\"command\":" + jsonJava()
                        + ",\"args\":[\"FakeServer.java\"],\"cwd\":\"acme/echo-mcp\"}}}");

        assertTrue(host().start().join().servers().getFirst().isConnected());
    }

    private void approveEverything() throws IOException {
        FakeMcpServer.approveEverything(paths.mcpDirectory());
    }

    private static String jsonJava() {
        return "\"" + FakeMcpServer.javaCommand().replace("\\", "\\\\") + "\"";
    }
}
