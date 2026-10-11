---
name: memory-task-core-adoption
status: in-progress
description: Chat adopting MCAgents-Mods/core — calling core's model client instead of its own, registering its pages into core's web console, the security review, and the 0.1.0 release.
---

# Adopting core

## 2026-10-11

**Goal.** The owner reorganised `MCAgents-Mods` so that `core` is the foundation every mod
builds on. Two of the four requests land here: chat must use core's code to talk to
OpenRouter, so a later mod does not write the client again, and chat's web console moves into
core, with chat registering its pages there so any mod can add pages to one console.

**Objective.** Chat depends on core's API and calls core's `OpenRouterClient`; chat's own
client, payloads and HTTP transport are gone. Chat's pages are a `WebModule` registered with
`CoreWebRegistry`; chat's server, token handling and page shell are gone. A security review
of the result lands its fixes, and the release is `0.1.0`. The build and tests pass on every
branch.

**Detail.** Core's half is recorded in core's own `mods-foundation.md`, tasks 5 to 7, and
merges first. The owner approved the plan for both repositories, the version `0.1.0`, and
keeping a Fabric build in core so chat has a core mod to run against.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file and its index row | `MCAgents-Mods/chat` | `chore/core-adoption-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | Core's model client | Depend on core; delete chat's client, payloads and transport; point every caller at core | `MCAgents-Mods/chat` | `feat/shared-llm-client` | `settings.gradle`, every module's build, `api/agent`, `api/model`, `common/agent`, `common/mcp`, `platforms/fabric`, docs | |
| 3 | Core's web console | Chat's pages become a `WebModule`; delete the server, the token handling and the shell | `MCAgents-Mods/chat` | `feat/web-registry` | `website/`, `platforms/fabric`, `api/web`, docs | |
| 4 | Security review | Fixes for confirmed findings | `MCAgents-Mods/chat` | `fix/security-review` | as found | |
| 5 | The release | `0.1.0`, changelog, closing this record | `MCAgents-Mods/chat` | `release/0.1.0` | `gradle.properties`, `wiki/logs/`, this file | |

## Decisions not to relitigate

**Chat compiles against core and ships none of it.** Core's API is `compileOnlyApi` and
`fabric.mod.json` depends on `mcagents_core`, so one core mod serves every MCAgents mod at
runtime. Tests put core on their own classpath.

**A local core checkout builds with chat.** `settings.gradle` includes `../core` as a
composite build when it is there, so the two are developed together. A chat release needs a
published core; publishing to GitHub Packages is outward-facing and waits for the owner.

**Keys stay in chat's encrypted SQLite store.** Core's client asks for them per request
through chat's `RepositoryApiKeySource`; core never stores them.

*(each task appends below as it lands)*

### Task 2 — feat/shared-llm-client

**Landed.** Chat calls core's client. Deleted from chat, now imported from core:

| Was chat's | Is core's |
|---|---|
| `api.agent`: `ApiKeySource`, `ChatCompletion`, `ChatRequest`, `ModelClient`, `ModelException`, `PromptMessage`, `ProviderRoute`, `ProviderRoutes`, `TokenUsage`, `ToolCall`, `ToolDefinition` | `core.api.model` |
| `api.model`: `ModelName`, `ModelPlatform` | `core.api.model` |
| `api.model.ChatRole` (`wireName`, `fromWireName`) | `core.api.chat.ChatRole` (`code`, `fromCode`) — the same four roles and strings, so stored history reads unchanged |
| `common.agent`: `HttpCall`, `HttpReply`, `HttpTransport` | `core.api.http` |
| `common.agent.JdkHttpTransport` | `core.common.http.JdkHttpTransport`, used by the MCP HTTP session too |
| `common.agent.openrouter`: `OpenRouterClient`, `OpenRouterPayloads` | `core.common.model` |
| test doubles `StubTransport`, `StubKeySource` | core's `common` test fixtures |

`ChatModelClients` is new: the one place chat's stack is built, used by the game and the
console. It sends chat's own attribution (`MCAgents-Mods/chat`, `MCAgentsClient`). The
console's fallback client used to be built without `StoredProviderRoutes`, so it ignored a
player's direct routes; building both from one place fixed that. The chat screen names the
new `CLOSED` failure.

**Build.** Core is `compileOnlyApi` in `api/` and `common/`, on the tests' classpath, and
`fabric.mod.json` depends on `mcagents_core >= 0.5.0`, expanded from the version catalog. The
built jar holds no `io/github/mcagentsmods/core` class. `settings.gradle` includes `../core`
as a composite build, substituting `core-api` and `core-common` with core's projects; core's
test fixtures resolve through the substitution with no extra wiring.

**Tests.** Chat's client, direct-route and tool-payload tests moved to core in core's task 5
and were deleted here, with the eleven `ModelNamingTest` cases that tested only `ModelName` or
`ModelPlatform`. The `ModelCatalog` and `ModelPreferences` cases stay. `./gradlew build`
passes: 381 tests in `common`, 78 in `website`.

**Not proven.** `runClient` with core's Fabric jar in `run/mods/`, and the mod loading next to
core in a real game.

### Task 3 — feat/web-registry

**Landed.** Chat's pages are `ChatWebModule`, registered with core's `CoreWebRegistry`, so
they are served by the one console every MCAgents mod shares:

| Was chat's | Now |
|---|---|
| `LocalWebConsole` — the server, the token, the `Host` check, the routing | core's `LocalWebHost`; chat's routing is `ChatWebModule.handle`, answering `/api/chat/…` |
| `WebAssets` | core's `WebResources`, reading through the module's own class loader |
| `api.web.WebConsole`, `WebConsoleException` | core's `api.web` |
| `main.css`, `shared/layout.css`, `shared/components.css`, `site.js`, the header and footer partials | core's, at `/assets/core/…` |
| the root redirect to `/knowledge/` | the module's address is `/chat/knowledge/`, its first navigation link |

The pages no longer set `window.SITE_ROOT` and `window.PAGE_SECTION` in an inline script, which
core's content security policy would not run; they name themselves on `<body>` with
`data-module="chat"` and `data-section`, and load every file by its absolute `/assets/…` path.
The pack page reads its id from `/chat/knowledge/<id>`, one segment deeper than before. The
API path is decoded once, by core, where chat's routes decoded it twice.

`WebConsoleHolder` registers the module the first time the player asks for the console, opens
core's console, and unregisters on shutdown before the database closes. The screen's **Stop**
closes core's console, so every mod's pages stop with it; chat's stay registered for the next
start.

**Tests.** The five route and page suites run through a fresh `CoreWebRegistry` with the
module registered (`ConsoleUnderTest`), so they drive chat's routes over real HTTP through
core's guard. Four tests of the server alone — every run's own token, traversal through the
asset route, staying up until closed, closing twice — are core's now and were removed; core
tests the same in `LocalWebHostTest`. `./gradlew build` passes: 381 tests in `common`, 74 in
`website`.

**Proven in a browser.** Chromium loaded all six pages from a console started by the tests'
own wiring: the header built from core's registry with the right link active on each, the
footer in place, the chat list showing a stored conversation, and no console error or failed
request other than the expected 404 for a pack that does not exist.

**Not proven.** The console opened from the game, which needs a launched client with core
installed.

### Task 4 — fix/security-review

**Landed.** Three reviews ran on chat after core was adopted: one for leaks, one for exploits,
one for duplicated code. What they confirmed and this branch fixes:

| Finding | Fix |
|---|---|
| The model could clone a repository and the mod ran its `mcp.json` command at once — code execution on the say-so of a Knowledge Pack or another tool's output | A server wired by its repository starts only once `mcp_servers.json` approves its fingerprint (`McpApproval`); a changed command needs approving again. owen chose this on the decision card |
| A clone URL kept only its first two path segments and dropped the host | Exactly `{owner}/{repository}`, no credentials, port, query or fragment; git gets a URL rebuilt from the coordinate; the outcome names the host; an existing checkout from another host is not pulled |
| A manifest could send tool calls to any host over plain http | https, or http to loopback only |
| Every child process inherited `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` | `ChildEnvironment` removes it for MCP servers and git; git also gets `GIT_TERMINAL_PROMPT=0` |
| `PUT /api/chat/routes` could move a stored key to any https host | While a key is stored, the address may change only within its host or back to the shipped one |
| A wallet nonce was not bound to its account or address and never expired | Each nonce carries both and its issue time; ten-minute lifetime |
| The wallet bridge's `Host` check accepted `localhost:anything` | The port must be the listener's |
| `WatchingMcpHost.close()` raced a reload, and a server started during it outlived the host | The flag is checked again under the sessions lock after a start; handshakes run outside the lock so close never waits on one |
| The watch service leaked when registering failed | Closed in that path; the loop takes the service as a parameter |
| An HTTP MCP request had no deadline of its own | `orTimeout` on the host's request timeout |
| git's output and a stdio server's lines were unbounded; a timed-out git was never stopped; a server's descendants outlived it | Capped reads, a drain thread so the timeout bounds the run, descendants destroyed |
| Manifests and the catalog were read whole | Skipped over 1 MB |
| The embedding cache kept whole pack text in its keys for six hours | SHA-256 keys |
| `AccountWallets.labelFor` and a second `randomHex` | `ChainAddress.masked(String)` and one `randomHex` |

**Tests.** `./gradlew build` passes: 397 tests in `common`, 76 in `website`.

**Deferred, and why.**

* `HttpMcpSession` never sends `Mcp-Session-Id`, because core's `HttpReply` carries no headers.
  The fix is a core change (headers on `HttpReply`), after 0.5.0.
* `KnowledgeRetriever` loads every pack for every question. Bounded by the player's own data;
  needs a retrieval redesign, not a security fix.
* `TokenCipher` derives its key with one unsalted SHA-256 and binds no AAD; `ApiKeyHandles` is
  an unsalted hash. Changing either needs a stored-data migration.
* The repeated screen boilerplate, the two near-identical list panels, the two MCP session
  classes' shared methods, the JSON readers and the paging helpers. Refactors with no
  behaviour change, left for their own branch.

### Task 5 — release/0.1.0

`project-version` is `0.1.0`, as approved. The changelog is `wiki/logs/0/1/0/CHANGELOG.md`,
with an Upgrading section, since every player has to install core, approve cloned MCP servers
and use the console's new addresses; its row is in `logs-index.md`. `fabric.mod.json` takes the
version from `gradle.properties`, so the jar is `mcagents-chat-0.1.0.jar`.

The PR column above fills in once the owner approves opening the pull requests; they open in
task order, each based on the branch before it.
