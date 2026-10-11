package io.github.mcagentsmods.chat.api.agent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One tool offered to the model, in the shape a provider takes.
 *
 * <p>Distinct from {@code McpToolDescriptor}, which is what a server published. This is the
 * flattened, already-qualified form that goes on the wire: the model never sees which
 * server a tool came from, only a name it can call.
 *
 * @param name        the name the model calls, unique across every tool offered at once
 * @param description what it does, shown to the model and nothing else
 * @param parametersSchema the JSON Schema for its arguments
 */
public record ToolDefinition(String name, String description, String parametersSchema) {

    /**
     * What an OpenAI-compatible provider accepts as a function name.
     *
     * <p>Enforced here rather than trusted, because the provider rejects the <em>whole
     * request</em> over one bad name — a single malformed tool would take every other tool
     * down with it, and the failure names none of them.
     */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        parametersSchema = parametersSchema == null || parametersSchema.isBlank()
                ? "{\"type\":\"object\",\"properties\":{}}"
                : parametersSchema;
        if (!ACCEPTED.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Tool name '" + name + "' is not one a provider will accept — it must be "
                            + "1 to 64 characters of letters, digits, dash or underscore");
        }
    }

    /** Whether {@code name} could be offered as a tool at all. */
    public static boolean isAcceptable(String name) {
        return name != null && ACCEPTED.matcher(name).matches();
    }
}
