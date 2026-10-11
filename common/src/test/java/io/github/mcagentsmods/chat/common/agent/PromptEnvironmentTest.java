package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.agent.PlayerEnvironment;
import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.model.PromptMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling the model where the player is.
 *
 * <p>Two things are pinned here and neither is cosmetic. The single-player wording is
 * exact, because it is a value other people's instructions are written against — the
 * generated {@code AGENTS.md} quotes it, and a player's own file may too. And the note goes
 * <em>after</em> the persona rather than inside it: the persona is the player's file, and it
 * is also the cached prefix of every request.
 */
class PromptEnvironmentTest {

    private static final String PERSONA = "You are a Skyblock expert. Be terse.";

    private static List<ChatMessage> conversation() {
        return List.of(new ChatMessage(1, "session-1", 1, ChatRole.USER,
                "where am I", Instant.now()));
    }

    private static String environmentNote(List<PromptMessage> prompt) {
        // The persona is first; the environment, when there is one, is second.
        return prompt.get(1).text();
    }

    @Test
    void saysExactlyThatThePlayerIsInSinglePlayer() {
        assertEquals("Player is playing in single player.",
                PlayerEnvironment.singlePlayer().context());
        assertEquals("Player is playing in single player.",
                PlayerEnvironment.SINGLE_PLAYER_CONTEXT);
    }

    @Test
    void carriesTheServerAddressAndNothingElseForAServer() {
        assertEquals("play.example.net:25565",
                PlayerEnvironment.multiplayer("play.example.net:25565").context());
    }

    @Test
    void refusesAMultiplayerEnvironmentWithNoAddress() {
        // A blank address is not "a server the mod could not name" — it is a bug that would
        // put an empty line in front of every question. UNKNOWN is how not knowing is said.
        assertThrows(IllegalArgumentException.class, () -> PlayerEnvironment.multiplayer("  "));
    }

    @Test
    void putsTheSinglePlayerSentenceInThePromptVerbatim() {
        List<PromptMessage> prompt = PromptAssembler.assemble(
                PERSONA, PlayerEnvironment.singlePlayer(), conversation(), List.of());

        assertEquals(ChatRole.SYSTEM, prompt.get(1).role());
        assertTrue(environmentNote(prompt).contains("\n\nPlayer is playing in single player.\n\n"),
                "the sentence stands on its own line, unaltered");
    }

    @Test
    void putsTheServerAddressInThePrompt() {
        List<PromptMessage> prompt = PromptAssembler.assemble(
                PERSONA, PlayerEnvironment.multiplayer("mc.hypixel.net"), conversation(), List.of());

        assertTrue(environmentNote(prompt).contains("mc.hypixel.net"));
    }

    @Test
    void addsNothingAtAllWhenItDoesNotKnowWhereThePlayerIs() {
        List<PromptMessage> known = PromptAssembler.assemble(
                PERSONA, PlayerEnvironment.singlePlayer(), conversation(), List.of());
        List<PromptMessage> unknown = PromptAssembler.assemble(
                PERSONA, PlayerEnvironment.unknown(), conversation(), List.of());

        assertEquals(known.size() - 1, unknown.size());
        // Not "environment: unknown", which is a line the model would try to use.
        assertEquals(PromptAssembler.assemble(PERSONA, conversation()), unknown);
    }

    @Test
    void keepsTheEnvironmentOutOfThePlayersOwnPersona() {
        List<PromptMessage> prompt = PromptAssembler.assemble(
                PERSONA, PlayerEnvironment.multiplayer("mc.example.net"), conversation(), List.of());

        assertEquals(PERSONA, prompt.getFirst().text(),
                "the persona message is the player's file and nothing else");
        assertTrue(prompt.getFirst().cacheBreakpoint(),
                "so it stays the cached prefix even when the player changes servers");
    }

    @Test
    void doesNotSpendABreakpointOnTheEnvironment() {
        List<PromptMessage> prompt = PromptAssembler.assemble(
                PERSONA, PlayerEnvironment.multiplayer("mc.example.net"), conversation(), List.of());

        assertFalse(prompt.get(1).cacheBreakpoint(),
                "the breakpoint on the newest answer already covers this line");
    }

    @Test
    void stillRefusesAConversationWithNothingInIt() {
        // The guard used to count the prompt's messages, which the environment note would
        // have quietly satisfied on its own.
        assertThrows(IllegalArgumentException.class, () -> PromptAssembler.assemble(
                PERSONA, PlayerEnvironment.singlePlayer(), List.of(), List.of()));
    }
}
