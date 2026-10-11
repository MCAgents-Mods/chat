package io.github.mcagentsmods.chat.shared.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A text field with a list of suggestions attached to it.
 *
 * <p>Minecraft has a cycling button and it has a text box, and neither is what a model
 * picker needs: OpenRouter adds and retires models faster than this mod ships, so the
 * player must be able to pick a known one <em>or</em> type an id nobody here has heard of.
 * A cycling button forbids the second; a bare text box makes the first tedious.
 *
 * <p>So this is both. The field is always editable and always authoritative. The dropdown
 * writes into it and closes; it never constrains what the field may contain, and nothing
 * validates the value against the list.
 *
 * <p>The list is drawn over whatever is beneath it, which means it has to be rendered after
 * the rest of the screen and offered the click before anything under it. The owning screen
 * handles both — see {@link #renderOverlay} and {@link #clickedOverlay}.
 */
final class EditableDropdown {

    private static final int ROW_HEIGHT = 12;
    private static final int MAX_VISIBLE_ROWS = 6;

    private static final int BACKGROUND = 0xF0101014;
    private static final int BORDER = 0xFF3A3A42;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int HOVER = 0xFF2F6FB8;

    private final Font font;
    private final List<String> options;
    private final EditBox field;
    private final int toggleWidth = 12;

    private int x;
    private int y;
    private int width;
    private int height;
    private boolean open;

    EditableDropdown(Font font, List<String> options, Component hint) {
        this.font = font;
        this.options = List.copyOf(options);
        this.field = new EditBox(font, 0, 0, 10, 20, hint);
        this.field.setHint(hint);
        // Long enough for any provider/model id anyone is likely to type.
        this.field.setMaxLength(200);
    }

    /** The editable field, so the screen can register it as a widget. */
    EditBox field() {
        return field;
    }

    void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        field.setX(x);
        field.setY(y);
        field.setWidth(width - toggleWidth);
    }

    String value() {
        return field.getValue().trim();
    }

    void setValue(String value) {
        field.setValue(value == null ? "" : value);
    }

    void close() {
        open = false;
    }

    boolean isOpen() {
        return open;
    }

    /** Draws the toggle. The list itself is drawn by {@link #renderOverlay}. */
    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int toggleLeft = x + width - toggleWidth;
        boolean hovered = isOverToggle(mouseX, mouseY);

        graphics.fill(toggleLeft, y, x + width, y + height, hovered ? HOVER : BORDER);
        graphics.centeredText(font, Component.literal(open ? "▴" : "▾"),
                toggleLeft + toggleWidth / 2, y + (height - font.lineHeight) / 2 + 1, TEXT);
    }

    /**
     * Draws the open list.
     *
     * <p>Called after everything else on the screen: the list floats over the widgets below
     * it, and drawing it in place would put it behind them.
     */
    void renderOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!open || options.isEmpty()) {
            return;
        }
        int rows = Math.min(options.size(), MAX_VISIBLE_ROWS);
        int listTop = y + height;
        int listBottom = listTop + rows * ROW_HEIGHT + 2;

        graphics.fill(x, listTop, x + width, listBottom, BACKGROUND);
        graphics.fill(x, listTop, x + width, listTop + 1, BORDER);
        graphics.fill(x, listBottom - 1, x + width, listBottom, BORDER);
        graphics.fill(x, listTop, x + 1, listBottom, BORDER);
        graphics.fill(x + width - 1, listTop, x + width, listBottom, BORDER);

        String current = value();
        for (int i = 0; i < rows; i++) {
            int rowTop = listTop + 1 + i * ROW_HEIGHT;
            String option = options.get(i);
            boolean hovered = mouseX >= x && mouseX < x + width
                    && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;

            if (hovered) {
                graphics.fill(x + 1, rowTop, x + width - 1, rowTop + ROW_HEIGHT, HOVER);
            }
            // The current value is marked, not enforced: it may not be on this list at all.
            String label = (option.equals(current) ? "✓ " : "  ") + option;
            graphics.text(font, font.plainSubstrByWidth(label, width - 8), x + 3, rowTop + 2, TEXT);
        }
    }

    /**
     * Offers a click to the toggle and, when open, to the list.
     *
     * <p>The screen calls this <em>before</em> its own widgets, so an open list gets the
     * click rather than whatever it happens to be covering.
     *
     * @return whether the click was consumed
     */
    boolean clickedOverlay(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();

        if (isOverToggle(mouseX, mouseY)) {
            open = !open;
            return true;
        }
        if (!open) {
            return false;
        }

        int rows = Math.min(options.size(), MAX_VISIBLE_ROWS);
        int listTop = y + height + 1;
        if (mouseX >= x && mouseX < x + width && mouseY >= listTop && mouseY < listTop + rows * ROW_HEIGHT) {
            int index = (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < rows) {
                setValue(options.get(index));
            }
            open = false;
            return true;
        }

        // A click anywhere else closes the list without changing the value, and is not
        // consumed — the player was reaching for whatever is underneath.
        open = false;
        return false;
    }

    private boolean isOverToggle(double mouseX, double mouseY) {
        return mouseX >= x + width - toggleWidth && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }
}
