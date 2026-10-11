package io.github.mcagentsmods.chat.common.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The local database schema, applied on every start.
 *
 * <p>Every statement is {@code IF NOT EXISTS}, so applying it to an existing database is a
 * no-op and applying it to a fresh one creates everything. A future schema change ships as
 * a migration appended here; the answer is never "delete the database", because the file
 * holds the player's whole history.
 *
 * <p>A column added to a table that already exists cannot say {@code IF NOT EXISTS}, so those
 * live in their own list and are guarded by a read of the pragma instead. Both lists are
 * append-only: their order is the migration history.
 */
final class SqliteSchema {

    private static final List<String> STATEMENTS = List.of(
            """
            CREATE TABLE IF NOT EXISTS api_keys (
                key_value VARCHAR(255) PRIMARY KEY,
                is_active BOOLEAN DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS content_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                display_name VARCHAR(255) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS contents (
                id VARCHAR(255) PRIMARY KEY,
                category_id INTEGER NOT NULL,
                version VARCHAR(50) NOT NULL,
                is_premium BOOLEAN DEFAULT 0,
                title VARCHAR(255),
                raw_content TEXT,
                embedded_data TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (category_id) REFERENCES content_categories(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_sessions (
                id VARCHAR(36) PRIMARY KEY,
                title VARCHAR(255) DEFAULT 'New Chat',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id VARCHAR(36) NOT NULL,
                message_index INTEGER NOT NULL,
                role VARCHAR(20) NOT NULL,
                content TEXT NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_chat_session ON chat_messages(session_id)",
            "CREATE INDEX IF NOT EXISTS idx_content_category ON contents(category_id)",
            // Appended, never reordered: the order of this list is the migration history.
            """
            CREATE TABLE IF NOT EXISTS settings (
                key VARCHAR(64) PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS player_wallets (
                id VARCHAR(36) PRIMARY KEY,
                account_id VARCHAR(255) NOT NULL,
                wallet_label VARCHAR(255) NOT NULL,
                is_active BOOLEAN NOT NULL DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_player_wallets_account ON player_wallets(account_id)",
            // One row per chain per wallet. UNIQUE is what makes re-binding an address a
            // correction rather than a second, contradictory row for the same chain, and
            // ON DELETE CASCADE is what stops a deleted wallet leaving addresses behind —
            // both only hold because the connection enforces foreign keys.
            """
            CREATE TABLE IF NOT EXISTS wallet_platform_addresses (
                id VARCHAR(36) PRIMARY KEY,
                wallet_id VARCHAR(36) NOT NULL,
                platform VARCHAR(32) NOT NULL,
                address VARCHAR(255) NOT NULL,
                is_verified BOOLEAN NOT NULL DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (wallet_id) REFERENCES player_wallets(id) ON DELETE CASCADE,
                UNIQUE (wallet_id, platform)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_wallet_addresses_wallet "
                    + "ON wallet_platform_addresses(wallet_id)");

    /**
     * Columns added to tables that already existed, applied after the statements above.
     *
     * <p>{@code IF NOT EXISTS} does not exist for a column, so each one is guarded by a read of
     * {@code PRAGMA table_info} instead — the same idempotence by a different route. Each must
     * be nullable or carry a default, because the rows already in the table will not have it.
     *
     * <p>Appended, never reordered, for the same reason as the list above: the order is the
     * migration history.
     */
    private static final List<AddedColumn> ADDED_COLUMNS = List.of(
            // Which platform a stored key authenticates to. Existing rows are OpenRouter keys,
            // which is what every key was before the mod could call a platform directly, so the
            // default is both the migration and the fallback.
            new AddedColumn("api_keys", "platform",
                    "ALTER TABLE api_keys ADD COLUMN platform VARCHAR(32) "
                            + "NOT NULL DEFAULT 'openrouter'"));

    /**
     * One column added to an existing table.
     *
     * @param table  the table it belongs to
     * @param column the column name, as {@code PRAGMA table_info} reports it
     * @param ddl    the statement that adds it
     */
    private record AddedColumn(String table, String column, String ddl) {
    }

    private SqliteSchema() {
        throw new AssertionError("No instances.");
    }

    /** Creates every table, index and column that is not already present. */
    static void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : STATEMENTS) {
                statement.executeUpdate(ddl);
            }
            for (AddedColumn column : ADDED_COLUMNS) {
                if (!hasColumn(connection, column.table(), column.column())) {
                    statement.executeUpdate(column.ddl());
                }
            }
        }
    }

    /** Whether {@code table} already has {@code column}, per SQLite's own catalogue. */
    private static boolean hasColumn(Connection connection, String table, String column)
            throws SQLException {
        // The table-valued form of the pragma, so the table name is a bound parameter like
        // every other value this layer sends — `PRAGMA table_info(x)` cannot be parameterised
        // and would have to be concatenated, which nothing here is allowed to do.
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT name FROM pragma_table_info(?)")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (column.equalsIgnoreCase(rows.getString("name"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
