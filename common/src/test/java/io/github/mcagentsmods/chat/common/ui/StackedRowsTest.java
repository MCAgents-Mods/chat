package io.github.mcagentsmods.chat.common.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind a screen that does not draw one thing on top of another.
 *
 * <p>These exist because the web console screen shipped with its status line six pixels
 * above a button row, so pressing a button replaced the message explaining what it had done
 * with the button itself. That is a sum, and a sum can be checked without a running game —
 * which matters here, because nothing else in this repository can look at a Minecraft
 * screen.
 */
class StackedRowsTest {

    /** Roughly what the console screen stacks: text, wrapped text, two button rows, status. */
    private static StackedRows consoleLayout(int top, int lineHeight, int urlLines, int hintLines) {
        StackedRows rows = new StackedRows(top);
        rows.add(lineHeight);                 // title
        rows.add(lineHeight);                 // subtitle
        rows.gap(10);
        rows.add(lineHeight);                 // running / not running
        rows.add(urlLines * lineHeight);      // the address
        rows.gap(10);
        rows.add(hintLines * lineHeight);     // the hint
        rows.gap(10);
        rows.add(46);                         // both button rows
        rows.gap(10);
        rows.add(lineHeight);                 // the status
        return rows;
    }

    /** The heights the layout above hands to each row, in the same order. */
    private static List<Integer> consoleHeights(int lineHeight, int urlLines, int hintLines) {
        return List.of(lineHeight, lineHeight, lineHeight, urlLines * lineHeight,
                hintLines * lineHeight, 46, lineHeight);
    }

    @Test
    void neverOverlapsOneRowWithTheNext() {
        // The defect, as a property: a row ends at or before the next one starts, for every
        // shape of content the screen can be asked to show. Stated as "ends before" rather
        // than "starts below" so that a row with nothing in it -- the address, when the
        // console is stopped -- is allowed to take no space at all.
        for (int urlLines = 0; urlLines <= 4; urlLines++) {
            for (int hintLines = 1; hintLines <= 4; hintLines++) {
                List<Integer> tops = consoleLayout(0, 10, urlLines, hintLines).tops();
                List<Integer> heights = consoleHeights(10, urlLines, hintLines);

                for (int i = 0; i < tops.size() - 1; i++) {
                    int bottom = tops.get(i) + heights.get(i);
                    assertTrue(bottom <= tops.get(i + 1),
                            "url=" + urlLines + " hint=" + hintLines
                                    + " ran row " + i + " to " + bottom
                                    + ", past row " + (i + 1) + " at " + tops.get(i + 1));
                }
            }
        }
    }

    @Test
    void keepsTheStatusClearOfTheButtonsHoweverLongTheTextIs() {
        // The exact failure: the status used to sit above a 46-pixel button block that began
        // six pixels below it. Here it is after, by construction, whatever the rows above do.
        for (int hintLines = 1; hintLines <= 6; hintLines++) {
            List<Integer> tops = consoleLayout(0, 10, 3, hintLines).tops();
            int buttonTop = tops.get(5);
            int statusTop = tops.get(6);

            assertTrue(statusTop >= buttonTop + 46,
                    "hint=" + hintLines + " left the status at " + statusTop
                            + ", inside a button block starting at " + buttonTop);
        }
    }

    @Test
    void growsTheBlockWhenTextWraps() {
        // A layout that ignored wrapping would report the same height for one line and four,
        // which is how text ends up outside the space reserved for it.
        int oneLine = consoleLayout(0, 10, 1, 1).heightFrom(0);
        int manyLines = consoleLayout(0, 10, 3, 4).heightFrom(0);

        assertEquals(50, manyLines - oneLine, "five extra wrapped lines at ten pixels each");
    }

    @Test
    void givesARowThatIsNotShownNoHeightOfItsOwn() {
        // The address is absent when the console is stopped. It still occupies a position,
        // so the indices stay stable, but it costs nothing — the rows below move up by
        // exactly the lines it would have taken.
        List<Integer> running = consoleLayout(0, 10, 2, 2).tops();
        List<Integer> stopped = consoleLayout(0, 10, 0, 2).tops();

        assertEquals(running.get(3), stopped.get(3), "the empty row starts where it would have");
        for (int i = 4; i < running.size(); i++) {
            assertEquals(running.get(i) - 20, stopped.get(i),
                    "row " + i + " should move up by the two lines the address is not using");
        }
    }

    @Test
    void centresTheBlockInTheWindow() {
        assertEquals(30, StackedRows.centred(200, 140, 8));
    }

    @Test
    void startsATallBlockOnScreenRatherThanAboveIt() {
        // A block taller than the window has to lose something. Losing the bottom is
        // recoverable — the player can resize — and losing the top means the title and the
        // address are the parts nobody can read.
        assertEquals(8, StackedRows.centred(120, 400, 8));
    }

    @Test
    void reportsWhereTheNextRowWouldStart() {
        StackedRows rows = new StackedRows(20);
        rows.add(10);
        rows.gap(5);
        rows.add(30);

        assertEquals(65, rows.bottom());
        assertEquals(45, rows.heightFrom(20));
    }

    @Test
    void refusesANegativeHeight() {
        // A negative row would move the cursor backwards and put the next row on top of it,
        // which is the one thing this class exists to make impossible.
        assertThrows(IllegalArgumentException.class, () -> new StackedRows(0).add(-1));
        assertThrows(IllegalArgumentException.class, () -> new StackedRows(0).gap(-1));
    }
}
