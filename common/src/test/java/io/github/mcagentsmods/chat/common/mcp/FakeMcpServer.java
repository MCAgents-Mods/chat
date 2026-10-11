package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Approves every server on disk under {@code mcpRoot}, as a player would by writing each
     * fingerprint into the catalog. Replaces any catalog already there.
     */
    static void approveEverything(Path mcpRoot) throws IOException {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (McpServerSpec spec : new McpDiscovery(mcpRoot).discover(new ArrayList<>())) {
            fingerprints.put(spec.id(), McpApproval.fingerprint(spec));
        }
        approve(mcpRoot, fingerprints);
    }

    /** Writes a catalog approving exactly {@code fingerprints}, by server id. */
    static void approve(Path mcpRoot, Map<String, String> fingerprints) throws IOException {
        StringBuilder json = new StringBuilder("{\"mcpServers\":{");
        fingerprints.forEach((id, fingerprint) -> json
                .append(json.charAt(json.length() - 1) == '{' ? "" : ",")
                .append(jsonString(id)).append(":{\"").append(McpApproval.FIELD).append("\":")
                .append(jsonString(fingerprint)).append('}'));
        Files.writeString(mcpRoot.resolve(MCAgents.MCP_SERVERS_FILE), json.append("}}").toString());
    }

    /** The spec discovery derives from what {@link #writeInto} writes at {@code owner/repository}. */
    static McpServerSpec discoveredSpec(String owner, String repository) {
        return McpServerSpec.stdio(repository, javaCommand(), List.of("FakeServer.java"),
                owner + "/" + repository);
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
