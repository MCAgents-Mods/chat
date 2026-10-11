package io.github.mcagentsmods.chat.api.mcp;

import java.util.List;
import java.util.Objects;

/**
 * What a tool call produced.
 *
 * <p>{@code isError} is not the same as a failed future. A future fails when the server
 * could not be reached or broke the protocol; {@code isError} is the server saying the call
 * itself went wrong — a missing file, a rejected argument. The distinction matters because
 * the second is something the model can act on and should be told about, while the first is
 * an operational problem that is not the model's to solve.
 *
 * @param text    the result, flattened to the text the model will read
 * @param isError whether the server reported the call as failed
 */
public record McpToolResult(String text, boolean isError) {

    public McpToolResult {
        Objects.requireNonNull(text, "text");
    }

    public static McpToolResult ok(String text) {
        return new McpToolResult(text, false);
    }

    public static McpToolResult error(String text) {
        return new McpToolResult(text, true);
    }

    /** Joins content blocks the way a model reads them: in order, one per line. */
    public static McpToolResult of(List<String> blocks, boolean isError) {
        return new McpToolResult(String.join("\n", blocks), isError);
    }
}
