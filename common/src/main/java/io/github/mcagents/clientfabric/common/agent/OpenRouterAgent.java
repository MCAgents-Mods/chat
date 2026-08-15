package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.Agent;
import io.github.mcagents.clientfabric.api.agent.ChatCompletion;
import io.github.mcagents.clientfabric.api.agent.ChatRequest;
import io.github.mcagents.clientfabric.api.agent.ModelClient;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.ModelPreferences;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The agent the chat screen talks to: reads the conversation, asks the model, hands back
 * the answer.
 *
 * <p>Everything it needs is local except the one call to the provider. The persona comes
 * from a file on disk, the conversation from the local database, and the model id from the
 * player's stored preferences — so the only thing that can fail for a network reason is the
 * completion itself, and it fails with a message the player can act on.
 */
public final class OpenRouterAgent implements Agent {

    private static final Logger LOGGER = System.getLogger(OpenRouterAgent.class.getName());

    private final LocalDatabase database;
    private final ModelClient client;
    private final KnowledgeRetriever retriever;
    private final Path personaFile;

    /**
     * @param database    the player's local store
     * @param client      the model client
     * @param personaFile {@code {minecraft_dir}/MCAgents/AGENTS.md}; it need not exist
     */
    public OpenRouterAgent(LocalDatabase database, ModelClient client, Path personaFile) {
        this.database = Objects.requireNonNull(database, "database");
        this.client = Objects.requireNonNull(client, "client");
        this.personaFile = Objects.requireNonNull(personaFile, "personaFile");
        this.retriever = new KnowledgeRetriever(database, client);
    }

    @Override
    public CompletableFuture<String> respond(String sessionId, String prompt) {
        Objects.requireNonNull(sessionId, "sessionId");

        return database.settings().loadModelPreferences()
                .thenCombine(database.chat().findMessages(sessionId), Conversation::new)
                .thenCompose(this::complete)
                .thenApply(ChatCompletion::text);
    }

    private CompletableFuture<ChatCompletion> complete(Conversation conversation) {
        if (conversation.messages().isEmpty()) {
            return CompletableFuture.failedFuture(new ModelException(
                    ModelException.Kind.REQUEST,
                    "There is nothing to answer: the conversation is empty"));
        }

        // Retrieval is scored against the newest question rather than the whole
        // conversation: a thread that wandered would otherwise keep pulling packs for what
        // it used to be about.
        String question = newestQuestion(conversation.messages());

        return retriever.retrieve(conversation.preferences(), question)
                .thenCompose(knowledge -> {
                    List<PromptMessage> messages = PromptAssembler.assemble(
                            readPersona(), conversation.messages(), knowledge);
                    return client.complete(
                            new ChatRequest(conversation.preferences().chatModel(), messages));
                });
    }

    /** The text of the newest user message, or blank if the conversation has none. */
    private static String newestQuestion(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatRole.USER) {
                return message.content();
            }
        }
        return "";
    }

    /**
     * Reads the player's instructions.
     *
     * <p>A missing or unreadable file is not an error — most players will never create one
     * — so the default persona is used and the mod carries on.
     */
    private String readPersona() {
        try {
            if (!Files.isRegularFile(personaFile)) {
                return "";
            }
            return Files.readString(personaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read the persona file at " + personaFile, e);
            return "";
        }
    }

    /**
     * The player's model settings and the conversation to send, gathered in one step.
     *
     * <p>Holds the whole preference record rather than just the chat model id: retrieval
     * needs the embedding model and the switch, and reading the settings twice could see
     * two different answers if the player saved between the reads.
     */
    private record Conversation(ModelPreferences preferences, List<ChatMessage> messages) {
    }
}
