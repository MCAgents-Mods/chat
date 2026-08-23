package io.github.mcagents.clientfabric.api.mcp;

/**
 * How the mod reaches an MCP server.
 *
 * <p>Two are supported because the ecosystem uses two. A server cloned from a repository
 * is almost always {@link #STDIO} — it is a program on disk, and the mod runs it. A server
 * someone else already hosts is {@link #HTTP}.
 */
public enum McpTransport {

    /**
     * The mod spawns the server as a child process and speaks newline-delimited JSON-RPC
     * over its standard input and output.
     *
     * <p>The child's standard error is log output, never protocol. A server that writes a
     * stray line to standard output corrupts the stream, which is why the reader discards
     * anything that is not a JSON object rather than failing the session.
     */
    STDIO,

    /**
     * The mod posts JSON-RPC to a URL and reads the reply, which may arrive as plain JSON
     * or as {@code text/event-stream} frames.
     *
     * <p>The endpoint must include the server's MCP path — typically {@code /mcp}. A URL
     * without it lands on a route that speaks no MCP, and servers commonly report that as
     * an authentication failure rather than a wrong address.
     */
    HTTP;

    /** The wire name used in {@code mcp_servers.json}. */
    public String configName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parses a {@code transport} value from configuration.
     *
     * @throws IllegalArgumentException if {@code value} names no transport
     */
    public static McpTransport fromConfig(String value) {
        for (McpTransport transport : values()) {
            if (transport.configName().equalsIgnoreCase(value)) {
                return transport;
            }
        }
        throw new IllegalArgumentException(
                "Unknown MCP transport '" + value + "' — expected stdio or http");
    }
}
