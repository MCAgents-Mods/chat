package io.github.mcagents.clientfabric.common.wallet;

import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shape check on an address the player typed in rather than proved. */
class AddressFormatsTest {

    private static final String EVM = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";

    @Test
    void acceptsAnAddressOnEveryEvmChain() {
        for (ChainPlatform platform : ChainPlatform.values()) {
            if (platform.isEvm()) {
                assertTrue(AddressFormats.isValid(platform, EVM),
                        platform + " should accept an ordinary EVM address");
            }
        }
    }

    @Test
    void acceptsTheNonEvmChainsMetaMaskShows() {
        assertTrue(AddressFormats.isValid(ChainPlatform.BITCOIN,
                "bc1qm8tvxyxpxu9z7q4vvj5s9wgqr7dkxxk8jqygj"));
        assertTrue(AddressFormats.isValid(ChainPlatform.BITCOIN,
                "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"));
        assertTrue(AddressFormats.isValid(ChainPlatform.BITCOIN,
                "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"));
        assertTrue(AddressFormats.isValid(ChainPlatform.SOLANA,
                "9iA8dBeYtWkFXBVsZfPjKPmzYyDzKQvNqYmvCzjJuVF"));
        assertTrue(AddressFormats.isValid(ChainPlatform.TRON,
                "TQu217PqPzr7Zt9vY3rKxYyGqWLnbME7Rn"));
    }

    @Test
    void catchesTheMistakeThatActuallyHappens() {
        // Not an invalid address — a truncated one. That is what a half-finished copy and
        // paste produces, and it is a payment sent nowhere.
        assertFalse(AddressFormats.isValid(ChainPlatform.ETHEREUM, EVM.substring(0, 30)));
        assertFalse(AddressFormats.isValid(ChainPlatform.TRON, "TQu217P"));
        assertFalse(AddressFormats.isValid(ChainPlatform.SOLANA, "9iA8dBe"));
    }

    @Test
    void catchesAnAddressPastedUnderTheWrongChain() {
        assertFalse(AddressFormats.isValid(ChainPlatform.BITCOIN, EVM));
        assertFalse(AddressFormats.isValid(ChainPlatform.ETHEREUM,
                "TQu217PqPzr7Zt9vY3rKxYyGqWLnbME7Rn"));
    }

    @Test
    void refusesAPrivateKeyOrRecoveryPhraseOutright() {
        // The one input the mod will not take. A player pasting this has been asked for the
        // wrong thing, and saying so is more use than "invalid address".
        String privateKey = "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
        String phrase = "legal winner thank year wave sausage worth useful legal winner "
                + "thank yellow";

        assertTrue(AddressFormats.problem(ChainPlatform.ETHEREUM, privateKey).orElseThrow()
                .contains("private key"));
        assertTrue(AddressFormats.problem(ChainPlatform.ETHEREUM, privateKey.substring(2))
                .orElseThrow().contains("private key"));
        assertTrue(AddressFormats.problem(ChainPlatform.BITCOIN, phrase).orElseThrow()
                .contains("recovery phrase"));
        // Even under OTHER, which accepts anything else at all.
        assertTrue(AddressFormats.problem(ChainPlatform.OTHER, phrase).isPresent());
    }

    @Test
    void acceptsAnythingUnderOther() {
        // The escape hatch for a chain this release has never heard of. A rule that is too
        // strict here leaves the player with no way forward at all.
        assertTrue(AddressFormats.isValid(ChainPlatform.OTHER, "cosmos1abcdefghijklmnop"));
        assertTrue(AddressFormats.isValid(ChainPlatform.OTHER, "anything-at-all"));
    }

    @Test
    void asksForSomethingWhenTheBoxIsEmpty() {
        assertTrue(AddressFormats.problem(ChainPlatform.ETHEREUM, "  ").isPresent());
        assertTrue(AddressFormats.problem(ChainPlatform.ETHEREUM, null).isPresent());
        assertTrue(AddressFormats.problem(null, EVM).isPresent());
    }

    @Test
    void rewritesAnEvmAddressIntoChecksumForm() {
        // So the mod shows the same casing MetaMask does, and one address never looks like
        // two.
        assertEquals(EVM, AddressFormats.normalise(ChainPlatform.ETHEREUM,
                "  0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed  "));
        assertEquals(EVM, AddressFormats.normalise(ChainPlatform.POLYGON,
                "0X5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED"));
    }

    @Test
    void leavesCaseSensitiveChainsExactlyAsGiven() {
        // Base58 is case-sensitive: changing the casing of a Solana address makes it a
        // different address, or no address at all.
        String solana = "9iA8dBeYtWkFXBVsZfPjKPmzYyDzKQvNqYmvCzjJuVF";

        assertEquals(solana, AddressFormats.normalise(ChainPlatform.SOLANA, "  " + solana + " "));
    }
}
