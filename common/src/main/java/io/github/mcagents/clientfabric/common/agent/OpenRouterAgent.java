package io.github.mcagents.clientfabric.common.agent;

import io.github.mcagents.clientfabric.api.agent.Agent;
import io.github.mcagents.clientfabric.api.agent.ChatCompletion;
import io.github.mcagents.clientfabric.api.agent.ChatRequest;
import io.github.mcagents.clientfabric.api.agent.EnvironmentSource;
import io.github.mcagents.clientfabric.api.agent.ModelClient;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.api.agent.PersonaStore;
import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import io.github.mcagents.clientfabric.api.agent.ToolBroker;
import io.github.mcagents.clientfabric.api.agent.ToolCall;
import io.github.mcagents.clientfabric.api.agent.ToolOutcome;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.ModelPreferences;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
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

    /**
     * How many times the model may ask for tools before the loop stops.
     *
     * <p>A bound, not a budget: a model that keeps calling tools without converging would
     * otherwise spend the player's credit indefinitely, on a question they are still
     * waiting for. Eight is far more than any answer has needed and low enough that the
     * runaway case is bounded.
     */
    private static final int MAX_TOOL_ROUNDS = 8;

    private final LocalDatabase database;
    private final ModelClient client;
    private final KnowledgeRetriever retriever;
    private final PersonaStore persona;
    private final EnvironmentSource environment;
    private final ToolBroker tools;

    /**
     * @param database the player's local store
     * @param client   the model client
     * @param persona  the player's {@code AGENTS.md}; the store creates it if it is missing
     */
    public OpenRouterAgent(LocalDatabase database, ModelClient client, PersonaStore persona) {
        this(database, client, persona, EnvironmentSource.unknown(), ToolBroker.none());
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
        this(database, client, persona, environment, ToolBroker.none());
    }

    /**
     * @param database    the player's local store
     * @param client      the model client
     * @param persona     the player's {@code AGENTS.md}
     * @param environment where the player is playing, asked at request time
     * @param tools       what the model may call; {@link ToolBroker#none()} for a mod with
     *                    no MCP servers, which is the usual case and changes nothing about
     *                    how a question is answered
     */
    public OpenRouterAgent(LocalDatabase database, ModelClient client, PersonaStore persona,
                           EnvironmentSource environment, ToolBroker tools) {
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

        // Both are started before either is waited on: retrieval is a provider round trip
        // and the persona is a disk read, and there is no reason for the second to begin
        // only once the first has finished.
        CompletableFuture<String> instructions = readPersona();

        return retriever.retrieve(conversation.preferences(), question)
                .thenCombine(instructions, (knowledge, personaText) -> PromptAssembler.assemble(
                        // Read here rather than held: the player may have joined a server
                        // since this agent was built, or since their last question.
                        personaText, environment.current(), conversation.messages(), knowledge))
                .thenCompose(messages -> answer(new ChatRequest(
                        conversation.preferences().chatModel(),
                        messages,
                        // Read once, and used for every follow-up in this exchange. The set
                        // the model was shown must be the set its calls are routed against,
                        // or a server disconnecting mid-question makes the model look like
                        // it invented a tool it was genuinely offered.
                        tools.definitions())));
    }

    /**
     * Sends the request, and keeps sending it while the model asks for tools.
     *
     * <p>Each round appends two things to the conversation: the assistant turn in which the
     * model asked, verbatim and with its call ids intact, and one result per call. A
     * provider matches results to calls by id and rejects a result for a call it cannot see,
     * so neither half can be summarised or dropped.
     *
     * <p>Nothing about this recursion is unbounded. It stops when the model answers, and at
     * {@link #MAX_TOOL_ROUNDS} regardless — and the stop is itself an answer rather than a
     * failure, because a player who waited deserves what was worked out rather than an
     * error about the mechanism.
     */
    private CompletableFuture<ChatCompletion> answer(ChatRequest request) {
        return answer(request, 1);
    }

    private CompletableFuture<ChatCompletion> answer(ChatRequest request, int round) {
        return client.complete(request).thenCompose(completion -> {
            if (!completion.wantsTools()) {
                return CompletableFuture.completedFuture(completion);
            }
            if (round >= MAX_TOOL_ROUNDS) {
                LOGGER.log(Level.WARNING,
                        "Stopping after {0} rounds of tool calls; the model did not converge",
                        MAX_TOOL_ROUNDS);
                return CompletableFuture.completedFuture(stoppedShort(completion));
            }
            return runTools(completion.toolCalls())
                    .thenCompose(outcomes -> answer(
                            request.continuedWith(continued(request, completion, outcomes)),
                            round + 1));
        });
    }

    /**
     * Runs every call in the batch at once.
     *
     * <p>A model that asks for three tools has said the three are independent. Running them
     * in sequence would make the player wait for the sum of them for no reason, and the
     * results are reassembled in the order the model asked, not the order they finished.
     */
    private CompletableFuture<List<ToolOutcome>> runTools(List<ToolCall> calls) {
        List<CompletableFuture<ToolOutcome>> running = calls.stream().map(tools::invoke).toList();
        return CompletableFuture.allOf(running.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> running.stream().map(CompletableFuture::join).toList());
    }

    /** The conversation so far, plus the model's request and what the tools answered. */
    private static List<PromptMessage> continued(
            ChatRequest request, ChatCompletion completion, List<ToolOutcome> outcomes) {

        List<PromptMessage> messages = new ArrayList<>(request.messages());
        messages.add(PromptMessage.assistantToolCalls(completion.text(), completion.toolCalls()));
        for (ToolOutcome outcome : outcomes) {
            messages.add(PromptMessage.toolResult(outcome.callId(), outcome.text()));
        }
        return messages;
    }

    /**
     * What to say when the model kept calling tools and never answered.
     *
     * <p>It keeps whatever text the model had produced, because a partial answer is worth
     * more to the player than a sentence about a round limit they did not know existed.
     */
    private static ChatCompletion stoppedShort(ChatCompletion completion) {
        String text = completion.text().isBlank()
                ? "I kept needing more tools and did not reach an answer. Ask again, "
                        + "or narrow the question."
                : completion.text();
        return new ChatCompletion(text, completion.model(), completion.usage());
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
