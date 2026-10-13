---
name: memory-task-forge-platform
status: in-progress
description: Adding a Forge platform to chat — a Forge jar for Minecraft 26.2 and 26.3 that nests SQLite as Jar-in-Jar — and the release that ships it.
---

# Forge platform

## 2026-10-13

**Goal.** The owner asked: "Work before thread 'Publish only shared modules' but after thread
'Minecraft 26.3 support'. add platform forge into mod chat." Forge players can then run chat,
as they already run core and agents.

**Objective.**
* chat builds a Forge jar for each Minecraft version it builds for, 26.2 and 26.3, beside its
  Fabric and NeoForge jars.
* Each Forge jar carries api, common and shared, nests sqlite-jdbc, declares only its own
  Minecraft version, and starts in a Forge client of that version with core and agents: chat's
  buttons appear on the agents menu and the local database opens.
* The release's GitHub release carries six jars: Fabric, NeoForge and Forge, each for 26.2 and
  26.3.

**Detail.**
* Work starts after chat `0.5.0`, the Minecraft 26.3 release, and uses its layout: shared
  sources in `platforms/forge/src`, one subproject per version in `platforms/forge/{version}`.
* Core and agents do not change; both already ship Forge jars (core `0.7.0`, agents `0.3.0`).
* The mod id `mcagents`, the group, the features and the other loaders stay as they are.

## Decisions

The owner was away and asked for every question to be settled by taking the recommended
option.

* **SQLite as Jar-in-Jar.** Forge 26.2 and 26.3 both ship Forge's Jar-in-Jar loader
  (`JarJarSelector`, `JarJarFileSystems`), and Forge publishes a Gradle plugin for it,
  `net.minecraftforge.jarjar`. The jar nests `META-INF/jarjar/sqlite-jdbc-{version}.jar` the
  way the NeoForge jar does. Shading sqlite-jdbc's classes into the mod jar was the
  alternative; it would merge sqlite-jdbc's own module descriptor into the mod.
* **The jar with SQLite nested takes the plain name.** The plugin builds it as a second jar
  with an `-all` classifier; here it is `MCAgentsChatForge-{version}+{minecraft}.jar`, and the
  jar without SQLite, which cannot open the database, is built to `build/slim/` and never sits
  in `build/libs/`.
* **No per-version Java class.** The two Forge builds differ only in `pack.mcmeta` (pack format
  107 for 26.2, 121 for 26.3, copied from agents and core): `ClientPlayerNetworkEvent.LoggingIn`
  and `LoggingOut`, `FMLPaths` and `FMLEnvironment` are the same in Forge 26.2-65.1.3 and
  26.3-66.0.11.
* **Ranges as core's and agents' Forge jars.** Forge `[{major},)` from the Forge build the jar
  compiled against (65, 66), Minecraft `[{version},{next})`, core and agents from the versions
  this build compiled against.
* **Release as the next minor.** A new loader is a feature, so the release after `0.5.0` is
  `0.6.0`. Riding in `0.5.0` would have made the 26.3 work wait.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/forge-platform-plan` | |
| 2 | Forge platform | `feat/forge-platform` | |
| 3 | Forge jars on the release | `ci/forge-jars` | |
| 4 | Release 0.6.0 | `release/0.6.0` | |

Merge order: 1 to 4, stacked, rebase-merged.

### Task 1 — chore/forge-platform-plan

This record, and its row in the memory index. Before it, a scratch copy of chat `0.4.1` with a
Forge module (Forge 26.2) built a jar with sqlite-jdbc nested; a Forge 26.2 client with that
jar and core's and agents' Forge jars in `mods/` loaded all three, Forge's Jar-in-Jar locator
picked up sqlite-jdbc from chat's jar, the database opened, joining a world set the player
environment, and G showed chat's buttons on the agents menu.
