package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpTransport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The player's consent to run what a cloned repository says to run.
 *
 * <p>A repository's {@code mcp.json} names a command, and the clone tool that brings the
 * repository in is one a model can call on its own — on the say-so of a Knowledge Pack, or
 * of another tool's output. Starting that command unasked would be running code the player
 * never saw. So a server whose wiring came from a repository starts only once the player has
 * written its fingerprint into {@code mcp_servers.json}:
 *
 * <pre>{"mcpServers": {"echo-mcp": {"approved": "3f9a1c0b7d2e5a46"}}}</pre>
 *
 * <p>The fingerprint covers everything that decides what runs — the command, its arguments,
 * its directory and environment, or the URL — so an approval is for one wiring. A later pull
 * that changes the command changes the fingerprint, and the server waits again.
 *
 * <p>A catalog entry that carries its own command or URL needs no approval: the player wrote
 * that wiring themselves.
 */
final class McpApproval {

    /** The catalog field an approval is written in. */
    static final String FIELD = "approved";

    /** Sixty-four bits: enough that no repository can be made to match another's approval. */
    private static final int FINGERPRINT_BYTES = 8;

    private McpApproval() {
        throw new AssertionError("No instances.");
    }

    /** The fingerprint of what {@code spec} would run; its id and enabled flag are not part of it. */
    static String fingerprint(McpServerSpec spec) {
        StringBuilder wiring = new StringBuilder()
                .append(spec.transport()).append('\0')
                .append(spec.command()).append('\0')
                .append(String.join("\u0001", spec.arguments())).append('\0')
                .append(spec.url()).append('\0')
                .append(spec.workingDirectory()).append('\0');
        new TreeMap<>(spec.environment()).forEach((key, value) ->
                wiring.append(key).append('=').append(value).append('\u0001'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(wiring.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, FINGERPRINT_BYTES);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * What the player is told about a server waiting for them: what it would do, and the
     * exact line that allows it.
     *
     * <p>Environment values are left out — a manifest's environment is where a token would be
     * — but their names are shown, because a variable the player does not expect is worth
     * seeing.
     */
    static String describe(McpServerSpec spec, String fingerprint) {
        String what = spec.transport() == McpTransport.HTTP
                ? "It would send tool calls to " + spec.url()
                : "It would run: " + String.join(" ", commandLine(spec))
                        + (spec.workingDirectory().isBlank() ? "" : " (in " + spec.workingDirectory() + ")");
        Map<String, String> environment = new TreeMap<>(spec.environment());
        String variables = environment.isEmpty()
                ? ""
                : ", with " + String.join(", ", environment.keySet()) + " set";
        return "Waiting for your approval. " + what + variables + ". To allow it, add \""
                + FIELD + "\": \"" + fingerprint + "\" to \"" + spec.id() + "\" in "
                + MCAgents.MCP_SERVERS_FILE + ".";
    }

    private static java.util.List<String> commandLine(McpServerSpec spec) {
        java.util.List<String> line = new java.util.ArrayList<>();
        line.add(spec.command());
        line.addAll(spec.arguments());
        return line;
    }
}
