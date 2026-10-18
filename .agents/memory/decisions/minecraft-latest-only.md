---
name: memory-decisions-minecraft-latest-only
description: The owner's permanent decision that every MCAgents mod supports only the latest Minecraft release, with the game version kept in jar names but not in artifact ids.
---

# Decision: only the latest Minecraft release

**Decided:** 2026-10-16, by the owner, as permanent. It replaces the 2026-10-13 choice to keep
26.2 beside 26.3.

- Chat, and every MCAgents mod, supports exactly one Minecraft version: the latest release,
  26.3 today. No older version is kept, restored or added beside it.
- Jar names keep the game's version as a suffix: `MCAgentsChatFabric-{version}+26.3.jar`.
- Gradle project names and artifact ids do not carry it: `:mcagents-chat-shared`,
  `mcagents-chat-shared`.
- The catalog names the version once, in one block (`minecraft`, `fabric-api`, `neoforge`,
  `forge`). Up to 0.13.1 each Minecraft-facing module also kept a `platforms/{module}/{minecraft}/`
  subproject; from 0.13.2 (owner, 2026-10-18) each is one folder, `platforms/{module}/`, with one
  `build.gradle`. Moving to a new release replaces that block, and Forge's pack format, in one
  change and bumps the minor version.

**Why:** the owner: "we will not have another version support". Keeping two versions doubled
every build, release and dev-client check for players who can update the game.
