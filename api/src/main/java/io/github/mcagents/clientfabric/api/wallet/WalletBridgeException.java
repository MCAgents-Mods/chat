package io.github.mcagents.clientfabric.api.wallet;

/**
 * A wallet connection that did not complete.
 *
 * <p>The player closed the tab, the signature did not match the address it claimed, the
 * browser never came back, or the bridge was shut before it was answered. None of these
 * are faults the mod can fix on the player's behalf, so each arrives here with a message
 * the interface can show as it stands.
 *
 * <p>Never carries a signature, a key, or a recovery phrase — see
 * {@code .agents/security/api-keys.md} for the same rule applied to provider keys.
 */
public class WalletBridgeException extends RuntimeException {

    public WalletBridgeException(String message) {
        super(message);
    }

    public WalletBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
