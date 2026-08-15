package io.github.mcagents.clientfabric.common.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Laying accounts out as columns across a screen.
 *
 * <h2>Why this is not in the screen that uses it</h2>
 *
 * <p>The same reason as {@link StackedRows}: the wallet screen used to show one account at a
 * time behind a dropdown, and showing them all at once is arithmetic — how many columns fit,
 * how wide each is, which accounts are on screen when there are more than fit. Arithmetic
 * does not need Minecraft to be wrong, and the Fabric module has no tests, so a mistake in
 * it could only be found by looking at it.
 *
 * <p>Nothing here knows what a font, a panel, or a wallet is. It is given widths and counts
 * and it returns widths and counts.
 *
 * <h2>The rules it enforces</h2>
 *
 * <ul>
 *   <li><strong>Columns never overlap and never leave a gap at the end.</strong> They divide
 *       the space exactly, so a remainder that does not divide evenly is spread over the
 *       earliest columns rather than left as a ragged strip on the right.</li>
 *   <li><strong>There is always at least one column.</strong> A window too narrow for the
 *       minimum shows one cramped account rather than none at all.</li>
 *   <li><strong>The window over the accounts stays inside the list.</strong> Deleting the
 *       last account cannot leave the screen looking at where it used to be.</li>
 * </ul>
 */
public final class AccountColumns {

    private AccountColumns() {
        throw new AssertionError("No instances.");
    }

    /** Where one column starts and how wide it is. */
    public record Column(int left, int width) {

        public Column {
            if (width < 0) {
                throw new IllegalArgumentException("A column cannot be narrower than nothing");
            }
        }

        /** One past the last pixel this column covers. */
        public int right() {
            return left + width;
        }
    }

    /**
     * How many columns of at least {@code minWidth} fit, with {@code gap} between them.
     *
     * <p>Never zero. A window too narrow for even one column at the minimum gets one column
     * anyway: an account drawn cramped can still be read and acted on, and no accounts at
     * all is a screen that looks broken.
     */
    public static int fit(int available, int minWidth, int gap) {
        if (minWidth <= 0) {
            throw new IllegalArgumentException("A column needs a minimum width");
        }
        if (gap < 0) {
            throw new IllegalArgumentException("A gap cannot be negative: " + gap);
        }
        // (available + gap) because n columns have n-1 gaps, not n.
        return Math.max(1, (available + gap) / (minWidth + gap));
    }

    /**
     * Divides {@code available} into {@code count} columns, exactly.
     *
     * <p>The pixels left over when the width does not divide evenly go one each to the
     * earliest columns. Dropping them instead would leave a strip on the right that belongs
     * to nothing, which looks like the layout has run out rather than like a rounding.
     */
    public static List<Column> divide(int left, int available, int count, int gap) {
        if (count <= 0) {
            throw new IllegalArgumentException("There has to be at least one column");
        }
        if (gap < 0) {
            throw new IllegalArgumentException("A gap cannot be negative: " + gap);
        }

        int usable = Math.max(0, available - gap * (count - 1));
        int base = usable / count;
        int remainder = usable % count;

        List<Column> columns = new ArrayList<>(count);
        int cursor = left;
        for (int index = 0; index < count; index++) {
            int width = base + (index < remainder ? 1 : 0);
            columns.add(new Column(cursor, width));
            cursor += width + gap;
        }
        return List.copyOf(columns);
    }

    /**
     * How tall a list showing exactly {@code rows} rows is.
     *
     * @param padding the panel's own padding, counted at the top and the bottom
     */
    public static int listHeight(int rows, int rowHeight, int padding) {
        if (rows < 0 || rowHeight < 0 || padding < 0) {
            throw new IllegalArgumentException("A list cannot have negative dimensions");
        }
        return rows * rowHeight + padding * 2;
    }

    /**
     * Keeps the window over the accounts inside the list of them.
     *
     * <p>Two things it stops: scrolling past the end, and — the one that actually happened —
     * a window left pointing beyond a list that has since got shorter, so deleting the last
     * account showed an empty screen with accounts still in it.
     *
     * @param first   the first account the screen wants to show
     * @param total   how many accounts there are
     * @param perPage how many columns fit on screen
     * @return a start that shows a full page wherever one is available, and {@code 0} when
     *         everything fits at once
     */
    public static int clampFirst(int first, int total, int perPage) {
        if (perPage <= 0) {
            throw new IllegalArgumentException("A page has to hold at least one column");
        }
        int last = Math.max(0, total - perPage);
        return Math.clamp(first, 0, last);
    }
}
