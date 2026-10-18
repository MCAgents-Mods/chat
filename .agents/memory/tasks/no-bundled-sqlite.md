---
name: memory-task-no-bundled-sqlite
status: done
description: Taking sqlite-jdbc out of the Fabric, NeoForge and Forge mod jars, declaring it compileOnly and dev-client runtimeOnly only, released as a minor version.
---

# No bundled sqlite-jdbc

## 2026-10-18

**Goal.** On 2026-10-18 the owner wrote, as Task 2 of two: "The current compiled jar is too
large (over 11MB) because `sqlite-jdbc` is being bundled. Remove the `include libs.sqlite.jdbc`
directive in the Fabric build script. Remove the `jarJar libs.sqlite.jdbc` directive in the
NeoForge and Forge build scripts. Ensure that `libs.sqlite.jdbc` is only declared using
`compileOnly` (for compilation) and `runtimeOnly` (so it remains available for the dev client
environment)." It starts after Task 1's release, 0.13.2 ([`flat-platforms.md`](flat-platforms.md)).
The plan is `plans/no-bundled-sqlite.md` in the project's shared files; the owner had already
asked for every branch to be opened as a pull request and rebase-merged in order.

**What master looks like.** Each loader module nests sqlite-jdbc 3.53.2.1 (11,982,884 bytes,
nearly all of it native libraries for 22 platforms): Loom's `include` in the Fabric jar,
`jarJar` in the NeoForge jar, and Forge's Jar-in-Jar plugin, which builds the Forge jar a second
time with it nested. The v0.13.1 jars are 12,309,311 (Fabric), 12,306,583 (NeoForge) and
12,307,313 (Forge) bytes. Core and template declare no sqlite-jdbc.

**Decisions.**

- **Chat only.** Core and template have nothing to change.
- **Exactly as asked.** No jar carries sqlite-jdbc; `common/` keeps `compileOnly` and
  `testImplementation`, each loader module keeps the dev client's `runtimeOnly`. The owner was
  told that no Minecraft or loader install provides SQLite, so a game must supply it.
- **Forge loses its Jar-in-Jar plugin**, which existed only for sqlite-jdbc: the plain `jar`
  task builds the player's jar again, and the catalog drops `forge-jarjar`.
- **A missing driver is named plainly.** A new `StorageState.NO_SQLITE` makes the holograms say
  "SQLite is not installed, so chat cannot save anything." instead of a cut-off class name.
- **A minor release, 0.14.0**, still on core 0.13.0: a game without SQLite now cannot store data.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/no-bundled-sqlite-plan` | #84 |
| 2 | Stop bundling sqlite-jdbc | `build/no-bundled-sqlite` | #85 |
| 3 | Release 0.14.0 | `release/0.14.0` | #86 |

### Task 1 — chore/no-bundled-sqlite-plan

This record, and its row in the memory index.

### Task 2 — build/no-bundled-sqlite

The `include` and `jarJar` lines go from `platforms/{fabric,neoforge,forge}/build.gradle`, with
Forge's Jar-in-Jar plugin and its `slim` jar; `ChatRuntime` names a missing driver; the setup
page, the repository map, the Release workflow's header and the state file stop saying the jar
nests SQLite. No loader jar holds `org/sqlite/` or a nested jar, `./gradlew build` is green, the
dev clients open the database, and a game without the driver says so instead of crashing.

### Task 3 — release/0.14.0

`project-version` is `0.14.0`. `wiki/logs/0/14/0/` tells players the jar no longer carries
SQLite; the logs index has its row at the top, and the repository rule, map, state and setup
page name the version. The `PR` column is filled.

Done: `./gradlew build` was green against core's master, and each loader jar held exactly the
entries of the v0.13.2 release jar less `sqlite-jdbc` (Fabric's `META-INF/jars/`, NeoForge's and
Forge's `META-INF/jarjar/`): 359 KB, 357 KB and 357 KB in place of about 12.3 MB. In core's dev
client with chat's jar and no SQLite, Fabric, NeoForge and Forge 26.3 reached a world and CHAT said
"SQLite is not installed, so chat cannot save anything."; a plain sqlite-jdbc jar in `mods/` was
skipped as not a mod on Fabric and NeoForge. Chat's own dev clients opened the database on all
three loaders.
