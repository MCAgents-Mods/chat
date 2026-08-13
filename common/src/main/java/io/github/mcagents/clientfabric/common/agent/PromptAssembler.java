package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a persona and a stored conversation into the message list sent to the model.
 *
 * <h2>Order is a design decision, not a formatting one</h2>
 *
 * <p>Providers cache prompts by matching a <strong>prefix</strong>. Everything up to the
 * first difference can be reused; everything after it is re-billed. So the order here is
 * chosen to keep the stable material at the front:
 *
 * <ol>
 *   <li>the persona from {@code {minecraft_dir}/MCAgents/AGENTS.md} — unchanged for as
 *       long as the player does not edit it;</li>
 *   <li>the conversation so far — grows by appending, so every earlier turn stays
 *       byte-identical;</li>
 *   <li>the new question, last.</li>
 * </ol>
 *
 * <p>The consequence worth stating out loud: <strong>retrieved Knowledge Pack content must
 * not go in the persona message.</strong> It changes with every question, and putting it
 * first would invalidate the whole conversation behind it on every turn — turning the
 * cheapest possible request into the most expensive one. It belongs beside the question, at
 * the end. See {@code .agents/api/openrouter.md}.
 */
public final class PromptAssembler {

    private PromptAssembler() {
        throw new AssertionError("No instances.");
    }

    /**
     * Builds the message list.
     *
     * @param persona  the player's instructions, or blank if they have written none
     * @param messages the stored conversation, oldest first, already including the
     *                 player's newest message
     */
    public static List<PromptMessage> assemble(String persona, List<ChatMessage> messages) {
        List<PromptMessage> prompt = new ArrayList<>(messages.size() + 1);

        String instructions = persona == null || persona.isBlank()
                ? defaultPersona()
                : persona.trim();
        prompt.add(PromptMessage.system(instructions));

        for (ChatMessage message : messages) {
            // A stored system message is context that was deliberately persisted; a stored
            // user or assistant message is a turn. All three replay as they were.
            prompt.add(new PromptMessage(message.role(), message.content()));
        }

        if (prompt.size() == 1) {
            throw new IllegalArgumentException("A prompt needs at least one stored message");
        }
        return List.copyOf(prompt);
    }

    /**
     * What the agent is told when the player has written no instructions of their own.
     *
     * <p>Deliberately short and constant: it is the first thing in every request, so its
     * bytes are the start of every cached prefix.
     */
    static String defaultPersona() {
        return """
                You are an assistant inside Minecraft, answering a player through a chat \
                screen in the game. Be concise and practical. If you are unsure about a \
                detail of a modpack or a server, say so rather than guessing. \
                The player can give you their own instructions by editing AGENTS.md in \
                their MCAgents folder.""";
    }

    /** Whether {@code role} is one the provider will accept in a conversation. */
    static boolean isSendable(ChatRole role) {
        return role == ChatRole.USER || role == ChatRole.ASSISTANT || role == ChatRole.SYSTEM;
    }
}
