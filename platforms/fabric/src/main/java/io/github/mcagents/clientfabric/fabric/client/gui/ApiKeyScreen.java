package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.model.ApiKey;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Where the player adds and removes OpenRouter API keys.
 *
 * <p>Without this there was no way to give the mod a key at all, which made the agent
 * permanently unable to answer — the model client was complete and unreachable.
 *
 * <h2>Keys are never shown in full</h2>
 *
 * <p>Stored keys are listed through {@link ApiKey#masked()}, never as their whole value.
 * The full value leaves the mod only in an {@code Authorization} header, and a player
 * streaming or screenshotting this screen must not leak their key by opening it. The entry
 * box is cleared the moment a key is saved for the same reason. See
 * {@code .agents/security/api-keys.md}.
 */
public final class ApiKeyScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyScreen.class);

    private static final int FIELD_WIDTH = 260;
    private static final int LINE = 20;
    private static final int ROW_HEIGHT = 22;

    private final LocalDatabase database;
    private final Screen parent;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private EditBox entryBox;
    private List<ApiKey> keys = List.of();

    public ApiKeyScreen(LocalDatabase database, Screen parent) {
        super(Component.translatable("gui.mcagents.keys.title"));
        this.database = database;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - FIELD_WIDTH) / 2;
        int top = 40;

        entryBox = new EditBox(font, left, top, FIELD_WIDTH - 60, LINE,
                Component.translatable("gui.mcagents.keys.add"));
        entryBox.setHint(Component.translatable("gui.mcagents.keys.add"));
        // Long enough for any provider key; the value itself is never rendered back.
        entryBox.setMaxLength(400);
        addRenderableWidget(entryBox);

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.keys.save"), button -> addKey())
                .bounds(left + FIELD_WIDTH - 56, top, 56, LINE)
                .build());

        // One row per stored key, each with its own remove button.
        int rowTop = top + ROW_HEIGHT + 12;
        for (int i = 0; i < keys.size() && rowTop < height - 60; i++) {
            ApiKey key = keys.get(i);
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.mcagents.keys.remove"),
                            button -> removeKey(key))
                    .bounds(left + FIELD_WIDTH - 56, rowTop, 56, LINE)
                    .build());
            rowTop += ROW_HEIGHT;
        }

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(left + FIELD_WIDTH - 100, height - 34, 100, LINE)
                .build());

        loadKeys();
    }

    private void loadKeys() {
        onClientThread(waiting.track(database.apiKeys().findAll()), stored -> {
            keys = stored;
            rebuildWidgets();
        }, "Could not read the stored API keys");
    }

    private void addKey() {
        String value = entryBox.getValue().trim();
        if (value.isEmpty()) {
            return;
        }
        // Cleared immediately: the key must not sit in a visible text box after it has been
        // stored, where it would survive into a screenshot or a stream.
        entryBox.setValue("");

        onClientThread(waiting.track(database.apiKeys().add(value)), stored -> loadKeys(),
                "Could not store the API key");
    }

    private void removeKey(ApiKey key) {
        onClientThread(waiting.track(database.apiKeys().remove(key.value())),
                removed -> loadKeys(), "Could not remove the API key");
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
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int left = (width - FIELD_WIDTH) / 2;
        graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);

        if (keys.isEmpty()) {
            graphics.text(font, Component.translatable("gui.mcagents.keys.empty")
                    .withStyle(ChatFormatting.GRAY), left, 40 + ROW_HEIGHT + 16, 0xFF9A9AA4);
        }

        int rowTop = 40 + ROW_HEIGHT + 12;
        for (int i = 0; i < keys.size() && rowTop < height - 60; i++) {
            ApiKey key = keys.get(i);
            // masked(), never the whole value.
            Component label = Component.literal(key.masked())
                    .withStyle(key.active() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
            graphics.text(font, label, left, rowTop + 6, 0xFFFFFFFF);
            if (!key.active()) {
                graphics.text(font, Component.translatable("gui.mcagents.keys.inactive")
                                .withStyle(ChatFormatting.RED),
                        left + 90, rowTop + 6, 0xFFFF8080);
            }
            rowTop += ROW_HEIGHT;
        }

        if (waiting.isWaiting()) {
            graphics.centeredText(font, waiting.label(Component.translatable("gui.mcagents.waiting")),
                    width / 2, height - 52, 0xFFFFFFFF);
        }
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
