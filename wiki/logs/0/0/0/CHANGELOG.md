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
* **The chat screen**, opened with `G`:
  * a session list on the left with a search box and a "new chat" button, a conversation
    on the right, and a prompt box with Send along the bottom;
  * an animated `Waiting.` / `Waiting..` / `Waiting...` state that runs while a
    `CompletableFuture` is pending and stops when it settles, whether it succeeded or
    failed;
  * results applied on the client thread through `Minecraft#execute`, and discarded if the
    player has closed the screen in the meantime;
  * the model call behind the `Agent` contract, so the screen never knows about models,
    keys, or retries — only that it asked something and an answer will arrive.
* **Model selection.** A `settings` table appended to the schema, `SettingsRepository`,
  `ModelPreferences`, and `ModelCatalog`. The settings screen is reached from the chat
  screen and offers both a chat model and an embedding model through `EditableDropdown` — a
  text field with a suggestion list attached, so the player can pick a known model **or type
  any id at all**. The lists are suggestions, never a whitelist: a model the mod has not
  heard of is stored as typed, and a blank field resolves to the catalog default rather than
  to no model.
* **The OpenRouter client.** Contracts in `api.agent`, implementation in `common.agent`
  behind a swappable `HttpTransport`. Rotates past a key the provider rejects — including
  402 out of credit — so another key still serves the turn. Retries rate limits, provider
  failures, and transport failures with jittered backoff; never retries a bad request, and
  never retries a malformed 200, because that request was already billed.
* **Prompt caching.** Two `cache_control: ephemeral` breakpoints per request: after the
  persona, and after the newest completed answer. Retrieved Knowledge Pack content is kept
  out of the cached prefix and travels with the question instead — putting it first would
  invalidate the whole conversation behind it on every turn. `usage.include` is requested so
  `cachedTokens` reports what was actually reused.
* **A local embedding cache**, since the same text and model always give the same vector.
  Completions are deliberately not cached: asking again should be able to answer differently.
* **`en_us` translations** for every string the screens show.
* **A cache layer.** `TtlCache` — a bounded LRU whose entries also expire — behind the
  `Cache` contract, and `CachingContentRepository` decorating the SQLite repository. Pack
  bodies and the category list are cached because they are read far more often than they
  change; searches, the stored-version map, and absent packs deliberately are not. Writes
  invalidate in the same call, so a downloaded pack is visible immediately rather than when
  a timer expires. The database stays the record: everything cached can be dropped at any
  moment without changing what the mod knows.
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
* Dependency scopes, measured rather than assumed. In Loom, `implementation` bundles
  nothing — only `include` does. Fabric Loader and Fabric API are `implementation` because
  `compileOnly` would strip them from `runtimeClasspath` and break `runClient` in
  development, while producing an identical shipped jar. Gson is `compileOnly` because
  Minecraft provides it. sqlite-jdbc is the only bundled library, and Fabric has no
  equivalent of Spigot's runtime Maven `libraries:` — Jar-in-Jar is the only option.
* `api/` and `common/` are plain `java-library` modules merged into the mod jar rather than
  nested as Jar-in-Jar mods; they are not Fabric mods and carry no `fabric.mod.json`.
* Minecraft 26.x reworked the client GUI API — `GuiGraphicsExtractor` for `GuiGraphics`,
  `extractRenderState` for `render`, the current screen on `Minecraft#gui`, and input as
  `KeyEvent`/`MouseButtonEvent` records. The full list is in the repository map.
* The chat screen and the model settings screen are compile-verified. Neither has been run
  in a real client, because the environment they were built in has no display.
