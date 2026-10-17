---
name: memory-task-own-modules
status: done
description: Making each loader module declare chat's own submodules with implementation, keeping the loader, Fabric API and core compileOnly, without changing a jar, released as a patch.
---

# Loader modules implement their own submodules

## 2026-10-17

**Goal.** On 2026-10-17 the owner wrote: "Make each mod in the `platforms/` directory implement
its own submodules (e.g., api, common, client, tools). For example, if the chat mod uses the
core mod, it should declare the core mod's package as `compileOnly` while still implementing its
own submodules. […] if a mod uses an external dependency (e.g., Fabric API), use `compileOnly`
for that as well, since it will be provided at runtime." The plan
(`plans/own-modules-implementation.md` in the project's shared files) covers core, chat and
template, and the owner had already asked for every branch to be opened as a pull request and
rebase-merged in order.

**What master looks like.** Since 0.10.1 ([`compile-only.md`](compile-only.md)) the loader
modules declare `chat-api`, `chat-common`, the shared module, Fabric Loader, Fabric API and Forge
as `compileOnly` with a `runtimeOnly` twin. Core is `compileOnlyApi` in `api/` and `common/` and
`compileOnly` in `platforms/shared/`; no loader source uses a core class.

**Decisions.**

- **Chat's own modules become `implementation`** in the Fabric, Forge and NeoForge modules. The
  loader modules are never published, so the scope reaches only the compile class path and the
  dev client's, as the two lines did together.
- **Fabric Loader, Fabric API and Forge stay `compileOnly`**, with the `runtimeOnly` line
  `runClient` needs: the game provides them.
- **Core stays where the code uses it**, compile-only: no loader module gains a core line,
  because no loader source needs one, and the jars carry no core class.
- **sqlite-jdbc is unchanged:** `compileOnly` in `common/`, nested by `include` or `jarJar`.
- **The jars do not change.** Every entry of the three loader jars is compared with 0.13.0's.
- **A patch release, 0.13.1**, still on core 0.13.0.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/own-modules-plan` | #78 |
| 2 | implementation for own modules | `build/own-modules` | #79 |
| 3 | Release 0.13.1 | `release/0.13.1` | #80 |

### Task 1 — chore/own-modules-plan

This record, and its row in the memory index.

### Task 2 — build/own-modules

The Fabric, Forge and NeoForge modules declare `chat-api`, `chat-common` and the shared module
with `implementation` and nothing else; Fabric Loader, Fabric API and Forge stay `compileOnly`
with the `runtimeOnly` line `runClient` needs, and sqlite-jdbc keeps its `include` or `jarJar`
and dev-client `runtimeOnly`. No loader module declares core. The repository map and the state
file say so. `./gradlew build` is green against core's `build/own-modules`, and the three loader
jars hold exactly the entries, at the same sizes, of the v0.13.0 release jars.

### Task 3 — release/0.13.1

`project-version` is `0.13.1`, a patch release: a build change, no behaviour change, and jars
with the same entries as 0.13.0's. Chat stays on core 0.13.0. `wiki/logs/0/13/1/` says players
and builders need do nothing; the logs index has its row at the top, and the repository rule,
map, state and setup page name the version. The `PR` column is filled.
