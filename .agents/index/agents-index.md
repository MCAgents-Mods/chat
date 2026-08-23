---
name: agents-index
description: Index of MCAgentsClient's local instruction folders — the rules that apply only to this repository.
---

# Agents Index

**Scope:** `.agents/` instruction folders
**Parent:** [`root-index.md`](root-index.md)

## rules

| File | Purpose |
|---|---|
| [`../rules/repository.md`](../rules/repository.md) | The rules specific to this repository — module boundaries, offline-first mandate, build commands, what must not be introduced. |

## database

| File | Purpose |
|---|---|
| [`../database/schema.md`](../database/schema.md) | The local SQLite tables, the invariants code depends on, and how a schema change ships without destroying player data. |

## security

| File | Purpose |
|---|---|
| [`../security/api-keys.md`](../security/api-keys.md) | Where an OpenRouter key may and may not appear, and how a rejected one is rotated. |

## api

| File | Purpose |
|---|---|
| [`../api/openrouter.md`](../api/openrouter.md) | Request shape, model selection, retry and timeout policy, and what is never sent to the model provider. |

## mcp

| File | Purpose |
|---|---|
| [`../mcp/host.md`](../mcp/host.md) | The mod as an MCP client: the mcps layout, the doubled path validation, the qualified-name cap, the reload-as-a-diff rule, and why a failing server is never fatal. |

## design

| File | Purpose |
|---|---|
| [`../design/web-design-system.md`](../design/web-design-system.md) | Silver Glass — the tokens, components, and layout every page the mod serves in a browser is built from, and why those pages must stay self-contained. |

## Maintenance

Any instruction file added to or removed from `.agents/` is reflected here **in the same
commit**. This index lists local files only; the shared set is reached through the shared
router named in [`root-index.md`](root-index.md).
