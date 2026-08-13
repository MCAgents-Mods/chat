package io.github.mcagents.clientfabric.common.storage.sqlite;

import java.sql.Connection;
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
            """);

    private SqliteSchema() {
        throw new AssertionError("No instances.");
    }

    /** Creates every table and index that is not already present. */
    static void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : STATEMENTS) {
                statement.executeUpdate(ddl);
            }
        }
    }
}
