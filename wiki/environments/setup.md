# Development Setup

## Requirements

| Tool | Version | Note |
|---|---|---|
| JDK | 25 | Gradle runs on it: Fabric Loom 1.18, which builds for Minecraft 26.3, refuses an older one. |
| Gradle | 9.7.1 | Comes from the committed wrapper. Use `./gradlew`, never a system `gradle`. |
| Git | any recent | |

## Where versions live

`gradle.properties` holds the project's identity only:

```properties
git-org=MCAgents-Mods
git-repo=chat
project-version=0.6.0
```

Every dependency coordinate lives in the Gradle version catalog at
`gradle/libs.versions.toml`:

| Entry | Value |
|---|---|
| Fabric Loader | 0.19.5 |
| Fabric Loom | 1.18.4 |
| ModDevGradle | 2.0.148 |
| ForgeGradle | 7.0.40 |
| Forge Jar-in-Jar plugin | 0.2.3 |
| Java | 25 |
| MCAgents core | 0.7.0 |
| MCAgents agents | 0.3.0 |

The entries that move with Minecraft come in one block per Minecraft version, each suffixed
with it (`fabric-api-26-2` for 26.2):

| Entry | 26.2 | 26.3 |
|---|---|---|
| Minecraft | 26.2 | 26.3 |
| Fabric API | 0.157.0+26.2 | 0.162.0+26.3 |
| NeoForge | 26.2.0.90 | 26.3.0.76 |
| Forge | 26.2-65.1.3 | 26.3-66.0.11 |

Change a platform version there, not in `gradle.properties`. Keep the Minecraft and loader
versions the same as core's own catalog: a mod built for a different Minecraft than core's
cannot load beside it.

Minecraft 26.x has no Yarn mappings and no intermediary — Fabric Loom resolves Mojang's
official mappings on its own, so there is no `mappings` line to add and no `remapJar` task
in the build. Check `https://meta.fabricmc.net/v2/versions/game` before changing the
Minecraft version.

## One build per Minecraft version

`platforms/shared` and the three loader modules compile against Minecraft, so each is built
once for every Minecraft version, 26.2 and 26.3, listed once in `settings.gradle`. Each is a
folder of shared sources with one Gradle subproject per version, `platforms/{module}/{minecraft}`, named
`mcagents-chat-{module}-{minecraft}`; that name is also its artifact id. The subproject
compiles the module's shared `src/` together with its own `src/`, which holds only what
differs for that version. `platforms/minecraft.gradle`, which every version subproject
applies, takes the version from the folder's name, finds its coordinates in the catalog, and
names the jar `MCAgentsChat{Module}-{version}+{minecraft}.jar`. The loader metadata is
expanded per version, so each jar declares its own Minecraft version and no other. Forge's
`pack.mcmeta` lives in each Forge version folder, because Forge does not make one for a mod
and its pack format moves with Minecraft (107 for 26.2, 121 for 26.3). `api` and `common`
name no Minecraft type and are built once.

## MCAgents core and agents

Chat compiles against MCAgents core and MCAgents agents and carries neither. Clone
`MCAgents-Mods/core` and `MCAgents-Mods/agents` beside this repository — `../core` and
`../agents` — and `settings.gradle` builds all three together, so chat compiles and tests
against their source. Pass `-Pcore-build=/path/to/core` or `-Pagents-build=/path/to/agents`
for another location. Without a checkout, Gradle resolves the published
`io.github.mcagentsmods:mcagents-core-{api,common}`, `mcagents-core-client-{minecraft}` and `mcagents-agents-{tool,api,common}` at
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
same loader and Minecraft version (`MCAgentsCoreFabric-{version}+{minecraft}.jar` and
`MCAgentsAgentsFabric-{version}+{minecraft}.jar`, or the `Neoforge` or `Forge` ones, from each
checkout's `platforms/{loader}/{minecraft}/build/libs/` or each repository's Releases page)
into that version subproject's `run/mods/` first, or the loader refuses to start chat for the
missing `mcagents_core` or `mcagents_agents`.

## Commands

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Run the tests | `./gradlew :mcagents-chat-common:test` |
| Build one module | `./gradlew :mcagents-chat-common:build` |
| Run the client on Fabric | `./gradlew :mcagents-chat-fabric-26.2:runClient` |
| Run the client on NeoForge | `./gradlew :mcagents-chat-neoforge-26.2:runClient` |
| Run the client on Forge | `./gradlew :mcagents-chat-forge-26.2:runClient` |
| Run a 26.3 client | The same with `-26.3`, such as `./gradlew :mcagents-chat-fabric-26.3:runClient` |
| Produce the mod jars | `./gradlew assemble` |

Tests live in `common/` and run in seconds without Minecraft — that is the payoff of
keeping the logic out of the platform module. Run them before you launch the game.

The mod jars land in `platforms/{fabric,neoforge,forge}/{minecraft}/build/libs/`, one per
loader and Minecraft version, such as
`platforms/fabric/26.2/build/libs/MCAgentsChatFabric-{version}+26.2.jar`, with
`MCAgentsChatNeoforge-{version}+26.2.jar` and `MCAgentsChatForge-{version}+26.2.jar` beside it
under `neoforge` and `forge`. Each carries the classes of `api/`, `common/` and
`platforms/shared/`, merged into it rather than nested as separate mods, and nests
`sqlite-jdbc` its loader's way: Loom's `include` for Fabric, Jar-in-Jar for NeoForge and
Forge. Forge's Jar-in-Jar plugin builds the jar a second time with `sqlite-jdbc` nested, so
the first, which could not open the database, goes to `build/slim/` instead.
`runClient` opens a real Minecraft window and needs a display; it will not work over a
headless connection. Minecraft 26.3 draws through Vulkan or OpenGL with an sRGB surface. On a
virtual display (Xvfb), which offers no sRGB OpenGL surface, a 26.3 client needs a Vulkan
driver such as Mesa's software one (lavapipe), and NeoForge's OpenGL-only early loading
window must be off: `earlyWindowControl = false` in the run's `config/fml.toml`.

The first build downloads and processes Minecraft. Expect it to take several minutes and a
few gigabytes of disk. Subsequent builds reuse the Loom cache. If it runs out of memory,
raise the daemon heap in your own `~/.gradle/gradle.properties`
(`org.gradle.jvmargs=-Xmx2G`) — the committed `gradle.properties` is reserved for project
identity.

## Publishing a release

Releases publish themselves. `.github/workflows/publish.yml` runs whenever `master` moves:
when `project-version` in `gradle.properties` has no `v{version}` tag yet, it builds and tests
chat, publishes every module to this repository's GitHub Packages as
`io.github.mcagentsmods:mcagents-chat-{module}` (`mcagents-chat-{module}-{minecraft}` for
shared and the loaders), and then tags the commit. When the tag already
exists it does nothing, so only a merge that raises the version publishes. If a run fails, no
tag is made; fix the cause and start the workflow again from the Actions tab
(**Run workflow**).

The build in that run reads MCAgents core from core's GitHub Packages, so the core version
named in `gradle/libs.versions.toml` has to be published before chat's release is merged.

The jars a player installs are attached to the repository's **Releases** page, one GitHub
release per version. `.github/workflows/release.yml` runs each time a Publish run on `master`
finishes: when the `v{version}` tag exists and has no GitHub release yet, it checks out the
tag, runs `./gradlew assemble` with the same token, and creates the release with the mod jars
attached, one per loader and Minecraft version — `MCAgentsChatFabric-{version}+{minecraft}.jar`,
`MCAgentsChatNeoforge-{version}+{minecraft}.jar` and `MCAgentsChatForge-{version}+{minecraft}.jar`
— and that version's `wiki/logs/{Major}/{Minor}/{Patch}/CHANGELOG.md` as its notes. It then deletes the
copy of the jars the run kept in Actions.
A release that failed is made by the next merge to `master`; **Run workflow** on the Release
workflow's page does the same by hand, and its `tag` input releases an earlier tag.

## Runtime files

Running the client creates a game directory under that version subproject, such as
`platforms/fabric/26.2/run/`. The mod's own data goes to its `MCAgents/` folder,
`platforms/fabric/26.2/run/MCAgents/`:

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
./gradlew :mcagents-chat-fabric-26.2:runClient
```

Anything works as a value — a passphrase, a random string — because it is hashed to a
256-bit key. Three things are worth knowing before you set one:

* **Keys already stored are encrypted on the next start**, once, in place. Nothing is lost.
* **Keep the value.** Change it or lose it and the mod cannot read those keys back. It does
  not delete them — put the same value back and they return — but nothing else can recover
  them.
* **Leaving it unset is supported.** Keys are then stored as you pasted them, which is what
  the mod did before this existed. The key screen says which of the two you are in.

It protects a database file that leaves the machine — a backup, a synced folder, a bug
report. It is not a defence against something already running on your machine, which can read
the variable as easily as the mod does.

## Working offline

The mod is required to work without network access, so test it that way. With no internet:
the game starts, the chat hologram opens, stored sessions and Knowledge Packs are
readable. If any of that fails, it is a bug.

Note that the *first* Gradle build does need network to fetch Minecraft and the
dependencies. Offline is a runtime requirement, not a build requirement.
