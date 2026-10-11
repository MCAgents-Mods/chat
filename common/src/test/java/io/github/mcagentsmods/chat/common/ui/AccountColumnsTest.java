package io.github.mcagentsmods.chat.common.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column arithmetic behind the wallet screen.
 *
 * <p>Tested here because the platform modules have no test source set: a screen laid out by hand
 * can only be checked by looking at it, so the sums it depends on are pulled out to where a
 * test can run them.
 */
class AccountColumnsTest {

    @Test
    void fitsAsManyColumnsAsTheWidthAllows() {
        // Three 100-wide columns and the two 10-wide gaps between them is 320.
        assertEquals(3, AccountColumns.fit(320, 100, 10));
        assertEquals(3, AccountColumns.fit(400, 100, 10), "and the spare goes to widening them");
    }

    @Test
    void countsGapsBetweenColumnsAndNotAfterTheLast() {
        // Two columns need one gap, not two. Counting the trailing one drops a column that
        // would have fitted.
        assertEquals(2, AccountColumns.fit(210, 100, 10));
    }

    @Test
    void alwaysGivesAtLeastOneColumn() {
        // A window narrower than the minimum shows one cramped account. Zero columns is a
        // screen that looks broken while the accounts are still there.
        assertEquals(1, AccountColumns.fit(40, 100, 10));
        assertEquals(1, AccountColumns.fit(0, 100, 10));
    }

    @Test
    void dividesTheWidthWithNothingLeftOver() {
        List<AccountColumns.Column> columns = AccountColumns.divide(8, 300, 4, 6);

        assertEquals(8, columns.getFirst().left(), "the first starts where it was told to");
        assertEquals(8 + 300, columns.getLast().right(),
                "and the last ends exactly at the far edge, with no ragged strip");
    }

    @Test
    void spreadsAnUnevenRemainderOverTheEarliestColumns() {
        // 100 across three columns is 33 each with one pixel over. Dropping it would leave a
        // gap on the right that belongs to nothing.
        List<AccountColumns.Column> columns = AccountColumns.divide(0, 100, 3, 0);

        assertEquals(List.of(34, 33, 33), columns.stream().map(AccountColumns.Column::width).toList());
        assertEquals(100, columns.getLast().right());
    }

    @Test
    void neverOverlapsOneColumnWithTheNext() {
        // The property, rather than the numbers: whatever the width, a row of columns has to
        // be readable as separate columns.
        for (int width = 60; width <= 600; width += 7) {
            for (int count = 1; count <= 5; count++) {
                List<AccountColumns.Column> columns = AccountColumns.divide(4, width, count, 5);
                for (int i = 1; i < columns.size(); i++) {
                    assertTrue(columns.get(i).left() >= columns.get(i - 1).right(),
                            "column " + i + " starts before column " + (i - 1) + " ends at width "
                                    + width + " for " + count + " columns");
                }
            }
        }
    }

    @Test
    void givesAColumnNoNegativeWidthWhenTheGapsAloneOverflow() {
        // Four columns with 20-pixel gaps need 60 pixels of gap before any content. A window
        // of 30 is not a reason to compute a negative width and draw a rectangle inside out.
        List<AccountColumns.Column> columns = AccountColumns.divide(0, 30, 4, 20);

        columns.forEach(column -> assertTrue(column.width() >= 0, column.toString()));
    }

    @Test
    void sizesAListToTheNumberOfRowsAsked() {
        // Five rows of twelve, plus the panel's four pixels of padding at each end.
        assertEquals(68, AccountColumns.listHeight(5, 12, 4));
    }

    @Test
    void keepsTheWindowInsideTheAccountsThatExist() {
        assertEquals(0, AccountColumns.clampFirst(0, 8, 3));
        assertEquals(5, AccountColumns.clampFirst(6, 8, 3), "the last page still shows three");
        assertEquals(0, AccountColumns.clampFirst(2, 3, 3), "everything fits, so there is no window");
    }

    @Test
    void bringsTheWindowBackWhenTheListGetsShorter() {
        // Deleting the last account used to leave the screen looking past the end of the list
        // — accounts still there, and a blank screen.
        assertEquals(0, AccountColumns.clampFirst(4, 2, 3));
    }

    @Test
    void refusesDimensionsThatCannotMeanAnything() {
        assertThrows(IllegalArgumentException.class, () -> AccountColumns.fit(300, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> AccountColumns.divide(0, 300, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> AccountColumns.divide(0, 300, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> AccountColumns.clampFirst(0, 4, 0));
    }
}
