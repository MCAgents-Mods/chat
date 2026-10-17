---
name: memory-task-compile-only
status: in progress
description: Replacing every implementation dependency with compileOnly (plus a dev-client runtimeOnly line in the unpublished loader modules), so published chat-common no longer asks for sqlite-jdbc, released as a patch.
---

# compileOnly instead of implementation

## 2026-10-17

**Goal.** On 2026-10-17 the owner wrote: "Did you use implement in each mods repo? if yes then
use compileOnly instead […] Check first if true then plan and work immediately." The plan
(`plans/compile-only.md` in the project's shared files) covers core, chat and template, and
the owner had already asked for every branch to be opened as a pull request and rebase-merged
in order.

**What the check found.** Chat never used `implementation` for core: `core-api` and
`core-common` are `compileOnlyApi`, `core-client` is `compileOnly`, and no chat jar carries a
core class. `implementation` was left in two places:

- the loader modules, for Fabric Loader, Fabric API and Forge (which the game provides) and for
  chat's own `api`, `common` and `shared` (which the jar task merges by their outputs, not
  through the configuration);
- `common/`, for sqlite-jdbc, which the loader jars nest themselves but which the published
  `chat-common` therefore also listed as a runtime dependency.

**Decisions.**

- **Everything becomes `compileOnly`.** In the loader modules each line gains a `runtimeOnly`
  twin, because the dev client runs from that class path; plain `compileOnly` stripped Fabric
  Loader and Fabric API from it and broke `runClient` when chat 0.0.0 measured it. The loader
  modules are never published, so `runtimeOnly` reaches the dev client and nothing else.
- **sqlite-jdbc is `compileOnly` in `common/`**, with `testImplementation` for the tests, and each
  loader module adds it to the dev client next to its `include` or `jarJar`.
- **`api` and `compileOnlyApi` stay.** They are compile dependencies between chat's own
  published modules and on core.
- **The jars do not change.** Every entry of the three loader jars is compared before and after.
- **A patch release, 0.10.1**, still on core 0.11.0.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/compile-only-plan` | |
| 2 | compileOnly everywhere | `build/compile-only` | |
| 3 | Release 0.10.1 | `release/0.10.1` | |

### Task 1 — chore/compile-only-plan

This record, and its row in the memory index.

### Task 2 — build/compile-only

`common/` declares sqlite-jdbc `compileOnly`, and `testImplementation` for the tests, which
open SQLite data sources themselves; the generated `chat-common` POM now lists only `chat-api`
and `core-common`. The Fabric, Forge and NeoForge modules declare `chat-api`, `chat-common`,
the shared module and their loader as `compileOnly`, each with a `runtimeOnly` twin, and add
sqlite-jdbc as `runtimeOnly` beside their `include` or `jarJar`. No Gradle file in chat declares
`implementation` any more (`testImplementation` aside). The repository map and the state file
say so. `./gradlew build` is green, and the three loader jars hold exactly the entries (and
sizes) of the v0.10.0 release jars. Chat's own dev clients on Fabric, NeoForge and Forge 26.3
joined a world with core 0.11.0 beside them and opened the local database; in core's dev
clients, chat loaded with the template and G then CHAT opened Chat Sessions on all three.
