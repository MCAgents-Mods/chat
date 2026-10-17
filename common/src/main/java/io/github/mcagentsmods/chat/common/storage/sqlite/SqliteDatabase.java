package io.github.mcagentsmods.chat.common.storage.sqlite;

import io.github.mcagentsmods.chat.api.storage.ApiKeyRepository;
import io.github.mcagentsmods.chat.api.storage.ChangeFeed;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.chat.api.storage.ContentRepository;
import io.github.mcagentsmods.chat.api.storage.DataAccessException;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.api.storage.SettingsRepository;
import io.github.mcagentsmods.chat.api.storage.WalletRepository;
import io.github.mcagentsmods.chat.common.security.TokenCipher;
import io.github.mcagentsmods.chat.common.storage.CachingContentRepository;
import io.github.mcagentsmods.chat.common.storage.RevisionedChatRepository;
import io.github.mcagentsmods.core.api.model.GlobalModelStore;
import io.github.mcagentsmods.core.api.model.GlobalModels;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The local store, backed by SQLite.
 *
 * <p>This class knows nothing about Minecraft. It is handed a path — from the game
 * directory in production, from a temporary directory in a test — and everything else
 * follows from that.
 *
 * <p>Open it with {@link #open(Path)} and close it with {@link #close()}.
 */
public final class SqliteDatabase implements LocalDatabase {

    private static final Logger LOGGER = System.getLogger(SqliteDatabase.class.getName());

    /** How long {@link #close()} waits for work already in flight. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Path file;
    private final Connection connection;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final ApiKeyRepository apiKeys;
    private final ContentRepository contents;
    private final ChatRepository chat;
    private final SettingsRepository settings;
    private final WalletRepository wallets;
    private final AtomicLong chatRevision = new AtomicLong();

    private SqliteDatabase(Path file, Connection connection, ExecutorService executor,
                           TokenCipher cipher, GlobalModelStore models) {
        this.file = file;
        this.connection = connection;
        this.executor = executor;

        SqlWorker worker = new SqlWorker(connection, executor);
        // The cipher reaches exactly one repository. Tokens are the only thing in this
        // database that is encrypted, because they are the only thing in it that is a
        // credential — a conversation is not made safer by being unreadable to its owner.
        this.apiKeys = new SqliteApiKeyRepository(worker, cipher);
        // Packs carry the largest rows in the database and are read on every question the
        // player asks, so reads of them go through a cache. The database stays the record;
        // the cache is dropped whenever a write touches what it holds.
        this.contents = new CachingContentRepository(new SqliteContentRepository(worker));
        // Wrapped so that every chat write moves the same counter, whoever makes it. A
        // counter owned by one writer would only ever see that writer's own writes.
        this.chat = new RevisionedChatRepository(new SqliteChatRepository(worker), chatRevision);
        // The model choices are core's global model, which every MCAgents mod reads; only the
        // embedding switch is kept in this database.
        this.settings = new SqliteSettingsRepository(worker, models);
        // Not cached. A player has a handful of wallets, they are read when a screen opens
        // rather than on every question, and a stale address is the one thing here that
        // would actually cost the player something.
        this.wallets = new SqliteWalletRepository(worker);
    }

    /**
     * Opens the store at {@code file}, creating the file, its parent directory, and the
     * schema if they do not exist.
     *
     * <p>The work happens on the database thread, so this never blocks the caller. The
     * returned future completes with a {@link DataAccessException} if the store cannot be
     * opened — which is a real possibility on a player's machine (a read-only directory, a
     * file locked by another process) and must be handled rather than crashing the game.
     *
     * <p>Stored tokens are encrypted with the key in
     * {@value TokenCipher#KEY_VARIABLE} if this machine has one, and stored as they are if it
     * does not — see {@link #open(Path, TokenCipher)}.
     *
     * @param file the database file, usually {@code {minecraft_dir}/MCAgents/data.db}
     */
    public static CompletableFuture<LocalDatabase> open(Path file) {
        return open(file, TokenCipher.fromEnvironment());
    }

    /**
     * Opens the store with an explicit cipher.
     *
     * <p>The seam tests use, so proving what encryption does to the database never means
     * setting an environment variable for the whole JVM.
     *
     * <p>When the cipher encrypts, any token still stored in the clear — written before
     * encryption existed, or while no key was configured — is encrypted here, once, before
     * anything can read it. Turning encryption on has to protect the keys the player already
     * had; protecting only the next one they paste would be a promise the database does not
     * keep.
     *
     * @param file   the database file
     * @param cipher what encrypts and decrypts stored tokens
     */
    public static CompletableFuture<LocalDatabase> open(Path file, TokenCipher cipher) {
        return open(file, cipher, GlobalModelStore.inMemory(GlobalModels.DEFAULTS));
    }

    /**
     * Opens the store over the game's global model.
     *
     * <p>The two shorter forms keep the model choices in memory, starting from the defaults,
     * which is what a test wants; the game passes core's store, {@code Models.store()}.
     *
     * @param file   the database file
     * @param cipher what encrypts and decrypts stored tokens
     * @param models where the chat and embedding choices are kept
     */
    public static CompletableFuture<LocalDatabase> open(Path file, TokenCipher cipher,
                                                        GlobalModelStore models) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(cipher, "cipher");
        Objects.requireNonNull(models, "models");
        Path absolute = file.toAbsolutePath().normalize();
        ExecutorService executor = newDatabaseThread(absolute);

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path parent = absolute.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Connection connection = connect(absolute);
                try {
                    SqliteSchema.apply(connection);
                    int encrypted = SqliteApiKeyRepository.encryptStoredKeys(connection, cipher);
                    if (encrypted > 0) {
                        LOGGER.log(Level.INFO,
                                "Encrypted {0} stored API keys that were held in the clear",
                                encrypted);
                    }
                } catch (SQLException e) {
                    closeQuietly(connection);
                    throw e;
                }
                LOGGER.log(Level.INFO, "Opened the local database at {0}", absolute);
                return (LocalDatabase) new SqliteDatabase(absolute, connection, executor, cipher, models);
            } catch (SQLException | IOException e) {
                executor.shutdownNow();
                throw new DataAccessException("Opening the local database at " + absolute + " failed", e);
            }
        }, executor);
    }

    private static Connection connect(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        // ON DELETE CASCADE on chat_messages only works with this on; SQLite defaults it off.
        config.enforceForeignKeys(true);
        // Survives an abrupt exit — closing Minecraft from the launcher, or a crash.
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5_000);

        // A DataSource rather than DriverManager: JDBC's ServiceLoader lookup is not
        // reliable under a mod loader's classloader, and this does not depend on it.
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file);
        return dataSource.getConnection();
    }

    private static ExecutorService newDatabaseThread(Path file) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MCAgents-database");
            // The game must be able to exit even if a query is stuck.
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) ->
                    LOGGER.log(Level.ERROR, "Uncaught failure on the database thread for " + file, e));
            return thread;
        });
    }

    @Override
    public ApiKeyRepository apiKeys() {
        return apiKeys;
    }

    @Override
    public ContentRepository contents() {
        return contents;
    }

    @Override
    public ChatRepository chat() {
        return chat;
    }

    @Override
    public SettingsRepository settings() {
        return settings;
    }

    @Override
    public WalletRepository wallets() {
        return wallets;
    }

    @Override
    public ChangeFeed changes() {
        // A read of an AtomicLong and nothing else, so this is safe to call from the client
        // thread on every tick.
        return chatRevision::get;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> closeQuietly(connection));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.log(Level.WARNING, "The database thread did not stop in time; abandoning it");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** The file this store is backed by. */
    public Path file() {
        return file;
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Closing the database connection failed", e);
        }
    }

    @Override
    public String toString() {
        return "SqliteDatabase[" + file + "]";
    }
}
