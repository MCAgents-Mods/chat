# 0.6.0

Released: 2026-10-16

Chat runs on Forge as well as Fabric and NeoForge, on Minecraft 26.2 and 26.3, as MCAgents core
and MCAgents agents already do.

## Upgrading

- **Forge players install `MCAgentsChatForge-0.6.0+{minecraft}.jar`** for their Minecraft
  version, beside core's and agents' Forge jars for the same version: core 0.7.0 and agents
  0.3.0 or newer. The jar asks for Forge 65 or newer on 26.2 and Forge 66 or newer on 26.3.
- **Fabric and NeoForge players change nothing** beyond taking the new jar.

## Added

- **A Forge jar for Minecraft 26.2 (Forge 26.2-65.1.3) and 26.3 (Forge 26.3-66.0.11)**, from
  the new `platforms/forge` module: chat's buttons on the agents menu, the chat hologram, and
  the local database, as on the other loaders. SQLite is nested in the jar as Jar-in-Jar with
  Forge's `net.minecraftforge.jarjar` plugin.
- **`mcagents-chat-forge-26.2` and `mcagents-chat-forge-26.3`** on GitHub Packages.
- **A Forge development client per version**: `./gradlew :mcagents-chat-forge-{minecraft}:runClient`.
  See [`wiki/environments/setup.md`](../../../../environments/setup.md#commands).

## Changed

- The Release workflow attaches six jars, Fabric, NeoForge and Forge for each Minecraft
  version.
