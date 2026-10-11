package io.github.mcagentsmods.chat.fabric.client.gui;

import io.github.mcagentsmods.chat.api.wallet.WalletBinding;
import io.github.mcagentsmods.chat.common.wallet.LocalWalletBridge;
import io.github.mcagentsmods.chat.common.wallet.SystemBrowser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Walks the player through proving an address with MetaMask.
 *
 * <h2>What is happening while this screen is open</h2>
 *
 * <p>MetaMask is a browser extension: {@code window.ethereum} exists on a web page and
 * nowhere else. So opening this screen starts a small server on {@code 127.0.0.1}, opens
 * the player's browser at it, and waits. The page asks MetaMask to sign a one-off message
 * <strong>for every address it shares</strong>, the mod checks each signature, and
 * <strong>the server stops when the page says it has finished</strong> — or when this
 * screen closes, whichever comes first. There is no way to leave it running.
 *
 * <p>One trip, however many addresses. A player with five accounts in MetaMask used to make
 * this journey five times, once per address, because the bridge stopped on the first
 * signature that verified.
 *
 * <p>The address is shown on screen as well as opened, because the browser that opens by
 * default is often not the one MetaMask is installed in, and a link the player can copy is
 * the only thing that helps in that case.
 *
 * <p>Signing costs nothing: it never reaches a chain, moves no funds, and approves nothing.
 * MCAgents never asks for a recovery phrase or a private key, and this screen has no field
 * to type one into.
 */
public final class WalletConnectScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletConnectScreen.class);

    private static final int LINE = 20;
    private static final int CONTENT_WIDTH = 300;

    private final String accountId;
    private final Screen parent;
    private final Consumer<List<WalletBinding>> onBound;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private LocalWalletBridge bridge;
    private Button openButton;
    private Button copyButton;
    private Component state = Component.translatable("gui.mcagents.wallets.connect.starting");
    private boolean failed;

    public WalletConnectScreen(String accountId, Screen parent,
                               Consumer<List<WalletBinding>> onBound) {
        super(Component.translatable("gui.mcagents.wallets.connect"));
        this.accountId = accountId;
        this.parent = parent;
        this.onBound = onBound;
    }

    @Override
    protected void init() {
        int left = (width - CONTENT_WIDTH) / 2;
        int buttonTop = height / 2 + 40;
        int buttonWidth = 145;

        openButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.connect.open"),
                        button -> openBrowser())
                .bounds(left, buttonTop, buttonWidth, LINE)
                .build());

        copyButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.connect.copy"),
                        button -> copyLink())
                .bounds(left + CONTENT_WIDTH - buttonWidth, buttonTop, buttonWidth, LINE)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + (CONTENT_WIDTH - 100) / 2, buttonTop + LINE + 8, 100, LINE)
                .build());

        // Started once, not on every resize: a rebuilt screen must not leave a second
        // listener behind, and the player would have to sign twice.
        if (bridge == null) {
            start();
        }
    }

    private void start() {
        try {
            bridge = LocalWalletBridge.start(accountId);
        } catch (RuntimeException e) {
            LOGGER.error("Could not start the wallet bridge", e);
            state = Component.translatable("gui.mcagents.wallets.connect.failed",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            failed = true;
            return;
        }

        state = Component.translatable("gui.mcagents.wallets.connect.waiting");
        waiting.track(bridge.bindings()).whenComplete((bound, failure) -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (minecraft == null || minecraft.gui.screen() != this) {
                    return;
                }
                if (failure != null) {
                    // The bridge's own wording, which already says what went wrong: a
                    // declined signature, a timeout, a closed tab.
                    LOGGER.info("The wallet connection did not complete: {}", describe(failure));
                    state = Component.literal(describe(failure));
                    failed = true;
                    return;
                }
                onBound.accept(bound);
                onClose();
            });
        });

        openBrowser();
    }

    private void openBrowser() {
        if (bridge == null) {
            return;
        }
        if (!SystemBrowser.open(bridge.url())) {
            // Not a failure of the binding: the address is on screen and copyable, which is
            // also what the player needs when their default browser is not the one MetaMask
            // is installed in.
            state = Component.translatable("gui.mcagents.wallets.connect.no_browser");
        }
    }

    private void copyLink() {
        if (bridge != null && minecraft != null) {
            minecraft.keyboardHandler.setClipboard(bridge.url().toString());
            state = Component.translatable("gui.mcagents.wallets.connect.copied");
        }
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank()
                ? "The wallet connection did not complete."
                : message;
    }

    @Override
    public void tick() {
        super.tick();
        waiting.tick();
        // Once the attempt has ended the bridge is gone, so the link leads nowhere. Leaving
        // these live would send the player to a dead port and tell them nothing.
        if (openButton != null) {
            openButton.active = !failed;
        }
        if (copyButton != null) {
            copyButton.active = !failed;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int left = (width - CONTENT_WIDTH) / 2;
        int top = height / 2 - 74;

        graphics.centeredText(font, title, width / 2, top, 0xFFFFFFFF);
        graphics.centeredText(font,
                Component.translatable("gui.mcagents.wallets.connect.into", accountId),
                width / 2, top + 12, 0xFF9A9AA4);

        // Said before the player reaches their wallet, not after: this is the moment a scam
        // would be asking for a recovery phrase, and the mod never will.
        graphics.text(font,
                Component.translatable("gui.mcagents.wallets.connect.never_asks")
                        .withStyle(ChatFormatting.YELLOW),
                left, top + 32, 0xFFFFD97A);
        graphics.text(font, font.plainSubstrByWidth(
                        Component.translatable("gui.mcagents.wallets.connect.signing_is_free")
                                .getString(), CONTENT_WIDTH),
                left, top + 44, 0xFF9A9AA4);

        Component line = waiting.isWaiting() && !failed
                ? waiting.label(state)
                : state;
        graphics.text(font, font.plainSubstrByWidth(line.getString(), CONTENT_WIDTH),
                left, top + 66, failed ? 0xFFFF8080 : 0xFFE0E0E0);

        if (bridge != null) {
            graphics.text(font, font.plainSubstrByWidth(bridge.url().toString(), CONTENT_WIDTH),
                    left, top + 78, 0xFF9CC4FF);
        }
    }

    @Override
    public void onClose() {
        stopBridge();
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
            return;
        }
        super.onClose();
    }

    @Override
    public void removed() {
        // Whatever takes the player off this screen — Escape, a click, the game closing —
        // the listener goes with it. This is the backstop for every path onClose misses.
        stopBridge();
        super.removed();
    }

    private void stopBridge() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
