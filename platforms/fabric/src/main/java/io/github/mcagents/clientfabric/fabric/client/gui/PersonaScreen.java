package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.agent.PersonaStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The editor for {@code {minecraft_dir}/MCAgents/AGENTS.md}, in game.
 *
 * <h2>Why this is a screen and not a file the player is told to go and find</h2>
 *
 * <p>The persona is the one thing that changes how every answer reads, and until now the
 * only way to write it was to alt-tab, find the game directory, and open a markdown file in
 * something. A player mid-session does not do that, so in practice nobody edited it. Here
 * it is one button away from the conversation it governs.
 *
 * <h2>The draft is kept, and only Save writes</h2>
 *
 * <p>The text lives in this screen until Save is pressed. Cancel and Escape both leave the
 * file exactly as it was — this is somebody's writing, and a screen that saved on close
 * would eventually eat a paragraph they were still thinking about. Resizing the window
 * rebuilds every widget, so the draft is held outside them and restored.
 *
 * <p>Reading and writing both go through {@link PersonaStore}, which is asynchronous by
 * contract. Nothing here waits on a future; results are applied on the client thread and
 * discarded if the player has already left.
 */
public final class PersonaScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonaScreen.class);

    private static final int MARGIN = 12;
    private static final int LINE = 20;
    private static final int GAP = 6;
    private static final int HEADER_HEIGHT = 34;
    private static final int BUTTON_WIDTH = 100;

    /**
     * Room for a long persona and then some.
     *
     * <p>Generous rather than tight: this is the front of every request, so a player who
     * wants to spend tokens on a page of instructions is spending their own, and a limit
     * that silently truncated their writing would be far worse than a large file.
     */
    private static final int CHARACTER_LIMIT = 64_000;

    private final PersonaStore store;
    private final Path file;
    private final Screen parent;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private MultiLineEditBox editor;
    private Button saveButton;

    /** Held here, not in the widget, so a resize does not lose what has been typed. */
    private String draft = "";
    private boolean loaded;
    private Component status = Component.empty();

    /**
     * @param store  the shared persona store — the same one the agent reads, so a save here
     *               is in force by the next question
     * @param file   where the file lives, shown so a player can also edit it outside the game
     * @param parent the screen to return to
     */
    public PersonaScreen(PersonaStore store, Path file, Screen parent) {
        super(Component.translatable("gui.mcagents.persona.title"));
        this.store = store;
        this.file = file;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int editorTop = MARGIN + HEADER_HEIGHT;
        int editorWidth = Math.max(LINE, width - MARGIN * 2);
        int editorHeight = Math.max(LINE, height - editorTop - MARGIN - LINE - GAP);

        editor = MultiLineEditBox.builder()
                .setX(MARGIN)
                .setY(editorTop)
                .setPlaceholder(Component.translatable("gui.mcagents.persona.hint"))
                .build(font, editorWidth, editorHeight,
                        Component.translatable("gui.mcagents.persona.title"));
        editor.setCharacterLimit(CHARACTER_LIMIT);
        editor.setValue(draft);
        editor.setValueListener(text -> draft = text);
        addRenderableWidget(editor);

        int buttonTop = height - MARGIN - LINE;
        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.persona.save"), button -> save())
                .bounds(MARGIN, buttonTop, BUTTON_WIDTH, LINE)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width - MARGIN - BUTTON_WIDTH, buttonTop, BUTTON_WIDTH, LINE)
                .build());

        setInitialFocus(editor);
        updateSaveState();

        if (!loaded) {
            load();
        }
    }

    private void load() {
        status = Component.translatable("gui.mcagents.persona.loading")
                .withStyle(ChatFormatting.GRAY);
        onClientThread(waiting.track(store.read()), text -> {
            loaded = true;
            draft = text;
            editor.setValue(text);
            status = Component.empty();
        }, "Could not read the player's instructions",
                failure -> status = Component.translatable("gui.mcagents.persona.read_failed",
                        describe(failure)).withStyle(ChatFormatting.RED));
    }

    private void save() {
        if (waiting.isWaiting()) {
            return;
        }
        status = Component.translatable("gui.mcagents.persona.saving")
                .withStyle(ChatFormatting.GRAY);
        // The store reloads its own cache as part of saving, so there is nothing to
        // invalidate here and nothing for the chat screen to be told.
        onClientThread(waiting.track(store.save(draft)), stored -> {
            draft = stored;
            status = Component.translatable("gui.mcagents.persona.saved")
                    .withStyle(ChatFormatting.GREEN);
        }, "Could not save the player's instructions",
                failure -> status = Component.translatable("gui.mcagents.persona.save_failed",
                        describe(failure)).withStyle(ChatFormatting.RED));
    }

    /**
     * Applies {@code onSuccess} on the client thread, and says so on screen when it fails.
     *
     * <p>A failure is shown as well as logged. This screen writes to a file the player
     * chose to edit; "it looked like it worked" is the one outcome a save must never have.
     */
    private <T> void onClientThread(CompletableFuture<T> request, Consumer<T> onSuccess,
                                    String failureMessage, Consumer<Throwable> onFailure) {
        request.whenComplete((result, failure) -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (minecraft == null || minecraft.gui.screen() != this) {
                    return;
                }
                if (failure != null) {
                    LOGGER.error("{}", failureMessage, failure);
                    onFailure.accept(failure);
                    return;
                }
                onSuccess.accept(result);
            });
        });
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                ? failure.getCause()
                : failure;
        if (cause == null) {
            return "unknown";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private void updateSaveState() {
        if (saveButton != null) {
            saveButton.active = loaded && !waiting.isWaiting();
        }
    }

    @Override
    public void tick() {
        super.tick();
        waiting.tick();
        updateSaveState();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.text(font, title, MARGIN, MARGIN, 0xFFFFFFFF);
        // The path, trimmed to the window rather than allowed to run off it: a player who
        // wants to edit this in a text editor needs to know where it is.
        graphics.text(font, Component.literal(
                        font.plainSubstrByWidth(file.toString(), width - MARGIN * 2)),
                MARGIN, MARGIN + 12, 0xFF9A9AA4);

        Component line = waiting.isWaiting()
                ? waiting.label(Component.translatable("gui.mcagents.waiting"))
                : status;
        graphics.text(font, line, MARGIN, MARGIN + 23, 0xFFC0C0C8);
    }

    @Override
    public void onClose() {
        // Deliberately without saving. See the class note.
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
