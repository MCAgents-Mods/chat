package io.github.mcagents.clientfabric.common.wallet;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Turning a MetaMask {@code personal_sign} signature back into the address that produced it.
 *
 * <h2>What the mod is actually checking</h2>
 *
 * <p>The browser page sends back an address and a signature. The address on its own proves
 * nothing — anything can type an address. So the mod ignores the claim and derives the
 * address from the signature itself: whoever signed the message must hold the key for the
 * address that comes out. If that is not the address the page claimed, the binding fails.
 *
 * <p>Signing costs nothing and authorises nothing. A {@code personal_sign} never reaches a
 * chain, moves no funds, and grants no approval — it is a proof of control and nothing else.
 *
 * <p>Public values only: a signature, a message, an address. No private key or recovery
 * phrase is ever accepted, requested, or handled.
 */
public final class EvmSignatures {

    /**
     * The EIP-191 prefix.
     *
     * <p>Wallets prepend this before hashing so that a message signed in a wallet can never
     * also be a valid transaction — a signature over arbitrary bytes would otherwise be a
     * blank cheque. The mod has to reproduce it exactly or the recovered address is wrong.
     */
    private static final String PERSONAL_SIGN_PREFIX = "Ethereum Signed Message:\n";

    /** {@code r} (32) + {@code s} (32) + {@code v} (1). */
    private static final int SIGNATURE_LENGTH = 65;

    private EvmSignatures() {
        throw new AssertionError("No instances.");
    }

    /**
     * Recovers the address that signed {@code message}.
     *
     * @param signature the 65-byte signature MetaMask returned, hex, with or without
     *                  {@code 0x}
     * @return the signer's address in EIP-55 checksum form, or empty if the signature is
     *         malformed or fits no key on the curve
     */
    public static Optional<String> recoverAddress(String message, String signature) {
        if (message == null || signature == null) {
            return Optional.empty();
        }
        byte[] raw = decodeHex(signature);
        if (raw == null || raw.length != SIGNATURE_LENGTH) {
            return Optional.empty();
        }

        BigInteger r = new BigInteger(1, raw, 0, 32);
        BigInteger s = new BigInteger(1, raw, 32, 32);
        int recoveryId = recoveryId(raw[64] & 0xFF);
        if (recoveryId < 0) {
            return Optional.empty();
        }

        Secp256k1.Point key =
                Secp256k1.recoverPublicKey(hashPersonalMessage(message), r, s, recoveryId);
        return key == null ? Optional.empty() : Optional.of(toAddress(key));
    }

    /**
     * Whether {@code signature} proves that {@code claimed} signed {@code message}.
     *
     * <p>Compared case-insensitively, because the same address is written in three
     * different casings by three different tools and none of them is more correct.
     */
    public static boolean proves(String message, String signature, String claimed) {
        if (claimed == null || claimed.isBlank()) {
            return false;
        }
        return recoverAddress(message, signature)
                .filter(recovered -> recovered.equalsIgnoreCase(claimed.trim()))
                .isPresent();
    }

    /** The EIP-191 hash a wallet actually signs, rather than the raw message. */
    static byte[] hashPersonalMessage(String message) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        // The length in the prefix counts bytes, not characters. A message with any
        // non-ASCII in it hashes differently if this is taken from String#length.
        byte[] prefix = (PERSONAL_SIGN_PREFIX + body.length).getBytes(StandardCharsets.UTF_8);

        byte[] combined = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(body, 0, combined, prefix.length, body.length);
        return Keccak256.digest(combined);
    }

    /** The address of a public key: the last twenty bytes of its Keccak-256 hash. */
    static String toAddress(Secp256k1.Point publicKey) {
        byte[] hash = Keccak256.digest(Secp256k1.encode(publicKey));
        StringBuilder hex = new StringBuilder(40);
        for (int i = 12; i < 32; i++) {
            hex.append(Character.forDigit((hash[i] >> 4) & 0x0F, 16));
            hex.append(Character.forDigit(hash[i] & 0x0F, 16));
        }
        return toChecksumAddress(hex.toString());
    }

    /**
     * Applies the EIP-55 mixed-case checksum.
     *
     * <p>The casing is not decoration: it is a checksum over the address itself, so a
     * player comparing what the mod shows against what MetaMask shows sees the same
     * characters in the same case, and a mistyped address in checksum form is detectable.
     */
    public static String toChecksumAddress(String address) {
        String hex = address.startsWith("0x") || address.startsWith("0X")
                ? address.substring(2)
                : address;
        String lower = hex.toLowerCase(Locale.ROOT);
        byte[] hash = Keccak256.digest(lower.getBytes(StandardCharsets.US_ASCII));

        StringBuilder result = new StringBuilder(lower.length() + 2).append("0x");
        for (int i = 0; i < lower.length(); i++) {
            char character = lower.charAt(i);
            if (character < 'a' || character > 'f') {
                result.append(character);
                continue;
            }
            int nibble = (hash[i / 2] >> (i % 2 == 0 ? 4 : 0)) & 0x0F;
            result.append(nibble >= 8 ? Character.toUpperCase(character) : character);
        }
        return result.toString();
    }

    /**
     * The recovery id packed into {@code v}.
     *
     * <p>Three encodings are in the wild and all three turn up: {@code 27/28} from the
     * original specification, a bare {@code 0/1} from some libraries, and
     * {@code chainId * 2 + 35} from EIP-155. Rejecting any of them would fail against a
     * wallet that is behaving correctly.
     */
    private static int recoveryId(int v) {
        if (v == 0 || v == 1) {
            return v;
        }
        if (v == 27 || v == 28) {
            return v - 27;
        }
        if (v >= 35) {
            return (v - 35) % 2;
        }
        return -1;
    }

    /** Decodes hex, tolerating a {@code 0x} prefix. Returns {@code null} if it is not hex. */
    private static byte[] decodeHex(String value) {
        String hex = value.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.isEmpty() || hex.length() % 2 != 0) {
            return null;
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
