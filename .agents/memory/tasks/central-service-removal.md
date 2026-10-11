---
name: memory-task-central-service-removal
status: open
description: Removing the mod's client for the central service at mcagents.mcrealms.space after its repository was deleted — what goes, what stays, and the plan.
---

# Removing the Central Service Client

## 2026-10-11

**Goal.** The repository that implemented the central service, `MCAgents/server-fabric-expressjs`,
has been deleted by the owner. The mod should stop carrying code and documents that assume the
service exists.

**Objective.** Nothing in the source, the web console, the game screens, the tests or the
documents contacts or describes the central service; the mod builds, its tests pass, and the
console pages that remain still work in a browser.

**Detail.** The owner chose to remove the client, not only the references. Removed: the
service client and sync (`CentralService`, `KnowledgeSync`, `ServiceHealth`, their exceptions
and tests), the startup fetch of the base pack, the console routes and page code for service
health, catalog, update and download, and `MCAgents.SERVICE_URL`. Kept: the local pack store,
embedding backfill, retrieval, deleting a pack, and every model-provider path. No version bump.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents-Mods/chat` | `chore/central-service-removal-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | Remove the client | The service client, its callers in all modules, the console routes and page code, and their tests | `MCAgents-Mods/chat` | `refactor/central-service-removal` | `common/service`, `platforms/fabric`, `website`, `api/MCAgents`, tests | |
| 3 | Docs and changelog | Instructions, README, both wikis, the state file, and the `0.0.0` changelog | `MCAgents-Mods/chat` | `docs/central-service-removal` | `AGENTS.md`, `README.md`, `.agents/`, `wiki/` | |

## Decisions not to relitigate

**The client goes in one task.** `WebApi` takes a `CentralService` and `LocalWebConsole`
builds one, so no half of the removal compiles on its own; splitting it would leave a branch
that does not build.

**No second release branch.** `release/0.0.0` already exists and the version is not changing.
Task 3 edits the `0.0.0` changelog directly, because `0.0.0` has not shipped and its log would
otherwise describe a client that no longer exists.

**The local pack store stays.** Removing it is a separate, larger change and was not asked
for. With no source of packs it stays empty and retrieval finds nothing; that is a known
consequence, not an oversight.

**The homepage and the OpenRouter `HTTP-Referer` header** name `mcagents.mcrealms.space`.
Both move to `https://github.com/MCAgents-Mods/chat`, since the service they pointed at is gone.

**Historical records keep their wording.** Task records under `.agents/memory/tasks/` describe
the service because it existed when they were written.

*(each task appends below as it lands)*

### Task 2 — refactor/central-service-removal

**Landed.** Deleted `common/service/` (`CentralService`, `KnowledgeSync`, `ServiceHealth`, two
exceptions, and `KnowledgeSyncTest`), `KnowledgeSyncFactory`, the startup fetch of the base
pack and its shutdown hook in `MCAgentsClientMod`, `MCAgents.SERVICE_URL` and
`BASE_KNOWLEDGE_ID`. `WebApi` and `LocalWebConsole` no longer take a service: the console's
`start` overloads dropped that parameter, and the routes `/api/service/*`, `/api/catalog` and
`/api/knowledge/{id}/update|download` are gone. The pack detail route answers only from the
local store, so an unknown id is a plain 404. In the browser, `service.js`, the update button
and the update notice on the chat page, the whole "Available to download" panel on the knowledge
page, and the download and update-check panels on a pack's page are removed, with their CSS.
Deleting a pack now returns to the list, because nothing is left to show on its page.

**Changed rather than removed.** The OpenRouter attribution `HTTP-Referer` and the
`fabric.mod.json` homepage now name `https://github.com/MCAgents-Mods/chat`, and the mod
description no longer promises downloadable packs. `CatalogRoutesTest` became `PackRoutesTest`,
keeping the local-pack cases (show, 404, delete) and adding one that asserts the removed
routes answer 404. Comments in the surviving code that named the central service were reworded
to describe what the code does.

**Proven.** `./gradlew build` passes for all four modules with the tests. The console was
driven in Chromium at 1280 and 620 px against a seeded store: the knowledge list, a pack's page
and the chat page render with no console error, no failed request, no horizontal scroll and no
mention of a service; the chat page's notice reads "1 pack needs embedding."; and deleting a pack
goes through the dialog and lands back on a list without it. No file under `api/`, `common/`,
`website/` or `platforms/` mentions the service, `KnowledgeSync` or `mcrealms.space`.

**Not proven.** The in-game screens were not opened in a client; they never referenced the
service, so nothing there changed, but that was established by search, not by running them.

**Left for task 3.** Every document still describes the service, the base pack and downloads.
`HttpCall.get` and the transport's GET branch are now unused by the mod; they are generic HTTP
and were left in place.
