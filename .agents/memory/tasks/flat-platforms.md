---
name: memory-task-flat-platforms
status: in-progress
description: Folding each platform module's 26.3 subproject and its {module}.gradle into one platforms/{module}/build.gradle, with no version folder left, released as a patch.
---

# One build.gradle per platform module

## 2026-10-18

**Goal.** On 2026-10-18 the owner wrote, as Task 1 of two: "Simplify the platform build scripts
and directory structure […] Consolidate the build logic into a single `build.gradle` file at the
root of each platform module […] Move any version-specific sources/resources (e.g., from
`platforms/fabric/26.3/src/`) up to the root of their respective modules […] Delete the old
`<loader>.gradle` files […] Delete the version-specific subdirectories (e.g., `26.3/`) across all
platform modules. Update `settings.gradle` so that the projects point directly to the module
roots". Core and template make the same change at the same time. Task 2, which stops bundling
sqlite-jdbc, starts after this release, so the sqlite lines stay as they are here. The plan is
`plans/flat-platforms-chat.md` in the project's shared files; the owner had already asked for
every branch to be opened as a pull request and rebase-merged in order.

**What master looks like.** Since 0.7.0 ([`latest-minecraft.md`](latest-minecraft.md)) each
platform module is a folder of shared sources with a `26.3/` subproject, whose `build.gradle`
applies the plugins, then `../{module}.gradle`, then `../../minecraft.gradle`, which takes the
game version from the folder's name. Only Forge's `pack.mcmeta` lives in a version folder.

**Decisions.**

- **`platforms/minecraft.gradle` goes too.** Its jar name and Minecraft range lines move into
  the build files that use them; the source-folder lines have nothing left to do.
- **The catalog names the game version once.** Its entries lose the `-26-3` suffix
  (`minecraft`, `fabric-api`, `neoforge`, `forge`), the builds read `libs.versions.minecraft`,
  and `settings.gradle` no longer names it. `core-client` joins the catalog.
- **Nothing a player or a build sees changes:** jar names keep `+26.3`, ids are unchanged, and
  every jar holds the entries of 0.13.1's.
- **`release.yml` takes `platforms/{loader}/build/libs/*-{version}+*.jar`**, but no sources jar, and
  still looks in `platforms/{loader}/*/` for a tag built before 0.13.2.
- **A patch release, 0.13.2**, still on core 0.13.0.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/flat-platforms-plan` | |
| 2 | One build.gradle per platform | `build/flat-platforms` | |
| 3 | Release 0.13.2 | `release/0.13.2` | |

### Task 1 — chore/flat-platforms-plan

This record, and its row in the memory index.

### Task 2 — build/flat-platforms

`platforms/{shared,fabric,neoforge,forge}/build.gradle` each hold their module's whole build;
Forge's `pack.mcmeta` moves to `platforms/forge/src/main/resources/`; the `26.3/` folders, the
`{module}.gradle` files and `minecraft.gradle` are deleted. `settings.gradle` points
`chat-{module}` at `platforms/{module}`, the catalog drops its suffixes, `release.yml` finds the
jars at their new paths, and the setup page, the repository map and the README describe the new
layout. `./gradlew build` is green and chat loads with core into a 26.3 world on Fabric,
NeoForge and Forge.

### Task 3 — release/0.13.2

`project-version` is `0.13.2`, a patch release: a build change, no behaviour change.
`wiki/logs/0/13/2/` says players and builders need do nothing; the logs index has its row at
the top, and the repository rule, map, state and setup page name the version. The `PR` column
is filled.
