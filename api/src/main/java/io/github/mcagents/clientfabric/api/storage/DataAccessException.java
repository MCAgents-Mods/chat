package io.github.mcagents.clientfabric.api.storage;

/**
 * A local storage operation failed.
 *
 * <p>Unchecked on purpose: every repository method hands its result back through a
 * {@link java.util.concurrent.CompletableFuture}, and a checked exception cannot travel
 * that way without being wrapped anyway. Callers handle it with
 * {@link java.util.concurrent.CompletableFuture#exceptionally} or
 * {@code whenComplete}.
 *
 * <p>The message never contains the value of an API key.
 */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message) {
        super(message);
    }

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
