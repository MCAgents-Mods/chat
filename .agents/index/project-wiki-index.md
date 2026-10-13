---
name: project-wiki-index
description: Index of wiki/ — the human-facing documentation for MCAgentsClient, excluding the versioned change logs.
---

# Project Wiki Index

**Scope:** `wiki/` (except `wiki/logs/`)
**Parent:** [`root-index.md`](root-index.md)

## information

| File | Purpose |
|---|---|
| [`../../wiki/information/overview.md`](../../wiki/information/overview.md) | What MCAgentsClient is, who it is for, and how its pieces fit together. |
| [`../../wiki/information/architecture.md`](../../wiki/information/architecture.md) | The module split, the offline-first boundary, and the async rule for anything touching I/O. |
| [`../../wiki/information/wallet-binding.md`](../../wiki/information/wallet-binding.md) | How wallets are held and how an EVM address is proved through MetaMask over a loopback bridge that stops itself. |
| [`../../wiki/information/knowledge-retrieval.md`](../../wiki/information/knowledge-retrieval.md) | How a question reaches the right Knowledge Packs, the limits on what is attached, and the Embedding switch that turns the whole path off. |
| [`../../wiki/information/mcp-servers.md`](../../wiki/information/mcp-servers.md) | External MCP servers: where they are cloned, how they are discovered and run without a restart, and what happens when one fails. |

## environments

| File | Purpose |
|---|---|
| [`../../wiki/environments/setup.md`](../../wiki/environments/setup.md) | Toolchain requirements and the commands to build and run the mod locally. |

## Child Indexes

| Index | Scope | Load when |
|---|---|---|
| [`logs-index.md`](logs-index.md) | `wiki/logs/` | You need release history or must record a change. |

## Maintenance

Any page added to or removed from `wiki/` is reflected here **in the same commit**.
