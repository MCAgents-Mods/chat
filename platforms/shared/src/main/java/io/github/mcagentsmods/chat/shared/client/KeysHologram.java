package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.core.api.hologram.Form;
import io.github.mcagentsmods.core.api.hologram.FormButton;
import io.github.mcagentsmods.core.api.hologram.FormField;
import io.github.mcagentsmods.core.api.hologram.FormText;
import io.github.mcagentsmods.core.api.hologram.FormTone;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.hologram.HologramButton;
import io.github.mcagentsmods.core.api.hologram.SelectList;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.common.model.Models;
import net.minecraft.client.resources.language.I18n;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Where the player adds and removes API keys, as a hologram KEYS opens: an Add Key form beside
 * the list of stored keys.
 *
 * <h2>A key carries the platform it opens</h2>
 *
 * <p>The platform field defaults to {@code openrouter}, what every key was and what a player
 * who ignores it keeps getting. Its arrow offers the platforms from
 * {@link ModelCatalog#offeredPlatforms}, the ones this release knows plus the ones the player
 * already holds keys for, then the custom providers on the Models page; the list suggests and
 * never constrains, so a platform this release has never heard of is typed straight in and
 * stored as typed.
 *
 * <p>Where a platform is called is set on the Models page, as a custom provider, and nowhere
 * here: two places to set one address would disagree.
 *
 * <h2>Keys are never shown in full</h2>
 *
 * <p>The key field draws stars, the stored keys are listed through {@link ApiKey#masked()}, and
 * the key field is emptied the moment ADD is pressed, so a player streaming or screenshotting
 * this page does not leak a key by opening it. The line at the bottom says whether
 * {@value TokenCipher#KEY_VARIABLE} is set, and so whether stored keys are encrypted, and never
 * implies more. See {@code .agents/security/api-keys.md}.
 */
final class KeysHologram extends HologramPage {

    /** The hologram's id. */
    static final String ID = "mcagents.keys";

    private static final String PREFIX = "gui.mcagents.keys.";

    /** Long enough for any provider key; the value itself is never drawn back. */
    private static final int KEY_LENGTH = 400;

    /** How much of a platform name a stored key's row shows. */
    private static final int PLATFORM_CHARS = 12;

    private final LocalDatabase database;

    private String entry = "";
    private String platform = ModelPlatform.OPENROUTER;
    private List<ApiKey> keys = List.of();
    private int selected = -1;

    private String note = "";
    private FormTone noteTone = FormTone.SOFT;

    private KeysHologram(LocalDatabase database) {
        this.database = database;
    }

    /** Opens the page and reads the stored keys into it. */
    static void open(LocalDatabase database) {
        KeysHologram page = new KeysHologram(database);
        page.open();
        page.loadKeys();
    }

    @Override
    Hologram build() {
        Form form = new Form(PREFIX + "title", List.of(
                FormField.of(PREFIX + "key", () -> entry, text -> entry = text)
                        .withHint(PREFIX + "add")
                        .withMaxLength(KEY_LENGTH)
                        .hidden(),
                FormField.of(PREFIX + "platform.label", () -> platform, text -> platform = text)
                        .withHint(PREFIX + "platform")
                        .withChoices(this::offeredPlatforms),
                FormText.of(PREFIX + "address.moved", FormTone.SOFT, 2),
                new FormText(this::noteLine, () -> noteTone, 2),
                new FormText(this::encryptionLine, () -> FormTone.SOFT, 4)),
                List.of(new FormButton(PREFIX + "save", () -> !entry.isBlank(), this::addKey)))
                .wide();
        SelectList stored = new SelectList(PREFIX + "stored", this::rows, () -> selected,
                index -> selected = index,
                List.of(new HologramButton(PREFIX + "remove", this::removeSelected)));
        return Hologram.of(ID, form, stored);
    }

    /** The platforms this release knows and the player holds keys for, then the custom providers. */
    private List<String> offeredPlatforms() {
        LinkedHashSet<String> offered = new LinkedHashSet<>(ModelCatalog.offeredPlatforms(
                keys.stream().map(ApiKey::platform).distinct().toList()));
        offered.addAll(Models.global().providerIds());
        return List.copyOf(offered);
    }

    /** One row per stored key: masked, never the whole value, then its platform. */
    private List<String> rows() {
        return keys.stream().map(key -> key.masked() + " " + shorten(key.platform())
                + (key.active() ? "" : " " + I18n.get(PREFIX + "inactive"))).toList();
    }

    private String noteLine() {
        if (waiting()) {
            return I18n.get("gui.mcagents.waiting");
        }
        if (!note.isEmpty()) {
            return note;
        }
        return keys.isEmpty() ? I18n.get(PREFIX + "empty") : "";
    }

    private String encryptionLine() {
        return I18n.get(TokenCipher.fromEnvironment().enabled()
                ? PREFIX + "encrypted"
                : PREFIX + "not_encrypted", TokenCipher.KEY_VARIABLE);
    }

    private void loadKeys() {
        request(database.apiKeys().findAll(), stored -> {
            keys = stored;
            if (selected >= keys.size()) {
                selected = -1;
            }
        }, "Could not read the stored API keys");
    }

    private void addKey() {
        String value = entry.trim();
        // Emptied before anything else: the key must not sit in a box after it is stored.
        entry = "";
        if (value.isEmpty()) {
            return;
        }
        // A blank platform is the gateway, not an error: ModelPlatform.normalise says so.
        String target = ModelPlatform.normalise(platform);
        request(database.apiKeys().add(value, target), stored -> {
            say(I18n.get(PREFIX + "added", target), FormTone.GOOD);
            loadKeys();
        }, "Could not store the API key",
                failure -> say(I18n.get(PREFIX + "add_failed"), FormTone.BAD));
    }

    private void removeSelected() {
        if (selected < 0 || selected >= keys.size()) {
            return;
        }
        ApiKey key = keys.get(selected);
        selected = -1;
        request(database.apiKeys().remove(key.value()), removed -> {
            say(I18n.get(PREFIX + "removed"), FormTone.SOFT);
            loadKeys();
        }, "Could not remove the API key",
                failure -> say(I18n.get(PREFIX + "remove_failed"), FormTone.BAD));
    }

    private void say(String text, FormTone tone) {
        this.note = text;
        this.noteTone = tone;
    }

    /** A platform name clipped to what a row has space for beside the mask. */
    private static String shorten(String platform) {
        if (platform.length() <= PLATFORM_CHARS) {
            return platform;
        }
        return platform.substring(0, PLATFORM_CHARS - 1) + "…";
    }
}
