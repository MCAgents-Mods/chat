package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.model.ApiKey;
import io.github.mcagents.clientfabric.api.model.ModelPlatform;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
 * <p>The second box is which platform the key authenticates to, and it defaults to
 * {@code openrouter} — what every key was, and what a player who ignores the box keeps
 * getting. A key stored for a platform the mod knows an address for is what turns a direct
 * call on; the address itself is configured in the web console, because typing a URL into a
 * Minecraft text box is a worse experience than the console's, not because the game cannot do
 * it.
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
    private static final int LIST_TOP = FORM_TOP + (ROW_HEIGHT * 2) + 12;

    /** How much of a platform name a row has space for before the remove button. */
    private static final int PLATFORM_CHARS = 12;

    private final LocalDatabase database;
    private final Screen parent;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private EditBox entryBox;
    private EditBox platformBox;
    private List<ApiKey> keys = List.of();

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

        // Which platform the key opens. Never validated against a list: the mod does not
        // reject a model id the player types and it does not reject a platform either.
        platformBox = new EditBox(font, left, top + ROW_HEIGHT, FIELD_WIDTH - 60, LINE,
                Component.translatable("gui.mcagents.keys.platform"));
        platformBox.setHint(Component.translatable("gui.mcagents.keys.platform"));
        platformBox.setMaxLength(64);
        platformBox.setValue(pendingPlatform);
        platformBox.setResponder(text -> pendingPlatform = text);
        addRenderableWidget(platformBox);

        // One row per stored key, each with its own remove button.
        int rowTop = LIST_TOP;
        for (int i = 0; i < keys.size() && rowTop < height - 60; i++) {
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
        // A blank box is the gateway, not an error: ModelPlatform.normalise says so, and that
        // is the fallback a player who never touched the box relies on.
        String platform = ModelPlatform.normalise(platformBox.getValue());
        // Cleared immediately: the key must not sit in a visible text box after it has been
        // stored, where it would survive into a screenshot or a stream. The platform is not
        // cleared — it is not a secret, and a player adding two keys for one platform should
        // not have to type it twice.
        entryBox.setValue("");
        pendingEntry = "";

        onClientThread(waiting.track(database.apiKeys().add(value, platform)),
                stored -> loadKeys(), "Could not store the API key");
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
        for (int i = 0; i < keys.size() && rowTop < height - 60; i++) {
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

        if (waiting.isWaiting()) {
            graphics.centeredText(font, waiting.label(Component.translatable("gui.mcagents.waiting")),
                    width / 2, height - 52, 0xFFFFFFFF);
        }
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
