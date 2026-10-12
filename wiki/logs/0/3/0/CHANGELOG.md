# 0.3.0

Released: 2026-10-12

Chat runs on MCAgents agents. Tool calling is the agents mod's now: chat's MCP tools are
registered there, one category per server, and chat answers through its agent, which picks the
servers a question needs, then their tools, then answers. Chat gives up the `G` key; `G` opens
the agents screen, and chat is its **Chat** button.

## Upgrading

* **Players: install MCAgents agents.** `MCAgentsChatFabric-0.3.0.jar` and
  `MCAgentsChatNeoforge-0.3.0.jar` need MCAgents agents `0.1.0` or newer beside MCAgents core
  `0.5.0` or newer, each for the same loader; without it the game refuses to load chat. Data
  under `{minecraft_dir}/MCAgents/` is unchanged.
* **Players: press `G`, then choose Chat.** `G` opens the agents screen. A key rebound to open
  chat in an earlier version is gone; rebind the agents screen's key under Options, Controls
  instead.
* **Players: one round of tool calls by default.** A question gets one round of tool calls,
  where 0.2.0 allowed eight. Raise it in the agents screen's **Settings**, up to eight.
* **Building chat without checkouts beside it** reads agents from
  `https://maven.pkg.github.com/MCAgents-Mods/agents` as well as core, with the same token.
  `../agents` beside `../core` needs neither.
* **Code using chat's tool types: move to agents'.** `api.agent.ToolBroker`,
  `api.agent.ToolOutcome` and `common.mcp.McpToolRouter` are gone. Register tools in
  `Agents.tools()` and answer through `Agents.agent(client)`, from
  `io.github.mcagentsmods:mcagents-agents-common`.

## Added

* **`McpTools`**, which registers every MCP server's tools in the agents registry, in a
  category named after the server, and keeps them in step with the host as servers connect,
  change and go. A server's tools run together when the model asks for several at once.
* **The Chat button** on the agents screen, added first thing in each loader's start-up.

## Changed

* `OpenRouterAgent` still assembles the persona, where the player is, the earlier turns and
  the Knowledge Packs, and hands that conversation to the agents mod's agent, which picks
  tools by the newest question.
* `mcp_clone_repository` is an agents tool in its own category, `mcp`, run alone, with four
  minutes to clone and reload.
* Both manifests require `mcagents_agents` at the version chat was built against.
* The Fabric development client loads `platforms/shared` as part of the mod, so the screens'
  strings are translated there as they are in the jar.

## Fixed

* **A failed answer's reason stays on the chat screen.** The screen's own writes looked like a
  change from the web console, so the next tick reloaded the conversation and cleared the
  reason: a player with no key saw their question and nothing else. It now reads "No usable
  OpenRouter API key. Add one with the key button."

## Removed

* Chat's own `G` key, `MCAgentsKeyMappings`, and its `key.mcagents.open_chat` strings.
* `ToolBroker`, `ToolOutcome`, `McpToolRouter`, and chat's own tool loop with its fixed eight
  rounds.
