package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.agent.ToolCall;
import io.github.mcagentsmods.chat.api.agent.ToolDefinition;
import io.github.mcagentsmods.chat.api.agent.ToolOutcome;
import io.github.mcagentsmods.chat.api.mcp.McpContextRegistry;
import io.github.mcagentsmods.chat.api.mcp.McpException;
import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpPromptDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpResourceDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a name the model wrote back into a call on the server that owns the tool.
 *
 * <p>Almost every test here is a failure case, because that is where the design lives: a
 * tool call that goes wrong must come back as something the model can read and recover
 * from, never as a broken request that kills the player's question.
 */
class McpToolRouterTest {

    private static McpToolDescriptor tool(String server, String name) {
        return new McpToolDescriptor(server, name, "Does " + name, "{\"type\":\"object\"}");
    }

    private static ToolCall call(String name, String arguments) {
        return new ToolCall("call-1", name, arguments);
    }

    @Test
    void offersEveryToolTheRegistryHolds() {
        McpToolRouter router = new McpToolRouter(
                hostWith(List.of(tool("weather", "forecast"), tool("files", "read"))));

        List<String> names = router.definitions().stream().map(ToolDefinition::name).toList();

        assertEquals(List.of("weather__forecast", "files__read"), names);
    }

    @Test
    void namesTheServerInTheDescriptionSoTwoSimilarToolsAreNotInterchangeable() {
        McpToolRouter router = new McpToolRouter(hostWith(List.of(tool("weather", "forecast"))));

        assertTrue(router.definitions().getFirst().description().contains("'weather'"),
                router.definitions().getFirst().description());
    }

    @Test
    void everyOfferedNameIsOneAProviderAccepts() {
        // Hostile names from a server: spaces, slashes, dots, unicode, and far too long.
        // A provider rejects the WHOLE request over one bad function name, so a single
        // server naming a tool badly would silence every other server's tools too.
        McpToolRouter router = new McpToolRouter(hostWith(List.of(
                tool("weather service", "get/forecast.now"),
                tool("files", "read file"),
                tool("emoji", "search\u2764"),
                tool("x".repeat(80), "y".repeat(80)))));

        List<ToolDefinition> definitions = router.definitions();

        assertEquals(4, definitions.size(), "sanitizing keeps them, it does not drop them");
        for (ToolDefinition definition : definitions) {
            assertTrue(ToolDefinition.isAcceptable(definition.name()),
                    "'" + definition.name() + "' would make the provider reject everything");
        }
    }

    @Test
    void buildingADefinitionRefusesANameAProviderWouldReject() {
        // The router filters before constructing, so this guard is the second lock. It is
        // asserted directly because the first lock means it cannot be reached through the
        // router today, and a change to qualification is exactly when it would matter.
        assertFalse(ToolDefinition.isAcceptable("has spaces"));
        assertFalse(ToolDefinition.isAcceptable("x".repeat(65)));
        assertFalse(ToolDefinition.isAcceptable(""));
        assertTrue(ToolDefinition.isAcceptable("weather__forecast"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition("has spaces", "", ""));
    }

    @Test
    void routesToTheServerNamedInTheQualifiedName() {
        RecordingSession weather = new RecordingSession("weather", McpToolResult.ok("sunny"));
        RecordingSession files = new RecordingSession("files", McpToolResult.ok("contents"));
        McpToolRouter router = new McpToolRouter(
                hostWith(List.of(tool("weather", "forecast"), tool("files", "read")),
                        weather, files));

        ToolOutcome outcome = router.invoke(call("files__read", "{\"path\":\"a.txt\"}")).join();

        assertEquals("contents", outcome.text());
        assertFalse(outcome.isError());
        assertEquals(0, weather.calls.size(), "the other server must not be touched");
    }

    @Test
    void stripsTheQualificationBeforeCallingTheServer() {
        RecordingSession session = new RecordingSession("weather", McpToolResult.ok("sunny"));
        McpToolRouter router =
                new McpToolRouter(hostWith(List.of(tool("weather", "forecast")), session));

        router.invoke(call("weather__forecast", "{}")).join();

        assertEquals("forecast", session.calls.getFirst(),
                "the server has never heard of the qualified name");
    }

    @Test
    void passesTheModelsArgumentsThrough() {
        RecordingSession session = new RecordingSession("weather", McpToolResult.ok("sunny"));
        McpToolRouter router =
                new McpToolRouter(hostWith(List.of(tool("weather", "forecast")), session));

        router.invoke(call("weather__forecast", "{\"city\":\"Oslo\",\"days\":3}")).join();

        assertEquals("\"Oslo\"", String.valueOf(session.arguments.getFirst().get("city")));
        assertEquals("3", String.valueOf(session.arguments.getFirst().get("days")));
    }

    @Test
    void anUnknownToolIsAnOutcomeTheModelCanCorrect() {
        McpToolRouter router = new McpToolRouter(hostWith(List.of(tool("weather", "forecast"))));

        ToolOutcome outcome = router.invoke(call("nope__missing", "{}")).join();

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("no tool called"));
        assertTrue(outcome.text().contains("weather__forecast"),
                "the model needs the real names to correct itself: " + outcome.text());
    }

    @Test
    void malformedArgumentsAreHandedBackToTheModelThatWroteThem() {
        McpToolRouter router = new McpToolRouter(hostWith(List.of(tool("weather", "forecast"))));

        ToolOutcome outcome = router.invoke(call("weather__forecast", "{not json")).join();

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("valid JSON"), outcome.text());
    }

    @Test
    void aDisconnectedServerSaysSoRatherThanFailing() {
        // In the registry, but no live session: exactly what a server dying mid-question
        // looks like from here.
        McpToolRouter router = new McpToolRouter(hostWith(List.of(tool("weather", "forecast"))));

        ToolOutcome outcome = router.invoke(call("weather__forecast", "{}")).join();

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("not connected"), outcome.text());
    }

    @Test
    void aServerThrowingBecomesAReadableOutcome() {
        RecordingSession session = new RecordingSession("weather", null);
        session.failure = new McpException("weather", "the upstream API is down");
        McpToolRouter router =
                new McpToolRouter(hostWith(List.of(tool("weather", "forecast")), session));

        ToolOutcome outcome = router.invoke(call("weather__forecast", "{}")).join();

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("the upstream API is down"), outcome.text());
        assertFalse(outcome.text().contains("Exception"),
                "the model must get a reason, not a class name: " + outcome.text());
    }

    @Test
    void aToolReportingItsOwnErrorKeepsThatDistinction() {
        RecordingSession session =
                new RecordingSession("weather", McpToolResult.error("no such city"));
        McpToolRouter router =
                new McpToolRouter(hostWith(List.of(tool("weather", "forecast")), session));

        ToolOutcome outcome = router.invoke(call("weather__forecast", "{}")).join();

        assertTrue(outcome.isError());
        assertEquals("no such city", outcome.text(),
                "a server's own error text is passed through, not wrapped");
    }

    @Test
    void aBuiltInToolIsOfferedFirstAndCannotBeShadowed() {
        McpToolRouter router = new McpToolRouter(hostWith(List.of(tool("weather", "forecast"))))
                .withBuiltIn(new StubBuiltIn("mcp_clone_repository"));

        assertEquals("mcp_clone_repository", router.definitions().getFirst().name());

        ToolOutcome outcome = router.invoke(call("mcp_clone_repository", "{}")).join();
        assertEquals("built-in ran", outcome.text());
    }

    @Test
    void aBuiltInThatThrowsIsStillAnOutcome() {
        StubBuiltIn built = new StubBuiltIn("mcp_clone_repository");
        built.failure = new IllegalStateException("git is not installed");
        McpToolRouter router = new McpToolRouter(hostWith(List.of())).withBuiltIn(built);

        ToolOutcome outcome = router.invoke(call("mcp_clone_repository", "{}")).join();

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("git is not installed"), outcome.text());
    }

    @Test
    void aModWithNoServersOffersNothingAndRefusesReadably() {
        McpToolRouter router = new McpToolRouter(McpHost.disabled());

        assertTrue(router.definitions().isEmpty());
        ToolOutcome outcome = router.invoke(call("anything", "{}")).join();
        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("none"), outcome.text());
    }

    // ---- doubles ----

    private static McpHost hostWith(List<McpToolDescriptor> tools, RecordingSession... sessions) {
        McpContextRegistry registry = McpContextRegistry.merge(
                tools, List.of(), List.of(), List.of(), null);
        return new McpHost() {
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
                for (RecordingSession session : sessions) {
                    if (session.serverId().equals(serverId)) {
                        return Optional.of(session);
                    }
                }
                return Optional.empty();
            }

            @Override
            public void onRegistryChanged(Consumer<McpContextRegistry> listener) {
            }

            @Override
            public List<McpServerSpec> configuredServers() {
                return List.of();
            }

            @Override
            public void close() {
            }
        };
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
        public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
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

    private static final class StubBuiltIn implements McpToolRouter.BuiltInTool {

        private final String name;
        private RuntimeException failure;

        StubBuiltIn(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "A built-in tool", "");
        }

        @Override
        public CompletableFuture<ToolOutcome> invoke(String callId, com.google.gson.JsonObject a) {
            return failure != null
                    ? CompletableFuture.failedFuture(failure)
                    : CompletableFuture.completedFuture(ToolOutcome.ok(callId, "built-in ran"));
        }
    }
}
