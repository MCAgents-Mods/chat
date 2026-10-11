package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.storage.DataAccessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Runs every database call on one background thread and hands the result back as a future.
 *
 * <p>One thread, one connection. That is not a performance compromise — it is what keeps
 * SQLite access correct without a connection pool or a lock, and the volumes here (a chat
 * history, a few dozen Knowledge Packs) never need more. What matters is the thread it is
 * <em>not</em> on: the client thread, which renders the game.
 */
final class SqlWorker {

    /** A unit of work that needs the connection and may fail the way JDBC fails. */
    @FunctionalInterface
    interface SqlCall<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Connection connection;
    private final ExecutorService executor;

    SqlWorker(Connection connection, ExecutorService executor) {
        this.connection = connection;
        this.executor = executor;
    }

    /**
     * Runs {@code call} on the database thread.
     *
     * @param description what the call was doing, used in the failure message; must never
     *                    contain a value read from the database, so an API key cannot end
     *                    up in a log
     */
    <T> CompletableFuture<T> submit(String description, SqlCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.run(connection);
            } catch (SQLException e) {
                throw new DataAccessException(description + " failed", e);
            }
        }, executor);
    }

    /**
     * Runs {@code call} on the database thread inside a transaction, committing on success
     * and rolling back on any failure.
     */
    <T> CompletableFuture<T> submitInTransaction(String description, SqlCall<T> call) {
        return submit(description, connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = call.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
