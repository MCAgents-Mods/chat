package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.chat.api.cache.Cache;
import io.github.mcagentsmods.chat.api.cache.CacheStats;
import io.github.mcagentsmods.chat.common.cache.TtlCache;
import io.github.mcagentsmods.core.api.model.ChatCompletion;
import io.github.mcagentsmods.core.api.model.ChatRequest;
import io.github.mcagentsmods.core.api.model.ModelChoice;
import io.github.mcagentsmods.core.api.model.ModelClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Caches embeddings locally, on top of whatever the provider caches for itself.
 *
 * <h2>Why embeddings and not completions</h2>
 *
 * <p>An embedding is a pure function: the same text and the same model always produce the
 * same vector. Asking twice is money and latency spent on an answer already known.
 *
 * <p>A completion is not. The same prompt can and should be able to produce a different
 * answer, and a player who asks the same question twice generally wants a second attempt,
 * not a replay. <strong>Completions are deliberately not cached here</strong> — reuse of
 * the prompt is the provider's job, through the cache breakpoints in
 * {@link PromptAssembler}, and that reuse costs the player nothing in freshness.
 *
 * <p>Retrieval embeds the player's question on every turn, and conversations repeat
 * themselves — "how do I get netherite" asked in three sessions is three identical
 * embedding calls without this.
 */
public final class CachingModelClient implements ModelClient {

    /** Embeddings never change for a given model and text, so this is generous. */
    private static final Duration EMBEDDING_TTL = Duration.ofHours(6);

    /** Distinct texts held at once. Questions repeat far more than they vary. */
    private static final int EMBEDDING_CACHE_SIZE = 256;

    private final ModelClient delegate;
    private final Cache<String, float[]> embeddings;

    public CachingModelClient(ModelClient delegate) {
        this(delegate, new TtlCache<>(EMBEDDING_CACHE_SIZE, EMBEDDING_TTL));
    }

    CachingModelClient(ModelClient delegate, Cache<String, float[]> embeddings) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    }

    @Override
    public CompletableFuture<ChatCompletion> complete(ChatRequest request) {
        // Not cached, on purpose. See the class comment.
        return delegate.complete(request);
    }

    @Override
    public CompletableFuture<float[]> embed(String model, String text) {
        return cached(model, text, () -> delegate.embed(model, text));
    }

    /**
     * Keyed by the model alone, not the provider: one model gives one vector for one text
     * whichever API served it, so a vector fetched through OpenRouter answers a direct call too.
     */
    @Override
    public CompletableFuture<float[]> embed(ModelChoice choice, String text) {
        return cached(choice.model(), text, () -> delegate.embed(choice, text));
    }

    private CompletableFuture<float[]> cached(String model, String text,
                                              Supplier<CompletableFuture<float[]>> fetch) {
        String key = cacheKey(model, text);

        return embeddings.get(key)
                // Copied on the way out: the array is mutable, and a caller that sorted or
                // normalised it in place would corrupt the entry for everyone after.
                .map(vector -> CompletableFuture.completedFuture(vector.clone()))
                .orElseGet(() -> fetch.get().thenApply(vector -> {
                    embeddings.put(key, vector.clone());
                    return vector;
                }));
    }

    /**
     * The model has to be part of the key: the same text embedded by two models gives two
     * different vectors, and mixing them silently ruins retrieval.
     *
     * <p>The text is hashed rather than kept. The texts embedded are whole Knowledge Pack
     * chunks, and a key holding one keeps it on the heap for the cache's lifetime — hours,
     * times every entry — to answer a question a digest answers as well.
     */
    private static String cacheKey(String model, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(model.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /** What the embedding cache has done, for diagnosing a cache that is not helping. */
    public CacheStats embeddingCacheStats() {
        return embeddings.stats();
    }

    @Override
    public void close() {
        embeddings.invalidateAll();
        delegate.close();
    }
}
