---
name: memory-tasks-agents-setup
description: Record of the agent instruction system setup for MCAgentsClient — mode, decisions, branch, and what was proposed but not created.
---

# Task — Agent Instruction Setup

**Status:** complete · **Branch:** `docs/agents-setup` · **Base:** `master`

## Goal

Adopt the LXAgents shared instruction set in `MCAgents/client-fabric`, create the local
instruction, knowledge, and memory system, and add the project's commercial license.

## Mode

**B — Consumer.** The shared set is served by the `lxagents-agents-base` MCP connector and
resolved at session start. Nothing shared is vendored; `git/`, `planning/`, `prompts/`, and
`creators/` do not exist locally.

## What was created

* `AGENTS.md` — entry point, connector bootstrap, auto-activation contract, trigger table,
  reading order, routing protocol, iron rule, placement, discovery protocol, version rule,
  no-session-links rule.
* `.agents/index/` — `root-index`, `agents-index`, `agent-wiki-index`, `project-wiki-index`,
  `memory-index`, `logs-index`.
* `.agents/rules/repository.md` — offline-first mandate, module boundaries, the async I/O
  rule, local data handling, English-only, version carrier.
* `.agents/wiki/context/repository-map.md` — orientation filled from real discovery.
* `.agents/memory/state/repository-state.md` and this file.
* `wiki/information/overview.md`, `wiki/information/architecture.md`,
  `wiki/environments/setup.md`, `wiki/logs/0/0/0/CHANGELOG.md`.
* `LICENSE` — MCAgents commercial license, holder `MCAgents`, year `2026`.
* `README.md` rewritten as an overview.

## Decisions

| Decision | Value | Source |
|---|---|---|
| License | Proprietary commercial EULA, holder `MCAgents`, year `2026` | User, explicitly |
| Initial version | `0.0.0` | User, explicitly |
| Default branch | `master` | Repository remote |
| Instruction set | Mandatory core only | Procedure §1.5 — the file selection was not put to the user because the user asked for immediate execution |

## Rules deliberately set aside

The user instructed one branch per task with strict `{type}/{primary-noun}` naming and no
generated suffix, which conflicts with the harness-assigned branch
`chore/project-setup-licensing-22g57m`. The user's instruction wins; the assigned branch is
not used. The §1.5 selection gate was also skipped on the user's explicit instruction to
work immediately — extra instruction files were reported as discoveries instead of created.

## Proposed but not created

Reported to the user as discovery-protocol findings, all `local`:
`.agents/architecture/module-boundaries.md`, `.agents/database/schema.md`,
`.agents/api/openrouter.md`, `.agents/security/api-keys.md`,
`.agents/testing/strategy.md`.
