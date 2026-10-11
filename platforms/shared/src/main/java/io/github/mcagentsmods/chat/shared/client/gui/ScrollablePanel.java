package io.github.mcagentsmods.chat.shared.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The bordered, scrollable, clipped rectangle both panels are drawn inside.
 *
 * <p>Scrolling is kept here rather than in each panel because getting it wrong is what
 * makes a list feel broken: the offset must be clamped whenever the content changes size,
 * not only when the wheel moves, or a list that shrinks leaves the player staring at empty
 * space with no way back.
 */
abstract class ScrollablePanel {

    protected static final int BACKGROUND = 0xC0101014;
    protected static final int BORDER = 0xFF3A3A42;
    protected static final int TEXT = 0xFFE0E0E0;
    protected static final int TEXT_DIM = 0xFF9A9AA4;
    protected static final int HIGHLIGHT = 0xFF2F6FB8;
    protected static final int HOVER = 0x40FFFFFF;

    protected static final int PADDING = 4;

    /** Pixels moved per wheel notch. */
    private static final int SCROLL_STEP = 12;

    protected int x;
    protected int y;
    protected int width;
    protected int height;

    private int scroll;

    void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        clampScroll();
    }

    /** Total height of the content, which decides how far it can scroll. */
    protected abstract int contentHeight();

    /** Draws the content, already translated by the scroll offset and clipped. */
    protected abstract void renderContent(GuiGraphicsExtractor graphics, int contentTop, int mouseX, int mouseY);

    /** Draws the frame, then the clipped content. */
    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.fill(x, y, x + width, y + 1, BORDER);
        graphics.fill(x, y + height - 1, x + width, y + height, BORDER);
        graphics.fill(x, y, x + 1, y + height, BORDER);
        graphics.fill(x + width - 1, y, x + width, y + height, BORDER);

        clampScroll();

        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        renderContent(graphics, y + PADDING - scroll, mouseX, mouseY);
        graphics.disableScissor();

        renderScrollbar(graphics);
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics) {
        int content = contentHeight();
        int visible = visibleHeight();
        if (content <= visible) {
            return;
        }
        int trackHeight = height - 2;
        int thumbHeight = Math.max(16, trackHeight * visible / content);
        int travel = trackHeight - thumbHeight;
        int thumbTop = y + 1 + (travel * scroll / maxScroll());

        graphics.fill(x + width - 4, thumbTop, x + width - 1, thumbTop + thumbHeight, BORDER);
    }

    boolean isOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    boolean scrollBy(double notches) {
        int previous = scroll;
        scroll -= (int) (notches * SCROLL_STEP);
        clampScroll();
        return scroll != previous;
    }

    /** Jumps to the bottom — what a conversation should do when a message arrives. */
    void scrollToBottom() {
        scroll = maxScroll();
    }

    /** Jumps to the top — what a list should do when its contents are replaced. */
    void scrollToTop() {
        scroll = 0;
    }

    protected int visibleHeight() {
        return Math.max(0, height - PADDING * 2);
    }

    protected int scrollOffset() {
        return scroll;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - visibleHeight());
    }

    private void clampScroll() {
        scroll = Math.clamp(scroll, 0, maxScroll());
    }
}
