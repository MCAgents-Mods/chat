---
name: memory-task-loader-platforms
status: in progress
description: Renaming chat's jars to MCAgentsChat{Module} and its artifact ids to mcagents-chat-{module}, and splitting the mod into platforms/{shared, fabric, neoforge} so it ships a NeoForge jar.
---

# Loader platforms

## 2026-10-11

**Goal.** The owner asked for one naming pattern across both repositories, and for chat to
gain the `platforms/{shared, fabric, neoforge}` layout core already has, with `shared` holding
the code both loaders use.

**Objective.** Every chat module builds `MCAgentsChat{Module}-{version}.jar` and publishes as
`io.github.mcagentsmods:mcagents-chat-{module}`. Chat depends on core's renamed coordinates,
`mcagents-core-api` and `mcagents-core-common`. The screens, the client start-up and the
factories live in `platforms/shared`, compiled against vanilla Minecraft; `platforms/fabric`
and `platforms/neoforge` hold only each loader's entry point, key registration and events.

| Module | Base name | Artifact id |
|---|---|---|
| `api` | `MCAgentsChatApi` | `mcagents-chat-api` |
| `common` | `MCAgentsChatCommon` | `mcagents-chat-common` |
| `website` | `MCAgentsChatWebsite` | `mcagents-chat-website` |
| `platforms/shared` | `MCAgentsChatShared` | `mcagents-chat-shared` |
| `platforms/fabric` | `MCAgentsChatFabric` | `mcagents-chat-fabric` |
| `platforms/neoforge` | `MCAgentsChatNeoforge` | `mcagents-chat-neoforge` |

**Detail.** Core's half is core's `artifact-names.md` and merges first. No version change:
`project-version` stays `0.1.0`, which is the owner's call.

## The task list

| # | Title | Scope | Branch | PR |
|---|---|---|---|---|
| 1 | The record | This file and its index row | `chore/loader-platforms-plan` | |
| 2 | Artifact names | Project names, base names, publications, core's new coordinates, docs | `refactor/artifact-names` | |
| 3 | Shared platform | `platforms/shared` against vanilla Minecraft; Fabric keeps its three loader classes | `refactor/shared-platform` | |
| 4 | NeoForge | `platforms/neoforge`: entry point, key registration, events, manifest, bundled SQLite | `feat/neoforge-platform` | |

## Task 2 — refactor/artifact-names

The Gradle projects are renamed `mcagents-chat-{module}` in `settings.gradle`, so the
artifact id is the project name with nothing to compute. Each module sets its own
`archivesName`, `MCAgentsChat{Module}`. The root build applies `maven-publish` to every module
with the GitHub Packages repository; `api`, `common` and `website` publish `components.java`,
and the Fabric module publishes its mod jar alone through `tasks.named('jar')` — an eager
`jar` reference evaluates the Fabric project before `website`, whose source set it bundles.

`libs.versions.toml` and the composite build's substitution now name core's
`mcagents-core-api` and `mcagents-core-common`. Released changelogs and older task records keep
the old names as history.

`./gradlew build publishToMavenLocal` passes against core's `refactor/artifact-names`.

## Task 3 — refactor/shared-platform

`platforms/shared` (`mcagents-chat-shared`, package `io.github.mcagentsmods.chat.shared`) now
holds every screen, `ChatScreenLauncher`, the factories, `PlayerAccounts`, `WebConsoleHolder`
and the language file. Only three Fabric calls existed, so the split was a move:

| Was, in `platforms/fabric` | Now |
|---|---|
| `MCAgentsClientMod.onInitializeClient` — storage, MCP, persona, shutdown hook | `ChatRuntime.start(Path gameDirectory)` in shared; the Fabric class keeps only registration |
| `MCAgentsKeyMappings.register()` with `KeyMappingHelper` and `ClientTickEvents` | `create(category)` and `onEndTick(client)` in shared; the loader registers the category, the key and the tick |
| `KeyMappingHelper.getBoundKeyOf` in `/mcagents status` | vanilla `isUnbound()` and `getTranslatedKeyMessage()` |
| `GameEnvironment.register()` with `ClientPlayConnectionEvents` | `onJoin(client)` and `onLeave()`; the loader forwards its events |

`platforms/shared` is built with Loom and the vanilla game alone. Without fabric-loader on its
classpath javac warns once per class about Fabric's `@Environment` enum in the game jar, and no
lint switch covers that warning, so the module compiles with warnings off; errors still fail.
The classpath is the guard against loader types: neither loader's API is on it.

Not proven in a real game. `./gradlew build` passes, and the Fabric jar carries the 34 shared
classes and the language file.
