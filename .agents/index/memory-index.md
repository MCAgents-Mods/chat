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

## tasks

| File | Purpose |
|---|---|
| [`../memory/tasks/agents-setup.md`](../memory/tasks/agents-setup.md) | Record of the agent instruction system setup: mode, decisions, branch, what was proposed but not created. |
| [`../memory/tasks/openrouter-integration.md`](../memory/tasks/openrouter-integration.md) | The model client, prompt caching, model selection, and dependency scoping — decisions not to relitigate, and what is still unverified. |
| [`../memory/tasks/gui-play-testing.md`](../memory/tasks/gui-play-testing.md) | What running the mod in a real client found, why the test suite could not, and the log-only-failure mistake made twice. |

## Maintenance

Any memory file added to or removed from `.agents/memory/` is reflected here **in the same
commit**. Load only the rows whose scope matches the current request.
