package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.SessionModels;
import io.github.mcagentsmods.chat.common.ui.ChatConversation;
import io.github.mcagentsmods.chat.common.ui.ProviderNotes;
import io.github.mcagentsmods.chat.shared.ChatRuntime;
import io.github.mcagentsmods.core.api.hologram.Form;
import io.github.mcagentsmods.core.api.hologram.FormButton;
import io.github.mcagentsmods.core.api.hologram.FormField;
import io.github.mcagentsmods.core.api.hologram.FormText;
import io.github.mcagentsmods.core.api.hologram.FormToggle;
import io.github.mcagentsmods.core.api.hologram.FormTone;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.model.GlobalModels;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.client.hologram.Holograms;
import io.github.mcagentsmods.core.common.model.Models;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One conversation's models, as a hologram MODEL on its chat hologram opens: the chat model and
 * the embedding model it is answered and embedded with, each with its provider, or the global
 * ones from the Models page.
 *
 * <p>Laid out like the Models page, with an "Own" switch above each pair. While a switch is off
 * its fields show the global model, greyed, and the conversation follows the Models page; turned
 * on, they start from the global model and can be changed. SAVE keeps the choice on the
 * conversation, or, for a new conversation not stored yet, keeps it until its first question
 * stores it. USE GLOBAL turns both off and saves at once.
 *
 * <p>Six fields and two switches fill the panel, so the Models page's route notes are left out:
 * SAVE refuses a provider with no address and warns about one with no key instead.
 */
final class ChatModelHologram extends HologramPage {

    /** What every chat model page's id starts with; the rest is its chat hologram's. */
    static final String ID_PREFIX = "mcagents.chatmodel.";

    private static final String PREFIX = "gui.mcagents.chat_model.";
    private static final String MODELS = "gui.mcagents.models.";

    private final String id;
    private final ChatConversation conversation;

    private boolean ownChat;
    private String chatProvider = ModelPlatform.OPENROUTER;
    private String chatModel = "";
    private boolean ownEmbedding;
    private String embeddingProvider = ModelPlatform.OPENROUTER;
    private String embeddingModel = "";

    /** The providers a key is stored for; read once, to warn when SAVE picks one with none. */
    private Set<String> keyed = Set.of();

    private String status = "";
    private FormTone statusTone = FormTone.SOFT;

    private ChatModelHologram(String chatHologramId, ChatConversation conversation) {
        this.id = idFor(chatHologramId);
        this.conversation = conversation;
        show(conversation.models());
    }

    /** The id of the model page for the chat hologram with id {@code chatHologramId}. */
    static String idFor(String chatHologramId) {
        return ID_PREFIX + chatHologramId.substring(ChatHologram.ID_PREFIX.length());
    }

    /**
     * Opens the model page of the conversation behind the chat hologram {@code chatHologramId},
     * on the models it chose.
     */
    static void open(String chatHologramId, ChatConversation conversation) {
        ChatModelHologram page = new ChatModelHologram(chatHologramId, conversation);
        page.open();
        page.loadKeys();
    }

    @Override
    Hologram build() {
        Form form = new Form(PREFIX + "title", List.of(
                new FormText(this::topLine, this::topTone, 1),
                new FormToggle(PREFIX + "own_chat", () -> ownChat, this::flipChat),
                FormField.of(MODELS + "chat.provider", () -> ownChat ? chatProvider : global().chat().provider(),
                                text -> chatProvider = text)
                        .withChoices(this::providerIds)
                        .onPick(this::pickChatProvider)
                        .editableWhen(() -> ownChat),
                FormField.of(MODELS + "chat", () -> ownChat ? chatModel : global().chat().model(),
                                text -> chatModel = text)
                        .withChoices(this::chatSuggestions)
                        .editableWhen(() -> ownChat),
                new FormToggle(PREFIX + "own_embedding", () -> ownEmbedding, this::flipEmbedding),
                FormField.of(MODELS + "embedding.provider",
                                () -> ownEmbedding ? embeddingProvider : global().embedding().provider(),
                                text -> embeddingProvider = text)
                        .withChoices(this::providerIds)
                        .editableWhen(() -> ownEmbedding),
                FormField.of(MODELS + "embedding",
                                () -> ownEmbedding ? embeddingModel : global().embedding().model(),
                                text -> embeddingModel = text)
                        .withChoices(() -> ModelCatalog.SUGGESTED_EMBEDDING_MODELS)
                        .editableWhen(() -> ownEmbedding)),
                List.of(new FormButton(MODELS + "save", () -> !waiting(), this::save),
                        new FormButton(PREFIX + "use_global", () -> !waiting(), this::useGlobal))).wide();
        return Hologram.of(id, form);
    }

    private static GlobalModels global() {
        return Models.global();
    }

    /** Which conversation this page sets: its title, or New Chat until it is stored. */
    private String subject() {
        String title = conversation.session().map(ChatSession::title)
                .orElse(I18n.get("hologram.mcagents.new_chat"));
        return I18n.get(PREFIX + "for", title);
    }

    private List<String> providerIds() {
        return global().providerIds();
    }

    /** The suggested chat models the provider serves, or every suggestion when it is the gateway or unknown. */
    private List<String> chatSuggestions() {
        String provider = ModelPlatform.normalise(chatProvider);
        List<String> own = ModelPlatform.isOpenRouter(provider)
                ? List.of()
                : ModelCatalog.suggestedChatModelsFor(provider);
        return own.isEmpty() ? ModelCatalog.SUGGESTED_CHAT_MODELS : own;
    }

    /**
     * The line under the title: what the last SAVE did, or else which conversation this page
     * sets. One line for both, so every field fits the panel.
     */
    private String topLine() {
        if (waiting()) {
            return I18n.get("gui.mcagents.waiting");
        }
        return status.isEmpty() ? subject() : status;
    }

    private FormTone topTone() {
        return waiting() || status.isEmpty() ? FormTone.SOFT : statusTone;
    }

    /** An own provider the conversation would be sent to with no key stored for it, or null. */
    private String keylessProvider(SessionModels chosen) {
        for (ModelChoice own : new ModelChoice[] {chosen.chat(), chosen.embedding()}) {
            if (own != null && ProviderNotes.of(global(), own.provider(), keyed).kind()
                    == ProviderNotes.Kind.NO_KEY) {
                return own.provider();
            }
        }
        return null;
    }

    /**
     * Reads which providers hold a key, for SAVE's warning. Only their provider names: no key value
     * is read back, and none could be shown here.
     */
    private void loadKeys() {
        ChatRuntime.database().ifPresent(database -> request(database.apiKeys().findAll(),
                keys -> keyed = keys.stream().filter(ApiKey::active).map(ApiKey::platform)
                        .map(ModelPlatform::normalise).collect(Collectors.toUnmodifiableSet()),
                "Could not read the stored API keys"));
    }

    private void show(SessionModels models) {
        GlobalModels global = global();
        ownChat = models.chat() != null;
        ModelChoice chat = ModelChoice.orElse(models.chat(), global.chat());
        chatProvider = chat.provider();
        chatModel = chat.model();
        ownEmbedding = models.embedding() != null;
        ModelChoice embedding = ModelChoice.orElse(models.embedding(), global.embedding());
        embeddingProvider = embedding.provider();
        embeddingModel = embedding.model();
    }

    /** Turning a switch on starts from the global model, the one the conversation was using. */
    private void flipChat() {
        ownChat = !ownChat;
        if (ownChat && chatModel.isBlank()) {
            chatProvider = global().chat().provider();
            chatModel = global().chat().model();
        }
    }

    private void flipEmbedding() {
        ownEmbedding = !ownEmbedding;
        if (ownEmbedding && embeddingModel.isBlank()) {
            embeddingProvider = global().embedding().provider();
            embeddingModel = global().embedding().model();
        }
    }

    /** As on the Models page: a provider the catalog suggests models for moves the model onto it. */
    private void pickChatProvider(String provider) {
        chatProvider = ModelPlatform.normalise(provider);
        if (!chatModel.isBlank() && !ModelCatalog.suggestedChatModelsFor(chatProvider).isEmpty()) {
            chatModel = ModelCatalog.chatModelOn(chatProvider, chatModel);
        }
    }

    private void save() {
        GlobalModels global = global();
        String chatAt = ModelPlatform.normalise(chatProvider);
        String embeddingAt = ModelPlatform.normalise(embeddingProvider);
        // A provider typed rather than picked must be one a request can go to, as on the Models
        // page: a model kept at a provider with no address would fail every question here.
        if ((ownChat && global.provider(chatAt).isEmpty())
                || (ownEmbedding && global.provider(embeddingAt).isEmpty())) {
            say(I18n.get(MODELS + "provider.unknown", ownChat && global.provider(chatAt).isEmpty()
                    ? chatAt : embeddingAt), FormTone.BAD);
            return;
        }
        if ((ownChat && chatModel.isBlank()) || (ownEmbedding && embeddingModel.isBlank())) {
            say(I18n.get(PREFIX + "model_missing"), FormTone.BAD);
            return;
        }
        SessionModels chosen = new SessionModels(
                ownChat ? ModelChoice.qualified(chatAt, ModelCatalog.resolve(chatModel)) : null,
                ownEmbedding ? ModelChoice.qualified(embeddingAt, ModelCatalog.resolve(embeddingModel)) : null);
        keep(chosen);
    }

    private void useGlobal() {
        keep(SessionModels.GLOBAL);
    }

    private void keep(SessionModels chosen) {
        boolean stored = conversation.sessionId().isPresent();
        request(conversation.chooseModels(chosen), done -> {
            show(chosen);
            String keyless = keylessProvider(chosen);
            if (chosen.isGlobal()) {
                say(I18n.get(PREFIX + "global_saved"), FormTone.GOOD);
            } else if (keyless != null) {
                say(I18n.get(PREFIX + "saved_no_key", keyless), FormTone.WARN);
            } else {
                say(I18n.get(stored ? PREFIX + "saved" : PREFIX + "kept"), FormTone.GOOD);
            }
        }, "Could not store the conversation's models",
                failure -> say(I18n.get(MODELS + "save_failed", describe(failure)), FormTone.BAD));
    }

    private void say(String text, FormTone tone) {
        this.status = text;
        this.statusTone = tone;
    }

    /** Closes the model page of the chat hologram {@code chatHologramId}, if it is open. */
    static void close(String chatHologramId) {
        Holograms.close(idFor(chatHologramId));
    }
}
