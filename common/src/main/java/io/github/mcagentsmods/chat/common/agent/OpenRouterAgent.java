package io.github.mcagentsmods.chat.common.agent;

import io.github.mcagentsmods.agents.api.agent.AgentAnswer;
import io.github.mcagentsmods.agents.api.agent.AgentRequest;
import io.github.mcagentsmods.agents.common.agent.ToolAgent;
import io.github.mcagentsmods.agents.tool.ToolExecutor;
import io.github.mcagentsmods.agents.tool.ToolRegistry;
import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.agent.EnvironmentSource;
import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ModelPreferences;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.model.ModelException;
import io.github.mcagentsmods.core.api.model.PromptMessage;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The agent the chat screen talks to: reads the conversation, asks the model, hands back
 * the answer.
 *
 * <p>Everything it needs is local except the calls to the provider. The persona comes
 * from a file on disk, the conversation from the local database, and the model id from the
 * player's stored preferences — so the only thing that can fail for a network reason is the
 * completion itself, and it fails with a message the player can act on.
 *
 * <p><b>Tools are the agents mod's.</b> This assembles what chat alone knows — the persona,
 * where the player is, the earlier turns and the Knowledge Packs — and hands it to the agents
 * mod's agent, which picks the tool categories and the tools by the newest question, runs the
 * calls the model makes, and returns the answer. Chat's MCP tools reach it through the agents
 * registry, like any other mod's.
 */
public final class OpenRouterAgent implements Agent {

    private static final Logger LOGGER = System.getLogger(OpenRouterAgent.class.getName());

    private final LocalDatabase database;
    private final ModelClient client;
    private final KnowledgeRetriever retriever;
    private final PersonaStore persona;
    private final EnvironmentSource environment;
    private final io.github.mcagentsmods.agents.api.agent.Agent tools;

    /**
     * @param database the player's local store
     * @param client   the model client
     * @param persona  the player's {@code AGENTS.md}; the store creates it if it is missing
     */
    public OpenRouterAgent(LocalDatabase database, ModelClient client, PersonaStore persona) {
        this(database, client, persona, EnvironmentSource.unknown());
    }

    /**
     * @param database    the player's local store
     * @param client      the model client
     * @param persona     the player's {@code AGENTS.md}; the store creates it if it is
     *                    missing, and is shared with every screen that can edit it so a
     *                    save is in force by the next question
     * @param environment where the player is playing, asked at request time rather than at
     *                    construction time — an agent built at the main menu answers
     *                    questions asked on a server
     */
    public OpenRouterAgent(LocalDatabase database, ModelClient client, PersonaStore persona,
                           EnvironmentSource environment) {
        this(database, client, persona, environment, withoutTools(client));
    }

    /**
     * @param database    the player's local store
     * @param client      the model client
     * @param persona     the player's {@code AGENTS.md}
     * @param environment where the player is playing, asked at request time
     * @param tools       the agents mod's agent, which answers with the tools every mod
     *                    registered: {@code Agents.agent(client)} in game. With none
     *                    registered a question costs one request, as it would without it
     */
    public OpenRouterAgent(LocalDatabase database, ModelClient client, PersonaStore persona,
                           EnvironmentSource environment,
                           io.github.mcagentsmods.agents.api.agent.Agent tools) {
        this.database = Objects.requireNonNull(database, "database");
        this.client = Objects.requireNonNull(client, "client");
        this.persona = Objects.requireNonNull(persona, "persona");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.retriever = new KnowledgeRetriever(database, client);
    }

    @Override
    public CompletableFuture<String> respond(String sessionId, String prompt) {
        Objects.requireNonNull(sessionId, "sessionId");

        return database.settings().loadModelPreferences()
                .thenCombine(database.chat().findMessages(sessionId), Conversation::new)
                .thenCompose(this::complete)
                .thenApply(AgentAnswer::text);
    }

    /** An agent with an empty registry of its own, for a chat that offers no tools. */
    private static io.github.mcagentsmods.agents.api.agent.Agent withoutTools(ModelClient client) {
        return new ToolAgent(client, new ToolRegistry(), ToolExecutor.shared(), () -> 1);
    }

    private CompletableFuture<AgentAnswer> complete(Conversation conversation) {
        if (conversation.messages().isEmpty()) {
            return CompletableFuture.failedFuture(new ModelException(
                    ModelException.Kind.REQUEST,
                    "There is nothing to answer: the conversation is empty"));
        }

        // Retrieval is scored against the newest question rather than the whole
        // conversation: a thread that wandered would otherwise keep pulling packs for what
        // it used to be about.
        String question = newestQuestion(conversation.messages());

        // Both are started before either is waited on: retrieval is a provider round trip
        // and the persona is a disk read, and there is no reason for the second to begin
        // only once the first has finished.
        CompletableFuture<String> instructions = readPersona();

        return retriever.retrieve(conversation.preferences(), question)
                .thenCombine(instructions, (knowledge, personaText) -> PromptAssembler.assemble(
                        // Read here rather than held: the player may have joined a server
                        // since this agent was built, or since their last question.
                        personaText, environment.current(), conversation.messages(), knowledge))
                .thenCompose(messages -> tools.answer(AgentRequest.of(
                                conversation.preferences().chatModel(), promptOf(question, messages))
                        // The whole conversation is what the answer is written from; the
                        // newest question alone is what the categories and tools are picked by.
                        .withConversation(messages)));
    }

    /**
     * What the agent picks its tools by: the newest question, or, in a conversation that has
     * none, the newest message the model is shown.
     */
    private static String promptOf(String question, List<PromptMessage> messages) {
        if (!question.isBlank()) {
            return question;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            String text = messages.get(i).text();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return "Continue the conversation.";
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
     * Reads the player's instructions, off this thread and off the client thread.
     *
     * <p>An unreadable file is not an error worth failing a question over — a locked file,
     * a permission the launcher lost, a folder on a share that has gone away. The assembler
     * substitutes its own default persona for a blank one, so the player still gets an
     * answer and the reason is in the log.
     */
    private CompletableFuture<String> readPersona() {
        return persona.read().exceptionally(failure -> {
            LOGGER.log(Level.WARNING,
                    "Could not read the player's instructions; answering with the default persona",
                    failure);
            return "";
        });
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
