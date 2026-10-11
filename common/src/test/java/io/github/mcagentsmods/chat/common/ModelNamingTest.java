package io.github.mcagentsmods.chat.common;

import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.core.api.model.ModelName;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stored shape of a model identifier: {@code {platform}/{model}}, lowercase, one spelling
 * per model whichever route reached it.
 */
class ModelNamingTest {

    @Test
    void normalisesEveryModelIdBeforeItIsStored() {
        ModelPreferences stored = ModelPreferences.of(
                "  OpenAI/GPT-4o-Mini  ", "OpenAI/Text-Embedding-3-Small", true);

        assertEquals("openai/gpt-4o-mini", stored.chatModel());
        assertEquals("openai/text-embedding-3-small", stored.embeddingModel());
    }

    @Test
    void tellsCallersWhichPlatformServesTheChosenModels() {
        ModelPreferences stored = ModelPreferences.of("openai/gpt-4o-mini", "gpt-embed", true);

        assertEquals(Optional.of("openai"), stored.chatPlatform());
        // Unqualified, so nothing to route on — the gateway takes it.
        assertEquals(Optional.empty(), stored.embeddingPlatform());
    }

    @Test
    void retiresAModelWhateverCapitalisationItWasSavedWith() {
        assertTrue(ModelCatalog.isRetired("DeepSeek/DeepSeek-V4-Flash-Latest"));
        assertEquals(ModelCatalog.DEFAULT_CHAT_MODEL,
                ModelCatalog.resolve("DeepSeek/DeepSeek-V4-Flash-Latest"));
    }

    @Test
    void everySuggestedModelFollowsTheConvention() {
        // The lists the interface offers are the mod's own data, so they have no excuse.
        java.util.stream.Stream.concat(
                        ModelCatalog.SUGGESTED_CHAT_MODELS.stream(),
                        ModelCatalog.SUGGESTED_EMBEDDING_MODELS.stream())
                .forEach(id -> {
                    ModelName name = ModelName.parse(id).orElseThrow(() ->
                            new AssertionError(id + " carries no platform segment"));
                    assertEquals(id, name.qualified(), id + " is not stored in its own shape");
                    assertEquals(id.toLowerCase(java.util.Locale.ROOT), id, id + " is not lowercase");
                });
    }

    @Test
    void offersThePlatformsItsOwnSuggestionsName() {
        assertEquals(ModelPlatform.OPENROUTER, ModelCatalog.suggestedPlatforms().get(0));
        assertTrue(ModelCatalog.suggestedPlatforms().contains("openai"));
        assertTrue(ModelCatalog.suggestedPlatforms().contains("deepseek"));
    }

    @Test
    void keepsTheModelWhenThePlatformChosenAlreadyServesIt() {
        // Picking the platform you are already on is not an instruction to switch models.
        assertEquals("deepseek/deepseek-v4-pro",
                ModelCatalog.chatModelOn("DeepSeek", "deepseek/deepseek-v4-pro"));
    }

    @Test
    void movesToThePlatformsOwnSuggestionWhenItCannotServeTheCurrentModel() {
        // openai/deepseek-v4-flash is a model OpenAI does not serve. The player asked for an
        // answer, not for a request that fails.
        assertEquals("openai/gpt-4o-mini",
                ModelCatalog.chatModelOn("openai", "deepseek/deepseek-v4-flash"));
    }

    @Test
    void carriesTheModelOverToAPlatformItSuggestsNothingFor() {
        // No suggestion to fall back on, so there is no better guess than the id the player
        // chose — and inventing one would be the mod overruling them.
        assertEquals("groq/deepseek-v4-flash",
                ModelCatalog.chatModelOn("groq", "deepseek/deepseek-v4-flash"));
    }

    @Test
    void qualifiesABareModelIdWithThePlatformChosenForIt() {
        assertEquals("openai/gpt-4o-mini", ModelCatalog.chatModelOn("openai", "gpt-4o-mini"));
    }

    @Test
    void neverQualifiesAModelWithTheGateway() {
        // openrouter/gpt-4o-mini is not a longer spelling of gpt-4o-mini; it is a model
        // nobody serves. The gateway is a route, and a route is not a model's platform.
        assertEquals("deepseek/deepseek-v4-flash",
                ModelCatalog.chatModelOn("openrouter", "deepseek/deepseek-v4-flash"));
        assertEquals("deepseek/deepseek-v4-flash",
                ModelCatalog.chatModelOn("", "deepseek/deepseek-v4-flash"));
    }

    @Test
    void resolvesARetiredIdWhileSwitchingPlatform() {
        assertEquals("deepseek/deepseek-v4-flash",
                ModelCatalog.chatModelOn("deepseek", "DeepSeek/DeepSeek-V4-Flash-Latest"));
    }

    @Test
    void saysWhichSuggestedModelsAPlatformServes() {
        assertEquals(java.util.List.of("deepseek/deepseek-v4-flash", "deepseek/deepseek-v4-pro"),
                ModelCatalog.suggestedChatModelsFor("DeepSeek"));
        assertTrue(ModelCatalog.suggestedChatModelsFor("groq").isEmpty(),
                "nothing suggested is not the same as nothing served");
    }

    @Test
    void offersTheGatewayTheShippedPlatformsAndThePlayersOwn() {
        java.util.List<String> offered = ModelCatalog.offeredPlatforms(
                java.util.List.of("Fireworks", "", "openai"));

        assertEquals(ModelPlatform.OPENROUTER, offered.get(0),
                "the gateway leads: it is the default a key is filed under");
        assertTrue(offered.contains("deepseek"), "a platform the suggested models name");
        assertTrue(offered.contains("groq"), "a platform this release ships an address for");
        assertEquals("fireworks", offered.get(offered.size() - 1),
                "and the player's own platform last, lowercased");
        assertEquals(offered.size(), java.util.Set.copyOf(offered).size(),
                "a platform the player already uses and the mod also ships is offered once");
    }

    @Test
    void leavesTheGatewayOutOfTheListOfWhoServesAModel() {
        // openrouter/gpt-4o-mini is a model nobody serves, so the gateway is not a choice
        // where a model's platform is being picked.
        assertFalse(ModelCatalog.offeredModelPlatforms(java.util.List.of())
                .contains(ModelPlatform.OPENROUTER));
        assertTrue(ModelCatalog.offeredModelPlatforms(java.util.List.of()).contains("openai"));
    }

}
