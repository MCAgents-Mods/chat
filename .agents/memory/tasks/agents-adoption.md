---
name: memory-task-agents-adoption
status: in-progress
description: Moving chat's tool calling onto the agents mod — MCP servers as agents tool categories, the answer through ToolAgent, the Chat button on the agents screen in place of chat's G key, and the 0.3.0 release.
---

# Agents adoption

## 2026-10-12

**Goal.** The owner wants one place for tool calling across MCAgents mods. Layer 1, the
agents mod (`MCAgents-Mods/agents`, mod id `mcagents_agents`), now holds it. Chat is layer 2
and stops carrying its own copy, so a later mod reuses the same loop.

**Objective.**
* Chat's MCP tools and its clone tool are registered in agents' `ToolRegistry`.
* Chat's agent answers through agents' `ToolAgent`, which asks the model in three steps:
  categories, then tools, then the answer.
* G opens the agents screen, where chat adds a Chat button.
* Chat is released as 0.3.0.

**Detail.**
* Agents 0.1.0 is published to `https://maven.pkg.github.com/MCAgents-Mods/agents`.
* Chat compiles against it and never bundles it, exactly as it does with core. The
  `mcagents_agents` mod provides it in game.
* Every model request still goes through core's `ModelClient`.

## Decisions

The owner was away and asked for every question to be settled by taking the recommended
option.

* **One category per MCP server, plus `mcp`.**
  * The category id is the server id, sanitized to `[A-Za-z0-9_-]{1,64}`.
  * Each server's tools keep their qualified `{server}__{tool}` names, so the name the model
    calls still routes to the session that owns it.
  * `mcp_clone_repository` sits in the `mcp` category.
* **The registry follows the host.** Tools are registered when the host's registry
  changes, and unregistered when a server goes away. A question reads a snapshot, so a
  server lost mid-question still answers as disconnected rather than unknown.
* **MCP tools run `CONCURRENT`, the clone tool `WAIT`.**
  * Concurrent MCP calls keep chat's rule that calls in one turn run together.
  * The clone tool reloads the host, so it runs alone.
  * Agents' worker limit (default 2, set in game) now bounds how many run at once.
* **Rounds follow the player's agents setting.** That is 1 by default, up to 8, in place of
  chat's fixed 8, as the owner set for every agent.
* **Persona, server context and knowledge stay chat's.** The assembled conversation goes to
  `ToolAgent` as the request's conversation. The newest question is the prompt that steps 1
  and 2 read.
* **Chat gives up its G key.** It registers a Chat button on the agents screen, and its
  manifests require `mcagents_agents` 0.1.0 or newer.
* **Version 0.3.0.** Chat is at 0.2.0, released by its publish thread.

## The task list

| # | Title | Scope | Branch | PR |
|---|---|---|---|---|
| 1 | The record | This file and the memory index | `chore/agents-adoption-plan` | |
| 2 | Tools through agents | Depend on agents, and require `mcagents_agents` in the manifests. Register the MCP and clone tools in agents' registry, and answer through `ToolAgent`. Delete `ToolBroker`, `ToolOutcome` and `McpToolRouter`. Update the tests and docs. | `refactor/agent-tools` | |
| 3 | Chat button | Drop chat's G key and register the Chat button on the agents screen | `feat/agents-button` | |
| 4 | Release 0.3.0 | Version, changelog, logs index, state, and close this record | `release/0.3.0` | |
