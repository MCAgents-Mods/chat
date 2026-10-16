---
name: memory-task-forge-platform
status: done
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
| 1 | Task record | `chore/forge-platform-plan` | #42 |
| 2 | Forge platform | `feat/forge-platform` | #43 |
| 3 | Forge jars on the release | `ci/forge-jars` | #44 |
| 4 | Release 0.6.0 | `release/0.6.0` | #45 |

Merge order: 1 to 4, stacked, rebase-merged.

### Task 1 — chore/forge-platform-plan

This record, and its row in the memory index. Before it, a scratch copy of chat `0.4.1` with a
Forge module (Forge 26.2) built a jar with sqlite-jdbc nested; a Forge 26.2 client with that
jar and core's and agents' Forge jars in `mods/` loaded all three, Forge's Jar-in-Jar locator
picked up sqlite-jdbc from chat's jar, the database opened, joining a world set the player
environment, and G showed chat's buttons on the agents menu.

### Task 2 — feat/forge-platform

`platforms/forge/` joins the loaders in the per-version layout: shared sources in `src/`, with
`MCAgentsForgeMod` (`@Mod`, which starts `MCAgentsForgeClient` on the client distribution only)
adding chat's menu buttons, registering `ClientPlayerNetworkEvent.LoggingIn` and `LoggingOut`
and starting `ChatRuntime` with `FMLPaths.GAMEDIR`, and `META-INF/mods.toml`; `26.2/` and
`26.3/` hold only a build file and that version's `pack.mcmeta`. `forge.gradle` builds on
ForgeGradle 7.0.40 and nests `sqlite-jdbc` with Forge's Jar-in-Jar plugin 0.2.3; the Jar-in-Jar
jar is `MCAgentsChatForge-{version}+{minecraft}.jar` in `build/libs/` and is the one published,
and the jar without SQLite goes to `build/slim/`. The catalog gained Forge 26.2-65.1.3 and
26.3-66.0.11 and both plugins, `settings.gradle` the Forge maven and the module, and the root
`build.gradle` the plugins. The dev client runs from one directory holding chat's classes, as
in agents. The README, the overview, the architecture and setup pages, the repository map, the
`platforms/shared` build comment and the state file name Forge.

Checked: `./gradlew build` passes against core 0.7.0's and agents 0.3.0's source. Each Forge
jar holds `META-INF/jarjar/sqlite-jdbc-3.53.2.1.jar` with its `metadata.json`, its version's
`pack.mcmeta` (107 for 26.2, 121 for 26.3), and a `mods.toml` asking for Forge `[65,)` or
`[66,)`, Minecraft `[26.2,26.3)` or `[26.3,26.4)`, core 0.7.0 and agents 0.3.0 or newer. In
chat's Forge dev client of each version, with core 0.7.0's and agents 0.3.0's released Forge jars
in `run/mods/`, the database opened, a new world logged "Player environment is now single
player", G showed CHAT, KEYS, MODEL, PERSONA and WALLETS on the agents menu, and CHAT opened the
three-panel hologram; MODEL opened its screen on 26.2. The built jars themselves, put beside
core's jar in agents' Forge client, loaded with Forge's Jar-in-Jar locator taking sqlite-jdbc
from chat's jar, and the database opened, on both versions.

The instruction files that list chat's loaders, `AGENTS.md`'s overview line and
`.agents/rules/repository.md`'s module table, are left for the owner: they are reported as
findings, not edited.

### Task 3 — ci/forge-jars

`release.yml`'s `LOADERS` gains `forge`, so a release carries six jars, one per loader and
Minecraft version, and its header names the Forge jar and why the jar without SQLite is not in
`build/libs/`. `publish.yml`'s header names the forge module among those it publishes. The
setup page, the repository map and the state file list the Forge jar among the release's; the
map's publishing and release paragraphs also give the per-version artifact ids and jar paths
they had not been updated for in `0.5.0`.

Checked: `actionlint` passes on both workflows, with shellcheck. The release workflow's
collection step, run over a local `./gradlew build`, picks exactly one jar per loader and
Minecraft version: six.

### Task 4 — release/0.6.0

`project-version` is `0.6.0`, a minor: a new loader is a feature, and nothing changes for
Fabric and NeoForge players. The 0.6.0 changelog and its logs-index row are new, and the setup
page, the rule, the map and the state file say 0.6.0. Merging this branch is the release:
Publish finds no `v0.6.0` tag, builds against core 0.7.0 and agents 0.3.0 from GitHub Packages,
publishes every module, the Forge ones included, and tags the commit, and Release then makes
the `v0.6.0` release with the six loader jars.
