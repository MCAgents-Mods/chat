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

### Task 2 — the key provider selector

**The datalist is now a select, which reverses a decision taken eight days ago.**
[`token-platforms-and-encryption.md`](token-platforms-and-encryption.md) task 6 chose a
datalist so the box would suggest without becoming a whitelist. The property was right and
is kept; the control was wrong. A datalist is invisible until you type, so a player who has
never seen a platform id has nothing to type to reveal one — the same objection
[`../../design/web-design-system.md`](../../design/web-design-system.md) already had the
model page act on when it dropped its datalist for visible chips.

**"Other…" is what keeps the list from becoming a whitelist.** Choosing it reveals a plain
text box whose contents are sent exactly as typed, so a platform that shipped after this
release is still one keypress further away than a listed one, not impossible. Nothing on the
server changed: `addKey` still refuses to validate a platform against any list.

**The blank value means "Other…", and that is safe only because blank is never a platform.**
A blank platform is stored as the gateway, so it can never arrive from the list meaning
anything else — and the page refuses to send a blank rather than filing a key under a
platform the player just declined to pick.

**The choice is held as a value, not read back off the element.** An empty `<select>` reads
as `''`, which is the "Other…" row, so the element alone cannot tell "no options yet" from
"typing my own" — and the first fill happens before the player has touched anything, which
is exactly when those two must not be confused. Reading it off the DOM put the page on
"Other…" on first load; that is why `platformChoice` exists.

**The selection survives the list being rebuilt.** The routes are re-fetched after every add,
and a selector that reset itself would file the second key of a pair under the wrong
platform — the same reason the game screen keeps its platform box after a save.

**`select.field` went into the shared component layer, not the page's stylesheet.** It is a
form control, the chat page takes the same one in task 3, and the design system reserves a
per-section stylesheet for page-specific layout. The popup is left to the browser: it is the
one overlay the design system's opaque-overlay rule cannot reach, and a hand-built listbox
would have to re-earn keyboard, touch and screen-reader behaviour a native select has.
