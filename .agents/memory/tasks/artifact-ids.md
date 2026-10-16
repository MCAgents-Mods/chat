---
name: memory-task-artifact-ids
status: in-progress
description: Dropping the repeated mcagents- from every published artifact id, publishing only api, common and shared and never the loader jars, on core 0.9.0 and agents 0.5.0, and releasing 0.8.0 with it.
---

# Shorter artifact ids, library modules only

## 2026-10-16

**Goal.** On 2026-10-13 the owner wrote, to start after the Minecraft 26.3 work, and on
2026-10-16 moved it after the latest-Minecraft-only work (0.7.0): "make only module 'api,
common, shared, And include module tool in mod agents too' to be publish. Don't publish
platform forge, fabric, neoforge. It seem we have duplicate in artifact id. Update it to be
likes this: group name stay the same. before: mcagents-{id}-{module} … new: {id}-{module}.
e.g. chat-api, chat-common, agents-api, agents-tool". Core did it first, as 0.9.0, then
agents, as 0.5.0; chat follows, then the template. The owner asked for every pull request to
be opened and rebase-merged in order without waiting for the owner. The plan is
`plans/artifact-ids.md` in the project's shared files.

**Objective.**

| Module | Before | After |
|---|---|---|
| `api` | `mcagents-chat-api` | `chat-api` |
| `common` | `mcagents-chat-common` | `chat-common` |
| `platforms/shared` | `mcagents-chat-shared` | `chat-shared` |
| `platforms/{fabric,neoforge,forge}` | `mcagents-chat-{loader}` | not published |

**Detail.**
* The group stays `io.github.mcagentsmods`. Jar names, the mod id `mcagents` and packages do
  not change. The loader jars are still built by every build and attached to each GitHub
  release by the Release workflow.
* Core's and agents' coordinates move the same way: `core-api`, `core-common` and
  `core-client` at 0.9.0, `agents-tool`, `agents-api`, `agents-common` and `agents-shared` at
  0.5.0.

## Decisions

The owner was away and asked for every question to be settled by taking the recommended
option.

* **The Gradle project names become the artifact ids,** `chat-api` and `chat-shared`,
  as before: here the project name is the artifact id, and a project named after its module
  alone would clash with core's `:api` and `:common` when core is included beside it.
* **`shared` is published.** It is the `shared` the owner named; it compiles against the
  latest Minecraft release, the only one supported.
* **A loader module's `publishing` block is deleted,** the new Forge one's too, so nothing is
  left that could publish it; it still builds its jar for the Release workflow.
* **Core 0.9.0 and agents 0.5.0,** the first whose artifact ids are `core-*` and `agents-*`.
  The registry filter in `build.gradle` asks core's and agents' GitHub Packages registries
  for `core-*` and `agents-*` only.
* **Old packages are left alone.** Nothing is deleted from GitHub Packages.
* **Release 0.8.0**, a minor after the latest-Minecraft-only release, 0.7.0: every coordinate
  a consumer resolves changes.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/artifact-ids-plan` | |
| 2 | Library modules only | `build/publish-scope` | |
| 3 | Shorter artifact ids, on core 0.9.0 and agents 0.5.0 | `build/artifact-ids` | |
| 4 | Release 0.8.0 | `release/0.8.0` | |

### Task 1 — chore/artifact-ids-plan

This record, and its row in the memory index.
