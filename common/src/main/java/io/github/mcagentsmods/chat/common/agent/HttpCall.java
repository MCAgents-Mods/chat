package io.github.mcagentsmods.chat.common.agent;

import java.util.Map;
import java.util.Objects;

/**
 * One HTTP request, described without reference to any HTTP library.
 *
 * <p>The indirection buys one thing that matters: tests drive the client through a stub
 * transport, so retry, key rotation, and JSON handling are all exercised without a network
 * or an API key.
 *
 * @param method  HTTP method; {@code GET} carries no body
 * @param url     absolute endpoint
 * @param headers request headers, including authorization
 * @param body    JSON body, empty for a GET
 */
public record HttpCall(String method, String url, Map<String, String> headers, String body) {

    public HttpCall {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
    }

    /** A POST carrying a JSON body — what the model provider takes. */
    public static HttpCall post(String url, Map<String, String> headers, String body) {
        return new HttpCall("POST", url, headers, body);
    }

    /** A GET — what the central service's reads take. */
    public static HttpCall get(String url, Map<String, String> headers) {
        return new HttpCall("GET", url, headers, "");
    }
}
