---
name: memory-state-repository-state
description: Current state of MCAgentsClient — what is built, what is not, and the next step. Overwritten in place, always current.
---

# Repository State

**Version:** 0.0.0 · **Default branch:** `master` · **Mode:** B (consumer)

## What exists

* The agent instruction system: root `AGENTS.md`, `.agents/index/`, `.agents/rules/repository.md`,
  `.agents/wiki/context/repository-map.md`, seed memory.
* The human wiki: overview, architecture, environment setup, and the `0.0.0` change log.
* `LICENSE` — MCAgents commercial license, 2026.
* `README.md` — overview only.
* **A working multi-module Gradle build.** `./gradlew build` is green and produces a
  loadable mod jar at `platforms/fabric/build/libs/mcagents-client-fabric-0.0.0.jar`,
  carrying all three modules' classes, `fabric.mod.json`, and the license.
* `MCAgents` (identity and on-disk layout) and `ClientPaths` (resolves that layout from a
  game directory it is handed).
* **The complete local storage layer.** `api.model` records, `api.storage` contracts
  (`LocalDatabase`, `ApiKeyRepository`, `ChatRepository`, `ContentRepository` — all
  `CompletableFuture`), and `common.storage.sqlite` implementing them against the schema
  the user specified. 33 tests in `common/`, all passing.
* `MCAgentsClientMod` opens the database asynchronously at startup and exposes it as
  `Optional<LocalDatabase>`; a failure to open leaves the game running.

## What does not exist yet

* The OpenRouter client, the embedding and retrieval layer, the GUI.
* Any download path from the central service — `ContentRepository` can store a pack, but
  nothing fetches one yet.
* Any CI workflow.

## Stack decisions already made

* Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.157.0+26.2, Fabric Loom 1.17.19,
  Java 25, Gradle 9.7.0 via the committed wrapper.
* Minecraft 26.x ships **no Yarn mappings and no intermediary** — Loom uses Mojang's
  official mappings implicitly, and **no `remapJar` task runs**: `jar` produces the final
  artifact. Verified by building on 2026-08-13.
* `gradle.properties` holds project identity only, exactly as the user specified. Every
  dependency coordinate lives in `gradle/libs.versions.toml`.
* `api/` and `common/` are plain `java-library` modules whose classes are merged into the
  mod jar by the `jar` task, not nested as Jar-in-Jar mods.
* Group `io.github.mcagents`, base package `io.github.mcagents.clientfabric`, version `0.0.0`.
* Local storage: SQLite at `{minecraft_dir}/MCAgents/data.db` via `org.xerial:sqlite-jdbc`
  `3.53.2.1`, bundled into the mod jar with Loom's `include`. One connection on one daemon
  thread; `SQLiteDataSource` rather than `DriverManager`, because JDBC's `ServiceLoader`
  lookup is unreliable under a mod loader's classloader.
* Tests use JUnit 6.1.3 and live in `common/` only — `api/` is contracts and
  `platforms/fabric/` needs a running game.
* Companion repository for the central Knowledge Pack service:
  `MCAgents/server-fabric-expressjs`.

## Shared instruction set

Resolved through the `lxagents-agents-base` MCP connector. Nothing from it is copied into
this repository; the override table in `.agents/index/root-index.md` is empty.

## Next step

The chat GUI on `feat/chat-gui`, then the OpenRouter client and retrieval.
