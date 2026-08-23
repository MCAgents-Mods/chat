package io.github.mcagents.clientfabric.api.mcp;

import java.util.Objects;

/**
 * One resource an MCP server offers.
 *
 * <p>Unlike a tool, a resource is addressed by a URI the server chose, so there is nothing
 * to qualify — {@code agents://manifest.json} is already unique to the server that serves
 * it. {@link #serverId()} is carried anyway, because a read has to be routed back to the
 * session that can answer it.
 *
 * @param serverId    the server that offers it
 * @param uri         the resource URI, as the server published it
 * @param name        a short name for it
 * @param description what it holds
 * @param mimeType    the media type, or empty when the server did not say
 */
public record McpResourceDescriptor(
        String serverId, String uri, String name, String description, String mimeType) {

    public McpResourceDescriptor {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(uri, "uri");
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        mimeType = mimeType == null ? "" : mimeType;
        if (uri.isBlank()) {
            throw new IllegalArgumentException("A resource needs a uri");
        }
    }
}
