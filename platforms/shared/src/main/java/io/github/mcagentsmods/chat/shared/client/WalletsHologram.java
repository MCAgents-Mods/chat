package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.api.wallet.ChainAddress;
import io.github.mcagentsmods.chat.api.wallet.ChainPlatform;
import io.github.mcagentsmods.chat.api.wallet.Wallet;
import io.github.mcagentsmods.chat.api.wallet.WalletBinding;
import io.github.mcagentsmods.chat.common.wallet.AccountWallets;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.api.hologram.HologramButton;
import io.github.mcagentsmods.core.api.hologram.SelectList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the player manages bound wallets, as a hologram WALLETS opens: three lists side by side,
 * one per level a player has.
 *
 * <p>An account holds several wallets — a main one, a trading one, one kept for a particular
 * server — and each wallet holds an address per chain, exactly as MetaMask does. So the page
 * is <strong>Accounts</strong> (ADD names a new one, CONNECT proves addresses through MetaMask,
 * NEW WALLET makes an empty wallet), the chosen account's <strong>Wallets</strong> (RENAME,
 * DELETE), and the chosen wallet's <strong>Addresses</strong> (ADD types one in, COPY puts the
 * whole address on the clipboard, REMOVE). Every question opens beside the page, in
 * {@link HologramDialogs} or {@link WalletDialogs}, and short notices go to the action bar.
 *
 * <h2>The rules this page keeps</h2>
 *
 * <p><strong>Nothing blocks.</strong> Every call into storage returns a future and every result
 * is applied back on the game's thread.
 *
 * <p><strong>A write acts only on its own account.</strong> Every write goes through
 * {@link AccountWallets}, which checks the account rather than trusting the caller.
 *
 * <p><strong>Nothing here needs the network.</strong> Reading, adding, renaming, copying and
 * deleting all work with no internet at all. Only proving an address through MetaMask needs a
 * browser, and failing at that leaves everything else untouched.
 *
 * <p><strong>Public addresses only.</strong> There is no field on this page, or any it opens,
 * for a private key or a recovery phrase.
 */
final class WalletsHologram extends HologramPage {

    /** The hologram's id. */
    static final String ID = "mcagents.wallets";

    private static final String PREFIX = "gui.mcagents.wallets.";

    private final LocalDatabase database;
    private final AccountWallets accountWallets;
    private final String homeAccount;

    private List<String> accounts = List.of();

    /** Every account's wallets read so far, so a list can be drawn without reading again. */
    private final Map<String, List<Wallet>> walletsByAccount = new LinkedHashMap<>();

    private String selectedAccount;
    private String selectedWalletId;
    private int selectedAddress = -1;

    private WalletsHologram(LocalDatabase database, String homeAccount) {
        this.database = database;
        this.accountWallets = new AccountWallets(database.wallets());
        this.homeAccount = homeAccount;
        this.selectedAccount = homeAccount == null || homeAccount.isBlank() ? null : homeAccount;
    }

    /** Opens the page on the player's own account. */
    static void open(LocalDatabase database) {
        WalletsHologram page = new WalletsHologram(database,
                PlayerAccounts.current(Minecraft.getInstance()));
        page.open();
        page.loadAccounts();
    }

    @Override
    Hologram build() {
        return Hologram.of(ID,
                new SelectList(PREFIX + "accounts", this::allAccounts,
                        () -> allAccounts().indexOf(selectedAccount), this::selectAccount,
                        List.of(new HologramButton(PREFIX + "account.add.short", this::addAccount),
                                new HologramButton(PREFIX + "connect.short", this::connect),
                                new HologramButton(PREFIX + "add.short", this::createWallet))),
                new SelectList(PREFIX + "list", this::walletRows, this::walletIndex, this::selectWallet,
                        List.of(new HologramButton(PREFIX + "rename.short", this::renameWallet),
                                new HologramButton(PREFIX + "delete.short", this::deleteWallet))),
                new SelectList(PREFIX + "addresses", this::addressRows, () -> selectedAddress,
                        index -> selectedAddress = index,
                        List.of(new HologramButton(PREFIX + "address.add.short", this::addAddress),
                                new HologramButton(PREFIX + "address.copy", this::copyAddress),
                                new HologramButton(PREFIX + "address.remove", this::removeAddress))));
    }

    // ---------------------------------------------------------------- lists

    /**
     * The accounts this page lists. The player's own is always first, even before it owns a
     * wallet: accounts have no table, one exists exactly as long as a wallet names it, so a new
     * player would otherwise open the page onto nothing at all.
     */
    private List<String> allAccounts() {
        List<String> all = new ArrayList<>(accounts);
        if (homeAccount != null && !homeAccount.isBlank() && !all.contains(homeAccount)) {
            all.addFirst(homeAccount);
        }
        return all;
    }

    private List<Wallet> wallets() {
        return selectedAccount == null ? List.of() : walletsByAccount.getOrDefault(selectedAccount, List.of());
    }

    /** One row per wallet: a tick when it was proved by signing, its label, how many addresses. */
    private List<String> walletRows() {
        return wallets().stream().map(wallet -> (wallet.hasVerifiedAddress() ? "✓ " : "")
                + wallet.label()
                + (wallet.addresses().isEmpty() ? "" : " (" + wallet.addresses().size() + ")")).toList();
    }

    private int walletIndex() {
        List<Wallet> wallets = wallets();
        for (int i = 0; i < wallets.size(); i++) {
            if (wallets.get(i).id().equals(selectedWalletId)) {
                return i;
            }
        }
        return -1;
    }

    private Optional<Wallet> selectedWallet() {
        int index = walletIndex();
        return index < 0 ? Optional.empty() : Optional.of(wallets().get(index));
    }

    private List<ChainAddress> addresses() {
        return selectedWallet().map(Wallet::addresses).orElse(List.of());
    }

    /** One row per address, shortened; COPY puts the whole of it on the clipboard. */
    private List<String> addressRows() {
        return addresses().stream().map(address -> (address.verified() ? "✓ " : "")
                + address.platform().displayName() + " " + address.masked()).toList();
    }

    private void selectAccount(int index) {
        List<String> all = allAccounts();
        if (index < 0 || index >= all.size()) {
            return;
        }
        String account = all.get(index);
        if (!account.equals(selectedAccount)) {
            selectedAccount = account;
            selectedWalletId = null;
            selectedAddress = -1;
        }
        paint(account);
    }

    private void selectWallet(int index) {
        List<Wallet> wallets = wallets();
        if (index < 0 || index >= wallets.size()) {
            return;
        }
        String id = wallets.get(index).id();
        if (!id.equals(selectedWalletId)) {
            selectedWalletId = id;
            selectedAddress = -1;
        }
    }

    // ----------------------------------------------------------------- data

    private void loadAccounts() {
        request(database.wallets().findAccounts(), stored -> {
            accounts = stored;
            if (selectedAccount != null && !allAccounts().contains(selectedAccount)) {
                selectedAccount = null;
                selectedWalletId = null;
            }
            if (selectedAccount == null && !allAccounts().isEmpty()) {
                selectedAccount = allAccounts().getFirst();
            }
            if (selectedAccount != null) {
                paint(selectedAccount);
            }
        }, "Could not read the wallet accounts");
    }

    /** Reads one account's wallets. */
    private void paint(String accountId) {
        request(database.wallets().findWalletsByAccount(accountId), stored -> {
            walletsByAccount.put(accountId, stored);
            if (accountId.equals(selectedAccount) && selectedAddress >= addresses().size()) {
                selectedAddress = -1;
            }
        }, "Could not read the wallets in this account");
    }

    // ------------------------------------------------------------- accounts

    /**
     * Makes an account by naming it. There is nothing else to create: an account exists as soon
     * as a wallet names it, so this makes the account's first wallet.
     */
    private void addAccount() {
        HologramDialogs.name(PREFIX + "account.add", I18n.get(PREFIX + "account.add.tip"), "",
                PREFIX + "account.add.save", name -> {
                    if (name.isBlank()) {
                        return;
                    }
                    request(database.wallets().createWallet(name, null), wallet -> {
                        selectedAccount = name;
                        selectedWalletId = wallet.id();
                        selectedAddress = -1;
                        loadAccounts();
                    }, "Could not create the account");
                });
    }

    /**
     * Starts the MetaMask handshake for the chosen account. What comes back is every address the
     * browser proved, filed into this account by {@link AccountWallets#bindAll}.
     */
    private void connect() {
        String accountId = selectedAccount;
        if (accountId == null) {
            notice(Component.translatable(PREFIX + "none_account"));
            return;
        }
        WalletDialogs.connect(accountId, proved -> bindProved(accountId, proved));
    }

    private void bindProved(String accountId, List<WalletBinding> proved) {
        if (proved.isEmpty()) {
            return;
        }
        request(accountWallets.bindAll(accountId, proved), bound -> {
            if (!bound.isEmpty()) {
                selectedAccount = accountId;
                selectedWalletId = bound.getFirst().id();
                selectedAddress = -1;
            }
            loadAccounts();
            notice(Component.translatable(PREFIX + "bound.many", bound.size()));
        }, "Could not store the proved addresses");
    }

    // -------------------------------------------------------------- wallets

    private void createWallet() {
        String accountId = selectedAccount;
        if (accountId == null) {
            notice(Component.translatable(PREFIX + "none_account"));
            return;
        }
        request(database.wallets().createWallet(accountId, null), wallet -> {
            selectedAccount = accountId;
            selectedWalletId = wallet.id();
            selectedAddress = -1;
            loadAccounts();
        }, "Could not create the wallet");
    }

    private void renameWallet() {
        Wallet wallet = selectedWallet().orElse(null);
        if (wallet == null) {
            notice(Component.translatable(PREFIX + "none_selected"));
            return;
        }
        String accountId = selectedAccount;
        HologramDialogs.name(PREFIX + "rename", wallet.label(), wallet.label(), PREFIX + "rename.save",
                label -> {
                    if (label.isBlank()) {
                        return;
                    }
                    request(accountWallets.rename(accountId, wallet.id(), label),
                            renamed -> paint(accountId), "Could not rename the wallet");
                });
    }

    /**
     * Asks before deleting: a wallet takes every address under it and cannot be undone, so it is
     * the one action here that does not happen on a single click.
     */
    private void deleteWallet() {
        Wallet wallet = selectedWallet().orElse(null);
        if (wallet == null) {
            notice(Component.translatable(PREFIX + "none_selected"));
            return;
        }
        String accountId = selectedAccount;
        HologramDialogs.confirm(PREFIX + "delete.title",
                I18n.get(PREFIX + "delete.message", wallet.label()), PREFIX + "delete.confirm",
                () -> request(accountWallets.delete(accountId, wallet.id()), deleted -> {
                    if (wallet.id().equals(selectedWalletId)) {
                        selectedWalletId = null;
                        selectedAddress = -1;
                    }
                    // That may have been the account's last wallet, and an account with no
                    // wallets stops existing, so the accounts are read again.
                    loadAccounts();
                }, "Could not delete the wallet"));
    }

    // ------------------------------------------------------------ addresses

    /** Records an address the player types, into the chosen wallet. */
    private void addAddress() {
        Wallet wallet = selectedWallet().orElse(null);
        if (wallet == null) {
            notice(Component.translatable(PREFIX + "none_selected"));
            return;
        }
        String accountId = selectedAccount;
        WalletDialogs.addAddress(wallet, (platform, value) -> bind(accountId, wallet, platform, value));
    }

    /**
     * Records one typed address through the account check rather than around it: there is one
     * way into the store, and it is the one that knows about accounts.
     */
    private void bind(String accountId, Wallet wallet, ChainPlatform platform, String address) {
        request(accountWallets.bind(accountId, wallet.id(), platform, address, false), stored -> {
            selectedAccount = accountId;
            selectedWalletId = stored.id();
            paint(accountId);
            notice(Component.translatable(PREFIX + "bound.recorded", platform.displayName()));
        }, "Could not store the address");
    }

    /**
     * Puts the whole address on the clipboard. The list shows a shortened form because forty
     * characters do not fit beside a chain name; this is how the player gets the real thing.
     */
    private void copyAddress() {
        selectedAddressValue().ifPresentOrElse(address -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(address.address());
            notice(Component.translatable(PREFIX + "copied", address.platform().displayName()));
        }, () -> notice(Component.translatable(PREFIX + "address.none_selected")));
    }

    private void removeAddress() {
        Wallet wallet = selectedWallet().orElse(null);
        ChainAddress address = selectedAddressValue().orElse(null);
        if (wallet == null || address == null) {
            notice(Component.translatable(PREFIX + "address.none_selected"));
            return;
        }
        String accountId = selectedAccount;
        selectedAddress = -1;
        request(accountWallets.removeAddress(accountId, wallet.id(), address.platform()),
                removed -> paint(accountId), "Could not remove the address");
    }

    private Optional<ChainAddress> selectedAddressValue() {
        List<ChainAddress> addresses = addresses();
        return selectedAddress < 0 || selectedAddress >= addresses.size()
                ? Optional.empty()
                : Optional.of(addresses.get(selectedAddress));
    }

    /** A short notice on the action bar, where the player is already looking. */
    private static void notice(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(message);
        }
    }
}
