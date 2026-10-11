package io.github.mcagentsmods.chat.common;

import io.github.mcagentsmods.chat.common.ui.ModelSettingsRows;
import io.github.mcagentsmods.chat.common.ui.StackedRows;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model settings screen's layout, run without Minecraft.
 *
 * <p>The screen gained a provider row above the chat model, which is the kind of change that
 * has twice drawn a label inside the box below it — see
 * {@code .agents/memory/tasks/screen-layout.md}. The sums are here so that a third time fails
 * a test instead of a player's screen.
 */
class ModelSettingsRowsTest {

    private static final int LABEL = 11;
    private static final int FIELD = 20;
    private static final int SPACING = 8;

    @Test
    void putsEveryRowBelowTheOneBeforeIt() {
        ModelSettingsRows rows = ModelSettingsRows.lay(40, LABEL, FIELD, SPACING);

        List<Integer> tops = List.of(rows.providerLabel(), rows.providerField(), rows.routeNote(),
                rows.chatLabel(), rows.chatField(), rows.embeddingLabel(), rows.embeddingField(),
                rows.toggle(), rows.buttons(), rows.done());

        for (int i = 1; i < tops.size(); i++) {
            assertTrue(tops.get(i) > tops.get(i - 1),
                    "row " + i + " starts at " + tops.get(i) + ", which is not below "
                            + tops.get(i - 1));
        }
        assertTrue(rows.bottom() >= tops.get(tops.size() - 1) + FIELD,
                "the block ends below its last row");
    }

    @Test
    void neverLetsALabelLandInsideTheFieldItNames() {
        ModelSettingsRows rows = ModelSettingsRows.lay(40, LABEL, FIELD, SPACING);

        assertTrue(rows.providerLabel() + LABEL <= rows.providerField(),
                "the provider label ends before its field begins");
        assertTrue(rows.chatLabel() + LABEL <= rows.chatField());
        assertTrue(rows.embeddingLabel() + LABEL <= rows.embeddingField());
        assertTrue(rows.providerField() + FIELD <= rows.routeNote(),
                "the route line clears the field it describes");
    }

    @Test
    void keepsTheSwitchAgainstTheFieldItSwitches() {
        ModelSettingsRows rows = ModelSettingsRows.lay(40, LABEL, FIELD, SPACING);

        assertEquals(rows.embeddingField() + FIELD + SPACING, rows.toggle(),
                "one gap, so the switch reads as part of the embedding row and not as a "
                        + "setting of its own");
    }

    @Test
    void measuresTheSameHeightWhereverItStarts() {
        // What the screen centres with: the height cannot depend on the top, or centring it
        // would move it.
        int fromZero = ModelSettingsRows.lay(0, LABEL, FIELD, SPACING).bottom();
        ModelSettingsRows lower = ModelSettingsRows.lay(120, LABEL, FIELD, SPACING);

        assertEquals(fromZero, lower.height(120));
        assertEquals(120, lower.providerLabel(), "and it starts exactly where it was told to");
    }

    @Test
    void startsBelowTheTitleRatherThanOffTheTopOfAShortWindow() {
        int total = ModelSettingsRows.lay(0, LABEL, FIELD, SPACING).bottom();

        assertEquals(36, StackedRows.centred(120, total, 36),
                "a window too short for the block keeps the heading and loses the bottom");
        assertTrue(StackedRows.centred(400, total, 36) > 36, "a tall window centres it");
    }
}
