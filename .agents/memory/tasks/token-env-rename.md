---
name: memory-task-token-env-rename
status: open
description: Renaming the token encryption environment variable from MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY to MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY after the repository move.
---

# Renaming the Token Encryption Variable

## 2026-10-11

**Goal.** The variable that carries the key for encrypting stored API keys still named the old
repository, `client-fabric`. The repository is now `MCAgents-Mods/chat`, and the owner chose to
rename it.

**Objective.** `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` is the name the mod reads and every document
states; the old name is read nowhere; the build and tests pass.

**Detail.** The mod has not been published, so no player has set the old name and nothing is
lost by dropping it. The owner confirmed that. No version bump.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents-Mods/chat` | `chore/token-env-rename-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | Rename the variable | The constant, the comment that quotes it, and every document that states the name | `MCAgents-Mods/chat` | `refactor/token-env-rename` | `common/security`, `common/storage/sqlite`, `README.md`, `wiki/`, `.agents/` | |
| 3 | The release | Changelog, the closing entry, and `release/0.0.0` moved to the tip | `MCAgents-Mods/chat` | `release/0.0.0` | `wiki/logs/`, this file | |

## Decisions not to relitigate

**The old name is dropped, not kept as a fallback.** A fallback would be code for players who do
not exist yet, and a second name to document. If the mod had been published the answer would be
different: renaming would silently stop decrypting any key stored under the old name.

**Older task records keep the old name.** They say what was true when they were written. The
decision file `token-encryption-at-rest.md` is the one record that is still read as a rule, so
it gets a note about the rename rather than a rewrite.

**The release branch is moved, as before.** `release/0.0.0` stays the one release branch; task 3
adds the release commit on top of task 2 and moves the branch there by fast-forward.

*(each task appends below as it lands)*
