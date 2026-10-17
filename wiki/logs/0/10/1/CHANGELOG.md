# 0.10.1

Released: 2026-10-17

A build change only: no Gradle file in chat declares `implementation` any more. The loader
jars carry exactly what 0.10.0's did.

## Upgrading

- **Players: nothing to do.** The `v0.10.1` release carries
  `MCAgentsChatForge-0.10.1+26.3.jar`, `MCAgentsChatFabric-0.10.1+26.3.jar` and
  `MCAgentsChatNeoforge-0.10.1+26.3.jar`, with the same contents as 0.10.0's; replacing the jar
  is optional. It still needs core 0.11.0 or newer beside it.
- **Building against `chat-common`:** it no longer lists sqlite-jdbc as a runtime dependency.
  Chat's own jars nest it; code that runs `chat-common` outside them adds sqlite-jdbc itself.

## Changed

- **sqlite-jdbc is `compileOnly` in `common/`.** Each loader jar nests it (Loom's `include` on
  Fabric, Jar-in-Jar on NeoForge and Forge), so the published `chat-common` stops asking a
  consumer for it. The tests still run with it.
- **The loader modules compile with `compileOnly`.** Fabric, Forge and NeoForge declare chat's
  own modules and their loader (Fabric Loader and Fabric API, or Forge) as `compileOnly`, each
  with a `runtimeOnly` twin so the development client still finds them, sqlite-jdbc included.
  What a jar carries is decided only by its `jar` task and by `include` or `jarJar`, never by a
  configuration.
