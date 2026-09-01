---
name: memory-task-provider-selectors
status: done
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
| 4 | The release | The console's documentation, the changelog, and the closing entry | `MCAgents/client-fabric` | `docs/provider-selectors-release` | `wiki/`, `.agents/` | — |

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

### Task 3 — the chat provider selector

**A model id names both halves of the answer, so choosing a provider cannot leave the model
alone.** `ModelCatalog.chatModelOn` is that rule, in one place: the platform already serving
the model keeps it; a platform the mod suggests a model for, and that cannot serve the current
one, gets its own first suggestion; anything else carries the model half over. Putting it in
`api/` rather than in the page is what stops the console and the game screen from ever
disagreeing about what picking OpenAI means.

**The gateway is not on the chat list.** OpenRouter is a route, not a model's platform —
`openrouter/gpt-4o-mini` is a model nobody serves, which `ModelName` has said since it was
written. So `chatModelOn` returns the current id untouched when handed the gateway, the page
filters it out of the selector, and how a platform is reached is answered by the badge beside
the box instead.

**`chatPlatform` is a smaller way of saying the same thing, and an explicit id wins.**
`PUT /api/models` takes either; when both arrive the id is used, because it says strictly more.
The endpoint's absent-versus-blank rule is untouched, so a page sending only a platform still
leaves the embedding model and its switch exactly as they were.

**The selector sits beside the composer, not in the header.** It is a decision about the
question in front of you. It writes the same stored preference `/model/` and the game screen
read, so there is one choice wherever it is made — proven in a browser by picking a provider on
`/chat/` and reading the new id back off `/model/`.

**What the browser found that the Java suite could not.** The note under the selector was
written before the route badge was re-read, so for the length of one request the note announced
the new provider while the badge beside it still described the one just left. The badge is
painted first now and the note second: they are one sentence, and they land together. That is
the fourth defect a browser run has caught in this console with the Java suite green —
[`web-console.md`](web-console.md), [`console-dialogs.md`](console-dialogs.md).

## What is proven, and what is not

**Proven by the Java suites**: the three switch rules, with retirement and lowercasing applied;
that the gateway never qualifies a model; that a platform the mod ships nothing for is stored
as typed; that an explicit id outranks a platform; that picking a provider leaves the embedding
choice alone; that the offered list carries the gateway, the addressed platforms, the suggested
ones, and any the player already holds a key for; and that both pages serve their controls.

**Proven in a browser**, against a live console on a real database — 28 checks, Chromium: the
key selector's contents, its default, its hidden "Other…" box, a key filed under a listed
platform, the choice surviving the list's rebuild, a blank "Other…" refused, and a platform the
mod never heard of stored as typed and turning its row direct; on the chat page, the gateway
absent from the list, the stored provider selected, the model re-qualified on switch, the route
badge agreeing with it, an unlisted provider accepted and shown as reached through the gateway,
the same choice read back on `/model/`, and that provider still selected after a reload; no
horizontal scroll at 360, 620, 900 and 1280px on either page; no page errors, and no native
dialog.

**Not proven.** No question has been answered through a provider chosen this way — the harness
agent replies without calling anybody, so nothing here proves a request reaches OpenAI or
anyone else. Nothing has run inside Minecraft: the game screen keeps its own platform box and
is untouched by this work, and `platforms/fabric/` remains compile-verified only —
[`gui-play-testing.md`](gui-play-testing.md).

### Task 4 — the release

**What was updated, and why each was not optional.**
[`../../../wiki/information/web-console.md`](../../../wiki/information/web-console.md) — the
page describing both consoles pages: the key page's selector and its "Other…" box, the chat
page's "Answered by" row with the table of what each choice does to the stored model id, and
the note on `/model/` that the chat selector writes the same setting rather than a copy of it.
The changelog — one entry under `0.0.0`, because the version carrier is untouched.
[`../state/repository-state.md`](../state/repository-state.md) — what is live and what is still
unproven, and the test count, now 526.

**`README.md` and `overview.md` were read and left alone.** Both describe the *in-game* key
screen, whose platform box is a text field and is untouched by this work. Editing them to
mention a selector would have made them wrong.

**No pull request was opened, and none is pending.** The user asked for the branches to be
pushed and nothing more, so the `PR` column stays empty as a fact rather than as a gap. All
four branches are on `origin`, stacked in order.

## Closed

Four branches pushed, stacked `chore/provider-selectors-plan` → `feat/key-provider-selector` →
`feat/chat-provider-selector` → `docs/provider-selectors-release`. Nothing merged: merging is
the user's call and was not asked for.
