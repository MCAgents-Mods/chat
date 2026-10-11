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
