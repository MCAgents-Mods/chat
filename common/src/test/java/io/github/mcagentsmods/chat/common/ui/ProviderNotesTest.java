package io.github.mcagentsmods.chat.common.ui;

import io.github.mcagentsmods.chat.common.ui.ProviderNotes.Kind;
import io.github.mcagentsmods.chat.common.ui.ProviderNotes.Note;
import io.github.mcagentsmods.core.api.model.GlobalModels;
import io.github.mcagentsmods.core.api.model.ModelProvider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The line under a provider says where the request goes, and never claims a route it does not take. */
class ProviderNotesTest {

    private final GlobalModels models = GlobalModels.DEFAULTS
            .withProvider(new ModelProvider("homelab", "http://127.0.0.1:8080/v1"))
            .withProvider(new ModelProvider("deepseek", "https://eu.deepseek.example/v1"));

    @Test
    void namesTheHostOfABuiltInProviderWithAKey() {
        Note note = ProviderNotes.of(GlobalModels.DEFAULTS, "openai", Set.of("openai"));

        assertEquals(new Note(Kind.CALLED, "openai", "api.openai.com"), note);
    }

    @Test
    void saysADirectProviderWithNoKeyIsStillCalledAndWillAskForOne() {
        // The case owen reported: deepseek with no key went through OpenRouter without a word.
        Note note = ProviderNotes.of(GlobalModels.DEFAULTS, "deepseek", Set.of("openrouter"));

        assertEquals(Kind.NO_KEY, note.kind());
        assertEquals("api.deepseek.com", note.host());
    }

    @Test
    void usesTheAddressACustomProviderGivesABuiltInOne() {
        assertEquals("eu.deepseek.example", ProviderNotes.of(models, "deepseek", Set.of()).host());
    }

    @Test
    void namesACustomProviderByItsHostAndPort() {
        assertEquals(new Note(Kind.CALLED, "homelab", "127.0.0.1:8080"),
                ProviderNotes.of(models, "HomeLab", Set.of("homelab")));
    }

    @Test
    void treatsABlankFieldAsOpenRouter() {
        Note note = ProviderNotes.of(models, "  ", Set.of("openrouter"));

        assertTrue(note.isOpenRouter());
        assertEquals(Kind.CALLED, note.kind());
    }

    @Test
    void saysWhenNoAddressIsKnown() {
        assertEquals(new Note(Kind.UNKNOWN, "nowhere", ""), ProviderNotes.of(models, "nowhere", Set.of("nowhere")));
    }

    @Test
    void fallsBackToTheWholeAddressWhenItHasNoHost() {
        assertEquals("not a url", ProviderNotes.host(" not a url "));
    }
}
