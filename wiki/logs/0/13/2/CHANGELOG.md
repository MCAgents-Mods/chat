# 0.13.2

Released: 2026-10-18

A build change only: each platform module is one folder with one `build.gradle`, and the
version catalog names the Minecraft version once. The loader jars carry exactly what 0.13.1's
did.

## Upgrading

- **Players: nothing to do.** The `v0.13.2` release carries
  `MCAgentsChatFabric-0.13.2+26.3.jar`, `MCAgentsChatNeoforge-0.13.2+26.3.jar` and
  `MCAgentsChatForge-0.13.2+26.3.jar`, with the same contents as 0.13.1's; replacing the jar is
  optional. It still needs core 0.13.0 or newer beside it.
- **Nothing to change in a build that depends on chat.** Artifact ids, the published modules
  and their dependencies are unchanged.
- **In a checkout,** the jars and the development game directory move up one folder:
  `platforms/{loader}/build/libs/` and `platforms/{loader}/run/` in place of
  `platforms/{loader}/26.3/…`. Move a `run/` folder you want to keep.

## Changed

- **One `build.gradle` per platform module.** `platforms/shared`, `fabric`, `neoforge` and
  `forge` each hold their whole build in their own `build.gradle` and their sources in their
  own `src/`. The `26.3/` subprojects, the `shared.gradle`, `fabric.gradle`, `neoforge.gradle`
  and `forge.gradle` files and `platforms/minecraft.gradle` are gone; Forge's `pack.mcmeta`
  moved to `platforms/forge/src/main/resources/`. `settings.gradle` points `chat-{module}` at
  `platforms/{module}`.
- **The catalog names the Minecraft version once.** Its `minecraft`, `fabric-api`, `neoforge`
  and `forge` entries lose their `-26-3` suffix, and the builds read `libs.versions.minecraft`
  for the jar names and the mod metadata; `settings.gradle` no longer names it. `core-client`
  joins `core-api` and `core-common` in the catalog.
- **The Release workflow** takes each loader's jar from `platforms/{loader}/build/libs/`, and
  still finds the jars of a tag built before 0.13.2 when one is released by hand.
