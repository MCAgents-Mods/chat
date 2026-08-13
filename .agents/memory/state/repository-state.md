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

## What does not exist yet

* Any build file. No `settings.gradle`, no `build.gradle`, no Gradle wrapper.
* Any source code. `api/`, `common/`, and `platforms/fabric/` are not created.
* The SQLite layer, the OpenRouter client, the embedding and retrieval layer, the GUI.
* Any CI workflow.

## Stack decisions already made

* Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.157.0+26.2, Java 25, Fabric Loom.
* Minecraft 26.x ships **no Yarn mappings and no intermediary** — Loom uses Mojang's
  official mappings implicitly. Verified against `meta.fabricmc.net` on 2026-08-13.
* Group `io.github.mcagents`, base package `io.github.mcagents.clientfabric`, version `0.0.0`.
* Local storage: SQLite at `{minecraft_dir}/MCAgents/data.db` via `org.xerial:sqlite-jdbc`.
* Companion repository for the central Knowledge Pack service:
  `MCAgents/server-fabric-expressjs`.

## Shared instruction set

Resolved through the `lxagents-agents-base` MCP connector. Nothing from it is copied into
this repository; the override table in `.agents/index/root-index.md` is empty.

## Next step

Scaffold the multi-module Gradle project on `build/gradle`, then the SQLite layer on
`feat/sqlite-database`, then the chat GUI on `feat/chat-gui`.
