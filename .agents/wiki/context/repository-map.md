---
name: agent-wiki-context-repository-map
description: Orientation for agents working on MCAgentsClient — what exists today, what is planned, where things go, and the gotchas that bite first.
---

# Repository Map

Read this before touching anything. It says what is actually in the repository right now,
not what the design documents describe. The design lives in
[`../../../wiki/information/architecture.md`](../../../wiki/information/architecture.md).

## What exists right now

```
AGENTS.md            entry point for agents
.claude/CLAUDE.md    one import of AGENTS.md, so Claude Code reads the same file
README.md            human overview
LICENSE              MIT license, MCAgents, 2026
.github/workflows/   publish.yml — publishes the library modules and tags a release on master
                     release.yml — attaches the Fabric, NeoForge and Forge jars to the tag's GitHub release
.agents/             this repository's local instruction set, indexes, agent wiki, memory
wiki/                human documentation and versioned change logs
settings.gradle      module map and plugin repositories
build.gradle         group and version for every module
gradle.properties    project identity only — organization, repository, version
gradle/              the wrapper and libs.versions.toml
api/                 chat-api
common/              chat-common
platforms/shared/    chat-shared: build.gradle and src/
platforms/fabric/    chat-fabric: build.gradle and src/
platforms/neoforge/  chat-neoforge: build.gradle and src/
platforms/forge/     chat-forge: build.gradle and src/
```

The build works end to end: `./gradlew build` produces a loadable mod jar and
`./gradlew :chat-common:test` runs the suite. Storage, the OpenRouter client,
retrieval, and the interface are all implemented. Do not assume a source file exists
because the architecture mentions it — check. Retrieval in particular was described in
three documents for a long time before any code called `ModelClient.embed`.

## The six modules

| Path | Gradle module | Holds today | Will hold |
|---|---|---|---|
| `api/` | `chat-api` | `MCAgents` (identity, on-disk layout), `api.model` (`ApiKey`, `ChatSession`, `ChatMessage`, `ChatRole`, `ContentCategory`, `KnowledgeContent`, `ContentQuery`, `ContentAvailability`, `ModelPreferences` and `ModelCatalog`), `api.agent` (the prompt contracts: `Agent`, `PersonaStore`, `PlayerEnvironment`, `EnvironmentSource`), `api.wallet` (`ChainPlatform`, `ChainAddress`, `Wallet`, `WalletBinding`, `WalletBridge`), `api.storage` (`LocalDatabase` and the five repository interfaces), and `api.mcp` (the MCP host contracts — `McpHost`, `McpSession`, `McpServerSpec`, `McpContextRegistry`, `RepositoryCoordinate`, `McpRepositoryCloner`). Tool calling is core's. The model request types, `ModelName`/`ModelPlatform`, `ProviderRoutes` and `ChatRole` are MCAgents core's, under `io.github.mcagentsmods.core.api`. | The retrieval contracts. Every I/O method returns `CompletableFuture<T>`. |
| `common/` | `chat-common` | `ClientPaths`, `common.storage.sqlite` — the SQLite implementation of every storage contract — `common.security` — `TokenCipher`, which encrypts a stored token and is reached only by `SqliteApiKeyRepository` — `common.agent` — `ChatModelClients`, which builds chat's stack around core's OpenRouter client, `CachingModelClient`, `LegacyModelSettings`, which moves chat's old model settings into core's global model once, `PromptAssembler`, `KnowledgeRetriever`, `Vectors`, and `FilePersonaStore`/`DefaultPersona`, which own `AGENTS.md` — `common.wallet` — the MetaMask bridge, `AccountWallets`, and the cryptography they need — `common.ui`, `ChatSessions` and `ChatConversation`, what the chat hologram shows and does (a conversation's own models included), and `ProviderNotes`, the Models page's line under a provider, all tested without Minecraft — and `common.mcp` — the MCP host: discovery, the stdio and HTTP sessions, the directory watch, the git cloner, `CloneRepositoryTool`, and `McpTools`, which registers every server's tools in core's tool registry, one category per server, and calls each on the server that owns it. `OpenRouterAgent` assembles the conversation and answers through core's agent. Its `src/testFixtures/` holds `TestSigner`, which signs the way MetaMask would. | Streaming and further context injection. No Minecraft on the classpath. |
| `platforms/shared/` | `chat-shared` | `ChatRuntime` (the client start-up every loader calls, and the storage state), `client.ChatButtons` (CHAT, KEYS, MODEL, PERSONA and WALLETS on core's agents menu; the loader adds them), `client.ChatSessionsHologram` (the Chat Sessions pick list CHAT opens, NEW CHAT on its nav bar, open conversations faded), `client.ChatHologram` (one two-panel hologram per conversation, several at once, built on core's holograms from `ChatSessions` and one `ChatConversation` each), `client.ChatModelHologram` (the Chat Model page MODEL under a conversation opens: its own chat and embedding models, or the global ones), `client.PlayerAccounts`, `client.GameEnvironment` — the one place that knows which server the player is on — `client.HologramDialogs` (the name and confirm dialog holograms), `client.KeysHologram`, `client.ModelsHologram`, `client.PersonaHologram` and `client.WalletsHologram` (the KEYS, MODEL, PERSONA and WALLETS pages, on `client.HologramPage`), and `client.WalletDialogs` (add an address, connect MetaMask). | Further context injection. No loader type. |
| `platforms/fabric/` | `chat-fabric` | `MCAgentsClientMod` (the `ClientModInitializer`): adds chat's menu buttons, registers the connection events, and starts `ChatRuntime` with Fabric's game directory. `fabric.mod.json`. | Mixins. |
| `platforms/neoforge/` | `chat-neoforge` | `MCAgentsNeoForgeClient` (`@Mod`, client distribution only): adds chat's menu buttons, registers `ClientPlayerNetworkEvent.LoggingIn` and `LoggingOut`, and starts `ChatRuntime` with `FMLPaths.GAMEDIR`. `META-INF/neoforge.mods.toml`. | Nothing yet beyond parity with Fabric. |
| `platforms/forge/` | `chat-forge` | `MCAgentsForgeMod` (`@Mod`; starts `MCAgentsForgeClient` on the client distribution only), which adds chat's menu buttons, registers `ClientPlayerNetworkEvent.LoggingIn` and `LoggingOut`, and starts `ChatRuntime` with `FMLPaths.GAMEDIR`. `META-INF/mods.toml` and `pack.mcmeta`. | Nothing yet beyond parity with Fabric. |

**MCAgents core.** `api/` and `common/` compile against `io.github.mcagentsmods:core-api` and
`core-common` (`compileOnlyApi`), and `platforms/shared/` against
`core-client` (`compileOnly`) for the holograms. No chat jar carries them: the `mcagents_core` mod provides
them in game, and `fabric.mod.json` depends on it. `settings.gradle` builds a
checkout of `MCAgents-Mods/core` at `../core` (or `-Pcore-build=…`) together with chat when
one is there; without one, the published artifacts are resolved from core's GitHub Packages
repository, `https://maven.pkg.github.com/MCAgents-Mods/core`, which the root `build.gradle`
declares for the `io.github.mcagentsmods` group only. GitHub Packages wants a token even for a
public package, read from `GITHUB_ACTOR` and `GITHUB_TOKEN`: the workflow's own token on a
runner, a personal access token with `read:packages` on a machine. Tests put core and its
`StubTransport` and `StubKeySource` fixtures on their own classpath. Core publishes those
fixtures under the capability `io.github.mcagentsmods:common-test-fixtures`, taken from its
project name rather than its artifact id, so `common/` asks for that capability
by name instead of writing `testFixtures(libs.core.common)`, which finds them only in the
composite build.

**Tool calling is core's.** `core-common` brings `core-api` and the pure-Java `core-tool`: the
tool registry (`io.github.mcagentsmods.core.tool`), the agent and the menu buttons
(`core.api.agent`, `core.api.button`, `core.common.agent`). Up to chat 0.8.0 they were the
separate MCAgents agents mod (`agents-common`, `mcagents_agents`); from core 0.10.0 chat names
core alone, and a development client needs only core's jar for the same loader in its
`run/mods/`.

**Publishing.** `.github/workflows/publish.yml` runs on every push to `master` and on demand.
It reads `project-version`, stops when the `v{version}` tag is already on the remote, and
otherwise runs `./gradlew build`, then `./gradlew publish` — the library modules, `api`,
`common` and `shared`, as `io.github.mcagentsmods:chat-{module}`, to this repository's
GitHub Packages, and no loader jar — and only then pushes the tag. A merge that leaves the
version alone publishes nothing, and a failed run leaves no tag, so running the workflow again
retries the release. The build step carries the workflow's token because it reads core
from GitHub Packages, so the core version `libs.versions.toml` names must be published
before a chat release is.

**Releasing the jars.** `.github/workflows/release.yml` runs after each Publish run on
`master` and on demand. When `v{version}` exists and has no GitHub release, it builds the
tagged commit with `./gradlew assemble` and the same token, attaches
`platforms/{fabric,neoforge,forge}/build/libs/*-{version}+{minecraft}.jar`, one jar per
loader, to a new release whose notes are that version's changelog, and
deletes the run's `mod-jars` artifact afterwards.

`settings.gradle` maps the Gradle names to those directories; the directory names and the
module names deliberately differ.

The dependency chain is one-way: `api/` ← `common/` ← `platforms/shared/` ←
`platforms/fabric/`, `platforms/neoforge/` and `platforms/forge/`. Before 0.4.0 a `website/` module sat between
`common/` and `platforms/shared/` and served chat's pages through core's web console; both are
gone.

`api/` and `common/` are plain `java-library` modules, and `platforms/shared/` is
built with Loom against vanilla Minecraft alone. Their classes are **merged into the mod jar**
by the `jar` task in each loader module's `build.gradle` rather than nested as Jar-in-Jar mods —
they are not mods and have no manifest of their own. No jar carries a third-party library:
`sqlite-jdbc`, the only one, is about 12 MB of native binaries for every platform, so since 0.14.0
the game has to provide it. When it does not, `ChatRuntime` enters `StorageState.NO_SQLITE` and
the holograms say "SQLite is not installed, so chat cannot save anything." A plain sqlite-jdbc jar
in `mods/` does not count: Fabric and NeoForge skip it as not a mod.

**Only the loader modules declare `implementation`, and only for chat's own modules.** Each of
`platforms/{fabric,forge,neoforge}` implements `chat-api`, `chat-common` and the shared module.
Everything provided at runtime by someone else is `compileOnly`: core (`compileOnlyApi` in
`api/` and `common/`, `compileOnly` in `platforms/shared/`), Fabric Loader, Fabric API and Forge.
What reaches a jar is decided only by the `jar` task's merge, never by a configuration. `common/` compiles against `sqlite-jdbc` and runs its tests with it
(`testImplementation`), so published `chat-common` lists no runtime dependency. The loader
modules add Fabric Loader, Fabric API, Forge and sqlite-jdbc to `runClient` with `runtimeOnly`;
they are never published, so neither scope reaches anything else.

## Storage

`SqliteDatabase.open(Path)` returns a `CompletableFuture<LocalDatabase>` and is the only
entry point. What is worth knowing before changing it:

* **One connection, one thread.** `SqlWorker` runs every call on a single daemon thread
  named `MCAgents-database`. That is what makes SQLite access correct without a pool or a
  lock, and it is never the client thread.
* **A `DataSource`, not `DriverManager`.** JDBC's `ServiceLoader` driver lookup is not
  reliable under a mod loader's classloader; `SQLiteDataSource` does not depend on it.
* **Foreign keys are enforced explicitly.** SQLite defaults them off, and
  `chat_messages`'s `ON DELETE CASCADE` does nothing without them.
* **The schema is applied on every start** and every statement is `IF NOT EXISTS`. A schema
  change ships as a migration appended to `SqliteSchema`; the answer is never "delete the
  database", because that file is the player's whole history. A column added to a table that
  already exists cannot say `IF NOT EXISTS`, so those live in a second list guarded by a read
  of `pragma_table_info(?)`.
* **API keys are encrypted in the column, not in the interface.** With
  `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` set, `SqliteApiKeyRepository` writes an AES-GCM
  envelope under a fresh nonce; with it unset it writes the key. Either way everything above
  the repository sees the key. Because the ciphertext is randomised, **no key is ever looked
  up in SQL** — the repository scans and decrypts, and a deterministic column to search on is
  forbidden. Keys already in the clear are encrypted once at open.
* **Search text is escaped** before it reaches a `LIKE` clause, so typing `100%` searches
  for that rather than matching everything.
* **Reads of packs go through a cache.** `SqliteDatabase` wraps `SqliteContentRepository`
  in `CachingContentRepository`, so `contents()` is the caching one. The cache is never the
  source of truth — see the section below.
* **New tables are appended to `SqliteSchema`, never inserted.** That list's order is the
  migration history; `settings`, then the two wallet tables, went on the end. See
  [`../../database/schema.md`](../../database/schema.md), which is the authority on every
  rule in this section.
* **`CURRENT_TIMESTAMP` resolves to the second.** Anything sorted by it needs `rowid` as a
  final tie-break or its order is undefined between runs.

## Caching

`TtlCache` is a bounded LRU with a per-entry lifetime; `CachingContentRepository` decorates
the SQLite repository with it. What matters before changing either:

* **The database is the record.** Everything cached can be dropped at any moment without
  changing what the mod knows, which is what makes it safe to bound and to expire.
* **Writes invalidate, in the same call.** A downloaded pack is visible immediately; expiry
  is a backstop for changes made outside this instance's sight, not the mechanism.
* **Only two things are cached**: a pack by id (the largest rows, read on every question)
  and the category list (tiny, read on every render). Searches are
  deliberately **not** cached — a search key changes on nearly every keystroke and would
  evict the pack bodies.
* **Absence is never cached.** A pack missing now may be downloaded a moment later.
* **The size bound is not decoration.** This runs inside Minecraft; memory taken here is
  memory the game does not have.
* Nothing cached is written to disk. A cache file would be a second copy of player data to
  keep in step, and a second place an API key could end up.

## External MCP servers

`common.mcp` lets the mod act as an MCP **client**: it runs other people's servers and
merges their tools into one registry the agent is shown. Player-facing documentation is
[`../../../wiki/information/mcp-servers.md`](../../../wiki/information/mcp-servers.md).
What matters before changing any of it:

* **`{minecraft_dir}/MCAgents/mcps/{owner}/{repository}/` is the layout, and it is not
  negotiable.** `ClientPaths.mcpRepository` resolves it and refuses anything that escapes
  the root. `RepositoryCoordinate` has already refused traversal before that. Both checks
  stay: the values reaching them are frequently chosen by a language model, not a person.
* **Discovery never starts anything.** `McpDiscovery` reads files and returns specs;
  `WatchingMcpHost` starts them. That split is why "what is installed" is testable against
  a directory tree with no child process anywhere in the test.
* **A reload is a diff, not a restart.** `McpServerSpec.sameWiring` decides what actually
  changed, and an unchanged server keeps its live process. Restarting everything on every
  filesystem event would drop working connections constantly — servers write inside their
  own directories.
* **The watcher is not recursive and must not become so.** `WatchService` would need a key
  per directory; registering every checkout means a key per `node_modules` tree. Only the
  root and the owner directories are watched, and the two-second settle window covers the
  rest.
* **Tool names are qualified `{server}__{tool}` and capped at 64 characters**, because
  OpenAI-compatible providers reject a function name outside `[A-Za-z0-9_-]{1,64}` — and
  they reject the *whole request*, not just the offending tool. `McpContextRegistry.merge`
  is the only place that can see every tool at once, so it is where collisions are caught.
* **Stdout is the protocol on stdio.** A server logging there is a real and common bug;
  `StdioMcpSession` skips non-JSON lines rather than failing. Its stderr is drained and
  discarded — a child whose error pipe fills blocks forever, and that stream is exactly
  where a server prints the credentials it was handed.
* **`isError` is not a failed future.** A failed future means the server could not be
  reached; `isError` is the server saying the call went wrong. The model can act on the
  second and must be told about it.
* **Nothing here may fail the mod.** A server that will not start is an
  `McpServerStatus.State.FAILED` row. This is the offline-first mandate, applied to MCP.
* **Routing resolves a name; it never guesses.** Three places must agree on it —
  what `McpDiscovery` calls a server, what `McpContextRegistry` qualifies its tools as, and
  what `McpTools` strips before calling. They are edited separately, which is why
  `McpRoutingEndToEndTest` asserts the offered name is the routable one.
* **A tool call never fails a question.** An unknown tool, unparseable arguments, or a
  disconnected server all come back as an error result the model reads and can recover
  from. Core's agent runs the rounds — the player's setting, 1 unless they set
  more — and the stop is an answer, not an error.
* Tests spawn a **real child process** — `common/src/test/resources/mcp/FakeServer.java.txt`
  run through the JVM's own source launcher, so the suite needs nothing beyond the build's
  own toolchain. It misbehaves on purpose at startup, because a host that only survives
  well-behaved servers does not survive the ecosystem.

## Where versions live

`gradle.properties` carries the project's identity and nothing else:

```
git-org=MCAgents-Mods
git-repo=chat
project-version=0.13.2
```

**Every dependency coordinate is in `gradle/libs.versions.toml`**, the Gradle version
catalog. Change a platform version there, not in `gradle.properties`. Build scripts read
the project version through `providers.gradleProperty('project-version')`; the hyphenated
names cannot be dereferenced as `project.gitOrg`.

## Target platform

| Thing | Value |
|---|---|
| Minecraft | 26.3, the latest release and the only one supported |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.162.0+26.3 |
| Fabric Loom | 1.18.4 |
| NeoForge | 26.3.0.76 |
| ModDevGradle | 2.0.148 |
| Forge | 26.3-66.0.11 |
| ForgeGradle | 7.0.40 |
| MCAgents core | 0.13.0 |
| Java | 25 (Minecraft 26.x requires it) |
| Gradle | 9.7.1, via the committed wrapper |
| Group | `io.github.mcagentsmods` |
| Base package | `io.github.mcagentsmods.chat` |
| Version | `0.13.2` |

Two things about Minecraft 26.x that will waste your time if you assume otherwise:

* **There are no Yarn mappings and no intermediary.** Loom resolves Mojang's official
  mappings on its own. A `mappings` line in `dependencies` is neither needed nor correct,
  and Yarn's published versions stop at 1.21.11.
* **Nothing is remapped, so there is no `remapJar`.** The `jar` task produces the final
  artifact directly. Do not wire a task to `remapJar` — it does not run.

Fabric API and the loader are declared with plain `compileOnly` and `runtimeOnly`, not
`modImplementation`, for the same reason. Check `https://meta.fabricmc.net/v2/versions/` before changing any of
these.

## Commands

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Run the tests | `./gradlew :chat-common:test` — seconds, no Minecraft needed |
| Compile one module | `./gradlew :chat-common:build` |
| Run the client | `./gradlew :chat-fabric:runClient`, `:chat-neoforge:runClient` or `:chat-forge:runClient` |
| Produce the mod jars | `./gradlew assemble` — `MCAgentsChat{Fabric,Neoforge,Forge}-{version}+{minecraft}.jar` in `platforms/{fabric,neoforge,forge}/build/libs/` |

Tests live in `common/` because that is where the logic is and because Minecraft is not on
its classpath. Put a test there whenever you can; a behaviour that can only be checked by
launching the game will not get checked.

Always use `./gradlew`, never a system `gradle` — the wrapper pins 9.7.1 and a Gradle 8
launcher cannot evaluate `settings.gradle`. Gradle itself runs on JDK 25: Fabric Loom 1.18,
the first for 26.3, refuses an older JVM. The `foojay-resolver-convention` plugin in
`settings.gradle` downloads the Java 25 toolchain the build compiles with when it is not
installed. `runClient` needs a display and will not work headless. A 26.3 client draws
through Vulkan or OpenGL with an sRGB surface, which a virtual display (Xvfb) offers only
through a Vulkan driver such as Mesa's lavapipe; NeoForge's early loading window is OpenGL
only, so turn it off with `earlyWindowControl = false` in the run's `config/fml.toml`.

The first build downloads and processes Minecraft — several minutes and a few gigabytes.
If it fails with an out-of-memory error, raise the Gradle daemon heap in your **user**
`~/.gradle/gradle.properties` (`org.gradle.jvmargs=-Xmx2G`); the committed
`gradle.properties` is reserved for project identity.

## Generated paths — leave them alone

`build/`, `.gradle/`, `run/`, `platforms/*/build/`, and `platforms/*/run/` are
generated. Never commit them, never hand-edit them. `gradle/wrapper/gradle-wrapper.jar` is
the one binary that **is** committed, on purpose — `.gitignore` carries an explicit
exception for it.

## Player data — never in the repository

`{minecraft_dir}/MCAgents/data.db` and `{minecraft_dir}/MCAgents/AGENTS.md` live on the
player's machine. `data.db` holds OpenRouter API keys in the clear as a design decision, so
**never log its contents, never attach it to an issue, and never write a fixture copy into
this repository.**

## The Minecraft 26.x client API is not the one you remember

Every one of these changed, and each fails as "cannot find symbol" against older knowledge.
Check the class with `javap` before assuming a signature.

| What you would reach for | What 26.3 has |
|---|---|
| `GuiGraphics` | `net.minecraft.client.gui.GuiGraphicsExtractor` |
| `Screen#render(GuiGraphics, int, int, float)` | `Screen#extractRenderState(GuiGraphicsExtractor, int, int, float)`, which draws **widgets only** — the background is a separate `extractBackground` call made by the caller |
| `graphics.drawString(...)` | `graphics.text(font, …)`, `centeredText`, `textWithWordWrap` |
| `Minecraft#screen` | `Minecraft#gui.screen()`; `setScreen` is `setScreenAndShow` |
| `player.displayClientMessage(c, false)` | `player.sendSystemMessage(c)` |
| `keyPressed(int, int, int)` | `keyPressed(net.minecraft.client.input.KeyEvent)` |
| `mouseClicked(double, double, int)` | `mouseClicked(MouseButtonEvent, boolean)` |
| `ResourceLocation` | `net.minecraft.resources.Identifier` |
| `KeyBindingHelper` | `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper`, and `KeyMapping.Category` is a registered record, not a string |

The decompiled jar to inspect is
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/`.

## Reaching the mod in game

Chat's five buttons on core's agents menu, and nothing else. `G` is core's key:
it shows that menu, a core hologram, where every MCAgents mod adds buttons instead of taking a
key of its own. Each loader adds chat's buttons first thing in its client start-up, before
anything that can fail, and core guarantees its key the same way chat's own key used
to be guaranteed — a game that reaches the main menu has registered it.

**CHAT opens the chat hologram unconditionally**, in place of the menu. It does not require
the database, because requiring it is what once made this ship broken: `open()` returned
silently when storage was not ready, and a dead key is indistinguishable from a mod that never
loaded. Storage state is reported in the Conversation panel, where the player is already
looking, and NEW reopens a database that failed. KEYS, MODEL and WALLETS need the database and
say on the action bar why they did not open; PERSONA needs only the file.

Everything the player configures lives in the game — sessions on the chat hologram, and API
keys with each platform's address, models, the persona and wallets behind their menu buttons.
There is no command and no browser page, and adding either would be treating a symptom.

## Gotchas

* **Offline-first is a hard rule, not a preference.** The network may be unreachable at any
  time and everything local must keep working. See [`../../rules/repository.md`](../../rules/repository.md).
* **Never block the client thread.** No `join()`, no `get()` on a future from render or
  tick code. The game freezes and the player blames the mod.
* **`common/` must not see Minecraft.** If you reach for a `net.minecraft` import there,
  the value belongs in an `api/` contract passed in from the platform module.
* The repository root `AGENTS.md` is the agent entry point. `{minecraft_dir}/MCAgents/AGENTS.md`
  is the *player's* persona file at runtime. They are unrelated files with the same name —
  do not confuse them. The runtime one is written by `FilePersonaStore` when it is missing
  and **never rewritten afterwards**; there is one store per game session and every page
  that edits it is handed that instance, because a second store would mean a save in the
  editor and the next question disagreeing about what the persona is.
* The shared instruction set resolves through the `lxagents-agents-base` connector. The
  wiring is **not** in the repository: `.gitignore` carries both `.mcp.json` (local
  environment configuration, `env` block and all) and `mcps/` (the server checkouts). A
  fresh environment rebuilds both from the bootstrap in `AGENTS.md`. If the connector is
  still unreachable, say so and work from `.agents/` alone; do not reconstruct it, and
  never commit a copy of it.
* **Keccak-256 is not SHA3-256.** The JDK ships the latter, the difference is one padding
  byte, and substituting it recovers the wrong Ethereum address while looking entirely
  plausible. `common.wallet.Keccak256` exists for exactly this reason — do not "simplify" it
  into `MessageDigest`.
* **The wallet bridge is a listener, not a server.** It binds to `127.0.0.1` on an ephemeral
  port for one handshake and stops the moment a signature verifies. Any change that lets it
  outlive that — a fixed port, a retry loop, a cached instance — is a defect.
* **Anything a window is paged over needs a total ordering.** `LIMIT`/`OFFSET` over an
  ambiguous sort shows some rows twice and never shows others, which reads as data loss
  rather than as a sort bug.
* **An MCP server is a child process with your permissions.** The mod controls where a
  repository is cloned and what it is called, not what it does once running. `mcp_servers.json`
  is where a server's credentials would sit, which makes it as sensitive as `data.db`.
* **`git` is shelled out to, not embedded.** A Java git implementation would be megabytes
  in a jar that already carries SQLite's natives, for something the machine can nearly
  always already do. A machine without git gets a clear message instead.
* **The mod never handles a private key or a recovery phrase.** Not in a field, not in a
  parameter, not in a log. Wallet code deals in public addresses and signatures over
  messages the mod chose.
