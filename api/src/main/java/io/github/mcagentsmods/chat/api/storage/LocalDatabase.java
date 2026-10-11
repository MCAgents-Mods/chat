package io.github.mcagentsmods.chat.api.storage;

/**
 * The player's local store, at {@code {minecraft_dir}/MCAgents/data.db}.
 *
 * <p>It holds everything the mod remembers: chat history, OpenRouter API keys, and
 * downloaded Knowledge Packs. Nothing here needs the network — that is the point. The mod
 * reads and writes this store whether or not the central service or OpenRouter can be
 * reached.
 *
 * <h2>Everything is asynchronous</h2>
 *
 * Minecraft renders on the client thread, and a blocking database call on that thread
 * freezes the game. So <strong>every method on every repository below returns a
 * {@link java.util.concurrent.CompletableFuture}</strong>, with no synchronous variant —
 * a convenience method is how blocking calls get reintroduced.
 *
 * <p>Callers attach a callback and hand the result back to the client thread before
 * touching any Minecraft state. Never call {@code join()}, {@code get()}, or
 * {@code getNow()} on a future from render or tick code.
 *
 * <p>A failure arrives as a {@link DataAccessException} completing the future.
 */
public interface LocalDatabase extends AutoCloseable {

    /** The player's OpenRouter API keys. */
    ApiKeyRepository apiKeys();

    /** Downloaded Knowledge Packs and their categories. */
    ContentRepository contents();

    /** Conversations and their messages. */
    ChatRepository chat();

    /** Player preferences, including the selected chat and embedding models. */
    SettingsRepository settings();

    /** The player's bound wallets and the chain addresses under each one. */
    WalletRepository wallets();

    /**
     * A counter that moves when stored data changes, so a second view of the same store can
     * tell it has gone stale.
     */
    ChangeFeed changes();

    /**
     * Closes the store and stops the thread it runs its work on. Pending work is allowed
     * to finish first. Calling this twice is harmless.
     */
    @Override
    void close();
}
