package io.github.mcagentsmods.chat.api.agent;

import java.util.Objects;

/**
 * The model asking for a tool to be run.
 *
 * <p>{@link #arguments()} is kept as the JSON text the provider sent rather than as a parsed
 * map. Parsing belongs to whoever will execute the call, and a model that emits malformed
 * JSON — which happens — must produce a tool result the model can read and correct, not a
 * parse failure three layers up that ends the player's question.
 *
 * @param id        the provider's id for this call; it is what the result is matched to
 * @param name      the tool the model wants, as it was offered
 * @param arguments the arguments as a JSON object, verbatim
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        if (name.isBlank()) {
            throw new IllegalArgumentException("A tool call needs a name");
        }
    }
}
