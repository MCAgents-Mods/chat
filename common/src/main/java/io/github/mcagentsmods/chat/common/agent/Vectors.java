package io.github.mcagentsmods.chat.common.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.Optional;

/**
 * Reading stored embeddings and comparing them.
 *
 * <p>A pack's embedding arrives from the central service as a JSON array of numbers and is
 * stored exactly as published — see {@code KnowledgeContent#embeddedData()}. Nothing
 * re-encodes it, so this is the one place that knows the format.
 */
final class Vectors {

    private Vectors() {
        throw new AssertionError("No instances.");
    }

    /**
     * Parses a stored embedding.
     *
     * <p>Returns empty rather than throwing for anything unusable: a null, a blank, an empty
     * array, or text that is not a JSON array of numbers. A pack with a broken embedding is
     * a pack that cannot take part in retrieval, which is a reason to skip it and not a
     * reason to fail the question the player asked.
     */
    static Optional<float[]> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement root = JsonParser.parseString(stored);
            if (!root.isJsonArray()) {
                return Optional.empty();
            }
            JsonArray array = root.getAsJsonArray();
            if (array.isEmpty()) {
                return Optional.empty();
            }
            float[] vector = new float[array.size()];
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                    return Optional.empty();
                }
                vector[i] = element.getAsFloat();
            }
            return Optional.of(vector);
        } catch (JsonSyntaxException | NumberFormatException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    /**
     * Cosine similarity, in {@code [-1, 1]}, or {@link Double#NaN} when the two cannot be
     * compared.
     *
     * <h2>Why length is checked rather than assumed</h2>
     *
     * <p>Two vectors of different lengths came from different embedding models, and are not
     * points in the same space — the numbers would multiply happily and mean nothing. This
     * is the cheap detection for a library embedded with one model being searched with
     * another, which is exactly what happens the first time a player changes the embedding
     * model. Saying "cannot compare" lets the caller skip the pack instead of ranking it by
     * a meaningless score.
     *
     * <p>A zero vector is refused for the same reason: cosine is undefined without a
     * direction, and returning 0 would rank it as "unrelated" rather than "unknown".
     */
    static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return Double.NaN;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
