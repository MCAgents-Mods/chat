package io.github.mcagents.clientfabric.api.mcp;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One tool an MCP server offers, as the LLM will see it.
 *
 * <p>{@link #qualifiedName()} is the part that matters. Two servers may both export a tool
 * called {@code search}, and the model is given one flat list, so the name it calls has to
 * say which server it meant. Qualifying at the registry boundary — rather than hoping names
 * do not collide — is what lets any set of servers be loaded together.
 *
 * @param serverId    the server that offers it
 * @param name        the tool's own name, as that server calls it
 * @param description what it does, straight from the server
 * @param inputSchema the JSON Schema for its arguments, verbatim
 */
public record McpToolDescriptor(
        String serverId, String name, String description, String inputSchema) {

    /**
     * What a provider accepts as a tool name.
     *
     * <p>OpenAI-compatible APIs, OpenRouter among them, reject a function name outside
     * {@code [A-Za-z0-9_-]{1,64}}. A qualified name that breaks the pattern is not a
     * cosmetic problem: the whole request is rejected, including the tools that were fine.
     */
    private static final Pattern PROVIDER_SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Separates the server from the tool in a qualified name. */
    public static final String SEPARATOR = "__";

    public McpToolDescriptor {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null || inputSchema.isBlank()
                ? "{\"type\":\"object\",\"properties\":{}}"
                : inputSchema;
        if (name.isBlank()) {
            throw new IllegalArgumentException("A tool needs a name");
        }
    }

    /**
     * The name the model is shown and calls: {@code {serverId}__{name}}, sanitized.
     *
     * <p>Truncated to 64 characters because that is the provider's limit. Truncation can
     * in principle collide; {@link McpContextRegistry} is where that is detected, because
     * it is the only place that can see every tool at once.
     */
    public String qualifiedName() {
        String qualified = sanitize(serverId) + SEPARATOR + sanitize(name);
        return qualified.length() <= 64 ? qualified : qualified.substring(0, 64);
    }

    /** Whether {@code candidate} could be a qualified name at all. */
    public static boolean isProviderSafe(String candidate) {
        return candidate != null && PROVIDER_SAFE.matcher(candidate).matches();
    }

    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return out.toString();
    }
}
