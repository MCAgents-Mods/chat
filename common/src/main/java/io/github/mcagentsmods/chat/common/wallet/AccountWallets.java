package io.github.mcagentsmods.chat.common.wallet;

import io.github.mcagentsmods.chat.api.storage.WalletRepository;
import io.github.mcagentsmods.chat.api.wallet.ChainAddress;
import io.github.mcagentsmods.chat.api.wallet.ChainPlatform;
import io.github.mcagentsmods.chat.api.wallet.Wallet;
import io.github.mcagentsmods.chat.api.wallet.WalletBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Filing proved addresses into the account that asked for them, and nowhere else.
 *
 * <h2>One wallet per address</h2>
 *
 * <p>A handshake now comes back with every address the browser shared, and each of those is
 * a separate wallet in the player's eyes — that is what MetaMask calls an account and what
 * the interface lists as a row. So five proved addresses become five wallets, not five
 * addresses crowded into one: a wallet holds one address <em>per chain</em>, so filing them
 * together would mean each Ethereum address overwriting the one before it and four of the
 * five signatures being thrown away.
 *
 * <h2>The account boundary</h2>
 *
 * <p><strong>Every read and every write here is scoped by account.</strong> Existing wallets
 * are looked up with {@link WalletRepository#findWalletsByAccount} and a named wallet with
 * {@link WalletRepository#findWalletInAccount}, so an address bound from one account's panel
 * can never land in, or be matched against, a wallet belonging to another. The account is
 * also what the player signed: the challenge message names it, so binding somewhere else
 * would mean storing something other than what was agreed to.
 *
 * <h2>Why it lives in common/</h2>
 *
 * <p>Two callers file the same results — the in-game screen and the web console's wallets
 * page — and a rule about where a wallet may go is not a rule worth having twice.
 */
public final class AccountWallets {

    private final WalletRepository wallets;

    public AccountWallets(WalletRepository wallets) {
        this.wallets = Objects.requireNonNull(wallets, "wallets");
    }

    /**
     * Files every proved address into one account, one wallet per address.
     *
     * <p>Three outcomes per address, in order:
     *
     * <ol>
     *   <li><strong>A wallet in this account already holds it</strong> on that chain — that
     *       wallet is updated, so re-running a handshake corrects and re-verifies rather
     *       than collecting duplicates.</li>
     *   <li><strong>A wallet in this account holds nothing at all</strong> — it is filled.
     *       That is the empty wallet the player made a moment ago by pressing Add; leaving
     *       it behind and creating a second one would look like the button had failed.</li>
     *   <li><strong>Otherwise a new wallet</strong>, labelled with the shortened address so
     *       the row means something before the player renames it.</li>
     * </ol>
     *
     * <p>Sequential rather than parallel: the choices above depend on what the previous
     * address did, and the store runs on one thread regardless.
     *
     * @return the wallets as they now stand, in the order the addresses were proved
     */
    public CompletableFuture<List<Wallet>> bindAll(String accountId, List<WalletBinding> proved) {
        String account = requireAccount(accountId);
        List<WalletBinding> toBind = List.copyOf(Objects.requireNonNullElse(proved, List.of()));
        if (toBind.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return wallets.findWalletsByAccount(account).thenCompose(existing -> {
            List<Wallet> known = new ArrayList<>(existing);
            List<Wallet> bound = new ArrayList<>();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

            for (WalletBinding binding : toBind) {
                chain = chain.thenCompose(ignored -> bindOne(account, known, binding)
                        .thenAccept(wallet -> {
                            known.removeIf(entry -> entry.id().equals(wallet.id()));
                            known.add(wallet);
                            bound.add(wallet);
                        }));
            }
            return chain.thenApply(ignored -> List.copyOf(bound));
        });
    }

    /**
     * Binds one address to one named wallet, refusing a wallet outside the account.
     *
     * @throws IllegalArgumentException if the wallet is not in that account — including when
     *                                  there is no such wallet at all
     */
    public CompletableFuture<Wallet> bind(String accountId, String walletId,
                                          ChainPlatform platform, String address,
                                          boolean verified) {
        String account = requireAccount(accountId);
        Objects.requireNonNull(platform, "platform");

        return wallets.findWalletInAccount(account, walletId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "That wallet is not in this account."));
            }
            return wallets.addOrUpdateAddress(walletId, platform, address, verified);
        });
    }

    /**
     * Renames a wallet, refusing one outside the account.
     *
     * @return whether a wallet in that account was renamed
     */
    public CompletableFuture<Boolean> rename(String accountId, String walletId, String label) {
        return within(accountId, walletId,
                wallet -> wallets.renameWallet(wallet.id(), label));
    }

    /**
     * Deletes a wallet, refusing one outside the account.
     *
     * @return whether a wallet in that account was deleted
     */
    public CompletableFuture<Boolean> delete(String accountId, String walletId) {
        return within(accountId, walletId, wallet -> wallets.deleteWallet(wallet.id()));
    }

    /**
     * Removes one chain's address from a wallet, refusing one outside the account.
     *
     * @return whether a wallet in that account was changed
     */
    public CompletableFuture<Boolean> removeAddress(
            String accountId, String walletId, ChainPlatform platform) {
        if (platform == null) {
            return CompletableFuture.completedFuture(false);
        }
        return within(accountId, walletId,
                wallet -> wallets.removeAddress(wallet.id(), platform));
    }

    // ----------------------------------------------------------- internals

    private CompletableFuture<Boolean> within(
            String accountId, String walletId,
            Function<Wallet, CompletableFuture<Boolean>> action) {
        return wallets.findWalletInAccount(requireAccount(accountId), walletId)
                .thenCompose(found -> found
                        .map(action)
                        // Not an error: "no wallet of yours changed" is exactly what the
                        // caller needs to know, and it is the same answer whether the wallet
                        // is gone or was never theirs.
                        .orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

    private CompletableFuture<Wallet> bindOne(
            String account, List<Wallet> known, WalletBinding binding) {
        Optional<Wallet> holder = holding(known, binding);
        if (holder.isPresent()) {
            return wallets.addOrUpdateAddress(holder.get().id(), binding.platform(),
                    binding.address(), true);
        }

        Optional<Wallet> empty = known.stream()
                .filter(wallet -> wallet.addresses().isEmpty())
                .findFirst();
        if (empty.isPresent()) {
            return wallets.addOrUpdateAddress(empty.get().id(), binding.platform(),
                    binding.address(), true);
        }

        // Named after its shortened address: five wallets called "New Wallet" tell the player
        // nothing, and the address is what tells them apart until they are renamed.
        return wallets.createWallet(account, ChainAddress.masked(binding.address()))
                .thenCompose(created -> wallets.addOrUpdateAddress(created.id(),
                        binding.platform(), binding.address(), true));
    }

    /** A wallet in this account already holding this address on this chain. */
    private static Optional<Wallet> holding(List<Wallet> known, WalletBinding binding) {
        return known.stream()
                .filter(wallet -> wallet.address(binding.platform())
                        .map(ChainAddress::address)
                        .filter(stored -> stored.equalsIgnoreCase(binding.address()))
                        .isPresent())
                .findFirst();
    }

    private static String requireAccount(String accountId) {
        String account = accountId == null ? "" : accountId.trim();
        if (account.isBlank()) {
            throw new IllegalArgumentException("A wallet needs an account to belong to");
        }
        return account;
    }
}
