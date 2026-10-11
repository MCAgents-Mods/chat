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
