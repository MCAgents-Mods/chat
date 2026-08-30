---
name: memory-task-claude-md-import
description: Making Claude Code read this repository's AGENTS.md through an import rather than a copy — the plan, and why the file is one line of content.
---

# The CLAUDE.md Import

## 2026-08-30

**Goal.** Make Claude Code load this repository's instructions without giving the repository a
second set of them.

**Objective.** `.claude/CLAUDE.md` exists, imports the root `AGENTS.md`, and carries no
instruction content of its own, so the two files cannot disagree.

**Detail.** The file's contents were supplied by the repository owner and are used verbatim.
Nothing is added to it, and nothing from `AGENTS.md` is pasted into it.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents/client-fabric` | `chore/claude-md-import-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | The import | The file itself, and the structure page that lists what exists | `MCAgents/client-fabric` | `docs/claude-md-import` | `.claude/`, `.agents/wiki/context/repository-map.md` | |
| 3 | The release | Changelog and the closing entry on this record | `MCAgents/client-fabric` | `docs/claude-md-release` | `wiki/logs/` | |

## Decisions not to relitigate

**One line of content, and a comment explaining why.** Claude Code reads `CLAUDE.md`; every
other agent reads `AGENTS.md`. An import means both read the same file. A copy would be a
second source that goes stale and then quietly overrides the original — the same failure the
shared set's duplicate-instruction audit exists to catch, arriving through a different door.

**The import path is `../AGENTS.md`, not `AGENTS.md`.** An import resolves relative to the file
containing it, so from `.claude/` a bare `@AGENTS.md` would name `.claude/AGENTS.md`, which
does not exist. This is the kind of thing that fails silently — no file, no error, no
instructions — which is why the reason is written into the file rather than left to be
rediscovered.

**`.claude/` is not ignored by git**, unlike `.mcp.json` and `mcps/`. Those two are excluded
because they carry environment and vendored server content; this file carries neither. It is a
committed pointer, and it has to be committed to be any use to the next person who clones the
repository.

*(each task appends below as it lands)*
