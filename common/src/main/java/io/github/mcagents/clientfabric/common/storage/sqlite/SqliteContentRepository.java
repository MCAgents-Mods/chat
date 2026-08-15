package io.github.mcagents.clientfabric.common.storage.sqlite;

import io.github.mcagents.clientfabric.api.model.ContentAvailability;
import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.ContentQuery;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.model.KnowledgeSummary;
import io.github.mcagents.clientfabric.api.model.Page;
import io.github.mcagents.clientfabric.api.model.PageRequest;
import io.github.mcagents.clientfabric.api.storage.ContentRepository;
import io.github.mcagents.clientfabric.api.storage.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Downloaded Knowledge Packs, in {@code contents} and {@code content_categories}.
 *
 * <p>A pack is written once, when it is downloaded, and read from disk from then on. None
 * of these methods needs the network.
 */
final class SqliteContentRepository implements ContentRepository {

    private static final String CATEGORY_COLUMNS = "id, display_name, created_at, updated_at";
    private static final String CONTENT_COLUMNS =
            "id, category_id, version, is_premium, title, raw_content, embedded_data, "
                    + "created_at, updated_at";

    private final SqlWorker worker;

    SqliteContentRepository(SqlWorker worker) {
        this.worker = worker;
    }

    @Override
    public CompletableFuture<List<ContentCategory>> findAllCategories() {
        return worker.submit("Reading the content categories", connection -> {
            String sql = "SELECT " + CATEGORY_COLUMNS + " FROM content_categories "
                    + "ORDER BY display_name ASC, id ASC";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                List<ContentCategory> categories = new ArrayList<>();
                while (rows.next()) {
                    categories.add(readCategory(rows));
                }
                return List.copyOf(categories);
            }
        });
    }

    @Override
    public CompletableFuture<ContentCategory> saveCategory(long id, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return CompletableFuture.failedFuture(
                    new DataAccessException("A category display name cannot be blank"));
        }
        String name = displayName.trim();

        return worker.submitInTransaction("Storing a content category", connection -> {
            // The id comes from the central service, so the local row is kept in step with
            // it rather than being given an identity of its own.
            String sql = "INSERT INTO content_categories (id, display_name) VALUES (?, ?) "
                    + "ON CONFLICT(id) DO UPDATE SET "
                    + "display_name = excluded.display_name, updated_at = CURRENT_TIMESTAMP";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setString(2, name);
                statement.executeUpdate();
            }
            return findCategory(connection, id)
                    .orElseThrow(() -> new DataAccessException("The content category was not stored"));
        });
    }

    @Override
    public CompletableFuture<List<KnowledgeContent>> find(ContentQuery query) {
        Objects.requireNonNull(query, "query");

        return worker.submit("Searching the downloaded content", connection -> {
            StringBuilder sql = new StringBuilder("SELECT " + CONTENT_COLUMNS + " FROM contents WHERE 1 = 1");
            List<Object> parameters = new ArrayList<>();

            if (query.hasText()) {
                sql.append(" AND (title LIKE ? ESCAPE '\\' OR raw_content LIKE ? ESCAPE '\\')");
                String pattern = SqlValues.containsPattern(query.text());
                parameters.add(pattern);
                parameters.add(pattern);
            }
            if (query.availability() != ContentAvailability.ANY) {
                sql.append(" AND is_premium = ?");
                parameters.add(query.availability() == ContentAvailability.PREMIUM ? 1 : 0);
            }
            query.category().ifPresent(categoryId -> {
                sql.append(" AND category_id = ?");
                parameters.add(categoryId);
            });
            sql.append(" ORDER BY title ASC, id ASC");

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    List<KnowledgeContent> contents = new ArrayList<>();
                    while (rows.next()) {
                        contents.add(readContent(rows));
                    }
                    return List.copyOf(contents);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Page<KnowledgeSummary>> findSummaryPage(
            ContentQuery query, PageRequest page) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(page, "page");

        return worker.submit("Reading a page of the downloaded content", connection -> {
            StringBuilder where = new StringBuilder(" WHERE 1 = 1");
            List<Object> parameters = new ArrayList<>();

            if (query.hasText()) {
                where.append(" AND (title LIKE ? ESCAPE '\\' OR raw_content LIKE ? ESCAPE '\\')");
                String pattern = SqlValues.containsPattern(query.text());
                parameters.add(pattern);
                parameters.add(pattern);
            }
            if (query.availability() != ContentAvailability.ANY) {
                where.append(" AND is_premium = ?");
                parameters.add(query.availability() == ContentAvailability.PREMIUM ? 1 : 0);
            }
            query.category().ifPresent(categoryId -> {
                where.append(" AND category_id = ?");
                parameters.add(categoryId);
            });

            long total = countMatching(connection, where.toString(), parameters);
            if (total == 0) {
                return Page.empty(page);
            }

            // The body columns are read as a boolean and never selected. A window of twenty
            // rows that carried raw_content and embedded_data would pull megabytes off disk
            // to render a list of titles.
            //
            // ORDER BY title, id: id is the primary key, so this is a total order. A window
            // taken from anything less is not stable between requests, and the reader sees
            // rows twice while others never appear.
            String sql = "SELECT id, category_id, version, is_premium, title, "
                    + "(embedded_data IS NOT NULL AND TRIM(embedded_data) <> '') AS has_embedding, "
                    + "created_at, updated_at FROM contents" + where
                    + " ORDER BY title ASC, id ASC LIMIT ? OFFSET ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bind(statement, parameters);
                statement.setInt(index++, page.limit());
                statement.setInt(index, page.offset());

                try (ResultSet rows = statement.executeQuery()) {
                    List<KnowledgeSummary> summaries = new ArrayList<>();
                    while (rows.next()) {
                        summaries.add(readSummary(rows));
                    }
                    return new Page<>(summaries, page.offset(), page.limit(), total);
                }
            }
        });
    }

    private static long countMatching(Connection connection, String where, List<Object> parameters)
            throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT COUNT(*) FROM contents" + where)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /** Binds the filter parameters and returns the next free index. */
    private static int bind(PreparedStatement statement, List<Object> parameters)
            throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
        return parameters.size() + 1;
    }

    private static KnowledgeSummary readSummary(ResultSet rows) throws SQLException {
        return new KnowledgeSummary(
                rows.getString("id"),
                rows.getLong("category_id"),
                rows.getString("version"),
                rows.getBoolean("is_premium"),
                rows.getString("title"),
                rows.getBoolean("has_embedding"),
                SqlValues.readInstant(rows.getString("created_at")),
                SqlValues.readInstant(rows.getString("updated_at")));
    }

    @Override
    public CompletableFuture<Optional<KnowledgeContent>> findById(String id) {
        return worker.submit("Reading a downloaded pack", connection -> findContent(connection, id));
    }

    @Override
    public CompletableFuture<Map<String, String>> findStoredVersions() {
        return worker.submit("Reading the stored content versions", connection -> {
            String sql = "SELECT id, version FROM contents ORDER BY id ASC";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                Map<String, String> versions = new LinkedHashMap<>();
                while (rows.next()) {
                    versions.put(rows.getString("id"), rows.getString("version"));
                }
                return Map.copyOf(versions);
            }
        });
    }

    @Override
    public CompletableFuture<KnowledgeContent> save(KnowledgeContent content) {
        Objects.requireNonNull(content, "content");

        return worker.submitInTransaction("Storing a downloaded pack", connection -> {
            // contents.category_id is a foreign key, so the category must be stored first.
            // Saying so plainly beats surfacing a raw constraint violation to the player.
            if (findCategory(connection, content.categoryId()).isEmpty()) {
                throw new DataAccessException(
                        "Category " + content.categoryId() + " must be stored before its content");
            }

            String sql = "INSERT INTO contents "
                    + "(id, category_id, version, is_premium, title, raw_content, embedded_data) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(id) DO UPDATE SET "
                    + "category_id = excluded.category_id, "
                    + "version = excluded.version, "
                    + "is_premium = excluded.is_premium, "
                    + "title = excluded.title, "
                    + "raw_content = excluded.raw_content, "
                    + "embedded_data = excluded.embedded_data, "
                    + "updated_at = CURRENT_TIMESTAMP";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, content.id());
                statement.setLong(2, content.categoryId());
                statement.setString(3, content.version());
                statement.setBoolean(4, content.premium());
                statement.setString(5, content.title());
                statement.setString(6, content.rawContent());
                statement.setString(7, content.embeddedData());
                statement.executeUpdate();
            }
            return findContent(connection, content.id())
                    .orElseThrow(() -> new DataAccessException("The pack was not stored"));
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(String id) {
        return worker.submit("Removing a downloaded pack", connection -> {
            String sql = "DELETE FROM contents WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static Optional<ContentCategory> findCategory(Connection connection, long id) throws SQLException {
        String sql = "SELECT " + CATEGORY_COLUMNS + " FROM content_categories WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readCategory(rows)) : Optional.empty();
            }
        }
    }

    private static Optional<KnowledgeContent> findContent(Connection connection, String id) throws SQLException {
        String sql = "SELECT " + CONTENT_COLUMNS + " FROM contents WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readContent(rows)) : Optional.empty();
            }
        }
    }

    private static ContentCategory readCategory(ResultSet rows) throws SQLException {
        return new ContentCategory(
                rows.getLong("id"),
                rows.getString("display_name"),
                SqlValues.readInstant(rows.getString("created_at")),
                SqlValues.readInstant(rows.getString("updated_at")));
    }

    private static KnowledgeContent readContent(ResultSet rows) throws SQLException {
        return new KnowledgeContent(
                rows.getString("id"),
                rows.getLong("category_id"),
                rows.getString("version"),
                rows.getBoolean("is_premium"),
                rows.getString("title"),
                rows.getString("raw_content"),
                rows.getString("embedded_data"),
                SqlValues.readInstant(rows.getString("created_at")),
                SqlValues.readInstant(rows.getString("updated_at")));
    }
}
