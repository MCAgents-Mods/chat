package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.core.api.hologram.Form;
import io.github.mcagentsmods.core.api.hologram.FormButton;
import io.github.mcagentsmods.core.api.hologram.FormField;
import io.github.mcagentsmods.core.api.hologram.FormText;
import io.github.mcagentsmods.core.api.hologram.FormTone;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.client.hologram.Holograms;

import java.util.List;
import java.util.function.Consumer;

/**
 * The small holograms chat's pages ask through: a name to type, or a yes to an action that
 * cannot be undone. One stands at a time, beside the hologram that asked, which stays open; a
 * second question replaces the first. The dialog closes itself once answered, and its X
 * answers no.
 *
 * <p>Call every method on the game's thread.
 */
public final class HologramDialogs {

    /** The dialog's hologram id; one dialog stands at a time. */
    public static final String ID = "mcagents.dialog";

    private static final String CANCEL = "gui.cancel";
    private static final String NAME = "gui.mcagents.dialog.name";

    private HologramDialogs() {
        throw new AssertionError("No instances.");
    }

    /**
     * Asks for a name.
     *
     * @param title   the dialog's title, a translation key or the text itself
     * @param subject what is being named, shown under the title; blank for nothing
     * @param initial what the box starts with
     * @param save    the label of the button that answers
     * @param onSave  called with the name, trimmed, when the player presses {@code save}
     */
    public static void name(String title, String subject, String initial, String save,
                            Consumer<String> onSave) {
        String[] draft = {initial};
        Holograms.open(Hologram.of(ID, new Form(title, List.of(
                FormText.of(subject, FormTone.SOFT, 2),
                FormField.of(NAME, () -> draft[0], text -> draft[0] = text).withMaxLength(255)),
                List.of(FormButton.of(save, () -> {
                            Holograms.close(ID);
                            onSave.accept(draft[0].trim());
                        }),
                        FormButton.of(CANCEL, () -> Holograms.close(ID))))));
    }

    /**
     * Asks before an action that cannot be undone.
     *
     * @param title     the dialog's title, a translation key or the text itself
     * @param message   what will happen, already translated
     * @param confirm   the label of the button that goes ahead
     * @param onConfirm what to do when the player presses {@code confirm}
     */
    public static void confirm(String title, String message, String confirm, Runnable onConfirm) {
        Holograms.open(Hologram.of(ID, new Form(title, List.of(
                FormText.of(message, FormTone.TEXT, 6)),
                List.of(FormButton.of(confirm, () -> {
                            Holograms.close(ID);
                            onConfirm.run();
                        }),
                        FormButton.of(CANCEL, () -> Holograms.close(ID))))));
    }
}
