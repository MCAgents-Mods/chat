package io.github.mcagentsmods.chat.shared.client.gui;

import io.github.mcagentsmods.chat.api.wallet.ChainPlatform;
import io.github.mcagentsmods.chat.api.wallet.Wallet;
import io.github.mcagentsmods.chat.common.wallet.AddressFormats;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Adds one chain address to a wallet by hand.
 *
 * <h2>Why typing one in is allowed at all</h2>
 *
 * <p>MetaMask can prove an EVM address to the mod, because the browser can sign with it.
 * It cannot do the same for the Bitcoin, Solana, and Tron addresses it shows on the very
 * same screen — the extension offers a web page no way to sign with those. So those
 * addresses are typed in, recorded as unproved, and shown as such. Refusing them instead
 * would mean the mod could not hold most of what a player's wallet actually contains.
 *
 * <h2>The one thing this screen refuses</h2>
 *
 * <p>A private key or a recovery phrase. Nothing in the mod ever needs one, so an input
 * that looks like one is a player being asked for the wrong thing — by a scam or by their
 * own confusion — and is turned away with an explanation rather than stored.
 */
public final class AddAddressScreen extends Screen {

    private static final int FIELD_WIDTH = 280;
    private static final int LINE = 20;
    private static final int LABEL_HEIGHT = 11;
    private static final int ROW_SPACING = 10;

    private final Wallet wallet;
    private final Screen parent;
    private final BiConsumer<ChainPlatform, String> onAdd;

    private EditableDropdown platformBox;
    private EditBox addressBox;
    private Button saveButton;

    /** Kept across a resize, which rebuilds every widget from scratch. */
    private ChainPlatform platform = ChainPlatform.ETHEREUM;
    private String draft = "";

    private int contentLeft;
    private int platformTop;
    private int addressTop;

    public AddAddressScreen(Wallet wallet, Screen parent,
                            BiConsumer<ChainPlatform, String> onAdd) {
        super(Component.translatable("gui.mcagents.wallets.address.add"));
        this.wallet = wallet;
        this.parent = parent;
        this.onAdd = onAdd;
    }

    @Override
    protected void init() {
        contentLeft = (width - FIELD_WIDTH) / 2;
        platformTop = height / 2 - 40;
        addressTop = platformTop + LINE + LABEL_HEIGHT + ROW_SPACING;

        platformBox = new EditableDropdown(font, displayNames(),
                Component.translatable("gui.mcagents.wallets.address.chain"));
        platformBox.setBounds(contentLeft, platformTop, FIELD_WIDTH, LINE);
        platformBox.setValue(platform.displayName());
        addRenderableWidget(platformBox.field());

        addressBox = new EditBox(font, contentLeft, addressTop, FIELD_WIDTH, LINE,
                Component.translatable("gui.mcagents.wallets.address.hint"));
        addressBox.setHint(Component.translatable("gui.mcagents.wallets.address.hint"));
        // Long enough for any public address; nothing longer is one.
        addressBox.setMaxLength(255);
        addressBox.setValue(draft);
        addressBox.setResponder(text -> draft = text);
        addRenderableWidget(addressBox);
        setInitialFocus(addressBox);

        int buttonWidth = 100;
        int buttonTop = addressTop + LINE + ROW_SPACING * 2;

        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.address.save"),
                        button -> commit())
                .bounds(contentLeft, buttonTop, buttonWidth, LINE)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"), button -> onClose())
                .bounds(contentLeft + FIELD_WIDTH - buttonWidth, buttonTop, buttonWidth, LINE)
                .build());
    }

    private void commit() {
        ChainPlatform chosen = selectedPlatform();
        String value = addressBox.getValue().trim();
        if (AddressFormats.problem(chosen, value).isPresent()) {
            return;
        }
        // Stored the way the chain writes it: EVM addresses in checksum form so the mod and
        // MetaMask show the same casing, everything else exactly as given.
        onAdd.accept(chosen, AddressFormats.normalise(chosen, value));
        onClose();
    }

    /**
     * The chain the dropdown is showing.
     *
     * <p>The field is editable and always authoritative, so a value that matches nothing on
     * the list falls back to {@link ChainPlatform#OTHER} — which is the escape hatch for a
     * chain this release has never heard of.
     */
    private ChainPlatform selectedPlatform() {
        String typed = platformBox.value().trim();
        return Arrays.stream(ChainPlatform.values())
                .filter(candidate -> candidate.displayName().equalsIgnoreCase(typed)
                        || candidate.name().equalsIgnoreCase(typed.replace(' ', '_')))
                .findFirst()
                .orElse(ChainPlatform.OTHER);
    }

    private static List<String> displayNames() {
        return Arrays.stream(ChainPlatform.values()).map(ChainPlatform::displayName).toList();
    }

    @Override
    public void tick() {
        super.tick();
        platform = selectedPlatform();
        if (saveButton != null && addressBox != null) {
            saveButton.active =
                    AddressFormats.problem(platform, addressBox.getValue().trim()).isEmpty();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(font, title, width / 2, platformTop - LABEL_HEIGHT - 26, 0xFFFFFFFF);
        graphics.centeredText(font,
                Component.translatable("gui.mcagents.wallets.address.into", wallet.label()),
                width / 2, platformTop - LABEL_HEIGHT - 14, 0xFF9A9AA4);

        // Anchored to the fields themselves, so a label can never land inside its box.
        graphics.text(font, Component.translatable("gui.mcagents.wallets.address.chain"),
                contentLeft, platformTop - LABEL_HEIGHT, 0xFFC0C0C8);
        graphics.text(font, Component.translatable("gui.mcagents.wallets.address.label"),
                contentLeft, addressTop - LABEL_HEIGHT, 0xFFC0C0C8);

        // Said as it is typed rather than on save: a message that appears only after a
        // rejected click is a message the player has already stopped looking for.
        Optional<String> problem =
                AddressFormats.problem(platform, addressBox.getValue().trim());
        int noticeTop = addressTop + LINE + 4;
        if (addressBox.getValue().isBlank()) {
            graphics.text(font, wrapped(Component.translatable(
                            "gui.mcagents.wallets.address.public_only").getString()),
                    contentLeft, noticeTop, 0xFF9A9AA4);
        } else {
            problem.ifPresentOrElse(
                    reason -> graphics.text(font, wrapped(reason), contentLeft, noticeTop, 0xFFFF8080),
                    () -> graphics.text(font,
                            Component.translatable("gui.mcagents.wallets.address.looks_right"),
                            contentLeft, noticeTop, 0xFF7FD18A));
        }

        platformBox.render(graphics, mouseX, mouseY);
        // Last: an open list floats over everything, including the widgets above it.
        platformBox.renderOverlay(graphics, mouseX, mouseY);
    }

    /** Trims a sentence to the width of the fields, since these run long. */
    private String wrapped(String message) {
        return font.plainSubstrByWidth(message, FIELD_WIDTH);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (platformBox.clickedOverlay(event)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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
