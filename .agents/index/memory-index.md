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
| [`../memory/tasks/wallet-binding.md`](../memory/tasks/wallet-binding.md) | MetaMask wallet binding: the shape chosen, the decisions not to relitigate, and exactly what is proven and what is not. |
| [`../memory/tasks/central-service.md`](../memory/tasks/central-service.md) | Reaching mcagents.mcrealms.space: the cold-start design, the rate limit, the base pack, the model defaults, and what the live service actually answered. |
| [`../memory/tasks/web-console.md`](../memory/tasks/web-console.md) | The local web console: why it is long-lived where the wallet bridge is not, how the two sides stay in step, and the three defects a browser found that the Java suite could not. |
| [`../memory/tasks/embedding-switch.md`](../memory/tasks/embedding-switch.md) | Knowledge retrieval and its on/off switch: that retrieval did not exist, the absent-versus-blank rule, and what is proven versus only compiled. |
| [`../memory/tasks/knowledge-downloads.md`](../memory/tasks/knowledge-downloads.md) | Per-pack download, where each update check happens, and why embedding is the client's job rather than the service's. |
| [`../memory/tasks/screen-layout.md`](../memory/tasks/screen-layout.md) | The console screen's colliding text, why constants caused it, and how a screen's arithmetic got under test without Minecraft. |
| [`../memory/tasks/knowledge-lifecycle.md`](../memory/tasks/knowledge-lifecycle.md) | A pack's life on one machine: arriving, being embedded, going stale, being removed — and why the catalog panel never hides. |
| [`../memory/tasks/console-dialogs.md`](../memory/tasks/console-dialogs.md) | Why the console draws its own dialogs, what each sync button is allowed to cost, and the self-inflicted deletion a browser caught. |
| [`../memory/tasks/wallet-accounts.md`](../memory/tasks/wallet-accounts.md) | Proving every address in one trip, where the account boundary is enforced and why, the wallets page and the account columns, and the row-height guess a browser caught. |

## Maintenance

Any memory file added to or removed from `.agents/memory/` is reflected here **in the same
commit**. Load only the rows whose scope matches the current request.
