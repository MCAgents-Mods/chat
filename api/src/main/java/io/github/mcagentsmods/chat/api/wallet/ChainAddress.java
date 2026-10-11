package io.github.mcagentsmods.chat.api.wallet;

import java.time.Instant;
import java.util.Objects;

/**
 * One public address, on one chain, belonging to one wallet.
 *
 * <p><strong>Public addresses only.</strong> Nothing in this record, this package, or this
 * mod holds a private key or a recovery phrase, and no screen ever offers a field to type
 * one into. An address is a destination; it authorises nothing on its own, which is exactly
 * why it is safe to keep in a file on the player's disk.
 *
 * @param id        a UUID string
 * @param platform  the chain this address is on
 * @param address   the public address, as the player's wallet renders it
 * @param verified  whether the player proved control of it by signing, rather than typing
 *                  it in by hand
 * @param createdAt when it was bound
 */
public record ChainAddress(
        String id, ChainPlatform platform, String address, boolean verified, Instant createdAt) {

    /** Leading characters kept by {@link #masked()}. */
    private static final int PREFIX_LENGTH = 7;

    /** Trailing characters kept by {@link #masked()}. */
    private static final int SUFFIX_LENGTH = 5;

    public ChainAddress {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(createdAt, "createdAt");
        if (address.isBlank()) {
            throw new IllegalArgumentException("A chain address cannot be blank");
        }
    }

    /**
     * The short form the interface lists, as {@code 0x4b470…6BB75}.
     *
     * <p>Shortened for the same reason every wallet shortens it — a full address is forty
     * characters that will not fit beside a chain name — and not for secrecy. The whole
     * value is one click away on the copy button, because an address the player cannot copy
     * is an address they cannot use.
     */
    public String masked() {
        return masked(address);
    }

    /** {@link #masked()} for an address not yet stored as one. */
    public static String masked(String address) {
        if (address.length() <= PREFIX_LENGTH + SUFFIX_LENGTH + 1) {
            return address;
        }
        return address.substring(0, PREFIX_LENGTH)
                + "…"
                + address.substring(address.length() - SUFFIX_LENGTH);
    }

    @Override
    public String toString() {
        return "ChainAddress[" + platform.storageName() + " " + masked()
                + ", verified=" + verified + "]";
    }
}
