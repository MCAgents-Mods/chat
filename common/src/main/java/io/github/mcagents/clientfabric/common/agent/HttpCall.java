package io.github.mcagents.clientfabric.common.agent;

import java.util.Map;
import java.util.Objects;

/**
 * One HTTP request, described without reference to any HTTP library.
 *
 * <p>The indirection buys one thing that matters: tests drive the client through a stub
 * transport, so retry, key rotation, and JSON handling are all exercised without a network
 * or an API key.
 *
 * @param url     absolute endpoint
 * @param headers request headers, including authorization
 * @param body    JSON body
 */
public record HttpCall(String url, Map<String, String> headers, String body) {

    public HttpCall {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
    }
}
