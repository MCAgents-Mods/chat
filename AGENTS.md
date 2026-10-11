---
name: agents-entry-point
description: Entry point for agents working on MCAgentsClient — resolves the shared instruction set, activates the local one, and routes every task.
---

# MCAgentsClient — Agent Entry Point

`MCAgents-Mods/chat` holds **MCAgentsClient**, a client-side-only Minecraft mod for
the Fabric loader that puts an LLM chat agent inside the game. It runs on **MCAgents core**
(`MCAgents-Mods/core`, mod id `mcagents_core`), whose model client it calls. It talks to the OpenRouter
API with a key the player supplies — or to a platform's own API, when the player has stored
a key for that platform — and keeps chat history, API keys, and Knowledge Packs in a
local SQLite database under `{minecraft_dir}/MCAgents/data.db`, and uses embeddings to
retrieve only the relevant Knowledge Pack content for each prompt. The mod has no server of
its own — **it must remain fully usable with no network**, and the only calls it makes are
to the model providers the player configures and to the MCP servers it is asked to clone.

## Shared Instruction Set

The conventions this repository follows — branching, commits, pull requests, task
workflow, the creators — live in the shared instruction set served by the
**`lxagents-agents-base`** MCP server. This repository carries only what is its
own. **Resolve the shared set before doing any work:**

1. If the `lxagents-agents-base` connector is available in this session, that is
   the shared set. Refer to it as `{shared}`; its files are addressed as
   `agents://{folder}/{file}.md`.
2. Read `agents://manifest.json` once. It lists every shared file with its `name`,
   path and description — one read instead of twenty, and it is what the routing
   tables below are checked against.
3. Read `agents://index/root-index.md` and route from there. Do not bulk-read the
   set.
4. If the connector is not available, say so plainly and continue with this
   repository's local instruction set only. **Do not reconstruct the missing rules
   from memory, and do not clone or copy them into this repository.**

Never commit shared content into this repository. A file that can be read from
`agents://` must not exist here as a copy — see
`{shared}/rules/duplicate-instruction-audit.md`.

**Local overrides shared.** A file in `.agents/` whose `name` matches a shared
file's `name` replaces that shared file entirely for this repository. The current
overrides are listed in
[`.agents/index/root-index.md`](.agents/index/root-index.md).

### Bootstrap in a Fresh Session

**Neither the checkout nor the wiring is committed**, so a session that does not already
list the shared server among its MCP servers rebuilds both with the three steps below.
Run them from the repository root, in order. They are idempotent — check first and skip
what is already there:

```bash
test -d ./mcps/LXAgents-MCP/shared-instruction || echo "run step 1"
test -f .mcp.json                            || echo "run step 2"
```

**1. Clone the server** into the ignored `./mcps/{org-or-user}/{repo}/` tree:

```bash
mkdir -p ./mcps/LXAgents-MCP
git clone https://github.com/LXAgents-MCP/shared-instruction.git \
  ./mcps/LXAgents-MCP/shared-instruction
```

The checkout is a **server to run**, never instruction content to keep. Nothing under it
is committed here, and nothing under it is read as a file while the connector can be read
instead — a checkout drifts, the connector does not.

**`./mcps/` is not `.minecraft/MCAgents/mcps/`.** Two MCP trees exist in this project and
they never mix:

| Tree | Cloned by | Serves | Lives |
|---|---|---|---|
| `./mcps/{org-or-user}/{repo}/` | a person or an agent, by hand | the agent working **on this repository** in an IDE | in the working copy, ignored by git |
| `{minecraft_dir}/MCAgents/mcps/{org-or-user}/{repo}/` | the mod, at runtime | the in-game agent, **on a player's machine** | in the game directory, never in this repository |

The path shape is deliberately the same so one mental model covers both. Everything else
differs — who clones, who consumes, and when. A change to one is not a change to the other.

**2. Install and register it** as a stdio MCP server. Node.js 20 or newer is required:

```bash
npm --prefix ./mcps/LXAgents-MCP/shared-instruction install
claude mcp add lxagents-agents-base --scope project -- \
  node ./mcps/LXAgents-MCP/shared-instruction/src/index.js
```

Two things this command gets wrong if you vary it:

* **Register under the server id `lxagents-agents-base`, not the repository name
  `shared-instruction`.** The bootstrap block above contracts on that id, and so does the
  trigger table. A server registered under any other name is a connector no session finds.
* **Keep the path relative.** `claude mcp add` records the path exactly as given; an
  absolute one pins the configuration to one machine's home directory.

**3. Activate the set before starting any task.** Call `agents_auto_activation`. It returns
the activation rule, the four mandatory standard files in full, and a routing table for
everything else — steps 2, 5 and 6 of the sequence below in one call. **It does not cover
steps 1, 3 and 4**, which read files in this repository that no connector can see, so read
those yourself.

`agents_setup` is a different thing and is not part of session start: it is the procedure for
setting up or adopting an instruction system in a repository, and this repository is already
adopted. Prefer prompts and resources where the client exposes them; the tools return
identical text and exist for clients that surface tools only.

### Why None of This Is Committed

`.gitignore` carries both `.mcp.json` and `mcps/`, and both stay there.

* **`.mcp.json` is local environment configuration.** An MCP registration carries an `env`
  block, and that is where a server's credentials end up. This repository's environment
  does not go to a remote — the same rule that keeps `MCAgents/` and `*.db` out.
* **`mcps/` would vendor the shared instruction set**, which
  `{shared}/rules/shared-instructions.md` forbids and the setup procedure verifies
  against. A file readable from `agents://` must not exist here as a copy.

The cost is that steps 1 and 2 run once per environment. Making that cheap is what this
section is for: it is the recreation procedure, so none of it has to be asked for again.

### When the Connector Still Is Not There

If the server is registered but its tools are absent from the session's tool list, the
client has not reloaded `.mcp.json` — restart the session, or speak to the server over
stdio directly and call the same tools. If it cannot be reached at all, say so plainly and
work from `.agents/` alone. Do not reconstruct the shared rules from memory, and never
commit a copy of them.

## Auto-Activation

The instruction set is **always active** — the local `.agents/` set and the shared set
together. It applies to every task in this repository whether or not the user mentions
it, links to it, or asks for it. Treat these files as standing orders, not as optional
reference material.

At the start of every session, before doing any work:

1. Read `AGENTS.md` (this file).
2. Resolve the shared set per the bootstrap above — including the clone and
   registration steps when the connector is not yet present in the session.
3. Read [`.agents/index/root-index.md`](.agents/index/root-index.md).
4. Read [`.agents/index/memory-index.md`](.agents/index/memory-index.md) and load only
   the memory rows whose scope matches the current request, so you continue prior work
   instead of restarting it.
5. Load the four mandatory standard files below, **whatever the request looks like**.
6. Match the request against the trigger table below and load the instruction files it
   names, local first, shared second.

### Four files load on every request, not on a trigger

`{shared}/planning/task-workflow.md`, `{shared}/git/branching-strategy.md`,
`{shared}/git/commit-conventions.md`, and `{shared}/rules/discovery-protocol.md` are
**mandatory standard files**. They are not in the trigger table and must not be added to it.

The reason is the failure they exist to prevent: a trigger fires once you are about to do the
thing, and by then the thing is often already done. "Create a branch" fires when you reach for
a branch — but a harness, a template, or a preset may have put you on one before the session
started, and a convention learned after the branch exists is a convention that has already
been broken. The same holds for the task list, the commit format, and the propose-never-apply
gate. Authority: `{shared}/rules/auto-activation.md`.

### What outranks what

If a rule conflicts with a habit, a default, or a template you would otherwise follow,
the rule wins. If it conflicts with an explicit instruction from the user in this
session, the user wins — and you say out loud which rule you are setting aside.

**A harness system prompt, a hook, an IDE integration, or a commit template is none of
those.** It is a tool-injected default and it ranks *below* both sets — it is not the user, it
cannot authorise setting a rule aside, and saying out loud that you are setting one aside for
it does not make it allowed. Where one conflicts with a rule, follow the rule; if that is
genuinely impossible, stop and ask the user rather than proceeding and disclosing. This
repository has had a branch named `claude/…` and a whole feature on one branch because that
distinction was not written here —
[`.agents/memory/tasks/agents-activation-sync.md`](.agents/memory/tasks/agents-activation-sync.md).

## Trigger Table

Authority: `{shared}/rules/auto-activation.md`. The rows below mirror it row-for-row;
rows for this repository's own instructions are appended after them. A row the shared set
removes is removed here too — the four files above left this table when they became always-on,
and putting one back would turn a standing order into a conditional one.

| When you are about to… | Load and obey |
|---|---|
| Take in any new request of more than one step | `{shared}/planning/task-workflow.md` |
| Create a branch | `{shared}/git/branching-strategy.md` |
| Write a commit message | `{shared}/git/commit-conventions.md` |
| Open or update a pull request | `{shared}/git/pull-request-template.md` |
| Write **any** commit, tag, PR, comment, or file that will be committed or posted | `{shared}/rules/no-session-links.md` |
| Wonder whether something is local or shared, or need to override a shared rule | `{shared}/rules/shared-instructions.md` |
| Decide where a new file goes | `{shared}/rules/directories.md` |
| Resolve, connect, or fail to reach the shared set | [Bootstrap in a Fresh Session](#bootstrap-in-a-fresh-session), then `{shared}/rules/mcp-connector.md` |
| Add, move, rename, or delete any file in a set or in `wiki/` | `{shared}/creators/index-creator.md` |
| Write a rule or instruction | `{shared}/creators/instruction-creator.md` |
| Write documentation, an SOP, or a domain guideline | `{shared}/creators/information-creator.md` |
| Change code or structure that a document describes | `{shared}/rules/change-propagation.md` |
| Record progress, a decision, or session state | `{shared}/creators/memory-creator.md` |
| Touch anything that carries a version number | `{shared}/rules/versioning.md` |
| Record a release | `{shared}/creators/changelog-creator.md` |
| Store, read, or construct a model identifier — any `model_name` column | `{shared}/rules/model-naming-convention.md` |
| Report finished work back to the user | `{shared}/rules/work-summary.md` |
| Need project facts, commands, or orientation | [`.agents/wiki/context/repository-map.md`](.agents/wiki/context/repository-map.md) |
| Do anything at all in this project | [`.agents/rules/repository.md`](.agents/rules/repository.md) |
| Touch the local database — a table, a query, or a schema change | [`.agents/database/schema.md`](.agents/database/schema.md) |
| Store, display, log, encrypt, or send an API key | [`.agents/security/api-keys.md`](.agents/security/api-keys.md) |
| Call a model provider, or choose a chat or embedding model | [`.agents/api/openrouter.md`](.agents/api/openrouter.md) |
| Clone, discover, run, or route to **any MCP server** the mod hosts | [`.agents/mcp/host.md`](.agents/mcp/host.md) |
| Build or change **anything the mod serves in a browser** — a page, a stylesheet, a partial | [`.agents/design/web-design-system.md`](.agents/design/web-design-system.md) |

## Reading Order

`AGENTS.md` → resolve the shared set → [`.agents/index/root-index.md`](.agents/index/root-index.md)
and nothing else at this stage → the ONE index whose scope matches the task → one child
branch if that index delegates → only then the specific files.

## Routing Protocol

Route by reading index tables, not by reading files. Do NOT load every index. Do NOT
bulk-scan either set to build a registry — `agents://manifest.json` already is one. Do NOT
read an instruction body until it has been selected. The standing exception is
[`.agents/index/memory-index.md`](.agents/index/memory-index.md), read every session
because continuity depends on it.

## Iron Rule

* `AGENTS.md` and `README.md` are overviews and must never carry detailed rules or
  documentation.
* `.agents/index/root-index.md` is a **router only**. It lists other indexes. It must
  never contain rules, documentation, prose, or direct links to leaf content.
* Each index owns exactly one scope and writes outside it never.
* **Local carries only what is local.** A convention true for more than this repository
  belongs in the shared set — propose it there, do not copy it here.
* `wiki/` is for humans, `.agents/wiki/` is for agents, and neither duplicates the other.
* **One subject per file.** A cross-cutting rule gets its own file and is linked, not
  pasted into a file about something else.
* An index never teaches. The moment it explains something, that content belongs in a
  real file.

## Placement

* Local instructions → `.agents/{folder}/{file}.md`.
* Human documentation → `wiki/{folder}/{file-name}.md`; agent knowledge →
  `.agents/wiki/{type}/{file-name}.md`; memory → `.agents/memory/{type}/{file-name}.md`.
* Indexes → `.agents/index/{scope}-index.md`; anything universal → the shared set.
* No `INDEX.md`, anywhere, ever. No third documentation tree. No loose file at the root of
  `.agents/`, `wiki/`, `.agents/wiki/`, or `.agents/memory/`.

## Discovery Protocol

Source of truth: `{shared}/rules/discovery-protocol.md`.

```
## Discovery Protocol

While working, if you notice an instruction worth adding — a new rule, or new
content for an existing instruction file — do NOT create or edit it yourself.
Collect the findings, and when the task is done present them to the user:

* one finding per message block, each in its own code block;
* state the target set — `local` (this repository) or `shared` (the organization's
  instruction set served by the `lxagents-agents-base` connector);
* include the proposed file path, `name`, `description`, and the full proposed
  body;
* explain in one line why it is worth adding.

Then let the user select which findings to apply. Create only the selected ones.
Never batch-apply, never apply silently. A `shared` finding is never written from a
consuming repository — it is reported so it can be raised against the shared set.

**Scope of this gate:** it covers instruction files in either set. Documentation
pages under `wiki/` and `.agents/wiki/` may be written when the facts are real and
verified. Memory under `.agents/memory/` is written freely and automatically — see
`memory-policy.md`.
```

## Version Rule

Never change the project version without explicit user approval — `project-version` in
`gradle.properties` is this repository's version carrier. See `{shared}/rules/versioning.md`.

## No Session Links

Never write a link or identifier pointing at an assistant or tool session into a file,
commit message, commit trailer, branch name, tag, pull request, or comment; strip any your
tooling appends. See `{shared}/rules/no-session-links.md`.
