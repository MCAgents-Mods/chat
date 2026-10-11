---
name: openrouter-integration
description: How the mod talks to a model provider — the two routes a request can take, request shape, model selection, key rotation, retries, timeouts, and what must never be sent.
---

# Model Provider Integration

Every call is made with the player's own key, and every call is optional: a player with no
key has a working mod that cannot produce new answers, not a broken one.

## Two routes, one wire format

**OpenRouter is the default and the fallback.** A request goes through the gateway unless the
player has deliberately made a platform reachable on its own, which takes two things at once:
an address for it, and a key stored for it.

| | Sent to | `"model"` in the body | Key used |
|---|---|---|---|
| Through the gateway | `openrouter.ai/api/v1` | `openai/gpt-4o-mini` | an `openrouter` key |
| Direct | `api.openai.com/v1` | `gpt-4o-mini` | an `openai` key |

Both are built from one stored `{platform}/{model}` name — see
`{shared}/rules/model-naming-convention.md`, which is why moving a model between the routes
changes no stored data. `StoredProviderRoutes` resolves the route per request, from the same
store the keys are in, so a key added while the game is running needs no restart.

**Anything unresolved is the gateway**: an unqualified model id, a platform with no address,
a platform with no key of its own, an address that is neither https nor loopback.

`OpenRouterClient` serves both. It speaks the wire format OpenRouter defines, which is
OpenAI's — that is precisely why one client covers a gateway and a platform's own API.

**The client is core's, not chat's.** `OpenRouterClient`, `JdkHttpTransport`, and the request
types (`ChatRequest`, `PromptMessage`, `ToolDefinition`, `ModelException`, `ModelName`,
`ModelPlatform` and the rest) live in `MCAgents-Mods/core`, under
`io.github.mcagentsmods.core.api.model`, `.api.http`, `.common.model` and `.common.http`, so
every MCAgents mod calls the same code. Chat compiles against core and ships none of it; the
`mcagents_core` mod provides it in game. A change to how a request is built, retried or
rotated is made in core. What stays here is what is chat's: `ChatModelClients` builds the
one stack the game and the console share — chat's keys through `RepositoryApiKeySource`,
chat's routes through `StoredProviderRoutes`, `CachingModelClient` around it, and chat's own
attribution.

### What a direct request must not carry

* **`usage.include`** and **the `cache_control` content part**. Both are the gateway's. A
  platform's own API rejects the *whole request* over an unrecognised argument rather than
  ignoring it, and the providers reachable directly cache implicitly on prefix stability
  anyway.
* **`HTTP-Referer` and `X-Title`.** Attribution the gateway reads and nobody else does.

### Where an address comes from

`api.base-url.{platform}` in `settings` first, then the addresses `ModelPlatform` ships. A
configured address must be `https`, or loopback for a gateway the player runs themselves;
anything else is ignored rather than sent a key. Only OpenAI-compatible platforms are shipped
— Anthropic and Google are deliberately absent, because their own APIs are a different path,
header and body, and a direct call would be a request they reject.

## The seam

`AgentReply` in `platforms/fabric/.../client/gui` is what the chat screen depends on. The
model client implements it. Until one exists, `AgentReply.notConfigured()` explains itself
to the player instead of failing.

When the implementation lands it belongs in `common/`, behind a contract in `api/`, because
nothing about calling an HTTP API needs Minecraft. Move `AgentReply` to `api/` at that
point.

## Request shape

* Chat completions at `{base}/chat/completions`; embeddings at `{base}/embeddings`, where
  `{base}` is whatever the route resolved to.
* The key travels in the `Authorization: Bearer …` header, to the platform it was stored for
  and nowhere else — [`../security/api-keys.md`](../security/api-keys.md).
* **The stored conversation is the request body.** `chat_messages` holds one row per turn
  with a `role` of `user`, `assistant`, or `system`, which is exactly the array the API
  expects. Mapping is the only step; if you find yourself parsing or reassembling, the
  storage shape has been broken — see [`../database/schema.md`](../database/schema.md).
* The system message is assembled at request time from the player's
  `{minecraft_dir}/MCAgents/AGENTS.md` and the retrieved Knowledge Pack content. It is
  stored with `role = 'system'` only when it should persist in the transcript.

## Tool calls

An exchange with tools is still one request shape, extended:

* **`tools` goes after `messages` and stays identical across a follow-up.** A round of
  tool calls re-sends the whole conversation, so a `tools` array that changed between the
  first request and the second would break the cached prefix the breakpoints exist to
  reuse.
* **Every tool name must match `[A-Za-z0-9_-]{1,64}`.** The provider rejects the *entire
  request* over one bad name, naming none of the others. Filter before building the
  request; never let a bad name reach the wire — [`../mcp/host.md`](../mcp/host.md).
* **A schema is passed through as its server wrote it.** Rewriting a third-party schema is
  how a tool ends up described in a way its own server will not accept. A schema that will
  not parse is replaced with an empty object schema rather than made fatal, so one bad tool
  cannot silence the rest.
* **A response with `tool_calls` and no `content` is normal, not malformed.** The calls are
  the message. Only a message with neither is a malformed response.
* **The assistant turn is echoed back verbatim, ids included, followed by one `tool`
  message per call carrying its `tool_call_id`.** The provider matches results to calls by
  id and rejects a result for a call it cannot see, so neither half may be summarised,
  reordered, or dropped.
* **A call missing an id is skipped, not fatal.** It could never be answered — there is
  nothing to match a result to — and dropping it lets the rest of the batch run.
* **Tool messages are never stored.** They are the working-out, not the answer; a
  conversation replayed from `chat_messages` must read as the conversation the player had.
* The loop is bounded. A model that keeps calling tools without converging is stopped and
  whatever text it had produced is returned, because a player who waited deserves the
  partial answer rather than an error about a limit they did not know existed.

## Model selection

* The player chooses a chat model and an embedding model. Both are stored in `settings` and
  read at request time — never hardcoded at a call site.
* **Both are stored as `{platform}/{model}`, lowercased before the write.** `ModelName` is the
  only thing that builds or parses one; never assemble a stored id by hand, and never store
  one straight from a text box without `ModelPreferences.of` normalising it.
* An id with no platform segment is kept as the player typed it and goes through the gateway.
  Do not complete it — `openrouter/gpt-4o-mini` is not a longer spelling of `gpt-4o-mini`, it
  is a model nobody serves.
* **The suggested lists are suggestions, not a whitelist.** The interface offers them and
  also accepts anything the player types, because OpenRouter adds models faster than this
  mod ships. Never reject a model id the player entered; let the API reject it and report
  what the API said.
* A blank or missing selection falls back to the default in `ModelCatalog`. It never falls
  back to "no model".

## Failure handling

* **Every call returns `CompletableFuture`** and runs off the client thread. No exception.
* **A rejected key rotates, within its own platform.** 401 or 403 deactivates the key and
  retries with the next active key **for that platform**. If none is left, the player is told
  which platform needs a working key — not that the request failed.
* **429 and 5xx retry** with exponential backoff, at most three attempts, with jitter. 4xx
  other than 401/403/429 does not retry; the request was wrong and repeating it will not fix
  it.
* **Timeouts are mandatory** — connect and read both. A request without one can hang until
  the player closes the game.
* A failure surfaces in the interface as a message in the conversation, not as a crash and
  not as silence. The player must be able to tell "the model refused" from "nothing
  happened".

## What is never sent

* Anything the player did not ask to send. Context injection — inventory, the block being
  looked at, chat logs — is a feature the player controls, not a default.
* Another player's data.
* **An MCP server's environment.** A tool's name, description and schema are sent because
  the model must be able to call it; the credentials that server was configured with are
  not part of any of the three.

## Offline

A network failure here changes nothing about the rest of the mod. The screen opens, the
history is readable, downloaded packs are usable. Whatever calls OpenRouter must not be on
the path of any of those.
