---
name: repository-rules
description: The rules that apply only to MCAgentsClient — offline-first mandate, module boundaries, the async I/O rule, and what must never be introduced.
---

# MCAgentsClient Repository Rules

## Mode and shared set

This repository is a **Mode B consumer**. The shared instruction set is resolved through
the `lxagents-agents-base` MCP connector and is never copied here. Nothing under
`.agents/` may duplicate a file the connector serves; a local file that shadows a shared
`name` is an override and must be declared in
[`../index/root-index.md`](../index/root-index.md).

## The offline-first mandate

This is the rule that outranks every other rule in this file.

* **The mod is client-side only.** It must never require a Minecraft server plugin, a
  server-side companion mod, or any server cooperation to function.
* **The mod must start, load, and operate with no network at all.** Chat sessions already
  stored, Knowledge Packs already stored, API keys already saved, and the whole GUI
  stay usable whatever the network is doing.
* **The mod has no server of its own.** The only network calls it makes are to a model
  provider the player configured and to an MCP server it was asked to clone. No code path
  may block on either at startup or fail a local operation because one is unreachable.
* A failed network call is surfaced in the UI as a non-fatal message. It never throws out
  of a local operation and never leaves the local database in a partial state.
* Calls to the OpenRouter API are the player's own choice with the player's own key. They
  are optional in the same sense: absence of a key disables answering, never the mod.

Any change that makes the mod unusable without the network is a defect, regardless of what
else it improves.

## Module boundaries

Five Gradle modules, and the dependency direction is one-way. See
[`../../wiki/information/architecture.md`](../../wiki/information/architecture.md) for the
diagram and the reasoning.

| Module | Directory | Gradle name | May depend on |
|---|---|---|---|
| API | `api/` | `mcagents-chat-api` | The JDK and MCAgents core's API. |
| Common | `common/` | `mcagents-chat-common` | `api/`, MCAgents core, MCAgents agents, and plain JVM libraries. |
| Shared platform | `platforms/shared/` | `mcagents-chat-shared` | all of the above and vanilla Minecraft — no loader. |
| Fabric platform | `platforms/fabric/` | `mcagents-chat-fabric` | all of the above, Fabric. |
| NeoForge platform | `platforms/neoforge/` | `mcagents-chat-neoforge` | all of the above, NeoForge. |

* `api/` holds **contracts only** — interfaces, records, enums, sealed types, abstract
  classes. No implementation logic, no I/O, no third-party dependency. MCAgents core is not
  third-party: its types are the shared vocabulary every MCAgents mod speaks.
* **Core is compiled against, never bundled.** `mcagents-core-api` and `mcagents-core-common` are
  `compileOnlyApi`, `mcagents-core-client` is `compileOnly` in `platforms/shared/`, and the
  `mcagents_core` mod provides them in game. A chat jar that
  carries a core class would load a second copy beside the one every other mod uses.
* **What core already does is not written again here.** The model client, its transport, the
  request types and model naming are core's; a change to them is made in core.
* **Agents is compiled against, never bundled, and owns tool calling.** `mcagents-agents-common`
  is `compileOnlyApi` in `common/`, and the `mcagents_agents` mod provides it in game. The tool
  registry, the three-step agent, the worker limit and the menu `G` shows are agents'; chat
  registers its tools there and answers through its agent, and a change to how tools are
  picked or run is made in agents.
* `common/` holds the implementations of those contracts. It must compile and be testable
  **without Minecraft on the classpath**. If a class in `common/` needs a Minecraft type,
  the design is wrong: pass the value in through an `api/` contract instead.
* `platforms/shared/` holds everything Minecraft-specific that every loader shares — the
  client start-up, screens, the key and the factories — compiled against vanilla Minecraft.
  No loader type may appear in it.
* A loader module — `platforms/fabric/`, `platforms/neoforge/` — holds only what that loader
  needs: the entry
  point, the key and category registration, the tick and connection events, and the game
  directory it hands to `ChatRuntime.start`.
* Nothing in `api/` or `common/` imports `net.minecraft.*` or a loader's packages. Nothing in `platforms/` re-implements logic that belongs below it.

**A contract stays in `api/` even when only one module implements it.** `api/` is where
contracts go, and a contract that follows its implementation out is a rule that has stopped
applying.

**A test double used by two modules lives in `common/src/testFixtures/`.** A test source set
is private to its own Gradle project, so the alternative is a copy in each — and a
duplicated double drifts until the two modules are testing against different fictions.

## Never block the render thread

Minecraft's client thread renders the game. Blocking it freezes the game.

* **Every `api/` method that performs I/O returns `CompletableFuture<T>`** — database
  reads and writes, OpenRouter calls, embedding calls. No
  exceptions.
* `common/` executes that work on its own executor and never on the caller's thread.
* GUI code consumes a future by attaching a callback that hands the result back to the
  client thread before touching any Minecraft state.
* Never call `CompletableFuture#join`, `#get`, or `#getNow` on the client thread.

## Local data

* The local database is SQLite at `{minecraft_dir}/MCAgents/data.db`. The game directory is
  resolved by the loader module and handed to `platforms/shared/`; `common/` receives a path.
* Player-facing configuration lives beside it at `{minecraft_dir}/MCAgents/AGENTS.md` —
  the player's own agent personas and instructions. It is read at runtime and must never
  be overwritten by an update without the player asking.
* The database schema is created idempotently at startup. A schema change ships with a
  migration; never with a "delete the database and start again".
* External MCP servers are cloned to `{minecraft_dir}/MCAgents/mcps/{owner}/{repository}/`,
  with an optional `mcp_servers.json` beside them. **That file is as sensitive as the
  database**: an MCP server entry carries the environment the server is started with, which
  is where its credentials sit. Never log it, never attach it to an issue.
  [`../mcp/host.md`](../mcp/host.md) is the authority on everything under `mcps/`.
* Everything under `{minecraft_dir}/MCAgents/` is player data. Never commit a copy of it,
  and never log an API key.

## Language

All code, comments, identifiers, commit messages, documentation, and user-facing strings
in this repository are written in **English**.

## Version carrier

`project-version` in `gradle.properties` is this repository's version. It is `0.7.0` and
is never changed without explicit user approval — `{shared}/rules/versioning.md`.

## Build and run

The toolchain, the commands, and the entry points are in
[`../wiki/context/repository-map.md`](../wiki/context/repository-map.md). Keep that page
correct in the same commit that changes a command.
