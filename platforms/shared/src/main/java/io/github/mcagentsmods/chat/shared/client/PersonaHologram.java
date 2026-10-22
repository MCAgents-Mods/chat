package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.core.api.hologram.Form;
import io.github.mcagentsmods.core.api.hologram.FormArea;
import io.github.mcagentsmods.core.api.hologram.FormButton;
import io.github.mcagentsmods.core.api.hologram.FormText;
import io.github.mcagentsmods.core.api.hologram.FormTone;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import net.minecraft.client.resources.language.I18n;

import java.nio.file.Path;
import java.util.List;

/**
 * The editor for {@code {minecraft_dir}/MCAgents/chat/AGENTS.md}, as a hologram PERSONA opens.
 *
 * <p>The persona is the one thing that changes how every answer reads, so it is one button away
 * from the conversation it governs rather than a file the player has to go and find. The path is
 * shown too, so it can also be edited outside the game.
 *
 * <p><strong>Only SAVE writes.</strong> The text lives in this page until SAVE is pressed;
 * closing the hologram leaves the file exactly as it was, because this is somebody's writing and
 * a page that saved on close would eventually eat a paragraph they were still thinking about.
 * RELOAD reads the file again, dropping the draft. Reading and writing both go through
 * {@link PersonaStore}, the store the agent reads, so a save is in force by the next question.
 */
final class PersonaHologram extends HologramPage {

    /** The hologram's id. */
    static final String ID = "mcagents.persona";

    /**
     * Room for a long persona and then some: this is the front of every request, and a limit
     * that silently cut the player's writing would be far worse than a large file.
     */
    private static final int CHARACTER_LIMIT = 64_000;

    private static final String PREFIX = "gui.mcagents.persona.";

    private final PersonaStore store;
    private final Path file;

    private String draft = "";
    private boolean loaded;
    private String status = "";
    private FormTone tone = FormTone.SOFT;

    private PersonaHologram(PersonaStore store, Path file) {
        this.store = store;
        this.file = file;
    }

    /** Opens the editor on the stored persona. */
    static void open(PersonaStore store, Path file) {
        PersonaHologram page = new PersonaHologram(store, file);
        page.open();
        page.load();
    }

    @Override
    Hologram build() {
        return Hologram.of(ID, new Form(PREFIX + "title", List.of(
                FormText.of(file.toString(), FormTone.SOFT, 2),
                new FormText(this::statusLine, () -> waiting() ? FormTone.SOFT : tone, 1),
                new FormArea(() -> draft, text -> draft = text, PREFIX + "hint", CHARACTER_LIMIT)),
                List.of(new FormButton(PREFIX + "save", () -> loaded && !waiting(), this::save),
                        new FormButton(PREFIX + "reload", () -> !waiting(), this::load))).wide());
    }

    private String statusLine() {
        return waiting() && status.isEmpty() ? I18n.get("gui.mcagents.waiting") : status;
    }

    private void load() {
        say(I18n.get(PREFIX + "loading"), FormTone.SOFT);
        request(store.read(), text -> {
            loaded = true;
            draft = text;
            say("", FormTone.SOFT);
        }, "Could not read the player's instructions",
                failure -> say(I18n.get(PREFIX + "read_failed", describe(failure)), FormTone.BAD));
    }

    private void save() {
        say(I18n.get(PREFIX + "saving"), FormTone.SOFT);
        // The store reloads its own cache as part of saving, so nothing else is told.
        request(store.save(draft), stored -> {
            draft = stored;
            say(I18n.get(PREFIX + "saved"), FormTone.GOOD);
        }, "Could not save the player's instructions",
                failure -> say(I18n.get(PREFIX + "save_failed", describe(failure)), FormTone.BAD));
    }

    private void say(String text, FormTone tone) {
        this.status = text;
        this.tone = tone;
    }
}
