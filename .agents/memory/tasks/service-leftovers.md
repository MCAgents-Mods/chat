---
name: memory-task-service-leftovers
status: done
description: Removing the code the central service client left behind, ignoring the plans folder, and moving the release branch back to the tip of the stack.
---

# Service Leftovers

## 2026-10-11

**Goal.** Removing the central service client left code that nothing calls and that only
existed to support it. The owner asked for it to go, and for `.agents/plans/` to be ignored by
git.

**Objective.** `HttpCall.get`, the GET branch in `JdkHttpTransport`, and
`ContentRepository.findStoredVersions` — in the interface, both implementations, the tests and
the document that names it — are gone; `.gitignore` carries `.agents/plans/`; the build and
tests pass; and `release/0.0.0` is the tip of the stack again.

**Detail.** Kept on purpose: `HttpTransport`, `HttpReply` and `JdkHttpTransport` (OpenRouter and
the MCP HTTP session use them) and `saveCategory`/`save` (the pack store still needs a category
before it can hold a pack). No version bump.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents-Mods/chat` | `chore/service-leftovers-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | #8 |
| 2 | Ignore plans | `.agents/plans/` in `.gitignore`, verified with git itself | `MCAgents-Mods/chat` | `chore/ignore-plans` | `.gitignore` | #9 |
| 3 | Remove the leftovers | The unused GET path and `findStoredVersions`, with their tests and the document naming it | `MCAgents-Mods/chat` | `refactor/service-leftovers` | `common/agent`, `api/storage`, `common/storage`, tests, `.agents/wiki/context/` | #10 |
| 4 | The release | Closing entries and the changelog, and `release/0.0.0` moved to the tip | `MCAgents-Mods/chat` | `release/0.0.0` | `wiki/logs/`, the three records | #11 |

## Decisions not to relitigate

**One release branch, moved forward.** The version stays `0.0.0` until the owner bumps it, so a
second release branch has no name to take. `release/0.0.0` was the end of the repository-move
chain, and the central-service work stacked on top of it, which left it in the middle. Task 4
adds the release commit on top of task 3 and moves `release/0.0.0` there, so it is the tip and
holds everything. The new commits descend from the pushed branch, so pushing it is a
fast-forward and never a force-push. This bends the rule that a branch is never reused across
tasks; the owner approved it.

**The plans folder is ignored by the owner's instruction.** The planning workflow requires the
ignore rule and says never to add it unasked; the owner asked.

*(each task appends below as it lands)*

### Task 2 — chore/ignore-plans

**Landed.** `.gitignore` carries `.agents/plans/`, with a comment saying why. Checked with
`git check-ignore -v .agents/plans/tasks.md`, which printed the matching rule, and with
`git status`, which did not list a file created there; the scratch file was removed afterwards.
The rule was checked with git itself rather than by reading the pattern, as the planning
workflow asks.

**Not done.** No plan files were written: the task records carried the plan for this work, and
the working-plan folder is optional scratch. The folder does not exist.

### Task 3 — refactor/service-leftovers

**Landed.** Removed `HttpCall.get` and the GET branch of `JdkHttpTransport` — the transport now
sends every call with its own method and body — and `ContentRepository.findStoredVersions` from
the interface, `SqliteContentRepository` and `CachingContentRepository`, with the three tests
that covered it, the stub's copy of it, the unused imports they left, and the sentence in the
repository map that said it was deliberately not cached.

**Proven.** `./gradlew build` passes for all four modules with the tests. A search of the
sources, the documents and the pages for `findStoredVersions` and `HttpCall.get` finds nothing
outside the historical records.

**Kept.** `HttpTransport`, `HttpReply`, `JdkHttpTransport`, and `saveCategory`/`save`; see the
decisions above.

### Task 4 — release/0.0.0

**Landed.** `release/0.0.0` was moved forward by fast-forward from the end of the repository-move
chain to the end of this one, so it is the tip and holds all three chains' work: the repository
move, the central service removal, and these leftovers. Nothing was force-pushed or rewritten.
The release commit on top corrects one line of the `0.0.0` changelog — it still described the
stored-version map, which no longer exists — and closes this record. The version is unchanged.

## What is proven, and what is not

**Proven.** `./gradlew build` passes on the tip with the tests. `git check-ignore` reports the
plans rule. The only mention of the removed code left in the documents is in the historical
records.

**Not proven.** The mod loading in a running client; nothing in this chain launched Minecraft.

## Closed

Four branches in order — `chore/service-leftovers-plan`, `chore/ignore-plans`,
`refactor/service-leftovers`, `release/0.0.0` — stacked on `docs/central-service-removal`. Opened
as pull requests #8 to #11 (the release commit rides in #11) in one chain of 13 that merges in task order, rebasing the remaining branches onto `master` after each merge.
