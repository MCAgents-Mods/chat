package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.storage.DataAccessException;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.api.storage.WalletRepository;
import io.github.mcagents.clientfabric.api.wallet.ChainAddress;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wallets, the accounts above them, and the chain addresses under them. */
class SqliteWalletRepositoryTest {

    private static final String EVM = "0x4b4700B1a44eE8Dc0D8F0e0e6D2a0a2a1B6BB75c";
    private static final String BITCOIN = "bc1qm8tvxyxpxu9z7q4vvj5s9wgqr7dkxxk8jqygj";
    private static final String SOLANA = "9iA8dBeYtWkFXBVsZfPjKPmzYyDzKQvNqYmvCzjJuVF";
    private static final String TRON = "TQu217PmqPzr7Zt9vY3rKxYyGqWLnbME7Rn";

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private WalletRepository wallets;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        wallets = database.wallets();
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void keepsSeveralWalletsUnderOneAccount() {
        await(wallets.createWallet("Personal", "Main"));
        await(wallets.createWallet("Personal", "Trading"));

        List<Wallet> stored = await(wallets.findWalletsByAccount("Personal"));

        assertEquals(2, stored.size());
        assertEquals(List.of("Trading", "Main"), stored.stream().map(Wallet::label).toList(),
                "the most recently changed wallet is listed first");
    }

    @Test
    void keepsAccountsApart() {
        await(wallets.createWallet("Personal", "Main"));
        await(wallets.createWallet("Alt", "Spare"));

        assertEquals(List.of("Main"),
                await(wallets.findWalletsByAccount("Personal")).stream().map(Wallet::label).toList());
        assertEquals(List.of("Alt", "Personal"), await(wallets.findAccounts()));
    }

    @Test
    void listsOnlyAccountsThatStillOwnAWallet() {
        // Accounts have no table of their own, so deleting the last wallet is what makes an
        // account stop existing. Nothing is left behind for the player to tidy up.
        Wallet wallet = await(wallets.createWallet("Temporary", "Only"));

        assertTrue(await(wallets.findAccounts()).contains("Temporary"));

        await(wallets.deleteWallet(wallet.id()));

        assertFalse(await(wallets.findAccounts()).contains("Temporary"));
    }

    @Test
    void holdsOneAddressPerChainInOneWallet() {
        Wallet wallet = await(wallets.createWallet("Personal", "Multichain"));

        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.ETHEREUM, EVM, true));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.BITCOIN, BITCOIN, false));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.SOLANA, SOLANA, false));
        Wallet filled = await(wallets.addOrUpdateAddress(
                wallet.id(), ChainPlatform.TRON, TRON, false));

        assertEquals(4, filled.addresses().size());
        assertEquals(EVM, filled.address(ChainPlatform.ETHEREUM).orElseThrow().address());
        assertEquals(SOLANA, filled.address(ChainPlatform.SOLANA).orElseThrow().address());
    }

    @Test
    void listsAddressesInTheOrderTheChainsAreDeclared() {
        // The interface renders them in this order, so the store hands them over in it
        // rather than in whatever order they were added.
        Wallet wallet = await(wallets.createWallet("Personal", "Ordered"));

        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.TRON, TRON, false));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.BITCOIN, BITCOIN, false));
        Wallet filled = await(wallets.addOrUpdateAddress(
                wallet.id(), ChainPlatform.ETHEREUM, EVM, true));

        assertEquals(
                List.of(ChainPlatform.ETHEREUM, ChainPlatform.BITCOIN, ChainPlatform.TRON),
                filled.addresses().stream().map(ChainAddress::platform).toList());
    }

    @Test
    void correctsAnAddressRatherThanAddingASecondOneForTheSameChain() {
        Wallet wallet = await(wallets.createWallet("Personal", "Typo"));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.ETHEREUM,
                "0x0000000000000000000000000000000000000001", false));

        Wallet corrected = await(wallets.addOrUpdateAddress(
                wallet.id(), ChainPlatform.ETHEREUM, EVM, true));

        assertEquals(1, corrected.addresses().size());
        ChainAddress address = corrected.address(ChainPlatform.ETHEREUM).orElseThrow();
        assertEquals(EVM, address.address());
        assertTrue(address.verified(), "re-binding by signature makes the address proved");
    }

    @Test
    void remembersWhetherAnAddressWasProvedOrTypedIn() {
        Wallet wallet = await(wallets.createWallet("Personal", "Mixed"));

        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.ETHEREUM, EVM, true));
        Wallet stored = await(wallets.addOrUpdateAddress(
                wallet.id(), ChainPlatform.BITCOIN, BITCOIN, false));

        assertTrue(stored.address(ChainPlatform.ETHEREUM).orElseThrow().verified());
        assertFalse(stored.address(ChainPlatform.BITCOIN).orElseThrow().verified());
        assertTrue(stored.hasVerifiedAddress());
    }

    @Test
    void deletingAWalletRemovesItsAddresses() {
        // wallet_platform_addresses declares ON DELETE CASCADE, which SQLite only honours
        // when foreign key enforcement is switched on. This test is what proves it is.
        Wallet wallet = await(wallets.createWallet("Personal", "Doomed"));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.ETHEREUM, EVM, true));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.SOLANA, SOLANA, false));

        assertTrue(await(wallets.deleteWallet(wallet.id())));

        assertTrue(await(wallets.findWalletById(wallet.id())).isEmpty());
        assertEquals(0, countAddressRows(), "the addresses should have gone with the wallet");
    }

    @Test
    void removesOneChainWithoutTouchingTheRest() {
        Wallet wallet = await(wallets.createWallet("Personal", "Pruned"));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.ETHEREUM, EVM, true));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.BITCOIN, BITCOIN, false));

        assertTrue(await(wallets.removeAddress(wallet.id(), ChainPlatform.BITCOIN)));

        Wallet stored = await(wallets.findWalletById(wallet.id())).orElseThrow();
        assertEquals(1, stored.addresses().size());
        assertTrue(stored.address(ChainPlatform.ETHEREUM).isPresent());
    }

    @Test
    void refusesToBindAnAddressToAWalletThatDoesNotExist() {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> wallets.addOrUpdateAddress(
                        "no-such-wallet", ChainPlatform.ETHEREUM, EVM, true).join());

        assertTrue(failure.getCause() instanceof DataAccessException,
                "expected a DataAccessException, got " + failure.getCause());
        assertEquals(0, countAddressRows(), "nothing should have been written");
    }

    @Test
    void refusesAWalletWithNoAccountToBelongTo() {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> wallets.createWallet("  ", "Orphan").join());

        assertTrue(failure.getCause() instanceof DataAccessException,
                "expected a DataAccessException, got " + failure.getCause());
    }

    @Test
    void namesAnUnlabelledWalletByDefault() {
        Wallet wallet = await(wallets.createWallet("Personal", null));

        assertEquals(Wallet.DEFAULT_LABEL, wallet.label());
        assertTrue(wallet.active(), "a new wallet is in use until the player says otherwise");
        assertTrue(wallet.addresses().isEmpty());
    }

    @Test
    void renamesAndSetsAsideAWalletWithoutDeletingIt() {
        Wallet wallet = await(wallets.createWallet("Personal", "Before"));

        assertTrue(await(wallets.renameWallet(wallet.id(), "After")));
        assertTrue(await(wallets.setWalletActive(wallet.id(), false)));

        Wallet stored = await(wallets.findWalletById(wallet.id())).orElseThrow();
        assertEquals("After", stored.label());
        assertFalse(stored.active());
    }

    @Test
    void reportsThatNothingHappenedForAWalletThatIsNotThere() {
        assertFalse(await(wallets.deleteWallet("no-such-wallet")));
        assertFalse(await(wallets.renameWallet("no-such-wallet", "Ghost")));
        assertFalse(await(wallets.setWalletActive("no-such-wallet", false)));
        assertFalse(await(wallets.removeAddress("no-such-wallet", ChainPlatform.ETHEREUM)));
    }

    @Test
    void readsBackAChainItDoesNotRecogniseAsOther() {
        // A row written by a newer version of the mod must not make this one unable to read
        // the player's wallets.
        Wallet wallet = await(wallets.createWallet("Personal", "Future"));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.OTHER, "future-chain-address", false));

        Wallet stored = await(wallets.findWalletById(wallet.id())).orElseThrow();

        assertEquals(ChainPlatform.OTHER,
                stored.addresses().getFirst().platform());
    }

    @Test
    void survivesBeingReopened() {
        // The whole point of the store: what the player bound is still there next session.
        Wallet wallet = await(wallets.createWallet("Personal", "Persistent"));
        await(wallets.addOrUpdateAddress(wallet.id(), ChainPlatform.ETHEREUM, EVM, true));
        database.close();

        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        wallets = database.wallets();

        Wallet stored = await(wallets.findWalletById(wallet.id())).orElseThrow();
        assertEquals(EVM, stored.address(ChainPlatform.ETHEREUM).orElseThrow().address());
    }

    /**
     * Counts the address rows directly, past the repository.
     *
     * <p>Going through {@code findWalletsByAccount} would count nothing once the wallet is
     * gone, and so would report a clean cascade whether or not the rows were actually
     * removed. Orphans are only visible from outside.
     */
    private long countAddressRows() {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + gameDirectory.resolve("MCAgents/data.db"));
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows =
                     statement.executeQuery("SELECT COUNT(*) FROM wallet_platform_addresses")) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not count the stored addresses", e);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
