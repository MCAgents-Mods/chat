package io.github.mcagents.clientfabric.common.wallet;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.api.wallet.ChainAddress;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.Wallet;
import io.github.mcagents.clientfabric.api.wallet.WalletBinding;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a handshake's results land, and where they may not.
 *
 * <p>Two questions, and the second is the one worth the tests: a trip to the browser now
 * comes back with several proved addresses, so something has to decide how many wallets that
 * is — and whatever decides has to be unable to put one in somebody else's account.
 */
class AccountWalletsTest {

    private static final String MINE = "Steve";
    private static final String THEIRS = "Alex";

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private AccountWallets accountWallets;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        accountWallets = new AccountWallets(database.wallets());
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void givesEveryProvedAddressAWalletOfItsOwn() {
        // Five addresses in one wallet would be four addresses lost: a wallet holds one
        // address per chain, so each Ethereum address would overwrite the one before it.
        List<Wallet> bound = await(accountWallets.bindAll(MINE,
                List.of(proved("0xaaaA0000000000000000000000000000000000A1"),
                        proved("0xbBbb0000000000000000000000000000000000B2"),
                        proved("0xcCcc0000000000000000000000000000000000C3"))));

        assertEquals(3, bound.size());
        assertEquals(3, walletsIn(MINE).size());
        assertEquals(List.of("0xaaaA0000000000000000000000000000000000A1",
                        "0xbBbb0000000000000000000000000000000000B2",
                        "0xcCcc0000000000000000000000000000000000C3"),
                walletsIn(MINE).stream()
                        .map(wallet -> wallet.address(ChainPlatform.ETHEREUM).orElseThrow())
                        .map(ChainAddress::address)
                        .sorted()
                        .toList());
    }

    @Test
    void marksEveryOneOfThemProved() {
        await(accountWallets.bindAll(MINE, List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));

        assertTrue(walletsIn(MINE).getFirst().hasVerifiedAddress(),
                "these came back from a signature check; recording them as typed in would be a lie");
    }

    @Test
    void namesANewWalletAfterTheAddressItHolds() {
        // Five wallets all called "New Wallet" tell the player nothing about which is which.
        await(accountWallets.bindAll(MINE, List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));

        assertEquals("0xaaaA0…000A1", walletsIn(MINE).getFirst().label());
    }

    @Test
    void fillsAnEmptyWalletRatherThanLeavingItBehind() {
        // The wallet the player made a moment ago by pressing Add. Creating a second one
        // beside it would look like the button had failed.
        Wallet empty = await(database.wallets().createWallet(MINE, "Main"));

        await(accountWallets.bindAll(MINE, List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));

        List<Wallet> stored = walletsIn(MINE);
        assertEquals(1, stored.size());
        assertEquals(empty.id(), stored.getFirst().id());
        assertEquals("Main", stored.getFirst().label(), "and it keeps the name the player gave it");
    }

    @Test
    void updatesTheWalletThatAlreadyHoldsAnAddressInsteadOfDuplicatingIt() {
        await(accountWallets.bindAll(MINE, List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));

        await(accountWallets.bindAll(MINE, List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));

        assertEquals(1, walletsIn(MINE).size(),
                "running the handshake twice corrects what is there; it does not collect copies");
    }

    @Test
    void neverMatchesAnAddressAgainstAnotherAccountsWallet() {
        // The same MetaMask account may perfectly well be bound under two identities, and
        // binding it under one must not reach into the other.
        await(accountWallets.bindAll(THEIRS, List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));

        await(accountWallets.bindAll(MINE, List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));

        assertEquals(1, walletsIn(THEIRS).size());
        assertEquals(1, walletsIn(MINE).size());
    }

    @Test
    void refusesToBindIntoAWalletFromAnotherAccount() {
        Wallet theirs = await(database.wallets().createWallet(THEIRS, "Theirs"));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> accountWallets.bind(MINE, theirs.id(), ChainPlatform.ETHEREUM,
                                "0xaaaA0000000000000000000000000000000000A1", true)
                        .join());

        assertTrue(thrown.getCause() instanceof IllegalArgumentException,
                "expected an IllegalArgumentException, got " + thrown.getCause());
        assertTrue(await(database.wallets().findWalletById(theirs.id()))
                .orElseThrow().addresses().isEmpty(),
                "and nothing was written across the boundary");
    }

    @Test
    void refusesToRenameOrDeleteAWalletFromAnotherAccount() {
        Wallet theirs = await(database.wallets().createWallet(THEIRS, "Theirs"));

        assertFalse(await(accountWallets.rename(MINE, theirs.id(), "Mine now")));
        assertFalse(await(accountWallets.delete(MINE, theirs.id())));
        assertFalse(await(accountWallets.removeAddress(MINE, theirs.id(), ChainPlatform.ETHEREUM)));

        assertEquals("Theirs",
                await(database.wallets().findWalletById(theirs.id())).orElseThrow().label());
    }

    @Test
    void actsOnAWalletThatIsInTheAccount() {
        // The other half of the boundary: it has to let the owner through.
        Wallet mine = await(database.wallets().createWallet(MINE, "Mine"));

        assertTrue(await(accountWallets.rename(MINE, mine.id(), "Renamed")));
        assertEquals("Renamed",
                await(database.wallets().findWalletById(mine.id())).orElseThrow().label());
        assertTrue(await(accountWallets.delete(MINE, mine.id())));
    }

    @Test
    void bindsNothingWhenNothingWasProved() {
        // A cancelled handshake settles with an empty list rather than a failure, and an
        // empty list must not conjure a wallet.
        assertEquals(List.of(), await(accountWallets.bindAll(MINE, List.of())));
        assertEquals(List.of(), walletsIn(MINE));
    }

    @Test
    void refusesAnAccountThatIsNotNamed() {
        assertThrows(IllegalArgumentException.class,
                () -> accountWallets.bindAll("  ", List.of(proved("0xaaaA0000000000000000000000000000000000A1"))));
    }

    // ------------------------------------------------------------- plumbing

    private List<Wallet> walletsIn(String accountId) {
        return await(database.wallets().findWalletsByAccount(accountId));
    }

    private static WalletBinding proved(String address) {
        return new WalletBinding(address, ChainPlatform.ETHEREUM, 1, Instant.now());
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
