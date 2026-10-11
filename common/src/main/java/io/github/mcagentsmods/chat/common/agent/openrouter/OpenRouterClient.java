package io.github.mcagentsmods.chat.common.agent.openrouter;

import io.github.mcagentsmods.chat.api.agent.ApiKeySource;
import io.github.mcagentsmods.chat.api.agent.ChatCompletion;
import io.github.mcagentsmods.chat.api.agent.ChatRequest;
import io.github.mcagentsmods.chat.api.agent.ModelClient;
import io.github.mcagentsmods.chat.api.agent.ModelException;
import io.github.mcagentsmods.chat.api.agent.ProviderRoute;
import io.github.mcagentsmods.chat.api.agent.ProviderRoutes;
import io.github.mcagentsmods.chat.common.agent.HttpCall;
import io.github.mcagentsmods.chat.common.agent.HttpReply;
import io.github.mcagentsmods.chat.common.agent.HttpTransport;

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
 * The {@link ModelClient} that speaks the wire format OpenRouter defines.
 *
 * <p>That format is OpenAI's — {@code POST {base}/chat/completions} and
 * {@code POST {base}/embeddings}, a bearer token, the same request and response shapes — which
 * is why one client covers both routes a request can take. A {@link ProviderRoutes} decides
 * which: through the gateway, where the whole {@code {platform}/{model}} name travels and the
 * gateway's own extensions are welcome, or straight to a platform's own API, where the bare
 * model id travels and those extensions would be a 400. The default is the gateway, for every
 * request, which is what this client did before it could route at all.
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

    /** Attempts per request, including the first. Three is enough to ride out a blip. */
    private static final int MAX_ATTEMPTS = 3;

    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);

    /** How many keys to try before giving up, so a wallet of dead keys still terminates. */
    private static final int MAX_KEYS_PER_REQUEST = 5;

    private final HttpTransport transport;
    private final ApiKeySource keys;
    private final ProviderRoutes routes;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Duration initialBackoff;

    /** A client that sends everything through the gateway. */
    public OpenRouterClient(HttpTransport transport, ApiKeySource keys) {
        this(transport, keys, ProviderRoutes.throughOpenRouter());
    }

    /**
     * A client that asks {@code routes} where each request goes.
     *
     * @param routes resolves a stored model id to the address and the key that serve it
     */
    public OpenRouterClient(HttpTransport transport, ApiKeySource keys, ProviderRoutes routes) {
        this(transport, keys, routes, defaultScheduler(), true, DEFAULT_INITIAL_BACKOFF);
    }

    /** Test seam: the backoff is injectable so retry tests do not sleep for real. */
    OpenRouterClient(HttpTransport transport, ApiKeySource keys, ProviderRoutes routes,
                     ScheduledExecutorService scheduler, boolean ownsScheduler,
                     Duration initialBackoff) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.routes = Objects.requireNonNull(routes, "routes");
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
        return routes.route(request.model()).thenCompose(route -> {
            // The route decides what the model is called on the wire: the whole stored name
            // through the gateway, the platform's own id when the platform is called directly.
            ChatRequest routed = route.isThroughOpenRouter()
                    ? request
                    : new ChatRequest(route.modelId(), request.messages(), request.tools());
            String body = OpenRouterPayloads.chatRequestBody(routed, route.isThroughOpenRouter());
            return withKeyRotation(route, route.chatUrl(), body,
                    OpenRouterPayloads::readChatCompletion, 0);
        });
    }

    @Override
    public CompletableFuture<float[]> embed(String model, String text) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(text, "text");
        if (model.isBlank()) {
            return CompletableFuture.failedFuture(new ModelException(
                    ModelException.Kind.REQUEST, "An embedding request needs a model"));
        }
        return routes.route(model).thenCompose(route -> {
            String body = OpenRouterPayloads.embeddingRequestBody(route.modelId(), text);
            return withKeyRotation(route, route.embeddingsUrl(), body,
                    OpenRouterPayloads::readEmbedding, 0);
        });
    }

    /**
     * Sends with the next active key, moving to the one after it if the provider rejects it.
     *
     * @param keysTried how many keys have already been rejected for this request
     */
    private <T> CompletableFuture<T> withKeyRotation(
            ProviderRoute route, String url, String body, Function<String, T> reader,
            int keysTried) {

        if (keysTried >= MAX_KEYS_PER_REQUEST) {
            return CompletableFuture.failedFuture(new ModelException(ModelException.Kind.NO_KEY,
                    "Every stored API key was rejected"));
        }

        return keys.next(route.platform()).thenCompose(maybeKey -> {
            Optional<String> key = maybeKey;
            if (key.isEmpty()) {
                return CompletableFuture.<T>failedFuture(new ModelException(
                        ModelException.Kind.NO_KEY,
                        keysTried == 0
                                ? "No API key is configured for " + route.platform()
                                : "Every stored API key was rejected"));
            }
            String apiKey = key.get();

            return attempt(route, url, body, apiKey, reader, 1).handle((result, failure) -> {
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
                        .thenCompose(ignored ->
                                withKeyRotation(route, url, body, reader, keysTried + 1));
            }).thenCompose(Function.identity());
        });
    }

    /** One send, retrying the same key while the failure is worth retrying. */
    private <T> CompletableFuture<T> attempt(
            ProviderRoute route, String url, String body, String apiKey,
            Function<String, T> reader, int attempt) {

        HttpCall call = HttpCall.post(url, headers(apiKey, route), body);

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
                            .thenCompose(ignored ->
                                    attempt(route, url, body, apiKey, reader, attempt + 1));
                })
                .thenCompose(Function.identity());
    }

    private static Map<String, String> headers(String apiKey, ProviderRoute route) {
        Map<String, String> headers = new LinkedHashMap<>();
        // The one place a key value leaves this mod — and it goes to the platform the key was
        // stored for, never to another one.
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        if (route.isThroughOpenRouter()) {
            // OpenRouter uses these for attribution; neither identifies the player. They mean
            // nothing to a platform's own API, so they are not sent there.
            headers.put("HTTP-Referer", "https://github.com/MCAgents-Mods/chat");
            headers.put("X-Title", "MCAgentsClient");
        }
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
