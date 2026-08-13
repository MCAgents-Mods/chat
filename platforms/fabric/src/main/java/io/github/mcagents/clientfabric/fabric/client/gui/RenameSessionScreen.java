package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.model.ChatSession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Renames one conversation.
 *
 * <p>A whole screen for one text field, because the field is easier to find here than as an
 * edit box that appears in place: the pencil beside a conversation says what it does before
 * it is clicked, and a row that quietly turns editable does not.
 */
public final class RenameSessionScreen extends Screen {

    private static final int FIELD_WIDTH = 240;
    private static final int LINE = 20;

    private final ChatSession session;
    private final Screen parent;
    private final Consumer<String> onRename;

    private EditBox nameBox;
    private String draft;

    public RenameSessionScreen(ChatSession session, Screen parent, Consumer<String> onRename) {
        super(Component.translatable("gui.mcagents.session.rename"));
        this.session = session;
        this.parent = parent;
        this.onRename = onRename;
        this.draft = session.title();
    }

    @Override
    protected void init() {
        int left = (width - FIELD_WIDTH) / 2;
        int top = height / 2 - 20;

        nameBox = new EditBox(font, left, top, FIELD_WIDTH, LINE, title);
        nameBox.setMaxLength(255);
        nameBox.setValue(draft);
        nameBox.setResponder(text -> draft = text);
        addRenderableWidget(nameBox);
        // The player came here to type; the cursor belongs in the field, and the existing
        // title is selected so it can be replaced without clearing it first.
        setInitialFocus(nameBox);
        nameBox.moveCursorToEnd(false);

        int buttonWidth = 100;
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.session.rename.save"),
                        button -> commit())
                .bounds(left, top + LINE + 12, buttonWidth, LINE)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + FIELD_WIDTH - buttonWidth, top + LINE + 12, buttonWidth, LINE)
                .build());
    }

    private void commit() {
        String title = draft.trim();
        // A blank title is not an error; the repository substitutes the default, which is
        // how a player clears a name they no longer want.
        onRename.accept(title);
        onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
            commit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 46, 0xFFFFFFFF);
        graphics.centeredText(font,
                Component.literal(font.plainSubstrByWidth(session.title(), FIELD_WIDTH)),
                width / 2, height / 2 - 34, 0xFF9A9AA4);
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
