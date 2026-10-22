---
name: memory-task-config-paths
status: in progress
description: Moving every file chat keeps on disk into MCAgents/chat/ — the database, AGENTS.md and the MCP folder — moving a player's old files there on first start, on core 0.14.0, released as 0.15.0.
---

# Chat's files in MCAgents/chat/

## 2026-10-22

**Goal.** On 2026-10-22 the owner asked: "Make mods use this path for configs files.
`%{User}%/path/to/.minecraft/MCAgents/{mod}/`", with `.minecraft/MCAgents/core/` and
`.minecraft/MCAgents/chat/` as the examples, and for the template to produce it by default. Core
does its part first, as 0.14.0 (its record is `config-paths.md` in core); the template follows
chat. The owner asked for the work to start right after planning, for open questions to be
settled by picking the recommended option, and for every branch to be opened as a pull request
and rebase-merged in order. The plan is `plans/config-paths.md` in the project's shared files.

**What master looks like (0.14.0, on core 0.13.0).** `ClientPaths` resolves `{game_dir}/MCAgents/`
itself and keeps `data.db` (with SQLite's `-wal` and `-shm` beside it), `AGENTS.md` and
`mcps/{owner}/{repository}/` with `mcps/mcp_servers.json` loose in it, shared with every other
MCAgents mod. `ChatRuntime` reads core's global model from `AgentsPaths.under(...).modelsFile()`.

**Decisions.**

- **Everything chat owns moves to `MCAgents/chat/`,** not only the files a player edits.
  `mcp_servers.json` lives inside `mcps/`, so moving the config alone would split the catalog
  from the servers it lists, and `data.db` at the root of `MCAgents/` is in no mod's folder.
- **A player's old files are moved on first start,** before anything opens them, with core's
  `ModFiles.moveIfAbsent`: `data.db` and its journal files, `AGENTS.md` and `mcps/`. A file
  already in `MCAgents/chat/` is never overwritten; the old one is then left where it is.
- **Chat calls core's `AgentsPaths.moveLegacyFiles()` before its own `Models.start`,** so the
  global model is read from `MCAgents/core/` even when chat starts before core.
- **Core 0.14.0 is required,** for `ModFiles` and the move; every manifest asks for it.
- **A minor release, 0.15.0:** the files move; their formats and the schema do not change.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/config-paths-plan` | |
| 2 | Chat's files in MCAgents/chat/ | `fix/config-paths` | |
| 3 | Release 0.15.0 | `release/0.15.0` | |

### Task 1 — chore/config-paths-plan

This record, and its row in the memory index.
