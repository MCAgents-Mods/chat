package io.github.mcagentsmods.chat.shared.client.gui;

import io.github.mcagentsmods.chat.api.wallet.ChainAddress;
import io.github.mcagentsmods.chat.api.wallet.Wallet;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * The right panel of the wallet screen: one row per chain the selected wallet holds an
 * address on.
 *
 * <p>The addresses are shortened the way every wallet shortens them — {@code 0x4b470…6BB75}
 * — because forty characters do not fit beside a chain name. That is a layout decision and
 * not a secret: the copy control puts the whole address on the clipboard, and an address
 * the player cannot copy is an address they cannot use.
 */
final class ChainAddressPanel extends ScrollablePanel {

    private static final int ROW_HEIGHT = 12;
    private static final int CONTROL_WIDTH = 10;

    /** How much of the row the chain's name gets before the address starts. */
    private static final int PLATFORM_WIDTH = 62;

    private final Font font;
    private final Consumer<ChainAddress> onCopy;
    private final Consumer<ChainAddress> onDelete;

    private List<ChainAddress> addresses = List.of();
    private boolean hasWallet;

    ChainAddressPanel(Font font, Consumer<ChainAddress> onCopy, Consumer<ChainAddress> onDelete) {
        this.font = font;
        this.onCopy = onCopy;
        this.onDelete = onDelete;
    }

    void setWallet(Wallet wallet) {
        this.hasWallet = wallet != null;
        this.addresses = wallet == null ? List.of() : wallet.addresses();
        scrollToTop();
    }

    @Override
    protected int contentHeight() {
        return addresses.size() * ROW_HEIGHT;
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int contentTop, int mouseX, int mouseY) {
        if (addresses.isEmpty()) {
            Component message = hasWallet
                    ? Component.translatable("gui.mcagents.wallets.address.empty")
                    : Component.translatable("gui.mcagents.wallets.none_selected");
            graphics.text(font, message, x + PADDING, contentTop, TEXT_DIM);
            return;
        }

        for (int i = 0; i < addresses.size(); i++) {
            int rowTop = contentTop + i * ROW_HEIGHT;
            if (rowTop + ROW_HEIGHT < y || rowTop > y + height) {
                continue;
            }
            ChainAddress address = addresses.get(i);
            boolean isHovered = isOver(mouseX, mouseY)
                    && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;

            if (isHovered) {
                graphics.fill(x + 1, rowTop - 1, x + width - 1, rowTop + ROW_HEIGHT - 1, HOVER);
            }

            graphics.text(font,
                    font.plainSubstrByWidth(address.platform().displayName(), PLATFORM_WIDTH - 4),
                    x + PADDING, rowTop, TEXT);

            int addressLeft = x + PADDING + PLATFORM_WIDTH;
            int available = (isHovered ? deleteLeft() - CONTROL_WIDTH : x + width - PADDING)
                    - addressLeft - 4;
            // The tick separates an address proved by signature from one typed in by hand.
            String label = (address.verified() ? "✓ " : "") + address.masked();
            graphics.text(font, font.plainSubstrByWidth(label, Math.max(8, available)),
                    addressLeft, rowTop, address.verified() ? 0xFFB8E0BC : TEXT_DIM);

            if (isHovered) {
                boolean overCopy = mouseX >= copyLeft() && mouseX < deleteLeft();
                boolean overDelete = mouseX >= deleteLeft() && mouseX < x + width - 2;
                graphics.text(font, Component.literal("⧉"),
                        copyLeft(), rowTop, overCopy ? 0xFF9CC4FF : TEXT_DIM);
                graphics.text(font, Component.literal("×"),
                        deleteLeft(), rowTop, overDelete ? 0xFFFF6060 : TEXT_DIM);
            }
        }
    }

    private int deleteLeft() {
        return x + width - CONTROL_WIDTH - 4;
    }

    private int copyLeft() {
        return deleteLeft() - CONTROL_WIDTH;
    }

    /**
     * Copies or removes the address under the cursor.
     *
     * @return whether the click was consumed
     */
    boolean click(double mouseX, double mouseY) {
        if (!isOver(mouseX, mouseY) || addresses.isEmpty()) {
            return false;
        }
        int index = (int) ((mouseY - (y + PADDING) + scrollOffset()) / ROW_HEIGHT);
        if (index < 0 || index >= addresses.size()) {
            return false;
        }
        ChainAddress address = addresses.get(index);

        if (mouseX >= deleteLeft() && mouseX < x + width - 2) {
            onDelete.accept(address);
            return true;
        }
        if (mouseX >= copyLeft() && mouseX < deleteLeft()) {
            onCopy.accept(address);
            return true;
        }
        // Anywhere else on the row copies too: the whole reason to open this screen is to
        // get an address onto the clipboard, and hunting for a ten-pixel icon to do it is
        // the kind of thing that makes a player type it out by hand instead.
        onCopy.accept(address);
        return true;
    }
}
