package io.github.mcagentsmods.chat.api.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything needed to reach one MCP server.
 *
 * <p>A spec is produced two ways, and both end up here: read from
 * {@code mcp_servers.json}, or derived from a repository the mod cloned. That is the point
 * of the type — the loader does not care which, so a cloned server and a hand-configured
 * one are started by the same code.
 *
 * @param id        the name this server is known by; unique within a registry, and the
 *                  prefix its tools are exposed under
 * @param transport how to reach it
 * @param command   the executable, for {@link McpTransport#STDIO}; empty for HTTP
 * @param arguments arguments to the executable, for STDIO; empty for HTTP
 * @param url       the endpoint, for {@link McpTransport#HTTP}; empty for STDIO
 * @param environment environment variables for the child process; never logged
 * @param workingDirectory directory to start the child in, relative to the mcps root, or
 *                  empty to use the mcps root itself
 * @param enabled   whether the loader should connect to it at all
 */
public record McpServerSpec(
        String id,
        McpTransport transport,
        String command,
        List<String> arguments,
        String url,
        Map<String, String> environment,
        String workingDirectory,
        boolean enabled) {

    public McpServerSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (id.isBlank()) {
            throw new IllegalArgumentException("An MCP server needs an id");
        }
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
        if (transport == McpTransport.STDIO && command.isBlank()) {
            throw new IllegalArgumentException(
                    "stdio server '" + id + "' needs a command to run");
        }
        if (transport == McpTransport.HTTP && url.isBlank()) {
            throw new IllegalArgumentException("http server '" + id + "' needs a url");
        }
    }

    /** A stdio server: a program the mod runs, in a directory under the mcps root. */
    public static McpServerSpec stdio(
            String id, String command, List<String> arguments, String workingDirectory) {
        return new McpServerSpec(
                id, McpTransport.STDIO, command, arguments, "", Map.of(), workingDirectory, true);
    }

    /** An HTTP server someone else hosts. The URL must include the MCP path. */
    public static McpServerSpec http(String id, String url) {
        return new McpServerSpec(
                id, McpTransport.HTTP, "", List.of(), url, Map.of(), "", true);
    }

    /** This spec, enabled or disabled. */
    public McpServerSpec withEnabled(boolean value) {
        return new McpServerSpec(
                id, transport, command, arguments, url, environment, workingDirectory, value);
    }

    /**
     * Whether {@code other} would connect to the same thing in the same way.
     *
     * <p>The loader uses this to decide what a configuration change actually changed. A
     * server whose spec is unchanged keeps its live session across a reload; restarting it
     * would drop a working connection for nothing.
     */
    public boolean sameWiring(McpServerSpec other) {
        return other != null
                && transport == other.transport
                && command.equals(other.command)
                && arguments.equals(other.arguments)
                && url.equals(other.url)
                && environment.equals(other.environment)
                && workingDirectory.equals(other.workingDirectory);
    }

    /**
     * A description safe to log or show.
     *
     * <p>{@link #environment()} is deliberately absent: an MCP server's environment is
     * where its credentials are, and this record is printed in status screens.
     */
    @Override
    public String toString() {
        String target = transport == McpTransport.HTTP ? url : command + " " + String.join(" ", arguments);
        return "McpServerSpec[" + id + ", " + transport.configName() + ", " + target.trim()
                + (enabled ? "" : ", disabled") + "]";
    }
}
