---
name: memory-task-provider-selectors
status: in-progress
description: Two provider selectors in the console — one choosing the platform a pasted key opens, one choosing the platform that answers a question — the plan, the decisions, and what is proven.
---

# Provider Selectors

## 2026-09-01

**Goal.** Let a player choose a provider from a control that shows the choices, instead of
typing a platform id into a free-text box or hand-editing the platform half of a model id.
Two places need it, and they are asking two different questions: which platform a key
opens, and which platform answers a question.

**Objective.** On `/keys/` the platform a pasted key is filed under is picked from a
selector listing every platform worth offering, with an escape hatch that still accepts a
platform this release has never heard of. On `/chat/` the platform that answers is picked
from a selector beside the conversation, and the choice is in force for the next question
asked anywhere — in the browser or in the game — without visiting `/model/`.

**Detail.** The request named the page `/api`; the console has no such page. `/api/` is the
JSON prefix, and the nav item labelled **API keys** is served at `/keys/` — that is the page
meant, and its address does not change. Nothing about the routing rules changes: a platform
is still called directly only when it has an address and a key, and the gateway is still
what everything else falls back to. The suggestion lists stay suggestions — a selector that
became a whitelist would be a regression, because
[`../../api/openrouter.md`](../../api/openrouter.md) forbids rejecting a model or platform
the player named.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents/client-fabric` | `chore/provider-selectors-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | — |
| 2 | The key provider selector | Picking the platform a pasted key opens, on `/keys/` | `MCAgents/client-fabric` | `feat/key-provider-selector` | `website/` — `pages/keys.html`, `js/keys.js`, `css/keys/keys.css` | — |
| 3 | The chat provider selector | Picking the platform that answers, on `/chat/` | `MCAgents/client-fabric` | `feat/chat-provider-selector` | `api/model`, `website/` — `WebApi`, `pages/chat.html`, `js/chat.js`, `css/chat/chat.css` | — |
| 4 | The release | Both wikis, the changelog, and the closing entry | `MCAgents/client-fabric` | `docs/provider-selectors-release` | `wiki/`, `.agents/` | — |

Ordered by dependency and stacked that way: task 1 branches from `master`, task `k` from
task `k-1`. Tasks 2 and 3 both extend the console's page tests, and task 4 describes both,
so nothing here is independent of what precedes it.

**No pull request is opened for any of them.** The user asked for the branches to be pushed
and nothing more, which is the `{shared}/planning/task-workflow.md` §F gate
answered rather than skipped. The `PR` column stays empty for that reason, not because it
is waiting to be filled.

## Decisions not to relitigate

*(each task appends below as it lands)*
