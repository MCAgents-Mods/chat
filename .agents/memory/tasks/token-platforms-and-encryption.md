---
name: memory-task-token-platforms-and-encryption
description: Platform-aware, encrypted token storage and direct platform routing — the plan, the rule that was set aside, the decisions not to relitigate, and what is proven.
---

# Tokens, Platforms, and Direct Routes

## 2026-08-30

**Goal.** Apply the shared `{platform}/{model}` naming convention to every stored model
identifier, let the mod call a platform's own API directly instead of only through
OpenRouter, record which platform each stored token belongs to, and encrypt tokens at rest
with a key taken from the environment.

**Objective.** A player can store a key for a named platform and have questions for that
platform's models go straight to it; a player who configures nothing keeps exactly the mod
they had; and a copy of `data.db` taken off a machine with an encryption key set discloses
no token, with the same token encrypting differently every time it is written.

**Detail.** The platform is one added column, never a rebuilt table. Every path keeps a
fallback: an unqualified model id, a platform with no address, a platform with no key, and a
machine with no encryption key all behave as they did before. The encryption key comes from
`MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY` and from nowhere else.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents/client-fabric` | `chore/token-platforms-and-encryption-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | Model naming | `{platform}/{model}` as a type, applied before every write | `MCAgents/client-fabric` | `feat/model-naming` | `api.model` | |
| 3 | The token cipher | AES-GCM, a random nonce per write, key from the environment | `MCAgents/client-fabric` | `feat/token-cipher` | `common.security` | |
| 4 | Platform column and encryption at rest | The added column, the migration, the storage boundary | `MCAgents/client-fabric` | `feat/token-storage` | `api.model`, `api.storage`, `common.storage.sqlite` | |
| 5 | Direct routes | Resolving a route, and what changes on the wire | `MCAgents/client-fabric` | `feat/direct-routes` | `api.agent`, `common.agent` | |
| 6 | The console | Choosing a platform, configuring an address, disclosing encryption | `MCAgents/client-fabric` | `feat/console-platforms` | `website/` | |
| 7 | The game screen | Choosing a platform where a key is pasted | `MCAgents/client-fabric` | `feat/key-screen-platform` | `platforms/fabric/` | |
| 8 | The release | Instructions, both wikis, the changelog, indexes | `MCAgents/client-fabric` | `docs/token-platform-release` | `.agents/`, `wiki/`, `README.md` | |

Ordered by dependency, and stacked that way: task 1 branches from `master`, task `k` from
task `k-1`. Tasks 4 and 5 both need task 2's `ModelName`; task 4 needs task 3's cipher; tasks
6 and 7 need task 4's column; task 8 describes all of them. Nothing here is independent of
what precedes it, which is why nothing here is parallel.

**The `PR` column is filled by task 8**, not by task 1 — pull request numbers do not exist
until every branch is pushed, and back-filling them onto branch 1 afterwards would rebase the
whole stack.

## Decisions not to relitigate

*(each task appends below as it lands)*
