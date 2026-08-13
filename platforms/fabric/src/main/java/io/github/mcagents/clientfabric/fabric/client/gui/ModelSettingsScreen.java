package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.model.ModelCatalog;
import io.github.mcagents.clientfabric.api.model.ModelPreferences;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Where the player picks the chat model and the embedding model.
 *
 * <p>Both fields are dropdowns that can also be typed into. The suggested lists are a
 * convenience, not a whitelist — nothing here rejects a model id the mod has not heard of,
 * because OpenRouter's catalog moves faster than this mod ships. An id OpenRouter does not
 * serve fails at request time with OpenRouter's own message, which is more useful than
 * anything this screen could invent.
 */
public final class ModelSettingsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelSettingsScreen.class);

    private static final int FIELD_WIDTH = 240;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 30;

    private final LocalDatabase database;
    private final Screen parent;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private EditableDropdown chatModel;
    private EditableDropdown embeddingModel;
    private Button saveButton;

    /** Kept across a resize, which rebuilds every widget from scratch. */
    private ModelPreferences current = ModelPreferences.defaults();
    private boolean loaded;

    public ModelSettingsScreen(LocalDatabase database, Screen parent) {
        super(Component.translatable("gui.mcagents.models.title"));
        this.database = database;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - FIELD_WIDTH) / 2;
        int top = height / 2 - ROW_GAP;

        chatModel = new EditableDropdown(font, ModelCatalog.SUGGESTED_CHAT_MODELS,
                Component.translatable("gui.mcagents.models.chat"));
        chatModel.setBounds(left, top, FIELD_WIDTH, FIELD_HEIGHT);
        chatModel.setValue(current.chatModel());
        addRenderableWidget(chatModel.field());

        embeddingModel = new EditableDropdown(font, ModelCatalog.SUGGESTED_EMBEDDING_MODELS,
                Component.translatable("gui.mcagents.models.embedding"));
        embeddingModel.setBounds(left, top + ROW_GAP + 12, FIELD_WIDTH, FIELD_HEIGHT);
        embeddingModel.setValue(current.embeddingModel());
        addRenderableWidget(embeddingModel.field());

        int buttonWidth = 100;
        int buttonTop = top + ROW_GAP * 2 + 20;

        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.models.save"), button -> save())
                .bounds(left, buttonTop, buttonWidth, FIELD_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.models.reset"), button -> reset())
                .bounds(left + buttonWidth + 8, buttonTop, buttonWidth, FIELD_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(left + FIELD_WIDTH - buttonWidth, buttonTop + ROW_GAP, buttonWidth, FIELD_HEIGHT)
                .build());

        if (!loaded) {
            load();
        }
    }

    private void load() {
        onClientThread(waiting.track(database.settings().loadModelPreferences()), preferences -> {
            current = preferences;
            loaded = true;
            chatModel.setValue(preferences.chatModel());
            embeddingModel.setValue(preferences.embeddingModel());
        }, "Could not read the model preferences");
    }

    private void save() {
        // Blank fields are not an error: ModelPreferences substitutes the catalog default,
        // so "clear it and save" is how a player gets back to the default by hand.
        ModelPreferences requested =
                ModelPreferences.of(chatModel.value(), embeddingModel.value());

        onClientThread(waiting.track(database.settings().saveModelPreferences(requested)), stored -> {
            current = stored;
            // Show what was actually stored, which may differ from what was typed.
            chatModel.setValue(stored.chatModel());
            embeddingModel.setValue(stored.embeddingModel());
        }, "Could not store the model preferences");
    }

    private void reset() {
        chatModel.setValue(ModelCatalog.DEFAULT_CHAT_MODEL);
        embeddingModel.setValue(ModelCatalog.DEFAULT_EMBEDDING_MODEL);
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

        int left = (width - FIELD_WIDTH) / 2;
        graphics.centeredText(font, title, width / 2, height / 2 - ROW_GAP - 34, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("gui.mcagents.models.chat"),
                left, height / 2 - ROW_GAP - 12, 0xFFC0C0C8);
        graphics.text(font, Component.translatable("gui.mcagents.models.embedding"),
                left, height / 2 + 18, 0xFFC0C0C8);

        chatModel.render(graphics, mouseX, mouseY);
        embeddingModel.render(graphics, mouseX, mouseY);

        if (waiting.isWaiting()) {
            graphics.centeredText(font, waiting.label(Component.translatable("gui.mcagents.waiting")),
                    width / 2, height / 2 + ROW_GAP * 2 + 34, 0xFFFFFFFF);
        }

        // Last: an open list floats over everything, including the widgets above.
        chatModel.renderOverlay(graphics, mouseX, mouseY);
        embeddingModel.renderOverlay(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Before super: an open list must get the click rather than what it is covering.
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
