# 0.8.0

Released: 2026-10-16

Chat's artifact ids drop the repeated `mcagents-`, and only the library modules are published
to GitHub Packages; the loader jars are on each version's GitHub release only. Chat now runs on
MCAgents core 0.9.0 and MCAgents agents 0.5.0, whose ids changed the same way.

## Upgrading

- **Install core 0.9.0 and agents 0.5.0 or newer** beside it, for the same loader. The jar asks
  for them.
- **Ask for the new coordinates.** The group stays `io.github.mcagentsmods`; each artifact id
  loses its `mcagents-` prefix:

  | Before | From 0.8.0 |
  |---|---|
  | `mcagents-chat-api` | `chat-api` |
  | `mcagents-chat-common` | `chat-common` |
  | `mcagents-chat-shared` | `chat-shared` |

  See [`wiki/information/architecture.md`](../../../../information/architecture.md).
- **Take the loader jars from the GitHub release.** `mcagents-chat-fabric`, `-neoforge` and
  `-forge` have no 0.8.0 on GitHub Packages. The Release workflow attaches
  `MCAgentsChat{Loader}-0.8.0+26.3.jar` to the `v0.8.0` release, as it did for 0.7.0.
- **Development clients are renamed** with the projects: `./gradlew :chat-{loader}:runClient`.
  See [`wiki/environments/setup.md`](../../../../environments/setup.md#commands).

## Changed

- Published artifact ids are `chat-{module}`, and the Gradle projects carry the same names.
- The Publish workflow sends `api`, `common` and `shared` to GitHub Packages, and no loader
  jar.
- Built against MCAgents core 0.9.0 and MCAgents agents 0.5.0, read as `core-api`,
  `core-common`, `core-client` and `agents-common`.

## Removed

- The Fabric, NeoForge and Forge publications. The loader subprojects still build their jars.
