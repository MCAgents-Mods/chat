package io.github.mcagents.clientfabric.common.agent.openrouter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.github.mcagents.clientfabric.api.agent.ChatCompletion;
import io.github.mcagents.clientfabric.api.agent.ChatRequest;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import io.github.mcagents.clientfabric.api.agent.TokenUsage;

/**
 * Builds the JSON OpenRouter expects and reads the JSON it returns.
 *
 * <p>Kept apart from the client so the wire format can be exercised on its own — the shape
 * of a request is the thing most likely to be wrong, and the thing hardest to see in a
 * failure.
 *
 * <p>Parsing is defensive throughout. A provider that omits a field, returns an empty
 * choices array, or answers with HTML from a proxy must produce a clear
 * {@link ModelException}, not a {@link NullPointerException} three frames away.
 */
final class OpenRouterPayloads {

    private OpenRouterPayloads() {
        throw new AssertionError("No instances.");
    }

    /**
     * Builds the body of a chat completion request.
     *
     * <p>A message with no cache breakpoint is written the plain way, with {@code content}
     * as a string. A message that carries one is written as a single-part content array
     * with {@code cache_control: {"type": "ephemeral"}} on the part, which is what Anthropic
     * and Gemini models require through OpenRouter to reuse the prefix ending there.
     *
     * <p>Providers that cache implicitly — OpenAI, DeepSeek and others — ignore the marker
     * and cache on prefix stability alone. That is why the marker is additive: emitting it
     * never costs anything on a provider that does not read it, and the message order does
     * the work in both cases.
     *
     * <p>{@code usage.include} is requested so the response reports how much of the prompt
     * was actually reused. Without it there is no way to tell caching from wishful thinking.
     */
    static String chatRequestBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());

        JsonArray messages = new JsonArray();
        for (PromptMessage message : request.messages()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.role().wireName());
            if (message.cacheBreakpoint()) {
                entry.add("content", cacheableContent(message.text()));
            } else {
                entry.addProperty("content", message.text());
            }
            messages.add(entry);
        }
        body.add("messages", messages);

        JsonObject usage = new JsonObject();
        usage.addProperty("include", true);
        body.add("usage", usage);

        return body.toString();
    }

    /** A one-part content array carrying an ephemeral cache breakpoint. */
    private static JsonArray cacheableContent(String text) {
        JsonObject cacheControl = new JsonObject();
        cacheControl.addProperty("type", "ephemeral");

        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        part.add("cache_control", cacheControl);

        JsonArray content = new JsonArray();
        content.add(part);
        return content;
    }

    /** Builds the body of an embeddings request. */
    static String embeddingRequestBody(String model, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        return body.toString();
    }

    /** Reads a chat completion response. */
    static ChatCompletion readChatCompletion(String body) {
        JsonObject root = parseObject(body);

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelException(ModelException.Kind.MALFORMED_RESPONSE,
                    "The provider returned no choices");
        }

        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null) {
            throw new ModelException(ModelException.Kind.MALFORMED_RESPONSE,
                    "The provider returned a choice with no message");
        }

        String text = optionalString(message, "content");
        if (text == null) {
            // A refusal, or a tool call this mod does not request. Either way there is
            // nothing to show the player, and saying so beats rendering "null".
            throw new ModelException(ModelException.Kind.MALFORMED_RESPONSE,
                    "The provider returned a message with no content");
        }

        String servedModel = optionalString(root, "model");
        return new ChatCompletion(text, servedModel == null ? "" : servedModel, readUsage(root));
    }

    /** Reads an embeddings response. */
    static float[] readEmbedding(String body) {
        JsonObject root = parseObject(body);

        JsonArray data = root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelException(ModelException.Kind.MALFORMED_RESPONSE,
                    "The provider returned no embedding data");
        }

        JsonArray vector = data.get(0).getAsJsonObject().getAsJsonArray("embedding");
        if (vector == null || vector.isEmpty()) {
            throw new ModelException(ModelException.Kind.MALFORMED_RESPONSE,
                    "The provider returned an empty embedding");
        }

        float[] values = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            values[i] = vector.get(i).getAsFloat();
        }
        return values;
    }

    /**
     * Pulls the provider's own error message out of a failure body.
     *
     * <p>Preferred over anything this mod could invent: when a player picks a model
     * OpenRouter does not serve, OpenRouter's wording is the useful one.
     */
    static String readErrorMessage(String body, int status) {
        try {
            JsonObject root = parseObject(body);
            JsonObject error = root.getAsJsonObject("error");
            if (error != null) {
                String message = optionalString(error, "message");
                if (message != null && !message.isBlank()) {
                    return message;
                }
            }
        } catch (RuntimeException ignored) {
            // A proxy or gateway may answer with HTML. Fall through to the status.
        }
        return "The provider returned HTTP " + status;
    }

    static TokenUsage readUsage(JsonObject root) {
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage == null) {
            return TokenUsage.UNKNOWN;
        }
        return new TokenUsage(
                optionalInt(usage, "prompt_tokens"),
                optionalInt(usage, "completion_tokens"),
                readCachedTokens(usage));
    }

    /**
     * How many prompt tokens the provider served from its cache.
     *
     * <p>OpenRouter normalises this into {@code prompt_tokens_details.cached_tokens}, but
     * not every upstream provider fills it in, and some report it at the top level instead.
     * Both are read, because a zero here is indistinguishable from "caching is broken" and
     * that is exactly the thing this number exists to answer.
     */
    private static int readCachedTokens(JsonObject usage) {
        JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
        if (details != null) {
            int cached = optionalInt(details, "cached_tokens");
            if (cached > 0) {
                return cached;
            }
        }
        return optionalInt(usage, "cached_tokens");
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new ModelException(ModelException.Kind.MALFORMED_RESPONSE,
                        "The provider returned a response that is not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new ModelException(ModelException.Kind.MALFORMED_RESPONSE,
                    "The provider returned a response that could not be parsed", e);
        }
    }

    private static String optionalString(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static int optionalInt(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }
}
