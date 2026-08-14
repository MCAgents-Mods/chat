package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.model.ChatSession;
import io.github.mcagents.clientfabric.api.wallet.Wallet;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Renames one thing: a conversation, or a wallet.
 *
 * <p>A whole screen for one text field, because the field is easier to find here than as an
 * edit box that appears in place: the pencil beside a row says what it does before it is
 * clicked, and a row that quietly turns editable does not.
 *
 * <p>Nothing here knows what it is renaming — it is handed a title, the current name, and
 * somewhere to send the new one. That is why one screen serves both lists rather than each
 * growing its own copy.
 */
public final class RenameScreen extends Screen {

    private static final int FIELD_WIDTH = 240;
    private static final int LINE = 20;

    private final String subject;
    private final Component saveLabel;
    private final Screen parent;
    private final Consumer<String> onRename;

    private EditBox nameBox;
    private String draft;

    private RenameScreen(Component title, Component saveLabel, String subject,
                         Screen parent, Consumer<String> onRename) {
        super(title);
        this.saveLabel = saveLabel;
        this.subject = subject;
        this.parent = parent;
        this.onRename = onRename;
        this.draft = subject;
    }

    /** Renames a conversation. */
    public static RenameScreen forSession(ChatSession session, Screen parent,
                                          Consumer<String> onRename) {
        return new RenameScreen(
                Component.translatable("gui.mcagents.session.rename"),
                Component.translatable("gui.mcagents.session.rename.save"),
                session.title(), parent, onRename);
    }

    /** Renames a wallet. */
    public static RenameScreen forWallet(Wallet wallet, Screen parent,
                                         Consumer<String> onRename) {
        return new RenameScreen(
                Component.translatable("gui.mcagents.wallets.rename"),
                Component.translatable("gui.mcagents.wallets.rename.save"),
                wallet.label(), parent, onRename);
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
        // The player came here to type; the cursor belongs in the field.
        setInitialFocus(nameBox);
        nameBox.moveCursorToEnd(false);

        int buttonWidth = 100;
        addRenderableWidget(Button.builder(saveLabel, button -> commit())
                .bounds(left, top + LINE + 12, buttonWidth, LINE)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + FIELD_WIDTH - buttonWidth, top + LINE + 12, buttonWidth, LINE)
                .build());
    }

    private void commit() {
        // A blank name is not an error; the repository substitutes the default, which is
        // how a player clears a name they no longer want.
        onRename.accept(draft.trim());
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
                Component.literal(font.plainSubstrByWidth(subject, FIELD_WIDTH)),
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
