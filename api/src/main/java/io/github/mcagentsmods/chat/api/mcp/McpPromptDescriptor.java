package io.github.mcagentsmods.chat.api.mcp;

import java.util.Objects;

/**
 * One prompt an MCP server offers.
 *
 * <p>A prompt is a procedure the server wants followed — the shared instruction set's
 * {@code agents-setup} is one. Like a tool it is named rather than addressed, so it is
 * qualified the same way and for the same reason.
 *
 * @param serverId    the server that offers it
 * @param name        the prompt's own name
 * @param description what invoking it does
 */
public record McpPromptDescriptor(String serverId, String name, String description) {

    public McpPromptDescriptor {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        if (name.isBlank()) {
            throw new IllegalArgumentException("A prompt needs a name");
        }
    }

    /** {@code {serverId}__{name}}, matching {@link McpToolDescriptor#qualifiedName()}. */
    public String qualifiedName() {
        return new McpToolDescriptor(serverId, name, description, "").qualifiedName();
    }
}
