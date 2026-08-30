---
name: memory-task-agents-activation-sync
status: done
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
| 1 | The record | This file, written before the work is committed | `MCAgents/client-fabric` | `chore/agents-activation-sync-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | #78 |
| 2 | The sync | The outstanding consumer actions, applied to `AGENTS.md` | `MCAgents/client-fabric` | `docs/agents-activation-sync` | `AGENTS.md` | #79 |
| 3 | The release | Changelog and the closing entry on this record | `MCAgents/client-fabric` | `docs/agents-activation-release` | `wiki/logs/` | #80 |

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

### Task 2 — the sync

**Applied `0/8/0`, both halves.** The discovery-protocol trigger row is gone and the always-on
paragraph is in, naming four files. Doing only the first half would have dropped the
propose-never-self-apply gate out of this repository altogether, which is what the release note
warned about.

**Applied `0/12/0`.** The bootstrap now names `agents_auto_activation` as the one call that
covers steps 2, 5 and 6, and says plainly that it does **not** cover steps 1, 3 and 4 — the
three that read files here. It also says what `agents_setup` is, so the two stop being
confused: one activates, the other adopts.

**`0/11/0` was applied in the previous task**, when the `model_name` trigger row was added
after "Record a release". Every outstanding consumer action in the shared logs index is now
applied.

**The conflict paragraph now names the tool-injected default.** It said the user wins and you
disclose; it now also says a harness, hook, IDE integration or commit template is not the user,
ranks below both sets, and that disclosure is not authorisation — stop and ask instead. That
sentence is the one whose absence produced a `claude/…` branch.

**The always-on paragraph is written as the reason, not the list.** A trigger fires when you
are about to do the thing; a branch a harness created exists before the session starts, so a
branch convention that loads on "create a branch" loads too late to apply. That is the whole
argument for four files being exempt from the table, and it is now stated where the mistake
happened rather than only upstream.

**One local row's wording was stale** — "send an OpenRouter API key" — since keys became
per-platform and encrypted. Now "store, display, log, encrypt, or send an API key".

### Task 3 — the release

**Recorded in the changelog**, because an entry point that misstates the activation protocol is
a defect in how the repository is developed, and `0.0.0`'s log already covers the conventions
the project is built under.

**The duplicate audit ran and found nothing.** Five candidates after the exclusions the
procedure names — `.agents/api/openrouter.md`, `.agents/database/schema.md`,
`.agents/design/web-design-system.md`, `.agents/mcp/host.md`, `.agents/security/api-keys.md` —
and every one is local-only: no `name` collision, no body matching a shared `sha256`, no path
mirroring a shared path. The override table in `root-index.md` still correctly reads "no
overrides". Nothing was proposed for deletion, so nothing needed approval.

## What is proven, and what is not

**Proven.** Every "Consumers must" row in the shared logs index is applied: `0/8/0` in both
halves, `0/11/0` last task, `0/12/0` here. The trigger table mirrors the shared table row for
row, with this repository's five local rows appended after them. The audit is clean.

**Not proven.** That the next session actually behaves differently. The change makes the rule
*reachable* before the work starts, which is the part that was missing; it cannot make a
session read what it is given. The honest test is the next multi-task request in this
repository, and whether its branches come out named correctly without anyone asking.

**Observed, not changed.** The `mcp-connector` trigger row points at this file's own bootstrap
section before the shared file, which is a modified mirrored row rather than a clean one, and
no override is declared for it in `root-index.md`. The shared file is still loaded, so nothing
is lost today. Either declare it as an override or restore the plain row — a decision for the
user, not a silent edit.

## Closed

Merged into `master` in order, tasks 1 to 3, as pull requests #78 to #80, after the eight of
the token chain below them. Each pull request was re-targeted to `master` before it was merged
rather than after: a forge only re-targets a stacked pull request when its base branch is
deleted on merge, and where that setting is off the merge succeeds into the branch below while
the default branch silently stays behind.
