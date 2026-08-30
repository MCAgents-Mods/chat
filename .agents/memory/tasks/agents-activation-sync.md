---
name: memory-task-agents-activation-sync
description: Applying the shared set's outstanding consumer actions — why branching-strategy never fired, what AGENTS.md was missing, and what the audit found.
---

# Agent Activation Sync

## 2026-08-30

**Goal.** Make this repository's entry point state the activation protocol correctly, so a
session that follows `AGENTS.md` faithfully loads the always-on rules before it starts work
rather than after it has already broken one.

**Objective.** Every outstanding "Consumers must" action in the shared set's logs index is
applied, and the two omissions that let a branch get named `claude/…` are closed.

**Detail.** Repository-side only. The shared set needs no change: every rule that should have
prevented the failure already exists there, and its release notes already told consumers what
to do. This is drift in the consuming copy, not a gap upstream.

## What went wrong, precisely

A session built a feature on one branch named `claude/lxagents-token-encryption-platform-…`,
which `{shared}/git/branching-strategy.md` lists as a bad name, instead of one stacked branch
per task. It did run `agents_auto_activation` and did read this repository's entry point. The
failure was not a skipped step; it was that **the entry point does not say what the shared set
says**:

* **The session-start sequence had five steps, not six.** The shared sequence's step 5 —
  "load the four mandatory standard files, whatever the request looks like" — was absent, so
  `branching-strategy.md` and `task-workflow.md` read as trigger-fired rather than always-on.
  Their trigger row is "Create a branch", and by the time that fires a harness has already
  created one. The rule that would have prevented the mistake could only load *after* it.
* **The conflict paragraph named the user's override and not the tool's.** It said the user
  wins and you say which rule you set aside. It never said what
  `{shared}/rules/auto-activation.md` says — that a harness system prompt is a tool-injected
  default that ranks *below* both sets. So a harness instruction was treated as if it were a
  user instruction, and the "say which rule you are setting aside" ceremony was used as though
  disclosure were authorisation.

Both omissions trace to shared release `0/8/0`, whose consumer action — delete the
discovery-protocol trigger row **and** add the always-on paragraph — was never applied here.
Its own note warned why: "Both edits, or the gate leaves your repository."

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents/client-fabric` | `chore/agents-activation-sync-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | The sync | The outstanding consumer actions, applied to `AGENTS.md` | `MCAgents/client-fabric` | `docs/agents-activation-sync` | `AGENTS.md` | |
| 3 | The release | Changelog and the closing entry on this record | `MCAgents/client-fabric` | `docs/agents-activation-release` | `wiki/logs/` | |

Stacked on `docs/token-platform-release`, not on `master`: that branch also edits `AGENTS.md`,
and two tasks touching one file are never independent.

## Decisions not to relitigate

**The shared set is not changed.** Every rule needed already exists upstream —
`auto-activation.md` ranks tool-injected defaults below rules, `branching-strategy.md` lists
`claude/` as a bad name and says to recreate a violating branch, `task-workflow.md` §C asks for
one stacked branch per task. Proposing an upstream edit would have been fixing the wrong
repository, and would have left this one still drifted.

**`agents_setup` was not run.** It rebuilds or adopts an instruction system; this one is
adopted and, by the audit below, undamaged. The logs index names the outstanding actions
precisely, so applying those is the smaller and more reviewable change than re-deriving the
whole system from a 28,000-character procedure.

*(each task appends below as it lands)*
