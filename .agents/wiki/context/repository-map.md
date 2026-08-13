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
settings.gradle      module map and plugin repositories
build.gradle         group and version for every module
gradle.properties    project identity only — organization, repository, version
gradle/              the wrapper and libs.versions.toml
api/                 mcagents-fabric-api
common/              mcagents-fabric-common
platforms/fabric/    mcagents-fabric
```

The build works end to end: `./gradlew build` produces a loadable mod jar. Each module
currently holds one class, so most of the feature surface described below does not exist
yet. Do not assume a source file exists because the architecture mentions it — check.

## The three modules

| Path | Gradle module | Holds today | Will hold |
|---|---|---|---|
| `api/` | `mcagents-fabric-api` | `MCAgents` — mod identity and on-disk layout constants. | Contracts only: records, interfaces, enums. Every I/O method returns `CompletableFuture<T>`. |
| `common/` | `mcagents-fabric-common` | `ClientPaths` — resolves the mod's layout from a game directory it is handed. | SQLite via `org.xerial:sqlite-jdbc`, the OpenRouter client, embedding and retrieval. No Minecraft on the classpath. |
| `platforms/fabric/` | `mcagents-fabric` | `MCAgentsClientMod` — the `ClientModInitializer`. | Screens, key bindings, mixins, event listeners. |

`settings.gradle` maps the Gradle names to those directories; the directory names and the
module names deliberately differ.

`api/` and `common/` are plain `java-library` modules. Their classes are **merged into the
mod jar** by the `jar` task in `platforms/fabric/build.gradle` rather than nested as
Jar-in-Jar mods — they are not Fabric mods and have no `fabric.mod.json`. Third-party
runtime libraries are bundled with Loom's `include` instead.

## Where versions live

`gradle.properties` carries the project's identity and nothing else:

```
git-org=MCAgents
git-repo=client-fabric
project-version=0.0.0
```

**Every dependency coordinate is in `gradle/libs.versions.toml`**, the Gradle version
catalog. Change a platform version there, not in `gradle.properties`. Build scripts read
the project version through `providers.gradleProperty('project-version')`; the hyphenated
names cannot be dereferenced as `project.gitOrg`.

## Target platform

| Thing | Value |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |
| Fabric Loom | 1.17.19 |
| Java | 25 (Minecraft 26.2 requires it) |
| Gradle | 9.7.0, via the committed wrapper |
| Group | `io.github.mcagents` |
| Base package | `io.github.mcagents.clientfabric` |
| Version | `0.0.0` |

Two things about Minecraft 26.x that will waste your time if you assume otherwise:

* **There are no Yarn mappings and no intermediary.** Loom resolves Mojang's official
  mappings on its own. A `mappings` line in `dependencies` is neither needed nor correct,
  and Yarn's published versions stop at 1.21.11.
* **Nothing is remapped, so there is no `remapJar`.** The `jar` task produces the final
  artifact directly. Do not wire a task to `remapJar` — it does not run.

Fabric API and the loader are declared with plain `implementation`, not `modImplementation`,
for the same reason. Check `https://meta.fabricmc.net/v2/versions/` before changing any of
these.

## Commands

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Compile one module | `./gradlew :mcagents-fabric-common:build` |
| Run the client | `./gradlew :mcagents-fabric:runClient` |
| Produce the mod jar | `./gradlew :mcagents-fabric:build` — output in `platforms/fabric/build/libs/` |

Always use `./gradlew`, never a system `gradle` — the wrapper pins 9.7.0 and a Gradle 8
launcher cannot evaluate `settings.gradle`. Gradle itself runs on JDK 21 and compiles
through a Java 25 toolchain; the `foojay-resolver-convention` plugin in `settings.gradle`
downloads JDK 25 when it is not installed. `runClient` needs a display and will not work
headless.

The first build downloads and processes Minecraft — several minutes and a few gigabytes.
If it fails with an out-of-memory error, raise the Gradle daemon heap in your **user**
`~/.gradle/gradle.properties` (`org.gradle.jvmargs=-Xmx2G`); the committed
`gradle.properties` is reserved for project identity.

## Generated paths — leave them alone

`build/`, `.gradle/`, `run/`, `platforms/fabric/build/`, and `platforms/fabric/run/` are
generated. Never commit them, never hand-edit them. `gradle/wrapper/gradle-wrapper.jar` is
the one binary that **is** committed, on purpose — `.gitignore` carries an explicit
exception for it.

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
