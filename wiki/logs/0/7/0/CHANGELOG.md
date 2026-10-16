# 0.7.0

Released: 2026-10-16

Chat supports only the latest Minecraft release, 26.3. The 26.2 jars are gone, and the
artifact ids no longer carry the Minecraft version.

## Upgrading

- **Players on Minecraft 26.2 stay on 0.6.0.** Chat 0.7.0 ships one jar per loader, for 26.3:
  `MCAgentsChatFabric-0.7.0+26.3.jar`, `MCAgentsChatNeoforge-0.7.0+26.3.jar` and
  `MCAgentsChatForge-0.7.0+26.3.jar`. No later release will support 26.2 again.
- **Install core 0.8.0 and agents 0.4.0 or newer** beside it, for the same loader. The jar
  asks for them.
- **Drop the Minecraft version from chat's artifact ids.** `mcagents-chat-shared-26.3`
  becomes `mcagents-chat-shared`, and the same for `mcagents-chat-fabric`, `-neoforge` and
  `-forge`. `mcagents-chat-api` and `mcagents-chat-common` are unchanged.
- **Development clients drop the version too**: `./gradlew :mcagents-chat-{loader}:runClient`.
  See [`wiki/environments/setup.md`](../../../../environments/setup.md#commands).

## Changed

- Each Minecraft-facing module is one Gradle subproject, `mcagents-chat-{module}`, built from
  its version folder `platforms/{module}/26.3/`. Moving to the next Minecraft release replaces
  that folder, the catalog block and the version in `settings.gradle`. See
  [`wiki/environments/setup.md`](../../../../environments/setup.md#the-minecraft-version).
- Built against MCAgents core 0.8.0 and MCAgents agents 0.4.0, whose ids drop the Minecraft
  version the same way.
- The Release workflow attaches three jars, one per loader.

## Removed

- Minecraft 26.2: its subprojects, its catalog block (Fabric API 0.157.0+26.2, NeoForge
  26.2.0.90, Forge 26.2-65.1.3), and its Forge `pack.mcmeta`.
- The `-26.2` and `-26.3` artifact ids. Their published versions stay on GitHub Packages.
