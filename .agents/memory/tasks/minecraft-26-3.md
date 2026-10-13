---
name: memory-task-minecraft-26-3
status: in progress
description: Building chat's Fabric and NeoForge jars for both Minecraft 26.2 and 26.3, one Gradle subproject per Minecraft version, on core 0.7.0 and agents 0.3.0, and releasing 0.5.0 with it.
---

# Minecraft 26.3, keeping 26.2

## 2026-10-13

**Goal.** The owner wrote: "It seem minecraft 26.3 it out now. Make all mod support 26.3".
Asked whether 26.2 should keep working, the owner picked "Also keep 26.2": every loader jar is
built twice, once per Minecraft version. Core did it first, as 0.7.0, then agents, as 0.3.0;
chat follows, then the template. The owner asked for every pull request to be opened and
rebase-merged in order without waiting for him. The plan is `plans/minecraft-26.3.md` in the
project's shared files.

**Objective.**
* Chat releases 0.5.0 with four mod jars: `MCAgentsChat{Fabric,Neoforge}-0.5.0+{26.2,26.3}.jar`,
  each declaring its own Minecraft version only.
* Each starts in a dev client of its version, and CHAT on the agents menu opens the chat
  hologram.

**Detail.**
* Chat keeps its two loaders. A Forge module is the next thread's work ("Forge support for
  chat"), built on this layout.
* Group, mod id and features do not change.

## What 26.3 needs here

The same loaders as core: Fabric Loader 0.19.5 with Fabric API 0.162.0+26.3, and NeoForge
26.3.0.76, with Fabric Loom 1.18.4, which needs Gradle 9.7 or newer running on Java 25. Chat's
game code names none of the types 26.3 changed: the key, the pose rotation and the hologram
drawing all live in core and agents.

## Decisions

The owner was away and asked for every question to be settled by taking the recommended
option.

* **The same layout as core and agents.** `platforms/shared`, `fabric` and `neoforge` become
  a folder of shared sources with one subproject per Minecraft version,
  `platforms/{module}/{minecraft}`, named `mcagents-chat-{module}-{minecraft}`, so the artifact
  ids and jar names carry the version. `api` and `common` are built once.
* **Core 0.7.0 and agents 0.3.0**, whose `mcagents-core-client-{minecraft}` each version
  compiles against, and the oldest the jars load with.
* **Release 0.5.0**, a minor: the jar names and artifact ids change.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/minecraft-26-3-plan` | |
| 2 | Per-version layout, 26.2 only, on core 0.7.0 and agents 0.3.0 | `build/minecraft-versions` | |
| 3 | Minecraft 26.3 | `feat/minecraft-26-3` | |
| 4 | Release 0.5.0 | `release/0.5.0` | |

### Task 1 — chore/minecraft-26-3-plan

This record, and its row in the memory index.

### Task 2 — build/minecraft-versions

`platforms/shared`, `fabric` and `neoforge` each became a folder of shared sources with one
subproject per Minecraft version, `platforms/{module}/{minecraft}`, named
`mcagents-chat-{module}-{minecraft}`, the same layout as core's and agents'. The versions are
listed once in `settings.gradle`, 26.2 only for now; `platforms/minecraft.gradle` takes the
version from the folder name, looks up the catalog entries suffixed with it, compiles the
folder's own `src/` with the shared one, and names the jar `{Name}-{version}+{minecraft}.jar`.
The loader metadata is expanded per version. Core moved to 0.7.0, whose
`mcagents-core-client-{minecraft}` each version compiles against, and agents to 0.3.0; with
`../core` beside it, `settings.gradle` substitutes core's `:platforms:client-{minecraft}`.
`release.yml` attaches one jar per loader and Minecraft version.

The NeoForge module gained the development client agents' has (`runs { client }`, with the
bundled modules loaded as one mod): the setup page listed `:mcagents-chat-neoforge:runClient`,
but the module declared no run, so the task did not exist.

With `../core` and `../agents` both beside it, `settings.gradle` substitutes core's client for
every version core builds, read from core's `platforms/client/*/build.gradle`, not only
chat's: agents, built in the same composite, compiles against core's client for each of its
own versions, and Gradle does not carry agents' own substitution into chat's build.

Checked: `./gradlew build` passes against core 0.7.0's and agents 0.3.0's source; the jars are
`MCAgentsChat{Shared,Fabric,Neoforge}-0.4.1+26.2.jar`, each loader declaring Minecraft 26.2
only, `mcagents_core` 0.7.0 and `mcagents_agents` 0.3.0 or newer. `actionlint` passes on both
workflows.
