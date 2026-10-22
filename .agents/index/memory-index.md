---
name: memory-index
description: Index of .agents/memory/ — MCAgentsClient's dynamic state and task records, read at the start of every session.
---

# Memory Index

**Scope:** `.agents/memory/`
**Parent:** [`root-index.md`](root-index.md)

## state

| File | Purpose |
|---|---|
| [`../memory/state/repository-state.md`](../memory/state/repository-state.md) | What exists in this repository right now, what is not built yet, and the next obvious step. |

## decisions

| File | Purpose |
|---|---|
| [`../memory/decisions/token-encryption-at-rest.md`](../memory/decisions/token-encryption-at-rest.md) | Why stored tokens are encrypted, which local rule that set aside, and on whose instruction. |
| [`../memory/decisions/minecraft-latest-only.md`](../memory/decisions/minecraft-latest-only.md) | The owner's permanent decision: only the latest Minecraft release, the version kept in jar names but not in artifact ids. |

## tasks

| File | Purpose |
|---|---|
| [`../memory/tasks/agents-setup.md`](../memory/tasks/agents-setup.md) | Record of the agent instruction system setup: mode, decisions, branch, what was proposed but not created. |
| [`../memory/tasks/openrouter-integration.md`](../memory/tasks/openrouter-integration.md) | The model client, prompt caching, model selection, and dependency scoping — decisions not to relitigate, and what is still unverified. |
| [`../memory/tasks/gui-play-testing.md`](../memory/tasks/gui-play-testing.md) | What running the mod in a real client found, why the test suite could not, and the log-only-failure mistake made twice. |
| [`../memory/tasks/wallet-binding.md`](../memory/tasks/wallet-binding.md) | MetaMask wallet binding: the shape chosen, the decisions not to relitigate, and exactly what is proven and what is not. |
| [`../memory/tasks/central-service.md`](../memory/tasks/central-service.md) | Historical — the client was removed, see `central-service-removal.md`. Reaching mcagents.mcrealms.space: the cold-start design, the rate limit, the base pack, the model defaults, and what the live service actually answered. |
| [`../memory/tasks/web-console.md`](../memory/tasks/web-console.md) | The local web console: why it is long-lived where the wallet bridge is not, how the two sides stay in step, and the three defects a browser found that the Java suite could not. |
| [`../memory/tasks/embedding-switch.md`](../memory/tasks/embedding-switch.md) | Knowledge retrieval and its on/off switch: that retrieval did not exist, the absent-versus-blank rule, and what is proven versus only compiled. |
| [`../memory/tasks/knowledge-downloads.md`](../memory/tasks/knowledge-downloads.md) | Per-pack download, where each update check happens, and why embedding is the client's job rather than the service's. |
| [`../memory/tasks/screen-layout.md`](../memory/tasks/screen-layout.md) | The console screen's colliding text, why constants caused it, and how a screen's arithmetic got under test without Minecraft. |
| [`../memory/tasks/knowledge-lifecycle.md`](../memory/tasks/knowledge-lifecycle.md) | A pack's life on one machine: arriving, being embedded, going stale, being removed — and why the catalog panel never hides. |
| [`../memory/tasks/console-dialogs.md`](../memory/tasks/console-dialogs.md) | Why the console draws its own dialogs, what each sync button is allowed to cost, and the self-inflicted deletion a browser caught. |
| [`../memory/tasks/wallet-accounts.md`](../memory/tasks/wallet-accounts.md) | Proving every address in one trip, where the account boundary is enforced and why, the wallets page and the account columns, and the row-height guess a browser caught. |
| [`../memory/tasks/website-module.md`](../memory/tasks/website-module.md) | Why the web console became its own Gradle module, where the boundary is, what stayed behind in `api/` and `common/`, and how the move was proven not to have broken the pages. |
| [`../memory/tasks/server-context-and-persona.md`](../memory/tasks/server-context-and-persona.md) | Telling the agent where the player is, and making `AGENTS.md` a file the mod writes and the player edits from either interface — the decisions, the rule set aside, and what a browser proved that the Java suite could not. |
| [`../memory/tasks/mcp-connector-bootstrap.md`](../memory/tasks/mcp-connector-bootstrap.md) | Making the shared-instruction connector survive a session: the committed registration, the ignored checkout, and why the server id is not the repository name. |
| [`../memory/tasks/mcp-host.md`](../memory/tasks/mcp-host.md) | The mod as an MCP client: the mcps layout, why discovery is split from connection, the qualified-name cap, and what is proven versus only compiled. |
| [`../memory/tasks/mcp-routing.md`](../memory/tasks/mcp-routing.md) | Routing a model's tool call to the server that owns it: the three names that must agree, why a tool failure is never a failed question, and what is proven. |
| [`../memory/tasks/token-platforms-and-encryption.md`](../memory/tasks/token-platforms-and-encryption.md) | Platform-aware, encrypted token storage and direct platform routing: the plan, the rule set aside, and what is proven. |
| [`../memory/tasks/agents-activation-sync.md`](../memory/tasks/agents-activation-sync.md) | Why the branching rule never fired, which shared consumer actions were outstanding, and what the duplicate audit found. |
| [`../memory/tasks/claude-md-import.md`](../memory/tasks/claude-md-import.md) | Making Claude Code read `AGENTS.md` through an import rather than a copy, and why the path is `../AGENTS.md`. |
| [`../memory/tasks/provider-selectors.md`](../memory/tasks/provider-selectors.md) | Two provider selectors in the console: which platform a key opens, which platform answers a question, and why the page called "/api" is `/keys/`. |
| [`../memory/tasks/game-provider-selector.md`](../memory/tasks/game-provider-selector.md) | Provider selection in the game GUI: the key screen's platform dropdown, the model screen's provider row, and why the offered list lives in `api/`. |
| [`../memory/tasks/repository-move.md`](../memory/tasks/repository-move.md) | Moving to MCAgents-Mods/chat: the new namespace, the MIT license, what was deliberately not renamed, and the plan. |
| [`../memory/tasks/central-service-removal.md`](../memory/tasks/central-service-removal.md) | Removing the client for the deleted central service: what goes, what stays, and the plan. |
| [`../memory/tasks/service-leftovers.md`](../memory/tasks/service-leftovers.md) | The code the removed service client left behind, the plans folder ignore, and why `release/0.0.0` moved back to the tip. |
| [`../memory/tasks/token-env-rename.md`](../memory/tasks/token-env-rename.md) | Renaming the token encryption variable to `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY`, and why the old name is dropped rather than kept. |
| [`../memory/tasks/core-adoption.md`](../memory/tasks/core-adoption.md) | Adopting core: calling core's model client, registering chat's pages into core's web console, the security review, and the `0.1.0` release. |
| [`../memory/tasks/loader-platforms.md`](../memory/tasks/loader-platforms.md) | Renaming every jar to `MCAgentsChat{Module}` and artifact to `mcagents-chat-{module}`, and splitting the mod into `platforms/{shared, fabric, neoforge}`. |
| [`../memory/tasks/publish-workflow.md`](../memory/tasks/publish-workflow.md) | The publish workflow: resolving published core from GitHub Packages, the `v{version}` tag check, and why the first published release is `0.2.0`. |
| [`../memory/tasks/agents-adoption.md`](../memory/tasks/agents-adoption.md) | Moving tool calling onto the agents mod: MCP servers as tool categories, answers through `ToolAgent`, the Chat button on the agents screen, and the `0.3.0` release. |
| [`../memory/tasks/chat-hologram.md`](../memory/tasks/chat-hologram.md) | Removing the website and moving chat onto core's hologram: three panels, the controller under them, chat's buttons on the agents menu, and the `0.4.0` release. |
| [`../memory/tasks/release-workflow.md`](../memory/tasks/release-workflow.md) | The Release workflow that attaches the Fabric and NeoForge jars to each tag's GitHub release, and the `0.4.1` release. |
| [`../memory/tasks/minecraft-26-3.md`](../memory/tasks/minecraft-26-3.md) | Building the Fabric and NeoForge jars for Minecraft 26.2 and 26.3, one Gradle subproject per version, on core 0.7.0 and agents 0.3.0, and the `0.5.0` release. |
| [`../memory/tasks/forge-platform.md`](../memory/tasks/forge-platform.md) | Adding the Forge platform: a Forge jar for Minecraft 26.2 and 26.3 that nests SQLite as Jar-in-Jar, and the `0.6.0` release. |
| [`../memory/tasks/latest-minecraft.md`](../memory/tasks/latest-minecraft.md) | Only the latest Minecraft release (26.3): 26.2 dropped, no Minecraft version in project or artifact ids, core 0.8.0 and agents 0.4.0, and the `0.7.0` release. |
| [`../memory/tasks/artifact-ids.md`](../memory/tasks/artifact-ids.md) | Artifact ids `chat-{module}` without the repeated `mcagents-`, only `api`, `common` and `shared` published, on core 0.9.0 and agents 0.5.0, and the `0.8.0` release. |
| [`../memory/tasks/core-agents.md`](../memory/tasks/core-agents.md) | Chat on core alone after the agents mod became part of core: tools, the agent and menu buttons from core's packages, the agents dependency and manifest entries dropped. |
| [`../memory/tasks/session-picker.md`](../memory/tasks/session-picker.md) | A Chat Sessions hologram listing every conversation with NEW CHAT on its nav bar, one chat hologram per conversation, several at once, the open ones faded, on core 0.11.0. |
| [`../memory/tasks/compile-only.md`](../memory/tasks/compile-only.md) | Every `implementation` dependency replaced by `compileOnly`, with a dev-client `runtimeOnly` twin in the loader modules; published `chat-common` stops asking for sqlite-jdbc. |
| [`../memory/tasks/hologram-pages.md`](../memory/tasks/hologram-pages.md) | Keys, Models, Persona and Wallets, and every dialog they and the chat hologram open, as holograms built from core 0.12.0's form panel; the flat screens deleted. |
| [`../memory/tasks/model-providers.md`](../memory/tasks/model-providers.md) | The Models page calling the provider the player picks, custom providers, and editing core's global model every task falls back to. |
| [`../memory/tasks/per-chat-models.md`](../memory/tasks/per-chat-models.md) | Each conversation choosing its own chat and embedding model, falling back to core's global model, and retrieval in a model other than the library's. |
| [`../memory/tasks/own-modules.md`](../memory/tasks/own-modules.md) | Each loader module declaring chat's own submodules with `implementation` while the loader, Fabric API and core stay `compileOnly`, with no jar changing. |
| [`../memory/tasks/flat-platforms.md`](../memory/tasks/flat-platforms.md) | One `build.gradle` per platform module, with no `26.3/` subproject, `{module}.gradle` or `minecraft.gradle` left, and the catalog naming the Minecraft version once. |
| [`../memory/tasks/no-bundled-sqlite.md`](../memory/tasks/no-bundled-sqlite.md) | Taking sqlite-jdbc out of every mod jar, `compileOnly` plus the dev client's `runtimeOnly` only, and Forge's Jar-in-Jar plugin with it. |
| [`../memory/tasks/config-paths.md`](../memory/tasks/config-paths.md) | Every file chat keeps — `data.db`, `AGENTS.md`, `mcps/` — in `MCAgents/chat/`, a player's old files moved there on first start, on core 0.14.0. |

## Maintenance

Any memory file added to or removed from `.agents/memory/` is reflected here **in the same
commit**. Load only the rows whose scope matches the current request.
