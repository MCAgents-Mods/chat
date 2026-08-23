---
name: memory-task-mcp-routing
description: Routing a model's tool call to the server that owns it — the three names that must agree, why a tool failure is never a failed question, and what is proven.
---

# MCP Routing and Execution

## 2026-08-23

**Goal.** Turn the registry from task 2 into something the model actually uses: offer the
tools, parse the calls it makes, route each to the right server, and feed the results back
until it answers. Task 3 of three.

**Branch.** `feat/mcp-routing`, stacked on `feat/mcp-host`.

## Decisions not to relitigate

**`ToolBroker` is the seam, and it lives in `api.agent`.** The agent loop knows only "here
is what can be offered" and "here is the result of a call". It does not know the tools come
from MCP servers, which is why the loop is testable with no server running and why
`common.agent` does not depend on `common.mcp`.

**Three names must agree, and they are edited apart.** What `McpDiscovery` calls a server,
what `McpContextRegistry` qualifies its tools as, and what `McpToolRouter` strips before
calling. `McpRoutingEndToEndTest.theNameTheModelIsShownIsTheNameThatRoutes` exists solely to
pin that, against a real server process.

**A tool call never fails a question.** Unknown tool, unparseable arguments, disconnected
server, server threw — all come back as a `ToolOutcome` with `isError`, carrying text the
model reads. The unknown-tool outcome **lists the real names**, so the model corrects itself
instead of guessing again. Failing the request would turn a recoverable mistake into a dead
question the player has to re-ask.

**Built-ins are checked before MCP tools**, so a server cannot shadow one.
`mcp_clone_repository` must be built in: it is how a player with no servers gets their
first, and a tool served by a server they do not have yet is a loop with no entry. It takes
a URL and nothing else — no destination parameter, so no argument a model writes decides
where files land.

**The assistant turn is echoed back verbatim, ids intact.** The provider matches results to
calls by id and rejects a result for a call it cannot see, so neither the call nor the
results may be summarised, reordered, or dropped.

**Calls in one turn run concurrently.** A model asking for three tools has said they are
independent; sequencing them makes the player wait for the sum. Results are reassembled in
the order the model asked, not the order they finished.

**The loop is bounded at eight rounds, and the stop is an answer.** It keeps whatever text
the model produced — a player who waited deserves the partial answer over an error about a
limit they did not know existed.

**Tool messages are never stored.** `ChatRole.TOOL` is a wire role only. A conversation
replayed from `chat_messages` must read as the conversation the player had, not as the
working-out.

**`tools` is stable across a follow-up.** A round of tool calls re-sends the whole
conversation, so a `tools` array that changed between requests would break the cached prefix
the breakpoints exist to reuse. `ChatRequest.continuedWith` keeps model and tools fixed.

## Things learned the hard way

**A response with `tool_calls` and no content was being rejected as malformed.** The
existing reader threw on a null `content`, with a comment saying "a tool call this mod does
not request" — accurate when written, and exactly the line that had to change. Only a
message with *neither* content nor calls is malformed now.

**One bad tool name silences every tool.** A provider rejects the *whole request* over a
function name outside `[A-Za-z0-9_-]{1,64}`, naming none of the others. Hence filtering in
`McpToolRouter.definitions()` before `ToolDefinition` is constructed — the constructor
throws, which inside `definitions()` would break the entire request rather than one tool.
Same reasoning for a malformed schema, which is replaced with an empty object schema.

**Adding `ChatRole.TOOL` broke two switch expressions in `ConversationPanel`, on purpose.**
They are handled explicitly rather than given a `default`, so the next role added is a
compile error again rather than a silently mis-coloured row.

**Two tests were wrong before the code was.** One subclassed a record; one wrote a catalog
entry identical to the wiring it claimed to change. Both were rewritten to assert the
property actually at stake.

## What is proven, and what is not

**Proven, by 441 tests in `common/`** (40 new here): a call routed to a real server process
and back; the offered name being the routable name; two servers routing independently; a
server stopped after its tools were offered failing readably; the full request and response
JSON for tools, `tool_calls` and `tool_call_id`; multi-round exchanges; concurrent calls in
one turn keeping the model's order; the round limit; and tool rows never reaching the
database.

**Not proven.** Still nothing has run inside Minecraft — see
`.agents/memory/tasks/gui-play-testing.md`, which is this repository's own record of a green
Java suite meaning nothing about the mod loading. **No real model has been driven through
this loop**: every completion in the tests is scripted, so what a real model does with these
tool descriptions is unknown. No real third-party MCP server has been connected either.

**Deliberately absent.** No streaming of tool calls, so the player sees nothing until the
whole exchange finishes — a question needing several rounds looks like a long wait. No
per-tool consent prompt: a tool runs because the model asked, and the trust boundary is the
player's decision to clone the server. Both are worth revisiting before this ships to
players.
