---
name: memory-tasks-code-cleanup
status: in progress
description: Splitting chat's two largest mixed-purpose classes — the wallet bridge's HTTP plumbing and the MCP host's watch loop — with no behaviour change, and releasing 0.15.1.
---

# Code cleanup and command permissions

## 2026-10-30

**Goal.** On 2026-10-30 the owner asked for every mod repository to be cleaned up — optimised
code, one responsibility per file, better structure — and for the commands to be audited, with
a permission level on any command a player should not be able to use. Core goes first (its
record is `code-cleanup.md` in core, released as 0.15.0), then the template and chat. The owner
asked for the work to start right after planning, for open questions to be settled by picking
the recommended option, for the first branch to be `plan/{slug}` and the last
`release/{version}`, and for every branch to be opened as a pull request, rebase-merged in
order and checked in CI. The plan is `plans/code-cleanup.md` in the project's shared files.

**What master looks like (0.15.0, on core 0.14.0).**

- Chat registers no command. Its only privileged actions — cloning an MCP server and running
  one — are already gated by the player's approval in `mcps/mcp_servers.json`, and chat runs on
  the client only, where the player owns the machine. No permission level is needed.
- `LocalWalletBridge` (489 lines) is both the wallet binding flow and generic loopback HTTP
  plumbing: reading a body, decoding a query, writing JSON and errors.
- `WatchingMcpHost` (437 lines) is both the MCP session lifecycle and the file-system watch
  loop that reloads it.

**Decisions.**

- **Split in place, package-private,** so no public type moves and no behaviour changes.
- **No command work in chat:** nothing is registered, and the MCP actions are approval-gated.
- **A patch release, 0.15.1:** refactoring only; chat stays on core 0.14.0.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `plan/code-cleanup` | |
| 2 | Loopback HTTP out of the wallet bridge | `refactor/wallet-http` | |
| 3 | Watch loop out of the MCP host | `refactor/mcp-watcher` | |
| 4 | Release 0.15.1 | `release/0.15.1` | |

### Task 1 — plan/code-cleanup

This record, and its row in the memory index.
