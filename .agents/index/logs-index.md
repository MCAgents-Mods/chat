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
