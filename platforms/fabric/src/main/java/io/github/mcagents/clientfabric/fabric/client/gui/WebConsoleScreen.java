package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.web.LocalWebConsole;
import io.github.mcagents.clientfabric.common.wallet.SystemBrowser;
import io.github.mcagents.clientfabric.fabric.MCAgentsClientMod;
import io.github.mcagents.clientfabric.fabric.client.WebConsoleHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public final class WebConsoleScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebConsoleScreen.class);

    private static final int LINE = 20;
    private static final int CONTENT_WIDTH = 320;

    private final LocalDatabase database;
    private final Screen parent;

    private Component status = Component.empty();
    private boolean failed;

    public WebConsoleScreen(LocalDatabase database, Screen parent) {
        super(Component.translatable("gui.mcagents.console.title"));
        this.database = database;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - CONTENT_WIDTH) / 2;
        int top = height / 2 + 16;
        int half = (CONTENT_WIDTH - 8) / 2;

        boolean running = WebConsoleHolder.current().isPresent();

        addRenderableWidget(Button.builder(
                        Component.translatable(running
                                ? "gui.mcagents.console.open"
                                : "gui.mcagents.console.start"),
                        button -> startOrOpen())
                .bounds(left, top, half, LINE)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.console.copy"),
                        button -> copyLink())
                .bounds(left + half + 8, top, half, LINE)
                .build())
                .active = running;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.console.stop"),
                        button -> stop())
                .bounds(left, top + LINE + 6, half, LINE)
                .build())
                .active = running;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(left + half + 8, top + LINE + 6, half, LINE)
                .build());
    }

    private void startOrOpen() {
        Optional<LocalWebConsole> running = WebConsoleHolder.current();
        if (running.isEmpty()) {
            try {
                WebConsoleHolder.start(database, MCAgentsClientMod.paths());
                failed = false;
                status = Component.translatable("gui.mcagents.console.started");
                // Rebuilt so the Copy and Stop buttons stop being dead controls.
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
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int left = (width - CONTENT_WIDTH) / 2;
        int top = height / 2 - 74;

        graphics.centeredText(font, title, width / 2, top, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("gui.mcagents.console.subtitle"),
                width / 2, top + 12, 0xFF9A9AA4);

        Optional<LocalWebConsole> running = WebConsoleHolder.current();
        graphics.text(font, running.isPresent()
                        ? Component.translatable("gui.mcagents.console.running")
                                .withStyle(ChatFormatting.GREEN)
                        : Component.translatable("gui.mcagents.console.not_running")
                                .withStyle(ChatFormatting.GRAY),
                left, top + 34, running.isPresent() ? 0xFF7FD18A : 0xFF9A9AA4);

        // The whole address, including the token. Nothing else can be retyped from memory,
        // and a player whose default browser is the wrong one needs to paste it elsewhere.
        running.ifPresent(console -> graphics.text(font,
                font.plainSubstrByWidth(console.url().toString(), CONTENT_WIDTH),
                left, top + 48, 0xFF9CC4FF));

        graphics.text(font, font.plainSubstrByWidth(
                        Component.translatable("gui.mcagents.console.local_only").getString(),
                        CONTENT_WIDTH),
                left, top + 66, 0xFF9A9AA4);

        if (!status.getString().isEmpty()) {
            graphics.text(font, font.plainSubstrByWidth(status.getString(), CONTENT_WIDTH),
                    left, top + 84, failed ? 0xFFFF8080 : 0xFFE0E0E0);
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
