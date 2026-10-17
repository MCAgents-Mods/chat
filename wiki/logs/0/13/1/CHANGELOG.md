# 0.13.1

Released: 2026-10-17

A build change only: each loader module now declares chat's own modules with `implementation`,
and what the game or core provides stays `compileOnly`. The loader jars carry exactly what
0.13.0's did.

## Upgrading

- **Players: nothing to do.** The `v0.13.1` release carries
  `MCAgentsChatForge-0.13.1+26.3.jar`, `MCAgentsChatFabric-0.13.1+26.3.jar` and
  `MCAgentsChatNeoforge-0.13.1+26.3.jar`, with the same contents as 0.13.0's; replacing the jar
  is optional. It still needs core 0.13.0 or newer beside it.
- **Nothing to change in a build.** No API changes, and the published modules' dependencies are
  unchanged.

## Changed

- **The loader modules implement chat's own modules.** Fabric, Forge and NeoForge declare
  `chat-api`, `chat-common` and the shared module with `implementation`, since each is part of
  the mod the jar ships, in place of the `compileOnly` and `runtimeOnly` pair 0.10.1 introduced.
  Fabric Loader, Fabric API and Forge stay `compileOnly`, because the game provides them, with
  the `runtimeOnly` line the development client needs; core stays `compileOnlyApi` and
  `compileOnly` where chat's code uses it, because the `mcagents_core` mod provides it. What a
  jar carries is still decided only by its `jar` task and by `include` or `jarJar`. The loader
  modules are never published, so neither scope reaches anything else.
