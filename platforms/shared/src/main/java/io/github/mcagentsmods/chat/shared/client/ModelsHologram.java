package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.ui.ProviderNotes;
import io.github.mcagentsmods.core.api.hologram.Form;
import io.github.mcagentsmods.core.api.hologram.FormButton;
import io.github.mcagentsmods.core.api.hologram.FormField;
import io.github.mcagentsmods.core.api.hologram.FormText;
import io.github.mcagentsmods.core.api.hologram.FormToggle;
import io.github.mcagentsmods.core.api.hologram.FormTone;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.hologram.HologramButton;
import io.github.mcagentsmods.core.api.hologram.SelectList;
import io.github.mcagentsmods.core.api.model.GlobalModelStore;
import io.github.mcagentsmods.core.api.model.GlobalModels;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.api.model.ModelProvider;
import io.github.mcagentsmods.core.client.hologram.Holograms;
import io.github.mcagentsmods.core.common.model.Models;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The global model, as a hologram MODEL opens: what every task with no model of its own is
 * answered and embedded with, and the custom providers a request can go to.
 *
 * <h2>Provider and model are two settings</h2>
 *
 * <p>Each model has a provider beside it: the API the request is sent to, OpenRouter or a
 * platform's own, and nothing else. Picking a provider the catalog suggests models for moves
 * the chat model onto it through {@link ModelCatalog#chatModelOn}, so the id shown is one that
 * provider serves; typing a model never changes the provider. The line under each provider says where the request will go, and
 * whether a key for that provider is stored, through {@link ProviderNotes}.
 *
 * <h2>Custom providers</h2>
 *
 * <p>The list beside the form holds the providers the player added. ADD asks for a name, an
 * address and, if wanted, a key, in a dialog hologram; REMOVE drops the chosen one. Both take
 * effect at once, unlike the form, which writes only on SAVE: a switch that saved itself would
 * be the one control on the form that behaved differently.
 */
final class ModelsHologram extends HologramPage {

    /** The hologram's id. */
    static final String ID = "mcagents.models";

    /** The add-provider dialog's id; it stands beside the page. */
    static final String ADD_ID = "mcagents.models.add";

    private static final String PREFIX = "gui.mcagents.models.";

    /** Long enough for any provider key or address; a key is never drawn back. */
    private static final int LONG = 400;

    private final LocalDatabase database;
    private final GlobalModelStore store = Models.store();

    private String chatProvider = ModelPlatform.OPENROUTER;
    private String chatModel = "";
    private String embeddingProvider = ModelPlatform.OPENROUTER;
    private String embeddingModel = "";
    private boolean embeddingEnabled = ModelPreferences.DEFAULT_EMBEDDING_ENABLED;
    private boolean loaded;

    /** The providers a key is stored for; read with the preferences, for the notes. */
    private Set<String> keyed = Set.of();
    private int selectedProvider = -1;

    private String status = "";
    private FormTone statusTone = FormTone.SOFT;

    /** The add dialog's draft. */
    private String newName = "";
    private String newAddress = "";
    private String newKey = "";
    private String addNote = "";
    private FormTone addNoteTone = FormTone.SOFT;

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
        Form form = new Form(PREFIX + "title", List.of(
                FormField.of(PREFIX + "chat.provider", () -> chatProvider, text -> chatProvider = text)
                        .withChoices(this::providerIds)
                        .onPick(this::pickChatProvider),
                new FormText(() -> noteText(chatProvider), () -> noteTone(chatProvider), 1),
                FormField.of(PREFIX + "chat", () -> chatModel, text -> chatModel = text)
                        .withChoices(this::chatSuggestions),
                FormField.of(PREFIX + "embedding.provider", () -> embeddingProvider,
                                text -> embeddingProvider = text)
                        .withChoices(this::providerIds),
                new FormText(() -> noteText(embeddingProvider), () -> noteTone(embeddingProvider), 1),
                FormField.of(PREFIX + "embedding", () -> embeddingModel, text -> embeddingModel = text)
                        .withChoices(() -> ModelCatalog.SUGGESTED_EMBEDDING_MODELS),
                new FormToggle(PREFIX + "embedding.switch", () -> embeddingEnabled,
                        () -> embeddingEnabled = !embeddingEnabled),
                new FormText(this::statusLine, () -> statusTone, 1)),
                List.of(new FormButton(PREFIX + "save", () -> loaded && !waiting(), this::save),
                        FormButton.of(PREFIX + "reset", this::reset))).wide();
        SelectList providers = new SelectList(PREFIX + "providers", this::providerRows,
                () -> selectedProvider, index -> selectedProvider = index,
                List.of(new HologramButton(PREFIX + "providers.add", this::openAdd),
                        new HologramButton(PREFIX + "providers.remove", this::removeSelected)));
        return Hologram.of(ID, form, providers);
    }

    private List<String> providerIds() {
        return store.current().providerIds();
    }

    /** The suggested chat models the provider serves, or every suggestion when it is the gateway or unknown. */
    private List<String> chatSuggestions() {
        String provider = ModelPlatform.normalise(chatProvider);
        List<String> own = ModelPlatform.isOpenRouter(provider)
                ? List.of()
                : ModelCatalog.suggestedChatModelsFor(provider);
        return own.isEmpty() ? ModelCatalog.SUGGESTED_CHAT_MODELS : own;
    }

    private ProviderNotes.Note note(String provider) {
        return ProviderNotes.of(store.current(), provider, keyed);
    }

    private String noteText(String provider) {
        ProviderNotes.Note note = note(provider);
        String where = note.isOpenRouter() ? "OpenRouter" : note.host();
        return switch (note.kind()) {
            case CALLED -> I18n.get(PREFIX + "route.called", where);
            case NO_KEY -> I18n.get(PREFIX + "route.no_key", where);
            case UNKNOWN -> I18n.get(PREFIX + "route.unknown", note.provider());
        };
    }

    private FormTone noteTone(String provider) {
        return switch (note(provider).kind()) {
            case CALLED -> FormTone.GOOD;
            case NO_KEY -> FormTone.WARN;
            case UNKNOWN -> FormTone.BAD;
        };
    }

    /** One row per custom provider: its name and the host it is called at. */
    private List<String> providerRows() {
        return store.current().customProviders().stream()
                .map(provider -> provider.id() + "  " + ProviderNotes.host(provider.baseUrl()))
                .toList();
    }

    private String statusLine() {
        return waiting() ? I18n.get("gui.mcagents.waiting") : status;
    }

    /**
     * Reads the preferences and the providers the player holds keys for, together. The keys are
     * read for their provider names only; no value is read back, and none could be shown here.
     */
    private void load() {
        request(database.settings().loadModelPreferences()
                        .thenCombine(database.apiKeys().findAll(), Loaded::new), state -> {
            keyed = keyedProviders(state.keys());
            show(state.preferences());
            loaded = true;
        }, "Could not read the model preferences",
                failure -> say(I18n.get(PREFIX + "load_failed"), FormTone.BAD));
    }

    /** The two answers {@link #load()} waits for. */
    private record Loaded(ModelPreferences preferences, List<ApiKey> keys) {
    }

    private static Set<String> keyedProviders(List<ApiKey> keys) {
        return keys.stream().filter(ApiKey::active).map(ApiKey::platform)
                .map(ModelPlatform::normalise).collect(Collectors.toUnmodifiableSet());
    }

    private void refreshKeys() {
        request(database.apiKeys().findAll(), keys -> keyed = keyedProviders(keys),
                "Could not read the stored API keys");
    }

    private void show(ModelPreferences preferences) {
        chatProvider = preferences.chat().provider();
        chatModel = preferences.chatModel();
        embeddingProvider = preferences.embedding().provider();
        embeddingModel = preferences.embeddingModel();
        embeddingEnabled = preferences.embeddingEnabled();
    }

    /**
     * Puts the chat model on {@code provider} when the catalog suggests models for it, by the one
     * rule {@link ModelCatalog#chatModelOn}, so the id shown is one that provider serves before
     * anything is stored. A provider it suggests nothing for, a custom one most often, keeps the
     * model as it is: a host of other people's models is sent the whole name.
     */
    private void pickChatProvider(String provider) {
        chatProvider = ModelPlatform.normalise(provider);
        if (!chatModel.isBlank() && !ModelCatalog.suggestedChatModelsFor(chatProvider).isEmpty()) {
            chatModel = ModelCatalog.chatModelOn(chatProvider, chatModel);
        }
    }

    private void save() {
        GlobalModels global = store.current();
        String chatAt = ModelPlatform.normalise(chatProvider);
        String embeddingAt = ModelPlatform.normalise(embeddingProvider);
        // A provider typed rather than picked must be one a request can go to: a model saved
        // at a provider with no address would fail every question until the player looked here.
        for (String provider : List.of(chatAt, embeddingAt)) {
            if (global.provider(provider).isEmpty()) {
                say(I18n.get(PREFIX + "provider.unknown", provider), FormTone.BAD);
                return;
            }
        }
        // Blank fields are not an error: the catalog default fills them, so "clear it and
        // save" is how a player gets back to the default by hand.
        ModelPreferences requested = ModelPreferences.of(
                ModelChoice.qualified(chatAt, orDefault(chatModel, ModelCatalog.DEFAULT_CHAT_MODEL)),
                ModelChoice.qualified(embeddingAt, orDefault(embeddingModel, ModelCatalog.DEFAULT_EMBEDDING_MODEL)),
                embeddingEnabled);
        request(database.settings().saveModelPreferences(requested), stored -> {
            // Show what was actually stored, which may differ from what was typed.
            show(stored);
            say(I18n.get(PREFIX + "saved"), FormTone.GOOD);
        }, "Could not store the model preferences",
                failure -> say(I18n.get(PREFIX + "save_failed", describe(failure)), FormTone.BAD));
    }

    private static String orDefault(String model, String fallback) {
        return model == null || model.isBlank() ? fallback : model;
    }

    private void reset() {
        show(ModelPreferences.defaults());
        say("", FormTone.SOFT);
    }

    private void say(String text, FormTone tone) {
        this.status = text;
        this.statusTone = tone;
    }

    // Custom providers ------------------------------------------------------------------------

    /** Opens the add dialog beside the page, empty. */
    private void openAdd() {
        newName = "";
        newAddress = "";
        newKey = "";
        sayInAdd("", FormTone.SOFT);
        Holograms.open(Hologram.of(ADD_ID, new Form(PREFIX + "add.title", List.of(
                FormText.of(PREFIX + "add.about", FormTone.SOFT, 3),
                FormField.of(PREFIX + "add.name", () -> newName, text -> newName = text)
                        .withHint(PREFIX + "add.name.hint").withMaxLength(32),
                FormField.of(PREFIX + "add.address", () -> newAddress, text -> newAddress = text)
                        .withHint(PREFIX + "add.address.hint").withMaxLength(LONG),
                FormField.of(PREFIX + "add.key", () -> newKey, text -> newKey = text)
                        .withHint(PREFIX + "add.key.hint").withMaxLength(LONG).hidden(),
                new FormText(() -> addNote, () -> addNoteTone, 2)),
                List.of(new FormButton(PREFIX + "providers.add", () -> !waiting(), this::addProvider),
                        FormButton.of("gui.cancel", () -> Holograms.close(ADD_ID)))).wide()));
    }

    /**
     * Adds the provider in the dialog, and its key when one was typed. A name or an address that
     * cannot be used is refused in the dialog, before anything is written.
     */
    private void addProvider() {
        String name = ModelPlatform.normalise(newName);
        String address = newAddress.trim();
        String key = newKey.trim();
        // Emptied before anything else: a key must not sit in a box after it is stored.
        newKey = "";
        if (newName.isBlank() || !ModelProvider.isValidId(name) || ModelPlatform.isOpenRouter(name)) {
            sayInAdd(I18n.get(PREFIX + "add.name.refused"), FormTone.BAD);
            return;
        }
        if (!ModelPlatform.isUsableBaseUrl(address)) {
            sayInAdd(I18n.get(PREFIX + "add.address.refused"), FormTone.BAD);
            return;
        }
        ModelProvider provider = new ModelProvider(name, address);
        var saved = store.save(store.current().withProvider(provider));
        var stored = key.isEmpty()
                ? saved.thenApply(models -> (Object) models)
                : saved.thenCompose(models -> database.apiKeys().add(key, name)).thenApply(added -> (Object) added);
        request(stored, done -> {
            Holograms.close(ADD_ID);
            say(I18n.get(PREFIX + "providers.added", name), FormTone.GOOD);
            refreshKeys();
        }, "Could not add the provider " + name,
                failure -> sayInAdd(I18n.get(PREFIX + "save_failed", describe(failure)), FormTone.BAD));
    }

    /**
     * Removes the chosen custom provider. A form field that named it moves to OpenRouter, as
     * the stored choice does; a key stored for it is kept, and is removed on the Keys page.
     */
    private void removeSelected() {
        List<ModelProvider> custom = store.current().customProviders();
        if (selectedProvider < 0 || selectedProvider >= custom.size()) {
            return;
        }
        String gone = custom.get(selectedProvider).id();
        selectedProvider = -1;
        request(store.save(store.current().withoutProvider(gone)), saved -> {
            if (saved.provider(chatProvider).isEmpty()) {
                chatProvider = ModelPlatform.OPENROUTER;
            }
            if (saved.provider(embeddingProvider).isEmpty()) {
                embeddingProvider = ModelPlatform.OPENROUTER;
            }
            say(I18n.get(PREFIX + "providers.removed", gone), FormTone.SOFT);
        }, "Could not remove the provider " + gone,
                failure -> say(I18n.get(PREFIX + "save_failed", describe(failure)), FormTone.BAD));
    }

    private void sayInAdd(String text, FormTone tone) {
        this.addNote = text;
        this.addNoteTone = tone;
    }
}
