package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.api.wallet.ChainAddress;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.Wallet;
import io.github.mcagents.clientfabric.api.wallet.WalletBinding;
import io.github.mcagents.clientfabric.common.ui.AccountColumns;
import io.github.mcagents.clientfabric.common.wallet.AccountWallets;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Where the player manages bound wallets: one column per account, and the selected wallet's
 * chain addresses along the bottom.
 *
 * <h2>Every account at once</h2>
 *
 * <p>This screen used to show one account at a time behind a dropdown, which meant a player
 * with two identities could never see both — and comparing them, which is most of what an
 * account list is for, meant typing a name to look and typing the old one back. Now each
 * account is a column of its own, five wallets tall, scrolling past that. The shape matches
 * the console's {@code /wallets/} page on purpose: one layout to learn, not two.
 *
 * <p>When there are more accounts than columns the screen shows a window over them, with
 * arrows to move it. Squeezing eight accounts into eight unreadable slivers would be worse
 * than paging through them.
 *
 * <h2>Three levels, because a player has three</h2>
 *
 * <p>An account holds several wallets — a main one, a trading one, one kept for a particular
 * server — and each wallet holds an address per chain, exactly as MetaMask does. Flattening
 * any pair of those loses something real.
 *
 * <h2>The rules this screen keeps</h2>
 *
 * <p><strong>Nothing blocks.</strong> Every call into storage returns a future and every
 * result is applied back on the client thread.
 *
 * <p><strong>A column acts only on its own account.</strong> Every write goes through
 * {@link AccountWallets}, which checks the account rather than trusting the caller — so a
 * button under one column cannot reach a wallet under another.
 *
 * <p><strong>Nothing here needs the network.</strong> Reading, adding, renaming, copying,
 * and deleting all work with no internet at all. Only proving an address through MetaMask
 * needs a browser, and failing at that leaves everything else untouched.
 *
 * <p><strong>Public addresses only.</strong> There is no field on this screen, or any screen
 * it opens, for a private key or a recovery phrase.
 */
public final class WalletScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletScreen.class);

    private static final int MARGIN = 8;
    private static final int HEADER_HEIGHT = 22;
    private static final int GAP = 6;
    private static final int LINE = 20;
    private static final int ROW_HEIGHT = 12;

    /** How many wallets a column shows before it scrolls. */
    private static final int VISIBLE_ROWS = 5;

    /** Narrow enough for several columns on a small window, wide enough to read a label. */
    private static final int MIN_COLUMN_WIDTH = 116;

    /** The strip along the bottom, showing the selected wallet's addresses. */
    private static final int ADDRESS_STRIP_HEIGHT = 58;

    /** Room beside that strip for the one button that acts on it. */
    private static final int ADDRESS_BUTTON_WIDTH = 80;

    /** Client ticks a status line stays on screen — about four seconds. */
    private static final int STATUS_TICKS = 80;

    private final LocalDatabase database;
    private final AccountWallets accountWallets;
    private final Screen parent;
    private final String homeAccount;
    private final WaitingIndicator waiting = new WaitingIndicator();

    /** One list per account on screen, in the order the columns are drawn. */
    private final List<AccountColumn> columns = new ArrayList<>();

    /** Every account's wallets, so one column can be redrawn without re-reading the rest. */
    private final Map<String, List<Wallet>> walletsByAccount = new LinkedHashMap<>();

    private ChainAddressPanel addressPanel;

    private List<String> accounts = List.of();

    /**
     * Whether the accounts have been read once.
     *
     * <p>Load-bearing, for the reason recorded on {@code ApiKeyScreen}: reading rebuilds the
     * widgets, rebuilding re-enters {@code init()}, and a read that always rebuilds never
     * terminates.
     */
    private boolean loadedAccounts;

    /** Kept across a resize, which rebuilds every widget from scratch. */
    private int firstColumn;
    private String selectedAccount;
    private String selectedWalletId;

    private Component status = Component.empty();
    private int statusTicks;

    /** One account's column: the account it may act on, and the list showing it. */
    private record AccountColumn(String accountId, WalletListPanel panel) {
    }

    public WalletScreen(LocalDatabase database, Screen parent, String defaultAccount) {
        super(Component.translatable("gui.mcagents.wallets.title"));
        this.database = database;
        this.accountWallets = new AccountWallets(database.wallets());
        this.parent = parent;
        this.homeAccount = defaultAccount;
    }

    @Override
    protected void init() {
        List<String> shown = visibleAccounts();

        int listTop = MARGIN + HEADER_HEIGHT + ROW_HEIGHT;
        int listHeight = AccountColumns.listHeight(VISIBLE_ROWS, ROW_HEIGHT, 4);
        int buttonsTop = listTop + listHeight + GAP;
        int stripTop = buttonsTop + LINE + GAP;
        int stripHeight = Math.max(LINE,
                Math.min(ADDRESS_STRIP_HEIGHT, height - MARGIN - stripTop));

        addHeaderButtons();

        columns.clear();
        if (!shown.isEmpty()) {
            List<AccountColumns.Column> boxes = AccountColumns.divide(
                    MARGIN, width - MARGIN * 2, shown.size(), GAP);
            for (int index = 0; index < shown.size(); index++) {
                addColumn(shown.get(index), boxes.get(index), listTop, listHeight, buttonsTop);
            }
        }

        addressPanel = new ChainAddressPanel(font, this::copyAddress, this::removeAddress);
        int stripWidth = Math.max(LINE,
                width - MARGIN * 2 - ADDRESS_BUTTON_WIDTH - GAP);
        addressPanel.setBounds(MARGIN, stripTop, stripWidth, stripHeight);
        addressPanel.setWallet(selectedWallet().orElse(null));

        // Beside the strip it fills rather than up in the header: it acts on whichever
        // wallet is selected, and a control belongs next to what it changes.
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.address.add"),
                        button -> addAddress())
                .bounds(MARGIN + stripWidth + GAP, stripTop, ADDRESS_BUTTON_WIDTH, LINE)
                .tooltip(Tooltip.create(
                        Component.translatable("gui.mcagents.wallets.address.add.tip")))
                .build());

        if (!loadedAccounts) {
            loadAccounts();
            return;
        }
        columns.forEach(column -> paint(column.accountId()));
    }

    private void addHeaderButtons() {
        int right = width - MARGIN;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), button -> onClose())
                .bounds(right - 50, MARGIN, 50, LINE)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.account.add"),
                        button -> addAccount())
                .bounds(right - 50 - GAP - 90, MARGIN, 90, LINE)
                .tooltip(Tooltip.create(
                        Component.translatable("gui.mcagents.wallets.account.add.tip")))
                .build());

        // Only when there is somewhere to move to. Arrows that never do anything are two
        // more things to read on a screen that is already full.
        if (allAccounts().size() > columnCount()) {
            addRenderableWidget(Button.builder(Component.literal("◀"), button -> page(-1))
                    .bounds(MARGIN, MARGIN, 20, LINE)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("▶"), button -> page(1))
                    .bounds(MARGIN + 24, MARGIN, 20, LINE)
                    .build());
        }
    }

    private void addColumn(String accountId, AccountColumns.Column box,
                           int listTop, int listHeight, int buttonsTop) {
        WalletListPanel panel = new WalletListPanel(font,
                wallet -> selectWallet(accountId, wallet),
                wallet -> beginRename(accountId, wallet),
                wallet -> confirmDeleteWallet(accountId, wallet));
        panel.setBounds(box.left(), listTop, box.width(), listHeight);
        panel.setCaption(accountId);
        columns.add(new AccountColumn(accountId, panel));

        int half = (box.width() - 4) / 2;
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.connect.short"),
                        button -> connect(accountId))
                .bounds(box.left(), buttonsTop, Math.max(20, half), LINE)
                .tooltip(Tooltip.create(
                        Component.translatable("gui.mcagents.wallets.connect.tip")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.wallets.add.short"),
                        button -> createWallet(accountId))
                .bounds(box.left() + Math.max(20, half) + 4, buttonsTop,
                        Math.max(20, box.width() - half - 4), LINE)
                .tooltip(Tooltip.create(Component.translatable("gui.mcagents.wallets.add.tip")))
                .build());
    }

    // ---------------------------------------------------------------- data

    /**
     * The accounts this screen can draw columns for.
     *
     * <p>The player's own is always among them, even before it owns a wallet. Accounts have
     * no table — one exists exactly as long as a wallet names it — so a new player would
     * otherwise open this screen onto nothing at all and have to invent a name before they
     * could press anything.
     */
    private List<String> allAccounts() {
        List<String> all = new ArrayList<>(accounts);
        if (homeAccount != null && !homeAccount.isBlank() && !all.contains(homeAccount)) {
            all.addFirst(homeAccount);
        }
        return all;
    }

    private List<String> visibleAccounts() {
        List<String> all = allAccounts();
        if (all.isEmpty()) {
            return List.of();
        }
        int perPage = columnCount();
        firstColumn = AccountColumns.clampFirst(firstColumn, all.size(), perPage);
        return all.subList(firstColumn, Math.min(all.size(), firstColumn + perPage));
    }

    private int columnCount() {
        return AccountColumns.fit(width - MARGIN * 2, MIN_COLUMN_WIDTH, GAP);
    }

    private void page(int by) {
        firstColumn = AccountColumns.clampFirst(
                firstColumn + by * columnCount(), allAccounts().size(), columnCount());
        rebuildWidgets();
    }

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
                return;
            }
            columns.forEach(column -> paint(column.accountId()));
        }, "Could not read the wallet accounts");
    }

    /** Reads one account's wallets and draws them into its column. */
    private void paint(String accountId) {
        panelFor(accountId).ifPresent(WalletListPanel::setLoading);
        onClientThread(waiting.track(database.wallets().findWalletsByAccount(accountId)),
                stored -> {
                    walletsByAccount.put(accountId, stored);
                    panelFor(accountId).ifPresent(panel -> {
                        panel.setWallets(stored);
                        if (accountId.equals(selectedAccount) && selectedWalletId != null) {
                            panel.select(selectedWalletId);
                        }
                    });
                    if (accountId.equals(selectedAccount)) {
                        addressPanel.setWallet(selectedWallet().orElse(null));
                    }
                }, "Could not read the wallets in this account");
    }

    private Optional<WalletListPanel> panelFor(String accountId) {
        return columns.stream()
                .filter(column -> column.accountId().equals(accountId))
                .map(AccountColumn::panel)
                .findFirst();
    }

    private void selectWallet(String accountId, Wallet wallet) {
        // One selection across the whole screen: the strip along the bottom shows one
        // wallet, so two columns showing a highlight would be claiming it shows two.
        columns.stream()
                .filter(column -> !column.accountId().equals(accountId))
                .forEach(column -> column.panel().select(null));

        selectedAccount = accountId;
        selectedWalletId = wallet.id();
        addressPanel.setWallet(wallet);
    }

    private Optional<Wallet> selectedWallet() {
        if (selectedAccount == null || selectedWalletId == null) {
            return Optional.empty();
        }
        return walletsByAccount.getOrDefault(selectedAccount, List.of()).stream()
                .filter(wallet -> wallet.id().equals(selectedWalletId))
                .findFirst();
    }

    // ------------------------------------------------------------- accounts

    /**
     * Makes an account by naming it.
     *
     * <p>There is nothing to create: an account exists as soon as a wallet names it, so this
     * makes the account's first wallet and the account arrives with it.
     */
    private void addAccount() {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(RenameScreen.forNewAccount(this, name -> {
            if (name.isBlank()) {
                return;
            }
            onClientThread(waiting.track(database.wallets().createWallet(name, null)), wallet -> {
                selectedAccount = name;
                selectedWalletId = wallet.id();
                loadAccounts();
            }, "Could not create the account");
        }));
    }

    // -------------------------------------------------------------- wallets

    private void createWallet(String accountId) {
        onClientThread(waiting.track(database.wallets().createWallet(accountId, null)), wallet -> {
            selectedAccount = accountId;
            selectedWalletId = wallet.id();
            loadAccounts();
        }, "Could not create the wallet");
    }

    private void beginRename(String accountId, Wallet wallet) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(RenameScreen.forWallet(wallet, this,
                label -> onClientThread(
                        waiting.track(accountWallets.rename(accountId, wallet.id(), label)),
                        renamed -> paint(accountId), "Could not rename the wallet")));
    }

    /**
     * Asks before deleting.
     *
     * <p>Deleting a wallet takes every address under it and cannot be undone, so it is the
     * one action here that does not happen on a single click.
     */
    private void confirmDeleteWallet(String accountId, Wallet wallet) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        deleteWallet(accountId, wallet);
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

    private void deleteWallet(String accountId, Wallet wallet) {
        onClientThread(waiting.track(accountWallets.delete(accountId, wallet.id())), deleted -> {
            if (wallet.id().equals(selectedWalletId)) {
                selectedWalletId = null;
                selectedAccount = null;
                addressPanel.setWallet(null);
            }
            // That may have been the account's last wallet, and an account with no wallets
            // stops existing — so the columns themselves can change here.
            loadAccounts();
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
        if (wallet == null || selectedAccount == null) {
            return;
        }
        String accountId = selectedAccount;
        onClientThread(waiting.track(
                        accountWallets.removeAddress(accountId, wallet.id(), address.platform())),
                removed -> paint(accountId), "Could not remove the address");
    }

    /** Records an address the player types, into the wallet they have selected. */
    private void addAddress() {
        if (minecraft == null) {
            return;
        }
        Wallet wallet = selectedWallet().orElse(null);
        if (wallet == null || selectedAccount == null) {
            show(Component.translatable("gui.mcagents.wallets.none_selected"), true);
            return;
        }
        String accountId = selectedAccount;
        minecraft.setScreenAndShow(new AddAddressScreen(wallet, this,
                (platform, value) -> bind(accountId, wallet, platform, value)));
    }

    /**
     * Starts the MetaMask handshake for one account.
     *
     * <p>The bridge, the browser, and the signature check all live behind
     * {@link WalletConnectScreen}; what comes back here is a list of addresses that have
     * already been proved — <strong>every</strong> account the browser shared, not only the
     * one it happened to have selected.
     *
     * <p>No wallet has to exist first. The handshake belongs to the account, and what comes
     * back decides how many wallets there are, so pre-making one would guess at a number the
     * browser has not given yet.
     */
    private void connect(String accountId) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(new WalletConnectScreen(accountId, this,
                proved -> bindProved(accountId, proved)));
    }

    private void bindProved(String accountId, List<WalletBinding> proved) {
        if (proved.isEmpty()) {
            return;
        }
        onClientThread(waiting.track(accountWallets.bindAll(accountId, proved)), bound -> {
            if (!bound.isEmpty()) {
                selectedAccount = accountId;
                selectedWalletId = bound.getFirst().id();
            }
            loadAccounts();
            show(Component.translatable("gui.mcagents.wallets.bound.many", bound.size()), false);
        }, "Could not store the proved addresses");
    }

    /**
     * Records one hand-typed address, through the account check rather than around it.
     *
     * <p>The wallet came from this account's own column, so the check passes — which is the
     * point: there is one way into the store and it is the one that knows about accounts.
     */
    private void bind(String accountId, Wallet wallet, ChainPlatform platform, String address) {
        onClientThread(waiting.track(accountWallets
                        .bind(accountId, wallet.id(), platform, address, false)),
                stored -> {
                    selectedAccount = accountId;
                    selectedWalletId = stored.id();
                    paint(accountId);
                    show(Component.translatable("gui.mcagents.wallets.bound.recorded",
                            platform.displayName()), false);
                }, "Could not store the address");
    }

    // --------------------------------------------------------- housekeeping

    private void show(Component message, boolean isProblem) {
        this.status = isProblem
                ? message.copy().withStyle(net.minecraft.ChatFormatting.RED)
                : message;
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        columns.forEach(column -> column.panel().render(graphics, mouseX, mouseY));
        addressPanel.render(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        Component header = waiting.isWaiting()
                ? waiting.label(Component.translatable("gui.mcagents.waiting"))
                : statusTicks > 0 ? status : title;

        // Between the paging arrows on the left and the buttons on the right, and clipped to
        // that width. A header drawn at its natural length is a header drawn over a button.
        int headerLeft = MARGIN + (allAccounts().size() > columnCount() ? 48 : 0);
        int headerWidth = Math.max(20, width - headerLeft - MARGIN - 150);
        graphics.text(font, font.plainSubstrByWidth(header.getString(), headerWidth),
                headerLeft, MARGIN + 6, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (AccountColumn column : columns) {
            if (column.panel().click(event.x(), event.y(), doubleClick)) {
                return true;
            }
        }
        if (addressPanel.click(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (AccountColumn column : columns) {
            if (column.panel().isOver(mouseX, mouseY) && column.panel().scrollBy(scrollY)) {
                return true;
            }
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
