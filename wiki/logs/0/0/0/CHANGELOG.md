# 0.0.0

Initial repository scaffold — the conventions, documentation, licensing, and build the
project is developed under.

## Added

* **Multi-module Gradle build.** `mcagents-fabric-api` (`api/`),
  `mcagents-fabric-common` (`common/`), and `mcagents-fabric` (`platforms/fabric/`), wired
  by `settings.gradle` and built with Fabric Loom 1.17.19 on Gradle 9.7.0 and a Java 25
  toolchain. `./gradlew build` produces a loadable mod jar carrying all three modules'
  classes, `fabric.mod.json`, and the license.
* **Version catalog.** `gradle/libs.versions.toml` pins Minecraft 26.2, Fabric Loader
  0.19.3, Fabric API 0.157.0+26.2, Fabric Loom 1.17.19, sqlite-jdbc 3.53.2.1, and Java 25.
  `gradle.properties` carries the project's identity only.
* **First classes.** `MCAgents` — mod identity and the layout it owns on disk;
  `ClientPaths` — resolves that layout from a game directory it is handed, with no
  knowledge of Minecraft; `MCAgentsClientMod` — the `ClientModInitializer`, which resolves
  the game directory once at startup and contacts nothing over the network.
* **Local storage.** The whole SQLite layer at `{minecraft_dir}/MCAgents/data.db`:
  * `api/` — the `ApiKey`, `ChatSession`, `ChatMessage`, `ContentCategory`,
    `KnowledgeContent`, and `ContentQuery` records, and the `LocalDatabase`,
    `ApiKeyRepository`, `ChatRepository`, and `ContentRepository` contracts. Every method
    that touches storage returns a `CompletableFuture`, with no synchronous variant.
  * `common/` — the SQLite implementation, on one connection driven by one daemon thread,
    with the schema applied idempotently at startup, foreign keys enforced so a deleted
    conversation takes its messages with it, and search text escaped before it reaches a
    `LIKE` clause.
  * `platforms/fabric/` — the mod opens the database asynchronously and carries on if it
    cannot; a player whose storage is unavailable keeps a working game and gets a logged
    explanation.
  * 33 tests in `common/`, run with `./gradlew :mcagents-fabric-common:test`.
* **`org.xerial:sqlite-jdbc` 3.53.2.1**, bundled into the mod jar with Loom's `include` so
  the mod stores data on a machine that has never been online.

* **Agent instruction system.** Root `AGENTS.md` as the entry point, with the
  `lxagents-agents-base` connector bootstrap, the auto-activation contract, and the trigger
  table. The shared instruction set is resolved over the connector and never copied here.
* **Local instruction set.** `.agents/rules/repository.md` — the offline-first mandate, the
  three-module boundary, the rule that every I/O method returns `CompletableFuture<T>`,
  local data handling, and English as the repository's only language.
* **Index system.** `.agents/index/` with the root router and scope indexes for the
  instruction set, both wikis, memory, and the change logs.
* **Agent knowledge.** `.agents/wiki/context/repository-map.md` — orientation, the target
  platform versions, the commands, the generated paths, and the gotchas.
* **Memory seed.** `.agents/memory/state/repository-state.md` and
  `.agents/memory/tasks/agents-setup.md`.
* **Human documentation.** `wiki/information/overview.md`,
  `wiki/information/architecture.md`, and `wiki/environments/setup.md`.
* **License.** `LICENSE` — the MCAgents commercial license, copyright 2026 MCAgents.
  Proprietary; three categories of authorized licensee; publisher-verification warning for
  third-party distribution platforms.
* **README.** Rewritten as an overview pointing at the wiki.

## Notes

* Group `io.github.mcagents`, base package `io.github.mcagents.clientfabric`.
* Minecraft 26.x publishes no Yarn mappings and no intermediary; Fabric Loom uses Mojang's
  official mappings and **no `remapJar` task runs** — `jar` produces the final artifact.
  Fabric API and the loader are declared with plain `implementation` for the same reason.
* `api/` and `common/` are plain `java-library` modules merged into the mod jar rather than
  nested as Jar-in-Jar mods; they are not Fabric mods and carry no `fabric.mod.json`.
