---
name: memory-state-repository-state
description: Current state of MCAgentsClient — what is built, what is not, and the next step. Overwritten in place, always current.
---

# Repository State

**Version:** 0.14.0 · **Default branch:** `master` · **Mode:** B (consumer)

This file is **state, not history**. It is overwritten, never appended to. What happened and
why belongs in `.agents/memory/tasks/` and in `wiki/logs/`.

## What works, confirmed in a real client

Run on Minecraft 26.2 against a live multiplayer server, and observed working:

* **The chat hologram**, opened with `G` and then **CHAT** on the MCAgents menu, which agents
  0.2.0 draws with core 0.6.0's holograms. Run in a Fabric dev client: CHAT replaced the menu
  with Sessions, Conversation and User Prompt; a question typed into the prompt box started a
  conversation and, with no key, showed its reason as the agent's line; NEW, RENAME and DELETE
  worked by click; KEYS, MODEL, PERSONA and WALLETS opened their screens (holograms since
  0.11.0). In 0.5.0, on core
  0.7.0 and agents 0.3.0, the menu buttons and CHAT's hologram were seen again in Fabric and
  NeoForge dev clients on both Minecraft 26.2 and 26.3, and then in Forge dev clients on both,
  where the built Forge jars also loaded SQLite from their Jar-in-Jar. See
  [`../tasks/chat-hologram.md`](../tasks/chat-hologram.md),
  [`../tasks/minecraft-26-3.md`](../tasks/minecraft-26-3.md) and
  [`../tasks/forge-platform.md`](../tasks/forge-platform.md).
* **Every G menu page as a hologram** (0.11.0, core 0.12.0), in a Fabric 26.3 dev client:
  KEYS (a key typed as stars, stored, listed masked, the box emptied; a platform picked from
  its arrow filled its known address), MODEL (a provider picked from its arrow rewrote the
  chat model, the Embedding switch flipped, SAVE said Saved), PERSONA (typed into and saved to
  `AGENTS.md`), WALLETS (a wallet made, renamed in a dialog hologram, an Ethereum address
  added with its format checked as typed, copied), and CONNECT, whose bridge answered on
  loopback and stopped when CANCEL closed the dialog. NeoForge and Forge 26.3 opened the same
  pages. See [`../tasks/hologram-pages.md`](../tasks/hologram-pages.md).
* **Chosen providers and custom providers** (0.12.0, core 0.13.0), in 26.3 dev clients: on
  Fabric a custom provider `homelab` at `http://127.0.0.1:8085/v1` was added with a key, picked
  and saved, and a question reached a local stub there as `llama3` with that key; on NeoForge
  the saved choice came back after a restart, `deepseek` picked from the arrow showed
  "api.deepseek.com · no key yet" and a question failed naming deepseek rather than going
  through OpenRouter; on Forge REMOVE dropped `homelab` from the file. See
  [`../tasks/model-providers.md`](../tasks/model-providers.md).
* **Per-chat models** (0.13.0, core 0.13.0), in 26.3 dev clients against a loopback stub
  provider: on Fabric a chat with its own chat and embedding models was answered and embedded
  with them while another chat used the global ones, and the choice was on its row; on NeoForge
  an own chat model with the global embedding, the amber no-key warning, and USE GLOBAL putting
  the chat back on the global model; on Forge an own embedding model, a blank model refused, and
  DELETE closing the Chat Model page. See [`../tasks/per-chat-models.md`](../tasks/per-chat-models.md).
* **A full exchange.** The agent answers. OpenRouter is reached with the player's key and
  the reply is stored and rendered.
* **Storage.** Up to 0.13.2, `sqlite-jdbc` loaded from the Jar-in-Jar nested jar on Windows
  and the database opened at `{minecraft_dir}/MCAgents/data.db`. Since 0.14.0 no jar carries
  it: in a game without it (core's dev client with chat's jar on Fabric, NeoForge and Forge
  26.3) the game does not crash, and CHAT says "SQLite is not installed, so chat cannot save
  anything."; a plain sqlite-jdbc jar in `mods/` is skipped on Fabric and NeoForge. Chat's own
  dev clients still open the database. See [`../tasks/no-bundled-sqlite.md`](../tasks/no-bundled-sqlite.md).

## What is built but unproven in a real client

* Renaming a conversation, and deleting one behind its confirmation.
* **The wallet handshake.** Covered by tests in `common/`, and the Wallets hologram has been
  used in a dev client, but **no signature from the real MetaMask extension has ever been
  checked** — the tests sign with their own secp256k1
  signer. The multi-address handshake makes that gap sharper: nothing has confirmed how many
  accounts a real extension shares by default, only that the mod binds however many it is
  given. See
  `.agents/memory/tasks/wallet-binding.md` and
  `.agents/memory/tasks/wallet-accounts.md`.
* **The persona file and the server context.** `AGENTS.md` is written on first run, edited
  from the PERSONA hologram in game, and reloaded
  into the one shared `FilePersonaStore` as it is saved. The agent is told where the player
  is playing — a server address, or exactly `Player is playing in single player.` — through
  `EnvironmentSource`, recorded by `GameEnvironment` from the Fabric connection events. **No
  join event has ever fired.** See `.agents/memory/tasks/server-context-and-persona.md`.
* **The whole MCP client.** The mod can clone external MCP servers into
  `{minecraft_dir}/MCAgents/mcps/{owner}/{repository}/`, discover and run them over stdio or
  HTTP, register their tools in core's tool registry, one category per server, and route the
  model's tool calls back to the server that owns each one. Covered by tests in `common/`, seventeen of which drive a
  **real child process** — but **no game has ever started the host**, **no real
  third-party MCP server has been connected**, and **no real model has been driven through
  the tool loop**: every completion in those tests is scripted. See
  `.agents/memory/tasks/mcp-host.md` and `.agents/memory/tasks/mcp-routing.md`.
* Key rotation, retry with backoff, and the failure messages for each `ModelException` kind
  — all covered by tests against a stub transport, none seen against real OpenRouter.
* **Platform-aware, encrypted token storage.** `api_keys` carries the platform each key opens
  and `key_value` holds an AES-GCM envelope when
  `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` is set. Proven by reading the database file with
  its own connection: no token is legible in it, the same token encrypts differently on two
  writes, keys already in the clear are encrypted at the next open, and a wrong key hides rows
  without deleting them. **No real player's database has been migrated**.
* **Chosen providers** (0.12.0, core 0.13.0). Each model is called through the provider picked
  beside it on the Models hologram, kept in core's global model
  (`MCAgents/agents/models.properties`), at that provider's address, a custom provider's first.
  The address, the bare model id, the dropped gateway extensions and the per-provider key are
  asserted against a stub transport, and the one-time move of 0.11.0's settings by
  `LegacyModelSettings` is tested; **no request has ever reached a platform's real API**, and
  the six shipped addresses have not been called.
* **Per-chat retrieval in another model.** A conversation whose embedding model is not the
  library's re-embeds the packs in its own model; proven against a recording client only.
* Prompt caching. The breakpoints and the request JSON are verified; **no `cached_tokens`
  figure has ever been read back**, so whether the provider actually reuses the prefix is
  still unknown. `usage.include` is requested so the first person to look will see it.

## What does not exist

* Streaming. An answer arrives whole — including a tool exchange, so a question needing
  several rounds looks to the player like one long wait.
* Any per-tool consent prompt. A tool runs because the model asked for it; the trust
  boundary is the player's decision to clone that server.
* Embedding and retrieval. The agent answers from the conversation only; downloaded
  Knowledge Packs are stored but never consulted.
* Any source of Knowledge Packs. The central service client was removed, so the library is
  empty unless something else puts a pack in it.
* Any page that lists stored Knowledge Packs: the web console's went in 0.4.0.
* Session search: it went with the chat screen in 0.4.0.
* Anything that tells a new player the key is `G`.
* Any CI that builds a pull request. `.github/workflows/publish.yml` runs only on `master`
  and by hand, and publishes only a version that has no tag yet; `.github/workflows/release.yml`
  runs after it and only attaches the Fabric, NeoForge and Forge jars to that tag's GitHub
  release.

## Architecture as built

* `api/` contracts, `common/` implementations, `platforms/shared/` the mod on vanilla Minecraft, `platforms/fabric/`,
  `platforms/neoforge/` and `platforms/forge/` the jars. One-way dependency chain; no Minecraft in `api/` or `common/`.
* Built on MCAgents core alone, which sends the requests, owns tool calling and the `G` menu, and
  is not bundled. Tool calling was the separate agents mod up to chat 0.8.0. See
  [`../tasks/agents-adoption.md`](../tasks/agents-adoption.md) and
  [`../tasks/core-agents.md`](../tasks/core-agents.md).
* Every I/O method returns `CompletableFuture`. 416 tests in `common/`.
* The chat hologram: in `common.ui`, `ChatSessions` holds the store, the agent and the list of
  conversations, and each `ChatConversation` one conversation's messages, the question waiting
  in it and why the last was not answered; both hand results to the client thread through an
  executor. In `platforms/shared/`, `ChatSessionsHologram` is the Chat Sessions pick list CHAT
  opens, and `ChatHologram` one two-panel hologram per conversation, several at once.
  MODEL under a conversation opens `ChatModelHologram`, a form for that conversation's own
  chat and embedding models; `ChatConversation.chooseModels` keeps them, a new conversation's
  until its first question stores it.
* The persona: `PersonaStore` in `api/`, `FilePersonaStore` in `common/`. **One store per
  game session**, held by `AgentFactory` and handed to every page, so a save is the persona
  the next question uses. It generates the file when it is missing
  and never rewrites it after that, and its cache re-reads whenever the file's timestamp or
  size has moved — which is what keeps a text editor working without a disk read per turn.
* The environment: `PlayerEnvironment` and `EnvironmentSource` in `api/`, recorded by
  `GameEnvironment` on join and cleared on disconnect. `PromptAssembler` puts it in its own
  system message **after** the persona, with no breakpoint of its own: the persona is the
  player's file and the cached prefix, and changing servers must not invalidate it. Not
  knowing where the player is adds nothing to the prompt at all.
* Storage: SQLite, one connection on one daemon thread, `SQLiteDataSource` rather than
  `DriverManager`. `CachingContentRepository` wraps it.
* Model access: core's `OpenRouterClient` behind core's `HttpTransport`, built by
  `ChatModelClients` and wrapped by `CachingModelClient` (embeddings cached, completions
  deliberately not). Chat compiles against MCAgents core `0.11.0` and ships none of it; the
  mod depends on `mcagents_core`. See [`../tasks/core-adoption.md`](../tasks/core-adoption.md).
* Knowledge: stored as text with no vector. `EmbeddingBackfill` gives stored packs a vector
  using the player's own embedding model, because a vector is only comparable inside the
  space of the model that made it. Nothing lists, shows or deletes stored packs since the
  console went in 0.4.0, and there is no catalog, download or update path.
* Retrieval: `KnowledgeRetriever` embeds the newest question, ranks stored packs by
  cosine similarity (embedding the packs with the question's model when a conversation's own
  embedding model is not the one the library was embedded with), and `PromptAssembler` places the best few after the last cache
  breakpoint and before the question. Governed by one setting,
  `model.embedding.enabled`, carried on `ModelPreferences` and set on the in-game
  Models hologram. Off does no work at all rather than filtering afterwards, and
  a failed embedding call yields an ordinary answer rather than an error.
* MCP: the mod is an MCP **client**. `api.mcp` holds the contracts; `common.mcp` holds
  `McpDiscovery` (reads files, starts nothing), `StdioMcpSession`, `HttpMcpSession`,
  `WatchingMcpHost` (connects, watches the directory, reloads as a diff so an unchanged
  server keeps its process), `GitRepositoryCloner`, and `McpTools`, which registers each
  server's tools in core's tool registry under a category named after the server and follows
  the host. `OpenRouterAgent` answers through core's agent; rounds of tool calls
  are the player's agents setting, 1 by default.
  `McpHostFactory` owns one host per game session on daemon threads. Tools are exposed as
  `{server}__{tool}` capped at 64 characters, because a provider rejects the whole request
  over one bad function name. **Nothing about MCP may fail the mod** — a server that will
  not start is a status row. `mcp_clone_repository` is built in rather than served, because
  a player with no servers cannot be handed a tool by one; it is an agents tool in the `mcp`
  category, run alone.
* Reading: long lists come back a window at a time through `Page` and `PageRequest`, with
  the total counted rather than inferred. Any ordering a window is taken from is total, or
  the reader sees rows twice and never sees others.
* Wallets: `player_wallets` and `wallet_platform_addresses` in the same store. One handshake
  proves **every** address the browser shares, each over its own message and single-use
  nonce, and each becomes a wallet of its own. `LocalWalletBridge` serves one page on
  `127.0.0.1` and stops when the page says it has finished.
  `AccountWallets` is the only way into the store, and it checks that a wallet belongs to
  the account acting on it — the boundary is in the mod, never in the interface. Keccak-256
  and secp256k1 recovery are written out in `common.wallet` — the JDK's SHA3-256 is a
  different function and would recover the wrong address.

## Stack

Minecraft 26.3 only, the latest release · Fabric Loader 0.19.5 · Fabric API 0.162.0+26.3 ·
NeoForge 26.3.0.76 · Forge 26.3-66.0.11 · Fabric Loom 1.18.4 · ModDevGradle 2.0.148 · ForgeGradle 7.0.40 ·
Java 25 · Gradle 9.7.1 via the committed wrapper · group `io.github.mcagentsmods` · base package
`io.github.mcagentsmods.chat`.

Facts that cost time to establish, and will again if forgotten:

* Minecraft 26.x has **no Yarn mappings and no intermediary**, and **no `remapJar` runs** —
  `jar` produces the final artifact.
* No configuration bundles anything in Loom; only `include` does. Since 0.13.1 the loader
  modules declare chat's own modules `implementation` and nothing else is: Fabric Loader,
  Fabric API and Forge are `compileOnly` with a `runtimeOnly` for the dev client, and core is
  compile-only wherever it is declared. Gson is `compileOnly` because Minecraft provides it. Since 0.14.0 no jar bundles
  anything third-party: `sqlite-jdbc` is `compileOnly`, with the dev client's `runtimeOnly`. Fabric has no
  equivalent of Spigot's runtime Maven `libraries:`.
* The 26.x client GUI API is reworked — see `.agents/wiki/context/repository-map.md`.
* `gradle.properties` holds project identity only; every coordinate lives in
  `gradle/libs.versions.toml`.

## Shared instruction set

Resolved through the `lxagents-agents-base` MCP connector. Nothing from it is copied here;
the override table in `.agents/index/root-index.md` is empty.

## The network

The mod makes two kinds of call: to the model provider the player configured, and to a git
host when it is asked to clone an MCP server. It has no server of its own. The client for the
central service at `mcagents.mcrealms.space` was removed when its repository was deleted —
see [`../tasks/central-service-removal.md`](../tasks/central-service-removal.md).

## Next step

**Play-test in a real client, and publish real embeddings.**

Two things are outstanding, and the second is the more interesting.

1. The join-event detection behind the server context and **the whole MCP host and tool
   loop** are compile-verified and unopened; the pages are holograms now and were opened in
   a dev client for 0.11.0. `platforms/fabric` has no test source set, so nothing here
   can prove any of them. One session in a real client would settle them all, plus a binding
   through the real MetaMask extension. The cheapest check in that session: watch `logs/latest.log` for the `Player environment is now …` line when joining a world,
   a server, and a realm. A second is now worth as much: ask the agent to clone a real MCP
   server and then ask it something that needs one of that server's tools — it is the only
   way to learn what a real model does with these tool descriptions, and what a real
   third-party server does that the fake one does not. Since 0.3.0 it also shows whether a
   real model picks the right server and tool in core's agent's first two steps.
2. **No pack has been embedded against a real provider.** The harness carries fake keys, so
   the failure path is exercised and the success path is not. One press with a real key
   would settle it — and would be the first end-to-end proof that a stored pack can be
   retrieved against.

Then decide how a pack reaches a machine at all. With the central service gone there is no
source, so retrieval has nothing to find until the owner chooses one.
