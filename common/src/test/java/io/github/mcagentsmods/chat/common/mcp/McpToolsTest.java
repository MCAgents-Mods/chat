package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.McpContextRegistry;
import io.github.mcagentsmods.chat.api.mcp.McpException;
import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpPromptDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpRepositoryCloner;
import io.github.mcagentsmods.chat.api.mcp.McpResourceDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpToolResult;
import io.github.mcagentsmods.chat.api.mcp.RepositoryCoordinate;
import io.github.mcagentsmods.core.tool.ExecutionMode;
import io.github.mcagentsmods.core.tool.ToolArguments;
import io.github.mcagentsmods.core.tool.ToolExecutor;
import io.github.mcagentsmods.core.tool.ToolInvocation;
import io.github.mcagentsmods.core.tool.ToolNames;
import io.github.mcagentsmods.core.tool.ToolOutcome;
import io.github.mcagentsmods.core.tool.ToolRegistry;
import io.github.mcagentsmods.core.tool.ToolResult;
import io.github.mcagentsmods.core.tool.ToolSpec;
import io.github.mcagentsmods.core.tool.WorkerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP servers' tools in the agents registry: one category per server, kept in step with
 * the host, and each call turned back into a call on the server that owns the tool.
 *
 * <p>Most tests here are failure cases, because that is where the design lives: a tool call
 * that goes wrong must come back as something the model can read and recover from, never as a
 * broken request that kills the player's question.
 */
@Timeout(30)
class McpToolsTest {

    private static final McpRepositoryCloner NO_CLONES = url ->
            CompletableFuture.failedFuture(new IllegalStateException("not in this test"));

    private final ToolRegistry registry = new ToolRegistry();
    private final WorkerPool pool = new WorkerPool(2);
    private final ToolExecutor executor = new ToolExecutor(pool);

    @AfterEach
    void close() {
        pool.close();
    }

    private static McpToolDescriptor tool(String server, String name) {
        return new McpToolDescriptor(server, name, "Does " + name, "{\"type\":\"object\"}");
    }

    private McpTools started(TestHost host) {
        return new McpTools(host, registry).start(NO_CLONES);
    }

    private ToolOutcome call(String name, String arguments) {
        return executor.run(new ToolInvocation("call-1", name, arguments), registry::find).join();
    }

    private List<String> mcpToolNames() {
        return registry.tools().stream()
                .map(ToolSpec::name)
                .filter(name -> !name.equals(CloneRepositoryTool.NAME))
                .toList();
    }

    @Test
    void registersEveryToolTheHostHasInACategoryPerServer() {
        started(new TestHost(List.of(tool("weather", "forecast"), tool("files", "read"))));

        assertEquals(List.of("files__read", "weather__forecast"), mcpToolNames());
        assertEquals(Set.of("weather"), registry.find("weather__forecast").orElseThrow().spec().categories());
        assertEquals(Set.of("files"), registry.find("files__read").orElseThrow().spec().categories());
        assertTrue(registry.category("weather").orElseThrow().description().contains("'weather'"));
    }

    @Test
    void namesTheServerInTheDescriptionSoTwoSimilarToolsAreNotInterchangeable() {
        started(new TestHost(List.of(tool("weather", "forecast"))));

        String description = registry.find("weather__forecast").orElseThrow().spec().description();
        assertTrue(description.contains("'weather'"), description);
    }

    @Test
    void serverToolsRunTogetherWhenAskedTogether() {
        started(new TestHost(List.of(tool("weather", "forecast"))));

        assertEquals(ExecutionMode.CONCURRENT,
                registry.find("weather__forecast").orElseThrow().spec().mode());
    }

    @Test
    void everyRegisteredNameIsOneAProviderAccepts() {
        // Hostile names from a server: spaces, slashes, dots, a symbol, and far too long.
        // A provider rejects the WHOLE request over one bad function name, so a single
        // server naming a tool badly would silence every other server's tools too.
        started(new TestHost(List.of(
                tool("weather service", "get/forecast.now"),
                tool("files", "read file"),
                tool("emoji", "search❤"),
                tool("x".repeat(80), "y".repeat(80)))));

        List<ToolSpec> specs = registry.tools();

        assertEquals(5, specs.size(), "sanitizing keeps them, it does not drop them");
        for (ToolSpec spec : specs) {
            assertTrue(ToolNames.isValid(spec.name()), "'" + spec.name() + "' would be refused");
            spec.categories().forEach(category ->
                    assertTrue(ToolNames.isValid(category), "'" + category + "' would be refused"));
        }
    }

    @Test
    void aCategoryIsTheServerIdAsACategoryMustBeWritten() {
        assertEquals("weather", McpTools.categoryOf("weather"));
        assertEquals("weather_service", McpTools.categoryOf("weather service"));
        assertEquals("caf_", McpTools.categoryOf("café"));
        assertEquals(64, McpTools.categoryOf("x".repeat(80)).length());
        assertEquals("_", McpTools.categoryOf(""));
    }

    @Test
    void routesToTheServerNamedInTheQualifiedName() {
        RecordingSession weather = new RecordingSession("weather", McpToolResult.ok("sunny"));
        RecordingSession files = new RecordingSession("files", McpToolResult.ok("contents"));
        started(new TestHost(List.of(tool("weather", "forecast"), tool("files", "read")),
                weather, files));

        ToolOutcome outcome = call("files__read", "{\"path\":\"a.txt\"}");

        assertEquals("contents", outcome.text());
        assertFalse(outcome.isError());
        assertEquals(0, weather.calls.size(), "the other server must not be touched");
    }

    @Test
    void stripsTheQualificationBeforeCallingTheServer() {
        RecordingSession session = new RecordingSession("weather", McpToolResult.ok("sunny"));
        started(new TestHost(List.of(tool("weather", "forecast")), session));

        call("weather__forecast", "{}");

        assertEquals("forecast", session.calls.getFirst(),
                "the server has never heard of the qualified name");
    }

    @Test
    void passesTheModelsArgumentsThrough() {
        RecordingSession session = new RecordingSession("weather", McpToolResult.ok("sunny"));
        started(new TestHost(List.of(tool("weather", "forecast")), session));

        call("weather__forecast", "{\"city\":\"Oslo\",\"days\":3}");

        assertEquals("\"Oslo\"", String.valueOf(session.arguments.getFirst().get("city")));
        assertEquals("3", String.valueOf(session.arguments.getFirst().get("days")));
    }

    @Test
    void aDisconnectedServerSaysSoRatherThanFailing() {
        // In the registry, but no live session: exactly what a server dying mid-question
        // looks like from here.
        started(new TestHost(List.of(tool("weather", "forecast"))));

        ToolOutcome outcome = call("weather__forecast", "{}");

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("not connected"), outcome.text());
    }

    @Test
    void aServerThrowingBecomesAReadableOutcome() {
        RecordingSession session = new RecordingSession("weather", null);
        session.failure = new McpException("weather", "the upstream API is down");
        started(new TestHost(List.of(tool("weather", "forecast")), session));

        ToolOutcome outcome = call("weather__forecast", "{}");

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("the upstream API is down"), outcome.text());
        assertFalse(outcome.text().contains("Exception"),
                "the model must get a reason, not a class name: " + outcome.text());
    }

    @Test
    void aToolReportingItsOwnErrorKeepsThatDistinction() {
        RecordingSession session =
                new RecordingSession("weather", McpToolResult.error("no such city"));
        started(new TestHost(List.of(tool("weather", "forecast")), session));

        ToolOutcome outcome = call("weather__forecast", "{}");

        assertTrue(outcome.isError());
        assertEquals("no such city", outcome.text(),
                "a server's own error text is passed through, not wrapped");
    }

    @Test
    void aSchemaThatIsNotAnObjectIsOfferedWithNoArguments() {
        started(new TestHost(List.of(
                new McpToolDescriptor("weather", "forecast", "Forecasts", "[1, 2]"))));

        assertTrue(registry.contains("weather__forecast"));
    }

    @Test
    void followsTheHostAsServersComeAndGo() {
        TestHost host = new TestHost(List.of(tool("weather", "forecast")));
        started(host);

        host.change(List.of(tool("files", "read")));

        assertEquals(List.of("files__read"), mcpToolNames());
    }

    @Test
    void aToolThatChangedIsReplaced() {
        TestHost host = new TestHost(List.of(tool("weather", "forecast")));
        started(host);

        host.change(List.of(new McpToolDescriptor("weather", "forecast", "Forecasts better",
                "{\"type\":\"object\"}")));

        assertTrue(registry.find("weather__forecast").orElseThrow().spec().description()
                .startsWith("Forecasts better"));
    }

    @Test
    void closingRemovesEveryToolItRegistered() {
        McpTools tools = started(new TestHost(List.of(tool("weather", "forecast"))));

        tools.close();

        assertTrue(registry.isEmpty());
    }

    @Test
    void theCloneToolIsThereWithNoServersInItsOwnCategoryAndRunsAlone() {
        started(new TestHost(List.of()));

        ToolSpec clone = registry.find(CloneRepositoryTool.NAME).orElseThrow().spec();
        assertEquals(Set.of(McpTools.CLONE_CATEGORY), clone.categories());
        assertEquals(ExecutionMode.WAIT, clone.mode(), "it writes files and restarts servers");
    }

    @Test
    void theCloneToolNeedsAUrl() throws Exception {
        CloneRepositoryTool tool = new CloneRepositoryTool(NO_CLONES, new TestHost(List.of()));

        ToolResult result = tool.call(ToolArguments.parse("{}")).toCompletableFuture().join();

        assertTrue(result.isError());
        assertTrue(result.text().contains("'url'"), result.text());
    }

    @Test
    void theCloneToolSaysWhatArrivedAndWhatItOffers() throws Exception {
        RepositoryCoordinate coordinate = new RepositoryCoordinate("acme", "weather");
        McpRepositoryCloner cloner = url -> CompletableFuture.completedFuture(
                new McpRepositoryCloner.ClonedRepository(coordinate, Path.of("weather"), false, ""));
        TestHost host = new TestHost(List.of(tool("weather", "forecast")));
        CloneRepositoryTool tool = new CloneRepositoryTool(cloner, host);

        ToolResult result = tool.call(ToolArguments.parse(
                "{\"url\":\"https://github.com/acme/weather.git\"}")).toCompletableFuture().join();

        assertFalse(result.isError(), result.text());
        assertTrue(result.text().startsWith("Cloned acme/weather from github.com"), result.text());
        assertTrue(result.text().contains("offers 1 tool."), result.text());
    }

    @Test
    void aFailedCloneIsAReason() throws Exception {
        McpRepositoryCloner cloner = url -> CompletableFuture.failedFuture(
                new IllegalStateException("git is not installed"));
        CloneRepositoryTool tool = new CloneRepositoryTool(cloner, new TestHost(List.of()));

        ToolResult result = tool.call(ToolArguments.parse(
                "{\"url\":\"https://github.com/acme/weather.git\"}")).toCompletableFuture().join();

        assertTrue(result.isError());
        assertTrue(result.text().contains("git is not installed"), result.text());
    }

    // ---- doubles ----

    /** A host whose registry the test sets, and which tells its listeners when it does. */
    private static final class TestHost implements McpHost {

        private final List<RecordingSession> sessions;
        private final List<Consumer<McpContextRegistry>> listeners = new ArrayList<>();
        private volatile McpContextRegistry registry;

        TestHost(List<McpToolDescriptor> tools, RecordingSession... sessions) {
            this.registry = registryOf(tools);
            this.sessions = List.of(sessions);
        }

        void change(List<McpToolDescriptor> tools) {
            registry = registryOf(tools);
            listeners.forEach(listener -> listener.accept(registry));
        }

        private static McpContextRegistry registryOf(List<McpToolDescriptor> tools) {
            return McpContextRegistry.merge(tools, List.of(), List.of(), List.of(), null);
        }

        @Override
        public CompletableFuture<McpContextRegistry> start() {
            return CompletableFuture.completedFuture(registry);
        }

        @Override
        public McpContextRegistry registry() {
            return registry;
        }

        @Override
        public CompletableFuture<McpContextRegistry> reload() {
            return CompletableFuture.completedFuture(registry);
        }

        @Override
        public Optional<McpSession> session(String serverId) {
            return sessions.stream()
                    .filter(session -> session.serverId().equals(serverId))
                    .map(McpSession.class::cast)
                    .findFirst();
        }

        @Override
        public void onRegistryChanged(Consumer<McpContextRegistry> listener) {
            listeners.add(listener);
        }

        @Override
        public List<McpServerSpec> configuredServers() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    /** A session that records what it was asked and answers from a script. */
    private static final class RecordingSession implements McpSession {

        private final String serverId;
        private final McpToolResult result;
        private final List<String> calls = new ArrayList<>();
        private final List<Map<String, Object>> arguments = new ArrayList<>();
        private RuntimeException failure;

        RecordingSession(String serverId, McpToolResult result) {
            this.serverId = serverId;
            this.result = result;
        }

        @Override
        public String serverId() {
            return serverId;
        }

        @Override
        public CompletableFuture<List<McpToolDescriptor>> listTools() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<McpResourceDescriptor>> listResources() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<McpPromptDescriptor>> listPrompts() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public synchronized CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
            calls.add(name);
            arguments.add(args);
            return failure != null
                    ? CompletableFuture.failedFuture(failure)
                    : CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<String> readResource(String uri) {
            return CompletableFuture.completedFuture("");
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
