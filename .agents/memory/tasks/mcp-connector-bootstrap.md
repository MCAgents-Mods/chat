---
name: memory-task-mcp-connector-bootstrap
description: Making the shared-instruction connector survive a session — the committed registration, the ignored checkout, and why the server id is not the repository name.
---

# MCP Connector Bootstrap

## 2026-08-23

**Goal.** Stop re-prompting the shared-set setup every session. `AGENTS.md` already carried
the connector bootstrap block, but nothing in the repository said *how to make the connector
exist* — so every fresh session began by being told to clone and register it by hand.

**What was added.** A `### Bootstrap in a Fresh Session` subsection under
`## Shared Instruction Set` in `AGENTS.md`, holding the three steps verbatim: clone into
`mcps/{org-or-user}/{repo}/`, `npm install` plus `claude mcp add --scope project`, and
call `agents-setup` before any task. The session-start contract and the trigger table now
point at it.

**Nothing about the wiring is committed.** `.gitignore` carries both `.mcp.json` and
`mcps/`. The first shape of this task tracked `.mcp.json` so a client would reconnect on
its own; the user rejected it, and correctly — **an MCP registration carries an `env`
block, and that is where a server's credentials end up.** It is local environment
configuration, and it belongs with `MCAgents/` and `*.db` on the ignore list rather than
in a remote. It was removed from the branch's history, not merely deleted in a later
commit, so it never landed anywhere.

Committing `mcps/` was never on the table for a different reason: it would vendor the
shared instruction set, which `{shared}/rules/shared-instructions.md` forbids and the setup
procedure verifies against. The clone is **a server to run**, not content to keep.

The price is that the bootstrap runs once per environment instead of zero times. That is
what `AGENTS.md` is for — the point was never to skip the steps, it was to stop having to
ask for them.

**The server id is `lxagents-agents-base`, not `shared-instruction`.** The repository is
named `shared-instruction`; the connector the bootstrap block contracts on is named
`lxagents-agents-base`. It was registered under the repository name first, and `AGENTS.md`
then referred to a connector no session would ever find. Register by the **server id**.

**The path in `.mcp.json` is relative.** `claude mcp add` records the path exactly as
given, so passing an absolute one pins the configuration to one machine's home directory.
Rewritten to `mcps/LXAgents-MCP/shared-instruction/src/index.js` and proven by a container
restart: the connector came back on its own.

**Documentation drifted from the commands that were actually run.** `AGENTS.md` was written
while the server was still registered as `shared-instruction` under an absolute path, and
kept saying so after both were changed — a reader following it verbatim would have
registered a connector nothing looks for. Fixed when `.mcp.json` was un-tracked. The lesson
is narrow and worth keeping: when a documented command is corrected in the shell, the
document is part of the correction.

**Rule set aside, on the user's explicit instruction.** `{shared}/rules/mcp-connector.md`
step 4 says not to clone the shared set into the repository. The user asked for the clone,
and the gitignore keeps it uncommitted, so what the rule actually protects — no vendored
copy in git history — still holds.

**A registered server is not a loaded server.** Tools appear in a session's tool list only
after the client reloads `.mcp.json`. Until then the server can still be driven by speaking
JSON-RPC to it over stdio; that is how the setup procedure was read in the session that
registered it.

**Branch.** `docs/mcp-connector-bootstrap`, off `master`. It was first committed on
`claude/zen-pasteur-zrj3rg` — the harness mandated that name, and the tool-preset prefix is
one `{shared}/git/branching-strategy.md` explicitly forbids. The mistake was letting a
harness default outrank a standing order without saying so; the user caught it. The rule
covers the case: *recreate the branch correctly and delete the wrong one, or present the
options to the user.* Recreated at the same commit, the local branch deleted, the remote
one left in place because this session cannot delete it.

**Two MCP trees, and they are not the same tree.** `./mcps/{org}/{repo}/` holds servers for
the agent working **on this repository** in an IDE — cloned by hand, ignored by git.
`{minecraft_dir}/MCAgents/mcps/{org}/{repo}/` holds servers the **mod** clones at runtime for
the in-game agent, on a player's machine. The path shape is deliberately identical so one
mental model covers both; who clones, who consumes, and when are all different.

This was gotten wrong once, in this task: reading "we can't have mcp repo within our repo"
as a request to move the dev-time clone out of the working copy, and proposing
`~/.mcagents/mcps/` for it. It is not — the dev-time clone belongs at `./mcps/`, and what
must stay out of the repository is the **tracking**, not the directory. `.gitignore` already
does that. The table in `AGENTS.md` exists so the two are never conflated again.

**`./mcps/x` and `mcps/x` are the same path to git** — the `./` is a no-op prefix, matched
by the same ignore rule and resolved to the same destination. The documented form is `./`
because it states "relative to the repository root" out loud, not because it behaves
differently. Verified: `claude mcp add … node ./mcps/…/src/index.js` records the path as
given and the server completes an MCP handshake from it.

**A nested `.git` does sit in the working copy** and that is accepted, not overlooked.
`git add -f` on it would stage a gitlink stub — git warns about exactly this. The ignore
entry is what keeps it unreachable; nothing else has to.

**Task plan.** Three tasks, stacked in dependency order per
`{shared}/planning/task-workflow.md` §C:

| # | Branch | Off | Scope |
|---|---|---|---|
| 1 | `docs/mcp-connector-bootstrap` | `master` | Persistent connector setup in `AGENTS.md` |
| 2 | `feat/mcp-host` | task 1 | Clone tool, `.minecraft/MCAgents/mcps/{org}/{repo}/`, dynamic loading, unified registry |
| 3 | `feat/mcp-routing` | task 2 | Parse LLM tool calls, route to the owning server, format results back |

**Done.** All three branches are pushed. Nothing here is outstanding: the bootstrap is in
`AGENTS.md`, `.mcp.json` and `mcps/` are both ignored, and a fresh session resolves the
connector from the file rather than from a prompt. The one thing this task could not finish
is outside it — `origin/claude/zen-pasteur-zrj3rg` still carries the commit in which
`.mcp.json` was briefly tracked, because this session cannot delete a remote branch. Nothing
sensitive is in it (`"env": {}` and a relative path), but the branch is worth deleting.
