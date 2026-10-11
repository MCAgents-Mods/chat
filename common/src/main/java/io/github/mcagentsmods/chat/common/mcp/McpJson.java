package io.github.mcagentsmods.chat.common.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.mcagentsmods.chat.api.mcp.McpException;
import io.github.mcagentsmods.chat.api.mcp.McpPromptDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpResourceDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The MCP wire format: building JSON-RPC requests and reading what comes back.
 *
 * <p>Kept apart from both transports because the two share every byte of it — a stdio
 * server and an HTTP server differ only in how the bytes travel. Keeping the format in one
 * place is also what makes it testable without either.
 *
 * <p>Parsing is defensive throughout, for the same reason as the OpenRouter payloads: these
 * servers are third-party code the player chose, and a missing field must produce a clear
 * {@link McpException} rather than a {@link NullPointerException} somewhere else.
 */
final class McpJson {

    /**
     * The protocol version the mod speaks.
     *
     * <p>Servers negotiate: a server on a newer revision answers with its own, and the
     * fields used here have been stable across every revision that supports them.
     */
    static final String PROTOCOL_VERSION = "2025-06-18";

    private McpJson() {
        throw new AssertionError("No instances.");
    }

    /**
     * Waits for {@code future} and unwraps what a failure actually was.
     *
     * <p>{@link java.util.concurrent.CompletableFuture#join()} wraps every failure in a
     * {@link java.util.concurrent.CompletionException}, whose message is the cause's class
     * name followed by its message. That string ends up in a status row a player reads, so
     * the wrapper is removed here rather than left to every caller to remember.
     */
    static <T> T await(String serverId, java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            throw asMcpException(serverId, wrapped.getCause() == null ? wrapped : wrapped.getCause());
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw new McpException(serverId, "Request was cancelled", cancelled);
        }
    }

    /** The failure as an {@link McpException}, keeping one that already is. */
    static McpException asMcpException(String serverId, Throwable failure) {
        if (failure instanceof McpException already) {
            return already;
        }
        String message = failure.getMessage();
        return new McpException(serverId,
                message == null || message.isBlank() ? failure.getClass().getSimpleName() : message,
                failure);
    }

    /** A JSON-RPC request envelope. */
    static String request(long id, String method, JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params == null ? new JsonObject() : params);
        return message.toString();
    }

    /** A JSON-RPC notification — no id, and no reply is expected. */
    static String notification(String method) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        message.add("params", new JsonObject());
        return message.toString();
    }

    /** The {@code initialize} parameters identifying this mod to a server. */
    static JsonObject initializeParams() {
        JsonObject client = new JsonObject();
        client.addProperty("name", "MCAgentsClient");
        client.addProperty("version", "1");

        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        params.add("clientInfo", client);
        return params;
    }

    /** The {@code tools/call} parameters. */
    static JsonObject callToolParams(String name, Map<String, Object> arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", toJson(arguments));
        return params;
    }

    /** The {@code resources/read} parameters. */
    static JsonObject readResourceParams(String uri) {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        return params;
    }

    /**
     * Unwraps a JSON-RPC reply to its {@code result}.
     *
     * @throws McpException if the reply is not JSON, or carries an {@code error}
     */
    static JsonObject result(String serverId, String payload) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(payload);
        } catch (RuntimeException malformed) {
            throw new McpException(serverId, "Server did not answer with JSON", malformed);
        }
        if (!parsed.isJsonObject()) {
            throw new McpException(serverId, "Server answered with JSON that is not an object");
        }
        JsonObject message = parsed.getAsJsonObject();
        if (message.has("error") && message.get("error").isJsonObject()) {
            JsonObject error = message.getAsJsonObject("error");
            String detail = string(error, "message");
            int code = error.has("code") ? error.get("code").getAsInt() : 0;
            throw new McpException(serverId,
                    "Server refused the request" + (code == 0 ? "" : " (" + code + ")")
                            + (detail.isEmpty() ? "" : ": " + detail));
        }
        if (!message.has("result") || !message.get("result").isJsonObject()) {
            throw new McpException(serverId, "Server answered without a result");
        }
        return message.getAsJsonObject("result");
    }

    /** Reads {@code tools/list} into descriptors. */
    static List<McpToolDescriptor> tools(String serverId, JsonObject result) {
        List<McpToolDescriptor> tools = new ArrayList<>();
        for (JsonElement element : array(result, "tools")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject tool = element.getAsJsonObject();
            String name = string(tool, "name");
            if (name.isEmpty()) {
                continue;
            }
            String schema = tool.has("inputSchema") && tool.get("inputSchema").isJsonObject()
                    ? tool.getAsJsonObject("inputSchema").toString()
                    : "";
            tools.add(new McpToolDescriptor(serverId, name, string(tool, "description"), schema));
        }
        return tools;
    }

    /** Reads {@code resources/list} into descriptors. */
    static List<McpResourceDescriptor> resources(String serverId, JsonObject result) {
        List<McpResourceDescriptor> resources = new ArrayList<>();
        for (JsonElement element : array(result, "resources")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject resource = element.getAsJsonObject();
            String uri = string(resource, "uri");
            if (uri.isEmpty()) {
                continue;
            }
            resources.add(new McpResourceDescriptor(
                    serverId,
                    uri,
                    string(resource, "name"),
                    string(resource, "description"),
                    string(resource, "mimeType")));
        }
        return resources;
    }

    /** Reads {@code prompts/list} into descriptors. */
    static List<McpPromptDescriptor> prompts(String serverId, JsonObject result) {
        List<McpPromptDescriptor> prompts = new ArrayList<>();
        for (JsonElement element : array(result, "prompts")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject prompt = element.getAsJsonObject();
            String name = string(prompt, "name");
            if (name.isEmpty()) {
                continue;
            }
            prompts.add(new McpPromptDescriptor(serverId, name, string(prompt, "description")));
        }
        return prompts;
    }

    /**
     * Flattens a {@code tools/call} result to the text a model reads.
     *
     * <p>Non-text content — an image, an audio blob — is named rather than inlined. A model
     * being handed base64 it cannot decode is worse than being told what it was offered.
     */
    static McpToolResult toolResult(JsonObject result) {
        List<String> blocks = new ArrayList<>();
        for (JsonElement element : array(result, "content")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject content = element.getAsJsonObject();
            String type = string(content, "type");
            if (type.equals("text")) {
                blocks.add(string(content, "text"));
            } else if (type.equals("resource")) {
                JsonObject embedded = content.has("resource") && content.get("resource").isJsonObject()
                        ? content.getAsJsonObject("resource")
                        : new JsonObject();
                String text = string(embedded, "text");
                blocks.add(text.isEmpty() ? "[resource " + string(embedded, "uri") + "]" : text);
            } else if (!type.isEmpty()) {
                blocks.add("[" + type + " content, not shown]");
            }
        }
        boolean isError = result.has("isError") && result.get("isError").isJsonPrimitive()
                && result.get("isError").getAsBoolean();
        if (blocks.isEmpty()) {
            blocks.add(isError ? "The tool reported an error and said nothing more."
                    : "The tool returned no content.");
        }
        return McpToolResult.of(blocks, isError);
    }

    /** Flattens a {@code resources/read} result to text. */
    static String resourceText(JsonObject result) {
        StringBuilder text = new StringBuilder();
        for (JsonElement element : array(result, "contents")) {
            if (!element.isJsonObject()) {
                continue;
            }
            String body = string(element.getAsJsonObject(), "text");
            if (!body.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(body);
            }
        }
        return text.toString();
    }

    /** A string field, or empty when it is absent or not a string. */
    static String string(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = object.getAsJsonPrimitive(field);
        return primitive.isString() ? primitive.getAsString() : primitive.toString();
    }

    private static JsonArray array(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonArray()) {
            return new JsonArray();
        }
        return object.getAsJsonArray(field);
    }

    /** Converts parsed tool arguments back to JSON for the wire. */
    static JsonObject toJson(Map<String, Object> arguments) {
        JsonObject object = new JsonObject();
        if (arguments == null) {
            return object;
        }
        arguments.forEach((key, value) -> object.add(key, element(value)));
        return object;
    }

    private static JsonElement element(Object value) {
        return switch (value) {
            case null -> com.google.gson.JsonNull.INSTANCE;
            case JsonElement json -> json;
            case String text -> new JsonPrimitive(text);
            case Number number -> new JsonPrimitive(number);
            case Boolean flag -> new JsonPrimitive(flag);
            case Map<?, ?> map -> {
                JsonObject nested = new JsonObject();
                map.forEach((k, v) -> nested.add(String.valueOf(k), element(v)));
                yield nested;
            }
            case Iterable<?> items -> {
                JsonArray array = new JsonArray();
                items.forEach(item -> array.add(element(item)));
                yield array;
            }
            default -> new JsonPrimitive(String.valueOf(value));
        };
    }
}
