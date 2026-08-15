package io.github.mcagents.clientfabric.api.storage;

import io.github.mcagents.clientfabric.api.wallet.ChainAddress;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.Wallet;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The wallets the player has bound, and the chain addresses under each one.
 *
 * <p>Three levels, and each exists because the level above it is not enough on its own: an
 * <em>account</em> holds many wallets, a <em>wallet</em> holds many chain addresses, and an
 * address belongs to exactly one chain. Flattening any pair of them loses something a
 * player actually has.
 *
 * <p><strong>Public addresses only.</strong> Nothing here stores, accepts, or returns a
 * private key or a recovery phrase.
 *
 * <p>Every method is asynchronous — see {@link LocalDatabase} for why.
 */
public interface WalletRepository {

    /**
     * Every account that owns at least one wallet, alphabetically.
     *
     * <p>Accounts are not stored in their own table: an account exists exactly as long as a
     * wallet names it, and stops existing when the last one is deleted. That keeps the
     * player from accumulating empty accounts they then have to tidy up.
     */
    CompletableFuture<List<String>> findAccounts();

    /** Every wallet under one account, most recently changed first, addresses included. */
    CompletableFuture<List<Wallet>> findWalletsByAccount(String accountId);

    /** One wallet with its addresses, or empty if it has been deleted. */
    CompletableFuture<Optional<Wallet>> findWalletById(String walletId);

    /**
     * One wallet, but only if it belongs to the account named.
     *
     * <p>This is the account boundary, and it exists because a wallet id alone does not
     * carry one. Anything acting on a wallet on behalf of an account — a page showing that
     * account's panel, a screen with that account selected, a handshake that named it in the
     * message the player signed — resolves the wallet through here rather than by id, so a
     * request that names one account and a wallet in another finds nothing rather than
     * quietly writing across the boundary.
     *
     * <p>Empty means "not in that account", which covers both a wallet that has been deleted
     * and a wallet that belongs to somebody else. The caller cannot tell the two apart, and
     * has no reason to.
     */
    CompletableFuture<Optional<Wallet>> findWalletInAccount(String accountId, String walletId);

    /**
     * Creates an empty wallet under an account, creating the account by naming it.
     *
     * @param label what the player calls it; blank falls back to {@link Wallet#DEFAULT_LABEL}
     */
    CompletableFuture<Wallet> createWallet(String accountId, String label);

    /** Renames a wallet. A blank label falls back to {@link Wallet#DEFAULT_LABEL}. */
    CompletableFuture<Boolean> renameWallet(String walletId, String label);

    /**
     * Binds an address to a wallet on one chain, replacing whatever was on that chain.
     *
     * <p>Replacing rather than accumulating is the point: a wallet has one address per
     * chain, so re-binding is how a player corrects a typo, and a second row for the same
     * chain would be a contradiction rather than a history.
     *
     * @param verified whether the player proved control by signing, rather than typing it
     * @return the wallet as it now stands
     */
    CompletableFuture<Wallet> addOrUpdateAddress(
            String walletId, ChainPlatform platform, String address, boolean verified);

    /** Removes one chain's address from a wallet. */
    CompletableFuture<Boolean> removeAddress(String walletId, ChainPlatform platform);

    /**
     * Deletes a wallet and every {@link ChainAddress} under it.
     *
     * <p>The addresses go with it through {@code ON DELETE CASCADE}, so a deleted wallet
     * never leaves rows behind pointing at nothing.
     */
    CompletableFuture<Boolean> deleteWallet(String walletId);

    /**
     * Marks a wallet as in use, or sets it aside without deleting it.
     *
     * <p>A flag on the wallet, not a single selection — a player may well be using two at
     * once, and a store that silently deactivated the others would be deciding that for
     * them.
     */
    CompletableFuture<Boolean> setWalletActive(String walletId, boolean active);
}
