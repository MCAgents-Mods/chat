---
name: memory-tasks-central-service
description: Record of the central-service work — the cold-start design, the model default change, the base knowledge pack, and what the live service actually answered.
---

# Task — Reaching the Central Service

**Status:** complete, unverified in a real client · **Base:** `master`

## Branches

| Repository | Branch | What it carries |
|---|---|---|
| server | `feat/rate-limit` | Per-client fixed-window limiter, tighter on the catalog reads |
| server | `feat/base-knowledge` | `mcagents-info` at version `0.0.0` |
| client | `feat/model-defaults` | New defaults, and the retired-id map |
| client | `feat/central-service` | `CentralService`, `KnowledgeSync`, the startup fetch |
| client | `feat/web-console-models` | `/model/` |
| client | `feat/web-console-sync` | The health banner, Reconnect, refresh and update buttons |
| client | `docs/service-and-models` | This record and the documentation |

## Decisions, not to relitigate

| Decision | Why |
|---|---|
| A sleeping service is not an error | The host idles, so a refused connection is the ordinary first answer. `ServiceHealth` is a record, not an exception, and no caller has to catch anything. |
| Retry every minute, stop after ten | A page that retried forever would poll a host that is not coming back until the tab closed. The button restarts the budget, because the player is asking again. |
| Health has its own loose rate-limit bucket | The mod polls it once a minute while the service wakes. A 429 there would make a sleeping server look like a broken one. |
| The rate limiter is written out, not installed | Roughly forty lines of a counter in a map, against a dependency and a changelog to follow. It is **not** a security boundary and says so. |
| Refresh and update are one operation | The chat button exists only because that is where a player notices the agent is missing something. One implementation, so they cannot diverge. |
| Packs download one at a time | This runs while the player is in a game; parallel body downloads would compete with it for bandwidth. |
| `-latest` was retired, not just delisted | Removing an id from the list stops it being offered, not being used. A player who selected it keeps it saved and keeps failing every request. |

## What the tests caught

**A fresh install could never receive the base pack.** `ensureBaseKnowledge` fetched the pack
and saved it, but `contents.category_id` is a foreign key and a brand-new database has no
categories — so the save failed, the whole call is best-effort, and it failed *silently* on
the one run that mattered most. It now stores the categories first.

## What the live service actually answered

Driven from a real browser against `https://mcagents.mcrealms.space`:

* `/health` answered, version `0.0.0` — so the reachable path is real, not simulated.
* Pressing refresh downloaded its catalog: 3 packs, 47 → 50 locally.
* The cold-start banner, the Reconnect button, and the disabled refresh button were all
  exercised against a second console pointed at a port with nothing on it.

**`mcagents-info` was not downloaded, and that is correct for now:** the live deployment
publishes only the three example packs, because `feat/base-knowledge` has not been deployed.
The client asked for it and carried on. Once that branch is live, a client picks it up at the
next startup with no client change.

## Not proven

* No screen has run in a real client. That now includes the console button, the console
  screen, and the live refresh — see [`web-console.md`](web-console.md).
* The rate limiter has been exercised against the test suite and never against a real flood.
* Nothing has re-embedded a library after a change of embedding model; the warning on
  `/model/` describes a consequence nothing has yet had to handle.
