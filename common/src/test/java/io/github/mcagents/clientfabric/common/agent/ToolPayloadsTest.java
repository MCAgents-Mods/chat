package io.github.mcagents.clientfabric.common.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagents.clientfabric.api.agent.ChatCompletion;
import io.github.mcagents.clientfabric.api.agent.ChatRequest;
import io.github.mcagents.clientfabric.api.agent.ModelClient;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.api.agent.PromptMessage;
import io.github.mcagents.clientfabric.api.agent.ToolCall;
import io.github.mcagents.clientfabric.api.agent.ToolDefinition;
import io.github.mcagents.clientfabric.common.agent.openrouter.OpenRouterClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool-calling half of the OpenRouter wire format.
 *
 * <p>The request shape is the thing most likely to be wrong and the hardest to see in a
 * failure, so it is asserted as JSON rather than inferred from behaviour.
 */
class ToolPayloadsTest {

    private static ModelClient clientAnswering(StubTransport transport) {
        return new OpenRouterClient(transport, new StubKeySource("sk-test"));
    }

    private static JsonObject bodyOf(StubTransport transport) {
        return JsonParser.parseString(transport.lastCall().body()).getAsJsonObject();
    }

    private static final String ANSWER =
            "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"done\"}}]}";

    @Test
    void aRequestWithNoToolsCarriesNoToolsField() {
        StubTransport transport = new StubTransport().reply(200, ANSWER);
        clientAnswering(transport)
                .complete(new ChatRequest("m", List.of(PromptMessage.user("hi")))).join();

        assertFalse(bodyOf(transport).has("tools"),
                "a mod with no MCP servers must send exactly what it sent before");
    }

    @Test
    void toolsAreSentAsFunctionsWithTheirSchema() {
        StubTransport transport = new StubTransport().reply(200, ANSWER);
        clientAnswering(transport).complete(new ChatRequest("m",
                List.of(PromptMessage.user("hi")),
                List.of(new ToolDefinition("weather__forecast", "Weather",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"))))
                .join();

        JsonArray tools = bodyOf(transport).getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject tool = tools.get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());

        JsonObject function = tool.getAsJsonObject("function");
        assertEquals("weather__forecast", function.get("name").getAsString());
        assertEquals("Weather", function.get("description").getAsString());
        assertTrue(function.getAsJsonObject("parameters").has("properties"),
                "the schema must be an object, not a string");
    }

    @Test
    void aMalformedSchemaDoesNotMakeTheRequestUnsendable() {
        // One server's bad schema would otherwise silence every other server's tools.
        StubTransport transport = new StubTransport().reply(200, ANSWER);
        clientAnswering(transport).complete(new ChatRequest("m",
                List.of(PromptMessage.user("hi")),
                List.of(new ToolDefinition("a__b", "d", "not a schema at all"))))
                .join();

        JsonObject parameters = bodyOf(transport).getAsJsonArray("tools").get(0)
                .getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        assertEquals("object", parameters.get("type").getAsString());
    }

    @Test
    void anAssistantTurnEchoesItsToolCallsWithTheirIds() {
        StubTransport transport = new StubTransport().reply(200, ANSWER);
        clientAnswering(transport).complete(new ChatRequest("m", List.of(
                PromptMessage.user("hi"),
                PromptMessage.assistantToolCalls("",
                        List.of(new ToolCall("call_abc", "a__b", "{\"x\":1}"))),
                PromptMessage.toolResult("call_abc", "the result"))))
                .join();

        JsonArray messages = bodyOf(transport).getAsJsonArray("messages");
        JsonObject assistant = messages.get(1).getAsJsonObject();
        JsonObject call = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();

        assertEquals("call_abc", call.get("id").getAsString());
        assertEquals("function", call.get("type").getAsString());
        assertEquals("a__b", call.getAsJsonObject("function").get("name").getAsString());
        assertEquals("{\"x\":1}",
                call.getAsJsonObject("function").get("arguments").getAsString(),
                "arguments travel as a JSON string, which is what the API expects");

        JsonObject result = messages.get(2).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("call_abc", result.get("tool_call_id").getAsString());
        assertEquals("the result", result.get("content").getAsString());
    }

    @Test
    void readsToolCallsOutOfAResponse() {
        StubTransport transport = new StubTransport().reply(200, """
                {"model":"m","choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"c1","type":"function","function":{"name":"a__b","arguments":"{\\"x\\":1}"}}
                ]}}]}
                """);

        ChatCompletion completion = clientAnswering(transport)
                .complete(new ChatRequest("m", List.of(PromptMessage.user("hi")))).join();

        assertTrue(completion.wantsTools());
        assertEquals(1, completion.toolCalls().size());
        assertEquals("c1", completion.toolCalls().getFirst().id());
        assertEquals("a__b", completion.toolCalls().getFirst().name());
        assertEquals("{\"x\":1}", completion.toolCalls().getFirst().arguments());
        assertEquals("", completion.text(), "no content with tool calls is normal, not an error");
    }

    @Test
    void aCallWithNoIdIsSkippedRatherThanFailingTheBatch() {
        // A result can only be matched to a call by id, so a call without one can never
        // be answered. Dropping it lets the rest of the batch run.
        StubTransport transport = new StubTransport().reply(200, """
                {"model":"m","choices":[{"message":{"tool_calls":[
                  {"type":"function","function":{"name":"a__b","arguments":"{}"}},
                  {"id":"c2","type":"function","function":{"name":"c__d","arguments":"{}"}}
                ]}}]}
                """);

        ChatCompletion completion = clientAnswering(transport)
                .complete(new ChatRequest("m", List.of(PromptMessage.user("hi")))).join();

        assertEquals(1, completion.toolCalls().size());
        assertEquals("c2", completion.toolCalls().getFirst().id());
    }

    @Test
    void aMessageWithNeitherContentNorCallsIsStillMalformed() {
        StubTransport transport = new StubTransport()
                .reply(200, "{\"model\":\"m\",\"choices\":[{\"message\":{}}]}");

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> clientAnswering(transport)
                        .complete(new ChatRequest("m", List.of(PromptMessage.user("hi")))).join());

        assertTrue(thrown.getCause() instanceof ModelException);
    }

    @Test
    void aPlainAnswerStillReportsNoToolCalls() {
        StubTransport transport = new StubTransport().reply(200, ANSWER);

        ChatCompletion completion = clientAnswering(transport)
                .complete(new ChatRequest("m", List.of(PromptMessage.user("hi")))).join();

        assertFalse(completion.wantsTools());
        assertEquals("done", completion.text());
    }

    @Test
    void aCacheBreakpointStillWorksAlongsideTools() {
        StubTransport transport = new StubTransport().reply(200, ANSWER);
        clientAnswering(transport).complete(new ChatRequest("m",
                List.of(PromptMessage.system("stable").asCacheBreakpoint(),
                        PromptMessage.user("hi")),
                List.of(new ToolDefinition("a__b", "d", ""))))
                .join();

        JsonObject first = bodyOf(transport).getAsJsonArray("messages").get(0).getAsJsonObject();
        assertTrue(first.get("content").isJsonArray(), "the breakpoint must survive");
        assertTrue(bodyOf(transport).has("tools"));
    }
}
