package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import io.github.mcagents.clientfabric.api.agent.TokenUsage;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prefix discipline that makes provider-side prompt caching actually work.
 *
 * <p>These are the tests that would fail if someone "tidied" the assembler by moving
 * retrieved context into the system message, or by rebuilding the persona per request.
 * Neither would break a single functional test — the mod would answer perfectly, and every
 * turn would silently cost full price.
 */
class PromptCachingTest {

    private static final String PERSONA = "You are a Skyblock expert. Be terse.";

    private static ChatMessage message(int index, ChatRole role, String content) {
        return new ChatMessage(index, "session-1", index, role, content, Instant.now());
    }

    /** A conversation of {@code turns} completed exchanges, plus a new unanswered question. */
    private static List<ChatMessage> conversation(int turns, String newQuestion) {
        List<ChatMessage> messages = new ArrayList<>();
        int index = 1;
        for (int turn = 1; turn <= turns; turn++) {
            messages.add(message(index++, ChatRole.USER, "question " + turn));
            messages.add(message(index++, ChatRole.ASSISTANT, "answer " + turn));
        }
        messages.add(message(index, ChatRole.USER, newQuestion));
        return messages;
    }

    /** The serialised prefix up to and including the last breakpoint. */
    private static String cachedPrefix(List<PromptMessage> prompt) {
        StringBuilder prefix = new StringBuilder();
        for (PromptMessage message : prompt) {
            prefix.append(message.role().wireName()).append(':').append(message.text()).append('\n');
            if (message.cacheBreakpoint()) {
                break;
            }
        }
        return prefix.toString();
    }

    @Test
    void marksThePersonaAsABreakpoint() {
        List<PromptMessage> prompt = PromptAssembler.assemble(PERSONA, conversation(0, "hello"));

        assertEquals(ChatRole.SYSTEM, prompt.getFirst().role());
        assertTrue(prompt.getFirst().cacheBreakpoint(),
                "the persona is identical on every request and is the cheapest thing to reuse");
    }

    @Test
    void marksTheLastCompletedAnswerAsABreakpoint() {
        List<PromptMessage> prompt = PromptAssembler.assemble(PERSONA, conversation(3, "question 4"));

        List<Integer> breakpoints = new ArrayList<>();
        for (int i = 0; i < prompt.size(); i++) {
            if (prompt.get(i).cacheBreakpoint()) {
                breakpoints.add(i);
            }
        }

        // index 0 is the persona; the last assistant message is second from the end,
        // because the unanswered question is last.
        assertEquals(List.of(0, prompt.size() - 2), breakpoints);
        assertEquals(ChatRole.ASSISTANT, prompt.get(prompt.size() - 2).role());
    }

    @Test
    void neverMarksTheNewQuestion() {
        List<PromptMessage> prompt = PromptAssembler.assemble(PERSONA, conversation(2, "brand new"));

        PromptMessage last = prompt.getLast();
        assertEquals("brand new", last.text());
        assertFalse(last.cacheBreakpoint(),
                "the new question is by definition not in any previous request");
    }

    @Test
    void placesNoSecondBreakpointOnTheFirstTurn() {
        // Nothing has been answered yet, so there is no settled history to reuse.
        List<PromptMessage> prompt = PromptAssembler.assemble(PERSONA, conversation(0, "first ever"));

        assertEquals(1, prompt.stream().filter(PromptMessage::cacheBreakpoint).count());
    }

    @Test
    void usesAtMostTwoBreakpoints() {
        // Providers cap them — Anthropic at four — and each one costs a cache write.
        for (int turns = 0; turns < 12; turns++) {
            long count = PromptAssembler.assemble(PERSONA, conversation(turns, "next")).stream()
                    .filter(PromptMessage::cacheBreakpoint)
                    .count();
            assertTrue(count <= 2, "turns=" + turns + " produced " + count + " breakpoints");
        }
    }

    @Test
    void keepsTheCachedPrefixByteIdenticalAsTheConversationGrows() {
        // The whole point. Turn N's cached prefix must be a prefix of turn N+1's, or the
        // provider reuses nothing and every turn is billed in full.
        List<PromptMessage> turnOne = PromptAssembler.assemble(PERSONA, conversation(1, "second"));
        List<PromptMessage> turnTwo = PromptAssembler.assemble(PERSONA, conversation(2, "third"));
        List<PromptMessage> turnThree = PromptAssembler.assemble(PERSONA, conversation(3, "fourth"));

        assertTrue(cachedPrefix(turnTwo).startsWith(cachedPrefix(turnOne)),
                "turn 2 must extend turn 1's prefix, not rewrite it");
        assertTrue(cachedPrefix(turnThree).startsWith(cachedPrefix(turnTwo)),
                "turn 3 must extend turn 2's prefix, not rewrite it");
    }

    @Test
    void producesTheSamePrefixForTheSameInputEveryTime() {
        // A persona rebuilt with a timestamp, a random id, or a reordered map would break
        // caching completely while every other test still passed.
        List<ChatMessage> messages = conversation(2, "same question");

        assertEquals(
                cachedPrefix(PromptAssembler.assemble(PERSONA, messages)),
                cachedPrefix(PromptAssembler.assemble(PERSONA, messages)));
    }

    @Test
    void putsTheDefaultPersonaFirstWhenThePlayerHasWrittenNone() {
        List<PromptMessage> prompt = PromptAssembler.assemble("  ", conversation(1, "next"));

        assertEquals(PromptAssembler.defaultPersona(), prompt.getFirst().text());
        assertTrue(prompt.getFirst().cacheBreakpoint());
    }

    @Test
    void reportsWhetherTheProviderReusedAnything() {
        TokenUsage hit = new TokenUsage(1000, 50, 900);
        TokenUsage miss = new TokenUsage(1000, 50, 0);

        assertTrue(hit.hadCacheHit());
        assertEquals(0.9, hit.cacheHitRate(), 0.0001);

        assertFalse(miss.hadCacheHit());
        assertEquals(0.0, miss.cacheHitRate());
        assertEquals(0.0, TokenUsage.UNKNOWN.cacheHitRate(), "no tokens is not a division by zero");
    }
}
