package io.github.mcagents.clientfabric.api.wallet;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One wallet the player has bound, with every chain address under it.
 *
 * <h2>Why there is an account above this</h2>
 *
 * <p>A player keeps more than one wallet — a main one, a trading one, one they use for a
 * particular server — and more than one identity to keep them under. So wallets hang off an
 * {@code accountId} rather than being a flat list: the mod defaults that to the signed-in
 * Minecraft profile, and a player who wants a second set of wallets names a second account
 * instead of mixing them together.
 *
 * @param accountId who these wallets belong to; the Minecraft profile by default, but any
 *                  label the player chooses
 * @param label     what the player calls this wallet
 * @param active    whether the mod should treat it as the one in use; a wallet is
 *                  deactivated rather than deleted when the player is done with it
 * @param addresses its chain addresses, in {@link ChainPlatform} order
 */
public record Wallet(
        String id,
        String accountId,
        String label,
        boolean active,
        List<ChainAddress> addresses,
        Instant createdAt,
        Instant updatedAt) {

    /** The label a wallet is given until it is renamed. */
    public static final String DEFAULT_LABEL = "New Wallet";

    /** The account a wallet lands under when the Minecraft profile cannot be read. */
    public static final String DEFAULT_ACCOUNT = "Personal";

    public Wallet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A wallet id cannot be blank");
        }
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("A wallet needs an account to belong to");
        }
        addresses = List.copyOf(Objects.requireNonNullElse(addresses, List.of()));
    }

    /** This wallet's address on one chain, if it has one. */
    public Optional<ChainAddress> address(ChainPlatform platform) {
        return addresses.stream().filter(entry -> entry.platform() == platform).findFirst();
    }

    /**
     * Whether the player has proved control of any address in this wallet.
     *
     * <p>A wallet built entirely from hand-typed addresses is still a useful record, but it
     * is not proof of anything, and the interface says so rather than letting the two look
     * alike.
     */
    public boolean hasVerifiedAddress() {
        return addresses.stream().anyMatch(ChainAddress::verified);
    }

    @Override
    public String toString() {
        return "Wallet[" + label + ", account=" + accountId
                + ", addresses=" + addresses.size() + ", active=" + active + "]";
    }
}
