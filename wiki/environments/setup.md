# Development Setup

## Requirements

| Tool | Version | Note |
|---|---|---|
| JDK | 25 | Minecraft 26.2 requires it. Gradle's toolchain resolver can download it for you. |
| Gradle | 8.14.3 or newer | The wrapper in the repository is authoritative — use `./gradlew`. |
| Git | any recent | |

Target platform, fixed in `gradle.properties`:

| Property | Value |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |

Minecraft 26.x has no Yarn mappings and no intermediary. Fabric Loom resolves Mojang's
official mappings on its own; there is no `mappings` line to add. Check
`https://meta.fabricmc.net/v2/versions/game` before changing the Minecraft version.

## Commands

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Build one module | `./gradlew :mcagents-fabric-common:build` |
| Run the client | `./gradlew :mcagents-fabric:runClient` |
| Produce the mod jar | `./gradlew :mcagents-fabric:build` |

The mod jar lands in `platforms/fabric/build/libs/`. `runClient` opens a real Minecraft
window and needs a display; it will not work over a headless connection.

The first build downloads Minecraft and decompiles it. Expect it to take several minutes
and a few gigabytes of disk. Subsequent builds reuse the Loom cache.

## Runtime files

Running the client creates a game directory under `platforms/fabric/run/`. The mod's own
data goes to `platforms/fabric/run/MCAgents/`:

| File | Contents |
|---|---|
| `data.db` | SQLite — chat sessions, messages, API keys, downloaded Knowledge Packs |
| `AGENTS.md` | The agent persona and instructions, editable by the player |

Both are player data. `run/` is generated and is not committed. **`data.db` stores
OpenRouter API keys in the clear — never attach it to an issue and never copy it into the
repository.**

## Working offline

The mod is required to work without network access, so test it that way. With no internet:
the game starts, the chat screen opens, stored sessions and downloaded Knowledge Packs are
readable, and the content browser reports that the catalog is unreachable without breaking
anything else. If any of that fails, it is a bug.

Note that the *first* Gradle build does need network to fetch Minecraft and the
dependencies. Offline is a runtime requirement, not a build requirement.
