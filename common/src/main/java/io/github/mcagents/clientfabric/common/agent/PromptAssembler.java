package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.PlayerEnvironment;
import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;

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
 *   <li>where the player is playing — unchanged for as long as they stay there;</li>
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
     * Builds the message list, with no retrieved content and no environment.
     *
     * @param persona  the player's instructions, or blank if they have written none
     * @param messages the stored conversation, oldest first, already including the
     *                 player's newest message
     */
    public static List<PromptMessage> assemble(String persona, List<ChatMessage> messages) {
        return assemble(persona, PlayerEnvironment.unknown(), messages, List.of());
    }

    /**
     * Builds the message list, with no environment.
     *
     * @param persona   the player's instructions, or blank if they have written none
     * @param messages  the stored conversation, oldest first
     * @param knowledge the packs retrieval chose, best first
     */
    public static List<PromptMessage> assemble(String persona, List<ChatMessage> messages,
                                               List<KnowledgeContent> knowledge) {
        return assemble(persona, PlayerEnvironment.unknown(), messages, knowledge);
    }

    /**
     * Builds the message list.
     *
     * <p>Retrieved packs go in as one system message immediately before the newest question
     * — after the last cache breakpoint, so they never invalidate the settled prefix, and
     * before the question, so the thing the model is being asked stays last. See the class
     * note: putting them in the persona would re-bill the whole conversation every turn.
     *
     * <p>The environment goes in its own system message directly after the persona, and
     * <strong>not inside it</strong>. Two reasons, and the second is the one that matters:
     * the persona is the player's own file and appending to it would put words in it they
     * did not write; and a player who walks from one server to another would otherwise
     * change the first bytes of the request, throwing away a cached prefix that is
     * identical apart from one line. Separated, the persona keeps its breakpoint and only
     * the short note behind it changes.
     *
     * @param persona     the player's instructions, or blank if they have written none
     * @param environment where the player is playing; {@link PlayerEnvironment.Kind#UNKNOWN}
     *                    adds nothing to the prompt at all
     * @param messages    the stored conversation, oldest first, already including the
     *                    player's newest message
     * @param knowledge   the packs retrieval chose, best first; empty when retrieval is off,
     *                    found nothing, or could not run
     */
    public static List<PromptMessage> assemble(String persona, PlayerEnvironment environment,
                                               List<ChatMessage> messages,
                                               List<KnowledgeContent> knowledge) {
        List<PromptMessage> prompt = new ArrayList<>(messages.size() + 3);

        String instructions = persona == null || persona.isBlank()
                ? defaultPersona()
                : persona.trim();
        // Breakpoint one: the persona is byte-identical until the player edits their file.
        prompt.add(PromptMessage.system(instructions).asCacheBreakpoint());

        // No breakpoint of its own. It sits between the persona's breakpoint and the one on
        // the newest completed answer, which already covers it in every request after the
        // first — and a third breakpoint buys one short line of reuse for a write.
        if (environment != null && environment.isKnown()) {
            prompt.add(PromptMessage.system(whereThePlayerIs(environment)));
        }

        int lastSettledTurn = lastSettledTurnIndex(messages);
        int questionIndex = knowledge.isEmpty() ? -1 : newestQuestionIndex(messages);

        for (int i = 0; i < messages.size(); i++) {
            if (i == questionIndex) {
                prompt.add(PromptMessage.system(reference(knowledge)));
            }
            ChatMessage message = messages.get(i);
            // A stored system message is context that was deliberately persisted; a stored
            // user or assistant message is a turn. All three replay as they were.
            PromptMessage entry = new PromptMessage(message.role(), message.content());
            // Breakpoint two: everything up to the newest completed answer is settled and
            // will never change again. The new question sits after it, uncached.
            prompt.add(i == lastSettledTurn ? entry.asCacheBreakpoint() : entry);
        }

        if (!knowledge.isEmpty() && questionIndex == -1) {
            // No user message to sit in front of — unusual, but appending is still correct
            // and losing the content silently would not be.
            prompt.add(PromptMessage.system(reference(knowledge)));
        }

        // Counted from the conversation rather than from the prompt's size: the prompt now
        // starts with either one leading system message or two, and a size check would have
        // quietly started accepting an empty conversation the day the environment arrived.
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A prompt needs at least one stored message");
        }
        return List.copyOf(prompt);
    }

    /**
     * Where the player is, as one short system message.
     *
     * <p>The context itself is on its own line and unaltered — for single player it is
     * {@link PlayerEnvironment#SINGLE_PLAYER_CONTEXT} exactly, and for a server it is the
     * address and nothing else. The lines around it say what the value is and what to do
     * with it, because an address dropped into a prompt with no frame reads as noise.
     *
     * <p>It is told to prefer the player's own account of where they are. A client knows
     * the address it dialled; it does not know that the address is a proxy in front of
     * fifty minigame servers, and the player does.
     */
    private static String whereThePlayerIs(PlayerEnvironment environment) {
        return """
                Where the player is playing right now, as reported by their game client:

                %s

                Take this into account when it changes the answer — server rules, plugins, \
                and versions all do. If the player says something different about where \
                they are, believe the player.""".formatted(environment.context());
    }

    /**
     * The retrieved packs, as one system message.
     *
     * <p>Each is named and versioned. The model is told where this came from and told it may
     * be irrelevant, because retrieval by similarity has no notion of whether it was right —
     * an instruction to use the content regardless would turn a near miss into a confident
     * wrong answer.
     */
    private static String reference(List<KnowledgeContent> knowledge) {
        StringBuilder text = new StringBuilder(
                "Reference material from the player's Knowledge Packs, selected as possibly "
                        + "relevant to the question that follows. Use what helps and ignore "
                        + "what does not; it was matched by similarity and may be off-topic. "
                        + "Do not mention this material unless you draw on it.");

        for (KnowledgeContent pack : knowledge) {
            text.append("\n\n--- ").append(pack.title() == null || pack.title().isBlank()
                            ? pack.id()
                            : pack.title())
                    .append(" (").append(pack.id()).append(" v").append(pack.version())
                    .append(") ---\n")
                    .append(pack.rawContent() == null ? "" : pack.rawContent());
        }
        return text.toString();
    }

    /**
     * The index of the newest user message — the question being asked.
     *
     * <p>Returns {@code -1} if the conversation ends without one, which {@code respond} does
     * not produce but a stored conversation could.
     */
    private static int newestQuestionIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The index of the newest assistant message, which is where settled history ends.
     *
     * <p>Returns {@code -1} on the first turn of a conversation, when there is no answer
     * yet and so nothing beyond the persona is worth a breakpoint.
     */
    private static int lastSettledTurnIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.ASSISTANT) {
                return i;
            }
        }
        return -1;
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
