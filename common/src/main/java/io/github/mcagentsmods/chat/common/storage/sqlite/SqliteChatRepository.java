package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.model.ModelCatalog;
import io.github.mcagentsmods.chat.api.model.Page;
import io.github.mcagentsmods.chat.api.model.PageRequest;
import io.github.mcagentsmods.chat.api.model.SessionModels;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.chat.api.storage.DataAccessException;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.model.ModelChoice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Conversations and their messages, in {@code chat_sessions} and {@code chat_messages}.
 *
 * <p>A message is one turn by one speaker. Appending one also touches its session's
 * {@code updated_at}, which is what the session list sorts on, so the conversation the
 * player is in stays at the top.
 */
final class SqliteChatRepository implements ChatRepository {

    private static final String SESSION_COLUMNS = "id, title, created_at, updated_at";
    private static final String MESSAGE_COLUMNS =
            "id, session_id, message_index, role, content, created_at";

    private final SqlWorker worker;

    SqliteChatRepository(SqlWorker worker) {
        this.worker = worker;
    }

    @Override
    public CompletableFuture<List<ChatSession>> findAllSessions() {
        return worker.submit("Reading the chat sessions", connection -> {
            String sql = "SELECT " + SESSION_COLUMNS + " FROM chat_sessions "
                    + "ORDER BY updated_at DESC, created_at DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                return readSessions(rows);
            }
        });
    }

    @Override
    public CompletableFuture<List<ChatSession>> searchSessions(String text) {
        if (text == null || text.isBlank()) {
            return findAllSessions();
        }
        String pattern = SqlValues.containsPattern(text.trim());

        return worker.submit("Searching the chat sessions", connection -> {
            // A session matches on its title or on anything said in it, so searching for a
            // remembered phrase finds the conversation it was said in.
            String sql = "SELECT DISTINCT s.id, s.title, s.created_at, s.updated_at "
                    + "FROM chat_sessions s "
                    + "LEFT JOIN chat_messages m ON m.session_id = s.id "
                    + "WHERE s.title LIKE ? ESCAPE '\\' OR m.content LIKE ? ESCAPE '\\' "
                    + "ORDER BY s.updated_at DESC, s.created_at DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, pattern);
                statement.setString(2, pattern);
                try (ResultSet rows = statement.executeQuery()) {
                    return readSessions(rows);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Page<ChatSession>> findSessionPage(String search, PageRequest page) {
        java.util.Objects.requireNonNull(page, "page");
        boolean filtered = search != null && !search.isBlank();
        String pattern = filtered ? SqlValues.containsPattern(search.trim()) : null;

        return worker.submit("Reading a page of the chat sessions", connection -> {
            // A session matches on its title or on anything said in it, so a remembered
            // phrase finds the conversation it was said in. DISTINCT because the join
            // against messages would otherwise return a session once per matching message.
            String from = filtered
                    ? "FROM chat_sessions s LEFT JOIN chat_messages m ON m.session_id = s.id "
                            + "WHERE s.title LIKE ? ESCAPE '\\' OR m.content LIKE ? ESCAPE '\\'"
                    : "FROM chat_sessions s";

            long total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(DISTINCT s.id) " + from)) {
                if (filtered) {
                    statement.setString(1, pattern);
                    statement.setString(2, pattern);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    total = rows.next() ? rows.getLong(1) : 0;
                }
            }
            if (total == 0) {
                return Page.empty(page);
            }

            // s.rowid last, and it has to be: CURRENT_TIMESTAMP resolves to the second, so
            // two conversations touched in the same breath carry identical timestamps.
            // Paging an ambiguous ordering shows the reader some rows twice and never shows
            // them others — the failure looks like data loss rather than a sort bug.
            String sql = "SELECT DISTINCT s.id, s.title, s.created_at, s.updated_at, s.rowid "
                    + from
                    + " ORDER BY s.updated_at DESC, s.created_at DESC, s.rowid DESC "
                    + "LIMIT ? OFFSET ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (filtered) {
                    statement.setString(index++, pattern);
                    statement.setString(index++, pattern);
                }
                statement.setInt(index++, page.limit());
                statement.setInt(index, page.offset());

                try (ResultSet rows = statement.executeQuery()) {
                    List<ChatSession> sessions = new ArrayList<>();
                    while (rows.next()) {
                        sessions.add(readSession(rows));
                    }
                    return new Page<>(sessions, page.offset(), page.limit(), total);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ChatSession>> findSession(String id) {
        return worker.submit("Reading a chat session", connection ->
                findSession(connection, id));
    }

    @Override
    public CompletableFuture<ChatSession> createSession(String title) {
        String id = UUID.randomUUID().toString();
        String resolvedTitle = title == null || title.isBlank()
                ? ChatSession.DEFAULT_TITLE
                : title.trim();

        return worker.submitInTransaction("Creating a chat session", connection -> {
            String sql = "INSERT INTO chat_sessions (id, title) VALUES (?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.setString(2, resolvedTitle);
                statement.executeUpdate();
            }
            return findSession(connection, id)
                    .orElseThrow(() -> new DataAccessException("The chat session was not stored"));
        });
    }

    @Override
    public CompletableFuture<Boolean> renameSession(String id, String title) {
        String resolvedTitle = title == null || title.isBlank()
                ? ChatSession.DEFAULT_TITLE
                : title.trim();

        return worker.submit("Renaming a chat session", connection -> {
            String sql = "UPDATE chat_sessions SET title = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, resolvedTitle);
                statement.setString(2, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteSession(String id) {
        // The messages go with it: chat_messages declares ON DELETE CASCADE, and the
        // connection enables foreign key enforcement so that it is actually applied.
        return worker.submit("Deleting a chat session", connection -> {
            String sql = "DELETE FROM chat_sessions WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<SessionModels> findSessionModels(String sessionId) {
        return worker.submit("Reading a chat session's models", connection -> {
            String sql = "SELECT chat_provider, chat_model, embedding_provider, embedding_model "
                    + "FROM chat_sessions WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, sessionId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return SessionModels.GLOBAL;
                    }
                    return new SessionModels(
                            readChoice(rows.getString("chat_provider"), rows.getString("chat_model")),
                            readChoice(rows.getString("embedding_provider"),
                                    rows.getString("embedding_model")));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> saveSessionModels(String sessionId, SessionModels models) {
        java.util.Objects.requireNonNull(models, "models");
        // Not touching updated_at, unlike a rename: choosing a model is not a turn, and the
        // conversation must not jump to the top of the list for it.
        return worker.submit("Storing a chat session's models", connection -> {
            String sql = "UPDATE chat_sessions SET chat_provider = ?, chat_model = ?, "
                    + "embedding_provider = ?, embedding_model = ? WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                ModelChoice chat = models.chat();
                ModelChoice embedding = models.embedding();
                statement.setString(1, chat == null ? null : chat.provider());
                statement.setString(2, chat == null ? null : chat.model());
                statement.setString(3, embedding == null ? null : embedding.provider());
                statement.setString(4, embedding == null ? null : embedding.model());
                statement.setString(5, sessionId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    /**
     * One stored choice, or null for "the global model". A blank model is no choice at all, and
     * a retired id is moved off, as the global model's is: a choice that cannot work is not one.
     */
    private static ModelChoice readChoice(String provider, String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return ModelChoice.qualified(provider, ModelCatalog.resolve(model));
    }

    @Override
    public CompletableFuture<List<ChatMessage>> findMessages(String sessionId) {
        return worker.submit("Reading the messages in a chat session", connection -> {
            String sql = "SELECT " + MESSAGE_COLUMNS + " FROM chat_messages "
                    + "WHERE session_id = ? ORDER BY message_index ASC, id ASC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, sessionId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ChatMessage> messages = new ArrayList<>();
                    while (rows.next()) {
                        messages.add(readMessage(rows));
                    }
                    return List.copyOf(messages);
                }
            }
        });
    }

    @Override
    public CompletableFuture<ChatMessage> appendMessage(String sessionId, ChatRole role, String content) {
        if (role == null) {
            return CompletableFuture.failedFuture(new DataAccessException("A message needs a role"));
        }
        if (content == null) {
            return CompletableFuture.failedFuture(new DataAccessException("A message needs content"));
        }

        return worker.submitInTransaction("Appending a chat message", connection -> {
            if (findSession(connection, sessionId).isEmpty()) {
                throw new DataAccessException("No chat session with that id exists");
            }

            int index = nextIndex(connection, sessionId);
            long id = insertMessage(connection, sessionId, index, role, content);
            touchSession(connection, sessionId);

            return readMessage(connection, id);
        });
    }

    private static int nextIndex(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(message_index), 0) + 1 FROM chat_messages WHERE session_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 1;
            }
        }
    }

    private static long insertMessage(
            Connection connection, String sessionId, int index, ChatRole role, String content)
            throws SQLException {
        String sql = "INSERT INTO chat_messages (session_id, message_index, role, content) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, sessionId);
            statement.setInt(2, index);
            statement.setString(3, role.code());
            statement.setString(4, content);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DataAccessException("The chat message was not stored");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void touchSession(Connection connection, String sessionId) throws SQLException {
        String sql = "UPDATE chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    private static Optional<ChatSession> findSession(Connection connection, String id) throws SQLException {
        String sql = "SELECT " + SESSION_COLUMNS + " FROM chat_sessions WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readSession(rows)) : Optional.empty();
            }
        }
    }

    private static ChatMessage readMessage(Connection connection, long id) throws SQLException {
        String sql = "SELECT " + MESSAGE_COLUMNS + " FROM chat_messages WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new DataAccessException("The chat message was not stored");
                }
                return readMessage(rows);
            }
        }
    }

    private static List<ChatSession> readSessions(ResultSet rows) throws SQLException {
        List<ChatSession> sessions = new ArrayList<>();
        while (rows.next()) {
            sessions.add(readSession(rows));
        }
        return List.copyOf(sessions);
    }

    private static ChatSession readSession(ResultSet rows) throws SQLException {
        String title = rows.getString("title");
        return new ChatSession(
                rows.getString("id"),
                title == null ? ChatSession.DEFAULT_TITLE : title,
                SqlValues.readInstant(rows.getString("created_at")),
                SqlValues.readInstant(rows.getString("updated_at")));
    }

    private static ChatMessage readMessage(ResultSet rows) throws SQLException {
        return new ChatMessage(
                rows.getLong("id"),
                rows.getString("session_id"),
                rows.getInt("message_index"),
                ChatRole.fromCode(rows.getString("role")),
                rows.getString("content"),
                SqlValues.readInstant(rows.getString("created_at")));
    }
}
