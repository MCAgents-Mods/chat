package io.github.mcagentsmods.chat.common.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovering the signer of a {@code personal_sign} message.
 *
 * <p>Two kinds of check here, and both are needed. The published vectors — the address of a
 * known key, and the EIP-55 checksum cases — pin the implementation to the real Ethereum
 * definitions rather than to itself. The round trips then prove that recovery genuinely
 * inverts signing, including for the messages that break naive implementations.
 *
 * <p>No signature produced by MetaMask itself is checked here, because that would need a
 * browser and a player. What is checked is that a correct signature from any source
 * recovers to the right address and that a wrong one recovers to something else.
 */
class EvmSignaturesTest {

    private static final String MESSAGE = """
            MCAgents wallet binding

            Account: Personal
            Nonce: 2f0a4c8e91b7d6a35c48e2f1b09d7a64

            Signing proves you control this address. It authorises nothing and moves no funds.""";

    @Test
    void derivesTheKnownAddressOfAKnownKey() {
        // The address of private key 1 is the most widely published secp256k1-to-Ethereum
        // vector there is. If the curve arithmetic or the padding were wrong this would be
        // some other address entirely.
        assertEquals("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf", addressOf(BigInteger.ONE));
    }

    @Test
    void appliesTheEip55Checksum() {
        // The four canonical vectors from EIP-55 itself.
        assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                EvmSignatures.toChecksumAddress("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"));
        assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                EvmSignatures.toChecksumAddress("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"));
        assertEquals("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
                EvmSignatures.toChecksumAddress("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb"));
        assertEquals("0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
                EvmSignatures.toChecksumAddress("0xd1220a0cf47c7b9be7a2e6ba89f429762e7b9adb"));
    }

    @Test
    void checksummingIsIdempotentAndCaseBlind() {
        String checksummed = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";

        assertEquals(checksummed, EvmSignatures.toChecksumAddress(checksummed));
        assertEquals(checksummed,
                EvmSignatures.toChecksumAddress("5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED"));
    }

    @Test
    void recoversTheSignerOfAMessage() {
        BigInteger key = new BigInteger(
                "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16);
        String signature = sign(MESSAGE, key);

        assertEquals(Optional.of(addressOf(key)), EvmSignatures.recoverAddress(MESSAGE, signature));
        assertTrue(EvmSignatures.proves(MESSAGE, signature, addressOf(key)));
    }

    @Test
    void recoversAcrossManyKeys() {
        // One key could pass by luck — a wrong recovery id, say, is right a quarter of the
        // time. Twenty are not all luck.
        for (int i = 1; i <= 20; i++) {
            BigInteger key = BigInteger.valueOf(i).multiply(BigInteger.valueOf(0x9E3779B9L));
            String signature = sign(MESSAGE + " #" + i, key);

            assertEquals(Optional.of(addressOf(key)),
                    EvmSignatures.recoverAddress(MESSAGE + " #" + i, signature),
                    "key " + i + " did not recover to its own address");
        }
    }

    @Test
    void countsThePrefixLengthInBytesRatherThanCharacters() {
        // EIP-191 puts the message's byte length in the prefix. A message with anything
        // non-ASCII in it recovers to the wrong address if that is taken from the string's
        // character count — and player-facing text will not stay ASCII.
        String unicode = "Bind wallet — café ☕ 日本語";
        BigInteger key = BigInteger.valueOf(20250814);

        assertEquals(Optional.of(addressOf(key)),
                EvmSignatures.recoverAddress(unicode, sign(unicode, key)));
    }

    @Test
    void refusesASignatureOverADifferentMessage() {
        BigInteger key = BigInteger.valueOf(4242);
        String signature = sign(MESSAGE, key);

        // Replaying yesterday's signature against today's nonce must not bind anything.
        assertFalse(EvmSignatures.proves(MESSAGE + " (tampered)", signature, addressOf(key)));
        assertNotEquals(Optional.of(addressOf(key)),
                EvmSignatures.recoverAddress(MESSAGE + " (tampered)", signature));
    }

    @Test
    void refusesASignatureThatDoesNotMatchTheAddressItArrivedWith() {
        // The whole point: the page's claimed address is ignored in favour of the one the
        // signature actually produces.
        String signature = sign(MESSAGE, BigInteger.valueOf(4242));

        assertFalse(EvmSignatures.proves(MESSAGE, signature, addressOf(BigInteger.valueOf(4243))));
        assertFalse(EvmSignatures.proves(MESSAGE, signature,
                "0x0000000000000000000000000000000000000000"));
    }

    @Test
    void refusesMalformedInput() {
        assertTrue(EvmSignatures.recoverAddress(MESSAGE, "not-a-signature").isEmpty());
        assertTrue(EvmSignatures.recoverAddress(MESSAGE, "0x1234").isEmpty());
        assertTrue(EvmSignatures.recoverAddress(MESSAGE, "0x" + "00".repeat(65)).isEmpty());
        assertTrue(EvmSignatures.recoverAddress(MESSAGE, null).isEmpty());
        assertTrue(EvmSignatures.recoverAddress(null, "0x" + "11".repeat(65)).isEmpty());
        assertFalse(EvmSignatures.proves(MESSAGE, "0x" + "11".repeat(65), null));
    }

    @Test
    void acceptsEveryEncodingOfVThatWalletsProduce() {
        BigInteger key = BigInteger.valueOf(777);
        String canonical = sign(MESSAGE, key);
        String expected = addressOf(key);

        // 27/28 is what the signature above carries. Some libraries send a bare 0/1
        // instead, and EIP-155 sends chainId * 2 + 35; all three mean the same thing.
        int v = Integer.parseInt(canonical.substring(canonical.length() - 2), 16);
        String body = canonical.substring(0, canonical.length() - 2);

        assertEquals(Optional.of(expected),
                EvmSignatures.recoverAddress(MESSAGE, body + String.format("%02x", v - 27)));
        assertEquals(Optional.of(expected),
                EvmSignatures.recoverAddress(MESSAGE, body + String.format("%02x", v - 27 + 37)));
    }

    @Test
    void toleratesAMissingHexPrefixAndSurroundingSpace() {
        BigInteger key = BigInteger.valueOf(31337);
        String signature = sign(MESSAGE, key);

        assertEquals(Optional.of(addressOf(key)),
                EvmSignatures.recoverAddress(MESSAGE, "  " + signature.substring(2) + "  "));
    }

    // ------------------------------------------------------------ signing

    /** The address a private key controls. */
    private static String addressOf(BigInteger privateKey) {
        return EvmSignatures.toAddress(Secp256k1.multiply(Secp256k1.G, privateKey));
    }

    /**
     * Signs the way a wallet does, so the tests have something to recover from.
     *
     * <p>This lives in the tests and nowhere else. The mod never signs and never holds a
     * private key; it only ever checks signatures made elsewhere.
     */
    private static String sign(String message, BigInteger privateKey) {
        byte[] hash = EvmSignatures.hashPersonalMessage(message);
        BigInteger n = Secp256k1.N;
        BigInteger e = new BigInteger(1, hash);

        // A fixed nonce derived from the key and the message: reproducible across runs, and
        // never reused across different messages, which is what makes it safe enough for a
        // test signer.
        BigInteger k = new BigInteger(1, Keccak256.digest(
                (privateKey.toString(16) + ":" + message).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);

        Secp256k1.Point point = Secp256k1.multiply(Secp256k1.G, k);
        BigInteger r = point.x().mod(n);
        BigInteger s = k.modInverse(n).multiply(e.add(r.multiply(privateKey))).mod(n);

        int recoveryId = (point.y().testBit(0) ? 1 : 0)
                | (point.x().compareTo(n) >= 0 ? 2 : 0);
        // Wallets normalise to the lower of the two equivalent s values, which flips the
        // parity bit. Recovery has to cope with what wallets actually send.
        if (s.compareTo(n.shiftRight(1)) > 0) {
            s = n.subtract(s);
            recoveryId ^= 1;
        }

        return "0x" + pad(r) + pad(s) + String.format("%02x", recoveryId + 27);
    }

    private static String pad(BigInteger value) {
        String hex = value.toString(16);
        return "0".repeat(64 - hex.length()) + hex;
    }
}
