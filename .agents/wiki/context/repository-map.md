---
name: agent-wiki-context-repository-map
description: Orientation for agents working on MCAgentsClient — what exists today, what is planned, where things go, and the gotchas that bite first.
---

# Repository Map

Read this before touching anything. It says what is actually in the repository right now,
not what the design documents describe. The design lives in
[`../../../wiki/information/architecture.md`](../../../wiki/information/architecture.md).

## What exists right now

```
AGENTS.md            entry point for agents
README.md            human overview
LICENSE              MCAgents commercial license, 2026
.agents/             this repository's local instruction set, indexes, agent wiki, memory
wiki/                human documentation and versioned change logs
```

**There is no build yet.** No `build.gradle`, no `settings.gradle`, no source tree, no
dependency on Minecraft or Fabric. The Gradle scaffold is the next task on the
`build/gradle` branch. Do not assume a source file exists because the architecture
mentions it — check.

## What is planned, and where it will go

| Path | Gradle module | Holds |
|---|---|---|
| `api/` | `mcagents-fabric-api` | Contracts only: records, interfaces, enums. Every I/O method returns `CompletableFuture<T>`. |
| `common/` | `mcagents-fabric-common` | Implementations: SQLite via `org.xerial:sqlite-jdbc`, the OpenRouter client, embedding and retrieval. No Minecraft on the classpath. |
| `platforms/fabric/` | `mcagents-fabric` | The mod itself: initializer, screens, key bindings, mixins, and the resolution of the game directory. |

`settings.gradle` maps the Gradle names to those directories; the directory names and the
module names deliberately differ.

## Target platform

| Thing | Value |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |
| Java | 25 (Minecraft 26.2 requires it) |
| Gradle | 8.14.3 or newer, with Fabric Loom |
| Group | `io.github.mcagents` |
| Base package | `io.github.mcagents.clientfabric` |
| Version | `0.0.0` |

Minecraft 26.x has **no Yarn mappings and no intermediary** — Fabric Loom resolves Mojang's
official mappings automatically, and a `mappings` line in `dependencies` is neither needed
nor correct. Check `https://meta.fabricmc.net/v2/versions/` before changing any of these.

## Commands

Once the Gradle scaffold lands:

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Compile one module | `./gradlew :mcagents-fabric-common:build` |
| Run the client | `./gradlew :mcagents-fabric:runClient` |
| Produce the mod jar | `./gradlew :mcagents-fabric:build` — output in `platforms/fabric/build/libs/` |

The environment used for development here carries JDK 21 and JDK 25; Gradle's toolchain
resolver selects 25. `runClient` needs a display and will not work headless.

## Generated paths — leave them alone

`build/`, `.gradle/`, `run/`, `platforms/fabric/build/`, and `platforms/fabric/run/` are
generated. Never commit them, never hand-edit them.

## Player data — never in the repository

`{minecraft_dir}/MCAgents/data.db` and `{minecraft_dir}/MCAgents/AGENTS.md` live on the
player's machine. `data.db` holds OpenRouter API keys in the clear as a design decision, so
**never log its contents, never attach it to an issue, and never write a fixture copy into
this repository.**

## Gotchas

* **Offline-first is a hard rule, not a preference.** The central service at
  `mcagents.mcrealms.space` may be unreachable at any time and everything local must keep
  working. See [`../../rules/repository.md`](../../rules/repository.md).
* **Never block the client thread.** No `join()`, no `get()` on a future from render or
  tick code. The game freezes and the player blames the mod.
* **`common/` must not see Minecraft.** If you reach for a `net.minecraft` import there,
  the value belongs in an `api/` contract passed in from the platform module.
* The repository root `AGENTS.md` is the agent entry point. `{minecraft_dir}/MCAgents/AGENTS.md`
  is the *player's* persona file at runtime. They are unrelated files with the same name —
  do not confuse them.
* The shared instruction set resolves through the `lxagents-agents-base` connector. If it
  is unavailable, say so and work from `.agents/` alone; do not reconstruct it.
