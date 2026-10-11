package io.github.mcagentsmods.chat.api.agent;

import io.github.mcagentsmods.core.api.model.ToolCall;
import java.util.Objects;

/**
 * What running a tool produced, on its way back to the model.
 *
 * <p><b>Every outcome is a normal message, including the failures.</b> A tool that was not
 * found, whose arguments would not parse, or whose server has gone away all come back as
 * text the model reads and can act on — asking again with different arguments, or telling
 * the player what it could not do. Failing the request instead would turn a recoverable
 * mistake into a dead question.
 *
 * @param callId  the {@link ToolCall#id()} this answers; the provider matches on it
 * @param text    what to show the model
 * @param isError whether this was a failure, so the model is not left to infer it
 */
public record ToolOutcome(String callId, String text, boolean isError) {

    public ToolOutcome {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(text, "text");
    }

    public static ToolOutcome ok(String callId, String text) {
        return new ToolOutcome(callId, text, false);
    }

    /**
     * A failure the model should try to recover from.
     *
     * <p>Prefixed, because a model handed a bare sentence frequently repeats it to the
     * player as though it were the answer.
     */
    public static ToolOutcome error(String callId, String reason) {
        return new ToolOutcome(callId, "The tool call failed: " + reason, true);
    }
}
