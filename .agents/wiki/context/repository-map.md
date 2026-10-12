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
.github/workflows/   publish.yml — publishes every module and tags a release on master
.agents/             this repository's local instruction set, indexes, agent wiki, memory
wiki/                human documentation and versioned change logs
settings.gradle      module map and plugin repositories
build.gradle         group and version for every module
gradle.properties    project identity only — organization, repository, version
gradle/              the wrapper and libs.versions.toml
api/                 mcagents-chat-api
common/              mcagents-chat-common
platforms/shared/    mcagents-chat-shared
platforms/fabric/    mcagents-chat-fabric
platforms/neoforge/  mcagents-chat-neoforge
```

The build works end to end: `./gradlew build` produces a loadable mod jar and
`./gradlew :mcagents-chat-common:test` runs the suite. Storage, the OpenRouter client,
retrieval, and the interface are all implemented. Do not assume a source file exists
because the architecture mentions it — check. Retrieval in particular was described in
three documents for a long time before any code called `ModelClient.embed`.

## The six modules

| Path | Gradle module | Holds today | Will hold |
|---|---|---|---|
| `api/` | `mcagents-chat-api` | `MCAgents` (identity, on-disk layout), `api.model` (`ApiKey`, `ChatSession`, `ChatMessage`, `ChatRole`, `ContentCategory`, `KnowledgeContent`, `ContentQuery`, `ContentAvailability`, `ModelPreferences` and `ModelCatalog`), `api.agent` (the prompt contracts: `Agent`, `PersonaStore`, `PlayerEnvironment`, `EnvironmentSource`), `api.wallet` (`ChainPlatform`, `ChainAddress`, `Wallet`, `WalletBinding`, `WalletBridge`), `api.storage` (`LocalDatabase` and the five repository interfaces), and `api.mcp` (the MCP host contracts — `McpHost`, `McpSession`, `McpServerSpec`, `McpContextRegistry`, `RepositoryCoordinate`, `McpRepositoryCloner`), and the tool-calling contracts in `api.agent` (`ToolBroker`, `ToolOutcome`). The model request types, `ModelName`/`ModelPlatform`, `ProviderRoutes` and `ChatRole` are MCAgents core's, under `io.github.mcagentsmods.core.api`. | The retrieval contracts. Every I/O method returns `CompletableFuture<T>`. |
| `common/` | `mcagents-chat-common` | `ClientPaths`, `common.storage.sqlite` — the SQLite implementation of every storage contract — `common.security` — `TokenCipher`, which encrypts a stored token and is reached only by `SqliteApiKeyRepository` — `common.agent` — `ChatModelClients`, which builds chat's stack around core's OpenRouter client, `CachingModelClient`, `StoredProviderRoutes`, `PromptAssembler`, `KnowledgeRetriever`, `Vectors`, and `FilePersonaStore`/`DefaultPersona`, which own `AGENTS.md` — `common.wallet` — the MetaMask bridge, `AccountWallets`, and the cryptography they need — `common.ui`, the screen arithmetic that can be tested without Minecraft, and `common.mcp` — the MCP host: discovery, the stdio and HTTP sessions, the directory watch, the git cloner, and `McpToolRouter`, which routes a model's tool call to the server that owns the tool. Its `src/testFixtures/` holds the doubles `website/` tests against too. | Streaming and further context injection. No Minecraft on the classpath. |
| `website/` | `mcagents-chat-website` | `ChatWebModule` — chat's pages, registered with MCAgents core's `CoreWebRegistry`, which owns the server, the token and the page shell — `WebApi`, `WebJson`, `ApiKeyHandles`, and every page, script and style under `resources/mcagents/web/`. Everything chat shows a browser, and nothing else. | More pages. No Minecraft on the classpath, and nothing in `common/` may depend back on it. |
| `platforms/shared/` | `mcagents-chat-shared` | `ChatRuntime` (the client start-up every loader calls, and the storage state), `client.MCAgentsKeyMappings` (builds the key; the loader registers it), `client.PlayerAccounts`, `client.WebConsoleHolder` (registers chat's pages with core's console), `client.GameEnvironment` — the one place that knows which server the player is on — and `client.gui` — the chat screen, its panels, the persona editor, the model settings screen, the wallet screens, and the console screen. | Further context injection, the content browser. No loader type. |
| `platforms/fabric/` | `mcagents-chat-fabric` | `MCAgentsClientMod` (the `ClientModInitializer`): registers the key category and key, the end-of-tick and connection events, and starts `ChatRuntime` with Fabric's game directory. `fabric.mod.json`. | Mixins. |
| `platforms/neoforge/` | `mcagents-chat-neoforge` | `MCAgentsNeoForgeClient` (`@Mod`, client distribution only): registers the key category and key on `RegisterKeyMappingsEvent`, `ClientTickEvent.Post`, `ClientPlayerNetworkEvent.LoggingIn` and `LoggingOut`, and starts `ChatRuntime` with `FMLPaths.GAMEDIR`. `META-INF/neoforge.mods.toml`; `sqlite-jdbc` nested with `jarJar`. | Nothing yet beyond parity with Fabric. |

**MCAgents core.** `api/` and `common/` compile against `io.github.mcagentsmods:mcagents-core-api` and
`mcagents-core-common` (`compileOnlyApi`), and no chat jar carries them: the `mcagents_core` mod
provides them in game, and `fabric.mod.json` depends on it. `settings.gradle` builds a
checkout of `MCAgents-Mods/core` at `../core` (or `-Pcore-build=…`) together with chat when
one is there; without one, the published artifacts are resolved from core's GitHub Packages
repository, `https://maven.pkg.github.com/MCAgents-Mods/core`, which the root `build.gradle`
declares for the `io.github.mcagentsmods` group only. GitHub Packages wants a token even for a
public package, read from `GITHUB_ACTOR` and `GITHUB_TOKEN`: the workflow's own token on a
runner, a personal access token with `read:packages` on a machine. Tests put core and its
`StubTransport` and `StubKeySource` fixtures on their own classpath. Core publishes those
fixtures under the capability `io.github.mcagentsmods:common-test-fixtures`, taken from its
project name rather than its artifact id, so `common/` and `website/` ask for that capability
by name instead of writing `testFixtures(libs.core.common)`, which finds them only in the
composite build.

**Publishing.** `.github/workflows/publish.yml` runs on every push to `master` and on demand.
It reads `project-version`, stops when the `v{version}` tag is already on the remote, and
otherwise runs `./gradlew build`, then `./gradlew publish` — all six modules, as
`io.github.mcagentsmods:mcagents-chat-{module}`, to this repository's GitHub Packages — and
only then pushes the tag. A merge that leaves the version alone publishes nothing, and a
failed run leaves no tag, so running the workflow again retries the release. The build step
carries the workflow's token because it reads core from GitHub Packages, so the core version
`libs.versions.toml` names must be published before a chat release is.

`settings.gradle` maps the Gradle names to those directories; the directory names and the
module names deliberately differ.

The dependency chain is one-way: `api/` ← `common/` ← `website/` ← `platforms/shared/` ←
`platforms/fabric/` and `platforms/neoforge/`. The
`WebConsole` contract stays in `api/` even though only `website/` implements it — `api/` is
where contracts go.

`api/`, `common/` and `website/` are plain `java-library` modules, and `platforms/shared/` is
built with Loom against vanilla Minecraft alone. Their classes are **merged into the mod jar**
by the `jar` task in each loader module's `build.gradle` rather than nested as Jar-in-Jar mods —
they are not mods and have no manifest of their own. Third-party
runtime libraries go the other way: `sqlite-jdbc` is nested with Loom's `include` at
`META-INF/jars/` in the Fabric jar, and with `jarJar` at `META-INF/jarjar/` in the NeoForge jar. It carries native binaries for every platform, so
it alone is about 12 MB of the built jar.

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
  what `McpToolRouter` strips before calling. They are edited separately, which is why
  `McpRoutingEndToEndTest` asserts the offered name is the routable one.
* **A tool call never fails a question.** An unknown tool, unparseable arguments, or a
  disconnected server all come back as a `ToolOutcome` the model reads and can recover
  from. `OpenRouterAgent` then loops — bounded at `MAX_TOOL_ROUNDS`, and the stop is an
  answer, not an error.
* Tests spawn a **real child process** — `common/src/test/resources/mcp/FakeServer.java.txt`
  run through the JVM's own source launcher, so the suite needs nothing beyond the build's
  own toolchain. It misbehaves on purpose at startup, because a host that only survives
  well-behaved servers does not survive the ecosystem.

## Where versions live

`gradle.properties` carries the project's identity and nothing else:

```
git-org=MCAgents-Mods
git-repo=chat
project-version=0.2.0
```

**Every dependency coordinate is in `gradle/libs.versions.toml`**, the Gradle version
catalog. Change a platform version there, not in `gradle.properties`. Build scripts read
the project version through `providers.gradleProperty('project-version')`; the hyphenated
names cannot be dereferenced as `project.gitOrg`.

## Target platform

| Thing | Value |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |
| Fabric Loom | 1.17.19 |
| NeoForge | 26.2.0.90 |
| ModDevGradle | 2.0.148 |
| Java | 25 (Minecraft 26.2 requires it) |
| Gradle | 9.7.0, via the committed wrapper |
| Group | `io.github.mcagentsmods` |
| Base package | `io.github.mcagentsmods.chat` |
| Version | `0.2.0` |

Two things about Minecraft 26.x that will waste your time if you assume otherwise:

* **There are no Yarn mappings and no intermediary.** Loom resolves Mojang's official
  mappings on its own. A `mappings` line in `dependencies` is neither needed nor correct,
  and Yarn's published versions stop at 1.21.11.
* **Nothing is remapped, so there is no `remapJar`.** The `jar` task produces the final
  artifact directly. Do not wire a task to `remapJar` — it does not run.

Fabric API and the loader are declared with plain `implementation`, not `modImplementation`,
for the same reason. Check `https://meta.fabricmc.net/v2/versions/` before changing any of
these.

## Commands

| Goal | Command |
|---|---|
| Build everything | `./gradlew build` |
| Run the tests | `./gradlew :mcagents-chat-common:test` — seconds, no Minecraft needed |
| Compile one module | `./gradlew :mcagents-chat-common:build` |
| Run the client | `./gradlew :mcagents-chat-fabric:runClient` or `:mcagents-chat-neoforge:runClient` |
| Produce the mod jars | `./gradlew build` — output in `platforms/fabric/build/libs/` and `platforms/neoforge/build/libs/` |

Tests live in `common/` because that is where the logic is and because Minecraft is not on
its classpath. Put a test there whenever you can; a behaviour that can only be checked by
launching the game will not get checked.

Always use `./gradlew`, never a system `gradle` — the wrapper pins 9.7.0 and a Gradle 8
launcher cannot evaluate `settings.gradle`. Gradle itself runs on JDK 21 and compiles
through a Java 25 toolchain; the `foojay-resolver-convention` plugin in `settings.gradle`
downloads JDK 25 when it is not installed. `runClient` needs a display and will not work
headless.

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

| What you would reach for | What 26.2 has |
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
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/`.

## Reaching the mod in game

The `G` key, and nothing else. That is enough, and it is provably enough:

> `KeyMappingRegistryImpl.registerKeyMapping` throws `IllegalStateException` if
> `Minecraft.options` already exists. An exception out of `onInitializeClient` crashes
> startup with a Fabric error screen. **So a game that reaches the main menu has already
> proven its key mappings registered** — a key that appears to do nothing is never a
> registration problem.
>
> NeoForge gets the same guarantee another way: it offers key registration only on
> `RegisterKeyMappingsEvent`, which fires during start-up, before `Options` is built.

**The screen therefore opens unconditionally.** It does not require the database, because
requiring it is what made this ship broken: `open()` returned silently when storage was not
ready, and a dead key is indistinguishable from a mod that never loaded. Storage state is
reported *inside* the screen, with a Retry button, where the player is already looking.

Everything the player configures lives in the interface — sessions, models (gear), API keys
(key icon), the web console (globe), and wallets (bottom-left). There is no command, and
adding one would be treating a symptom.

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
  and **never rewritten afterwards**; there is one store per game session and every screen
  that edits it is handed that instance, because a second store would mean a save in the
  browser and a question in the game disagreeing about what the persona is.
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
* **The web console is the opposite, deliberately.** It runs until the player stops it,
  because a browser tab whose server vanished is a page that stops working while it is being
  read. The two share their defences and nothing else; **do not change either to match the
  other.**
* **A green Java suite says nothing about whether a page works.** Driving the console in a
  browser found three defects the API tests could not see — a dead observer, two panels that
  could not scroll, and a composer that scrolled itself off-screen. Chromium is available;
  use it. See `.agents/memory/tasks/web-console.md`.
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
