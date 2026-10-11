package io.github.mcagentsmods.chat.common.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Stacks rows of known height and reports where each one starts.
 *
 * <h2>Why this is not in the screen that uses it</h2>
 *
 * <p>A screen laid out with constants — {@code height / 2 + 16} for the buttons,
 * {@code height / 2 + 10} for the status under them — put a line of text six pixels above a
 * control and drew it underneath. That defect is arithmetic, and arithmetic does not need
 * Minecraft to be wrong or to be tested.
 *
 * <p>So the sums live here, where a test can run them, and the screen supplies the two
 * things only it knows: the font's line height and how many lines its text wrapped into.
 * Nothing in this class knows what a font is.
 *
 * <h2>The rule it enforces</h2>
 *
 * <p>Every row begins where the one before it ended. There is no way to express "put this at
 * y" and therefore no way to express two things at the same y, which is the only reason the
 * original could collide.
 */
public final class StackedRows {

    private final List<Integer> tops = new ArrayList<>();
    private int cursor;

    /** @param top where the first row begins */
    public StackedRows(int top) {
        this.cursor = top;
    }

    /**
     * Places a row and returns where it starts.
     *
     * @param height how tall it is; zero is allowed, for a row that is not being shown
     */
    public int add(int height) {
        if (height < 0) {
            throw new IllegalArgumentException("A row cannot have a negative height: " + height);
        }
        int top = cursor;
        tops.add(top);
        cursor += height;
        return top;
    }

    /** Leaves a gap without placing a row in it. */
    public StackedRows gap(int height) {
        if (height < 0) {
            throw new IllegalArgumentException("A gap cannot be negative: " + height);
        }
        cursor += height;
        return this;
    }

    /** Where the next row would start, which is also the bottom of everything placed. */
    public int bottom() {
        return cursor;
    }

    /** How tall everything placed so far is, gaps included. */
    public int heightFrom(int top) {
        return cursor - top;
    }

    /** Every row's top, in the order they were added. */
    public List<Integer> tops() {
        return List.copyOf(tops);
    }

    /**
     * The top that centres a block of {@code total} height in {@code available}.
     *
     * <p>Clamped to {@code minimum} so a block taller than the window starts on screen and
     * runs off the bottom, rather than starting above the top where the title would be the
     * part nobody can read.
     */
    public static int centred(int available, int total, int minimum) {
        return Math.max(minimum, (available - total) / 2);
    }
}
