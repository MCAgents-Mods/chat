package io.github.mcagents.clientfabric.common;

import io.github.mcagents.clientfabric.api.model.ModelCatalog;
import io.github.mcagents.clientfabric.api.model.ModelPreferences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The models offered and the ones fallen back to.
 *
 * <p>These read like trivia until one of them is wrong, at which point every request the
 * mod makes fails for a player who never touched the setting.
 */
class ModelDefaultsTest {

    @Test
    void defaultsToModelsTheProviderActuallyServes() {
        assertEquals("deepseek/deepseek-v4-flash", ModelCatalog.DEFAULT_CHAT_MODEL);
        assertEquals("openai/text-embedding-3-small", ModelCatalog.DEFAULT_EMBEDDING_MODEL);
    }

    @Test
    void offersBothDefaultsInTheirLists() {
        // A default that is not on the list looks like a mistake to anyone reading the
        // dropdown, and leaves no way back to it once the player has typed over it.
        assertTrue(ModelCatalog.isSuggestedChatModel(ModelCatalog.DEFAULT_CHAT_MODEL));
        assertTrue(ModelCatalog.isSuggestedEmbeddingModel(ModelCatalog.DEFAULT_EMBEDDING_MODEL));
    }

    @Test
    void noLongerOffersTheAliasThatWasNeverServed() {
        assertFalse(ModelCatalog.SUGGESTED_CHAT_MODELS.contains("deepseek/deepseek-v4-flash-latest"));
    }

    @Test
    void movesAPlayerOffAModelThatStoppedWorking() {
        // Removing an id from the list stops it being offered, not stops it being used: a
        // player who selected it before has it saved, and would otherwise keep failing
        // every request until they thought to look at the setting.
        assertTrue(ModelCatalog.isRetired("deepseek/deepseek-v4-flash-latest"));
        assertEquals(ModelCatalog.DEFAULT_CHAT_MODEL,
                ModelPreferences.of("deepseek/deepseek-v4-flash-latest", "").chatModel());
        assertEquals(ModelCatalog.DEFAULT_CHAT_MODEL,
                ModelCatalog.resolve("  deepseek/deepseek-v4-flash-latest  "));
    }

    @Test
    void leavesEveryOtherChoiceAlone() {
        // The retirement list is narrow on purpose. A player who typed an id the mod has
        // never heard of keeps it — that is the whole reason the lists are suggestions.
        assertFalse(ModelCatalog.isRetired("some/model-nobody-here-knows"));
        assertEquals("some/model-nobody-here-knows",
                ModelPreferences.of("some/model-nobody-here-knows", "").chatModel());
        assertEquals("deepseek/deepseek-v4-pro",
                ModelPreferences.of("deepseek/deepseek-v4-pro", "").chatModel());
    }

    @Test
    void stillFallsBackWhenNothingIsChosen() {
        assertEquals(ModelCatalog.DEFAULT_CHAT_MODEL, ModelPreferences.of("  ", null).chatModel());
        assertEquals(ModelCatalog.DEFAULT_EMBEDDING_MODEL,
                ModelPreferences.of(null, "  ").embeddingModel());
    }
}
