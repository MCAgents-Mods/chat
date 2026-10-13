# 0.5.0

Released: 2026-10-13

Chat runs on Minecraft 26.3 as well as 26.2, on MCAgents core 0.7.0 and MCAgents agents 0.3.0.
Every loader jar is now built once per Minecraft version, so a player picks the jar for their
loader and their game.

## Upgrading

- **Pick the jar for your Minecraft version.** The jars are named
  `MCAgentsChat{Loader}-0.5.0+{minecraft}.jar`: `MCAgentsChatFabric-0.5.0+26.3.jar` for Fabric
  on 26.3, and so on. Each declares its own Minecraft version and refuses the other. Install it
  beside core's and agents' jars for the same loader and Minecraft version.
- **Core 0.7.0 and agents 0.3.0 or newer are required.**
- **The Minecraft-facing artifacts carry the Minecraft version.** `mcagents-chat-shared`
  becomes `mcagents-chat-shared-26.2` and `mcagents-chat-shared-26.3`, and the same for
  `mcagents-chat-fabric` and `-neoforge`. `mcagents-chat-api` and `mcagents-chat-common` are
  unchanged.
- **Building chat needs Gradle on Java 25.** Fabric Loom 1.18, the first for 26.3, refuses an
  older JVM. The wrapper is Gradle 9.7.1.

## Added

- **Minecraft 26.3**, on Fabric (Loader 0.19.5, Fabric API 0.162.0+26.3) and NeoForge
  26.3.0.76. The menu buttons and the chat hologram work as on 26.2.
- **One Gradle subproject per Minecraft version** for `platforms/shared` and each loader,
  `platforms/{module}/{minecraft}`, with the versions listed once in `settings.gradle`. See
  [`wiki/environments/setup.md`](../../../../environments/setup.md#one-build-per-minecraft-version).
- **Development clients per version**, on NeoForge as well as Fabric:
  `./gradlew :mcagents-chat-{loader}-{minecraft}:runClient`.

## Changed

- The jar names gain `+{minecraft}`, and the Release workflow attaches four jars, one per
  loader and Minecraft version. Chat still has no Forge jar.
- Fabric Loader 0.19.5 is the oldest the Fabric jars accept, for 26.2 as well.
