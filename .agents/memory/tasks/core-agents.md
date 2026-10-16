---
name: memory-task-core-agents
status: in-progress
description: Moving chat onto core alone once the agents mod became part of MCAgents core — tools, the agent and the menu buttons from core's packages, the agents dependency and manifest entries dropped — and releasing it as a minor.
---

# Chat on core alone

## 2026-10-16

**Goal.** On 2026-10-16 the owner wrote: "Merge code in repo agents to core. Right now we have
two core mods. So we will make it have only one. After merge then update repo chat too. I
will delete repo agents after work finish." Core takes everything the agents mod did (its
`agents-merge` task record): the tool module (`core-tool`), the agent and the menu buttons
(`core-api`, `core-common`), and the menu G shows (`core-client`). Chat follows here. The
owner was away and had asked for the recommended option to be taken on every question, and
for every branch to be opened and rebase-merged in order. The plan is
`plans/agents-into-core.md` in the project's shared files.

**What changes in chat.** Every class keeps its name; only the package it is imported from
changes: `io.github.mcagentsmods.agents.tool` becomes `…core.tool`, `…agents.api.agent`
becomes `…core.api.agent`, `…agents.api.button` becomes `…core.api.button`, and
`…agents.common` and `…agents.common.agent` become `…core.common.agent`. `agents-common` and
the composite build of a `../agents` checkout leave the build. `core-tool` needs no
substitution of its own: chat names only `core-api`, `core-common` and `core-client`, and they
bring it. The Fabric, NeoForge and Forge manifests stop requiring
`mcagents_agents` and require the core release that carries it.

**Decisions.**

- **Core alone, no agents fallback.** Core's manifests refuse `mcagents_agents`, so chat
  cannot ask for both.
- **A minor release:** the mods a player installs change.
- **Instruction files left stale are findings,** not edits: the repository rule and the
  OpenRouter and MCP host instructions that name the agents mod are reported to the owner.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/core-agents-plan` | |
| 2 | Tools, agent and buttons from core | `refactor/core-agents` | |
| 3 | Release | `release/{version}` | |

### Task 1 — chore/core-agents-plan

This record, and its row in the memory index.

### Task 2 — refactor/core-agents

Nine classes import the tool registry, the agent and `AgentButton` from core's packages;
nothing else in them changes. `settings.gradle` no longer builds a `../agents` checkout,
`libs.versions.toml` drops `mcagents-agents` and `agents-common` and names core 0.10.0, the
first with tool calling, and `common/` compiles and tests against core alone. The root
`build.gradle` asks only core's GitHub Packages registry, for `core-*`. The three loader
manifests, and the `processResources` blocks that fill them, no longer name `mcagents_agents`.
The Publish and Release headers, the README, the setup and architecture pages, the MCP servers
page, the repository map and the state file say tools come from core, and the setup and
architecture pages say what changed after 0.8.0.
