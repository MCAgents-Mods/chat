package io.github.mcagents.clientfabric.common.service;

/**
 * The central service refused or could not answer a read.
 *
 * <p>Never fatal. Every message on one of these is written to be shown to a player, so it
 * says what to do rather than what went wrong internally — and it carries no URL, no status
 * line, and nothing of the player's.
 */
public class CentralServiceException extends RuntimeException {

    public CentralServiceException(String message) {
        super(message);
    }

    public CentralServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
