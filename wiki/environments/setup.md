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
git-org=MCAgents-Mods
git-repo=chat
project-version=0.3.0
```

Every dependency coordinate lives in the Gradle version catalog at
`gradle/libs.versions.toml`:

| Entry | Value |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |
| Fabric Loom | 1.17.19 |
| NeoForge | 26.2.0.90 |
| ModDevGradle | 2.0.148 |
| Java | 25 |

Change a platform version there, not in `gradle.properties`.

Minecraft 26.x has no Yarn mappings and no intermediary — Fabric Loom resolves Mojang's
official mappings on its own, so there is no `mappings` line to add and no `remapJar` task
in the build. Check `https://meta.fabricmc.net/v2/versions/game` before changing the
Minecraft version.

## MCAgents core and agents

Chat compiles against MCAgents core and MCAgents agents and carries neither. Clone
`MCAgents-Mods/core` and `MCAgents-Mods/agents` beside this repository — `../core` and
`../agents` — and `settings.gradle` builds all three together, so chat compiles and tests
against their source. Pass `-Pcore-build=/path/to/core` or `-Pagents-build=/path/to/agents`
for another location. Without a checkout, Gradle resolves the published
`io.github.mcagentsmods:mcagents-core-{api,common}` and `mcagents-agents-{tool,api,common}` at
the versions in `gradle/libs.versions.toml`, from each repository's GitHub Packages registry.
GitHub Packages asks for a token even though both are public, so export a personal access
token with the `read:packages` scope first:

```bash
export GITHUB_ACTOR=your-github-login
export GITHUB_TOKEN=ghp_your_token
./gradlew build
```

A build with both checkouts beside it needs neither.

`runClient` does not put core or agents in the development game. Copy each one's jar for the
same loader (`platforms/fabric/build/libs/MCAgentsCoreFabric-*.jar` and
`MCAgentsAgentsFabric-*.jar`, or the `Neoforge` ones, in each checkout) into that loader's
`run/mods/` first, or the loader refuses to start chat for the missing `mcagents_core` or
`mcagents_agents`.

## Commands

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Run the tests | `./gradlew :mcagents-chat-common:test` |
| Build one module | `./gradlew :mcagents-chat-common:build` |
| Run the client on Fabric | `./gradlew :mcagents-chat-fabric:runClient` |
| Run the client on NeoForge | `./gradlew :mcagents-chat-neoforge:runClient` |
| Produce the mod jars | `./gradlew :mcagents-chat-fabric:build :mcagents-chat-neoforge:build` |

Tests live in `common/` and run in seconds without Minecraft — that is the payoff of
keeping the logic out of the platform module. Run them before you launch the game.

The mod jars land in `platforms/fabric/build/libs/` as `MCAgentsChatFabric-{version}.jar` and
in `platforms/neoforge/build/libs/` as `MCAgentsChatNeoforge-{version}.jar`. Each carries the
classes of `api/`, `common/`, `website/` and `platforms/shared/`, merged into it rather than
nested as separate mods, and nests `sqlite-jdbc` its loader's way.
`runClient` opens a real Minecraft window and needs a display; it will not work over a
headless connection.

The first build downloads and processes Minecraft. Expect it to take several minutes and a
few gigabytes of disk. Subsequent builds reuse the Loom cache. If it runs out of memory,
raise the daemon heap in your own `~/.gradle/gradle.properties`
(`org.gradle.jvmargs=-Xmx2G`) — the committed `gradle.properties` is reserved for project
identity.

## Publishing a release

Releases publish themselves. `.github/workflows/publish.yml` runs whenever `master` moves:
when `project-version` in `gradle.properties` has no `v{version}` tag yet, it builds and tests
chat, publishes every module to this repository's GitHub Packages as
`io.github.mcagentsmods:mcagents-chat-{module}`, and then tags the commit. When the tag already
exists it does nothing, so only a merge that raises the version publishes. If a run fails, no
tag is made; fix the cause and start the workflow again from the Actions tab
(**Run workflow**).

The build in that run reads MCAgents core from core's GitHub Packages, so the core version
named in `gradle/libs.versions.toml` has to be published before chat's release is merged.

## Runtime files

Running the client creates a game directory under `platforms/fabric/run/`. The mod's own
data goes to `platforms/fabric/run/MCAgents/`:

| File | Contents |
|---|---|
| `data.db` | SQLite — chat sessions, messages, API keys, Knowledge Packs |
| `AGENTS.md` | The agent persona and instructions, editable by the player |

Both are player data. `run/` is generated and is not committed. **`data.db` holds API keys —
never attach it to an issue and never copy it into the repository**, encrypted or not.

## Encrypting stored API keys

Set `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` in the environment the game runs in, and the
mod encrypts every stored key with it:

```bash
export MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY="$(openssl rand -base64 32)"
./gradlew :mcagents-chat-fabric:runClient
```

Anything works as a value — a passphrase, a random string — because it is hashed to a
256-bit key. Three things are worth knowing before you set one:

* **Keys already stored are encrypted on the next start**, once, in place. Nothing is lost.
* **Keep the value.** Change it or lose it and the mod cannot read those keys back. It does
  not delete them — put the same value back and they return — but nothing else can recover
  them.
* **Leaving it unset is supported.** Keys are then stored as you pasted them, which is what
  the mod did before this existed. The console's key page says which of the two you are in.

It protects a database file that leaves the machine — a backup, a synced folder, a bug
report. It is not a defence against something already running on your machine, which can read
the variable as easily as the mod does.

## Working offline

The mod is required to work without network access, so test it that way. With no internet:
the game starts, the chat screen opens, stored sessions and Knowledge Packs are
readable. If any of that fails, it is a bug.

Note that the *first* Gradle build does need network to fetch Minecraft and the
dependencies. Offline is a runtime requirement, not a build requirement.
