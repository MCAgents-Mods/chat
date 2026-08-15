package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.website.LocalWebConsole;
import io.github.mcagents.clientfabric.common.ui.StackedRows;
import io.github.mcagents.clientfabric.common.wallet.SystemBrowser;
import io.github.mcagents.clientfabric.fabric.MCAgentsClientMod;
import io.github.mcagents.clientfabric.fabric.client.WebConsoleHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Starts and stops the web console, and shows the player where to point their browser.
 *
 * <h2>Closing this screen does not stop the console</h2>
 *
 * <p>That is the point of it. The player opens the console so they can go back to playing
 * with the browser beside them; a server that closed when they pressed Escape would shut
 * exactly when it became useful. It runs until they stop it here or the game exits.
 *
 * <p>The address is shown as well as opened, because the browser that opens by default is
 * often not the one the player wants to read in — and because the address carries the
 * one-time token, so it cannot be retyped from memory.
 *
 * <h2>The layout is measured, not counted</h2>
 *
 * <p>Every row's position comes from the height of the rows above it, worked out in
 * {@link #init()} where the font is already available. The screen previously used fixed
 * offsets from {@code height / 2}, which produced two visible defects: the hint was cut off
 * mid-word because it was clipped to a pixel width rather than wrapped, and the status line
 * was drawn six pixels above a button row and disappeared underneath it.
 *
 * <p>Neither was a matter of picking better numbers. Wrapped text has a height nobody can
 * predict from the source — it depends on the window, the language, and how long a URL's
 * token happens to be — so any layout built from constants is one translation away from
 * colliding again.
 */
public final class WebConsoleScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebConsoleScreen.class);

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int MAX_CONTENT_WIDTH = 320;

    /** Space between one group of rows and the next. */
    private static final int SECTION_GAP = 10;

    /** The least this screen will start from, so a tall block runs off the bottom, not the top. */
    private static final int TOP_MARGIN = 8;

    private final LocalDatabase database;
    private final Screen parent;

    private Component status = Component.empty();
    private boolean failed;

    /* Where init() decided everything goes. Recorded rather than recomputed while drawing,
     * so the buttons and the text cannot disagree about the layout they are part of. */
    private int contentWidth = MAX_CONTENT_WIDTH;
    private int contentLeft;
    private int titleY;
    private int subtitleY;
    private int stateY;
    private int urlY;
    private int hintY;
    private int statusY;
    private List<FormattedCharSequence> urlLines = List.of();
    private List<FormattedCharSequence> hintLines = List.of();

    public WebConsoleScreen(LocalDatabase database, Screen parent) {
        super(Component.translatable("gui.mcagents.console.title"));
        this.database = database;
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Narrow windows exist. Clamping here rather than assuming 320 is what keeps the
        // hint inside the screen instead of running off the right edge of a small one.
        contentWidth = Math.min(MAX_CONTENT_WIDTH, Math.max(120, width - 40));
        contentLeft = (width - contentWidth) / 2;

        Optional<LocalWebConsole> running = WebConsoleHolder.current();
        urlLines = running
                .map(console -> font.split(Component.literal(console.url().toString()), contentWidth))
                .orElse(List.of());
        hintLines = font.split(
                Component.translatable("gui.mcagents.console.local_only"), contentWidth);

        int lineHeight = font.lineHeight + 1;
        int buttonBlock = BUTTON_HEIGHT * 2 + BUTTON_GAP;

        // Measured once to centre the block, then laid out for real. Both passes use the
        // same stacker, so the total and the positions cannot disagree.
        int total = stack(new StackedRows(0), lineHeight, buttonBlock).heightFrom(0);

        StackedRows rows = stack(
                new StackedRows(StackedRows.centred(height, total, TOP_MARGIN)),
                lineHeight, buttonBlock);

        List<Integer> tops = rows.tops();
        titleY = tops.get(0);
        subtitleY = tops.get(1);
        stateY = tops.get(2);
        urlY = tops.get(3);
        hintY = tops.get(4);
        int buttonTop = tops.get(5);
        statusY = tops.get(6);

        int half = (contentWidth - 8) / 2;
        boolean isRunning = running.isPresent();

        addRenderableWidget(Button.builder(
                        Component.translatable(isRunning
                                ? "gui.mcagents.console.open"
                                : "gui.mcagents.console.start"),
                        button -> startOrOpen())
                .bounds(contentLeft, buttonTop, half, BUTTON_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.console.copy"),
                        button -> copyLink())
                .bounds(contentLeft + half + 8, buttonTop, half, BUTTON_HEIGHT)
                .build())
                .active = isRunning;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.console.stop"),
                        button -> stop())
                .bounds(contentLeft, buttonTop + BUTTON_HEIGHT + BUTTON_GAP, half, BUTTON_HEIGHT)
                .build())
                .active = isRunning;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(contentLeft + half + 8, buttonTop + BUTTON_HEIGHT + BUTTON_GAP,
                        half, BUTTON_HEIGHT)
                .build());
    }

    /**
     * The screen's rows, in order, added to {@code rows}.
     *
     * <p>Run twice — once from zero to measure the block, once from the centred top to place
     * it — because the height cannot be known before the rows are stacked and the top cannot
     * be known before the height. One method, so the two passes cannot drift apart.
     *
     * <p>The status row is last, after the buttons. A message the player triggers can be one
     * line or three, and anything above a control it can grow into is eventually drawn
     * underneath it — which is exactly what used to happen here.
     */
    private StackedRows stack(StackedRows rows, int lineHeight, int buttonBlock) {
        rows.add(lineHeight);                          // title
        rows.add(lineHeight);                          // subtitle
        rows.gap(SECTION_GAP);
        rows.add(lineHeight);                          // running / not running
        rows.add(urlLines.size() * lineHeight);        // the address, or nothing
        rows.gap(SECTION_GAP);
        rows.add(hintLines.size() * lineHeight);       // the hint
        rows.gap(SECTION_GAP);
        rows.add(buttonBlock);                         // both button rows
        rows.gap(SECTION_GAP);
        rows.add(lineHeight);                          // the status
        return rows;
    }

    private void startOrOpen() {
        Optional<LocalWebConsole> running = WebConsoleHolder.current();
        if (running.isEmpty()) {
            try {
                WebConsoleHolder.start(database, MCAgentsClientMod.paths());
                failed = false;
                status = Component.translatable("gui.mcagents.console.started");
                // Rebuilt so the Copy and Stop buttons stop being dead controls — and so the
                // layout makes room for the address, which was not there a moment ago.
                rebuildWidgets();
            } catch (RuntimeException e) {
                LOGGER.error("Could not start the web console", e);
                failed = true;
                status = Component.translatable("gui.mcagents.console.failed",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return;
            }
        }
        WebConsoleHolder.current().ifPresent(console -> {
            if (!SystemBrowser.open(console.url())) {
                status = Component.translatable("gui.mcagents.console.no_browser");
            }
        });
    }

    private void copyLink() {
        WebConsoleHolder.current().ifPresent(console -> {
            if (minecraft != null) {
                minecraft.keyboardHandler.setClipboard(console.url().toString());
                status = Component.translatable("gui.mcagents.console.copied");
            }
        });
    }

    private void stop() {
        WebConsoleHolder.stop();
        status = Component.translatable("gui.mcagents.console.stopped");
        failed = false;
        // The address is gone, so the rows below it move up.
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int lineHeight = font.lineHeight + 1;

        graphics.centeredText(font, title, width / 2, titleY, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("gui.mcagents.console.subtitle"),
                width / 2, subtitleY, 0xFF9A9AA4);

        boolean running = WebConsoleHolder.current().isPresent();
        graphics.text(font, running
                        ? Component.translatable("gui.mcagents.console.running")
                                .withStyle(ChatFormatting.GREEN)
                        : Component.translatable("gui.mcagents.console.not_running")
                                .withStyle(ChatFormatting.GRAY),
                contentLeft, stateY, running ? 0xFF7FD18A : 0xFF9A9AA4);

        // The whole address, including the token, wrapped rather than cut: a truncated
        // address is worse than none, because it looks copyable and is not.
        drawLines(graphics, urlLines, urlY, lineHeight, 0xFF9CC4FF);
        drawLines(graphics, hintLines, hintY, lineHeight, 0xFF9A9AA4);

        if (!status.getString().isEmpty()) {
            drawLines(graphics, font.split(status, contentWidth), statusY, lineHeight,
                    failed ? 0xFFFF8080 : 0xFFE0E0E0);
        }
    }

    private void drawLines(GuiGraphicsExtractor graphics, List<FormattedCharSequence> lines,
                           int top, int lineHeight, int colour) {
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), contentLeft, top + i * lineHeight, colour);
        }
    }

    @Override
    public void onClose() {
        // Deliberately leaves the console running — see the class comment.
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
