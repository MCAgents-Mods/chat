package io.github.mcagents.clientfabric.common.wallet;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for MetaMask: an address, and signatures it can actually produce.
 *
 * <p>It lives in this package because the pieces it needs — {@link Secp256k1},
 * {@link Keccak256}, and {@code EvmSignatures.hashPersonalMessage} — are package-private and
 * belong that way. Widening a signature scheme's internals so a test in another package can
 * reach them would trade a real boundary for a convenience; a public helper on this side of
 * the boundary costs nothing.
 *
 * <p>Two keys, because the point of the wallet work is that a browser holds more than one
 * account.
 */
public final class TestSigner {

    /** The key used by every single-address test. */
    public static final TestSigner PLAYER = new TestSigner(new BigInteger(
            "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16));

    /** A second account in the same browser — the one the bridge used to drop. */
    public static final TestSigner SECOND = new TestSigner(new BigInteger(
            "2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a", 16));

    private final BigInteger key;

    private TestSigner(BigInteger key) {
        this.key = key;
    }

    /** This signer's address, in the EIP-55 checksum form the mod stores. */
    public String address() {
        return EvmSignatures.toChecksumAddress(
                EvmSignatures.toAddress(Secp256k1.multiply(Secp256k1.G, key)));
    }

    /**
     * Signs a message the way a wallet would, so the checking code has something real to
     * verify rather than a fixture that agrees with it by construction.
     *
     * <p>The nonce {@code k} is derived from the key and the message rather than drawn at
     * random, which makes a failing test reproduce the same signature every time.
     */
    public String sign(String message) {
        byte[] hash = EvmSignatures.hashPersonalMessage(message);
        BigInteger n = Secp256k1.N;
        BigInteger e = new BigInteger(1, hash);
        BigInteger k = new BigInteger(1, Keccak256.digest(
                (key.toString(16) + ":" + message).getBytes(StandardCharsets.UTF_8)))
                .mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);

        Secp256k1.Point point = Secp256k1.multiply(Secp256k1.G, k);
        BigInteger r = point.x().mod(n);
        BigInteger s = k.modInverse(n).multiply(e.add(r.multiply(key))).mod(n);
        int recoveryId = (point.y().testBit(0) ? 1 : 0) | (point.x().compareTo(n) >= 0 ? 2 : 0);
        if (s.compareTo(n.shiftRight(1)) > 0) {
            // Low-s, as every wallet produces: the other half of the curve is the same
            // signature written differently, and verifiers reject it.
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
