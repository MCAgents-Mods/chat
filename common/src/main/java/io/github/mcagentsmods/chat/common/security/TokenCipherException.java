package io.github.mcagentsmods.chat.common.security;

/**
 * A stored token could not be read back.
 *
 * <p>Thrown when a row was written encrypted and the key that would open it is missing or
 * different — the encryption key was never set on this machine, or it was changed.
 *
 * <p><strong>The message never contains a token, encrypted or otherwise.</strong> It says what
 * was attempted and never what it was attempted with, which is the same rule every message out
 * of the storage layer follows — {@code .agents/security/api-keys.md}.
 */
public class TokenCipherException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TokenCipherException(String message) {
        super(message);
    }

    public TokenCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
