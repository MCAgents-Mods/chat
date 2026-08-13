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

## Maintenance

Any memory file added to or removed from `.agents/memory/` is reflected here **in the same
commit**. Load only the rows whose scope matches the current request.
