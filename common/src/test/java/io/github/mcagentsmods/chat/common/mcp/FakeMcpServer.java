package io.github.mcagentsmods.chat.common.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands up a real, runnable MCP server in a directory, for tests that need a live one.
 *
 * <p>The server is a single Java source file run through the JVM's own source launcher, so
 * these tests depend on nothing the build does not already require. Writing it in
 * JavaScript and running {@code node} would have made the suite fail on a machine without
 * node, for a reason unrelated to the code under test.
 *
 * <p>The source lives in {@code src/test/resources/mcp/FakeServer.java.txt} rather than in
 * a string here. Java inside a Java string literal needs every quote escaped twice, and an
 * escaping mistake there fails as a mysterious protocol error rather than as a compile
 * error.
 */
final class FakeMcpServer {

    private FakeMcpServer() {
        throw new AssertionError("No instances.");
    }

    /** The {@code java} running this test, which is by definition present. */
    static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Writes the server and the {@code mcp.json} that starts it into {@code repository},
     * so discovery finds it exactly as it would find a cloned one.
     *
     * @param toolName the single tool this server will offer
     */
    static void writeInto(Path repository, String toolName) throws IOException {
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("FakeServer.java"), source(toolName));
        Files.writeString(repository.resolve("mcp.json"), manifest());
    }

    /** The manifest a discovered repository would carry. */
    static String manifest() {
        return "{\"command\":" + jsonString(javaCommand()) + ",\"args\":[\"FakeServer.java\"]}";
    }

    private static String source(String toolName) throws IOException {
        try (InputStream stream =
                     FakeMcpServer.class.getResourceAsStream("/mcp/FakeServer.java.txt")) {
            if (stream == null) {
                throw new IOException("FakeServer.java.txt is missing from the test resources");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__TOOL_NAME__", toolName);
        }
    }

    /** Quotes a path for JSON — Windows separators would otherwise be escape sequences. */
    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
