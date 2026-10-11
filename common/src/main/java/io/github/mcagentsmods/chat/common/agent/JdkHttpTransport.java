package io.github.mcagentsmods.chat.common.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The real transport, over the JDK's HTTP client.
 *
 * <p>Both timeouts are set deliberately. A request without one can hang until the player
 * closes the game, and "the mod froze" is indistinguishable from "the mod is broken".
 */
public final class JdkHttpTransport implements HttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient httpClient;

    public JdkHttpTransport() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public CompletableFuture<HttpReply> send(HttpCall call) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(call.url()))
                // Generous, because a long answer legitimately takes a while to generate.
                .timeout(REQUEST_TIMEOUT);

        builder.method(call.method(), HttpRequest.BodyPublishers.ofString(call.body()));

        call.headers().forEach(builder::header);

        return httpClient
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new HttpReply(response.statusCode(), response.body()));
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
