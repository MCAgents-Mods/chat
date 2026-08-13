# Development Setup

## Requirements

| Tool | Version | Note |
|---|---|---|
| JDK | 21 or newer to launch Gradle; 25 to compile | The `foojay-resolver-convention` plugin downloads JDK 25 if you do not have it. |
| Gradle | 9.7.0 | Comes from the committed wrapper. Use `./gradlew`, never a system `gradle`. |
| Git | any recent | |

## Where versions live

`gradle.properties` holds the project's identity only:

```properties
git-org=MCAgents
git-repo=client-fabric
project-version=0.0.0
```

Every dependency coordinate lives in the Gradle version catalog at
`gradle/libs.versions.toml`:

| Entry | Value |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |
| Fabric Loom | 1.17.19 |
| Java | 25 |

Change a platform version there, not in `gradle.properties`.

Minecraft 26.x has no Yarn mappings and no intermediary — Fabric Loom resolves Mojang's
official mappings on its own, so there is no `mappings` line to add and no `remapJar` task
in the build. Check `https://meta.fabricmc.net/v2/versions/game` before changing the
Minecraft version.

## Commands

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Build one module | `./gradlew :mcagents-fabric-common:build` |
| Run the client | `./gradlew :mcagents-fabric:runClient` |
| Produce the mod jar | `./gradlew :mcagents-fabric:build` |

The mod jar lands in `platforms/fabric/build/libs/`. It carries the classes of all three
modules — `api/` and `common/` are merged into it rather than nested as separate mods.
`runClient` opens a real Minecraft window and needs a display; it will not work over a
headless connection.

The first build downloads and processes Minecraft. Expect it to take several minutes and a
few gigabytes of disk. Subsequent builds reuse the Loom cache. If it runs out of memory,
raise the daemon heap in your own `~/.gradle/gradle.properties`
(`org.gradle.jvmargs=-Xmx2G`) — the committed `gradle.properties` is reserved for project
identity.

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
