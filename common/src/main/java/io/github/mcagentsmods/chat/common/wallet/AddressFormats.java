package io.github.mcagentsmods.chat.common.wallet;

import io.github.mcagentsmods.chat.api.wallet.ChainPlatform;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A sanity check on an address the player typed in by hand.
 *
 * <h2>What this is for, and what it is not</h2>
 *
 * <p>An address bound by signature needs no checking — it came out of the signature itself.
 * An address typed into a box does, because the usual mistake is not an invalid address but
 * a truncated one, and a truncated address is a payment sent nowhere.
 *
 * <p>These checks are deliberately shallow: shape and character set, not checksums. A rule
 * that is too strict is worse than one that is too loose here, because a player whose real
 * address is rejected has no way forward, whereas a player who mistypes one can see it on
 * the screen beside the chain it belongs to. {@link ChainPlatform#OTHER} accepts anything
 * non-blank, and is the escape hatch when this file is wrong about a chain.
 */
public final class AddressFormats {

    /** {@code 0X} as well as {@code 0x}: some tools shout, and the address is the same one. */
    private static final Pattern EVM = Pattern.compile("^0[xX][0-9a-fA-F]{40}$");

    /** Legacy pay-to-key and pay-to-script addresses, base58 without the ambiguous glyphs. */
    private static final Pattern BITCOIN_LEGACY =
            Pattern.compile("^[13][1-9A-HJ-NP-Za-km-z]{25,34}$");

    /** Bech32 and bech32m, which are single-case by construction. */
    private static final Pattern BITCOIN_BECH32 =
            Pattern.compile("^(bc1|BC1)[0-9A-Za-z]{11,71}$");

    private static final Pattern BASE58 = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private static final Pattern TRON = Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");

    private AddressFormats() {
        throw new AssertionError("No instances.");
    }

    /**
     * What is wrong with this address, if anything.
     *
     * @return a sentence to show the player, or empty when the address looks right for the
     *         chain
     */
    public static Optional<String> problem(ChainPlatform platform, String address) {
        if (platform == null) {
            return Optional.of("Pick a chain for this address.");
        }
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) {
            return Optional.of("Paste the address you want to bind.");
        }
        if (looksLikeASecret(value)) {
            // The one input this mod refuses outright. Nothing here ever needs a key, so
            // anything that resembles one is a player about to make a serious mistake.
            return Optional.of(
                    "That looks like a private key or recovery phrase. Never paste one here — "
                            + "only public addresses belong in this box.");
        }

        if (platform.isEvm()) {
            return EVM.matcher(value).matches()
                    ? Optional.empty()
                    : Optional.of("An " + platform.displayName()
                            + " address is 0x followed by 40 hex characters.");
        }
        return switch (platform) {
            case BITCOIN -> BITCOIN_LEGACY.matcher(value).matches()
                    || BITCOIN_BECH32.matcher(value).matches()
                    ? Optional.empty()
                    : Optional.of("A Bitcoin address starts with 1, 3, or bc1.");
            case SOLANA -> BASE58.matcher(value).matches()
                    ? Optional.empty()
                    : Optional.of("A Solana address is 32 to 44 base58 characters.");
            case TRON -> TRON.matcher(value).matches()
                    ? Optional.empty()
                    : Optional.of("A Tron address starts with T and is 34 characters long.");
            // OTHER exists so an unlisted chain can still be recorded. Nothing is checked.
            default -> Optional.empty();
        };
    }

    /** Whether the address looks right for the chain. */
    public static boolean isValid(ChainPlatform platform, String address) {
        return problem(platform, address).isEmpty();
    }

    /**
     * Tidies an address for storage.
     *
     * <p>EVM addresses are rewritten into EIP-55 checksum form so that the mod shows the
     * same casing as the wallet does and two spellings of one address do not look like two
     * addresses. Everything else is case-sensitive and is stored exactly as given.
     */
    public static String normalise(ChainPlatform platform, String address) {
        String value = address == null ? "" : address.trim();
        if (platform != null && platform.isEvm() && EVM.matcher(value).matches()) {
            return EvmSignatures.toChecksumAddress(value);
        }
        return value;
    }

    /**
     * Whether this input looks like something that must never be typed into the mod.
     *
     * <p>A 64-character hex string is a private key, an {@code 0x}-prefixed one is a key
     * with a prefix, and a run of twelve or more lowercase words is a recovery phrase. None
     * of them is an address, and all of them mean the player has been asked for the wrong
     * thing — by a scam, or by their own confusion.
     */
    private static boolean looksLikeASecret(String value) {
        String candidate = value.startsWith("0x") || value.startsWith("0X")
                ? value.substring(2)
                : value;
        if (candidate.length() == 64 && candidate.chars()
                .allMatch(character -> Character.digit(character, 16) >= 0)) {
            return true;
        }
        String[] words = value.toLowerCase(Locale.ROOT).split("\\s+");
        return words.length >= 12
                && java.util.Arrays.stream(words).allMatch(word -> word.matches("[a-z]{3,8}"));
    }
}
