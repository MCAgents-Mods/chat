---
name: logs-index
description: Version list for MCAgentsClient, newest first — each row points at that version's change log.
---

# Logs Index

**Scope:** `wiki/logs/`
**Parent:** [`project-wiki-index.md`](project-wiki-index.md)

## Versions

| Version | File | Purpose |
|---|---|---|
| 0.14.0 | [`../../wiki/logs/0/14/0/CHANGELOG.md`](../../wiki/logs/0/14/0/CHANGELOG.md) | The mod jars no longer carry `sqlite-jdbc` (about 12.3 MB to about 360 KB); a game without SQLite says chat cannot save anything; Forge's Jar-in-Jar plugin is gone; core stays 0.13.0. |
| 0.13.2 | [`../../wiki/logs/0/13/2/CHANGELOG.md`](../../wiki/logs/0/13/2/CHANGELOG.md) | Build only: one `build.gradle` per platform module with no `26.3/` subproject, the catalog naming the Minecraft version once; the jars are unchanged; core stays 0.13.0. |
| 0.13.1 | [`../../wiki/logs/0/13/1/CHANGELOG.md`](../../wiki/logs/0/13/1/CHANGELOG.md) | Build only: the loader modules `implementation` chat's own modules while the loader, Fabric API and core stay `compileOnly`; the jars are unchanged; core stays 0.13.0. |
| 0.13.0 | [`../../wiki/logs/0/13/0/CHANGELOG.md`](../../wiki/logs/0/13/0/CHANGELOG.md) | MODEL under each conversation opens a Chat Model page: its own chat and embedding model, or the global ones; packs are re-embedded in a conversation's own embedding model; core stays 0.13.0. |
| 0.12.0 | [`../../wiki/logs/0/12/0/CHANGELOG.md`](../../wiki/logs/0/12/0/CHANGELOG.md) | The picked provider is called at its own address; a chat and an embedding provider, any number of custom providers on the Models page; the models move into core 0.13.0's global model. |
| 0.11.0 | [`../../wiki/logs/0/11/0/CHANGELOG.md`](../../wiki/logs/0/11/0/CHANGELOG.md) | Every G menu page is a hologram: Keys, Models, Persona and Wallets, and their questions as dialog holograms; the flat screens are gone; core 0.12.0. |
| 0.10.1 | [`../../wiki/logs/0/10/1/CHANGELOG.md`](../../wiki/logs/0/10/1/CHANGELOG.md) | Build only: nothing is `implementation` any more; sqlite-jdbc is `compileOnly` in `common/`, so published `chat-common` no longer asks for it; the jars are unchanged. |
| 0.10.0 | [`../../wiki/logs/0/10/0/CHANGELOG.md`](../../wiki/logs/0/10/0/CHANGELOG.md) | CHAT opens a Chat Sessions list with NEW CHAT on its title bar; each conversation gets its own chat hologram, several at once, and the open ones are faded in the list; core 0.11.0. |
| 0.9.0 | [`../../wiki/logs/0/9/0/CHANGELOG.md`](../../wiki/logs/0/9/0/CHANGELOG.md) | Chat on core alone: tools, the agent and menu buttons from core 0.10.0, which took in the agents mod; `mcagents_agents` no longer required. |
| 0.8.0 | [`../../wiki/logs/0/8/0/CHANGELOG.md`](../../wiki/logs/0/8/0/CHANGELOG.md) | Artifact ids without the repeated `mcagents-`, as in `chat-common`; only the library modules published; core 0.9.0 and agents 0.5.0. |
| 0.7.0 | [`../../wiki/logs/0/7/0/CHANGELOG.md`](../../wiki/logs/0/7/0/CHANGELOG.md) | Minecraft 26.3 only; ids without the Minecraft version; core 0.8.0 and agents 0.4.0; releases carry three jars. |
| 0.6.0 | [`../../wiki/logs/0/6/0/CHANGELOG.md`](../../wiki/logs/0/6/0/CHANGELOG.md) | A Forge jar for Minecraft 26.2 and 26.3, with SQLite nested as Jar-in-Jar; releases carry six jars. |
| 0.5.0 | [`../../wiki/logs/0/5/0/CHANGELOG.md`](../../wiki/logs/0/5/0/CHANGELOG.md) | Minecraft 26.3 beside 26.2: one jar per loader and Minecraft version, artifact ids suffixed with the version; core 0.7.0 and agents 0.3.0 required. |
| 0.4.1 | [`../../wiki/logs/0/4/1/CHANGELOG.md`](../../wiki/logs/0/4/1/CHANGELOG.md) | The Release workflow attaches the Fabric and NeoForge jars to each version's GitHub release; no code changes. |
| 0.4.0 | [`../../wiki/logs/0/4/0/CHANGELOG.md`](../../wiki/logs/0/4/0/CHANGELOG.md) | In game only: the website and the chat screen go, CHAT opens a three-panel chat hologram, and keys, models, the persona and wallets get their own buttons on the MCAgents menu. |
| 0.3.0 | [`../../wiki/logs/0/3/0/CHANGELOG.md`](../../wiki/logs/0/3/0/CHANGELOG.md) | Runs on MCAgents agents: MCP servers as tool categories, answers through the agents mod's agent, and a Chat button on the agents screen in place of chat's G key. |
| 0.2.0 | [`../../wiki/logs/0/2/0/CHANGELOG.md`](../../wiki/logs/0/2/0/CHANGELOG.md) | A NeoForge jar, every module published as `mcagents-chat-{module}`, and releases that publish themselves to GitHub Packages. |
| 0.1.0 | [`../../wiki/logs/0/1/0/CHANGELOG.md`](../../wiki/logs/0/1/0/CHANGELOG.md) | Runs on MCAgents core: core's model client and shared web console, and MCP servers that wait for the player's approval. |
| 0.0.0 | [`../../wiki/logs/0/0/0/CHANGELOG.md`](../../wiki/logs/0/0/0/CHANGELOG.md) | Everything built so far — the instruction system and build, storage, the agent and retrieval, the web console and its provider selectors, wallets, and the MCP client and its tool routing. |

## Maintenance

A new version directory is added to the top of this table **in the same commit** that
creates it. The project version is never bumped without explicit user approval.
