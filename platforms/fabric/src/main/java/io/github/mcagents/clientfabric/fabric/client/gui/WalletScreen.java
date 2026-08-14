package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.api.wallet.ChainAddress;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.Wallet;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Where the player manages bound wallets: accounts across the top, wallets down the left,
 * and the selected wallet's chain addresses on the right.
 *
 * <h2>Three levels, because a player has three</h2>
 *
 * <p>An account holds several wallets — a main one, a trading one, one kept for a
 * particular server — and each wallet holds an address per chain, exactly as MetaMask does.
 * Flattening any pair of those loses something real.
 *
 * <h2>The rules this screen keeps</h2>
 *
 * <p><strong>Nothing blocks.</strong> Every call into storage returns a future and every
 * result is applied back on the client thread.
 *
 * <p><strong>Nothing here needs the network.</strong> Reading, adding, renaming, copying,
 * and deleting all work with no internet at all. Only proving an address through MetaMask
 * needs a browser, and failing at that leaves everything else untouched.
 *
 * <p><strong>Public addresses only.</strong> There is no field on this screen, or any
 * screen it opens, for a private key or a recovery phrase.
 */
public final class WalletScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletScreen.class);

    private static final int MARGIN = 8;
    private static final int HEADER_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 24;
    private static final int SIDEBAR_WIDTH = 132;
    private static final int GAP = 6;
    private static final int LINE = 20;

    /** Client ticks a status line stays on screen — about four seconds. */
    private static final int STATUS_TICKS = 80;

    private final LocalDatabase database;
    private final Screen parent;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private WalletListPanel walletList;
    private ChainAddressPanel addressPanel;
    private EditableDropdown accountBox;

    private List<String> accounts = List.of();
    private List<Wallet> wallets = List.of();

    /**
     * Whether the account list has been read once.
     *
     * <p>Load-bearing, for the reason recorded on {@code ApiKeyScreen}: reading rebuilds the
     * widgets, rebuilding re-enters {@code init()}, and a read that always rebuilds never
     * terminates.
     */
    private boolean loadedAccounts;

    /** Kept across a resize, which rebuilds every widget from scratch. */
    private String activeAccount;
    private String selectedWalletId;

    private Component status = Component.empty();
    private int statusTicks;

    public WalletScreen(LocalDatabase database, Screen parent, String defaultAccount) {
        super(Component.translatable("gui.mcagents.wallets.title"));
        this.database = database;
        this.parent = parent;
        this.activeAccount = defaultAccount;
    }

    @Override
    protected void init() {
        int contentTop = MARGIN + HEADER_HEIGHT;
        int contentBottom = height - MARGIN - FOOTER_HEIGHT - GAP;
        int contentHeight = Math.max(LINE, contentBottom - contentTop);
        int sidebarWidth = Math.min(SIDEBAR_WIDTH, width / 3);
        int rightLeft = MARGIN + sidebarWidth + GAP;
        int rightWidth = Math.max(LINE, width - MARGIN - rightLeft);
        int bottom = height - MARGIN - FOOTER_HEIGHT;

        // Every account that already owns a wallet, plus the player's own. Typing a name
        // that is not on the list is how a new account gets made — naming it is all it
        // takes, because accounts have no table of their own.
        List<String> options = new ArrayList<>(accounts);
        if (activeAccount != null && !options.contains(activeAccount)) {
            options.addFirst(activeAccount);
        }
        accountBox = new EditableDropdown(font, options,
                Component.translatable("gui.mcagents.wallets.account"));
        accountBox.setBounds(MARGIN, MARGIN, sidebarWidth, LINE);
        accountBox.setValue(activeAccount);
        accountBox.field().setResponder(text -> {
            activeAccount = text;
            loadWallets();
        });
        addRenderableWidget(accountBox.field());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(width - MARGIN - 60, MARGIN, 60, LINE)
                .build());

        walletList = new WalletListPanel(
                font, this::selectWallet, this::beginRename, this::confirmDeleteWallet);
        walletList.setBounds(MARGIN, contentTop, sidebarWidth, contentHeight);

        addressPanel = new ChainAddressPanel(font, this::copyAddress, this::removeAddress);
        addressPanel.setBounds(rightLeft, contentTop, rightWidth, contentHeight);

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.add"),
                        button -> createWallet())
                .bounds(MARGIN, bottom, sidebarWidth, LINE)
                .tooltip(Tooltip.create(Component.translatable("gui.mcagents.wallets.add.tip")))
                .build());

        int connectWidth = Math.min(140, Math.max(80, rightWidth / 2 - GAP));
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.connect"),
                        button -> connectWallet())
                .bounds(rightLeft, bottom, connectWidth, LINE)
                .tooltip(Tooltip.create(Component.translatable("gui.mcagents.wallets.connect.tip")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.address.add"),
                        button -> addAddress())
                .bounds(rightLeft + connectWidth + GAP, bottom,
                        Math.max(60, rightWidth - connectWidth - GAP), LINE)
                .build());

        if (!loadedAccounts) {
            loadAccounts();
        }
        loadWallets();
    }

    // ---------------------------------------------------------------- data

    /**
     * Reads the accounts, and rebuilds only if the list actually changed.
     *
     * <p>Rebuilding unconditionally is what caused the endless loop described on
     * {@link #loadedAccounts}.
     */
    private void loadAccounts() {
        onClientThread(waiting.track(database.wallets().findAccounts()), stored -> {
            boolean changed = !stored.equals(accounts);
            accounts = stored;
            loadedAccounts = true;
            if (changed) {
                rebuildWidgets();
            }
        }, "Could not read the wallet accounts");
    }

    private void loadWallets() {
        String account = account();
        if (account.isBlank()) {
            wallets = List.of();
            walletList.setWallets(List.of());
            addressPanel.setWallet(null);
            return;
        }
        walletList.setLoading();
        onClientThread(waiting.track(database.wallets().findWalletsByAccount(account)), stored -> {
            // A slower earlier request must not overwrite the account the player has since
            // switched to.
            if (!account.equals(account())) {
                return;
            }
            wallets = stored;
            walletList.setWallets(stored);
            if (selectedWalletId != null) {
                walletList.select(selectedWalletId);
            }
            addressPanel.setWallet(selectedWallet().orElse(null));
        }, "Could not read the wallets in this account");
    }

    private void selectWallet(Wallet wallet) {
        selectedWalletId = wallet.id();
        addressPanel.setWallet(wallet);
    }

    private Optional<Wallet> selectedWallet() {
        return wallets.stream()
                .filter(wallet -> wallet.id().equals(selectedWalletId))
                .findFirst();
    }

    private String account() {
        String value = accountBox == null ? activeAccount : accountBox.value();
        return value == null ? "" : value.trim();
    }

    // ------------------------------------------------------------- wallets

    private void createWallet() {
        String account = account();
        if (account.isBlank()) {
            show(Component.translatable("gui.mcagents.wallets.account.missing"), true);
            return;
        }
        onClientThread(waiting.track(database.wallets().createWallet(account, null)), wallet -> {
            selectedWalletId = wallet.id();
            walletList.select(wallet.id());
            loadAccounts();
            loadWallets();
        }, "Could not create the wallet");
    }

    private void beginRename(Wallet wallet) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(RenameScreen.forWallet(wallet, this,
                label -> onClientThread(
                        waiting.track(database.wallets().renameWallet(wallet.id(), label)),
                        renamed -> loadWallets(), "Could not rename the wallet")));
    }

    /**
     * Asks before deleting.
     *
     * <p>Deleting a wallet takes every address under it and cannot be undone, so it is the
     * one action here that does not happen on a single click.
     */
    private void confirmDeleteWallet(Wallet wallet) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        deleteWallet(wallet);
                    }
                    if (minecraft != null) {
                        minecraft.setScreenAndShow(this);
                    }
                },
                Component.translatable("gui.mcagents.wallets.delete.title"),
                Component.translatable("gui.mcagents.wallets.delete.message", wallet.label()),
                Component.translatable("gui.mcagents.wallets.delete.confirm"),
                Component.translatable("gui.cancel")));
    }

    private void deleteWallet(Wallet wallet) {
        onClientThread(waiting.track(database.wallets().deleteWallet(wallet.id())), deleted -> {
            if (wallet.id().equals(selectedWalletId)) {
                selectedWalletId = null;
                addressPanel.setWallet(null);
            }
            loadAccounts();
            loadWallets();
        }, "Could not delete the wallet");
    }

    // ----------------------------------------------------------- addresses

    /**
     * Puts the whole address on the clipboard.
     *
     * <p>The list shows a shortened form because forty characters do not fit beside a chain
     * name. This is how the player gets the real thing, and it is the reason the shortening
     * is a layout decision rather than a loss.
     */
    private void copyAddress(ChainAddress address) {
        if (minecraft == null) {
            return;
        }
        minecraft.keyboardHandler.setClipboard(address.address());
        show(Component.translatable("gui.mcagents.wallets.copied",
                address.platform().displayName()), false);
    }

    private void removeAddress(ChainAddress address) {
        Wallet wallet = selectedWallet().orElse(null);
        if (wallet == null) {
            return;
        }
        onClientThread(
                waiting.track(database.wallets().removeAddress(wallet.id(), address.platform())),
                removed -> loadWallets(), "Could not remove the address");
    }

    private void addAddress() {
        if (minecraft == null) {
            return;
        }
        withWallet(wallet -> minecraft.setScreenAndShow(new AddAddressScreen(wallet, this,
                (platform, value) -> bind(wallet, platform, value, false))));
    }

    /**
     * Starts the MetaMask handshake for the selected wallet.
     *
     * <p>The bridge, the browser, and the signature check all live behind
     * {@link WalletConnectScreen}; what comes back here is an address that has already been
     * proved.
     */
    private void connectWallet() {
        if (minecraft == null) {
            return;
        }
        withWallet(wallet -> minecraft.setScreenAndShow(
                new WalletConnectScreen(account(), wallet, this, binding -> bind(
                        wallet, binding.platform(), binding.address(), true))));
    }

    /**
     * Runs an action against the selected wallet, creating one first if there is none.
     *
     * <p>A player who opens this screen and clicks "Connect" should not be told to press
     * something else first — the same reasoning as the chat screen starting a conversation
     * on the first message.
     */
    private void withWallet(Consumer<Wallet> action) {
        Optional<Wallet> selected = selectedWallet();
        if (selected.isPresent()) {
            action.accept(selected.get());
            return;
        }
        String account = account();
        if (account.isBlank()) {
            show(Component.translatable("gui.mcagents.wallets.account.missing"), true);
            return;
        }
        onClientThread(waiting.track(database.wallets().createWallet(account, null)), wallet -> {
            selectedWalletId = wallet.id();
            walletList.select(wallet.id());
            loadWallets();
            action.accept(wallet);
        }, "Could not create the wallet");
    }

    private void bind(Wallet wallet, ChainPlatform platform, String address, boolean verified) {
        onClientThread(waiting.track(database.wallets()
                        .addOrUpdateAddress(wallet.id(), platform, address, verified)),
                stored -> {
                    selectedWalletId = stored.id();
                    loadWallets();
                    show(Component.translatable(verified
                            ? "gui.mcagents.wallets.bound.verified"
                            : "gui.mcagents.wallets.bound.recorded",
                            platform.displayName()), false);
                }, "Could not store the address");
    }

    // --------------------------------------------------------- housekeeping

    private void show(Component message, boolean isProblem) {
        this.status = isProblem ? message.copy().withStyle(net.minecraft.ChatFormatting.RED) : message;
        this.statusTicks = STATUS_TICKS;
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
                // The player may have closed the screen while the request was in flight.
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
        if (statusTicks > 0) {
            statusTicks--;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        walletList.render(graphics, mouseX, mouseY);
        addressPanel.render(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int sidebarWidth = Math.min(SIDEBAR_WIDTH, width / 3);
        int headerLeft = MARGIN + sidebarWidth + GAP;

        Component header = waiting.isWaiting()
                ? waiting.label(Component.translatable("gui.mcagents.waiting"))
                : statusTicks > 0 ? status : title;
        graphics.text(font, header, headerLeft, MARGIN + 6, 0xFFFFFFFF);

        accountBox.render(graphics, mouseX, mouseY);
        // Last: an open list floats over everything, including the widgets above it.
        accountBox.renderOverlay(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Before anything else: an open list must get the click rather than the panel it
        // is covering.
        if (accountBox.clickedOverlay(event)) {
            return true;
        }
        if (walletList.click(event.x(), event.y(), doubleClick)) {
            return true;
        }
        if (addressPanel.click(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (walletList.isOver(mouseX, mouseY) && walletList.scrollBy(scrollY)) {
            return true;
        }
        if (addressPanel.isOver(mouseX, mouseY) && addressPanel.scrollBy(scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
