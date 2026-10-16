# 0.9.0

Released: 2026-10-16

Chat runs on MCAgents core alone. Core 0.10.0 took in the MCAgents agents mod — the tool
registry, the agent that answers with tools, and the menu G shows — so chat no longer needs the
agents mod, and its jars no longer ask for it.

## Upgrading

- **Install core 0.10.0 or newer** beside chat, for the same loader, and **remove the
  MCAgents agents jar**. Core 0.10.0 refuses to load beside it, because core now does what it
  did.
- **Nothing else moves for a player.** The menu is still on `G`, chat's five buttons are still
  on it, and the agents settings file stays at `MCAgents/agents/settings.properties`.
- **Building chat needs only a core checkout** at `../core`, or core's GitHub Packages
  registry. See [`wiki/environments/setup.md`](../../../../environments/setup.md#mcagents-core).

## Changed

- The tool registry, the agent and `AgentButton` come from core's
  `io.github.mcagentsmods.core.tool`, `core.api.agent`, `core.api.button` and
  `core.common.agent` packages.
- Built against MCAgents core 0.10.0, read as `core-api`, `core-common` and `core-client`.

## Removed

- The dependency on MCAgents agents: `agents-common`, the `../agents` composite build, the
  agents GitHub Packages registry, and `mcagents_agents` in the Fabric, NeoForge and Forge
  manifests.
