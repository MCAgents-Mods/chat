package io.github.mcagentsmods.chat.api.model;

import io.github.mcagentsmods.core.api.model.ModelChoice;
import java.util.Optional;

/**
 * The models one conversation chose for itself: a chat model and an embedding model, each with
 * the provider it is called through, either of them absent.
 *
 * <p>An absent choice is not an empty one: it means "use the global model", the one the Models
 * page sets for every task without a model of its own. It is resolved per question with
 * {@link #applyTo(ModelPreferences)}, by core's one fallback rule,
 * {@link ModelChoice#orElse(ModelChoice, ModelChoice)}, so a conversation left on the global
 * model follows a change on the Models page at once, and one that chose its own keeps it.
 *
 * @param chat      the conversation's chat model, or {@code null} for the global one
 * @param embedding the conversation's embedding model, or {@code null} for the global one
 */
public record SessionModels(ModelChoice chat, ModelChoice embedding) {

    /** A conversation that chose nothing: both models are the global ones. */
    public static final SessionModels GLOBAL = new SessionModels(null, null);

    /** Whether this conversation chose neither model. */
    public boolean isGlobal() {
        return chat == null && embedding == null;
    }

    /** The conversation's own chat model, if it chose one. */
    public Optional<ModelChoice> ownChat() {
        return Optional.ofNullable(chat);
    }

    /** The conversation's own embedding model, if it chose one. */
    public Optional<ModelChoice> ownEmbedding() {
        return Optional.ofNullable(embedding);
    }

    /**
     * The models a question in this conversation is answered and embedded with: its own where it
     * chose one, the global ones where it did not. The embedding switch is always the global
     * one; a conversation chooses a model, not whether to retrieve.
     *
     * @param global the global preferences, read for this question
     */
    public ModelPreferences applyTo(ModelPreferences global) {
        java.util.Objects.requireNonNull(global, "global");
        if (isGlobal()) {
            return global;
        }
        return ModelPreferences.of(
                ModelChoice.orElse(chat, global.chat()),
                ModelChoice.orElse(embedding, global.embedding()),
                global.embeddingEnabled());
    }

    /** These choices with another chat model; {@code null} goes back to the global one. */
    public SessionModels withChat(ModelChoice chat) {
        return new SessionModels(chat, embedding);
    }

    /** These choices with another embedding model; {@code null} goes back to the global one. */
    public SessionModels withEmbedding(ModelChoice embedding) {
        return new SessionModels(chat, embedding);
    }
}
