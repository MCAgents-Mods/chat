package io.github.mcagents.clientfabric.common.ui;

/**
 * Where every row of the model settings screen begins.
 *
 * <h2>Why the screen does not work this out itself</h2>
 *
 * <p>It used to. The chat field was placed at {@code height / 2 - ROW_GAP} and each row after
 * it added its own offsets, which is the arrangement that has already drawn a label inside the
 * box below it twice in this project — {@code .agents/memory/tasks/screen-layout.md}. Adding a
 * provider row above the chat model made the sum longer, and a longer sum computed in a class
 * that cannot be tested is the same defect waiting for a third turn.
 *
 * <p>So the arithmetic lives here, built on {@link StackedRows}, where a test runs it with no
 * Minecraft on the classpath. Every row begins where the one before it ended, so two rows
 * cannot be given the same position — there is no way to express one.
 *
 * @param providerLabel  the label above the provider field
 * @param providerField  the provider dropdown
 * @param routeNote      the line saying how that provider is reached
 * @param chatLabel      the label above the chat model field
 * @param chatField      the chat model dropdown
 * @param embeddingLabel the label above the embedding model field
 * @param embeddingField the embedding model dropdown
 * @param toggle         the embedding switch, directly under the field it switches
 * @param buttons        Save and Use defaults, side by side
 * @param done           Done, on its own line
 * @param bottom         where everything ends, which is what {@link #height} measures from
 */
public record ModelSettingsRows(int providerLabel, int providerField, int routeNote,
                                int chatLabel, int chatField,
                                int embeddingLabel, int embeddingField,
                                int toggle, int buttons, int done, int bottom) {

    /**
     * Lays the screen out from the four measurements only the screen knows.
     *
     * @param top         where the first label begins
     * @param labelHeight one line of text, which is the font's line height plus its leading
     * @param fieldHeight a control's height
     * @param spacing     the gap between one row and the next
     */
    public static ModelSettingsRows lay(int top, int labelHeight, int fieldHeight, int spacing) {
        StackedRows rows = new StackedRows(top);

        int providerLabel = rows.add(labelHeight);
        int providerField = rows.add(fieldHeight);
        // Immediately under the field, because it describes that field's current answer and
        // nothing else. No gap: the two are one row read together.
        int routeNote = rows.add(labelHeight);
        rows.gap(spacing);

        int chatLabel = rows.add(labelHeight);
        int chatField = rows.add(fieldHeight);
        rows.gap(spacing);

        int embeddingLabel = rows.add(labelHeight);
        int embeddingField = rows.add(fieldHeight);
        rows.gap(spacing);

        // The switch belongs to the field above it rather than being a setting of its own, so
        // it sits against it with only the ordinary gap between.
        int toggle = rows.add(fieldHeight);
        rows.gap(spacing * 2);

        int buttons = rows.add(fieldHeight);
        rows.gap(spacing);
        int done = rows.add(fieldHeight);

        return new ModelSettingsRows(providerLabel, providerField, routeNote,
                chatLabel, chatField, embeddingLabel, embeddingField,
                toggle, buttons, done, rows.bottom());
    }

    /** How tall the whole block is, so the caller can centre it. */
    public int height(int top) {
        return bottom - top;
    }
}
