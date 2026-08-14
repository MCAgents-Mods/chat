package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.storage.DataAccessException;
import io.github.mcagents.clientfabric.api.storage.WalletRepository;
import io.github.mcagents.clientfabric.api.wallet.ChainAddress;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.Wallet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Wallets and their chain addresses, in {@code player_wallets} and
 * {@code wallet_platform_addresses}.
 *
 * <p>A wallet is read with its addresses or not at all — every method that returns a
 * {@link Wallet} fills in the addresses first, because a wallet without them is not
 * something any caller has a use for. The addresses come back in one query per read rather
 * than one per wallet, so listing an account is two round trips whatever it holds.
 *
 * <p>Accounts have no table. An account exists exactly as long as a wallet names it, which
 * is what keeps a player from collecting empty accounts they then have to tidy up.
 */
final class SqliteWalletRepository implements WalletRepository {

    private static final String WALLET_COLUMNS =
            "id, account_id, wallet_label, is_active, created_at, updated_at";
    private static final String ADDRESS_COLUMNS =
            "id, wallet_id, platform, address, is_verified, created_at";

    private final SqlWorker worker;

    SqliteWalletRepository(SqlWorker worker) {
        this.worker = worker;
    }

    @Override
    public CompletableFuture<List<String>> findAccounts() {
        return worker.submit("Reading the wallet accounts", connection -> {
            String sql = "SELECT DISTINCT account_id FROM player_wallets "
                    + "ORDER BY account_id COLLATE NOCASE ASC";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                List<String> accounts = new ArrayList<>();
                while (rows.next()) {
                    accounts.add(rows.getString("account_id"));
                }
                return List.copyOf(accounts);
            }
        });
    }

    @Override
    public CompletableFuture<List<Wallet>> findWalletsByAccount(String accountId) {
        return worker.submit("Reading the wallets in an account", connection -> {
            // rowid breaks the tie, and it has to: CURRENT_TIMESTAMP resolves to the
            // second, so two wallets made in the same breath carry identical timestamps and
            // would otherwise come back in whatever order SQLite felt like. Insertion order
            // is both stable and the one a player would expect.
            String sql = "SELECT " + WALLET_COLUMNS + " FROM player_wallets "
                    + "WHERE account_id = ? ORDER BY updated_at DESC, created_at DESC, rowid DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, required(accountId, "an account id"));
                try (ResultSet rows = statement.executeQuery()) {
                    return withAddresses(connection, readWalletRows(rows));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Wallet>> findWalletById(String walletId) {
        return worker.submit("Reading a wallet", connection -> findWallet(connection, walletId));
    }

    @Override
    public CompletableFuture<Wallet> createWallet(String accountId, String label) {
        String id = UUID.randomUUID().toString();

        return worker.submitInTransaction("Creating a wallet", connection -> {
            String sql = "INSERT INTO player_wallets (id, account_id, wallet_label) "
                    + "VALUES (?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.setString(2, required(accountId, "an account id"));
                statement.setString(3, labelOrDefault(label));
                statement.executeUpdate();
            }
            return findWallet(connection, id)
                    .orElseThrow(() -> new DataAccessException("The wallet was not stored"));
        });
    }

    @Override
    public CompletableFuture<Boolean> renameWallet(String walletId, String label) {
        String resolvedLabel = labelOrDefault(label);

        return worker.submit("Renaming a wallet", connection -> {
            String sql = "UPDATE player_wallets "
                    + "SET wallet_label = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, resolvedLabel);
                statement.setString(2, walletId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Wallet> addOrUpdateAddress(
            String walletId, ChainPlatform platform, String address, boolean verified) {
        if (platform == null) {
            return CompletableFuture.failedFuture(
                    new DataAccessException("An address needs a chain to be on"));
        }
        String id = UUID.randomUUID().toString();

        return worker.submitInTransaction("Binding an address to a wallet", connection -> {
            String value = required(address, "an address");
            if (findWallet(connection, walletId).isEmpty()) {
                throw new DataAccessException("No wallet with that id exists");
            }
            // One address per chain per wallet: re-binding corrects what is there rather
            // than adding a second row that contradicts it. The id of the existing row is
            // kept, so a correction does not look like a different address to anything
            // holding on to it.
            String sql = "INSERT INTO wallet_platform_addresses "
                    + "(id, wallet_id, platform, address, is_verified) VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT (wallet_id, platform) DO UPDATE SET "
                    + "address = excluded.address, is_verified = excluded.is_verified";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.setString(2, walletId);
                statement.setString(3, platform.storageName());
                statement.setString(4, value);
                statement.setBoolean(5, verified);
                statement.executeUpdate();
            }
            touchWallet(connection, walletId);

            return findWallet(connection, walletId)
                    .orElseThrow(() -> new DataAccessException("The address was not stored"));
        });
    }

    @Override
    public CompletableFuture<Boolean> removeAddress(String walletId, ChainPlatform platform) {
        if (platform == null) {
            return CompletableFuture.completedFuture(false);
        }
        return worker.submitInTransaction("Removing an address from a wallet", connection -> {
            String sql = "DELETE FROM wallet_platform_addresses "
                    + "WHERE wallet_id = ? AND platform = ?";
            boolean removed;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, walletId);
                statement.setString(2, platform.storageName());
                removed = statement.executeUpdate() > 0;
            }
            if (removed) {
                touchWallet(connection, walletId);
            }
            return removed;
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteWallet(String walletId) {
        // The addresses go with it: wallet_platform_addresses declares ON DELETE CASCADE,
        // and the connection enables foreign key enforcement so that it is applied.
        return worker.submit("Deleting a wallet", connection -> {
            String sql = "DELETE FROM player_wallets WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, walletId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> setWalletActive(String walletId, boolean active) {
        return worker.submit("Changing whether a wallet is in use", connection -> {
            String sql = "UPDATE player_wallets "
                    + "SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBoolean(1, active);
                statement.setString(2, walletId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------- reading

    private static Optional<Wallet> findWallet(Connection connection, String walletId)
            throws SQLException {
        String sql = "SELECT " + WALLET_COLUMNS + " FROM player_wallets WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, walletId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                WalletRow row = readWalletRow(rows);
                return Optional.of(row.toWallet(readAddresses(connection, List.of(row.id()))
                        .getOrDefault(row.id(), List.of())));
            }
        }
    }

    /**
     * Fills in every wallet's addresses with one further query.
     *
     * <p>One query for the wallets and one for their addresses, rather than one per wallet:
     * this runs on the single database thread, and a read that costs a round trip per row
     * is the kind that starts to show when a player has collected a few.
     */
    private static List<Wallet> withAddresses(Connection connection, List<WalletRow> rows)
            throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<ChainAddress>> byWallet =
                readAddresses(connection, rows.stream().map(WalletRow::id).toList());

        List<Wallet> wallets = new ArrayList<>(rows.size());
        for (WalletRow row : rows) {
            wallets.add(row.toWallet(byWallet.getOrDefault(row.id(), List.of())));
        }
        return List.copyOf(wallets);
    }

    private static Map<String, List<ChainAddress>> readAddresses(
            Connection connection, List<String> walletIds) throws SQLException {
        if (walletIds.isEmpty()) {
            return Map.of();
        }
        // The placeholders are built from the number of ids, never from their values —
        // every id is still bound. See .agents/database/schema.md.
        String placeholders = String.join(",", Collections.nCopies(walletIds.size(), "?"));
        String sql = "SELECT " + ADDRESS_COLUMNS + " FROM wallet_platform_addresses "
                + "WHERE wallet_id IN (" + placeholders + ")";

        Map<String, List<ChainAddress>> byWallet = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < walletIds.size(); i++) {
                statement.setString(i + 1, walletIds.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byWallet.computeIfAbsent(rows.getString("wallet_id"), key -> new ArrayList<>())
                            .add(readAddress(rows));
                }
            }
        }
        // Listed in the order the enum declares, which is the order MetaMask itself shows
        // them in, so the list does not reshuffle as addresses are added.
        byWallet.replaceAll((walletId, addresses) -> addresses.stream()
                .sorted(Comparator.comparingInt(address -> address.platform().ordinal()))
                .toList());
        return byWallet;
    }

    private static List<WalletRow> readWalletRows(ResultSet rows) throws SQLException {
        List<WalletRow> wallets = new ArrayList<>();
        while (rows.next()) {
            wallets.add(readWalletRow(rows));
        }
        return wallets;
    }

    private static WalletRow readWalletRow(ResultSet rows) throws SQLException {
        String label = rows.getString("wallet_label");
        return new WalletRow(
                rows.getString("id"),
                rows.getString("account_id"),
                label == null || label.isBlank() ? Wallet.DEFAULT_LABEL : label,
                rows.getBoolean("is_active"),
                SqlValues.readInstant(rows.getString("created_at")),
                SqlValues.readInstant(rows.getString("updated_at")));
    }

    private static ChainAddress readAddress(ResultSet rows) throws SQLException {
        return new ChainAddress(
                rows.getString("id"),
                ChainPlatform.fromStorageName(rows.getString("platform")),
                rows.getString("address"),
                rows.getBoolean("is_verified"),
                SqlValues.readInstant(rows.getString("created_at")));
    }

    private static void touchWallet(Connection connection, String walletId) throws SQLException {
        String sql = "UPDATE player_wallets SET updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, walletId);
            statement.executeUpdate();
        }
    }

    private static String labelOrDefault(String label) {
        return label == null || label.isBlank() ? Wallet.DEFAULT_LABEL : label.trim();
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new DataAccessException("This call needs " + what);
        }
        return value.trim();
    }

    /** A wallet row before its addresses have been read. */
    private record WalletRow(
            String id,
            String accountId,
            String label,
            boolean active,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {

        Wallet toWallet(List<ChainAddress> addresses) {
            return new Wallet(id, accountId, label, active, addresses, createdAt, updatedAt);
        }
    }
}
