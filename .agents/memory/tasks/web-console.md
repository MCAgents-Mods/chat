---
name: memory-tasks-web-console
description: Record of the local web console — the shape chosen, the decisions not to relitigate, the bugs a browser found that tests could not, and what is still unproven.
---

# Task — The Local Web Console

**Status:** complete, unverified in a real client · **Base:** `master`

## Branches

One per task, stacked, each based on the one before it. All pushed.

| Branch | What it carries |
|---|---|
| `docs/web-design-system` | Silver Glass in `.agents/design/`, and the AGENTS.md row routing to it |
| `feat/paged-queries` | `Page`, `PageRequest`, `KnowledgeSummary`, paged reads, 15 tests |
| `feat/web-console` | The loopback server, the JSON API, the guard, the revision feed, 24 tests |
| `feat/web-console-pages` | The three Silver Glass pages, 6 tests |
| `feat/web-console-screen` | The in-game button, the console screen, the live refresh |
| `feat/knowledge-scroll-panel` | The pack list in its own scroller |
| `feat/web-console-keys` | `/keys/`, the handle scheme, 7 tests |
| `docs/web-console` | This record and the documentation |

## Goal

A browser view of the player's own data, served by the mod: the knowledge library, the
chat, and the API keys, in a window with a scrollbar and a find bar.

## Decisions, not to relitigate

| Decision | Why |
|---|---|
| The console is long-lived; the wallet bridge is not | A browser tab whose server vanished is a page that stops working while it is being read. The bridge's rule — stop the instant a signature verifies — is about the bridge. **Do not change either to resemble the other.** |
| The revision counter lives in a repository decorator | A counter owned by the console would only ever see the console's own writes, so the sync would work in one direction and look finished from the side that worked. |
| The console outlives the screen that started it | The player opens it precisely so they can go back to the game with a browser beside them. |
| Assets are served without a token | A `<link>` in a static page cannot carry one. The alternative was generating markup in Java to substitute a token into every asset URL. Assets carry no player data; pages and `/api/` stay guarded. |
| Pages are static and fetch what they show | No page file ever contains player text, so there is nothing in one to escape. |
| Keys are named by a SHA-256 handle | `key_value` is the primary key, so a delete button would otherwise have sent the player's key to the browser and back. |
| Keys are listed as masks | What `.agents/security/api-keys.md` allows, and all a player needs to recognise one. |
| A key's shape is not validated | OpenRouter has changed its prefix before; refusing a working key is worse than letting the provider reject it. |
| Blocking in `WebApi` is correct | An HTTP handler exists to produce a response and runs on the console's own worker thread. The thread that must never block is the one rendering the game, and it is not this one. |

## The console once promised never to touch a key

It said so in its contract and a test asserted `/api/keys` returned 404. The user then
asked for a key page. That is their call, the security rule permits a masked one, and the
comments now describe what actually holds. The test was replaced rather than deleted: it no
longer asserts that no route exists, it asserts that **no route returns a value**, which is
the invariant that was always the real one.

## What a browser found that the tests could not

The Java tests exercised the API and passed. Driving the pages in Chromium found three
defects in the same afternoon, and each of them would have shipped:

1. **Chat infinite-scroll was dead.** The sentinel sat outside the sidebar's own scroller,
   so the observer measured it against the viewport, read it as permanently visible, fired
   twice on load and never again — intersection had not *changed*. Dragging the scrollbar
   did nothing.
2. **Neither scroller scrolled.** `min-height: 0` was missing on the flex children, so both
   panels grew past their containers instead of shrinking. The list then loaded every row at
   once, defeating the paging entirely.
3. **The chat page scrolled its own composer off-screen** on every send, because the panel's
   `min-height` ignored `main`'s padding.

**Drive the pages in a browser before believing them.** A green Java suite says the API is
right and nothing at all about whether the page works.

## Proven

* Paging: every row appears exactly once across windows, for packs, chats, and keys.
* The guard: no token, a foreign `Host`, a traversal through the asset route — all refused.
* No key value in any response, asserted against the JSON *and* against the rendered page.
* The sync moves in both directions, each verified from the side that did not write.
* The console stops when told and not before.
* 197 tests in `common/`. `./gradlew build` produces the jar with the assets inside it.
* Chromium, against a seeded store: 47 packs and 26 conversations paging correctly, live
  search, category filter, a chat message round-tripping, keys added and removed, no
  horizontal scroll at 390px, and no JavaScript errors on any page.

## Not proven, and worth knowing

* **No screen here has run in a real client.** Compile-verified against Minecraft 26.2 only;
  this environment has no display. That includes the console button, the console screen, and
  the live refresh in `AgentChatScreen#tick`. See [`gui-play-testing.md`](gui-play-testing.md)
  for the same mistake made twice before.
* **The live refresh has never been seen working end to end** — the counter is tested on both
  sides, but game-to-browser-to-game has only been proven in pieces.
* The token sits in the address bar, so it is in the browser's history. Acceptable for a
  loopback-only console; worth knowing.
