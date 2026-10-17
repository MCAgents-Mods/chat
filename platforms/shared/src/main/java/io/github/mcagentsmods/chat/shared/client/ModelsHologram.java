package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.hologram.Form;
import io.github.mcagentsmods.core.api.hologram.FormButton;
import io.github.mcagentsmods.core.api.hologram.FormField;
import io.github.mcagentsmods.core.api.hologram.FormText;
import io.github.mcagentsmods.core.api.hologram.FormToggle;
import io.github.mcagentsmods.core.api.hologram.FormTone;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelName;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.api.model.ProviderRoutes;
import io.github.mcagentsmods.core.common.model.Models;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * Where the player picks the chat model and the embedding model, as a hologram MODEL opens.
 *
 * <p>Every field offers suggestions from its arrow and can also be typed into. The suggested
 * lists are a convenience, not a whitelist: nothing here rejects a model id the mod has not
 * heard of, because OpenRouter's catalog moves faster than this mod ships. An id OpenRouter does
 * not serve fails at request time with OpenRouter's own message.
 *
 * <h2>The provider row</h2>
 *
 * <p>Above the chat model, because it is the shorter way of asking the same question: a stored
 * id names both who serves the model and which model it is, so picking a provider rewrites the
 * id through {@link ModelCatalog#chatModelOn}, the one rule for that choice. The two stay in step
 * both ways: typing a model id moves the provider field to that id's platform, so a provider
 * that differs from the id's platform can only mean the player typed it on purpose, and SAVE
 * honours it then.
 *
 * <p>Under it, where that provider is called, answered by core's {@link Models#routes()}, the
 * same routing the real request goes through. The models are core's global model, which every
 * task with no model of its own falls back to.
 *
 * <p>Nothing is stored until SAVE, the embedding switch included: a switch that saved itself
 * would be the one control that behaved differently.
 */
final class ModelsHologram extends HologramPage {

    /** The hologram's id. */
    static final String ID = "mcagents.models";

    private static final String PREFIX = "gui.mcagents.models.";

    private final LocalDatabase database;
    private final ProviderRoutes routes = Models.routes();

    /** The embedding choice as loaded, carried through a save untouched. */
    private ModelChoice embeddingChoice = ModelPreferences.defaults().embedding();

    private String provider = "";
    private String chatModel = "";
    private String embeddingModel = "";
    private boolean embeddingEnabled = ModelPreferences.DEFAULT_EMBEDDING_ENABLED;
    private boolean loaded;

    /** The platforms the player already holds a key for, offered beside the ones this release knows. */
    private List<String> platformsInUse = List.of();

    private String route = "";
    private FormTone routeTone = FormTone.SOFT;
    private String status = "";
    private FormTone statusTone = FormTone.SOFT;

    private ModelsHologram(LocalDatabase database) {
        this.database = database;
        show(ModelPreferences.defaults());
    }

    /** Opens the page on the stored preferences. */
    static void open(LocalDatabase database) {
        ModelsHologram page = new ModelsHologram(database);
        page.open();
        page.load();
    }

    @Override
    Hologram build() {
        return Hologram.of(ID, new Form(PREFIX + "title", List.of(
                FormField.of(PREFIX + "provider", () -> provider, text -> provider = text)
                        .withChoices(() -> ModelCatalog.offeredModelPlatforms(platformsInUse))
                        .onPick(this::applyProvider),
                new FormText(() -> route, () -> routeTone, 1),
                FormField.of(PREFIX + "chat", () -> chatModel, this::typeChatModel)
                        .withChoices(() -> ModelCatalog.SUGGESTED_CHAT_MODELS)
                        .onPick(this::pickChatModel),
                FormField.of(PREFIX + "embedding", () -> embeddingModel, text -> embeddingModel = text)
                        .withChoices(() -> ModelCatalog.SUGGESTED_EMBEDDING_MODELS),
                new FormToggle(PREFIX + "embedding.switch", () -> embeddingEnabled,
                        () -> embeddingEnabled = !embeddingEnabled),
                new FormText(() -> PREFIX + (embeddingEnabled ? "embedding.tip.on" : "embedding.tip.off"),
                        () -> FormTone.SOFT, 3),
                new FormText(this::statusLine, () -> statusTone, 1)),
                List.of(new FormButton(PREFIX + "save", () -> loaded && !waiting(), this::save),
                        FormButton.of(PREFIX + "reset", this::reset))).wide());
    }

    private String statusLine() {
        return waiting() ? I18n.get("gui.mcagents.waiting") : status;
    }

    /**
     * Reads the preferences and the platforms the player holds keys for, together. The keys are
     * read for their platform names only; no value is read back, and none could be shown here.
     */
    private void load() {
        request(database.settings().loadModelPreferences()
                        .thenCombine(database.apiKeys().findAll(), Loaded::new), state -> {
            platformsInUse = state.keys().stream().map(ApiKey::platform).distinct().toList();
            show(state.preferences());
            loaded = true;
            showRoute(chatModel);
        }, "Could not read the model preferences",
                failure -> say(I18n.get(PREFIX + "load_failed"), FormTone.BAD));
    }

    /** The two answers {@link #load()} waits for. */
    private record Loaded(ModelPreferences preferences, List<ApiKey> keys) {
    }

    private void show(ModelPreferences preferences) {
        chatModel = preferences.chatModel();
        provider = preferences.chat().provider();
        embeddingChoice = preferences.embedding();
        embeddingModel = preferences.embeddingModel();
        embeddingEnabled = preferences.embeddingEnabled();
    }

    /** The platform half of a stored id, or blank when it names none. */
    private static String platformOf(String model) {
        return ModelName.parse(model).map(ModelName::platform).orElse("");
    }

    /** Typing an id moves the provider field to that id's platform, so it is never stale. */
    private void typeChatModel(String text) {
        chatModel = text;
        provider = platformOf(text);
    }

    private void pickChatModel(String model) {
        typeChatModel(model);
        showRoute(model);
    }

    /**
     * Puts the chat model on {@code platform}, by the one rule {@link ModelCatalog#chatModelOn}.
     * Applied when a provider is picked rather than at save time, so the id that results is
     * visible before anything is stored.
     */
    private void applyProvider(String platform) {
        if (platform == null || platform.isBlank()) {
            return;
        }
        String next = ModelCatalog.chatModelOn(platform, chatModel);
        chatModel = next;
        // Back from the id, not from what was picked: they differ when the rule declined to
        // move, and the field must show where the model actually is.
        provider = platformOf(next);
        showRoute(next);
    }

    /** Asks how {@code model} would be reached, through the code the request itself uses. */
    private void showRoute(String model) {
        request(routes.route(ModelChoice.qualified(provider, model)), answer -> {
            if (answer.isThroughOpenRouter()) {
                route = I18n.get(PREFIX + "route.gateway");
                routeTone = FormTone.SOFT;
            } else {
                route = I18n.get(PREFIX + "route.direct", answer.platform());
                routeTone = FormTone.GOOD;
            }
        }, "Could not work out how the provider is reached");
    }

    private void save() {
        // A provider typed rather than picked is honoured here, and only here: the field mirrors
        // the id's platform as it is typed, so a difference means the player edited the provider.
        if (!provider.equalsIgnoreCase(platformOf(chatModel))) {
            applyProvider(provider);
        }
        // Blank fields are not an error: ModelPreferences substitutes the catalog default, so
        // "clear it and save" is how a player gets back to the default by hand.
        ModelPreferences requested = ModelPreferences.of(
                ModelChoice.qualified(provider.isBlank() ? ModelPlatform.OPENROUTER : provider, chatModel),
                embeddingChoice.withModel(embeddingModel.isBlank()
                        ? ModelCatalog.DEFAULT_EMBEDDING_MODEL : embeddingModel),
                embeddingEnabled);
        request(database.settings().saveModelPreferences(requested), stored -> {
            // Show what was actually stored, which may differ from what was typed.
            show(stored);
            showRoute(stored.chatModel());
            say(I18n.get(PREFIX + "saved"), FormTone.GOOD);
        }, "Could not store the model preferences",
                failure -> say(I18n.get(PREFIX + "save_failed", describe(failure)), FormTone.BAD));
    }

    private void reset() {
        show(ModelPreferences.of(ModelCatalog.DEFAULT_CHAT_MODEL, ModelCatalog.DEFAULT_EMBEDDING_MODEL,
                ModelPreferences.DEFAULT_EMBEDDING_ENABLED));
        showRoute(chatModel);
        say("", FormTone.SOFT);
    }

    private void say(String text, FormTone tone) {
        this.status = text;
        this.statusTone = tone;
    }
}
