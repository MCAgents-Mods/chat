package io.github.mcagentsmods.chat.shared.client.gui;

import io.github.mcagentsmods.chat.api.wallet.Wallet;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * One account's column on the wallet screen: every wallet in it.
 *
 * <p>Drawn by hand rather than built from widgets, for the same reason
 * {@link SessionListPanel} is — the rows are replaced wholesale whenever the account
 * changes, and a list of registered widgets would have to be rebuilt every time.
 *
 * <p>There is one of these per account now rather than one on the screen, which is what
 * lets every account be visible at once. Each carries the name of the account it draws, so
 * a column says which account it is without the screen having to label it from outside.
 */
final class WalletListPanel extends ScrollablePanel {

    private static final int ROW_HEIGHT = 12;

    /** Width of each control at the right of a row: rename, then delete. */
    private static final int CONTROL_WIDTH = 10;

    private final Font font;
    private final Consumer<Wallet> onSelect;
    private final Consumer<Wallet> onRename;
    private final Consumer<Wallet> onDelete;

    private List<Wallet> wallets = List.of();
    private String selectedId;
    private String caption = "";
    private boolean loading = true;

    WalletListPanel(Font font, Consumer<Wallet> onSelect,
                    Consumer<Wallet> onRename, Consumer<Wallet> onDelete) {
        this.font = font;
        this.onSelect = onSelect;
        this.onRename = onRename;
        this.onDelete = onDelete;
    }

    void setWallets(List<Wallet> wallets) {
        this.wallets = List.copyOf(wallets);
        this.loading = false;
        if (wallets.stream().noneMatch(wallet -> wallet.id().equals(selectedId))) {
            selectedId = null;
        }
        scrollToTop();
    }

    void setLoading() {
        this.loading = true;
    }

    /** The account name written above the box. */
    void setCaption(String caption) {
        this.caption = caption == null ? "" : caption;
    }

    /**
     * Draws the caption, then the box.
     *
     * <p>Above the frame rather than inside it, because a heading that scrolled away with
     * the rows would leave a column of wallets belonging to nobody in particular. Drawn
     * after {@code super} so it is outside the scissor the panel clips its rows with.
     */
    @Override
    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.render(graphics, mouseX, mouseY);
        if (!caption.isEmpty()) {
            graphics.text(font, Component.literal(
                            font.plainSubstrByWidth(caption, width - PADDING * 2)),
                    x + PADDING, y - ROW_HEIGHT, TEXT);
        }
    }

    void select(String walletId) {
        this.selectedId = walletId;
    }

    @Override
    protected int contentHeight() {
        return wallets.size() * ROW_HEIGHT;
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int contentTop, int mouseX, int mouseY) {
        if (wallets.isEmpty()) {
            Component message = loading
                    ? Component.translatable("gui.mcagents.wallets.loading")
                    : Component.translatable("gui.mcagents.wallets.empty");
            graphics.text(font, message, x + PADDING, contentTop, TEXT_DIM);
            return;
        }

        int textWidth = width - PADDING * 2 - 4;
        for (int i = 0; i < wallets.size(); i++) {
            int rowTop = contentTop + i * ROW_HEIGHT;
            if (rowTop + ROW_HEIGHT < y || rowTop > y + height) {
                continue;
            }
            Wallet wallet = wallets.get(i);
            boolean isSelected = wallet.id().equals(selectedId);
            boolean isHovered = isOver(mouseX, mouseY)
                    && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;

            if (isSelected) {
                graphics.fill(x + 1, rowTop - 1, x + width - 1, rowTop + ROW_HEIGHT - 1, HIGHLIGHT);
            } else if (isHovered) {
                graphics.fill(x + 1, rowTop - 1, x + width - 1, rowTop + ROW_HEIGHT - 1, HOVER);
            }

            // The tick says the player proved this wallet by signing rather than typing it
            // in. Without it a recorded address and a proved one look identical.
            String label = (wallet.hasVerifiedAddress() ? "✓ " : "")
                    + wallet.label()
                    + (wallet.addresses().isEmpty() ? "" : " (" + wallet.addresses().size() + ")");
            int available = isHovered ? textWidth - CONTROL_WIDTH * 2 : textWidth;
            int colour = isSelected ? 0xFFFFFFFF : wallet.active() ? TEXT : TEXT_DIM;
            graphics.text(font, font.plainSubstrByWidth(label, available), x + PADDING, rowTop, colour);

            // The delete control appears only under the cursor: a permanent row of crosses
            // beside every wallet invites the one click that cannot be undone.
            if (isHovered) {
                boolean overRename = mouseX >= renameLeft() && mouseX < deleteLeft();
                boolean overDelete = mouseX >= deleteLeft() && mouseX < x + width - 2;
                graphics.text(font, Component.literal("✎"),
                        renameLeft(), rowTop, overRename ? 0xFF9CC4FF : TEXT_DIM);
                graphics.text(font, Component.literal("×"),
                        deleteLeft(), rowTop, overDelete ? 0xFFFF6060 : TEXT_DIM);
            }
        }
    }

    private int deleteLeft() {
        return x + width - CONTROL_WIDTH - 4;
    }

    private int renameLeft() {
        return deleteLeft() - CONTROL_WIDTH;
    }

    /**
     * Acts on the wallet under the cursor: the cross deletes, the pencil renames, and
     * anything else selects.
     *
     * @return whether the click was consumed
     */
    boolean click(double mouseX, double mouseY, boolean doubleClick) {
        if (!isOver(mouseX, mouseY) || wallets.isEmpty()) {
            return false;
        }
        int index = (int) ((mouseY - (y + PADDING) + scrollOffset()) / ROW_HEIGHT);
        if (index < 0 || index >= wallets.size()) {
            return false;
        }
        Wallet wallet = wallets.get(index);

        if (mouseX >= deleteLeft() && mouseX < x + width - 2) {
            onDelete.accept(wallet);
            return true;
        }
        if (mouseX >= renameLeft() && mouseX < deleteLeft()) {
            onRename.accept(wallet);
            return true;
        }

        selectedId = wallet.id();
        onSelect.accept(wallet);
        if (doubleClick) {
            onRename.accept(wallet);
        }
        return true;
    }
}
