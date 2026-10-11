package io.github.mcagentsmods.chat.fabric.client.gui;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.ModelName;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.agent.StoredProviderRoutes;
import io.github.mcagentsmods.chat.common.ui.ModelSettingsRows;
import io.github.mcagentsmods.chat.common.ui.StackedRows;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Where the player picks the chat model and the embedding model.
 *
 * <p>Every field is a dropdown that can also be typed into. The suggested lists are a
 * convenience, not a whitelist — nothing here rejects a model id the mod has not heard of,
 * because OpenRouter's catalog moves faster than this mod ships. An id OpenRouter does not
 * serve fails at request time with OpenRouter's own message, which is more useful than
 * anything this screen could invent.
 *
 * <h2>The provider row</h2>
 *
 * <p>Above the chat model, because it is the shorter way of asking the same question: a stored
 * id names both who serves the model and which model it is, so picking a provider rewrites the
 * id through {@link ModelCatalog#chatModelOn}, the one rule the console uses for the same
 * choice. The field below shows the result immediately — nothing is guessed at save time.
 *
 * <p>The two stay in step in both directions: typing a model id moves the provider field to
 * that id's platform, so the provider box is never stale, and a provider that differs from the
 * id's platform can therefore only mean the player changed it deliberately.
 *
 * <p>Under it, whether that provider is reached directly or through the gateway — answered by
 * {@link StoredProviderRoutes}, the same code the real request routes through, rather than by
 * this screen reasoning about keys and addresses on its own.
 */
public final class ModelSettingsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelSettingsScreen.class);

    private static final int FIELD_WIDTH = 240;
    private static final int FIELD_HEIGHT = 20;
    /** Vertical space reserved for a field's label, so the two never collide. */
    private static final int LABEL_HEIGHT = 11;
    private static final int ROW_SPACING = 8;
    /** The highest the block may start, leaving the title its own line above everything. */
    private static final int MINIMUM_TOP = 36;

    private final LocalDatabase database;
    private final Screen parent;
    private final WaitingIndicator waiting = new WaitingIndicator();
    private final StoredProviderRoutes routes;

    private EditableDropdown provider;
    private EditableDropdown chatModel;
    private EditableDropdown embeddingModel;
    private Button embeddingToggle;
    private Button saveButton;

    /**
     * Where the layout actually put things.
     *
     * <p>Recorded rather than recomputed. The labels used to derive their own positions
     * from {@code height / 2}, which drifted out of step with the fields and drew the
     * embedding label on top of the embedding box.
     */
    private int contentLeft;
    private ModelSettingsRows rows = ModelSettingsRows.lay(
            MINIMUM_TOP, LABEL_HEIGHT, FIELD_HEIGHT, ROW_SPACING);

    /** Kept across a resize, which rebuilds every widget from scratch. */
    private ModelPreferences current = ModelPreferences.defaults();
    private boolean loaded;

    /**
     * The platforms the player already holds a key for, offered alongside the ones this
     * release knows.
     *
     * <p>Read once with the preferences and kept, because the list is fixed when a dropdown is
     * built: a rebuild is what puts a newly stored platform on it, and there is exactly one.
     */
    private List<String> platformsInUse = List.of();

    /** How the chosen provider is reached, or empty until the answer arrives. */
    private Component routeNote = Component.empty();

    public ModelSettingsScreen(LocalDatabase database, Screen parent) {
        super(Component.translatable("gui.mcagents.models.title"));
        this.database = database;
        this.parent = parent;
        this.routes = new StoredProviderRoutes(database.settings(), database.apiKeys());
    }

    @Override
    protected void init() {
        contentLeft = (width - FIELD_WIDTH) / 2;
        int left = contentLeft;
        // Every row's position comes from one stacked layout rather than from each control
        // computing its own offset. Centred where the window allows it and pinned below the
        // title where it does not, so a short window loses the bottom rather than the heading.
        int total = ModelSettingsRows.lay(0, LABEL_HEIGHT, FIELD_HEIGHT, ROW_SPACING).bottom();
        rows = ModelSettingsRows.lay(StackedRows.centred(height, total, MINIMUM_TOP),
                LABEL_HEIGHT, FIELD_HEIGHT, ROW_SPACING);

        // Who answers. The gateway is not offered: it is the route everything falls back to,
        // not a platform that serves models, and openrouter/gpt-4o-mini is a model nobody
        // serves rather than a longer spelling of gpt-4o-mini.
        provider = new EditableDropdown(font, ModelCatalog.offeredModelPlatforms(platformsInUse),
                Component.translatable("gui.mcagents.models.provider"));
        provider.setBounds(left, rows.providerField(), FIELD_WIDTH, FIELD_HEIGHT);
        provider.setValue(platformOf(current.chatModel()));
        addRenderableWidget(provider.field());

        chatModel = new EditableDropdown(font, ModelCatalog.SUGGESTED_CHAT_MODELS,
                Component.translatable("gui.mcagents.models.chat"));
        chatModel.setBounds(left, rows.chatField(), FIELD_WIDTH, FIELD_HEIGHT);
        chatModel.setValue(current.chatModel());
        // Typing an id moves the provider field to that id's platform, so the provider box is
        // never stale — which is what makes "the provider differs from the id's platform" mean
        // exactly one thing: the player changed it on purpose.
        chatModel.field().setResponder(text -> provider.setValue(platformOf(text)));
        addRenderableWidget(chatModel.field());

        embeddingModel = new EditableDropdown(font, ModelCatalog.SUGGESTED_EMBEDDING_MODELS,
                Component.translatable("gui.mcagents.models.embedding"));
        embeddingModel.setBounds(left, rows.embeddingField(), FIELD_WIDTH, FIELD_HEIGHT);
        embeddingModel.setValue(current.embeddingModel());
        addRenderableWidget(embeddingModel.field());

        // Directly under the embedding field, because it is that field's on/off switch and
        // not a third setting. Full width for the same reason.
        embeddingToggle = addRenderableWidget(Button.builder(
                        embeddingToggleLabel(), button -> toggleEmbedding())
                .bounds(left, rows.toggle(), FIELD_WIDTH, FIELD_HEIGHT)
                .build());
        applyEmbeddingToggle();

        int buttonWidth = 100;

        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.models.save"), button -> save())
                .bounds(left, rows.buttons(), buttonWidth, FIELD_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.models.reset"), button -> reset())
                .bounds(left + buttonWidth + 8, rows.buttons(), buttonWidth, FIELD_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(left + FIELD_WIDTH - buttonWidth, rows.done(), buttonWidth, FIELD_HEIGHT)
                .build());

        if (!loaded) {
            load();
        }
    }

    /**
     * Reads the preferences and the platforms the player already has keys for, together.
     *
     * <p>One read and one rebuild: a dropdown's list is fixed when it is built, so the
     * platforms have to be known before {@code init} runs again, and two reads each rebuilding
     * would rebuild twice. The keys are read for their platform names only — no value is read
     * back, and none could be shown by this screen if it were.
     */
    private void load() {
        onClientThread(waiting.track(database.settings().loadModelPreferences()
                        .thenCombine(database.apiKeys().findAll(), Loaded::new)), state -> {
            current = state.preferences();
            platformsInUse = state.keys().stream().map(ApiKey::platform).distinct().toList();
            loaded = true;
            // Rebuilt rather than assigned into: the provider dropdown's list now has entries
            // it did not have when it was built.
            rebuildWidgets();
            showRoute(current.chatModel());
        }, "Could not read the model preferences");
    }

    /** The two answers {@link #load()} waits for, so one rebuild sees both. */
    private record Loaded(ModelPreferences preferences, java.util.List<ApiKey> keys) {
    }

    /** The platform half of a stored id, or blank when it names none. */
    private static String platformOf(String model) {
        return ModelName.parse(model).map(ModelName::platform).orElse("");
    }

    /**
     * Puts the chat model on {@code platform}, by the same rule the console applies.
     *
     * <p>Applied when a provider is picked rather than at save time, so the model id that
     * results is visible before anything is stored — and so a player who types an id directly
     * keeps it, since nothing rewrites the field behind them.
     */
    private void applyProvider(String platform) {
        if (platform == null || platform.isBlank()) {
            return;
        }
        String next = ModelCatalog.chatModelOn(platform, chatModel.value());
        chatModel.setValue(next);
        // Back from the id, not from what was picked: they differ when the rule declined to
        // move, and the field must show where the model actually is.
        provider.setValue(platformOf(next));
        showRoute(next);
    }

    /**
     * Asks how {@code model} would be reached, and says so under the provider field.
     *
     * <p>Routed through {@link StoredProviderRoutes} — the same code the request itself goes
     * through — so this line cannot claim a direct call the mod would not actually make.
     */
    private void showRoute(String model) {
        onClientThread(routes.route(model), route -> {
            routeNote = route.isThroughOpenRouter()
                    ? Component.translatable("gui.mcagents.models.route.gateway")
                            .withStyle(ChatFormatting.GRAY)
                    : Component.translatable("gui.mcagents.models.route.direct", route.platform())
                            .withStyle(ChatFormatting.GREEN);
        }, "Could not work out how the provider is reached");
    }

    /**
     * Flips the switch in this screen's state.
     *
     * <p>Not written to storage here. Every other control on this screen takes effect on
     * Save, and a switch that saved itself would be the one control that behaved
     * differently — a player who flipped it to see what it said would have changed their
     * settings by looking.
     */
    private void toggleEmbedding() {
        current = current.withEmbeddingEnabled(!current.embeddingEnabled());
        applyEmbeddingToggle();
    }

    /** Puts the switch's state on the button: its words, its colour, and its explanation. */
    private void applyEmbeddingToggle() {
        if (embeddingToggle == null) {
            return;
        }
        boolean enabled = current.embeddingEnabled();
        embeddingToggle.setMessage(embeddingToggleLabel());
        embeddingToggle.setTooltip(Tooltip.create(Component.translatable(enabled
                ? "gui.mcagents.models.embedding.tip.on"
                : "gui.mcagents.models.embedding.tip.off")));
        // The embedding model still matters when the switch is off — it is what retrieval
        // will use when it comes back on — so the field stays editable rather than greyed.
    }

    /**
     * The button's words.
     *
     * <p>They say which state the switch is <em>in</em>, not what pressing it would do. The
     * colour repeats that rather than carrying it: a player who cannot separate the two
     * colours still reads the word.
     */
    private Component embeddingToggleLabel() {
        boolean enabled = current.embeddingEnabled();
        return Component.translatable(enabled
                        ? "gui.mcagents.models.embedding.on"
                        : "gui.mcagents.models.embedding.off")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private void save() {
        // Blank fields are not an error: ModelPreferences substitutes the catalog default,
        // so "clear it and save" is how a player gets back to the default by hand.
        // The switch is carried through from what was loaded. This screen does not offer it
        // yet, and a save that dropped it would turn retrieval back on for a player who had
        // turned it off somewhere else.
        // A provider typed rather than picked is honoured here, and only here: the field
        // mirrors the id's platform as it is typed, so a difference means the player edited
        // the provider box and nothing else can produce one.
        if (!provider.value().equalsIgnoreCase(platformOf(chatModel.value()))) {
            applyProvider(provider.value());
        }

        ModelPreferences requested = ModelPreferences.of(
                chatModel.value(), embeddingModel.value(), current.embeddingEnabled());

        onClientThread(waiting.track(database.settings().saveModelPreferences(requested)), stored -> {
            current = stored;
            // Show what was actually stored, which may differ from what was typed.
            chatModel.setValue(stored.chatModel());
            provider.setValue(platformOf(stored.chatModel()));
            embeddingModel.setValue(stored.embeddingModel());
            applyEmbeddingToggle();
            showRoute(stored.chatModel());
        }, "Could not store the model preferences");
    }

    private void reset() {
        chatModel.setValue(ModelCatalog.DEFAULT_CHAT_MODEL);
        provider.setValue(platformOf(ModelCatalog.DEFAULT_CHAT_MODEL));
        embeddingModel.setValue(ModelCatalog.DEFAULT_EMBEDDING_MODEL);
        current = current.withEmbeddingEnabled(ModelPreferences.DEFAULT_EMBEDDING_ENABLED);
        applyEmbeddingToggle();
        showRoute(ModelCatalog.DEFAULT_CHAT_MODEL);
        provider.close();
        chatModel.close();
        embeddingModel.close();
    }

    private <T> void onClientThread(CompletableFuture<T> request, Consumer<T> onSuccess,
                                    String failureMessage) {
        request.whenComplete((result, failure) -> {
            if (failure != null) {
                LOGGER.error("{}", failureMessage, failure);
                return;
            }
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (minecraft != null && minecraft.gui.screen() == this) {
                    onSuccess.accept(result);
                }
            });
        });
    }

    @Override
    public void tick() {
        super.tick();
        waiting.tick();
        if (saveButton != null) {
            saveButton.active = !waiting.isWaiting();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(font, title, width / 2, rows.providerLabel() - 20, 0xFFFFFFFF);
        // Every label sits on the row the layout gave it, so none can land inside a box.
        graphics.text(font, Component.translatable("gui.mcagents.models.provider"),
                contentLeft, rows.providerLabel(), 0xFFC0C0C8);
        graphics.text(font, routeNote, contentLeft, rows.routeNote() + 1, 0xFF9A9AA4);
        graphics.text(font, Component.translatable("gui.mcagents.models.chat"),
                contentLeft, rows.chatLabel(), 0xFFC0C0C8);
        graphics.text(font, Component.translatable("gui.mcagents.models.embedding"),
                contentLeft, rows.embeddingLabel(), 0xFFC0C0C8);

        provider.render(graphics, mouseX, mouseY);
        chatModel.render(graphics, mouseX, mouseY);
        embeddingModel.render(graphics, mouseX, mouseY);

        if (waiting.isWaiting()) {
            // Under everything the layout placed, which is the one line nothing else claims.
            graphics.centeredText(font, waiting.label(Component.translatable("gui.mcagents.waiting")),
                    width / 2, rows.bottom() + ROW_SPACING, 0xFFFFFFFF);
        }

        // Last: an open list floats over everything, including the widgets above.
        provider.renderOverlay(graphics, mouseX, mouseY);
        chatModel.renderOverlay(graphics, mouseX, mouseY);
        embeddingModel.renderOverlay(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Before super: an open list must get the click rather than what it is covering.
        if (provider.clickedOverlay(event)) {
            // A row was picked, or the list was opened or dismissed. Reading the field back
            // covers all three: applying the same platform twice is a no-op, because the first
            // rule in chatModelOn is that a platform already serving the model keeps it.
            applyProvider(provider.value());
            return true;
        }
        if (chatModel.clickedOverlay(event) || embeddingModel.clickedOverlay(event)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
