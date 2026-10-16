---
name: memory-task-latest-minecraft
status: in-progress
description: Dropping Minecraft 26.2 so chat supports only the latest release (26.3), removing the Minecraft suffix from project and artifact ids, on core 0.8.0 and agents 0.4.0, and releasing 0.7.0.
---

# Task: only the latest Minecraft version

**Status:** in progress

## The request

On 2026-10-16 the owner wrote: "Make it support only latest minecraft version, right now it is
"26.3". we will not have another version support … but still keep suffix. e.g. "+26.3"", and
then: "Also delete "+26.3" in artifact id too. Since we only support latest version", with
`agents-api`, `core-common`, `agents-tool` and `chat-common` as examples, and this work first,
before the artifact-id and Forge-for-chat work. Core 0.8.0 and agents 0.4.0 do the same and are
published first. The plan is `plans/latest-minecraft-only.md` in the project's shared files.

## Decisions

- **Only the latest Minecraft release, permanently.** See
  [`../decisions/minecraft-latest-only.md`](../decisions/minecraft-latest-only.md).
- **The per-version layout stays, with 26.3 as its only entry**, so moving to the next release
  renames a folder rather than rewriting the build.
- **Jar names keep the game's version** (`MCAgentsChatFabric-0.7.0+26.3.jar`); **project and
  artifact ids lose it**: `mcagents-chat-shared-26.3` becomes `mcagents-chat-shared`, and the
  loaders `mcagents-chat-{fabric,neoforge,forge}`. Dropping the `mcagents-` prefix is the next
  task chain's (artifact ids), not this one's.
- **Core 0.8.0 and agents 0.4.0**, whose ids drop the version the same way.
- **Release 0.7.0**, a minor: it drops a supported Minecraft version and renames the version
  subprojects' artifact ids.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/latest-minecraft-plan` | |
| 2 | Only Minecraft 26.3 | `build/latest-minecraft` | |
| 3 | Unversioned project and artifact ids, core 0.8.0 and agents 0.4.0 | `build/unversioned-ids` | |
| 4 | Release 0.7.0 | `release/0.7.0` | |

### Task 1 — chore/latest-minecraft-plan

This record, the decision record, and their rows in the memory index.

### Task 2 — build/latest-minecraft

Every `platforms/{module}/26.2/` folder is gone (the four `build.gradle` files and Forge's
`pack.mcmeta`, format 107); `settings.gradle` lists `['26.3']` alone, and the catalog lost its
`-26-2` block. The comments in `settings.gradle`, the catalog, `platforms/minecraft.gradle`,
each module's `.gradle` file and both workflows describe one version, the latest release.
`release.yml` already collects `platforms/{loader}/*/build.gradle`, so it attaches three jars.
Docs updated: `README.md`, `wiki/environments/setup.md` (its "One build per Minecraft version"
section is now "The Minecraft version"), `wiki/information/architecture.md`, the repository
map and the stack line in the repository state.
