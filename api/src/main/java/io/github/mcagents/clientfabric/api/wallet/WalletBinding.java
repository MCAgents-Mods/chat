package io.github.mcagents.clientfabric.api.wallet;

import java.time.Instant;
import java.util.Objects;

/**
 * An address the player has just proved they control.
 *
 * <p>This is what a {@link WalletBridge} produces and it is the only thing in the mod that
 * carries proof. The player signed a one-off message in MetaMask, the mod recovered the
 * signing address from that signature, and the two matched — so the address in this record
 * is not a claim, it is a result.
 *
 * <p>Signing proves control. It authorises nothing, moves nothing, and costs nothing: a
 * {@code personal_sign} never reaches a chain.
 *
 * @param address    the address recovered from the signature, in EIP-55 checksum form
 * @param platform   the EVM chain the browser was connected to when it signed
 * @param chainId    the chain id the browser reported, kept even when
 *                   {@link ChainPlatform#fromEvmChainId} did not recognise it
 * @param verifiedAt when the signature was checked
 */
public record WalletBinding(
        String address, ChainPlatform platform, long chainId, Instant verifiedAt) {

    public WalletBinding {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (address.isBlank()) {
            throw new IllegalArgumentException("A verified address cannot be blank");
        }
    }
}
