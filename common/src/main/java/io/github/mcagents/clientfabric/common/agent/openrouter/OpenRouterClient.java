package io.github.mcagents.clientfabric.common.agent.openrouter;

import io.github.mcagents.clientfabric.api.agent.ApiKeySource;
import io.github.mcagents.clientfabric.api.agent.ChatCompletion;
import io.github.mcagents.clientfabric.api.agent.ChatRequest;
import io.github.mcagents.clientfabric.api.agent.ModelClient;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.common.agent.HttpCall;
import io.github.mcagents.clientfabric.common.agent.HttpReply;
import io.github.mcagents.clientfabric.common.agent.HttpTransport;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The OpenRouter implementation of {@link ModelClient}.
 *
 * <p>Three behaviours live here so that no caller has to think about them:
 *
 * <ul>
 *   <li><strong>Key rotation.</strong> A rejected key is deactivated and the next one is
 *       tried, so a player who topped up a second key does not see a failed conversation.</li>
 *   <li><strong>Retries.</strong> Rate limits, provider failures, and transport failures
 *       back off and retry. A bad request does not — repeating it cannot help.</li>
 *   <li><strong>Timeouts.</strong> Enforced by the transport, on every call.</li>
 * </ul>
 *
 * <p>This class knows nothing about Minecraft and nothing about where keys are stored. It
 * is given an {@link ApiKeySource} and an {@link HttpTransport}, which is what lets every
 * behaviour above be tested without a network or an API key.
 */
public final class OpenRouterClient implements ModelClient {

    private static final Logger LOGGER = System.getLogger(OpenRouterClient.class.getName());

    private static final String CHAT_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String EMBEDDINGS_URL = "https://openrouter.ai/api/v1/embeddings";

    /** Attempts per request, including the first. Three is enough to ride out a blip. */
    private static final int MAX_ATTEMPTS = 3;

    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);

    /** How many keys to try before giving up, so a wallet of dead keys still terminates. */
    private static final int MAX_KEYS_PER_REQUEST = 5;

    private final HttpTransport transport;
    private final ApiKeySource keys;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Duration initialBackoff;

    public OpenRouterClient(HttpTransport transport, ApiKeySource keys) {
        this(transport, keys, defaultScheduler(), true, DEFAULT_INITIAL_BACKOFF);
    }

    /** Test seam: the backoff is injectable so retry tests do not sleep for real. */
    OpenRouterClient(HttpTransport transport, ApiKeySource keys,
                     ScheduledExecutorService scheduler, boolean ownsScheduler,
                     Duration initialBackoff) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
    }

    private static ScheduledExecutorService defaultScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "MCAgents-openrouter-retry");
            // The game must be able to exit while a backoff is pending.
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override
    public CompletableFuture<ChatCompletion> complete(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        String body = OpenRouterPayloads.chatRequestBody(request);
        return withKeyRotation(CHAT_URL, body, OpenRouterPayloads::readChatCompletion, 0);
    }

    @Override
    public CompletableFuture<float[]> embed(String model, String text) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(text, "text");
        if (model.isBlank()) {
            return CompletableFuture.failedFuture(new ModelException(
                    ModelException.Kind.REQUEST, "An embedding request needs a model"));
        }
        String body = OpenRouterPayloads.embeddingRequestBody(model, text);
        return withKeyRotation(EMBEDDINGS_URL, body, OpenRouterPayloads::readEmbedding, 0);
    }

    /**
     * Sends with the next active key, moving to the one after it if the provider rejects it.
     *
     * @param keysTried how many keys have already been rejected for this request
     */
    private <T> CompletableFuture<T> withKeyRotation(
            String url, String body, Function<String, T> reader, int keysTried) {

        if (keysTried >= MAX_KEYS_PER_REQUEST) {
            return CompletableFuture.failedFuture(new ModelException(ModelException.Kind.NO_KEY,
                    "Every stored API key was rejected"));
        }

        return keys.next().thenCompose(maybeKey -> {
            Optional<String> key = maybeKey;
            if (key.isEmpty()) {
                return CompletableFuture.<T>failedFuture(new ModelException(
                        ModelException.Kind.NO_KEY,
                        keysTried == 0
                                ? "No OpenRouter API key is configured"
                                : "Every stored API key was rejected"));
            }
            String apiKey = key.get();

            return attempt(url, body, apiKey, reader, 1).handle((result, failure) -> {
                if (failure == null) {
                    return CompletableFuture.completedFuture(result);
                }
                ModelException error = asModelException(failure);
                if (error.kind() != ModelException.Kind.AUTHENTICATION) {
                    return CompletableFuture.<T>failedFuture(error);
                }
                // The provider rejected this key. Retire it and carry on with the next,
                // without the player seeing a failure that another key would have served.
                LOGGER.log(Level.WARNING, "An API key was rejected; rotating to the next");
                return keys.reject(apiKey)
                        .thenCompose(ignored -> withKeyRotation(url, body, reader, keysTried + 1));
            }).thenCompose(Function.identity());
        });
    }

    /** One send, retrying the same key while the failure is worth retrying. */
    private <T> CompletableFuture<T> attempt(
            String url, String body, String apiKey, Function<String, T> reader, int attempt) {

        HttpCall call = HttpCall.post(url, headers(apiKey), body);

        return transport.send(call)
                .handle((reply, failure) -> {
                    if (failure != null) {
                        throw new ModelException(ModelException.Kind.TRANSPORT,
                                "The request to the provider could not be sent", failure);
                    }
                    return reply;
                })
                .thenApply(reply -> {
                    if (!reply.isSuccess()) {
                        throw errorFor(reply);
                    }
                    return reader.apply(reply.body());
                })
                .handle((result, failure) -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    ModelException error = asModelException(failure);
                    if (!error.isRetryable() || attempt >= MAX_ATTEMPTS) {
                        return CompletableFuture.<T>failedFuture(error);
                    }
                    return delay(backoffFor(attempt))
                            .thenCompose(ignored -> attempt(url, body, apiKey, reader, attempt + 1));
                })
                .thenCompose(Function.identity());
    }

    private static Map<String, String> headers(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        // The one place a key value leaves this mod.
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        // OpenRouter uses these for attribution; neither identifies the player.
        headers.put("HTTP-Referer", "https://mcagents.mcrealms.space");
        headers.put("X-Title", "MCAgentsClient");
        return headers;
    }

    private static ModelException errorFor(HttpReply reply) {
        String message = OpenRouterPayloads.readErrorMessage(reply.body(), reply.status());
        ModelException.Kind kind = switch (reply.status()) {
            case 401, 403 -> ModelException.Kind.AUTHENTICATION;
            case 402 -> ModelException.Kind.AUTHENTICATION; // out of credit: try another key
            case 429 -> ModelException.Kind.RATE_LIMIT;
            default -> reply.status() >= 500
                    ? ModelException.Kind.SERVER
                    : ModelException.Kind.REQUEST;
        };
        return new ModelException(kind, message);
    }

    /** Exponential, with jitter so a wave of clients does not retry in lockstep. */
    private Duration backoffFor(int attempt) {
        long base = initialBackoff.toMillis() * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
        return Duration.ofMillis(base + jitter);
    }

    private CompletableFuture<Void> delay(Duration duration) {
        CompletableFuture<Void> waited = new CompletableFuture<>();
        scheduler.schedule(() -> waited.complete(null), duration.toMillis(), TimeUnit.MILLISECONDS);
        return waited;
    }

    private static ModelException asModelException(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                ? failure.getCause()
                : failure;
        if (cause instanceof ModelException modelException) {
            return modelException;
        }
        return new ModelException(ModelException.Kind.TRANSPORT,
                "The request to the provider failed", cause);
    }

    /** The executor retries are scheduled on, exposed for tests. */
    Executor scheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        transport.close();
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
}
