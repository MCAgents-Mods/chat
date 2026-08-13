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
README.md            human overview
LICENSE              MCAgents commercial license, 2026
.agents/             this repository's local instruction set, indexes, agent wiki, memory
wiki/                human documentation and versioned change logs
settings.gradle      module map and plugin repositories
build.gradle         group and version for every module
gradle.properties    project identity only — organization, repository, version
gradle/              the wrapper and libs.versions.toml
api/                 mcagents-fabric-api
common/              mcagents-fabric-common
platforms/fabric/    mcagents-fabric
```

The build works end to end: `./gradlew build` produces a loadable mod jar and
`./gradlew :mcagents-fabric-common:test` runs the storage tests. Storage is implemented;
the OpenRouter client, retrieval, and the interface are not. Do not assume a source file
exists because the architecture mentions it — check.

## The three modules

| Path | Gradle module | Holds today | Will hold |
|---|---|---|---|
| `api/` | `mcagents-fabric-api` | `MCAgents` (identity, on-disk layout), `api.model` (`ApiKey`, `ChatSession`, `ChatMessage`, `ChatRole`, `ContentCategory`, `KnowledgeContent`, `ContentQuery`, `ContentAvailability`, `ModelPreferences`, `ModelCatalog`), `api.storage` (`LocalDatabase` and the four repository interfaces). | The OpenRouter and retrieval contracts. Every I/O method returns `CompletableFuture<T>`. |
| `common/` | `mcagents-fabric-common` | `ClientPaths`, and `common.storage.sqlite` — the SQLite implementation of every storage contract. | The OpenRouter client, embedding and retrieval. No Minecraft on the classpath. |
| `platforms/fabric/` | `mcagents-fabric` | `MCAgentsClientMod` (the `ClientModInitializer`), `client.MCAgentsKeyMappings`, and `client.gui` — the chat screen, its panels, and the model settings screen. | Mixins, context injection, the content browser. |

`settings.gradle` maps the Gradle names to those directories; the directory names and the
module names deliberately differ.

`api/` and `common/` are plain `java-library` modules. Their classes are **merged into the
mod jar** by the `jar` task in `platforms/fabric/build.gradle` rather than nested as
Jar-in-Jar mods — they are not Fabric mods and have no `fabric.mod.json`. Third-party
runtime libraries go the other way: `sqlite-jdbc` is bundled with Loom's `include` and
lands at `META-INF/jars/` in the mod jar. It carries native binaries for every platform, so
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
  database", because that file is the player's whole history.
* **Search text is escaped** before it reaches a `LIKE` clause, so typing `100%` searches
  for that rather than matching everything.
* **Reads of packs go through a cache.** `SqliteDatabase` wraps `SqliteContentRepository`
  in `CachingContentRepository`, so `contents()` is the caching one. The cache is never the
  source of truth — see the section below.
* **The `settings` table was appended to `SqliteSchema`, not inserted.** That list's order
  is the migration history — see [`../../database/schema.md`](../../database/schema.md),
  which is the authority on every rule in this section.

## Caching

`TtlCache` is a bounded LRU with a per-entry lifetime; `CachingContentRepository` decorates
the SQLite repository with it. What matters before changing either:

* **The database is the record.** Everything cached can be dropped at any moment without
  changing what the mod knows, which is what makes it safe to bound and to expire.
* **Writes invalidate, in the same call.** A downloaded pack is visible immediately; expiry
  is a backstop for changes made outside this instance's sight, not the mechanism.
* **Only two things are cached**: a pack by id (the largest rows, read on every question)
  and the category list (tiny, read on every render). Searches and `findStoredVersions` are
  deliberately **not** cached — a search key changes on nearly every keystroke and would
  evict the pack bodies, and a stale version map would re-download or skip a pack.
* **Absence is never cached.** A pack missing now may be downloaded a moment later.
* **The size bound is not decoration.** This runs inside Minecraft; memory taken here is
  memory the game does not have.
* Nothing cached is written to disk. A cache file would be a second copy of player data to
  keep in step, and a second place an API key could end up.

## Where versions live

`gradle.properties` carries the project's identity and nothing else:

```
git-org=MCAgents
git-repo=client-fabric
project-version=0.0.0
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
| Java | 25 (Minecraft 26.2 requires it) |
| Gradle | 9.7.0, via the committed wrapper |
| Group | `io.github.mcagents` |
| Base package | `io.github.mcagents.clientfabric` |
| Version | `0.0.0` |

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
| Run the tests | `./gradlew :mcagents-fabric-common:test` — seconds, no Minecraft needed |
| Compile one module | `./gradlew :mcagents-fabric-common:build` |
| Run the client | `./gradlew :mcagents-fabric:runClient` |
| Produce the mod jar | `./gradlew :mcagents-fabric:build` — output in `platforms/fabric/build/libs/` |

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

`build/`, `.gradle/`, `run/`, `platforms/fabric/build/`, and `platforms/fabric/run/` are
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

Two independent entry points, on purpose:

| Way in | Registered through |
|---|---|
| The `G` key | `KeyMappingHelper`, dispatched on the client tick |
| `/mcagents` | `ClientCommandRegistrationCallback` |

They share no failure mode. A key binding can be rebound, collide with another mod, or fail
to reach the game's key map at all; a command does none of those. **`/mcagents status` is
the diagnostic**: it reports whether storage is opening, ready, or failed, what the key is
currently bound to, and the exact failure if there was one.

**No path may fail silently.** A keypress that does nothing is indistinguishable from a mod
that never loaded, and that is what made the first version undiagnosable in a real game.
Every failure either opens the screen or writes a line in the player's chat, and startup
logs at INFO so `logs/latest.log` answers the question without a debug build.

## Gotchas

* **Offline-first is a hard rule, not a preference.** The central service at
  `mcagents.mcrealms.space` may be unreachable at any time and everything local must keep
  working. See [`../../rules/repository.md`](../../rules/repository.md).
* **Never block the client thread.** No `join()`, no `get()` on a future from render or
  tick code. The game freezes and the player blames the mod.
* **`common/` must not see Minecraft.** If you reach for a `net.minecraft` import there,
  the value belongs in an `api/` contract passed in from the platform module.
* The repository root `AGENTS.md` is the agent entry point. `{minecraft_dir}/MCAgents/AGENTS.md`
  is the *player's* persona file at runtime. They are unrelated files with the same name —
  do not confuse them.
* The shared instruction set resolves through the `lxagents-agents-base` connector. If it
  is unavailable, say so and work from `.agents/` alone; do not reconstruct it.
