package io.github.mcagentsmods.chat.shared.client.gui;

import io.github.mcagentsmods.chat.api.model.ApiKey;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Where the player adds and removes API keys.
 *
 * <p>Without this there was no way to give the mod a key at all, which made the agent
 * permanently unable to answer — the model client was complete and unreachable.
 *
 * <h2>A key carries the platform it opens</h2>
 *
 * <p>The second control is which platform the key authenticates to, and it defaults to
 * {@code openrouter} — what every key was, and what a player who ignores it keeps getting. It
 * is a dropdown that can also be typed into, offering the same platforms the console's key
 * page offers, from the same list in {@link ModelCatalog#offeredPlatforms}. The list suggests
 * and never constrains: a platform this release has never heard of is typed straight into the
 * field and stored exactly as typed.
 *
 * <p>A key stored for a platform the mod has an address for is what turns a direct call on.
 * The row under the platform sets that address: empty means the one this release ships, or
 * none, and only {@code https://} or a loopback address is taken, because the address is where
 * the platform's key is sent. OpenRouter's own address is fixed. The game is the player's own,
 * so unlike the web console that used to do this, the screen lets an address move to another
 * host while a key is stored.
 *
 * <h2>Whether keys are encrypted</h2>
 *
 * <p>The last line says whether {@value TokenCipher#KEY_VARIABLE} is set, and so whether the
 * stored keys are encrypted in the database or kept as pasted. It says which of the two the
 * player has and never implies more; see {@code .agents/security/api-keys.md}.
 *
 * <h2>Keys are never shown in full</h2>
 *
 * <p>Stored keys are listed through {@link ApiKey#masked()}, never as their whole value.
 * The full value leaves the mod only in an {@code Authorization} header, and a player
 * streaming or screenshotting this screen must not leak their key by opening it. The entry
 * box is cleared the moment a key is saved for the same reason. See
 * {@code .agents/security/api-keys.md}.
 */
public final class ApiKeyScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyScreen.class);

    private static final int FIELD_WIDTH = 260;
    private static final int LINE = 20;
    private static final int ROW_HEIGHT = 22;

    /**
     * Where the entry boxes start, and where the list of stored keys starts under them.
     *
     * <p>Both {@code init} and the render pass place things from these two numbers rather
     * than from arithmetic each repeats. A screen where two methods compute the same
     * offset independently is a screen whose text collides the moment one of them changes —
     * see {@code .agents/memory/tasks/screen-layout.md}.
     */
    private static final int FORM_TOP = 40;
    private static final int NOTE_TOP = FORM_TOP + (ROW_HEIGHT * 3) + 2;
    private static final int LIST_TOP = NOTE_TOP + 14;

    /** The gap kept between the last key row and the encryption line under it. */
    private static final int FOOTER_GAP = 4;

    /** How much of a platform name a row has space for before the remove button. */
    private static final int PLATFORM_CHARS = 12;

    private final LocalDatabase database;
    private final Screen parent;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private EditBox entryBox;
    private EditableDropdown platformPicker;
    private EditBox addressBox;
    private Button addressButton;
    private List<ApiKey> keys = List.of();

    /** The platform the address box was last filled for, kept across a rebuild. */
    private String addressPlatform;

    /** What the address box holds, kept across a rebuild like the other two boxes. */
    private String pendingAddress = "";

    /** The result of the last address save, shown under the form, or null for none. */
    private Component addressNote;
    private int addressNoteColour;

    /**
     * Whether the key list has been read once.
     *
     * <p>This guard is load-bearing. {@code init()} used to call {@link #loadKeys()}
     * unconditionally, and {@code loadKeys} calls {@code rebuildWidgets()}, which calls
     * {@code init()} again — an endless rebuild that destroyed every widget several times a
     * second. Nothing could hold focus, so the entry box could not be clicked into, and the
     * database was queried forever.
     */
    private boolean loaded;

    /** Kept across a rebuild, so a half-typed key is not thrown away. */
    private String pendingEntry = "";

    /** Kept for the same reason, and defaulted to the platform every key used to be for. */
    private String pendingPlatform = ModelPlatform.OPENROUTER;

    public ApiKeyScreen(LocalDatabase database, Screen parent) {
        super(Component.translatable("gui.mcagents.keys.title"));
        this.database = database;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - FIELD_WIDTH) / 2;
        int top = FORM_TOP;

        entryBox = new EditBox(font, left, top, FIELD_WIDTH - 60, LINE,
                Component.translatable("gui.mcagents.keys.add"));
        entryBox.setHint(Component.translatable("gui.mcagents.keys.add"));
        // Long enough for any provider key; the value itself is never rendered back.
        entryBox.setMaxLength(400);
        entryBox.setValue(pendingEntry);
        entryBox.setResponder(text -> pendingEntry = text);
        addRenderableWidget(entryBox);
        // The player opened this screen to type a key; put the cursor where they expect it.
        setInitialFocus(entryBox);

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.keys.save"), button -> addKey())
                .bounds(left + FIELD_WIDTH - 56, top, 56, LINE)
                .build());

        // Which platform the key opens. The list is a suggestion and never a limit: the mod
        // does not reject a model id the player types and it does not reject a platform
        // either, so this is the dropdown that can be typed into rather than a cycling button.
        //
        // Built from the keys already read, so a platform the player is using is offered even
        // when this release has never heard of it. On the first init there are none yet; the
        // read below rebuilds the screen once they arrive.
        platformPicker = new EditableDropdown(font, ModelCatalog.offeredPlatforms(platformsInUse()),
                Component.translatable("gui.mcagents.keys.platform"));
        platformPicker.setBounds(left, top + ROW_HEIGHT, FIELD_WIDTH - 60, LINE);
        platformPicker.setValue(pendingPlatform);
        platformPicker.field().setResponder(text -> {
            pendingPlatform = text;
            showAddressFor(text);
        });
        addRenderableWidget(platformPicker.field());

        // Where the chosen platform is called. Filled from the stored setting whenever the
        // platform changes, and kept as typed across a rebuild.
        addressBox = new EditBox(font, left, top + ROW_HEIGHT * 2, FIELD_WIDTH - 60, LINE,
                Component.translatable("gui.mcagents.keys.address"));
        addressBox.setMaxLength(400);
        addressBox.setValue(pendingAddress);
        addressBox.setResponder(text -> pendingAddress = text);
        addRenderableWidget(addressBox);

        addressButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.keys.address.save"),
                        button -> saveAddress())
                .bounds(left + FIELD_WIDTH - 56, top + ROW_HEIGHT * 2, 56, LINE)
                .build());
        showAddressFor(pendingPlatform);

        // One row per stored key, each with its own remove button.
        int rowTop = LIST_TOP;
        for (int i = 0; i < keys.size() && rowTop + LINE <= listBottom(); i++) {
            ApiKey key = keys.get(i);
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.mcagents.keys.remove"),
                            button -> removeKey(key))
                    .bounds(left + FIELD_WIDTH - 56, rowTop, 56, LINE)
                    .build());
            rowTop += ROW_HEIGHT;
        }

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(left + FIELD_WIDTH - 100, height - 34, 100, LINE)
                .build());

        if (!loaded) {
            loadKeys();
        }
    }

    /**
     * The platforms the player already holds a key for.
     *
     * <p>Offered alongside the ones this release knows, so a player who filed a key under a
     * platform the mod has never heard of does not have to type that name again — and cannot
     * mistype it into a second platform nobody serves.
     */
    private List<String> platformsInUse() {
        return keys.stream().map(ApiKey::platform).distinct().toList();
    }

    /**
     * Reads the stored keys and rebuilds only if the list actually changed.
     *
     * <p>Rebuilding unconditionally is what caused the endless loop described on
     * {@link #loaded}: a rebuild re-enters {@code init()}, so a read that always rebuilds
     * never terminates.
     */
    private void loadKeys() {
        onClientThread(waiting.track(database.apiKeys().findAll()), stored -> {
            boolean changed = !stored.equals(keys);
            keys = stored;
            loaded = true;
            if (changed) {
                rebuildWidgets();
            }
        }, "Could not read the stored API keys");
    }

    private void addKey() {
        String value = entryBox.getValue().trim();
        if (value.isEmpty()) {
            return;
        }
        // A blank field is the gateway, not an error: ModelPlatform.normalise says so, and that
        // is the fallback a player who never touched it relies on.
        String platform = ModelPlatform.normalise(platformPicker.value());
        // Cleared immediately: the key must not sit in a visible text box after it has been
        // stored, where it would survive into a screenshot or a stream. The platform is not
        // cleared — it is not a secret, and a player adding two keys for one platform should
        // not have to type it twice.
        entryBox.setValue("");
        pendingEntry = "";

        onClientThread(waiting.track(database.apiKeys().add(value, platform)),
                stored -> loadKeys(), "Could not store the API key");
    }

    /**
     * Points the address box at {@code typed}'s platform.
     *
     * <p>When the platform is the one the box already holds, only its state is set again, as a
     * rebuild needs, and what the player typed stays. A new platform empties the box and reads
     * its stored address into it; a read that lands after the player has moved on is dropped.
     */
    private void showAddressFor(String typed) {
        String platform = ModelPlatform.normalise(typed);
        boolean gateway = ModelPlatform.isOpenRouter(platform);
        addressBox.setEditable(!gateway);
        addressButton.active = !gateway;
        addressBox.setHint(gateway
                ? Component.translatable("gui.mcagents.keys.address.gateway")
                : ModelPlatform.knownBaseUrl(platform)
                        .<Component>map(Component::literal)
                        .orElse(Component.translatable("gui.mcagents.keys.address.none")));
        if (platform.equals(addressPlatform)) {
            return;
        }
        addressPlatform = platform;
        addressNote = null;
        addressBox.setValue("");
        if (gateway) {
            return;
        }
        onClientThread(database.settings().find(ModelPlatform.baseUrlSettingKey(platform)), stored -> {
            if (platform.equals(addressPlatform) && addressBox.getValue().isEmpty()) {
                addressBox.setValue(stored.map(String::trim).orElse(""));
            }
        }, "Could not read the address of " + platform);
    }

    /**
     * Stores the address box for its platform, or clears the platform's address when it is
     * empty. An address that is neither {@code https://} nor loopback is refused before
     * anything is written.
     */
    private void saveAddress() {
        String platform = addressPlatform;
        if (platform == null || ModelPlatform.isOpenRouter(platform)) {
            return;
        }
        String url = addressBox.getValue().trim();
        if (!url.isEmpty() && !ModelPlatform.isUsableBaseUrl(url)) {
            showAddressNote(Component.translatable("gui.mcagents.keys.address.refused"), false);
            return;
        }
        waiting.track(database.settings().put(ModelPlatform.baseUrlSettingKey(platform), url))
                .whenComplete((saved, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Could not save the address of {}", platform, failure);
                    }
                    if (minecraft == null) {
                        return;
                    }
                    minecraft.execute(() -> {
                        if (minecraft == null || minecraft.gui.screen() != this) {
                            return;
                        }
                        if (failure != null) {
                            showAddressNote(Component.translatable(
                                    "gui.mcagents.keys.address.failed"), false);
                        } else {
                            showAddressNote(Component.translatable(url.isEmpty()
                                    ? "gui.mcagents.keys.address.cleared"
                                    : "gui.mcagents.keys.address.saved", platform), true);
                        }
                    });
                });
    }

    private void showAddressNote(Component note, boolean succeeded) {
        addressNote = note;
        addressNoteColour = succeeded ? 0xFF80FF80 : 0xFFFF8080;
    }

    /** The encryption line, wrapped to the form's width. */
    private List<FormattedCharSequence> encryptionLines() {
        Component text = TokenCipher.fromEnvironment().enabled()
                ? Component.translatable("gui.mcagents.keys.encrypted", TokenCipher.KEY_VARIABLE)
                : Component.translatable("gui.mcagents.keys.not_encrypted", TokenCipher.KEY_VARIABLE);
        return font.split(text, FIELD_WIDTH);
    }

    /** Where the encryption line starts: as far down as it fits over the Done button. */
    private int footerTop() {
        return height - 38 - encryptionLines().size() * font.lineHeight;
    }

    /** The lowest point a key row may reach, above the encryption line. */
    private int listBottom() {
        return footerTop() - FOOTER_GAP;
    }

    private void removeKey(ApiKey key) {
        onClientThread(waiting.track(database.apiKeys().remove(key.value())),
                removed -> loadKeys(), "Could not remove the API key");
    }

    private <T> void onClientThread(CompletableFuture<T> request, Consumer<T> onSuccess,
                                    String failureMessage) {
        request.whenComplete((result, failure) -> {
            if (failure != null) {
                LOGGER.error("{}", failureMessage, failure);
                return;
            }
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (minecraft != null && minecraft.gui.screen() == this) {
                    onSuccess.accept(result);
                }
            });
        });
    }

    @Override
    public void tick() {
        super.tick();
        waiting.tick();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int left = (width - FIELD_WIDTH) / 2;
        graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);

        if (keys.isEmpty()) {
            graphics.text(font, Component.translatable("gui.mcagents.keys.empty")
                    .withStyle(ChatFormatting.GRAY), left, LIST_TOP + 4, 0xFF9A9AA4);
        }

        int rowTop = LIST_TOP;
        for (int i = 0; i < keys.size() && rowTop + LINE <= listBottom(); i++) {
            ApiKey key = keys.get(i);
            // masked(), never the whole value.
            Component label = Component.literal(key.masked())
                    .withStyle(key.active() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
            graphics.text(font, label, left, rowTop + 6, 0xFFFFFFFF);
            // Which API the key opens. Shown because two masked keys look identical and the
            // platform is the only thing that tells them apart — and it is not a secret.
            graphics.text(font, Component.literal(shorten(key.platform()))
                            .withStyle(ChatFormatting.GRAY),
                    left + 92, rowTop + 6, 0xFF9A9AA4);
            if (!key.active()) {
                graphics.text(font, Component.translatable("gui.mcagents.keys.inactive")
                                .withStyle(ChatFormatting.RED),
                        left + FIELD_WIDTH - 116, rowTop + 6, 0xFFFF8080);
            }
            rowTop += ROW_HEIGHT;
        }

        // One line under the form: the wait while something is being stored, otherwise what
        // the last address save did.
        if (waiting.isWaiting()) {
            graphics.text(font, waiting.label(Component.translatable("gui.mcagents.waiting")),
                    left, NOTE_TOP, 0xFFFFFFFF);
        } else if (addressNote != null) {
            graphics.text(font, font.plainSubstrByWidth(addressNote.getString(), FIELD_WIDTH),
                    left, NOTE_TOP, addressNoteColour);
        }

        int lineTop = footerTop();
        for (FormattedCharSequence line : encryptionLines()) {
            graphics.text(font, line, left, lineTop, 0xFF9A9AA4);
            lineTop += font.lineHeight;
        }

        platformPicker.render(graphics, mouseX, mouseY);
        // Last, and over the key rows it covers: an open list floats above the screen rather
        // than sitting inside it.
        platformPicker.renderOverlay(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Before super, so an open list gets the click rather than the remove button beneath
        // it. A row that was picked is read back here rather than relied upon to notify: the
        // dropdown writes into the field directly, and this screen keeps the pending value
        // across the rebuild that a stored key triggers.
        if (platformPicker.clickedOverlay(event)) {
            pendingPlatform = platformPicker.value();
            showAddressFor(pendingPlatform);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * A platform name clipped to what the row has space for.
     *
     * <p>Clipped by characters rather than measured in pixels: the budget is the gap between
     * the mask and the rejected marker, and a name long enough to reach either is a name the
     * player only needs to recognise.
     */
    private static String shorten(String platform) {
        if (platform.length() <= PLATFORM_CHARS) {
            return platform;
        }
        return platform.substring(0, PLATFORM_CHARS - 1) + "…";
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
