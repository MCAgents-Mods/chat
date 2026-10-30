package io.github.mcagentsmods.chat.common.wallet;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The HTTP plumbing {@link LocalWalletBridge} answers its page with: reading a bounded request
 * body, reading a query parameter, and writing JSON, errors and empty replies with the headers
 * every reply carries.
 *
 * <p>Nothing here knows about wallets, tokens or which requests are allowed — the bridge
 * decides that before it calls in.
 */
final class LoopbackHttp {

    /** Enough for a signature and an address; anything larger is not a reply we sent for. */
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private LoopbackHttp() {
    }

    static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            // Bounded: an unbounded read on a socket anyone local can open is a way to make
            // the game run out of memory.
            byte[] bytes = stream.readNBytes(MAX_BODY_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static String string(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString()
                : "";
    }

    static String decode(String value) {
        return value == null
                ? null
                : java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static String queryValue(String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(key)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    static void sendJson(HttpExchange exchange, int status, JsonObject body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        send(exchange, status, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void sendError(HttpExchange exchange, int status, String reason)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", reason);
        sendJson(exchange, status, body);
    }

    static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        // No CORS headers, on purpose: without them the browser refuses to let another
        // origin read anything this bridge says.
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
