package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.McpException;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpToolResult;
import io.github.mcagentsmods.core.api.http.HttpCall;
import io.github.mcagentsmods.core.api.http.HttpReply;
import io.github.mcagentsmods.core.api.http.HttpTransport;
import io.github.mcagentsmods.core.common.testing.StubTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP transport, driven against a scripted transport rather than a server.
 *
 * <p>Reusing the model client's {@link io.github.mcagentsmods.core.api.http.HttpTransport}
 * seam is what makes this possible: every byte of the HTTP path is exercised with no
 * network, no port, and nothing to host.
 */
class HttpMcpSessionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final String INITIALIZED =
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":\"2025-06-18\","
                    + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}";

    private static McpServerSpec spec() {
        return McpServerSpec.http("hosted", "https://example.test/mcp");
    }

    private static StubTransport connected() {
        StubTransport transport = new StubTransport();
        transport.reply(200, INITIALIZED);
        transport.reply(202, ""); // the initialized notification
        return transport;
    }

    @Test
    void completesTheHandshakeBeforeAnythingElse() {
        StubTransport transport = connected();
        HttpMcpSession session = HttpMcpSession.connect(spec(), transport, TIMEOUT);

        assertTrue(session.isAlive());
        HttpCall first = transport.calls().getFirst();
        assertEquals("https://example.test/mcp", first.url());
        assertTrue(first.body().contains("\"method\":\"initialize\""));
        assertTrue(first.headers().get("Accept").contains("text/event-stream"),
                "both response shapes must be offered, or half the servers refuse");
    }

    @Test
    void readsToolsFromAPlainJsonReply() {
        StubTransport transport = connected();
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                + "{\"name\":\"forecast\",\"description\":\"Weather\","
                + "\"inputSchema\":{\"type\":\"object\"}}]}}");

        var tools = HttpMcpSession.connect(spec(), transport, TIMEOUT).listTools().join();

        assertEquals(1, tools.size());
        assertEquals("forecast", tools.getFirst().name());
        assertEquals("Weather", tools.getFirst().description());
        assertEquals("hosted__forecast", tools.getFirst().qualifiedName());
    }

    @Test
    void aToolWithoutASchemaStillGetsAValidOne() {
        StubTransport transport = connected();
        transport.reply(200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"bare\"}]}}");

        var tool = HttpMcpSession.connect(spec(), transport, TIMEOUT).listTools().join().getFirst();

        assertTrue(tool.inputSchema().contains("object"),
                "a provider rejects a function whose parameters are not a schema");
    }

    @Test
    void readsToolsFromAnEventStreamReply() {
        StubTransport transport = connected();
        transport.reply(200, """
                event: message
                data: {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"forecast"}]}}

                """);

        HttpMcpSession session = HttpMcpSession.connect(spec(), transport, TIMEOUT);

        assertEquals("forecast", session.listTools().join().getFirst().name());
    }

    @Test
    void takesTheLastDataFrameWhenProgressIsStreamedFirst() {
        String body = """
                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

                data: {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"late"}]}}

                """;
        assertTrue(HttpMcpSession.payloadOf(body).contains("\"late\""));
    }

    @Test
    void callsAToolAndFlattensItsContent() {
        StubTransport transport = connected();
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"line one\"},"
                + "{\"type\":\"text\",\"text\":\"line two\"}]}}");

        HttpMcpSession session = HttpMcpSession.connect(spec(), transport, TIMEOUT);
        McpToolResult result = session.callTool("forecast", Map.of("city", "Oslo")).join();

        assertEquals("line one\nline two", result.text());
        assertFalse(result.isError());
        assertTrue(transport.lastCall().body().contains("\"city\":\"Oslo\""));
    }

    @Test
    void aToolErrorIsAResultAndNotAFailure() {
        StubTransport transport = connected();
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,"
                + "\"content\":[{\"type\":\"text\",\"text\":\"no such city\"}]}}");

        McpToolResult result = HttpMcpSession.connect(spec(), transport, TIMEOUT)
                .callTool("forecast", Map.of()).join();

        assertTrue(result.isError(), "the model must be told, not have the call fail under it");
        assertEquals("no such city", result.text());
    }

    @Test
    void nonTextContentIsNamedRatherThanInlined() {
        StubTransport transport = connected();
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                + "{\"type\":\"image\",\"data\":\"AAAA\"}]}}");

        McpToolResult result = HttpMcpSession.connect(spec(), transport, TIMEOUT)
                .callTool("shot", Map.of()).join();

        assertEquals("[image content, not shown]", result.text());
    }

    @Test
    void aJsonRpcErrorBecomesAnMcpException() {
        StubTransport transport = connected();
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                + "{\"code\":-32601,\"message\":\"Method not found\"}}");

        HttpMcpSession session = HttpMcpSession.connect(spec(), transport, TIMEOUT);
        CompletionException thrown =
                assertThrows(CompletionException.class, () -> session.listTools().join());

        assertTrue(thrown.getCause() instanceof McpException);
        assertTrue(thrown.getCause().getMessage().contains("Method not found"));
    }

    @Test
    void aUrlMissingItsMcpPathSaysSo() {
        StubTransport transport = new StubTransport();
        transport.reply(404, "Cannot POST /");

        McpException thrown = assertThrows(McpException.class,
                () -> HttpMcpSession.connect(McpServerSpec.http("hosted", "https://example.test/"), transport, TIMEOUT));

        assertTrue(thrown.getMessage().contains("/mcp"),
                "the message must name the likeliest cause: " + thrown.getMessage());
    }

    @Test
    void anAuthFailureIsNotReportedAsAWrongUrl() {
        StubTransport transport = new StubTransport();
        transport.reply(401, "unauthorized");

        McpException thrown = assertThrows(McpException.class,
                () -> HttpMcpSession.connect(spec(), transport, TIMEOUT));

        assertTrue(thrown.getMessage().contains("credentials"));
    }

    @Test
    void resourcesAndPromptsComeBackTheSameWay() {
        StubTransport transport = connected();
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"resources\":["
                + "{\"uri\":\"agents://manifest.json\",\"name\":\"manifest\"}]}}");
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"prompts\":["
                + "{\"name\":\"agents-setup\",\"description\":\"Set up\"}]}}");

        HttpMcpSession session = HttpMcpSession.connect(spec(), transport, TIMEOUT);

        assertEquals("agents://manifest.json", session.listResources().join().getFirst().uri());
        assertEquals("agents-setup", session.listPrompts().join().getFirst().name());
    }

    @Test
    void closingDoesNotCloseTheSharedTransport() {
        StubTransport transport = connected();
        HttpMcpSession session = HttpMcpSession.connect(spec(), transport, TIMEOUT);

        session.close();

        assertFalse(session.isAlive());
        assertFalse(transport.isClosed(),
                "the transport is shared with the model client and other servers");
    }

    @Test
    void listReadsAreNotSentAfterClose() {
        StubTransport transport = connected();
        HttpMcpSession session = HttpMcpSession.connect(spec(), transport, TIMEOUT);
        int before = transport.callCount();

        session.close();
        assertThrows(CompletionException.class, () -> session.listTools().join());

        assertEquals(before, transport.callCount(), "a closed session must not reach the network");
    }

    @Test
    void readsAResourceAsText() {
        StubTransport transport = connected();
        transport.reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"contents\":["
                + "{\"uri\":\"agents://a.md\",\"text\":\"body\"}]}}");

        assertEquals("body", HttpMcpSession.connect(spec(), transport, TIMEOUT)
                .readResource("agents://a.md").join());
    }

    @Test
    void unknownTransportNamesAreRejectedClearly() {
        assertThrows(IllegalArgumentException.class,
                () -> io.github.mcagentsmods.chat.api.mcp.McpTransport.fromConfig("carrier-pigeon"));
        assertEquals(List.of("stdio", "http"),
                List.of(io.github.mcagentsmods.chat.api.mcp.McpTransport.STDIO.configName(),
                        io.github.mcagentsmods.chat.api.mcp.McpTransport.HTTP.configName()));
    }

    @Test
    void anEndpointThatNeverAnswersFailsTheHandshakeOnTheRequestTimeout() {
        HttpTransport silent = new HttpTransport() {
            @Override
            public CompletableFuture<HttpReply> send(HttpCall call) {
                return new CompletableFuture<>();
            }

            @Override
            public void close() {
            }
        };

        assertThrows(McpException.class,
                () -> HttpMcpSession.connect(spec(), silent, Duration.ofMillis(50)));
    }
}
